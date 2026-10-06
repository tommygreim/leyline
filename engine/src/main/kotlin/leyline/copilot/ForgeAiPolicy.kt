package leyline.copilot

import forge.ai.AiCostDecision
import forge.ai.ComputerUtilCard
import forge.ai.ComputerUtilCost
import forge.ai.PlayerControllerAi
import forge.ai.simulation.GameStateEvaluator
import forge.card.ColorSet
import forge.card.MagicColor
import forge.game.Game
import forge.game.GameActionUtil
import forge.game.GameObject
import forge.game.IEntityMap
import forge.game.ability.effects.CharmEffect
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.combat.Combat
import forge.game.cost.CostPart
import forge.game.cost.CostReturn
import forge.game.cost.CostSacrifice
import forge.game.cost.CostTapType
import forge.game.phase.PhaseType
import forge.game.player.Player
import forge.game.spellability.AbilitySub
import forge.game.spellability.LandAbility
import forge.game.spellability.OptionalCostValue
import forge.game.spellability.SpellAbility
import forge.game.spellability.TargetChoices
import forge.game.zone.ZoneType
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.handoff.OneShotPayCostsWindowKind
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.StaticChoiceIds
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq
import wotc.mtgo.gre.external.messaging.Messages.StaticList

@Suppress("ReturnCount")
private fun effectCostContexts(
    bridge: GameBridge,
    seatPlayer: Player,
    msg: GREToClientMessage,
): List<Triple<SpellAbility, CostPart, PayCostsRouteKind>> {
    if (!msg.hasPayCostsReq() || !msg.payCostsReq.hasEffectCostReq()) return emptyList()
    if (msg.payCostsReq.effectCostReq.costSelection.idsCount == 0) return emptyList()

    val published = bridge.currentOneShotPayCostsInteraction()
    if (published?.windowKind == OneShotPayCostsWindowKind.Select) {
        val pendingSa =
            bridge
                .getGame()
                ?.stack
                ?.firstOrNull()
                ?.spellAbility
        if (pendingSa != null) return costPartsForRoute(pendingSa, published.kind)
    }

    val sourceId =
        msg.prompt.parametersList
            .firstOrNull { it.parameterName == "CardId" }
            ?.numberValue
            ?: return emptyList()
    val forgeId = bridge.getForgeCardId(InstanceId(sourceId)) ?: return emptyList()
    val source = bridge.findCard(forgeId) ?: return emptyList()
    val abilities = getAllCastableAbilities(source, seatPlayer) + getNonManaActivatedAbilities(source, seatPlayer)
    return abilities.distinctBy { it.id }.flatMap { sa ->
        sa.activatingPlayer = seatPlayer
        listOf(
            PayCostsRouteKind.Sacrifice to CostSacrifice::class.java,
            PayCostsRouteKind.StationTapCost to CostTapType::class.java,
        ).flatMap { (kind, type) ->
            sa.payCosts
                ?.costParts
                .orEmpty()
                .filter { type.isInstance(it) }
                .map { Triple(sa, it, kind) }
        } +
            sa.payCosts
                ?.costParts
                .orEmpty()
                .filterIsInstance<CostReturn>()
                .filter { part ->
                    sa.alternativeCost == forge.game.spellability.AlternativeCost.WebSlinging ||
                        part.type.contains("attacking+unblocked") ||
                        part.descriptiveType.contains("unblocked attacker", ignoreCase = true)
                }.map { part ->
                    Triple(
                        sa,
                        part,
                        if (sa.alternativeCost == forge.game.spellability.AlternativeCost.WebSlinging) {
                            PayCostsRouteKind.SelectCostReturnTappedCreature
                        } else {
                            PayCostsRouteKind.SelectCostReturnAttacker
                        },
                    )
                }
    }
}

private fun costPartsForRoute(
    sa: SpellAbility,
    kind: PayCostsRouteKind,
): List<Triple<SpellAbility, CostPart, PayCostsRouteKind>> {
    val type =
        when (kind) {
            PayCostsRouteKind.Sacrifice -> CostSacrifice::class.java
            PayCostsRouteKind.StationTapCost -> CostTapType::class.java
            PayCostsRouteKind.SelectCostReturnAttacker,
            PayCostsRouteKind.SelectCostReturnTappedCreature,
            -> CostReturn::class.java
            PayCostsRouteKind.SelectCostExileFromGrave,
            PayCostsRouteKind.CollectEvidence,
            PayCostsRouteKind.EnlistCost,
            PayCostsRouteKind.TapPayment,
            PayCostsRouteKind.ConvokeCost,
            PayCostsRouteKind.ImproviseCost,
            PayCostsRouteKind.WaterbendCost,
            -> return emptyList()
        }
    return sa.payCosts
        ?.costParts
        .orEmpty()
        .filter { type.isInstance(it) }
        .map { Triple(sa, it, kind) }
}

