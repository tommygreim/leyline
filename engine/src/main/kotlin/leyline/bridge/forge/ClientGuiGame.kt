package leyline.bridge.forge

import forge.LobbyPlayer
import forge.deck.CardPool
import forge.game.GameEntityView
import forge.game.GameState
import forge.game.GameView
import forge.game.ability.ApiType
import forge.game.card.CardView
import forge.game.event.GameEvent
import forge.game.event.GameEventSpellAbilityCast
import forge.game.event.GameEventSpellRemovedFromStack
import forge.game.phase.PhaseType
import forge.game.player.DelayedReveal
import forge.game.player.IHasIcon
import forge.game.player.Player
import forge.game.player.PlayerView
import forge.game.spellability.SpellAbility
import forge.game.spellability.SpellAbilityView
import forge.game.zone.ZoneType
import forge.gamemodes.match.YieldUpdate
import forge.gui.control.PlaybackSpeed
import forge.gui.interfaces.IGuiGame
import forge.interfaces.IGameController
import forge.item.PaperCard
import forge.localinstance.skin.FSkinProp
import forge.player.PlayerZoneUpdate
import forge.player.PlayerZoneUpdates
import forge.trackable.TrackableCollection
import forge.util.FSerializableFunction
import forge.util.ITriggerEvent
import leyline.DevCheck
import leyline.bridge.handoff.DistributionRouteKind
import leyline.bridge.handoff.DistributionTargetRef
import leyline.bridge.handoff.DistributionWindowValue
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.TargetingCandidateValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import org.slf4j.LoggerFactory

/**
 * Web adapter for [IGuiGame]. Routes interactive choice methods through
 * [InteractivePromptBridge] and stubs desktop-only UI methods as no-ops.
 *
 * This allows [PlayerController] to extend [forge.player.PlayerControllerHuman]
 * and inherit all 157 correctly-implemented card interaction methods.
 */
