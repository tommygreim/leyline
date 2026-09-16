package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationFrameFinalizer
import leyline.game.bundle.GsmFrame
import leyline.game.event.GameEvent
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.StackEntry
import leyline.game.snapshot.StackSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.InstanceIdRegistry
import leyline.game.state.PendingSubmittedTargets
import leyline.game.state.ProjectionAcknowledgements
import leyline.game.state.ProjectionOutput
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import leyline.game.state.ProjectionViewerRole
import leyline.game.state.ViewerProjectionCursor
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/** Finalizes one viewer's state projection as one tentative value transition. */
@Suppress("LargeClass") // Shared planning and per-view rendering are one cohesive projection lifecycle.
object StateProjectionCompiler {
    data class Result(
        val gsm: GameStateMessage,
        val projectionSnapshot: GsmSnapshot,
        val output: ProjectionOutput,
        val transition: ProjectionTransition,
        val objectRefreshInstanceIds: Set<Int>,
    )

    data class ViewerInput(
        val input: StateFrameInput,
        val intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
        val actions: ActionsAvailableReq? = null,
        val decisionPending: Boolean = actions != null,
        val role: ProjectionViewerRole = ProjectionViewerRole.Player,
    )

    data class ViewerResult(
        val seatId: SeatId,
        val result: Result,
    )

    data class FoldResult(
        val viewers: List<ViewerResult>,
        val transition: ProjectionTransition,
        val phaseTransitionCommitAnnotation: AnnotationInfo? = null,
    )

    fun compileOneViewer(
        environment: StateProjectionEnvironment,
        input: StateFrameInput,
        prior: ProjectionState,
        intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
    ): Result = compileViewers(environment, prior, listOf(ViewerInput(input, intent))).viewers.single().result

    internal fun compileOneViewerWithActions(
        environment: StateProjectionEnvironment,
        input: StateFrameInput,
        prior: ProjectionState,
        intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
        actions: ActionsAvailableReq,
    ): Result = compileViewers(environment, prior, listOf(ViewerInput(input, intent, actions))).viewers.single().result

    fun compileViewers(
        environment: StateProjectionEnvironment,
        prior: ProjectionState,
        viewers: List<ViewerInput>,
    ): FoldResult {
        require(viewers.isNotEmpty()) { "Projection requires at least one viewer" }
        require(viewers.map { it.input.viewingSeatId }.distinct().size == viewers.size) { "Viewer seats must be unique" }
        val editor = prior.editor()
        val canonical =
            viewers.firstOrNull { viewer ->
                viewer.intent.supplements.any { it is ProjectionSupplement.SubmitPendingTargets }
            } ?: viewers.first()
        val stagedCanonical = stagePreStackAbilities(canonical.input, canonical.intent.supplements)
        aliasAdmittedStackAbilities(stagedCanonical, editor)
        val planned = StateMapper.planSharedDraft(stagedCanonical, environment, editor)
        projectPrivateCardPrompt(
            planned.gsm,
            stagedCanonical.snapshot,
            canonical.input.viewingSeatId,
            canonical.intent.privateCardPrompt,
            environment,
            editor,
        )
        val plannedOrder =
            projectOrder(
                planned.gsm,
                stagedCanonical.snapshot,
                canonical.input.viewingSeatId,
                canonical.intent.orderPrompt,
                environment,
                editor,
            )
        val supplementAnnotations = projectSupplements(canonical.input, prior, canonical.intent.supplements, planned, editor)
        val finalized =
            AnnotationFrameFinalizer.finalize(
                plannedOrder.gsm.annotationsList + supplementAnnotations,
                planned.firstAnnotationId,
            )
        val shared =
            planned.copy(
                gsm =
                    planned.gsm
                        .toBuilder()
                        .clearAnnotations()
                        .addAllAnnotations(finalized.annotations)
                        .build(),
                output =
                    planned.output.copy(
                        idReallocations = planned.output.idReallocations + plannedOrder.idReallocations,
                    ),
            )
        val phaseTransitionCommitFrame =
            if (ProjectionSupplement.PhaseTransition in canonical.intent.supplements) {
                val frame = GsmFrame.from(stagedCanonical.snapshot)
                AnnotationFrameFinalizer
                    .finalize(
                        listOf(
                            AnnotationBuilder.phaseOrStepModified(
                                stagedCanonical.snapshot.phase.activePlayer,
                                frame.phase.number,
                                frame.step.number,
                            ),
                        ),
                        finalized.nextId,
                    )
            } else {
                null
            }
        editor.persistentAnnotations =
            editor.persistentAnnotations.copy(
                nextAnnotationId = phaseTransitionCommitFrame?.nextId ?: finalized.nextId,
            )

        val projected =
            viewers.map { viewer ->
                renderViewer(
                    viewer,
                    shared,
                    plannedOrder,
                    finalized.annotations,
                    supplementAnnotations.consumedSubmittedTargets,
                    environment,
                    prior,
                    editor,
                )
            }
        val next = editor.freeze()
        val acknowledgements =
            projected.fold(ProjectionAcknowledgements()) { accumulated, (_, result) ->
                ProjectionAcknowledgements(
                    consumedEarthbendResolutionVersions =
                        accumulated.consumedEarthbendResolutionVersions + result.output.consumedEarthbendResolutionVersions,
                    promptFacts = accumulated.promptFacts.merge(result.output.promptFactConsumption),
                )
            }
        val transition = ProjectionTransition(prior.revision, next, acknowledgements)
        return FoldResult(
            viewers =
                projected.map { (seatId, result) ->
                    ViewerResult(seatId, result.copy(transition = transition))
                },
            transition = transition,
            phaseTransitionCommitAnnotation = phaseTransitionCommitFrame?.annotations?.single(),
        )
    }