/**
 * Forge-AI advisor that picks the response the AI would submit for a pending
 * prompt on behalf of a seat, without displacing leyline's bridged controller.
 *
 * Reads the live [GameBridge] through a supplier so both consumers can share
 * it: the headless simclient (`harness.bridge`) as its volume decision engine,
 * and the copilot proposal surface (`session.gameBridge`) which exposes the
 * AI's chosen action for a human seat as an autoplay intent. The supplier is
 * evaluated lazily so consult gates that never touch the bridge stay cheap and
 * fail closed before a game is initialised.
 *
 * Builds a parallel [PlayerControllerAi] for the seat (NOT registered as the
 * Forge `Player`'s actual controller — leyline's bridged controller stays in
 * place and continues to emit GRE prompts). The advisor is consulted as a
 * read-only decision source: at a priority window we ask the AI what it would
 * play, then translate the chosen [forge.game.spellability.SpellAbility] into
 * a matching `ActionsAvailableReq` action.
 *
 * ## Load-bearing rules — DO NOT BREAK when adding a new translator method
 *
 * 1. **Don't register [PlayerControllerAi] as the player's controller.**
 *    Leyline's bridged `PlayerController` (extends `PlayerControllerHuman`)
 *    MUST stay registered — it's what emits GRE prompts and blocks on
 *    response futures. Swap it out and the seat stops producing prompts.
 *
 * 2. **Wrap every Forge-AI call in [askAi].** Forge AI internals (e.g.
 *    `forge.ai.AiCostDecision`, `forge.ai.ability.AttachAi`) cast
 *    `player.getController()` to `PlayerControllerAi`. Outside that scope
 *    you'll get `ClassCastException` because the registered controller is
 *    leyline's bridge. Forge's own `runWithController` is NOT enough: the
 *    bridge registers its controller at `Long.MAX_VALUE - 1`, above any
 *    `getNextTimestamp()` layer, so [askAi] layers the AI controller at
 *    `Long.MAX_VALUE` and removes it in `finally`.
 *
 * 3. **Skip AI consult on Pass-only AARs** (caller side — see
 *    `SimClientDriver.hasCastableActionsInAar`). Forge AI's search costs
 *    50-200ms; during that window engine continuation can consume the priority
 *    window, and a subsequent submit lands "no pending action", causing a
 *    state resync. The driver's race guard handles this; translators here
 *    don't need to.
 *
 * Scope: one consult method per prompt family (AAR, attackers/blockers,
 * SelectN, SelectTargets, CTO, sacrifice PayCosts). Prompt types without a
 * method — and any consult that fails closed — fall through to the caller's
 * greedy responder.
 */
