package leyline.acceptance

import forge.game.card.Card
import forge.game.player.Player
import forge.game.zone.ZoneType
import leyline.bridge.coord.GameLoopPoller
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PromptCallStatus
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.data.ForgeCardRepository
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import leyline.tooling.headless.dumpDiagnostics
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecType
import wotc.mtgo.gre.external.messaging.Messages.DamageRecipient
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import java.nio.file.Files

class MatchdoorAcceptanceExecutor(
    private val seed: Long = 42L,
) {
    fun runScenario(
        scenario: AcceptanceScenario,
        onComplete: (List<GREToClientMessage>) -> Unit = {},
    ): Int {
        require(scenario.steps.isNotEmpty()) { "scenario ${scenario.id} has no executable steps" }
        val harness =
            MatchFlowHarness(
                seed = seed,
                deckList = scenario.deckList,
                opponentDeckList = scenario.opponentDeckList,
                fullControl = scenario.fullControl,
                cardRepositoryOverride = if (scenario.forgeCatalog) ForgeCardRepository.open() else null,
            )
        try {
            scenario.puzzle?.let { harness.connectAndKeepPuzzleText(readPuzzleText(it)) } ?: harness.connectAndKeep()
            val run = ScenarioRun(harness, scenario.id)
            var remainingOptionalActions = scenario.steps.count { it is OptionalActionStep }
            if (remainingOptionalActions > 0) harness.holdNextOptionalAction()
            scenario.steps.forEachIndexed { index, step ->
                run.executeStep(index, step)
                if (step is OptionalActionStep && --remainingOptionalActions > 0) {
                    harness.holdNextOptionalAction()
                }
                if (!harness.isGameOver()) {
                    harness.accumulator.assertConsistent("${scenario.id} step ${index + 1} ${step.label}")
                }
            }
            onComplete(harness.allMessages.toList())
            return scenario.steps.size
        } catch (failure: Throwable) {
            harness.dumpDiagnostics(scenario.id)
            throw failure
        } finally {
            harness.shutdown()
        }
    }
}

private fun readPuzzleText(puzzle: String): String {
    val fileName = if (puzzle.endsWith(".pzl")) puzzle else "$puzzle.pzl"
    return Files.readString(AcceptancePaths.resolve("data/puzzles/$fileName", notFoundMessage = "puzzle not found: $fileName"))
}

private val OUR_SEAT = SeatId(1)
private val OPPONENT_SEAT = SeatId(2)

internal fun stackResolutionNeedsAdvance(
    passCount: Int,
    stackEmpty: Boolean,
    pendingKind: PendingActionKind?,
    postActionHorizonPublished: Boolean = false,
): Boolean =
    when {
        pendingKind == PendingActionKind.DECLARE_ATTACKERS || pendingKind == PendingActionKind.DECLARE_BLOCKERS -> false
        !stackEmpty -> true
        pendingKind == PendingActionKind.PRIORITY -> !postActionHorizonPublished && passCount == 0
        passCount == 0 -> true
        pendingKind == PendingActionKind.SYNC_ONLY -> true
        else -> false
    }

private fun seat(side: AcceptanceSide): SeatId =
    when (side) {
        AcceptanceSide.Ours -> OUR_SEAT
        AcceptanceSide.Opponent -> OPPONENT_SEAT
    }

private fun phaseMatches(
    actual: String?,
    expected: String,
): Boolean = actual == expected.toForgePhaseName()

private fun AcceptanceManaTypeChoice.toManaColor(): ManaColor =
    when (this) {
        AcceptanceManaTypeChoice.TwoGeneric -> ManaColor.TwoGeneric
        AcceptanceManaTypeChoice.White -> ManaColor.White_afc9
        AcceptanceManaTypeChoice.Blue -> ManaColor.Blue_afc9
        AcceptanceManaTypeChoice.Black -> ManaColor.Black_afc9
        AcceptanceManaTypeChoice.Red -> ManaColor.Red_afc9
        AcceptanceManaTypeChoice.Green -> ManaColor.Green_afc9
    }

/** A condition's match outcome alongside the "actual" text used in failure diagnostics. */
private data class ConditionResult(
    val matched: Boolean,
    val actual: String,
)

/**
 * Drives one scenario's steps against a single [MatchFlowHarness]. Holds the harness plus the
 * current step's error-context string so step/condition/card-resolution helpers don't have to
 * thread both through every call.
 */