    @Suppress("LongParameterList")
    private fun renderViewer(
        viewer: ViewerInput,
        shared: StateMapper.Draft,
        plannedOrder: OrderResult,
        finalizedAnnotations: List<AnnotationInfo>,
        submittedTargetsConsumed: Boolean,
        environment: StateProjectionEnvironment,
        prior: ProjectionState,
        editor: ProjectionState.Editor,
    ): Pair<SeatId, Result> {
        val viewerAnnotations =
            if (
                submittedTargetsConsumed &&
                viewer.intent.supplements.none { it is ProjectionSupplement.SubmitPendingTargets }
            ) {
                finalizedAnnotations.filterNot { AnnotationType.PlayerSubmittedTargets in it.typeList }
            } else {
                finalizedAnnotations
            }
        val stagedInput = stagePreStackAbilities(viewer.input, viewer.intent.supplements)
        val projected =
            StateMapper.renderViewerDraft(
                shared,
                stagedInput,
                environment,
                prior,
                editor,
                viewer.actions,
                includePrivateObjects = viewer.role.seesSeatPrivateCards,
            )
        val rendered =
            if (viewer.decisionPending) {
                projected
            } else {
                projected.copy(
                    gsm =
                        projected.gsm
                            .toBuilder()
                            .setPendingMessageCount(0)
                            .build(),
                )
            }

        fun applyViewerOverlays(
            gsm: GameStateMessage,
            snapshot: GsmSnapshot,
        ): OrderResult {
            val annotated =
                gsm
                    .toBuilder()
                    .clearAnnotations()
                    .addAllAnnotations(viewerAnnotations)
                    .build()
            val privateOverlay =
                if (viewer.role == ProjectionViewerRole.Player) {
                    projectPrivateCardPrompt(
                        annotated,
                        stagedInput.snapshot,
                        viewer.input.viewingSeatId,
                        viewer.intent.privateCardPrompt,
                        environment,
                        editor,
                    )
                } else {
                    annotated
                }
            val orderOverlay =
                if (viewer.role == ProjectionViewerRole.Player) {
                    renderPlannedOrder(
                        privateOverlay,
                        stagedInput.snapshot,
                        viewer.input.viewingSeatId,
                        viewer.intent.orderPrompt,
                        plannedOrder,
                        environment,
                        editor,
                    )
                } else {
                    OrderResult(privateOverlay, snapshot)
                }
            return orderOverlay.copy(
                gsm =
                    orderOverlay.gsm
                        .toBuilder()
                        .clearAnnotations()
                        .addAllAnnotations(viewerAnnotations)
                        .build(),
            )
        }
        val finalizedOrderOverlay = applyViewerOverlays(rendered.gsm, rendered.projectionSnapshot)
        val fullState =
            applyViewerOverlays(
                StateMapper.renderViewerFullState(
                    shared,
                    viewer.input.viewingSeatId,
                    viewer.actions,
                    includePrivateObjects = viewer.role.seesSeatPrivateCards,
                ),
                rendered.projectionSnapshot,
            ).gsm
                .toBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(finalizedOrderOverlay.gsm.gameStateId)
                .clearPrevGameStateId()
                .clearAnnotations()
                .clearActions()
                .clearDiffDeletedInstanceIds()
                .setPendingMessageCount(0)
                .setUpdate(GameStateUpdate.SendAndRecord)
                .build()
        val draft =
            rendered.copy(
                gsm = finalizedOrderOverlay.gsm,
                projectionSnapshot = finalizedOrderOverlay.snapshot,
                output =
                    rendered.output.copy(
                        idReallocations = rendered.output.idReallocations + finalizedOrderOverlay.idReallocations,
                    ),
            )
        val viewerSeatId = SeatId(viewer.input.viewingSeatId)
        val priorCursor = editor.viewerCursors[viewerSeatId] ?: ViewerProjectionCursor()
        editor.viewerCursors[viewerSeatId] =
            priorCursor.copy(
                previousSnapshot = draft.projectionSnapshot,
                fullState = fullState,
                pendingSubmittedTargets =
                    if (
                        submittedTargetsConsumed &&
                        viewer.intent.supplements.any { it is ProjectionSupplement.SubmitPendingTargets }
                    ) {
                        null
                    } else {
                        priorCursor.pendingSubmittedTargets
                    },
            )
        return viewerSeatId to
            Result(
                gsm = draft.gsm,
                projectionSnapshot = draft.projectionSnapshot,
                output = draft.output,
                transition = ProjectionTransition(prior.revision, prior),
                objectRefreshInstanceIds = draft.objectRefreshInstanceIds,
            )
    }