class ForgeAiPolicy(
    private val bridgeSupplier: () -> GameBridge,
    private val seatId: SeatId,
) {
    private val bridge: GameBridge
        get() = bridgeSupplier()

    private fun game(): Game = bridge.getGame() ?: error("ForgeAiPolicy: game was not initialised")

    /**
     * Forge-AI heuristic evaluation of the current game state from this
     * seat's perspective. Positive favours the seat; null when the game is
     * not initialised or the evaluator throws.
     */
    fun evaluateGameState(): GameStateEvaluator.Score? =
        askAi("evaluateGameState") {
            GameStateEvaluator().getScoreForGameState(game(), seatPlayer)
        }

    /** Resolved on first use — bridge/game are not ready at construction. */
    private val seatPlayer: Player by lazy {
        bridge.getPlayer(seatId)
            ?: error("ForgeAiPolicy: seat $seatId has no Forge player")
    }

    private val aiController: PlayerControllerAi by lazy {
        PlayerControllerAi(game(), seatPlayer, seatPlayer.lobbyPlayer)
    }

    /** Resolved action ready to submit as a `PerformAction` GRE message. */
    data class Choice(
        val action: Action,
        val instanceId: Int,
        val grpId: Int,
        val actionType: ActionType,
        val abilityGrpId: Int = 0,
    )

    /**
     * Ask the AI what to play at the current priority window. Returns a
     * [Choice] when the AI picks an ability that matches an available AAR
     * action, or null when the AI passes / no actionable match.
     *
     * Match rules (iteration 1):
     *   - LandAbility → first AAR action with `actionType=Play_add3` and same grpId
     *   - isSpell() → the unique prompt cast offer matching source + displayed cost
     *   - non-spell activated ability → exact `Activate_add3` host iid + abilityGrpId match
     *   - everything else → null (caller falls back to greedy / pass)
     */
    fun chooseAarAction(
        promptActions: List<Action>,
        isSkipped: (Action) -> Boolean = { false },
    ): Choice? {
        // Forge AI writes its preferred targets onto source abilities while choosing an action.
        // Restore them so consulting here cannot consume a later targeting interaction.
        val targetSnapshots = snapshotCardAbilityTargets(game())
        val abilities =
            try {
                askAi("chooseSpellAbilityToPlay") { aiController.chooseSpellAbilityToPlay() }
            } finally {
                targetSnapshots.forEach { (ability, targets) -> ability.setTargets(targets) }
            }
        if (abilities.isNullOrEmpty()) return null

        for (sa in abilities) {
            val hostCard = sa.hostCard ?: continue
            val grpId = bridge.cardRepository.findGrpIdByName(hostCard.name) ?: continue
            // Match the action to this exact Forge card instance.
            val mappedInstanceId = bridge.instanceId(hostCard)
            val match =
                if (sa.isSpell) {
                    choosePromptCastOfferForAbility(
                        actions = promptActions.filterNot(isSkipped),
                        sa = sa,
                        player = seatPlayer,
                        cardRepository = bridge.cardRepository,
                        sourceInstanceId = mappedInstanceId,
                        sourceGrpId = grpId,
                    )
                } else {
                    val actionType = if (sa is LandAbility) ActionType.Play_add3 else ActionType.Activate_add3
                    chooseMatchingAction(sa, actionType, grpId, mappedInstanceId, promptActions, isSkipped)
                } ?: continue
            return Choice(match, match.instanceId, match.grpId, match.actionType, match.abilityGrpId)
        }
        return null
    }

    /**
     * Last priority-window safeguard for a cast the normal AI declines at the
     * end of its own turn. The AAR supplies legality and affordability: only
     * an active Cast with a non-zero cost and a concrete auto-tap solution is
     * eligible. Forge supplies card identity and filters out instants, Flash,
     * and non-permanents.
     *
     * Forge's card evaluator ranks eligible permanents. If that evaluator
     * fails, spending the greatest mana value before passing the turn is the
     * conservative deterministic fallback; source instance id breaks ties.
     */
    internal fun chooseMain2ProactivePermanent(promptActions: List<Action>): Choice? {
        val phase = game().phaseHandler
        if (phase.phase != PhaseType.MAIN2 || phase.playerTurn != seatPlayer || phase.priorityPlayer != seatPlayer) return null

        val candidates = promptActions.mapNotNull(::main2ProactiveCandidate)
        if (candidates.isEmpty()) return null

        val forgeBest =
            askAi("rankMain2ProactivePermanent") {
                ComputerUtilCard.getBestAI(candidates.map { it.card })
            }
        return candidates.firstOrNull { it.card === forgeBest }?.choice
            ?: candidates
                .sortedWith(
                    compareByDescending<ProactiveCandidate> { it.card.getCMC() }
                        .thenBy { it.choice.instanceId },
                ).first()
                .choice
    }

    private fun main2ProactiveCandidate(action: Action): ProactiveCandidate? {
        if (action.actionType != ActionType.Cast) return null
        if (action.manaCostList.sumOf { it.count } <= 0) return null
        if (!action.hasAutoTapSolution() || action.autoTapSolution.autoTapActionsCount == 0) return null

        val card = cardForInstance(action.instanceId) ?: return null
        if (!card.isPermanent || card.isInstant) return null
        val grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: return null
        val mappedInstanceId = bridge.instanceId(card)
        val hasSorcerySpeedAbility =
            getAllCastableAbilities(card, seatPlayer).any { sa ->
                sa.activatingPlayer = seatPlayer
                sa.isSpell &&
                    !sa.withFlash(card, seatPlayer) &&
                    chooseMatchingAction(
                        sa = sa,
                        actionType = ActionType.Cast,
                        grpId = grpId,
                        mappedInstanceId = mappedInstanceId,
                        promptActions = listOf(action),
                        isSkipped = { false },
                    ) != null
            }
        if (!hasSorcerySpeedAbility) return null
        return ProactiveCandidate(
            card = card,
            choice = Choice(action, action.instanceId, action.grpId, ActionType.Cast, action.abilityGrpId),
        )
    }

    private data class ProactiveCandidate(
        val card: Card,
        val choice: Choice,
    )

    private fun chooseMatchingAction(
        sa: SpellAbility,
        actionType: ActionType,
        grpId: Int,
        mappedInstanceId: Int,
        promptActions: List<Action>,
        isSkipped: (Action) -> Boolean,
    ): Action? {
        if (actionType != ActionType.Activate_add3) {
            // Match by grpId OR the mapped instanceId: a basic land (or any card
            // with multiple printings) resolves findGrpIdByName to a different
            // printing than the one the AAR offers, so a grpId-only filter drops
            // the play. The hydrated card is rebound to the source instanceId,
            // making it the reliable key when the grpId diverges.
            val candidates =
                promptActions.filter {
                    it.actionType == actionType &&
                        !isSkipped(it) &&
                        (it.grpId == grpId || it.instanceId == mappedInstanceId)
                }
            return chooseCastVariant(sa, grpId, seatPlayer, bridge.cardRepository, candidates.filter { it.instanceId == mappedInstanceId })
                ?: chooseCastVariant(sa, grpId, seatPlayer, bridge.cardRepository, candidates)
        }

        val hostCard = sa.hostCard ?: return null
        val cardData = bridge.cardRepository.findByGrpId(grpId) ?: return null
        val abilityGrpId = bridge.abilityRegistryFor(hostCard, cardData)?.forSpellAbility(sa.id) ?: return null
        return promptActions.firstOrNull {
            it.actionType == ActionType.Activate_add3 &&
                it.instanceId == mappedInstanceId &&
                it.abilityGrpId == abilityGrpId &&
                !isSkipped(it)
        }
    }

    /**
     * Ask the AI which creatures should attack. Uses a throwaway [Combat]
     * object so the consult does not mutate Forge's live combat before the
     * bridge receives a DeclareAttackers response.
     */
    fun chooseAttackers(): List<Int>? {
        val probeCombat = Combat(seatPlayer)
        askAi("declareAttackers") { aiController.declareAttackers(seatPlayer, probeCombat) } ?: return null
        return probeCombat.getAttackers().map { attacker ->
            bridge.instanceId(attacker)
        }
    }

    /**
     * Ask the AI which of the seat's creatures should block the current
     * attackers. Uses a probe [Combat] so the consult does not mutate Forge's
     * live combat before the bridge receives a DeclareBlockers response.
     * Returns a `blockerInstanceId → attackerInstanceId` map ready for
     * [leyline.tooling.headless.MatchFlowHarness.declareBlockers], or null when
     * there are no blocks.
     *
     * For multi-block (one blocker assigned to several attackers) we emit one
     * entry per (blocker, attacker) pair.
     */
    fun chooseBlockers(msg: GREToClientMessage? = null): Map<Int, Int>? {
        val probeCombat = liveProbeCombat() ?: rebuiltCombat(msg) ?: return null
        askAi("declareBlockers") { aiController.declareBlockers(seatPlayer, probeCombat) } ?: return null
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (attacker in probeCombat.getAttackers()) {
            val blockers = probeCombat.getBlockers(attacker)
            if (blockers.isNullOrEmpty()) continue
            for (blocker in blockers) {
                val blockerId = bridge.instanceId(blocker)
                val attackerId = bridge.instanceId(attacker)
                pairs += blockerId to attackerId
            }
        }
        if (pairs.isEmpty()) return null
        // declareBlockers takes Map<blockerInstanceId, attackerInstanceId>; the
        // multi-block case (same blocker → multiple attackers) collapses to the
        // last assignment per blocker. Acceptable for iteration 2; refine if AI
        // ever banding-blocks.
        return pairs.toMap()
    }

    /** Clone of the live combat, when one is actually underway. */
    private fun liveProbeCombat(): Combat? {
        val combat: Combat = game().combat ?: return null
        if (combat.getAttackers().isEmpty()) return null
        return Combat(combat, identityEntityMap())
    }

    /**
     * Rebuild combat from the DeclareBlockersReq itself: the prompt lists every
     * attacker each of our creatures could block. A game with no combat under way
     * (a consult against a hydrated game) still holds the attacking cards on the
     * battlefield, so registering them attacking us reconstructs the decision
     * the prompt is asking about — the same prompt-derived pattern target
     * consults use for their ability.
     */
    private fun rebuiltCombat(msg: GREToClientMessage?): Combat? {
        val req = msg?.takeIf { it.hasDeclareBlockersReq() }?.declareBlockersReq ?: return null
        val attackerIds =
            req.blockersList
                .flatMap { it.attackerInstanceIdsList + it.selectedAttackerInstanceIdsList }
                .distinct()
        val attackers = attackerIds.mapNotNull { cardForInstance(it) }
        if (attackers.isEmpty()) return null
        val combat = Combat(attackers.first().controller ?: return null)
        for (card in attackers) combat.addAttacker(card, seatPlayer)
        // Register blocks already committed on the (re-)prompt: the AI must see
        // them the way it sees them on a live combat clone, or it re-proposes
        // blocks past the point where the declaration has converged.
        for (blocker in req.blockersList) {
            if (blocker.selectedAttackerInstanceIdsCount == 0) continue
            val blockerCard = cardForInstance(blocker.blockerInstanceId) ?: continue
            for (attackerId in blocker.selectedAttackerInstanceIdsList) {
                val attackerCard = cardForInstance(attackerId) ?: continue
                combat.addBlocker(attackerCard, blockerCard)
            }
        }
        return combat
    }

    private fun identityEntityMap(): IEntityMap =
        object : IEntityMap {
            override fun getGame() = game()

            override fun map(o: GameObject): GameObject = o
        }

    fun canChooseSelectN(req: SelectNReq): Boolean {
        val count = selectNCount(req)
        if (count <= 0) return false
        val cards = req.idsList.mapNotNull { cardForInstance(it) }
        return cards.size == req.idsList.size && cards.all { it.zone?.zoneType == ZoneType.Hand }
    }

    fun chooseSelectN(req: SelectNReq): List<Int>? {
        val count = selectNCount(req)
        if (count <= 0) return emptyList()
        val candidates = req.idsList.mapNotNull { cardForInstance(it) }
        if (candidates.size != req.idsList.size || candidates.any { it.zone?.zoneType != ZoneType.Hand }) return null
        val targetPlayer = candidates.firstOrNull()?.owner ?: return null
        if (candidates.any { it.owner != targetPlayer }) return null
        val sourceSa = cardForInstance(req.sourceId)?.spellAbilities?.firstOrNull()
        val validCards = CardCollection(candidates)
        val chosen =
            askAi("chooseCardsToDiscardFrom") {
                aiController.chooseCardsToDiscardFrom(targetPlayer, sourceSa, validCards, count, count, validCards)
            } ?: return null
        val chosenIds = chosen.map { instanceIdForCard(it) }.filter { it != 0 }
        return chosenIds.takeIf { it.size >= count }?.take(count)
    }

    fun canChooseStaticColorSelectN(msg: GREToClientMessage): Boolean {
        if (!msg.hasSelectNReq()) return false
        val req = msg.selectNReq
        return req.staticList == StaticList.Colors && selectNCount(req) > 0
    }

    fun chooseStaticColorSelectN(msg: GREToClientMessage): List<Int>? {
        if (!canChooseStaticColorSelectN(msg)) return null
        val req = msg.selectNReq
        val sa =
            game()
                .stack
                .firstOrNull()
                ?.spellAbility
        val allowedIds = allowedStaticColorIds(req, emptyList())
        val allowedColors = colorSetFromStaticIds(allowedIds)
        if (allowedColors.isColorless) return null
        val colors =
            askAi("chooseColors") {
                aiController.chooseColors(
                    "Choose a color",
                    sa,
                    req.minSel.coerceAtLeast(1),
                    (if (req.maxSel > 0) req.maxSel else req.minSel).coerceAtLeast(1),
                    allowedColors,
                )
            } ?: return null
        val selected = colors.toStaticColorIds().filter { it in allowedIds }.take(selectNCount(req))
        return selected.takeIf { it.isNotEmpty() }
    }

    fun canChooseSacrificeCostPayment(msg: GREToClientMessage): Boolean =
        effectCostContexts(msg).any { it.third == PayCostsRouteKind.Sacrifice }

    fun canChooseEffectCostPayment(msg: GREToClientMessage): Boolean = effectCostContexts(msg).isNotEmpty()

    /**
     * Recover the Forge-AI cost decision for a sacrifice cost-payment prompt.
     * The pending bridge prompt carries the [SpellAbility] being paid; the AI
     * cost visitor picks the cards it would sacrifice, and those map to the
     * prompt's candidate instance ids. Fails closed (null) when the prompt is
     * not a sacrifice cost selection or the AI choice does not map onto the
     * offered candidates.
     *
     * Assumes the pending prompt corresponds to the SA's first [CostSacrifice]
     * part; an SA with several sacrifice parts would need prompt/part
     * correlation. Candidate-id validation keeps a mismatched decision from
     * being submitted — it degrades to greedy instead.
     */
    fun chooseSacrificeCostPayment(msg: GREToClientMessage): List<Int>? =
        effectCostContexts(msg)
            .asSequence()
            .filter { it.third == PayCostsRouteKind.Sacrifice }
            .mapNotNull { chooseEffectCostPayment(msg, it) }
            .firstOrNull()

    /** Choose cards for a supported non-mana cost through Forge's cost visitor. */
    fun chooseEffectCostPayment(msg: GREToClientMessage): List<Int>? =
        effectCostContexts(msg)
            .asSequence()
            .mapNotNull { chooseEffectCostPayment(msg, it) }
            .firstOrNull()

    private fun chooseEffectCostPayment(
        msg: GREToClientMessage,
        context: Triple<SpellAbility, CostPart, PayCostsRouteKind>,
    ): List<Int>? {
        val (sa, costPart, _) = context
        val decision =
            askAi("effectCostDecision") {
                costPart.accept(AiCostDecision(seatPlayer, sa, false))
            } ?: return null
        val chosenIds = decision.cards.map { instanceIdForCard(it) }
        return effectCostSelectionIds(chosenIds, msg.payCostsReq.effectCostReq.costSelection)
    }

    private fun effectCostContexts(msg: GREToClientMessage): List<Triple<SpellAbility, CostPart, PayCostsRouteKind>> =
        runCatching { effectCostContexts(bridge, seatPlayer, msg) }.getOrElse { emptyList() }

    fun canChooseSelectTargets(msg: GREToClientMessage): Boolean {
        if (!msg.hasSelectTargetsReq()) return false
        val req = msg.selectTargetsReq
        if (req.targetsCount == 0) return false
        return req.targetsList.all { group ->
            val saneBounds = group.minTargets >= 0 && group.maxTargets >= group.minTargets
            val availableCount =
                group.targetsList
                    .map { it.targetInstanceId }
                    .distinct()
                    .size
            saneBounds && availableCount >= group.minTargets
        }
    }

    fun chooseSelectTargets(msg: GREToClientMessage): TargetGroupSelections? {
        if (!canChooseSelectTargets(msg)) return null

        val costTargets = chooseTapCostTargets(msg)

        // Preferred path: let Forge's AI pick the target for the bound spell/
        // ability. When no prompt-bound ability exists (a consult against a
        // hydrated game hosts no pending prompt), rebuild the ability from the
        // prompt's source card — the same card-not-stack pattern optional-cost
        // decisions use — so the AI's considered pick survives hydration.
        val sa =
            bridge.currentTargetingAbility()
                ?: game().stack.firstOrNull()?.spellAbility
                ?: rebuiltTargetingSa(msg.selectTargetsReq.sourceId)
        val preferredIds =
            costTargets
                ?: if (sa != null) {
                    val previousTargets = sa.targets.clone()
                    val chosenTargets =
                        try {
                            sa.targets.clear()
                            val chose = askAi("chooseTargetsFor") { aiController.chooseTargetsFor(sa) } ?: false
                            if (chose) sa.targets.toList() else null
                        } finally {
                            sa.targets.clear()
                            sa.targets.addAll(previousTargets)
                        }
                    chosenTargets?.mapNotNull { targetInstanceId(it) }.orEmpty()
                } else {
                    emptyList()
                }
        return selectTargetPlan(msg.selectTargetsReq, preferredIds)
    }

    private fun chooseTapCostTargets(msg: GREToClientMessage): List<Int>? {
        val req = msg.selectTargetsReq
        val group = req.targetsList.singleOrNull() ?: return null
        val source = cardForInstance(req.sourceId) ?: return null
        val abilities =
            getAllCastableAbilities(source, seatPlayer) +
                getNonManaActivatedAbilities(source, seatPlayer) +
                source.spellAbilities
        val legalIds = group.targetsList.map { it.targetInstanceId }.toSet()
        val min = group.minTargets.coerceAtLeast(0)
        val max = group.maxTargets.takeIf { it >= min } ?: return null
        return abilities
            .asSequence()
            .distinctBy { it.id }
            .flatMap { sa ->
                sa.activatingPlayer = seatPlayer
                sa.payCosts
                    ?.costParts
                    .orEmpty()
                    .filterIsInstance<CostTapType>()
                    .asSequence()
                    .map { sa to it }
            }.mapNotNull { (sa, cost) ->
                val decision = askAi("tapCostDecision") { cost.accept(AiCostDecision(seatPlayer, sa, false)) }
                decision?.cards?.map { instanceIdForCard(it) }
            }.firstOrNull { ids ->
                ids.isNotEmpty() && ids.distinct().size == ids.size && ids.size in min..max && ids.all { it in legalIds }
            }
    }

    /** Build a legal desired set independently for every target group. */
    private fun selectTargetPlan(
        req: SelectTargetsReq,
        preferredIds: List<Int>,
    ): TargetGroupSelections? {
        val committed = TargetSelectionDiff.committedTargets(req)
        val result = linkedMapOf<Int, List<Int>>()
        for (group in req.targetsList) {
            val min = group.minTargets.coerceAtLeast(0)
            val max = group.maxTargets.takeIf { it >= group.minTargets } ?: group.minTargets
            val legalIds = group.targetsList.map { it.targetInstanceId }.distinct()
            val desired =
                committed[group.targetIdx]
                    .orEmpty()
                    .filter { it in legalIds }
                    .distinct()
                    .take(max)
                    .toMutableList()
            preferredIds.filter { it in legalIds && it !in desired }.take(max - desired.size).forEach(desired::add)
            group.targetsList
                .filter { it.legalAction == SelectAction.Select_a1ad && it.targetInstanceId !in desired }
                .map { it.targetInstanceId }
                .distinct()
                .sortedByDescending(::isEnemyTarget)
                .take((min - desired.size).coerceAtLeast(0))
                .forEach(desired::add)
            if (desired.size !in min..max) return null
            result[group.targetIdx] = desired
        }
        return result.takeIf { TargetSelectionDiff.isValid(req, it) }
    }

    /**
     * Rebuild the targeting ability for [sourceId]'s card so target selection
     * can run without a prompt-bound ability. First castable ability that
     * targets wins — refining by abilityGrpId is a later slice for
     * multi-face/adventure cards.
     */
    private fun rebuiltTargetingSa(sourceId: Int): SpellAbility? {
        val card = cardForInstance(sourceId) ?: return null
        val sa = getAllCastableAbilities(card, seatPlayer).firstOrNull { it.usesTargeting() } ?: return null
        sa.activatingPlayer = seatPlayer
        return sa
    }

    /**
     * True if [instanceId] is on the opponent's side (fallback-target preference).
     * Cards compare by controller. An id with no card mapping is a player target
     * — players use their seatId as targeting instanceId — so compare seats;
     * without this, player-target spells sorted both players equal and the
     * fallback aimed at whoever sorted first: ourselves.
     */
    private fun isEnemyTarget(instanceId: Int): Boolean {
        val card = cardForInstance(instanceId)
        if (card != null) return card.controller != seatPlayer
        return instanceId in 1..2 && instanceId != seatId.value
    }

    fun canChooseCastingTimeOptions(msg: GREToClientMessage): Boolean {
        if (!msg.hasCastingTimeOptionsReq()) return false
        val options = msg.castingTimeOptionsReq.castingTimeOptionReqList
        return isManaTypeCto(options) ||
            isSimpleModalCto(options) ||
            isSingleOptionalCostCto(options) ||
            isSingleChooseXCto(options)
    }

    internal fun chooseCastingTimeOptions(msg: GREToClientMessage): SimDecision? {
        if (!canChooseCastingTimeOptions(msg)) return null
        return chooseManaTypeCastingTimeOptions(msg)?.let { SimDecision.ManaTypeChoices(it) }
            ?: chooseModalCastingTimeOptions(msg)
            ?: chooseOptionalCastingTimeOptions(msg)?.let { SimDecision.OptionalCost(it) }
            ?: chooseXCastingTimeOptions(msg)
    }

    private fun chooseManaTypeCastingTimeOptions(msg: GREToClientMessage): List<Pair<Int, ManaColor>>? {
        val options = msg.castingTimeOptionsReq.castingTimeOptionReqList
        if (!isManaTypeCto(options)) return null
        return options.map { option ->
            val color = option.selectManaTypeReq.manaColorsList.firstOrNull { it != ManaColor.TwoGeneric } ?: ManaColor.TwoGeneric
            option.ctoId to color
        }
    }

    private fun chooseModalCastingTimeOptions(msg: GREToClientMessage): SimDecision.ModalChoice? {
        if (!isSimpleModalCto(msg.castingTimeOptionsReq.castingTimeOptionReqList)) return null
        val option =
            msg.castingTimeOptionsReq.castingTimeOptionReqList
                .single()
        val modal = option.modalReq
        val modalGrpIds = modal.modalOptionsList.map { it.grpId }
        val context = bridge.cutCoordinator.modalChoices.aiContext() ?: return null
        if (context.possibleFullIndices.size != modalGrpIds.size) return null
        val sa = context.sourceAbility
        val possible =
            modalPossibleAbilities(
                sa,
                context.possibleFullIndices,
                modalGrpIds.size,
            ) ?: return null
        modalChoiceGrpIds(sa.chosenList, possible, modalGrpIds)?.let {
            return SimDecision.ModalChoice(option.ctoId, it)
        }
        modalChoiceGrpIds(subAbilityChain(sa.subAbility), possible, modalGrpIds)?.let {
            return SimDecision.ModalChoice(option.ctoId, it)
        }
        val previousSub = sa.subAbility
        val previousChosen = sa.chosenList
        val chosen =
            try {
                sa.subAbility = null
                askAi("chooseModeForAbility") { aiController.chooseModeForAbility(sa, possible, 1, 1, false) }
            } finally {
                sa.subAbility = previousSub
                sa.chosenList = previousChosen
            } ?: return null
        return modalChoiceGrpIds(chosen, possible, modalGrpIds)?.let {
            SimDecision.ModalChoice(option.ctoId, it)
        }
    }

    private fun chooseOptionalCastingTimeOptions(msg: GREToClientMessage): Int? {
        val options = msg.castingTimeOptionsReq.castingTimeOptionReqList
        if (!isSingleOptionalCostCto(options)) return null
        val costOption = options.first { !it.isRequired }
        val card = cardForInstance(costOption.affectedId) ?: return null
        val sa = getAllCastableAbilities(card, seatPlayer).firstOrNull() ?: return null
        sa.activatingPlayer = seatPlayer
        val optionalCosts = GameActionUtil.getOptionalCostValues(sa)
        if (optionalCosts.size != 1) return null
        val chosen = askAi("chooseOptionalCosts") { aiController.chooseOptionalCosts(sa, optionalCosts) } ?: return null
        return if (optionalCostChosen(chosen, optionalCosts.single())) costOption.ctoId else 0
    }

    private fun chooseXCastingTimeOptions(msg: GREToClientMessage): SimDecision.CastingTimeX? {
        val options = msg.castingTimeOptionsReq.castingTimeOptionReqList
        if (!isSingleChooseXCto(options)) return null
        val option = options.single()
        val card = cardForInstance(option.affectedId) ?: return null
        val sa =
            getAllCastableAbilities(card, seatPlayer)
                .firstOrNull { it.payCosts?.hasXInAnyCostPart() == true && it.getSVar("X") == "Count\$xPaid" }
                ?: return null
        sa.activatingPlayer = seatPlayer
        val req = option.numericInputReq
        val min = req.minValue.coerceAtLeast(0)
        val max = ComputerUtilCost.setMaxXValue(sa, seatPlayer, false).coerceAtMost(req.maxValue)
        if (max < min) return null
        val chosen = askAi("chooseCastingTimeX") { aiController.chooseNumber(sa, "Choose X", min, max) } ?: return null
        if (chosen !in min..max) return null
        return SimDecision.CastingTimeX(option.ctoId, chosen)
    }

    private fun optionalCostChosen(
        chosen: List<OptionalCostValue>,
        option: OptionalCostValue,
    ): Boolean = chosen.any { it.type == option.type && it.cost.toString() == option.cost.toString() }

    private fun isSimpleModalCto(options: List<wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq>): Boolean {
        if (options.size != 1) return false
        val option = options.single()
        if (option.castingTimeOptionType != CastingTimeOptionType.Modal_a7b4 || !option.hasModalReq()) return false
        val modal = option.modalReq
        return modal.minSel == 1 && modal.maxSel == 1 && modal.modalOptionsCount > 0
    }

    private fun isManaTypeCto(options: List<wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq>): Boolean =
        options.isNotEmpty() &&
            options.all {
                it.ctoId > 0 &&
                    it.isRequired &&
                    it.castingTimeOptionType == CastingTimeOptionType.ManaType &&
                    it.hasSelectManaTypeReq()
            }

    private fun isSingleOptionalCostCto(options: List<wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq>): Boolean {
        if (options.size != 2) return false
        val costOptions = options.filter { !it.isRequired }
        val doneOptions = options.filter { it.isRequired && it.castingTimeOptionType == CastingTimeOptionType.Done }
        if (costOptions.size != 1 || doneOptions.size != 1) return false
        val costOption = costOptions.single()
        return costOption.ctoId > 0 &&
            costOption.affectedId != 0 &&
            costOption.castingTimeOptionType == CastingTimeOptionType.Kicker
    }

    private fun modalChoiceGrpIds(
        chosen: List<AbilitySub>?,
        possible: List<AbilitySub>,
        modalGrpIds: List<Int>,
    ): List<Int>? {
        if (chosen?.size != 1) return null
        val selected = chosen.single()
        val index =
            possible.indexOf(selected).takeIf { it >= 0 }
                ?: selected.description?.let { description ->
                    possible.indexOfFirst { it.description == description }.takeIf { it >= 0 }
                }
                ?: return null
        return listOf(modalGrpIds.getOrNull(index) ?: return null)
    }

    private fun subAbilityChain(first: AbilitySub?): List<AbilitySub> {
        val result = mutableListOf<AbilitySub>()
        var current = first
        while (current != null) {
            result += current
            current = current.subAbility
        }
        return result
    }

    private fun modalPossibleAbilities(
        sa: SpellAbility,
        possibleFullIndices: List<Int>?,
        modalOptionCount: Int,
    ): MutableList<AbilitySub>? {
        val fullList = sa.getAdditionalAbilityList("Choices")
        if (possibleFullIndices != null) {
            if (fullList == null) return null
            val possible = possibleFullIndices.map { idx -> fullList.getOrNull(idx) ?: return null }
            return possible.toMutableList().takeIf { it.size == modalOptionCount }
        }
        val possible = CharmEffect.makePossibleOptions(sa) ?: return null
        return possible.toMutableList().takeIf { it.size == modalOptionCount }
    }

    private fun selectNCount(req: SelectNReq): Int {
        val min = req.minSel.coerceAtLeast(0)
        val max = if (req.maxSel > 0) req.maxSel else min
        return min.coerceAtMost(max)
    }

    private fun cardForInstance(instanceId: Int): Card? {
        if (instanceId == 0) return null
        val forgeId = bridge.getForgeCardId(InstanceId(instanceId)) ?: return null
        return bridge.findCard(forgeId)
    }

    private fun instanceIdForCard(card: Card): Int = bridge.instanceId(card)

    private fun targetInstanceId(target: GameObject): Int? =
        when (target) {
            is Card -> instanceIdForCard(target)
            is Player -> if (target == seatPlayer) seatId.value else 3 - seatId.value
            else -> null
        }

    private fun ColorSet.toStaticColorIds(): List<Int> =
        buildList {
            if (hasWhite()) StaticChoiceIds.colorIdForMask(MagicColor.WHITE)?.let(::add)
            if (hasBlue()) StaticChoiceIds.colorIdForMask(MagicColor.BLUE)?.let(::add)
            if (hasBlack()) StaticChoiceIds.colorIdForMask(MagicColor.BLACK)?.let(::add)
            if (hasRed()) StaticChoiceIds.colorIdForMask(MagicColor.RED)?.let(::add)
            if (hasGreen()) StaticChoiceIds.colorIdForMask(MagicColor.GREEN)?.let(::add)
        }

    private var askAiDepth = 0

    private fun <T> askAi(
        label: String,
        block: () -> T,
    ): T? {
        // The bridge registers leyline's PlayerController at Long.MAX_VALUE - 1,
        // so Forge's Player.runWithController (which layers at getNextTimestamp())
        // never out-ranks it. Layer the AI controller at Long.MAX_VALUE instead so
        // Forge-AI internals that cast player.getController() see PlayerControllerAi.
        // The fixed slot is shared, so nested consults only add/remove at depth 0.
        var result: T? = null
        var threw: Throwable? = null
        if (askAiDepth == 0) seatPlayer.addController(Long.MAX_VALUE, seatPlayer, aiController, false)
        askAiDepth += 1
        try {
            result = block()
        } catch (t: Throwable) {
            threw = t
        } finally {
            askAiDepth -= 1
            if (askAiDepth == 0) seatPlayer.removeController(Long.MAX_VALUE, false)
        }
        val failure = threw
        if (failure != null) {
            log.warn(
                "Forge AI {} threw: {}: {} at {}",
                label,
                failure::class.simpleName,
                failure.message,
                failure.stackTrace.firstOrNull(),
            )
        }
        return result
    }

    companion object {
        private val log = LoggerFactory.getLogger(ForgeAiPolicy::class.java)
    }
}

