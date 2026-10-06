package leyline.bridge.coord

import forge.ai.ComputerUtilMana
import forge.ai.PlayerControllerAi
import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.cost.Cost
import forge.game.cost.CostPartMana
import forge.game.cost.CostPayLife
import forge.game.mana.ManaCostBeingPaid
import forge.game.player.Player
import forge.game.spellability.OptionalCostValue
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.FinalManaSourcePaymentEntryValue
import leyline.bridge.handoff.FinalManaSourcePaymentValue
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.ManaPaymentDecision
import leyline.bridge.handoff.OptionalActionGate
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.interaction.ConvokeOrImproviseCostPlan
import leyline.bridge.interaction.ConvokeOrImproviseCostPlanner
import leyline.bridge.interaction.candidateRefs
import leyline.bridge.interaction.shouldInclude
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ManaColorMapping
import leyline.bridge.types.ManaCostText
import leyline.bridge.types.toCandidateRefs
import leyline.game.mapping.PromptIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Owns the cost-payment override surface that routes user decisions through
 * [InteractivePromptBridge] rather than Forge's desktop UI.
 *
 * The cost overrides share a common concern (the user resolving a cost) and
 * distinct wiring: stashed decisions from
 * [TargetingHandler][leyline.match.TargetingHandler], shock-land prompts via
 * [OptionalActionGate], convoke/improvise shard resolution.
 *
 * Three overrides stay on `PlayerController` because they must hand
 * `this: PlayerControllerHuman` to a collaborator:
 *
 * - `getCostDecisionMaker` — constructs `CostDecision` with the controller.
 * - `payManaCost` — hands `this` to `PlaySpellAbility.payManaCost`.
 * - `chooseCardsForCost` — trivial delegation to `TargetingCoordinator`.
 *
 * See [leyline.bridge.forge.PlayerController]'s KDoc for the coordinator pattern.
 */