    private fun leyline.game.state.PromptFactConsumption.merge(
        next: leyline.game.state.PromptFactConsumption,
    ): leyline.game.state.PromptFactConsumption =
        leyline.game.state.PromptFactConsumption(
            choiceResults = choiceResults + next.choiceResults,
            staleReveals = staleReveals + next.staleReveals,
            convokePayments = convokePayments + next.convokePayments,
            collectEvidenceCosts = collectEvidenceCosts + next.collectEvidenceCosts,
            targetSpecs = targetSpecs + next.targetSpecs,
        )

    private data class SupplementAnnotations(
        val annotations: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>,
        val consumedSubmittedTargets: Boolean,
    ) : List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo> by annotations

    private fun projectSupplements(
        input: StateFrameInput,
        prior: ProjectionState,
        supplements: List<ProjectionSupplement>,
        draft: StateMapper.Draft,
        editor: ProjectionState.Editor,
    ): SupplementAnnotations {
        val annotations = mutableListOf<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>()
        var submittedTargetsConsumed = false
        val frameIds = draft.idResolver
        for (supplement in supplements) {
            when (supplement) {
                ProjectionSupplement.NewTurnStarted ->
                    annotations += AnnotationBuilder.newTurnStarted(input.snapshot.phase.activePlayer)

                ProjectionSupplement.PhaseTransition -> {
                    val frame = GsmFrame.from(input.snapshot)
                    annotations +=
                        AnnotationBuilder.phaseOrStepModified(
                            input.snapshot.phase.activePlayer,
                            frame.phase.number,
                            frame.step.number,
                        )
                }

                is ProjectionSupplement.PlayerSelectingTargets -> {
                    val targetInstanceId =
                        supplement.stackAbilityForgeId
                            ?.let(frameIds::triggerStackAbilityIid)
                            ?: frameIds.cardIid(supplement.sourceForgeId)
                    annotations +=
                        AnnotationBuilder.playerSelectingTargets(
                            targetInstanceId,
                            supplement.seatId,
                        )
                }

                is ProjectionSupplement.ReserveTriggeredAbility ->
                    editor.identities.getOrAlloc(FrameIdResolver.triggerStackAbilityForgeId(supplement.forgeAbilityId))

                is ProjectionSupplement.PreStackAbility,
                is ProjectionSupplement.PreStackSpell,
                -> Unit

                is ProjectionSupplement.SubmitPendingTargets -> {
                    check(!submittedTargetsConsumed) { "Only one submitted-target fact may be consumed per viewer frame" }
                    val expected =
                        PendingSubmittedTargets(
                            supplement.spellInstanceId,
                            supplement.seatId,
                            supplement.version,
                        )
                    check(prior.viewerCursors[SeatId(input.viewingSeatId)]?.pendingSubmittedTargets == expected) {
                        "Submitted-target fact does not match the prior viewer cursor"
                    }
                    annotations += AnnotationBuilder.playerSubmittedTargets(supplement.spellInstanceId, supplement.seatId)
                    submittedTargetsConsumed = true
                }

                is ProjectionSupplement.StaticParityChoice -> {
                    val sourceId = frameIds.cardIid(supplement.sourceForgeId)
                    val sourceGrpId =
                        input.snapshot.boundCards
                            .getValue(supplement.sourceForgeId)
                            .snapshot.grpId
                    annotations += AnnotationBuilder.resolutionStart(sourceId, leyline.bridge.types.GrpId(sourceGrpId))
                    annotations +=
                        AnnotationBuilder.selectNDecoration(
                            sourceId,
                            optionIndex = 0,
                            affectedObjectIds = supplement.evenForgeIds.map(frameIds::cardIid),
                        )
                    annotations +=
                        AnnotationBuilder.selectNDecoration(
                            sourceId,
                            optionIndex = 1,
                            affectedObjectIds = supplement.oddForgeIds.map(frameIds::cardIid),
                        )
                }
            }
        }
        return SupplementAnnotations(annotations, submittedTargetsConsumed)
    }