private fun snapshotCardAbilityTargets(game: Game): List<Pair<SpellAbility, TargetChoices>> =
    game.cardsInGame
        .flatMap { card ->
            card.allSpellAbilities.flatMap { root ->
                generateSequence(root) { it.subAbility }.filter { it.usesTargeting() }.toList()
            }
        }.map { ability -> ability to ability.targets.clone() }

private fun isSingleChooseXCto(options: List<wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionReq>): Boolean {
    if (options.size != 1) return false
    val option = options.single()
    return option.ctoId > 0 &&
        option.isRequired &&
        option.castingTimeOptionType == CastingTimeOptionType.ChooseX_a7b4 &&
        option.hasNumericInputReq()
}

internal sealed interface ExpectedCastVariant {
    data object Base : ExpectedCastVariant

    data class Alternative(
        val alternativeGrpId: Int,
    ) : ExpectedCastVariant

    data object UnresolvedAlternative : ExpectedCastVariant
}

internal fun chooseCastActionByVariant(
    candidates: List<Action>,
    variant: ExpectedCastVariant,
): Action? =
    when (variant) {
        ExpectedCastVariant.Base -> candidates.firstOrNull { it.alternativeGrpId == 0 }
        is ExpectedCastVariant.Alternative -> candidates.firstOrNull { it.alternativeGrpId == variant.alternativeGrpId }
        ExpectedCastVariant.UnresolvedAlternative -> null
    }