@Suppress("LargeClass") // Grows one small adapter per backend-neutral DSL verb.
private class ScenarioRun(
    val harness: MatchFlowHarness,
    private val scenarioId: String,
) {
    lateinit var context: String
        private set
    private var observationStart = 0
    private var postActionMessageStart: Int? = null

    @Suppress("CyclomaticComplexMethod")
    fun executeStep(
        index: Int,
        step: AcceptanceStep,
    ) {
        context = "$scenarioId step ${index + 1} (${step.label})"
        if (step !is WaitStep && step !is ExpectStep && step !is ResolveStackStep) {
            postActionMessageStart = harness.messageSnapshot()
        }
        when (step) {
            is WaitStep -> {
                assertConditions(step.conditions)
                observationStart = harness.allMessages.size
            }
            is ExpectStep -> {
                assertConditions(step.conditions)
                observationStart = harness.allMessages.size
            }
            is PassUntilStep -> passUntil(step)
            is ActivateStep -> activate(step)
            is ChooseStep -> choose(step)
            is ManaTypeChoicesStep -> manaTypeChoices(step)
            is ModalChoiceStep -> modalChoice(step)
            is StaticChoiceStep -> staticChoice(step)
            is OptionalActionStep -> respondToOptionalAction(step)
            CancelActionStep -> harness.cancelAction()
            is TargetStep -> target(step.target)
            is TargetsStep -> targets(step.targets)
            is DistributeStep -> distribute(step)
            is SelectCostStep -> selectCost(step)
            is SelectCardStep -> selectCard(step)
            is SelectCardsStep -> selectCards(step)
            is SearchCardsStep -> searchCards(step)
            is OrderCardsStep -> orderCards(step)
            is BlockStep -> block(step)
            is AttackStep -> attack(step)
            is TurnFaceUpStep -> turnFaceUp(step)
            is PlayLandStep -> playLand(step)
            is PlayMdfcStep -> submitNamedAction(ActionType.PlayMdfc, step.card)
            is CastStep -> cast(step)
            is CastAdventureStep -> submitNamedAction(ActionType.CastAdventure, step.card)
            is CastOmenStep -> submitNamedAction(ActionType.CastOmen, step.card)
            is CastMdfcStep -> submitNamedAction(ActionType.CastMdfc, step.card)
            ResolveStackStep -> resolveStack()
            AttackAllStep -> {
                harness.declareAllAttackers()
                harness.submitAttackers()
            }
        }
    }

    private fun playLand(step: PlayLandStep) {
        if (harness.hasPendingSelectNPrompt()) {
            val prompt = latestPromptMessage()
            require(prompt?.hasSelectNReq() == true) {
                "$context active SelectN interaction has no SelectNReq"
            }
            val instanceId = resolveCardInZone(AcceptanceSide.Ours, AcceptanceZone.Hand, step.card)
            require(instanceId in prompt.selectNReq.idsList) {
                "$context land $step.card iid=$instanceId is not in SelectNReq candidates ${prompt.selectNReq.idsList}"
            }
            harness.respondToSelectN(listOf(instanceId))
            return
        }
        requireAction { harness.playLand(step.card) }
    }

    private fun cast(step: CastStep) {
        val action =
            harness.accumulator.actions?.actionsList.orEmpty().firstOrNull { action ->
                action.actionType == ActionType.Cast &&
                    actionCardName(action).equals(step.card, ignoreCase = true) &&
                    actionMatchesZone(action, step.zone) &&
                    actionMatchesAltCost(action, step.altCost)
            } ?: error("$context no cast action for ${step.card} in ${step.zone.yamlName}")
        submitAction(action)
    }

    private fun submitNamedAction(
        actionType: ActionType,
        card: String,
    ) {
        val candidates =
            harness.accumulator.actions
                ?.actionsList
                .orEmpty()
                .filter { it.actionType == actionType }
        val action =
            candidates.firstOrNull { actionCardName(it).equals(card, ignoreCase = true) }
                ?: candidates
                    .singleOrNull()
                    ?.takeIf {
                        actionType in listOf(ActionType.PlayMdfc, ActionType.CastMdfc, ActionType.CastAdventure, ActionType.CastOmen)
                    }
                ?: error("$context no named ${actionType.name} action for $card")
        submitAction(action)
    }

    private fun selectCost(step: SelectCostStep) {
        val ids = step.cards.map { resolveCardInZone(step.side, step.zone, it) }
        val prompt = latestPromptMessage()
        val activePayCosts =
            harness.bridge
                .cutCoordinator
                .oneShotPayCosts
                .current()
        when {
            prompt?.hasSelectNReq() == true ->
                selectCards(SelectCardsStep(step.side, step.zone, step.cards))
            prompt?.hasSelectTargetsReq() == true -> {
                val offered =
                    prompt.selectTargetsReq.targetsList
                        .flatMap { it.targetsList }
                        .map { it.targetInstanceId }
                ids.forEach { id ->
                    require(id in offered) {
                        "$context selected cost iid=$id is not in SelectTargets candidates $offered"
                    }
                }
                harness.selectTargets(ids)
            }
            prompt?.hasPayCostsReq() == true || activePayCosts != null ->
                harness.respondToEffectCost(ids)
            prompt == null -> acceptDefaultedCardCost(step)
            else -> error("$context expected active PayCosts or SelectN/SelectTargets prompt")
        }
    }

    private fun acceptDefaultedCardCost(step: SelectCostStep) {
        val record =
            harness.bridge
                .promptBridge(OUR_SEAT)
                .history
                .lastOrNull()
        require(record != null) {
            "$context expected typed cost fallback, history=$record " +
                "messages=${harness.allMessages.takeLast(8).map { it.promptName() }}"
        }
        when (val route = record.route) {
            is ResolvedPromptRoute.CompatibilityCostSelection -> Unit
            is ResolvedPromptRoute.PayCosts ->
                require(route.descriptor.tapPayment != null) {
                    "$context grounded tap cost lost its TapPayment descriptor: $record"
                }
            else -> error("$context expected CompatibilityCostSelection or grounded TapPayment route: $record")
        }
        require(
            record.outcome in
                setOf(
                    PromptCallStatus.DEFAULTED_POLICY,
                    PromptCallStatus.NON_GAME_THREAD,
                    PromptCallStatus.NON_INTERACTIVE_SCOPE,
                ),
        ) {
            "$context typed cost was not defaulted safely: $record"
        }
        val expectedIndices =
            step.cards.map { card ->
                record.options.indexOfFirst { it.equals(card, ignoreCase = true) }.also {
                    require(it >= 0) { "$context defaulted cost card $card is not in options ${record.options}" }
                }
            }
        require(record.result == expectedIndices) {
            "$context defaulted cost result ${record.result} does not match $expectedIndices"
        }
    }

    private fun selectCard(step: SelectCardStep) = selectCards(SelectCardsStep(step.side, step.zone, listOf(step.card)))

    private fun distribute(step: DistributeStep) {
        val prompt = latestPromptMessage()
        require(prompt?.hasDistributionReq() == true) {
            "$context expected latest prompt DistributionReq"
        }
        val amounts =
            step.assignments.map { assignment ->
                resolveCardInZone(assignment.side, AcceptanceZone.Battlefield, assignment.card) to assignment.amount
            }
        require(amounts.map { it.first }.toSet() == prompt.distributionReq.targetIdsList.toSet()) {
            "$context assignments ${amounts.map { it.first }} do not match DistributionReq targets ${prompt.distributionReq.targetIdsList}"
        }
        harness.respondToDistribution(amounts)
    }

    private fun selectCards(step: SelectCardsStep) {
        val prompt = latestPromptMessage()
        require(prompt?.hasSelectNReq() == true) {
            "$context expected latest prompt SelectNReq"
        }
        val selectedIds = step.cards.map { resolveCardInZone(step.side, step.zone, it) }
        selectedIds.zip(step.cards).forEach { (selectedId, card) ->
            require(selectedId in prompt.selectNReq.idsList) {
                "$context selected $card iid=$selectedId is not in SelectNReq candidates ${prompt.selectNReq.idsList}"
            }
        }
        if (step.zone == AcceptanceZone.Sideboard) {
            require(prompt.prompt.promptId == PromptIds.LEARN_LESSON_OR_DISCARD || prompt.prompt.promptId == PromptIds.LEARN_LESSON_ONLY) {
                "$context sideboard selection expected Learn prompt, got promptId=${prompt.prompt.promptId}"
            }
        }
        harness.respondToSelectN(selectedIds)
    }

    private fun searchCards(step: SearchCardsStep) {
        val prompt = latestPromptMessage()
        require(prompt?.let { it.hasSearchReq() || it.hasSearchFromGroupsReq() } == true) {
            "$context expected latest prompt SearchReq or SearchFromGroupsReq"
        }
        val libraryIds = cardsInZone(step.side, AcceptanceZone.Library).map { harness.bridge.instanceId(it) }.toSet()
        val candidates =
            if (prompt.hasSearchFromGroupsReq()) {
                prompt.searchFromGroupsReq.groupsList
                    .flatMap { it.idsList }
                    .distinct()
            } else {
                prompt.searchReq.itemsSoughtList
            }.filter { it in libraryIds }
        val selectedIds = resolvePromptCardSelection(candidates, step.cards, "search")
        if (prompt.hasSearchFromGroupsReq()) {
            if (selectedIds.isEmpty()) {
                harness.respondToGroupedSearchFail()
            } else {
                val group = prompt.searchFromGroupsReq.groupsList.single { it.idsList.containsAll(selectedIds) }
                harness.respondToGroupedSearch(group.groupId, selectedIds, group.maxSelect)
            }
        } else {
            harness.respondToSearch(selectedIds)
        }
    }

    private fun orderCards(step: OrderCardsStep) {
        val prompt = latestPromptMessage()
        require(prompt?.hasOrderReq() == true) {
            "$context expected latest prompt OrderReq"
        }
        val orderedIds = resolvePromptCardOrder(prompt.orderReq.idsList, step.cards)
        harness.respondToOrder(orderedIds)
    }

    private fun activate(step: ActivateStep) {
        val matching =
            harness.accumulator.actions?.actionsList.orEmpty().filter { action ->
                action.actionType == ActionType.Activate_add3 &&
                    actionCardName(action).equals(step.card, ignoreCase = true) &&
                    actionMatchesZone(action, step.zone) &&
                    (step.abilityGrpId == null || action.abilityGrpId == step.abilityGrpId)
            }
        val selector = step.abilityGrpId?.toString() ?: "index ${step.abilityIndex}"
        val action =
            (if (step.abilityGrpId != null) matching.singleOrNull() else matching.getOrNull(step.abilityIndex))
                ?: error(
                    "$context no activate action $selector for ${step.card} in ${step.zone.yamlName}",
                )
        submitAction(action)
    }

    private fun choose(step: ChooseStep) {
        if (step.ctoId != null) {
            harness.respondToOptionalCost(step.ctoId)
            return
        }
        if (step.optionalCost == AcceptanceCastingTimeOption.Blight) {
            val option =
                harness.allMessages
                    .lastOrNull { it.hasCastingTimeOptionsReq() }
                    ?.castingTimeOptionsReq
                    ?.castingTimeOptionReqList
                    ?.firstOrNull { it.castingTimeOptionType == CastingTimeOptionType.ChooseOrCost }
                    ?: error("$context missing blight ChooseOrCost option")
            require(
                option.selectNReq.prompt.parametersList
                    .any { it.promptId == PromptIds.CHOOSE_OR_COST_PAY_BLIGHT },
            ) {
                "$context ChooseOrCost option is not the Blight branch"
            }
            harness.respondToAlternateCost(option.ctoId, option.selectNReq.idsList.first())
            return
        }
        val option =
            harness.allMessages
                .lastOrNull { it.hasCastingTimeOptionsReq() }
                ?.castingTimeOptionsReq
                ?.castingTimeOptionReqList
                ?.firstOrNull { it.castingTimeOptionType == step.optionalCost!!.toProtoType() }
                ?: error(
                    "$context missing ${step.optionalCost?.yamlName} option in latest CastingTimeOptionsReq",
                )
        harness.respondToOptionalCost(option.ctoId)
    }

    private fun manaTypeChoices(step: ManaTypeChoicesStep) {
        val options =
            harness.allMessages
                .lastOrNull { it.hasCastingTimeOptionsReq() }
                ?.castingTimeOptionsReq
                ?.castingTimeOptionReqList
                ?.filter { it.castingTimeOptionType == CastingTimeOptionType.ManaType }
                ?: error("$context missing ManaType options in latest CastingTimeOptionsReq")
        require(options.size == step.choices.size) {
            "$context expected ${step.choices.size} ManaType choices, got ${options.size}"
        }
        harness.respondToManaTypeChoices(options.zip(step.choices).map { (option, choice) -> option.ctoId to choice.toManaColor() })
    }

    private fun modalChoice(step: ModalChoiceStep) {
        val option =
            harness.allMessages
                .lastOrNull { it.hasCastingTimeOptionsReq() }
                ?.castingTimeOptionsReq
                ?.castingTimeOptionReqList
                ?.flatMap { it.modalReq.modalOptionsList }
                ?.getOrNull(step.index)
                ?: error("$context missing modal option index ${step.index}")
        harness.respondModalChoice(listOf(option.grpId))
    }

    private fun staticChoice(step: StaticChoiceStep) {
        val prompt = latestPromptMessage()
        require(prompt?.hasSelectNReq() == true) {
            "$context expected latest prompt SelectNReq"
        }
        val req = prompt.selectNReq
        // Dungeon and room choices have fixed protocol values, but their native
        // workflows require a Dynamic list rather than a generic static enum.
        val kind =
            harness.bridge.cutCoordinator.staticChoices
                .current()
                ?.kind
        val dynamicDungeonChoice =
            req.listType == SelectionListType.Dynamic &&
                (kind == StaticChoiceKind.Dungeon || kind == StaticChoiceKind.DungeonRoom)
        require(req.listType == SelectionListType.Static || req.listType == SelectionListType.StaticSubset || dynamicDungeonChoice) {
            "$context expected static or dungeon SelectNReq, got listType=${req.listType}, kind=$kind"
        }
        require(req.idsList.isEmpty() || step.id in req.idsList) {
            "$context static choice id=${step.id} not in SelectNReq ids ${req.idsList}"
        }
        harness.respondToSelectN(listOf(step.id))
    }

    private fun target(target: AcceptanceTargetSpec) {
        require(latestPromptMatches("SelectTargetsReq")) {
            "$context expected latest prompt SelectTargetsReq; actual=${latestPromptNameWithId() ?: "none"}"
        }
        harness.selectTargets(listOf(resolveTargetInstanceId(target)))
    }

    private fun targets(targets: List<AcceptanceTargetSpec>) {
        require(latestPromptMatches("SelectTargetsReq")) {
            "$context expected latest prompt SelectTargetsReq; actual=${latestPromptNameWithId() ?: "none"}"
        }
        val targetIds = targets.map(::resolveTargetInstanceId)
        harness.selectTargetsIterative(targetIds)
        harness.submitTargets()
    }

    private fun block(step: BlockStep) {
        require(latestPromptMatches("DeclareBlockersReq")) {
            "$context expected latest prompt DeclareBlockersReq"
        }
        val blockerId = resolveBattlefieldCard(AcceptanceSide.Ours, step.blocker)
        val attackerId = resolveBattlefieldCard(AcceptanceSide.Opponent, step.attacker)
        harness.declareBlockers(mapOf(blockerId to attackerId))
    }

    private fun attack(step: AttackStep) {
        require(latestPromptMatches("DeclareAttackersReq")) {
            "$context expected latest prompt DeclareAttackersReq"
        }
        val attackerIds = step.cards.map { resolveBattlefieldCard(AcceptanceSide.Ours, it) }
        val alternatives = step.altCost?.let { altCost -> attackerIds.associateWith { altCost.keywordAbilityId } }.orEmpty()
        val damageRecipients =
            step.target
                ?.let { target -> attackerIds.associateWith { damageRecipientForAttackTarget(target) } }
                .orEmpty()
        harness.toggleAttackers(attackerIds, alternatives, damageRecipients)
        harness.submitAttackers()
    }

    private fun damageRecipientForAttackTarget(target: AcceptanceTargetSpec): DamageRecipient =
        when (target) {
            is PlayerTargetSpec ->
                DamageRecipient
                    .newBuilder()
                    .setType(DamageRecType.Player_a0e5)
                    .setPlayerSystemSeatId(seat(target.side).value)
                    .build()

            is CardTargetSpec -> {
                require(target.side == AcceptanceSide.Opponent && target.zone == AcceptanceZone.Battlefield) {
                    "$context attack target must be an opponent battlefield planeswalker, got ${target.label}"
                }
                val card =
                    cardsInZone(target.side, target.zone)
                        .firstOrNull { it.name.equals(target.card, ignoreCase = true) }
                        ?: error("$context could not find attack target ${target.label}")
                require(card.isPlaneswalker) { "$context attack target ${target.card} is not a planeswalker" }
                DamageRecipient
                    .newBuilder()
                    .setType(DamageRecType.PlanesWalker)
                    .setPlaneswalkerInstanceId(harness.bridge.instanceId(card))
                    .build()
            }
        }

    private fun turnFaceUp(step: TurnFaceUpStep) {
        val card =
            player(AcceptanceSide.Ours)
                .getZone(ZoneType.Battlefield)
                .cards
                .firstOrNull { it.name.equals(step.card, ignoreCase = true) || it.isFaceDown }
                ?: error("$context could not find ${step.card} or a face-down card on battlefield")
        val instanceId = harness.bridge.instanceId(card)
        val action =
            harness.accumulator.actions
                ?.actionsList
                .orEmpty()
                .firstOrNull {
                    it.actionType == ActionType.SpecialTurnFaceUp_add3 &&
                        it.instanceId == instanceId
                } ?: error("$context no turn-face-up action for ${step.card} iid=$instanceId")
        submitAction(action)
        require(!card.isFaceDown) {
            "$context turn-face-up action did not resolve; " +
                "action=${actionSummary(action)}; " +
                "latest prompt=${latestPromptNameWithId() ?: "none"}; " +
                "actions=${harness.accumulator.actions?.actionsList.orEmpty().joinToString { actionSummary(it) }}"
        }
    }

    private fun resolveStack() {
        val actionMessageStart = postActionMessageStart ?: harness.messageSnapshot()
        postActionMessageStart = null
        repeat(12) { index ->
            if (harness.isGameOver()) return
            // `resolve_stack` has no cast-choice instruction. Decline an optional
            // resolution browser so the source can complete without silently
            // choosing a spell for the scenario.
            val blocking =
                harness.bridge.cutCoordinator
                    .currentBlockingInteraction()
            val resolutionCast = blocking?.interaction as? BlockingInteraction.ResolutionCast
            if (resolutionCast != null) {
                require(resolutionCast.optional) { "$context cannot auto-decline a mandatory resolution cast" }
                val picker =
                    requireNotNull(
                        harness.allMessages.lastOrNull {
                            it.hasActionsAvailableReq() &&
                                it.prompt.promptId == resolutionCast.promptId &&
                                it.gameStateId == blocking.gameStateId
                        },
                    ) { "$context resolution-cast picker was not published" }
                harness.submitAction(picker.actionsAvailableReq.actionsList.single { it.actionType == ActionType.Pass })
                return@repeat
            }
            val pending =
                harness.bridge
                    .actionBridge(OUR_SEAT)
                    .getPending()
            val postActionHorizonPublished =
                pending?.takeUnless { it.state.kind == PendingActionKind.SYNC_ONLY }?.let {
                    harness.pendingActionHorizonPublished(it, actionMessageStart)
                } == true
            if (!stackResolutionNeedsAdvance(
                    index,
                    harness.game().stack.isEmpty,
                    pending?.state?.kind,
                    postActionHorizonPublished,
                )
            ) {
                pending?.takeUnless { it.state.kind == PendingActionKind.SYNC_ONLY }?.let {
                    harness.awaitPendingActionHorizon(it, actionMessageStart)
                }
                return
            }
            harness.advance()
            if (harness.isGameOver()) return
            if (harness.bridge.cutCoordinator
                    .currentBlockingInteraction()
                    ?.interaction is
                    leyline.bridge.handoff.BlockingInteraction.Optional
            ) {
                return
            }
            val nextPending =
                harness.bridge
                    .actionBridge(OUR_SEAT)
                    .getPending()
            if (harness.game().stack.isEmpty && nextPending?.state?.kind != PendingActionKind.SYNC_ONLY) {
                nextPending?.takeUnless { it.state.kind == PendingActionKind.SYNC_ONLY }?.let {
                    harness.awaitPendingActionHorizon(it, actionMessageStart)
                }
                return
            }
        }
        error(
            "$context did not resolve stack; stack size=${harness.game().stack.size()}",
        )
    }

    private fun passUntil(step: PassUntilStep) {
        harness.passUntil(maxPasses = step.maxPasses) { passUntilConditionReached(step) }
        val reached = runCatching { step.conditions.all { matchesPassUntilCondition(it) } }.getOrDefault(false)
        require(reached) {
            "$context did not reach: ${step.conditions.joinToString { it.label }}; " +
                "latest prompt=${latestPromptNameWithId() ?: "none"}; " +
                "turn=${harness.turn()} phase=${harness.phase()} pending=${harness.bridge.actionBridge(
                    OUR_SEAT,
                ).getPending()?.state?.kind}; " +
                "life=${harness.human.life}/${harness.ai.life}; " +
                "prompts=${harness.allMessages.filter { it.isPromptMessage() }.map { it.promptName() + "#" + it.prompt.promptId }}; " +
                "actions=${harness.accumulator.actions?.actionsList.orEmpty().joinToString { actionSummary(it) }}"
        }
    }

    private fun respondToOptionalAction(step: OptionalActionStep) {
        val ready =
            harness.passUntil(maxPasses = 20) {
                when (
                    harness.bridge.cutCoordinator
                        .currentBlockingInteraction()
                        ?.interaction
                ) {
                    is leyline.bridge.handoff.BlockingInteraction.Optional,
                    is leyline.bridge.handoff.BlockingInteraction.TopOrBottom,
                    -> true
                    else -> false
                }
            }
        require(ready) { "$context optional action did not become pending" }
        harness.respondToOptionalAction(step.accept)
    }

    private fun passUntilConditionReached(step: PassUntilStep): Boolean =
        try {
            GameLoopPoller.awaitCondition(timeoutMs = 200, pollIntervalMs = 20) {
                harness.drainSink()
                step.conditions.all { matchesPassUntilCondition(it) }
            }
            true
        } catch (_: Throwable) {
            false
        }

    private fun matchesPassUntilCondition(condition: AcceptanceCondition): Boolean =
        if (condition is PromptCondition) {
            latestPromptMessage()?.let { prompt ->
                prompt.matchesPrompt(condition.prompt, condition.promptId) &&
                    harness.bridge.responseAcceptance.hasOutstandingPrompt(prompt.msgId)
            } == true
        } else {
            matchesCondition(condition)
        }

    private fun assertConditions(conditions: List<AcceptanceCondition>) {
        val failures = conditions.map { it to evaluateCondition(it) }.filterNot { (_, result) -> result.matched }
        require(failures.isEmpty()) {
            "$context failed: ${failures.joinToString("; ") { (condition, result) -> "${condition.label}; actual ${result.actual}" }}"
        }
    }

    private fun matchesCondition(condition: AcceptanceCondition): Boolean = evaluateCondition(condition).matched

    @Suppress("CyclomaticComplexMethod")
    private fun evaluateCondition(condition: AcceptanceCondition): ConditionResult =
        when (condition) {
            is ActionAvailableCondition ->
                ConditionResult(
                    actionAvailable(condition),
                    "actions=${harness.accumulator.actions?.actionsList.orEmpty().joinToString { actionSummary(it) }}",
                )

            is ActionUnavailableCondition ->
                ConditionResult(
                    !actionAvailable(
                        ActionAvailableCondition(
                            type = condition.type,
                            card = condition.card,
                            altCost = condition.altCost,
                            abilityGrpId = condition.abilityGrpId,
                        ),
                    ),
                    "actions=${harness.accumulator.actions?.actionsList.orEmpty().joinToString { actionSummary(it) }}",
                )

            is ZoneContainsCondition -> {
                val names = zoneCardNames(condition.side, condition.zone)
                ConditionResult(
                    names.any { it.equals(condition.card, ignoreCase = true) },
                    "${condition.side.yamlName} ${condition.zone.yamlName}=$names",
                )
            }

            is ZoneNotContainsCondition -> {
                val names = zoneCardNames(condition.side, condition.zone)
                ConditionResult(
                    names.none { it.equals(condition.card, ignoreCase = true) },
                    "${condition.side.yamlName} ${condition.zone.yamlName}=$names",
                )
            }

            is ZoneCountAtLeastCondition -> {
                val names = zoneCardNames(condition.side, condition.zone)
                ConditionResult(names.size >= condition.count, "count=${names.size} cards=$names")
            }

            is LifeTotalCondition -> {
                val life = player(condition.side).life
                ConditionResult(life == condition.value, "${condition.side.yamlName} life=$life")
            }

            is WinnerCondition -> {
                val winner = finalWinnerSeat()
                ConditionResult(winner == seat(condition.side).value, "winner=${winner ?: "none"}")
            }

            is LoserCondition -> {
                val loser = finalLoserSeat()
                ConditionResult(loser == seat(condition.side).value, "loser=${loser ?: "none"}")
            }

            is BattlefieldStatsAtLeastCondition ->
                battlefieldStatsResult(condition.side, condition.card) { power, toughness ->
                    power >= condition.power && toughness >= condition.toughness
                }

            is BattlefieldStatsCondition ->
                battlefieldStatsResult(condition.side, condition.card) { power, toughness ->
                    power == condition.power && toughness == condition.toughness
                }

            is PhaseCondition ->
                ConditionResult(
                    phaseMatches(harness.phase(), condition.phase),
                    "phase=${runCatching { harness.phase() }.getOrNull() ?: "none"}",
                )

            is PromptCondition ->
                ConditionResult(promptSeen(condition.prompt, condition.promptId), "latest prompt=${latestPromptNameWithId() ?: "none"}")

            is AnnotationSeenCondition ->
                ConditionResult(annotationSeen(condition), "annotations=${annotationTypes().distinct()}")

            is AnnotationSeenInPhaseCondition ->
                ConditionResult(annotationSeenInPhase(condition.type, condition.phase), "annotations=${annotationTypesByPhase()}")

            StackEmptyCondition ->
                ConditionResult(
                    harness.game().stackZone.size() == 0,
                    "stack size=${runCatching { harness.game().stackZone.size() }.getOrNull() ?: "none"}",
                )
        }

    private fun battlefieldStatsResult(
        side: AcceptanceSide,
        cardName: String,
        matches: (Int, Int) -> Boolean,
    ): ConditionResult {
        val card =
            player(side)
                .getZone(ZoneType.Battlefield)
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) }
        return if (card == null) {
            ConditionResult(false, "battlefield=${zoneCardNames(side, AcceptanceZone.Battlefield)}")
        } else {
            val instanceId = harness.bridge.instanceId(card)
            val observed =
                harness.allMessages
                    .drop(observationStart)
                    .asSequence()
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList.asSequence() }
                    .filter { it.instanceId == instanceId && it.hasPower() && it.hasToughness() }
                    .map { it.power.value to it.toughness.value }
                    .toList()
            val observedMatch =
                observed.any { (power, toughness) -> matches(power, toughness) }
            ConditionResult(
                matches(card.netPower, card.netToughness) || observedMatch,
                "current=${card.netPower}/${card.netToughness} observed=$observed",
            )
        }
    }

    private fun annotationSeen(condition: AnnotationSeenCondition): Boolean {
        val expected = AnnotationType.valueOf(condition.type)
        return harness.allMessages
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.annotationsList + it.gameStateMessage.persistentAnnotationsList }
            .any { annotation ->
                expected in annotation.typeList &&
                    condition.details.all { (key, value) ->
                        annotation.detailsList
                            .firstOrNull { it.key == key }
                            ?.let { detail ->
                                (
                                    detail.valueUint32List.map(Any::toString) +
                                        detail.valueInt32List.map(Any::toString) +
                                        detail.valueUint64List.map(Any::toString) +
                                        detail.valueInt64List.map(Any::toString) +
                                        detail.valueBoolList.map(Any::toString) +
                                        detail.valueStringList +
                                        detail.valueFloatList.map(Any::toString) +
                                        detail.valueDoubleList.map(Any::toString)
                                ).contains(value)
                            } == true
                    }
            }
    }

    private fun annotationSeenInPhase(
        type: String,
        phase: String,
    ): Boolean {
        val expected = AnnotationType.valueOf(type)
        val expectedPhase = phase.toForgePhaseName()
        return harness.allMessages
            .filter { it.hasGameStateMessage() && it.gameStateMessage.hasTurnInfo() }
            .any { message ->
                val gsm = message.gameStateMessage
                gsm.turnInfo.phase.name
                    .toForgePhaseName() == expectedPhase &&
                    (gsm.annotationsList + gsm.persistentAnnotationsList).any { expected in it.typeList }
            }
    }

    private fun annotationTypes(): List<String> =
        harness.allMessages
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.annotationsList + it.gameStateMessage.persistentAnnotationsList }
            .flatMap { it.typeList }
            .map { it.name }

    private fun annotationTypesByPhase(): List<String> =
        harness.allMessages
            .filter { it.hasGameStateMessage() && it.gameStateMessage.hasTurnInfo() }
            .flatMap { message ->
                val phase = message.gameStateMessage.turnInfo.phase.name
                (message.gameStateMessage.annotationsList + message.gameStateMessage.persistentAnnotationsList)
                    .flatMap { it.typeList }
                    .map { "${it.name}@$phase" }
            }

    private fun actionAvailable(condition: ActionAvailableCondition): Boolean {
        val expectedType =
            when (condition.type) {
                AcceptanceActionType.PlayLand -> ActionType.Play_add3
                AcceptanceActionType.PlayMdfc -> ActionType.PlayMdfc
                AcceptanceActionType.Cast -> ActionType.Cast
                AcceptanceActionType.CastMdfc -> ActionType.CastMdfc
                AcceptanceActionType.CastAdventure -> ActionType.CastAdventure
                AcceptanceActionType.CastOmen -> ActionType.CastOmen
                AcceptanceActionType.Activate -> ActionType.Activate_add3
            }
        val candidates =
            harness.accumulator.actions
                ?.actionsList
                .orEmpty()
                .filter { it.actionType == expectedType }
        val namedMatch =
            candidates.any { action ->
                action.actionType == expectedType &&
                    actionCardName(action).equals(condition.card, ignoreCase = true) &&
                    (condition.altCost == null || actionMatchesAltCost(action, condition.altCost)) &&
                    (condition.abilityGrpId == null || action.abilityGrpId == condition.abilityGrpId)
            }
        if (namedMatch) return true
        return condition.type in
            listOf(
                AcceptanceActionType.PlayMdfc,
                AcceptanceActionType.CastMdfc,
                AcceptanceActionType.CastAdventure,
                AcceptanceActionType.CastOmen,
            ) &&
            condition.altCost == null &&
            candidates.size == 1
    }

    private fun actionCardName(action: Action): String? {
        val grpName = harness.bridge.cardRepository.findNameByGrpId(action.grpId)
        if (grpName != null) return grpName
        val objectGrpName =
            harness.accumulator.objects[action.instanceId]
                ?.grpId
                ?.let(harness.bridge.cardRepository::findNameByGrpId)
        if (objectGrpName != null) return objectGrpName
        val sourceGrpName =
            harness.accumulator.objects[action.sourceId]
                ?.grpId
                ?.let(harness.bridge.cardRepository::findNameByGrpId)
        if (sourceGrpName != null) return sourceGrpName
        val forgeCardId = harness.bridge.getForgeCardId(InstanceId(action.instanceId))
        if (forgeCardId != null) return harness.game().findById(forgeCardId.value)?.name
        val sourceForgeCardId = harness.bridge.getForgeCardId(InstanceId(action.sourceId))
        if (sourceForgeCardId != null) return harness.game().findById(sourceForgeCardId.value)?.name
        return null
    }

    private fun actionMatchesZone(
        action: Action,
        expectedZone: AcceptanceZone,
    ): Boolean {
        val forgeCardId = harness.bridge.getForgeCardId(InstanceId(action.instanceId)) ?: return expectedZone == AcceptanceZone.Hand
        val card = harness.game().findById(forgeCardId.value) ?: return false
        return card.zone.zoneType == expectedZone.toForgeZone()
    }

    private fun actionMatchesAltCost(
        action: Action,
        altCost: AcceptanceAltCost?,
    ): Boolean {
        if (altCost == null) return true
        val cardGrpId =
            action.grpId.takeIf { it != 0 }
                ?: harness.bridge
                    .getForgeCardId(InstanceId(action.instanceId))
                    ?.let { harness.game().findById(it.value) }
                    ?.let { harness.bridge.resolveGrpId(it, action.instanceId) }
                ?: return false
        val keywordId = altCost.keywordAbilityId
        val abilityGrpId = harness.bridge.cardRepository.findKeywordAbilityGrpId(cardGrpId, keywordId)
        return action.alternativeGrpId == keywordId ||
            action.abilityGrpId == keywordId ||
            (abilityGrpId != null && (action.alternativeGrpId == abilityGrpId || action.abilityGrpId == abilityGrpId))
    }

    private fun promptSeen(
        prompt: String,
        promptId: Int?,
    ): Boolean = harness.allMessages.any { it.matchesPrompt(prompt, promptId) }

    private fun latestPromptMatches(
        prompt: String,
        promptId: Int? = null,
    ): Boolean = latestPromptMessage()?.matchesPrompt(prompt, promptId) == true

    private fun latestPromptMessage(): GREToClientMessage? =
        harness.allMessages
            .asReversed()
            .firstOrNull { it.isPromptMessage() }

    private fun latestPromptNameWithId(): String? = latestPromptMessage()?.let { msg -> "${msg.promptName()}#${msg.prompt.promptId}" }

    private fun finalWinnerSeat(): Int? {
        val resultRows =
            harness.allMessages
                .asReversed()
                .asSequence()
                .filter { it.hasGameStateMessage() && it.gameStateMessage.hasGameInfo() }
                .flatMap {
                    val results = it.gameStateMessage.gameInfo.resultsList
                    results.asReversed().asSequence()
                }
        return resultRows.firstOrNull { it.winningTeamId != 0 }?.winningTeamId
    }

    private fun finalLoserSeat(): Int? = finalWinnerSeat()?.let { 3 - it }

    private fun player(side: AcceptanceSide): Player =
        harness.bridge.getPlayer(seat(side))
            ?: harness.game().registeredPlayers.getOrNull(seat(side).value - 1)
            ?: error("missing ${side.yamlName} player")

    private fun cardsInZone(
        side: AcceptanceSide,
        zone: AcceptanceZone,
    ): List<Card> =
        when (zone) {
            AcceptanceZone.Stack ->
                harness
                    .game()
                    .stack
                    .map { it.sourceCard }
                    .filter { harness.bridge.seatOf(it.owner) == seat(side) }

            AcceptanceZone.Battlefield,
            AcceptanceZone.Hand,
            AcceptanceZone.Graveyard,
            AcceptanceZone.Exile,
            AcceptanceZone.Library,
            AcceptanceZone.Sideboard,
            -> player(side).getZone(zone.toForgeZone()).cards.toList()
        }

    private fun requireAction(action: () -> Boolean) {
        require(action()) { "$context action failed" }
    }

    private fun submitAction(action: Action) {
        harness.submitAction(action)
    }

    private fun resolveTargetInstanceId(target: AcceptanceTargetSpec): Int =
        when (target) {
            is PlayerTargetSpec -> seat(target.side).value
            is CardTargetSpec -> resolveCardInZone(target.side, target.zone, target.card)
        }

    private fun resolveBattlefieldCard(
        side: AcceptanceSide,
        card: String,
    ): Int = resolveCardInZone(side, AcceptanceZone.Battlefield, card)

    private fun resolvePromptCardOrder(
        candidateIds: List<Int>,
        cards: List<String>,
    ): List<Int> {
        require(candidateIds.size == cards.size) {
            "$context ordered ${cards.size} cards but OrderReq has ${candidateIds.size} candidates ${promptCardNames(candidateIds)}"
        }
        return resolvePromptCardSelection(candidateIds, cards, "OrderReq")
    }

    private fun resolvePromptCardSelection(
        candidateIds: List<Int>,
        cards: List<String>,
        promptName: String,
    ): List<Int> {
        val remaining = candidateIds.toMutableList()
        return cards.map { card ->
            val id =
                remaining.firstOrNull { iid -> cardNameByInstanceId(iid).equals(card, ignoreCase = true) }
                    ?: error("$context could not find an unselected $card in $promptName candidates ${promptCardNames(candidateIds)}")
            remaining.remove(id)
            id
        }
    }

    private fun resolveCardInZone(
        side: AcceptanceSide,
        zone: AcceptanceZone,
        cardName: String,
    ): Int {
        val card =
            cardsInZone(side, zone)
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) }
                ?: error("could not find $cardName in ${side.yamlName} ${zone.yamlName}")
        return harness.bridge.instanceId(card)
    }

    private fun promptCardNames(ids: List<Int>): List<String> = ids.map { iid -> cardNameByInstanceId(iid) ?: "iid=$iid" }

    private fun cardNameByInstanceId(iid: Int): String? {
        val cardId = harness.bridge.getForgeCardId(InstanceId(iid)) ?: return null
        return harness.game().findById(cardId.value)?.name
    }

    private fun zoneCardNames(
        side: AcceptanceSide,
        zone: AcceptanceZone,
    ): List<String> = cardsInZone(side, zone).map { it.name }

    private fun actionSummary(action: Action): String = "${action.actionType.name}:${actionCardName(action) ?: "?"}"
}