    private data class OrderResult(
        val gsm: GameStateMessage,
        val snapshot: GsmSnapshot,
        val idReallocations: List<InstanceIdRegistry.IdReallocation> = emptyList(),
    )

    private fun stagePreStackAbilities(
        input: StateFrameInput,
        supplements: List<ProjectionSupplement>,
    ): StateFrameInput {
        val abilities = supplements.filterIsInstance<ProjectionSupplement.PreStackAbility>()
        val spells = supplements.filterIsInstance<ProjectionSupplement.PreStackSpell>()
        val reservations = supplements.filterIsInstance<ProjectionSupplement.ReserveTriggeredAbility>()
        if (abilities.isEmpty() && spells.isEmpty() && reservations.isEmpty()) return input

        var stack = input.snapshot.stack
        var zones = input.snapshot.zones
        var boundCards = input.snapshot.boundCards
        for (spell in spells) {
            val bound = spell.card
            val card = bound.snapshot
            val currentZone = zones.values.firstOrNull { card.forgeCardId in it.contents }
            if (currentZone != null && currentZone.id != leyline.game.mapping.ZoneIds.LIMBO) continue
            if (stack.entries.none { it.forgeCardId == card.forgeCardId && it.isSpell }) {
                // Newest object leads: the client reads objectInstanceIds[0] as the top of
                // the stack, and ZoneMapper projects the zone in this order.
                stack =
                    StackSnapshot(
                        listOf(
                            StackEntry(
                                forgeCardId = card.forgeCardId,
                                controller = card.controller,
                                owner = card.owner,
                                grpId = card.grpId,
                                sourceCardGrpId = card.grpId,
                                isSpell = true,
                                targets = emptyList(),
                            ),
                        ) + stack.entries,
                    )
            }
            boundCards = boundCards + (card.forgeCardId to bound)
            zones =
                zones.mapValues { (_, zone) ->
                    if (card.forgeCardId in zone.contents) zone.copy(contents = zone.contents - card.forgeCardId) else zone
                }
            val stackZone = checkNotNull(zones[leyline.game.mapping.ZoneIds.STACK])
            if (card.forgeCardId !in stackZone.contents) {
                zones = zones + (stackZone.id to stackZone.copy(contents = stackZone.contents + card.forgeCardId))
            }
        }
        for (ability in abilities) {
            val existing = stack.entries.firstOrNull { it.forgeAbilityId == ability.forgeAbilityId }
            if (existing != null) {
                check(
                    existing.forgeCardId == ability.sourceForgeCardId &&
                        existing.grpId == ability.abilityGrpId &&
                        existing.sourceCardGrpId == ability.sourceCardGrpId &&
                        existing.owner == ability.ownerSeatId &&
                        existing.controller == ability.controllerSeatId &&
                        existing.targets == ability.targetForgeCardIds,
                ) { "Pre-stack ability conflicts with the existing frame entry" }
                continue
            }
            stack =
                StackSnapshot(
                    listOf(
                        StackEntry(
                            forgeCardId = ability.sourceForgeCardId,
                            controller = ability.controllerSeatId,
                            owner = ability.ownerSeatId,
                            grpId = ability.abilityGrpId,
                            sourceCardGrpId = ability.sourceCardGrpId,
                            isSpell = false,
                            isActivatedAbility = ability.isActivatedAbility,
                            targets = ability.targetForgeCardIds,
                            forgeAbilityId = ability.forgeAbilityId,
                        ),
                    ) + stack.entries,
                )
        }
        for (reservation in reservations) {
            if (stack.entries.any { it.forgeAbilityId == reservation.forgeAbilityId }) continue
            input.previousSnapshot
                ?.stack
                ?.entries
                ?.singleOrNull { it.forgeAbilityId == reservation.forgeAbilityId }
                ?.let { stack = StackSnapshot(listOf(it) + stack.entries) }
        }
        return input.copy(snapshot = copySnapshot(input.snapshot, zones = zones, boundCards = boundCards, stack = stack))
    }