class CostPaymentCoordinator(
    private val bridge: InteractivePromptBridge,
    private val player: Player,
    private val optionalActionGate: OptionalActionGate,
    private val useManualManaPayment: () -> Boolean = { false },
) {
    private val log = LoggerFactory.getLogger(CostPaymentCoordinator::class.java)

    /** Resolution-time energy payment uses the native amount-specific optional prompt. */
    fun confirmEnergyPayment(
        sa: SpellAbility,
        amount: Int,
    ): Boolean =
        optionalActionGate.await(
            hostCard = sa.hostCard,
            defaultOnTimeout = false,
            logContext = "confirmPayment:Energy",
            customPromptId = PromptIds.OPTIONAL_PAY_ENERGY[amount],
            costText = if (amount in PromptIds.OPTIONAL_PAY_ENERGY) null else "$amount {oE}",
        )

    /**
     * Convoke / improvise — prompt for a subset of untapped cards and map each
     * chosen card to a mana cost shard (colored first in WUBRG order, then
     * generic). Empty result means the player tapped no cards.
     */
    fun chooseCardsForConvokeOrImprovise(
        sa: SpellAbility,
        manaCost: ManaCost,
        untappedCards: CardCollectionView,
        artifacts: Boolean,
        creatures: Boolean,
        maxReduction: Int?,
    ): Map<Card, ManaCostShard> {
        val options = untappedCards.map { it.name }
        if (options.isEmpty()) return emptyMap()

        val plan =
            ConvokeOrImproviseCostPlanner.plan(
                optionCount = options.size,
                maxReduction = maxReduction,
                artifacts = artifacts,
                creatures = creatures,
            )
        val request = buildConvokeOrImproviseRequest(sa, manaCost, untappedCards, options, plan)
        val cardList = untappedCards.toList()
        val resolution = bridge.requestManaSourcePayment(request, cardList)
        val selectedCards = resolution.mapNotNull { cardList.getOrNull(it) }
        val exactShards =
            resolution.shards.mapNotNull { selected ->
                val card = cardList.getOrNull(selected.originalOptionIndex) ?: return@mapNotNull null
                val shard = ManaColorMapping.paymentShard(selected.costColor) ?: return@mapNotNull null
                card to shard
            }
        val result =
            if (exactShards.size == selectedCards.size) {
                exactShards.toMap()
            } else if (artifacts) {
                selectedCards
                    .take(manaCost.genericCost)
                    .associateWith { ManaCostShard.GENERIC }
            } else {
                ConvokeShardAssigner
                    .assign(selectedCards, ConvokeShardAssigner.costCounts(manaCost)) { it.color }
                    .toMap()
            }
        val paymentKind = (request.route as? ResolvedPromptRoute.PayCosts)?.descriptor?.manaSourcePayment
        if (paymentKind != null) {
            sa.hostCard?.let { source ->
                bridge.recordFinalManaSourcePayment(
                    FinalManaSourcePaymentValue(
                        kind = paymentKind,
                        sourceForgeCardId = ForgeCardId(source.id),
                        payments =
                            result.map { (card, shard) ->
                                FinalManaSourcePaymentEntryValue(
                                    paymentForgeCardId = ForgeCardId(card.id),
                                    paymentColor = ManaColorMapping.paymentWireColor(shard),
                                )
                            },
                    ),
                )
            }
        }
        return result
    }

    private fun buildConvokeOrImproviseRequest(
        sa: SpellAbility,
        manaCost: ManaCost,
        untappedCards: CardCollectionView,
        options: List<String>,
        plan: ConvokeOrImproviseCostPlan,
    ): PromptRequest {
        val includeManaFields = plan.manaFieldsPolicy.shouldInclude
        val displayedCost = if (includeManaFields) manaCost.toColorCounts() else emptyList()
        val candidateRefs = plan.candidateRefsPolicy.candidateRefs(untappedCards.toCandidateRefs())
        return PromptRequest(
            promptType = "choose_cards",
            message = "Choose cards to tap for ${plan.keyword}",
            options = options,
            min = 0,
            max = plan.maxSelection,
            defaultIndex = 0,
            candidateRefs = candidateRefs,
            route = PromptRouteResolver.resolve(plan.semantic, candidateRefs.isNotEmpty()),
            sourceEntityId = sa.hostCard?.id,
            sourceCardName = sa.hostCard?.name,
            waterbendManaCost = displayedCost,
            waterbendCostString = if (includeManaFields) ManaCostText.clientText(displayedCost) else null,
        )
    }

    /** Automatic mana payment reached from Forge's `PlaySpellAbility` path. */
    fun applyManaToCost(
        toPay: ManaCostBeingPaid,
        ability: SpellAbility,
        effect: Boolean,
        requireSourceChoice: Boolean = false,
    ): Boolean {
        log.debug("applyManaToCost: {} for {}", toPay, ability.hostCard?.name)
        applyHybridManaChoices(toPay, ability)
        if (player.controller is PlayerControllerAi) {
            return ComputerUtilMana.payManaCost(toPay, ability, player, effect)
        }
        // ComputerUtilMana's AI payment path calls AiCostDecision directly,
        // bypassing PlayerController.confirmPayment. Preflight its dry-run
        // source plan before any sacrifice, discard, exile, or life-payment
        // mana ability can mutate the game. Ordinary tap-only sources keep
        // the existing automatic payment behavior.
        var plan = ComputerUtilMana.getManaPaymentPlan(ManaCostBeingPaid(toPay), ability, player, effect)
        // An optional cost already accepted by the player must decline immediately
        // when Forge proves it unpayable; otherwise a resolution-time payment
        // could remain suspended in an empty, uncompletable PayCosts window.
        if (effect && plan == null) return false
        val undoFloor = player.game.stack.undoStackSize
        val manuallyActivated = mutableListOf<SpellAbility>()
        if (requireSourceChoice || useManualManaPayment() || plan == null || plan.any(ManaActivationConfirmation::requiresConfirmation)) {
            val executor = SpellExecutor(player.game, player, bridge)
            while (true) {
                val decision =
                    optionalActionGate.awaitManaPayment(
                        BlockingInteraction.ManaPayment(
                            sourceId = ForgeCardId(ability.hostCard.id),
                            manaCost = toPay.toManaCost().toColorCounts(),
                            canAutoPay = plan != null,
                            canUndo = player.game.stack.canUndo(player) && player.game.stack.undoStackSize > undoFloor,
                        ),
                        toPay.toManaCost(),
                        ability,
                    )
                when (decision) {
                    is ManaPaymentDecision.Sources -> {
                        decision.actions.forEach { source ->
                            if (executor.activateMana(source.cardId, source.abilityId, source.selectedColor, source.ability)) {
                                source.ability?.let(manuallyActivated::add)
                            }
                        }
                    }
                    ManaPaymentDecision.Undo -> {
                        if (player.game.stack.canUndo(player) &&
                            player.game.stack.undoStackSize > undoFloor &&
                            player.game.stack.undo()
                        ) {
                            if (manuallyActivated.isNotEmpty()) manuallyActivated.removeAt(manuallyActivated.lastIndex)
                        }
                    }
                    ManaPaymentDecision.Cancel -> {
                        while (player.game.stack.canUndo(player) &&
                            player.game.stack.undoStackSize > undoFloor &&
                            player.game.stack.undo()
                        ) {
                            // Refund reversible sources activated while this payment was pending.
                        }
                        return false
                    }
                    ManaPaymentDecision.AutoPay -> {
                        break
                    }
                }
                plan = ComputerUtilMana.getManaPaymentPlan(ManaCostBeingPaid(toPay), ability, player, effect)
            }
        }
        // GameBridge keeps the bridged controller at Long.MAX_VALUE - 1, so
        // Forge's timestamp-based runWithController cannot put an AI controller
        // above it. ComputerUtilMana requires that controller while it pays the
        // non-mana costs of selected sources.
        player.addController(
            Long.MAX_VALUE,
            player,
            PlayerControllerAi(player.game, player, player.originalLobbyPlayer),
            false,
        )
        return try {
            val paid = ComputerUtilMana.payManaCost(toPay, ability, player, effect)
            if (paid) {
                // Forge records mana drawn from the pool in payingMana, but its
                // AI payment path cannot see the abilities activated earlier in
                // our interactive window. Attach only abilities whose mana was
                // actually spent on this cost; excess floating mana must not
                // be undone if the spell is later cancelled.
                val spentParts = ability.payingMana.mapNotNull { it.manaAbility }.toSet()
                manuallyActivated
                    .filter { source -> source.allManaParts.any(spentParts::contains) }
                    .filterNot(ability.payingManaAbilities::contains)
                    .forEach(ability.payingManaAbilities::add)
            } else {
                while (player.game.stack.canUndo(player) && player.game.stack.undoStackSize > undoFloor && player.game.stack.undo()) {
                    // A failed payment must not strand sources tapped during the manual phase.
                }
            }
            paid
        } finally {
            player.removeController(Long.MAX_VALUE, false)
        }
    }

    private fun applyHybridManaChoices(
        toPay: ManaCostBeingPaid,
        ability: SpellAbility,
    ) {
        val choices = bridge.journal.consumeHybridManaStash() ?: return
        val hybridShards =
            toPay.getUnpaidShards().filter {
                it.isOr2Generic || it.isPhyrexian() || ManaColorMapping.standardHybridColors(it).size > 1
            }
        if (hybridShards.isEmpty()) return

        for ((index, shard) in hybridShards.withIndex()) {
            val coloredChoices = ManaColorMapping.paymentColors(shard)
            val coloredChoice = coloredChoices.firstOrNull() ?: continue
            val choice = choices.getOrNull(index) ?: coloredChoice
            toPay.decreaseShard(shard, 1)
            if (shard.isPhyrexian() && choice == ManaColor.Phyrexian_afc9) {
                ability.setSpendPhyrexianMana(true)
                player.payLife(2, ability, false)
                continue
            }
            if (shard.isOr2Generic && choice == ManaColor.TwoGeneric) {
                toPay.increaseGenericMana(2)
                continue
            }
            val replacement = monoColorShard(choice.takeIf { it in coloredChoices } ?: coloredChoice)
            if (replacement != null) {
                toPay.increaseShard(replacement, 1)
            }
        }
        log.info("applyManaToCost: applied hybrid mana choices {} for {}", choices, ability.hostCard?.name)
    }

    private fun monoColorShard(color: ManaColor): ManaCostShard? =
        when {
            color == ManaColor.White_afc9 -> ManaCostShard.WHITE
            color == ManaColor.Blue_afc9 -> ManaCostShard.BLUE
            color == ManaColor.Black_afc9 -> ManaCostShard.BLACK
            color == ManaColor.Red_afc9 -> ManaCostShard.RED
            color == ManaColor.Green_afc9 -> ManaCostShard.GREEN
            else -> null
        }

    /**
     * Binary keyword-cost prompt (max == 1, e.g. Offspring's "pay the
     * additional cost?"). When [keywordName] is supplied and a CTO-side
     * decision is already stashed by the deferred cast-cost interaction handler
     * when the player picked from the cost modal, use it — that's the path
     * that lets the client render a proper CastingTimeOptionsReq instead of a bare
     * confirm prompt. Fall back to the confirm prompt only when no CTO was
     * sent for this keyword (legacy / dev-harness paths). For max > 1 the
     * caller keeps `super.chooseNumberForKeywordCost` which routes through
     * `ClientGuiGame.getInteger`.
     */
    fun chooseKeywordCostBinary(
        prompt: String,
        keywordName: String? = null,
    ): Int {
        val stashedAnswer = resolveKeywordCostFromStash(bridge, keywordName)
        if (stashedAnswer != null) {
            log.info("chooseKeywordCostBinary: using stashed decision for keyword={} → {}", keywordName, stashedAnswer == 1)
            return stashedAnswer
        }
        val request =
            PromptRequest(
                promptType = "confirm",
                message = prompt,
                options = listOf("Yes", "No"),
                min = 1,
                max = 1,
                defaultIndex = 0,
            )
        val indices = bridge.requestChoice(request)
        return if (indices.firstOrNull() == 0) 1 else 0
    }

    /**
     * Optional cost resolution (kicker, buyback, flashback, cycling, warp,
     * Madness alt-cost). Reads the stashed decision from [leyline.bridge.handoff.PromptJournal]
     * after the client responded to `CastingTimeOptionsReq`. Falls back to
     * declining when no decision is present. Free-cast paths do not open a
     * casting-time choice window, and paying an optional cost without a
     * client decision is never safe.
     */
    fun chooseOptionalCosts(
        chosenSa: SpellAbility,
        optionalCosts: MutableList<OptionalCostValue>,
    ): MutableList<OptionalCostValue> {
        val stashed = consumeStashFor(bridge)
        if (stashed != null) {
            val chosen = stashed.mapNotNull { optionalCosts.getOrNull(it) }.toMutableList()
            log.info(
                "chooseOptionalCosts: using stashed decision — chose {} of {} for {}",
                chosen.size,
                optionalCosts.size,
                chosenSa.hostCard?.name,
            )
            return chosen
        }
        log.info(
            "chooseOptionalCosts: no decision recorded — declining {} optional costs for {}",
            optionalCosts.size,
            chosenSa.hostCard?.name,
        )
        return mutableListOf()
    }

    /**
     * Optional life payment. ETB land replacements receive the linked-entry
     * lifecycle; other single-part life costs keep the generic prompt shape.
     */
    fun payOptionalLife(
        lifePart: CostPayLife,
        sa: SpellAbility,
        etbLandReplacement: Boolean,
    ): Boolean {
        val amount = lifePart.getAbilityAmount(sa)
        val hostCard = sa.hostCard
        log.info("payCostToPreventEffect: optional PayLife<{}> for {}", amount, hostCard?.name)
        // Decline on timeout — land enters tapped, which is the safe outcome.
        val accepted =
            optionalActionGate.await(
                hostCard = hostCard,
                defaultOnTimeout = false,
                logContext = "payCostToPreventEffect",
                customPromptId =
                    leyline.game.mapping.PromptIds.SHOCK_LAND_ETB
                        .takeIf { etbLandReplacement },
                etbPayLifeReplacement = etbLandReplacement,
            )
        if (accepted) player.payLife(amount, sa, true)
        return accepted
    }

    /**
     * Optional single-part mana cost. Yes/No via [OptionalActionGate]; on
     * accept, drain mana through the shared auto-pay path. Decline on timeout.
     *
     * Payer is `[player]` (the controller whose [PlayerController] Forge
     * dispatches `payCostToPreventEffect` on), NOT `sa.activatingPlayer`.
     *
     * With [requireAffordable], a cost the payer cannot cover is declined
     * without a prompt: accepting could only fail, and a failed auto-tap
     * leaves the lands it reached tapped.
     */
    fun payOptionalManaCost(
        cost: Cost,
        sa: SpellAbility,
        context: String,
        requireAffordable: Boolean = false,
    ): Boolean {
        val hostCard = sa.hostCard
        if (requireAffordable) {
            val part = cost.costParts.firstOrNull { it is CostPartMana } as? CostPartMana
            if (part != null && !ComputerUtilMana.canPayManaCost(ManaCostBeingPaid(part.mana), sa, player, true)) {
                log.info("payCostToPreventEffect: {} unaffordable for {} — declining without prompt", cost, hostCard?.name)
                return false
            }
        }
        log.info(
            "payCostToPreventEffect: optional mana cost {} for {} (payer seat={}, context={})",
            cost,
            hostCard?.name,
            player.lobbyPlayer?.name,
            context,
        )
        val manaPart = cost.costParts.firstOrNull { it is CostPartMana } as? CostPartMana
        val accepted =
            optionalActionGate.await(
                hostCard = hostCard,
                defaultOnTimeout = false,
                logContext = "payCostToPreventEffect:$context",
                costText = manaPart?.mana?.let { ManaCostText.clientText(it.toColorCounts()) }?.takeIf { it.isNotEmpty() },
            )
        if (!accepted) return false

        if (manaPart == null) {
            log.warn("Optional mana cost accepted but no CostPartMana in cost {} — declining", cost)
            return false
        }
        val toPay = ManaCostBeingPaid(manaPart.mana)
        val paid = applyManaToCost(toPay, sa, effect = true, requireSourceChoice = true)
        if (!paid) {
            log.warn("Optional mana cost: auto-tap could not pay {} for {}", cost, hostCard?.name)
        }
        return paid
    }

    private fun ManaCost.toColorCounts(): List<Pair<ManaColor, Int>> = ManaColorMapping.deriveWubrgCostWithGenericFirst(this)

    companion object {
        /** Drain the optional cost stash from [bridge]'s journal, or null if none recorded. */
        fun consumeStashFor(bridge: InteractivePromptBridge): List<Int>? = bridge.journal.consumeOptionalCostStash()

        /**
         * Resolve a binary keyword-cost decision from [bridge]'s journal stash.
         * Returns 1 (pay) or 0 (decline) when a decision is stashed for
         * [keywordName]. Returns null when [keywordName] is null OR no
         * decision is stashed for it — caller should fall back to a confirm
         * prompt. Pure function, no side effects.
         */
        fun resolveKeywordCostFromStash(
            bridge: InteractivePromptBridge,
            keywordName: String?,
        ): Int? {
            if (keywordName == null) return null
            val cached = bridge.journal.peekKeywordCostDecision(keywordName) ?: return null
            return if (cached) 1 else 0
        }
    }
}