@Suppress("LargeClass") // IGuiGame adapter: override surface tracks Forge's GUI contract.
class ClientGuiGame(
    private val bridge: InteractivePromptBridge,
    private val currentStackSourceId: () -> Int? = { null },
    private val stackTargetingActive: () -> Boolean = { false },
    private val currentStackTargetingAbility: () -> SpellAbility? = { null },
    private val currentStackTargetIndex: () -> Int = { 1 },
    private val currentStackTargetPromptId: () -> Int? = { null },
    private val playerSeatOf: (Player) -> Int? = { null },
    private val playerViewSeatOf: (PlayerView) -> Int? = { null },
    private val stackTargetCandidate: (Int, Any?) -> TargetingCandidateValue.StackObject? = { _, _ -> null },
    private val currentDividedAllocationAbility: () -> SpellAbility? = { null },
    private val beforeDividedAllocation: (SpellAbility) -> List<DistributionTargetRef> = { emptyList() },
    private val currentSimultaneousAbilities: () -> List<SpellAbility> = { emptyList() },
) : IGuiGame {
    private data class StackTargetOptionSet(
        val candidates: List<TargetingCandidateValue.StackObject>,
        val finishOptionIndex: Int?,
    )

    companion object {
        private val log = LoggerFactory.getLogger(ClientGuiGame::class.java)
        private const val FINISH_TARGETING = "[FINISH TARGETING]"
    }

    // ── Choice primitives → bridge.requestChoice() ────────────────────

    override fun confirm(
        c: CardView?,
        question: String,
    ): Boolean = confirm(c, question, true, listOf("Yes", "No"))

    override fun confirm(
        c: CardView?,
        question: String,
        defaultIsYes: Boolean,
        options: List<String>?,
    ): Boolean {
        val opts = if (options.isNullOrEmpty()) listOf("Yes", "No") else options
        val request =
            PromptRequest(
                promptType = "confirm",
                message = question,
                options = opts,
                min = 1,
                max = 1,
                defaultIndex = if (defaultIsYes) 0 else 1,
            )
        val result = bridge.requestChoice(request)
        return result.firstOrNull() == 0
    }

    override fun showConfirmDialog(
        message: String,
        title: String,
    ): Boolean = confirm(null, "$title: $message")

    override fun showConfirmDialog(
        message: String,
        title: String,
        yesText: String,
        noText: String,
    ): Boolean =
        confirm(
            null,
            "$title: $message",
            true,
            listOf(yesText, noText),
        )

    override fun showConfirmDialog(
        message: String,
        title: String,
        yesText: String,
        noText: String,
        defaultYes: Boolean,
    ): Boolean = confirm(null, "$title: $message", defaultYes, listOf(yesText, noText))

    override fun <T : Any?> one(
        message: String,
        choices: List<T>,
    ): T {
        require(choices.isNotEmpty()) { "one() called with empty list" }
        val labels = choices.map { it?.toString() ?: "(none)" }
        val stackTargetOptions = stackTargetOptions(choices)
        val targetingCandidates = stackTargetOptions.candidates
        if (choices.size == 1 && targetingCandidates.isEmpty()) return choices[0]
        val request =
            PromptRequest(
                promptType = if (targetingCandidates.isNotEmpty()) "choose_cards" else "choose_one",
                message = message,
                options = labels,
                min = if (stackTargetOptions.finishOptionIndex != null) 0 else 1,
                max = 1,
                defaultIndex = firstSelectableIndex(labels),
                targetingCandidates = targetingCandidates,
                targetingFinishOptionIndex = stackTargetOptions.finishOptionIndex,
                targetIndex = currentStackTargetIndex(),
                targetPromptId = currentStackTargetPromptId(),
                isTriggeredAbility = currentStackTargetingAbility()?.isTrigger == true,
                forgeAbilityId = currentStackTargetingAbility()?.id ?: 0,
                route =
                    if (targetingCandidates.isNotEmpty()) {
                        PromptRouteResolver.resolve(PromptSemantic.TargetSelection)
                    } else {
                        PromptRouteResolver.resolve(PromptSemantic.Generic)
                    },
                sourceEntityId = if (targetingCandidates.isNotEmpty()) currentStackSourceId() else null,
            )
        val result = bridge.requestChoice(request, currentStackTargetingAbility().takeIf { targetingCandidates.isNotEmpty() })
        val idx = result.firstOrNull() ?: 0
        return choices.getOrElse(idx) { choices[0] }
    }

    override fun <T : Any?> one(
        message: String,
        choices: List<T>,
        display: FSerializableFunction<T, String>?,
    ): T {
        require(choices.isNotEmpty()) { "one() called with empty list" }
        val labels = choices.map { display?.apply(it) ?: it?.toString() ?: "(none)" }
        val stackTargetOptions = stackTargetOptions(choices)
        val targetingCandidates = stackTargetOptions.candidates
        if (choices.size == 1 && targetingCandidates.isEmpty()) return choices[0]
        val request =
            PromptRequest(
                promptType = if (targetingCandidates.isNotEmpty()) "choose_cards" else "choose_one",
                message = message,
                options = labels,
                min = if (stackTargetOptions.finishOptionIndex != null) 0 else 1,
                max = 1,
                defaultIndex = firstSelectableIndex(labels),
                targetingCandidates = targetingCandidates,
                targetingFinishOptionIndex = stackTargetOptions.finishOptionIndex,
                targetIndex = currentStackTargetIndex(),
                targetPromptId = currentStackTargetPromptId(),
                isTriggeredAbility = currentStackTargetingAbility()?.isTrigger == true,
                forgeAbilityId = currentStackTargetingAbility()?.id ?: 0,
                route =
                    if (targetingCandidates.isNotEmpty()) {
                        PromptRouteResolver.resolve(PromptSemantic.TargetSelection)
                    } else {
                        PromptRouteResolver.resolve(PromptSemantic.Generic)
                    },
                sourceEntityId = if (targetingCandidates.isNotEmpty()) currentStackSourceId() else null,
            )
        val result = bridge.requestChoice(request, currentStackTargetingAbility().takeIf { targetingCandidates.isNotEmpty() })
        val idx = result.firstOrNull() ?: 0
        return choices.getOrElse(idx) { choices[0] }
    }

    override fun <T : Any?> oneOrNone(
        message: String,
        choices: List<T>,
    ): T? {
        if (choices.isNullOrEmpty()) return null
        val labels = choices.map { it?.toString() ?: "(none)" }
        val stackTargetOptions = stackTargetOptions(choices)
        val targetingCandidates = stackTargetOptions.candidates
        if (choices.size == 1 && targetingCandidates.isEmpty()) return choices[0]
        val request =
            PromptRequest(
                promptType = if (targetingCandidates.isNotEmpty()) "choose_cards" else "choose_one",
                message = message,
                options = labels,
                min = if (stackTargetOptions.finishOptionIndex != null) 0 else 1,
                max = 1,
                defaultIndex = firstSelectableIndex(labels),
                targetingCandidates = targetingCandidates,
                targetingFinishOptionIndex = stackTargetOptions.finishOptionIndex,
                targetIndex = currentStackTargetIndex(),
                targetPromptId = currentStackTargetPromptId(),
                isTriggeredAbility = currentStackTargetingAbility()?.isTrigger == true,
                forgeAbilityId = currentStackTargetingAbility()?.id ?: 0,
                route =
                    if (targetingCandidates.isNotEmpty()) {
                        PromptRouteResolver.resolve(PromptSemantic.TargetSelection)
                    } else {
                        PromptRouteResolver.resolve(PromptSemantic.Generic)
                    },
                sourceEntityId = if (targetingCandidates.isNotEmpty()) currentStackSourceId() else null,
            )
        val result = bridge.requestChoice(request, currentStackTargetingAbility().takeIf { targetingCandidates.isNotEmpty() })
        val idx = result.firstOrNull() ?: return null
        return choices.getOrElse(idx) { null }
    }

    override fun <T : Any?> getChoices(
        message: String,
        min: Int,
        max: Int,
        choices: List<T>,
    ): List<T> = getChoices(message, min, max, choices, null, null)

    override fun <T : Any?> getChoices(
        message: String,
        min: Int,
        max: Int,
        choices: List<T>,
        selected: List<T>?,
        display: FSerializableFunction<T, String>?,
    ): List<T> {
        if (choices.isEmpty()) return emptyList()
        val effectiveMax = if (max < 0) choices.size else max.coerceAtMost(choices.size)
        val effectiveMin = min.coerceAtLeast(0).coerceAtMost(effectiveMax)
        if (choices.size <= effectiveMin) return choices.toList()
        val labels = choices.map { display?.apply(it) ?: it?.toString() ?: "(none)" }
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = message,
                options = labels,
                min = effectiveMin,
                max = effectiveMax,
                defaultIndex = 0,
            )
        val indices = bridge.requestChoice(request)
        return indices.filter { it in choices.indices }.map { choices[it] }
    }

    override fun getInteger(
        message: String,
        min: Int,
        max: Int,
        sortDesc: Boolean,
    ): Int? = getInteger(message, min, max, 9)

    override fun getInteger(
        message: String,
        min: Int,
        max: Int,
        cutoff: Int,
    ): Int? {
        // Present as numbered options for small ranges, or just pick from list
        val range = max - min + 1
        if (range <= 0) return min
        val effectiveCutoff = range.coerceAtMost(cutoff.coerceAtMost(20))
        val options = (min..min + effectiveCutoff - 1).map { it.toString() }
        val request =
            PromptRequest(
                promptType = "choose_one",
                message = message,
                options = options,
                min = 1,
                max = 1,
                defaultIndex = 0,
            )
        val result = bridge.requestChoice(request)
        val idx = result.firstOrNull() ?: 0
        return (min + idx).coerceIn(min, max)
    }

    override fun <T : Any?> many(
        title: String,
        topCaption: String,
        cnt: Int,
        sourceChoices: List<T>,
        c: CardView?,
    ): List<T> = many(title, topCaption, cnt, cnt, sourceChoices, c)

    override fun <T : Any?> many(
        title: String,
        topCaption: String,
        min: Int,
        max: Int,
        sourceChoices: List<T>,
        c: CardView?,
    ): List<T> = getChoices(title, min, max, sourceChoices)

    override fun <T : Any?> many(
        title: String,
        topCaption: String,
        min: Int,
        max: Int,
        sourceChoices: List<T>,
        destChoices: List<T>,
        c: CardView?,
    ): List<T> = getChoices(title, min, max, sourceChoices)

    override fun <T : Any?> order(
        title: String,
        top: String,
        sourceChoices: List<T>,
        c: CardView?,
    ): List<T> = order(title, top, 0, 0, sourceChoices, listOf(), c, false)

    override fun <T : Any?> order(
        title: String,
        top: String,
        remainingObjectsMin: Int,
        remainingObjectsMax: Int,
        sourceChoices: List<T>,
        destChoices: List<T>?,
        referenceCard: CardView?,
        sideboardingMode: Boolean,
    ): List<T> =
        order(
            title,
            top,
            remainingObjectsMin,
            remainingObjectsMax,
            sourceChoices,
            destChoices,
            referenceCard,
            sideboardingMode,
            false,
        ).ordered()

    override fun <T : Any?> order(
        title: String,
        top: String,
        remainingObjectsMin: Int,
        remainingObjectsMax: Int,
        sourceChoices: List<T>,
        destChoices: List<T>?,
        referenceCard: CardView?,
        sideboardingMode: Boolean,
        showRememberCheckbox: Boolean,
    ): IGuiGame.OrderResult<T> {
        // Forge's `PCHuman.orderSimultaneousSa` routes simultaneous triggers (CR 603.3b) through
        // here as `SpellAbilityView`s, either the first-time list or the previously chosen
        // order as `destChoices`. PCHuman passes exactly one non-empty list; if a future
        // partial-preselect mode ever populates both, prefer dest (the preselected order is
        // the load-bearing input). The list is ordered resolve-first.
        val orderedDest = destChoices.orEmpty()
        val payload = if (sourceChoices.isNotEmpty()) sourceChoices else orderedDest
        if (payload.firstOrNull { it != null } is SpellAbilityView) {
            return IGuiGame.OrderResult(orderSimultaneousAbilities(payload), false)
        }

        if (sourceChoices.size <= 1) return IGuiGame.OrderResult(sourceChoices.toList(), false)
        // Library / graveyard / search ordering: present as repeated "pick next" via choose_one.
        val remaining = sourceChoices.toMutableList()
        val result = mutableListOf<T>()
        while (remaining.size > 1) {
            val labels = remaining.map { it?.toString() ?: "(none)" }
            val request =
                PromptRequest(
                    promptType = "choose_one",
                    message = "$title (pick ${result.size + 1} of ${sourceChoices.size})",
                    options = labels,
                    min = 1,
                    max = 1,
                    defaultIndex = 0,
                )
            val indices = bridge.requestChoice(request)
            val idx = (indices.firstOrNull() ?: 0).coerceIn(0, remaining.size - 1)
            result.add(remaining.removeAt(idx))
        }
        result.addAll(remaining)
        return IGuiGame.OrderResult(result, false)
    }

    /** The player's stack order for simultaneous triggers; Forge's own order when no prompt is due. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any?> orderSimultaneousAbilities(views: List<T>): List<T> {
        val abilitiesById = currentSimultaneousAbilities().associateBy { it.id }
        val handles = views.map { abilitiesById[(it as SpellAbilityView).id] ?: return views.toList() }
        val request =
            PromptRequest(
                promptType = "order_triggers",
                message = "Choose the order to put triggered abilities on the stack",
                options = views.map { it.toString() },
                min = views.size,
                max = views.size,
                defaultIndex = 0,
                route = PromptRouteResolver.resolve(PromptSemantic.OrderTriggers),
            )
        val result = bridge.requestTriggerOrder(request, handles) ?: return views.toList()
        val viewsById = views.associateBy { (it as SpellAbilityView).id }
        return result.handles.map { viewsById.getValue(it.id) }
    }

    override fun <T : Any?> insertInList(
        title: String,
        newItem: T,
        oldItems: List<T>,
    ): List<T> {
        // Ask where to insert: present positions as options
        val labels =
            (0..oldItems.size).map { pos ->
                if (pos == 0) {
                    "First"
                } else if (pos == oldItems.size) {
                    "Last (after ${oldItems[pos - 1]})"
                } else {
                    "After ${oldItems[pos - 1]}"
                }
            }
        val request =
            PromptRequest(
                promptType = "choose_one",
                message = "$title — where to place $newItem?",
                options = labels,
                min = 1,
                max = 1,
                defaultIndex = 0,
            )
        val result = bridge.requestChoice(request)
        val pos = (result.firstOrNull() ?: 0).coerceIn(0, oldItems.size)
        val combined = oldItems.toMutableList()
        combined.add(pos, newItem)
        return combined
    }

    override fun chooseSingleEntityForEffect(
        title: String,
        optionList: List<GameEntityView>,
        delayedReveal: DelayedReveal?,
        isOptional: Boolean,
    ): GameEntityView? {
        if (optionList.isEmpty()) return null
        if (optionList.size == 1 && !isOptional) return optionList[0]
        val labels = optionList.map { it.toString() }
        val stackTargetOptions = stackTargetOptions(optionList)
        val targetingCandidates = stackTargetOptions.candidates
        val request =
            PromptRequest(
                promptType = if (targetingCandidates.isNotEmpty()) "choose_cards" else "choose_one",
                message = title,
                options = labels,
                min = if (isOptional || stackTargetOptions.finishOptionIndex != null) 0 else 1,
                max = 1,
                defaultIndex = 0,
                targetingCandidates = targetingCandidates,
                targetingFinishOptionIndex = stackTargetOptions.finishOptionIndex,
                targetIndex = currentStackTargetIndex(),
                targetPromptId = currentStackTargetPromptId(),
                isTriggeredAbility = currentStackTargetingAbility()?.isTrigger == true,
                forgeAbilityId = currentStackTargetingAbility()?.id ?: 0,
                route =
                    if (targetingCandidates.isNotEmpty()) {
                        PromptRouteResolver.resolve(PromptSemantic.TargetSelection)
                    } else {
                        PromptRouteResolver.resolve(PromptSemantic.Generic)
                    },
                sourceEntityId = if (targetingCandidates.isNotEmpty()) currentStackSourceId() else null,
            )
        val indices = bridge.requestChoice(request, currentStackTargetingAbility().takeIf { targetingCandidates.isNotEmpty() })
        val idx = indices.firstOrNull()
        if (idx != null && idx in optionList.indices) return optionList[idx]
        return if (isOptional) null else optionList.firstOrNull()
    }

    internal fun stackTargetCandidates(optionList: List<*>): List<TargetingCandidateValue.StackObject> =
        stackTargetOptions(optionList).candidates

    /**
     * Index of the engine's "finish targeting" sentinel, if it offered one.
     *
     * The engine appends it once the minimum target count is met and treats
     * picking it as "no more targets"; picking anything else means "target this
     * too, ask me again". It is a plain option in the list, so a route that
     * answers by index has to know which index ends the sequence — otherwise an
     * optional target group can never terminate.
     */
    private fun finishTargetingIndex(optionList: List<*>): Int? = optionList.indexOfFirst { it == FINISH_TARGETING }.takeIf { it >= 0 }

    /**
     * First option that stands for something selectable.
     *
     * The engine groups target candidates under plain-string zone captions, and
     * the caption sits at index 0 whenever the first candidate is on the
     * battlefield. Answering with a caption is accepted but selects nothing: the
     * engine reports the choice as made, adds no target, and asks again with the
     * same list, so the minimum is never reached and the group never terminates.
     */
    private fun firstSelectableIndex(labels: List<String>): Int = labels.indexOfFirst { !it.isZoneCaption() }.takeIf { it >= 0 } ?: 0

    private fun String.isZoneCaption(): Boolean = startsWith("--CARDS ") && endsWith(":--")

    private fun stackTargetOptions(optionList: List<*>): StackTargetOptionSet {
        if (!stackTargetingActive()) return StackTargetOptionSet(emptyList(), finishTargetingIndex(optionList))
        val candidates =
            optionList.mapIndexed { index, option ->
                index to if (option == FINISH_TARGETING) null else stackTargetCandidate(index, option)
            }
        val expectedCount = optionList.count { it != FINISH_TARGETING }
        val resolvedCount = candidates.count { it.second != null }
        if (resolvedCount != expectedCount) {
            DevCheck.fail {
                "Stack-target option lacks exact engine identity: resolved=$resolvedCount expected=$expectedCount"
            }
            error("Stack-target option lacks exact engine identity")
        }
        return StackTargetOptionSet(
            candidates = candidates.mapNotNull { it.second },
            finishOptionIndex = candidates.firstOrNull { optionList[it.first] == FINISH_TARGETING }?.first,
        )
    }

    override fun chooseEntitiesForEffect(
        title: String,
        optionList: List<GameEntityView>,
        min: Int,
        max: Int,
        delayedReveal: DelayedReveal?,
    ): List<GameEntityView> {
        if (optionList.isEmpty()) return emptyList()
        val effectiveMax = max.coerceAtMost(optionList.size)
        val effectiveMin = min.coerceAtLeast(0).coerceAtMost(effectiveMax)
        if (optionList.size <= effectiveMin) return optionList.toList()
        val labels = optionList.map { it.toString() }
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = title,
                options = labels,
                min = effectiveMin,
                max = effectiveMax,
                defaultIndex = 0,
            )
        val indices = bridge.requestChoice(request)
        return indices.filter { it in optionList.indices }.map { optionList[it] }
    }

    override fun getAbilityToPlay(
        hostCard: CardView,
        abilities: List<SpellAbilityView>,
        triggerEvent: ITriggerEvent?,
    ): SpellAbilityView? {
        if (abilities.isEmpty()) return null
        if (abilities.size == 1) return abilities[0]
        val labels = abilities.map { it.toString() }
        val request =
            PromptRequest(
                promptType = "choose_one",
                message = "Choose ability for ${hostCard.name}",
                options = labels,
                min = 1,
                max = 1,
                defaultIndex = 0,
            )
        val result = bridge.requestChoice(request)
        val idx = result.firstOrNull() ?: 0
        return abilities.getOrElse(idx) { abilities[0] }
    }

    override fun assignCombatDamage(
        attacker: CardView,
        blockers: List<CardView>,
        damage: Int,
        defender: GameEntityView?,
        overrideOrder: Boolean,
        maySkip: Boolean,
    ): Map<CardView, Int> {
        // Shadowed by PlayerController.assignCombatDamage's AssignDamageReq flow for the
        // needsManualAssign case; reachable only via PlayerControllerHuman's own manual-assign
        // check firing on a case that flow doesn't replicate (e.g. no coordinator wired up).
        log.warn(
            "assignCombatDamage fallback: auto-assigning {} damage from {} across {} blockers by toughness",
            damage,
            attacker.name,
            blockers.size,
        )
        DevCheck.failOnAutoPass {
            "assignCombatDamage fallback auto-assigned $damage damage from ${attacker.name} across ${blockers.size} blockers"
        }
        return assignDamageByToughness(blockers, damage)
    }

    private fun assignDamageByToughness(
        blockers: List<CardView>,
        damage: Int,
    ): Map<CardView, Int> {
        if (blockers.isEmpty()) return emptyMap()
        val result = mutableMapOf<CardView, Int>()
        var remaining = damage
        for (blocker in blockers) {
            if (remaining <= 0) break
            // Assign toughness worth of damage to each blocker
            val toAssign = blocker.currentState?.toughness ?: remaining
            val assigned = remaining.coerceAtMost(toAssign)
            result[blocker] = assigned
            remaining -= assigned
        }
        // Assign leftover to last blocker (trample goes through defender)
        if (remaining > 0 && blockers.isNotEmpty()) {
            val last = blockers.last()
            result[last] = (result[last] ?: 0) + remaining
        }
        return result
    }

    override fun assignGenericAmount(
        effectSource: CardView,
        target: Map<Any, Int>,
        amount: Int,
        atLeastOne: Boolean,
        amountLabel: String,
    ): Map<Any, Int> {
        val ability = currentDividedAllocationAbility() ?: return evenDistribution(target, amount, effectSource, amountLabel)
        val semantic =
            when {
                ability.api == ApiType.DealDamage -> PromptSemantic.DividedAllocationDamage
                ability.api == ApiType.PutCounter -> PromptSemantic.DividedAllocationCounters
                else -> return evenDistribution(target, amount, effectSource, amountLabel)
            }
        if (atLeastOne && target.size >= 2 && amount > target.size && effectSource.id > 0) {
            val targetRefs =
                target.keys.map {
                    distributionTargetRef(it)
                        ?: return evenDistribution(target, amount, effectSource, amountLabel)
                }
            if (targetRefs.distinct().size != targetRefs.size || ability.id <= 0) {
                return evenDistribution(target, amount, effectSource, amountLabel)
            }
            val orderedTargets = beforeDividedAllocation(ability)
            if (orderedTargets.size != targetRefs.size || orderedTargets.toSet() != targetRefs.toSet()) {
                return evenDistribution(target, amount, effectSource, amountLabel)
            }
            val window =
                DistributionWindowValue(
                    kind =
                        if (semantic ==
                            PromptSemantic.DividedAllocationDamage
                        ) {
                            DistributionRouteKind.Damage
                        } else {
                            DistributionRouteKind.Counters
                        },
                    targets = orderedTargets,
                    amount = amount,
                    minPerTarget = 1,
                    sourceForgeCardId = effectSource.id,
                    sourceForgeAbilityId = ability.id,
                    sourceIsSpell = ability.isSpell,
                )
            val request =
                PromptRequest(
                    promptType = "distribution",
                    message = amountLabel,
                    options = target.keys.map { (it as? GameEntityView)?.name ?: it.toString() },
                    min = target.size,
                    max = target.size,
                    defaultIndex = 0,
                    route = PromptRouteResolver.resolve(semantic),
                    sourceEntityId = effectSource.id,
                )
            val result = bridge.requestDistribution(request, window)
            return target.keys.associateWith { key -> distributionTargetRef(key)?.let(result.amounts::get) ?: 0 }
        }
        return evenDistribution(target, amount, effectSource, amountLabel)
    }

    private fun evenDistribution(
        target: Map<Any, Int>,
        amount: Int,
        effectSource: CardView,
        amountLabel: String,
    ): Map<Any, Int> {
        // Simplified: distribute evenly, no manual allocation UI.
        if (target.isEmpty()) return emptyMap()
        log.warn(
            "assignGenericAmount: auto-distributing {} {} evenly across {} entities for {}, no manual allocation UI",
            amount,
            amountLabel,
            target.size,
            effectSource.name,
        )
        DevCheck.failOnAutoPass {
            "assignGenericAmount auto-distributed $amount $amountLabel across ${target.size} entities for ${effectSource.name}"
        }
        val perTarget = amount / target.size
        val remainder = amount % target.size
        var i = 0
        return target.keys.associateWith { key ->
            val extra = if (i < remainder) 1 else 0
            i++
            perTarget + extra
        }
    }

    internal fun distributionTargetRef(entity: Any): DistributionTargetRef? =
        when (entity) {
            is Player -> playerSeatOf(entity)?.let { DistributionTargetRef.Player(SeatId(it)) }
            is PlayerView -> playerViewSeatOf(entity)?.let { DistributionTargetRef.Player(SeatId(it)) }
            is GameEntityView -> DistributionTargetRef.Card(ForgeCardId(entity.id))
            else -> null
        }

    override fun sideboard(
        sideboard: CardPool,
        main: CardPool,
        message: String,
    ): List<PaperCard> {
        // Sideboarding not yet supported via web UI
        log.info("Sideboard requested but not implemented in web UI")
        return emptyList()
    }

    override fun showOptionDialog(
        message: String,
        title: String,
        icon: FSkinProp,
        options: List<String>,
        defaultOption: Int,
    ): Int {
        if (options.isEmpty()) return -1
        val request =
            PromptRequest(
                promptType = "choose_one",
                message = "$title: $message",
                options = options,
                min = 1,
                max = 1,
                defaultIndex = defaultOption.coerceIn(0, options.size - 1),
            )
        val result = bridge.requestChoice(request)
        return result.firstOrNull() ?: defaultOption
    }

    override fun showInputDialog(
        message: String,
        title: String,
        icon: FSkinProp?,
        initialInput: String?,
        inputOptions: List<String>?,
        isNumeric: Boolean,
    ): String {
        // If options are provided, use choose_one
        if (!inputOptions.isNullOrEmpty()) {
            val request =
                PromptRequest(
                    promptType = "choose_one",
                    message = "$title: $message",
                    options = inputOptions,
                    min = 1,
                    max = 1,
                    defaultIndex = 0,
                )
            val result = bridge.requestChoice(request)
            val idx = result.firstOrNull() ?: 0
            return inputOptions.getOrElse(idx) { initialInput.orEmpty() }
        }
        // Free text input — return initial or empty for now
        // TODO: text_input prompt type
        log.info("Free text input requested: $title: $message (returning '${initialInput.orEmpty()}')")
        return initialInput.orEmpty()
    }

    override fun manipulateCardList(
        title: String,
        cards: Iterable<CardView>,
        manipulable: Iterable<CardView>,
        toTop: Boolean,
        toBottom: Boolean,
        toAnywhere: Boolean,
    ): MutableList<CardView> {
        // Return manipulable cards as-is: no reordering UI yet.
        log.warn("manipulateCardList: returning '{}' cards unchanged, no reordering UI", title)
        return manipulable.toMutableList()
    }

    // ── Information display → log or no-op ─────────────────────────────

    override fun <T : Any?> reveal(
        message: String,
        items: List<T>,
    ) {
        log.debug("Reveal: $message — ${items.size} items")
    }

    override fun message(message: String) {
        log.debug("Message: $message")
    }

    override fun message(
        message: String,
        title: String,
    ) {
        log.debug("Message [$title]: $message")
    }

    override fun showErrorDialog(message: String) {
        log.warn("Error dialog: $message")
    }

    override fun showErrorDialog(
        message: String,
        title: String,
    ) {
        log.warn("Error dialog [$title]: $message")
    }

    // ── UI state management → no-op ────────────────────────────────────

    private var gameView: GameView? = null

    override fun setGameView(gameView: GameView) {
        this.gameView = gameView
    }

    override fun getGameView(): GameView? = gameView

    override fun setOriginalGameController(
        view: PlayerView,
        gameController: IGameController,
    ) {}

    override fun setGameController(
        player: PlayerView,
        gameController: IGameController,
    ) {}

    override fun setSpectator(spectator: IGameController) {}

    override fun openView(myPlayers: TrackableCollection<PlayerView>) {}

    override fun afterGameEnd() {}

    override fun showCombat() {}

    override fun showPromptMessage(
        playerView: PlayerView,
        message: String,
    ) {}

    override fun showPromptMessage(
        playerView: PlayerView,
        message: String,
        card: CardView?,
    ) {}

    override fun updateButtons(
        owner: PlayerView,
        okEnabled: Boolean,
        cancelEnabled: Boolean,
        focusOk: Boolean,
    ) {}

    override fun updateButtons(
        owner: PlayerView,
        label1: String,
        label2: String,
        enable1: Boolean,
        enable2: Boolean,
        focus1: Boolean,
    ) {}

    override fun flashIncorrectAction() {}

    override fun alertUser() {}

    override fun updatePhase(saveState: Boolean) {}

    override fun updateTurn(player: PlayerView) {}

    override fun updatePlayerControl() {}

    override fun enableOverlay() {}

    override fun disableOverlay() {}

    override fun finishGame() {}

    override fun showManaPool(player: PlayerView) {}

    override fun hideManaPool(player: PlayerView) {}

    override fun updateStack() {}

    override fun notifyStackAddition(event: GameEventSpellAbilityCast) {}

    override fun notifyStackRemoval(event: GameEventSpellRemovedFromStack) {}

    override fun handleLandPlayed(land: CardView) {}

    override fun handleGameEvent(event: GameEvent) {}

    override fun tempShowZones(
        controller: PlayerView,
        zonesToUpdate: Iterable<PlayerZoneUpdate>,
    ): Iterable<PlayerZoneUpdate> = zonesToUpdate

    override fun hideZones(
        controller: PlayerView,
        zonesToUpdate: Iterable<PlayerZoneUpdate>,
    ) {}

    override fun updateZones(zonesToUpdate: Iterable<PlayerZoneUpdate>) {}

    override fun updateSingleCard(card: CardView) {}

    override fun updateCards(cards: Iterable<CardView>) {}

    override fun updateRevealedCards(collection: TrackableCollection<CardView>) {}

    override fun refreshCardDetails(cards: Iterable<CardView>) {}

    override fun refreshField() {}

    override fun getGamestate(): GameState? = null

    override fun updateManaPool(manaPoolUpdate: Iterable<PlayerView>) {}

    override fun updateLives(livesUpdate: Iterable<PlayerView>) {}

    override fun updateShards(shardsUpdate: Iterable<PlayerView>) {}

    override fun updateDependencies() {}

    override fun setPanelSelection(hostCard: CardView) {}

    override fun setCard(card: CardView) {}

    override fun setPlayerAvatar(
        player: LobbyPlayer,
        ihi: IHasIcon,
    ) {}

    override fun openZones(
        controller: PlayerView,
        zones: Collection<ZoneType>,
        players: Map<PlayerView, Any>,
        backupLastZones: Boolean,
    ): PlayerZoneUpdates = PlayerZoneUpdates()

    override fun restoreOldZones(
        playerView: PlayerView,
        playerZoneUpdates: PlayerZoneUpdates,
    ) {}

    override fun setHighlighted(
        entities: Iterable<GameEntityView>,
        b: Boolean,
    ) {}

    override fun setSelectables(
        cards: Iterable<CardView>,
        min: Int,
        max: Int,
    ) {}

    override fun setWeaklySelectable(cards: Iterable<CardView>) {}

    override fun clearWeaklySelectable() {}

    override fun applyDelta(packet: forge.gamemodes.net.DeltaPacket) {}

    override fun clearSelectables() {}

    override fun isSelecting(): Boolean = false

    override fun isGamePaused(): Boolean = false

    override fun setGamePause(pause: Boolean) {}

    override fun getGameSpeed(): PlaybackSpeed = PlaybackSpeed.NORMAL

    override fun setGameSpeed(gameSpeed: PlaybackSpeed) {}

    // Day/Night state mirrors `forge.game.Game.getDayTime()` and is surfaced on
    // the wire via the `Designation` annotation family in StateMapper. These
    // IGuiGame methods exist only to satisfy Forge's GUI contract — the engine
    // bridge does not consume them. Backing field mirrors `AbstractGuiGame`
    // semantics so a future reader sees a normal accessor pair, not a lie.
    private var daytime: String? = null

    override fun getDayTime(): String? = daytime

    override fun updateDayTime(daytime: String?) {
        this.daytime = daytime
    }

    override fun awaitNextInput() {}

    override fun cancelAwaitNextInput() {}

    override fun isUiSetToSkipPhase(
        playerTurn: PlayerView,
        phase: PhaseType,
    ): Boolean = false

    override fun updateAutoPassPrompt() {}

    override fun applyYieldUpdate(update: YieldUpdate) {}

    // Auto-yield + trigger-accept/decline live on IGameController, not IGuiGame.
    // ClientGuiGame implements IGuiGame only; those preferences are client-side
    // toggles and don't apply to the headless server path.

    override fun setCurrentPlayer(player: PlayerView) {}

    override fun showWaitingTimer(
        forPlayer: PlayerView,
        waitingForPlayerName: String,
    ) {}

    override fun isNetGame(): Boolean = false

    override fun setNetGame() {}
}