    private fun aliasAdmittedStackAbilities(
        input: StateFrameInput,
        editor: ProjectionState.Editor,
    ) {
        val priorEntries =
            input.previousSnapshot
                ?.stack
                ?.entries
                .orEmpty()
        if (priorEntries.isEmpty()) return
        for (event in input.events.events.filterIsInstance<GameEvent.SpellCast>()) {
            if (!event.isAbility || event.isTrigger || event.rootAbilityForgeId == 0) continue
            val admittedForgeIds =
                listOf(event.abilityForgeId, event.stackAbilityForgeId)
                    .filter { it != 0 }
                    .toSet()
            if (admittedForgeIds.isEmpty()) continue
            val admitted =
                input.snapshot.stack.entries.singleOrNull {
                    it.forgeCardId == event.cardId && it.forgeAbilityId in admittedForgeIds
                } ?: continue
            val prior =
                priorEntries.singleOrNull { it.forgeAbilityId == event.rootAbilityForgeId } ?: continue
            if (!prior.isActivatedAbility || prior.forgeCardId != admitted.forgeCardId) continue
            if (prior.forgeAbilityId == admitted.forgeAbilityId) continue
            editor.identities.alias(
                FrameIdResolver.triggerStackAbilityForgeId(prior.forgeAbilityId),
                FrameIdResolver.triggerStackAbilityForgeId(admitted.forgeAbilityId),
            )
        }
    }

    private fun projectPrivateCardPrompt(
        gsm: GameStateMessage,
        snapshot: GsmSnapshot,
        viewingSeatId: Int,
        prompt: PrivateCardPromptProjection?,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
    ): GameStateMessage {
        prompt ?: return gsm
        prompt.sourceForgeId?.let(editor.identities::getOrAlloc)
        prompt.candidateForgeIds.forEach(editor.identities::getOrAlloc)
        return exposePrivateCandidates(gsm, snapshot, prompt.candidateForgeIds, viewingSeatId, environment, editor)
    }

    private fun projectOrder(
        gsm: GameStateMessage,
        snapshot: GsmSnapshot,
        viewingSeatId: Int,
        order: OrderPromptProjection?,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
    ): OrderResult {
        order ?: return OrderResult(gsm, snapshot)
        order.candidateForgeIds.forEach(editor.identities::getOrAlloc)
        val move = order.move
        if (move == null) {
            return OrderResult(
                exposePrivateCandidates(gsm, snapshot, order.candidateForgeIds, viewingSeatId, environment, editor),
                snapshot,
            )
        }
        check(move.forgeCardIds == order.candidateForgeIds) {
            "Order move must describe the exact prompt candidate sequence"
        }
        val sourceZoneId = ZoneIds.handOf(move.seatId)
        val destinationZoneId = ZoneIds.libraryOf(move.seatId)
        val moved =
            move.forgeCardIds.map { forgeCardId ->
                MovedCard(forgeCardId, editor.identities.realloc(forgeCardId))
            }
        val stagedSnapshot = stagedOrderSnapshot(snapshot, move, sourceZoneId, destinationZoneId)
        val sourceId = order.sourceForgeId?.let(editor.identities::getOrAlloc) ?: InstanceId(0)
        val stagedGsm =
            stagedOrderGsm(
                gsm,
                snapshot,
                stagedSnapshot,
                move,
                moved,
                sourceId,
                sourceZoneId,
                destinationZoneId,
                environment,
                editor,
            )
        editor.limboInstanceIds += moved.map { it.reallocation.old.value }
        moved.forEach { editor.protoZones[it.reallocation.new.value] = destinationZoneId }
        return OrderResult(stagedGsm, stagedSnapshot, moved.map { it.reallocation })
    }