/**
 * Validate AI-chosen sacrifice ids against the cost selection contract:
 * every id must be an offered candidate, chosen exactly once, and the count
 * must satisfy the selection's min/max. Null means "no usable AI decision".
 */
internal fun effectCostSelectionIds(
    chosenIds: List<Int>,
    selection: SelectNReq,
): List<Int>? {
    if (chosenIds.isEmpty()) return null
    val allowed = selection.idsList.toSet()
    if (chosenIds.any { it == 0 || it !in allowed }) return null
    if (chosenIds.distinct().size != chosenIds.size) return null
    val min = selection.minSel.coerceAtLeast(0)
    val max = if (selection.maxSel > 0) selection.maxSel else min
    if (chosenIds.size !in min..max) return null
    return chosenIds
}

internal fun sacrificeCostSelectionIds(
    chosenIds: List<Int>,
    selection: SelectNReq,
): List<Int>? = effectCostSelectionIds(chosenIds, selection)

internal fun allowedStaticColorIds(
    req: SelectNReq,
    promptStaticOptionIds: List<Int>,
): List<Int> {
    val ids =
        req.idsList
            .ifEmpty { promptStaticOptionIds }
            .ifEmpty {
                listOfNotNull(
                    StaticChoiceIds.colorIdForMask(MagicColor.WHITE),
                    StaticChoiceIds.colorIdForMask(MagicColor.BLUE),
                    StaticChoiceIds.colorIdForMask(MagicColor.BLACK),
                    StaticChoiceIds.colorIdForMask(MagicColor.RED),
                    StaticChoiceIds.colorIdForMask(MagicColor.GREEN),
                )
            }
    return ids.distinct()
}

internal fun colorSetFromStaticIds(ids: List<Int>): ColorSet {
    val mask =
        ids.fold(0) { acc, id ->
            acc or
                when (id) {
                    StaticChoiceIds.colorIdForMask(MagicColor.WHITE) -> MagicColor.WHITE.toInt()
                    StaticChoiceIds.colorIdForMask(MagicColor.BLUE) -> MagicColor.BLUE.toInt()
                    StaticChoiceIds.colorIdForMask(MagicColor.BLACK) -> MagicColor.BLACK.toInt()
                    StaticChoiceIds.colorIdForMask(MagicColor.RED) -> MagicColor.RED.toInt()
                    StaticChoiceIds.colorIdForMask(MagicColor.GREEN) -> MagicColor.GREEN.toInt()
                    else -> 0
                }
        }
    return ColorSet.fromMask(mask)
}