    private fun renderPlannedOrder(
        gsm: GameStateMessage,
        snapshot: GsmSnapshot,
        viewingSeatId: Int,
        order: OrderPromptProjection?,
        planned: OrderResult,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
    ): OrderResult {
        order ?: return OrderResult(gsm, snapshot)
        val move = order.move
        if (move == null) {
            return OrderResult(
                exposePrivateCandidates(gsm, snapshot, order.candidateForgeIds, viewingSeatId, environment, editor),
                snapshot,
            )
        }
        val moved = order.candidateForgeIds.zip(planned.idReallocations).map { (forgeCardId, ids) -> MovedCard(forgeCardId, ids) }
        val sourceZoneId = ZoneIds.handOf(move.seatId)
        val destinationZoneId = ZoneIds.libraryOf(move.seatId)
        val sourceId = order.sourceForgeId?.let(editor.identities::getOrAlloc) ?: InstanceId(0)
        return OrderResult(
            stagedOrderGsm(
                gsm,
                snapshot,
                planned.snapshot,
                move,
                moved,
                sourceId,
                sourceZoneId,
                destinationZoneId,
                environment,
                editor,
            ),
            planned.snapshot,
            planned.idReallocations,
        )
    }

    private data class MovedCard(
        val forgeCardId: ForgeCardId,
        val reallocation: InstanceIdRegistry.IdReallocation,
    )

    private fun stagedOrderSnapshot(
        snapshot: GsmSnapshot,
        move: OrderZoneMoveFact,
        sourceZoneId: Int,
        destinationZoneId: Int,
    ): GsmSnapshot {
        val moved = move.forgeCardIds.toSet()
        val zones = snapshot.zones.toMutableMap()
        zones[sourceZoneId]?.let { source ->
            zones[sourceZoneId] = source.copy(contents = source.contents.filterNot { it in moved })
        }
        zones[destinationZoneId]?.let { destination ->
            val remaining = destination.contents.filterNot { it in moved }
            zones[destinationZoneId] =
                destination.copy(
                    contents = if (move.putOnTop) move.forgeCardIds + remaining else remaining + move.forgeCardIds,
                )
        }
        return copySnapshot(snapshot, zones)
    }

    private fun copySnapshot(
        snapshot: GsmSnapshot,
        zones: Map<Int, ZoneSnapshot> = snapshot.zones,
        boundCards: Map<leyline.bridge.types.ForgeCardId, leyline.game.snapshot.BoundCard> = snapshot.boundCards,
        stack: StackSnapshot = snapshot.stack,
    ): GsmSnapshot =
        GsmSnapshot(
            matchId = snapshot.matchId,
            gameStateId = snapshot.gameStateId,
            seats = snapshot.seats,
            zones = zones,
            boundCards = boundCards,
            stack = stack,
            phase = snapshot.phase,
            combat = snapshot.combat,
            abilityWordEntries = snapshot.abilityWordEntries,
            pendingTriggers = snapshot.pendingTriggers,
            capturedAt = snapshot.capturedAt,
            dayTime = snapshot.dayTime,
            activePlayerSpellsCastThisTurn = snapshot.activePlayerSpellsCastThisTurn,
        )

    private fun stagedOrderGsm(
        gsm: GameStateMessage,
        snapshot: GsmSnapshot,
        stagedSnapshot: GsmSnapshot,
        move: OrderZoneMoveFact,
        moved: List<MovedCard>,
        sourceId: InstanceId,
        sourceZoneId: Int,
        destinationZoneId: Int,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
    ): GameStateMessage {
        val oldIds = moved.mapTo(mutableSetOf()) { it.reallocation.old.value }
        val newIds = moved.mapTo(mutableSetOf()) { it.reallocation.new.value }
        val builder = gsm.toBuilder()
        val replacementZones =
            listOfNotNull(
                stagedSnapshot.zones[sourceZoneId]?.let { zoneInfo(it, editor) },
                stagedSnapshot.zones[destinationZoneId]?.let { zoneInfo(it, editor) },
                limboZoneInfo(editor, moved.map { it.reallocation.old }),
            )
        builder.clearZones()
        builder.addAllZones(
            (gsm.zonesList.filterNot { it.zoneId in setOf(sourceZoneId, destinationZoneId, ZoneIds.LIMBO) } + replacementZones)
                .sortedBy { it.zoneId },
        )
        builder.clearGameObjects()
        builder.addAllGameObjects(gsm.gameObjectsList.filterNot { it.instanceId in oldIds || it.instanceId in newIds })
        for (movedCard in moved) {
            val card = snapshot.objects[movedCard.forgeCardId] ?: continue
            builder.addGameObjects(
                orderObject(card, movedCard.reallocation.old, ZoneIds.LIMBO, move.seatId.value, environment),
            )
            builder.addGameObjects(
                orderObject(card, movedCard.reallocation.new, destinationZoneId, move.seatId.value, environment),
            )
            builder.addAnnotations(AnnotationBuilder.objectIdChanged(movedCard.reallocation.old, movedCard.reallocation.new, sourceId))
            builder.addAnnotations(
                AnnotationBuilder.zoneTransfer(
                    movedCard.reallocation.new,
                    sourceZoneId,
                    destinationZoneId,
                    "Put",
                    affectorId = sourceId,
                ),
            )
        }
        return builder.build()
    }

    private fun exposePrivateCandidates(
        gsm: GameStateMessage,
        snapshot: GsmSnapshot,
        candidates: List<ForgeCardId>,
        viewingSeatId: Int,
        environment: StateProjectionEnvironment,
        editor: ProjectionState.Editor,
    ): GameStateMessage {
        val builder = gsm.toBuilder()
        val existing =
            builder.gameObjectsList
                .withIndex()
                .associate { (index, obj) -> obj.instanceId to index }
                .toMutableMap()
        for (forgeCardId in candidates) {
            val card = snapshot.objects[forgeCardId] ?: continue
            val zone = snapshot.zones.values.firstOrNull { forgeCardId in it.contents } ?: continue
            val id = editor.identities.getOrAlloc(forgeCardId)
            val objectInfo = orderObject(card, id, zone.id, zone.owner?.value ?: viewingSeatId, environment, viewingSeatId)
            existing[id.value]?.let { builder.setGameObjects(it, objectInfo) } ?: run {
                existing[id.value] = builder.gameObjectsCount
                builder.addGameObjects(objectInfo)
            }
        }
        return builder.build()
    }

    private fun zoneInfo(
        zone: ZoneSnapshot,
        editor: ProjectionState.Editor,
    ): ZoneInfo {
        val builder =
            ZoneInfo
                .newBuilder()
                .setZoneId(zone.id)
                .setType(zone.type)
                .setVisibility(if (zone.type == ZoneType.Library) Visibility.Hidden else zone.visibility)
        zone.owner?.let { owner ->
            builder.ownerSeatId = owner.value
            if (zone.type == ZoneType.Hand || zone.type == ZoneType.Sideboard) builder.addViewers(owner.value)
        }
        zone.contents.forEach { builder.addObjectInstanceIds(editor.identities.getOrAlloc(it).value) }
        return builder.build()
    }

    private fun limboZoneInfo(
        editor: ProjectionState.Editor,
        extraIds: List<InstanceId>,
    ): ZoneInfo =
        ZoneInfo
            .newBuilder()
            .setZoneId(ZoneIds.LIMBO)
            .setType(ZoneType.Limbo)
            .setVisibility(Visibility.Public)
            .addAllObjectInstanceIds((editor.limboInstanceIds.map(::InstanceId) + extraIds).distinct().map { it.value })
            .build()

    private fun orderObject(
        card: CardSnapshot,
        instanceId: InstanceId,
        zoneId: Int,
        ownerSeatId: Int,
        environment: StateProjectionEnvironment,
        viewerSeatId: Int = ownerSeatId,
    ): GameObjectInfo =
        ObjectMapper
            .buildFromSnapshot(
                cardSnap = card,
                instanceId = instanceId.value,
                zoneId = zoneId,
                ownerSeatId = ownerSeatId,
                cardProto = environment.cardProto,
                visibility = Visibility.Private,
            ).toBuilder()
            .addViewers(viewerSeatId)
            .build()
}
