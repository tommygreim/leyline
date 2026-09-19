package leyline.game.bundle

import forge.game.Game
import forge.game.card.Card
import forge.game.phase.PhaseType
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.CardSelectKind
import leyline.bridge.handoff.CardSelectWindowValue
import leyline.bridge.handoff.CommanderReturnPromptContext
import leyline.bridge.handoff.DistributionWindowValue
import leyline.bridge.handoff.GameActionBridge.ActionOffer
import leyline.bridge.handoff.GroupingWindowValue
import leyline.bridge.handoff.OrderWindowValue
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.ReplacementWindowValue
import leyline.bridge.handoff.RevealChoiceWindowValue
import leyline.bridge.handoff.SearchWindowValue
import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.handoff.TargetingWindowValue
import leyline.bridge.handoff.TriggerOrderWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationLossReason
import leyline.game.codes.DetailKeys
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.CapturedStateFrame
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.OrderPromptProjection
import leyline.game.mapping.OrderZoneMoveFact
import leyline.game.mapping.PlayerMapper
import leyline.game.mapping.PrivateCardPromptProjection
import leyline.game.mapping.ProjectionSupplement
import leyline.game.mapping.PromptIds
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateMapper
import leyline.game.mapping.StateProjectionCompiler
import leyline.game.mapping.ViewerProjectionIntent
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.BoundCard
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.SnapshotCapture
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.GameBridge
import leyline.game.state.PendingSubmittedTargets
import leyline.game.state.ProjectionAcknowledgements
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.game.state.PromptFactConsumption
import leyline.game.state.PromptFactKey
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.ViewerProjectionCursor
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds GRE message bundles for each flow milestone.
 *
 * Frame computation reads one snapshot, then installs projection history and the
 * shared viewer baseline through one seam. There is no Netty or mutable
 * handler state here. Callers prepare and install cuts while holding the
 * coordinator publication lock; isolated test helpers call preparation sequentially.
 *
 * Captures a [GsmSnapshot] at entry; every stage reads from the snapshot.
 *
 * **Update types** (what the client does with each GSM):
 * - [GameStateUpdate.SendAndRecord] — checkpoint; client persists state.
 *   Precedes [ActionsAvailableReq] at human decision points and carries persistent type changes.
 * - [GameStateUpdate.SendHiFi] — animation-quality intermediate. AI actions,
 *   phase echoes, combat toggles. Client animates but doesn't save.
 * - [GameStateUpdate.Send] — speculative/transient. Targeting, selection
 *   prompts. Client may discard on undo/cancel.
 *
 * **pendingMessageCount:** when 1, tells the client another message follows
 * in the same logical batch (GSM + request pair). Client defers processing
 * until both arrive. Omit for standalone GSMs (AI actions, echoes).
 *
 * Naming: `xxxBundle` → [BundleResult] (multi-message). Standalone helpers
 * ([queuedGameState], [edictalPass]) return single [GREToClientMessage].
 */
@Suppress("LargeClass") // coherent unit; split assessed 2026-04-05, marginal leverage
class BundleBuilder(
    private val bridge: GameBridge,
    private val matchId: String,
    val seatId: Int,
) {
    private val blockingInteractions = BlockingInteractionMaterializer(seatId)
    private val cardSelectWindows = CardSelectWindowMaterializer()
    private val revealChoiceWindows = RevealChoiceWindowMaterializer()
    private val staticChoiceWindows = StaticChoiceWindowMaterializer()
    private val modalChoiceWindows = ModalChoiceWindowMaterializer(seatId)
    private val targetingWindows = TargetingWindowMaterializer(seatId)
    private val searchWindows = SearchWindowMaterializer(SeatId(seatId))
    private val replacementWindows = ReplacementWindowMaterializer()
    private val orderWindows = OrderWindowMaterializer()
    private val triggerOrderWindows = TriggerOrderWindowMaterializer()
    private val distributionWindows = DistributionWindowMaterializer()
    private val groupingWindows = GroupingWindowMaterializer()
    private val manaSourcePayments = ManaSourcePaymentMaterializer(seatId)
    private val oneShotPayCosts = OneShotPayCostsMaterializer()
    private val gatherCounters = GatherCountersWindowMaterializer()
    private val stateFrameInputCapture = StateFrameInputCapture(bridge, matchId, seatId)

    /** Frozen on first projection, after the match game and variant exist; retries reuse the same value. */
    private val stateProjectionEnvironment get() = bridge.stateProjectionEnvironment

    data class BundleResult(
        val messages: List<GREToClientMessage>,
        val actionOffers: List<ActionOffer> = emptyList(),
        val actionGameStateId: Int? = null,
    )

    internal data class ActionWindowPrepared(
        val bundle: BundleResult,
        val transition: ProjectionTransition? = null,
        val closesPlaybackFrame: Boolean = false,
        val presentationActions: ActionsAvailableReq = ActionsAvailableReq.getDefaultInstance(),
    )

    data class FullStateResult(
        val snapshot: GsmSnapshot,
        val gsm: GameStateMessage,
        val actions: ActionsAvailableReq,
        val actionOffers: List<ActionOffer>,
    )

    internal data class PreparedFullState(
        val result: FullStateResult,
        val transition: ProjectionTransition,
    )

    private typealias FrameInput = StateFrameInputCapture.Materialized

    /** One immutable frame inside an ordinary-playback cut. Logical ids are reserved exactly once. */
    internal data class PlaybackFrameCut(
        val frame: CapturedStateFrame,
        val intent: ViewerProjectionIntent,
        val contentMsgId: Int,
        val coinFlipMsgIds: List<Int>,
        val echoLink: LogicalSequencePlanner.GameStateLink,
        val echoMsgId: Int,
        val lifeTotals: Map<Int, Int> = emptyMap(),
    )

    /** Exact initial state plus every ordered frame produced from one closed journal. */
    internal data class PlaybackCut(
        val priorProjection: ProjectionState,
        val actions: ActionsAvailableReq,
        val frames: List<PlaybackFrameCut>,
    )

    internal data class ViewerRoute(
        val viewer: ProjectionViewer,
        val builder: BundleBuilder,
    )

    internal data class ViewerBatches(
        val seatId: SeatId,
        val batches: List<List<GREToClientMessage>>,
    )

    internal data class PreparedViewerCut<T>(
        val player: T,
        val viewers: List<ViewerBatches>,
        val transition: ProjectionTransition,
        val closesPlaybackFrame: Boolean = false,
        val gameStateId: Int? = null,
    ) {
        /** Isolated one-view materializer compatibility. */
        val batches: List<List<GREToClientMessage>> get() = viewers.single().batches
    }

    private data class ViewerPromptProjection(
        val gameStateId: Int,
        val playerIndex: Int,
        val routes: List<ViewerRoute>,
        val playerInput: StateFrameInput,
        val fold: StateProjectionCompiler.FoldResult,
        val closesPlaybackFrame: Boolean,
    ) {
        fun outputs(playerMessages: List<GREToClientMessage>): List<ViewerBatches> {
            val content = playerMessages.first { it.hasGameStateMessage() }
            return routes.mapIndexed { index, route ->
                val (viewer, builder) = route
                val messages =
                    if (index == playerIndex) {
                        playerMessages
                    } else {
                        listOf(
                            builder.makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, content.msgId) {
                                it.gameStateMessage = fold.viewers[index].result.gsm
                            },
                        )
                    }
                ViewerBatches(viewer.seatId, listOf(messages))
            }
        }
    }

    internal data class PlaybackFrameSpec(
        val events: FrameEventLog,
        val turnStarted: Boolean = false,
        val lifeTotals: Map<Int, Int> = emptyMap(),
    )

    internal fun prepareFullState(
        game: Game,
        gameStateId: Int,
    ): PreparedFullState {
        val prior = bridge.projectionStateSnapshot()
        val input =
            stateFrameInputCapture.capture(
                game = game,
                gameStateId = gameStateId,
                revealForSeat = null,
                events = StateFrameInputCapture.Events.Supplied(FrameEventLog.EMPTY),
                priorProjectionOverride = prior,
                includePreviousSnapshot = false,
            ) { _, _ -> GameStateUpdate.SendAndRecord }
        val snapshot = input.state.snapshot
        val result =
            StateProjectionCompiler.compileOneViewer(
                environment = stateProjectionEnvironment,
                input = input.state,
                prior = input.priorProjection,
                intent = ViewerProjectionIntent.EMPTY,
            )
        val transition = result.transition
        val tentative = transition.nextState.copy(revision = transition.expectedRevision)
        val (actions, next) =
            bridge.editProjection(tentative) {
                ActionMapper.buildProjectionFromSnapshot(seatId, snapshot, bridge)
            }
        return PreparedFullState(
            result =
                FullStateResult(
                    snapshot = snapshot,
                    gsm = GsmBuilder.embedActions(result.gsm, actions.actions, GsmFrame.from(snapshot), recipientSeatId = seatId),
                    actions = actions.actions,
                    actionOffers = actions.offers,
                ),
            transition = transition.copy(nextState = next),
        )
    }

    private data class FrameDiff(
        val gameStateId: Int,
        val snap: GsmSnapshot,
        val result: StateProjectionCompiler.Result,
        val events: FrameEventLog,
        val previousSnap: GsmSnapshot?,
    )

    private fun frameInput(
        game: Game,
        counter: LogicalSequencePlanner,
        revealForSeat: Int?,
        eventsOverride: FrameEventLog?,
        priorProjectionOverride: ProjectionState? = null,
        previousSnapshotOverride: GsmSnapshot? = null,
        promptFactsOverride: PromptProjectionFacts? = null,
        effectFactsOverride: EffectProjectionFacts? = null,
        updateType: (GsmSnapshot, FrameEventLog) -> GameStateUpdate,
    ): FrameInput {
        val nextGs = counter.nextGsId()
        return stateFrameInputCapture.capture(
            game = game,
            gameStateId = nextGs,
            revealForSeat = revealForSeat,
            events =
                eventsOverride?.let(StateFrameInputCapture.Events::Supplied)
                    ?: StateFrameInputCapture.Events.CloseBundleFrame,
            priorProjectionOverride = priorProjectionOverride,
            previousSnapshotOverride = previousSnapshotOverride,
            promptFactsOverride = promptFactsOverride,
            effectFactsOverride = effectFactsOverride,
            updateType = updateType,
        )
    }

    private fun compileFrame(
        input: FrameInput,
        projectionState: ProjectionState = input.priorProjection,
        intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
    ): StateProjectionCompiler.Result =
        StateProjectionCompiler.compileOneViewer(
            environment = stateProjectionEnvironment,
            input = input.state,
            prior = projectionState,
            intent = intent,
        )

    private fun prepareFrameInputLocked(
        input: FrameInput,
        intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
    ): FrameDiff {
        val result = compileFrame(input, intent = intent)
        bridge.diffListener?.invoke(
            input.priorProjection,
            listOf(GameBridge.ProjectionFoldViewer(StateProjectionCompiler.ViewerInput(input.state, intent), result.gsm)),
        )
        return FrameDiff(
            input.state.gameStateId,
            result.projectionSnapshot,
            result,
            input.state.events,
            input.state.previousSnapshot,
        )
    }

    internal fun pendingSubmittedTargets(): PendingSubmittedTargets? = bridge.viewerProjectionCursor(SeatId(seatId)).pendingSubmittedTargets

    internal fun previousProjectionSnapshot(): GsmSnapshot? = bridge.viewerProjectionCursor(SeatId(seatId)).previousSnapshot

    internal fun preparePostAction(
        game: Game,
        counter: LogicalSequencePlanner,
        revealForSeat: Int? = null,
        priorityCandidates: PriorityActionCandidates? = null,
    ): ActionWindowPrepared {
        val input =
            frameInput(
                game,
                counter,
                revealForSeat = revealForSeat,
                eventsOverride = null,
            ) { snap, events -> resolveFrameUpdateType(snap, events) }
        val pendingSubmittedTargets = input.priorProjection.viewerCursors[SeatId(seatId)]?.pendingSubmittedTargets
        val intent =
            ViewerProjectionIntent.of(
                pendingSubmittedTargets
                    ?.let { pending ->
                        listOf(
                            ProjectionSupplement.SubmitPendingTargets(
                                pending.spellInstanceId,
                                pending.casterSeatId,
                                pending.version,
                            ),
                        )
                    }.orEmpty(),
            )
        val compiled = compileFrame(input, intent = intent)
        bridge.diffListener?.invoke(
            input.priorProjection,
            listOf(GameBridge.ProjectionFoldViewer(StateProjectionCompiler.ViewerInput(input.state, intent), compiled.gsm)),
        )
        val tentative = compiled.transition.nextState.copy(revision = compiled.transition.expectedRevision)
        val (projection, next) =
            bridge.editProjection(tentative) {
                ActionMapper.buildProjectionFromSnapshot(seatId, compiled.projectionSnapshot, bridge, priorityCandidates)
            }
        val diff =
            FrameDiff(
                input.state.gameStateId,
                compiled.projectionSnapshot,
                compiled,
                input.state.events,
                input.state.previousSnapshot,
            )
        val nextGs = diff.gameStateId
        val snap = diff.snap
        val frame = GsmFrame.from(snap)
        // Build state first (without actions) — triggers instanceId realloc on zone transfers.
        // Then build actions so they reference the new (post-move) instanceIds.
        val result = diff.result
        val actions = projection.actions

        // PhaseOrStepModified is now emitted event-driven from GameEvent.PhaseChanged
        // in StateMapper Stage 2b — no injection needed here.

        val gs = GsmBuilder.embedActions(result.gsm, actions, frame, recipientSeatId = seatId)

        // Stop at ActionsAvailableReq for human-priority prompts. A trailing
        // empty GSM advances the visual state after the prompt and can clear
        // zone-cast affordances while the action is still available.
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                    it.gameStateMessage = gs
                },
            ) + coinFlipPromptMessages(diff.events.events, nextGs, counter) +
                listOf(
                    makeGRE(GREMessageType.ActionsAvailableReq_695e, nextGs, counter.nextMsgId()) {
                        it.actionsAvailableReq = actions
                        it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                    },
                )

        return ActionWindowPrepared(
            BundleResult(messages, projection.offers, nextGs),
            compiled.transition.copy(nextState = next),
            closesPlaybackFrame = true,
        )
    }

    /** Prepare one initial action window for every registered viewer from one frame. */
    internal fun prepareInitialActionWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        routes: List<ViewerRoute>,
        kind: PendingActionKind,
        priorityCandidates: PriorityActionCandidates? = null,
        intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
    ): PreparedViewerCut<ActionWindowPrepared> {
        require(kind != PendingActionKind.SYNC_ONLY) { "Synchronization windows have no action presentation" }
        val frame =
            prepareViewerPromptProjection(
                game = game,
                counter = counter,
                routes = routes,
                intent = intent,
                intentForViewer =
                    { viewer ->
                        intent.takeIf { viewer.role == ProjectionViewerRole.Player } ?: ViewerProjectionIntent.EMPTY
                    },
                updateType =
                    when (kind) {
                        PendingActionKind.PRIORITY -> ::resolveFrameUpdateType
                        PendingActionKind.DECLARE_ATTACKERS,
                        PendingActionKind.DECLARE_BLOCKERS,
                        PendingActionKind.SYNC_ONLY,
                        -> { snap, _ -> StateMapper.resolveUpdateType(snap, seatId) }
                    },
            )
        val playerRoute = routes[frame.playerIndex]
        val playerSeatId = playerRoute.viewer.seatId.value
        val player = frame.fold.viewers[frame.playerIndex].result
        val diff =
            FrameDiff(
                gameStateId = frame.gameStateId,
                snap = player.projectionSnapshot,
                result = player,
                events = frame.playerInput.events,
                previousSnap = frame.playerInput.previousSnapshot,
            )
        val tentative =
            frame.fold.transition.nextState.copy(
                revision = frame.fold.transition.expectedRevision,
            )
        val (bundle, next, presentationActions) =
            when (kind) {
                PendingActionKind.PRIORITY -> prepareInitialPriority(frame, counter, tentative, playerSeatId, priorityCandidates)
                PendingActionKind.DECLARE_ATTACKERS,
                PendingActionKind.DECLARE_BLOCKERS,
                -> prepareInitialDeclaration(game, frame.gameStateId, counter, tentative, playerSeatId, kind, diff)
                PendingActionKind.SYNC_ONLY -> error("Synchronization windows have no action presentation")
            }
        val prepared =
            ActionWindowPrepared(
                bundle = bundle,
                transition = frame.fold.transition.copy(nextState = next),
                closesPlaybackFrame = frame.closesPlaybackFrame,
                presentationActions = presentationActions,
            )
        return PreparedViewerCut(
            player = prepared,
            viewers = frame.outputs(prepared.bundle.messages),
            transition = checkNotNull(prepared.transition),
            closesPlaybackFrame = prepared.closesPlaybackFrame,
            gameStateId = frame.gameStateId,
        )
    }

    private fun prepareInitialPriority(
        frame: ViewerPromptProjection,
        counter: LogicalSequencePlanner,
        tentative: ProjectionState,
        playerSeatId: Int,
        priorityCandidates: PriorityActionCandidates?,
    ): Triple<BundleResult, ProjectionState, ActionsAvailableReq> {
        val player = frame.fold.viewers[frame.playerIndex].result
        val (projection, projectionNext) =
            bridge.editProjection(tentative) {
                ActionMapper.buildProjectionFromSnapshot(playerSeatId, player.projectionSnapshot, bridge, priorityCandidates)
            }
        val actions = projection.actions
        val gs =
            GsmBuilder.embedActions(
                player.gsm,
                actions,
                GsmFrame.from(player.projectionSnapshot),
                recipientSeatId = playerSeatId,
            )
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, frame.gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = gs
                },
            ) + coinFlipPromptMessages(frame.playerInput.events.events, frame.gameStateId, counter) +
                listOf(
                    makeGRE(GREMessageType.ActionsAvailableReq_695e, frame.gameStateId, counter.nextMsgId()) {
                        it.actionsAvailableReq = actions
                        it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                    },
                )
        return Triple(BundleResult(messages, projection.offers, frame.gameStateId), projectionNext, actions)
    }

    private fun prepareInitialDeclaration(
        game: Game,
        gameStateId: Int,
        counter: LogicalSequencePlanner,
        tentative: ProjectionState,
        playerSeatId: Int,
        kind: PendingActionKind,
        diff: FrameDiff,
    ): Triple<BundleResult, ProjectionState, ActionsAvailableReq> {
        val (projected, projectionNext) =
            bridge.editProjection(tentative) {
                val bundle =
                    when (kind) {
                        PendingActionKind.DECLARE_ATTACKERS -> {
                            val req = RequestBuilder.buildDeclareAttackersReq(SeatId(playerSeatId), bridge)
                            promptRequestBundle(diff, counter, diff.result.gsm, GREMessageType.DeclareAttackersReq_695e) {
                                it.declareAttackersReq = req
                                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
                            }
                        }
                        PendingActionKind.DECLARE_BLOCKERS -> {
                            val req = RequestBuilder.buildDeclareBlockersReq(game, SeatId(playerSeatId), bridge)
                            promptRequestBundle(diff, counter, diff.result.gsm, GREMessageType.DeclareBlockersReq_695e) {
                                it.declareBlockersReq = req
                                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
                            }
                        }
                        PendingActionKind.PRIORITY,
                        PendingActionKind.SYNC_ONLY,
                        -> error("Unsupported initial action kind $kind")
                    }.copy(actionGameStateId = gameStateId)
                bundle to ActionMapper.buildNaiveActionsFromSnapshot(playerSeatId, diff.snap, bridge)
            }
        return Triple(projected.first, projectionNext, projected.second)
    }

    /** Prepare one state-only cut for a pre-block synchronization window. */
    internal fun prepareStateOnlyDiff(
        game: Game,
        counter: LogicalSequencePlanner,
        routes: List<ViewerRoute>,
        phaseTransition: Boolean = false,
    ): PreparedViewerCut<Unit> {
        val pending = bridge.projectionStateSnapshot().viewerCursors[SeatId(seatId)]?.pendingSubmittedTargets
        val intent =
            ViewerProjectionIntent.of(
                listOfNotNull(
                    pending?.let {
                        ProjectionSupplement.SubmitPendingTargets(it.spellInstanceId, it.casterSeatId, it.version)
                    },
                    ProjectionSupplement.PhaseTransition.takeIf { phaseTransition },
                ),
            )
        val frame =
            prepareViewerPromptProjection(
                game,
                counter,
                routes,
                intent,
                intentForViewer = { viewer ->
                    if (viewer.seatId.value == seatId) {
                        intent
                    } else {
                        ViewerProjectionIntent.of(listOfNotNull(ProjectionSupplement.PhaseTransition.takeIf { phaseTransition }))
                    }
                },
                requirePlayer = false,
                updateType = ::resolveFrameUpdateType,
            )
        val contentMsgId = counter.nextMsgId()
        val echoLink = counter.nextGameStateLink().takeIf { phaseTransition }
        val echoMsgId = counter.nextMsgId().takeIf { phaseTransition }
        val commitLink = counter.nextGameStateLink().takeIf { phaseTransition }
        val commitMsgId = counter.nextMsgId().takeIf { phaseTransition }
        val outputs =
            routes.mapIndexed { index, route ->
                val (viewer, builder) = route
                val state =
                    frame.fold.viewers[index]
                        .result.gsm
                val messages =
                    if (phaseTransition) {
                        builder.phaseTransitionStateMessages(
                            state,
                            checkNotNull(frame.fold.phaseTransitionCommitAnnotation),
                            contentMsgId,
                            checkNotNull(echoLink),
                            checkNotNull(echoMsgId),
                            checkNotNull(commitLink),
                            checkNotNull(commitMsgId),
                        )
                    } else {
                        listOf(
                            builder.makeGRE(GREMessageType.GameStateMessage_695e, frame.gameStateId, contentMsgId) {
                                it.gameStateMessage = state
                            },
                        )
                    }
                ViewerBatches(viewer.seatId, listOf(messages))
            }
        return PreparedViewerCut(Unit, outputs, frame.fold.transition, frame.closesPlaybackFrame, frame.gameStateId)
    }

    private fun phaseTransitionStateMessages(
        state: GameStateMessage,
        commitPhaseAnnotation: AnnotationInfo,
        contentMsgId: Int,
        echoLink: LogicalSequencePlanner.GameStateLink,
        echoMsgId: Int,
        commitLink: LogicalSequencePlanner.GameStateLink,
        commitMsgId: Int,
    ): List<GREToClientMessage> {
        val contentAnnotations = applicablePhaseTransitionAnnotations(state)
        val contentState =
            state
                .toBuilder()
                .setGameInfo(GsmBuilder.buildTransitionGameInfo(matchId))
                .clearAnnotations()
                .addAllAnnotations(contentAnnotations)
                .setUpdate(GameStateUpdate.SendHiFi)
                .build()
        val echoStateBuilder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(echoLink.gsId)
                .setPrevGameStateId(state.gameStateId)
                .setUpdate(GameStateUpdate.SendHiFi)
        if (state.hasTurnInfo()) echoStateBuilder.setTurnInfo(state.turnInfo)
        val commitStateBuilder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(commitLink.gsId)
                .setPrevGameStateId(echoLink.gsId)
                .addAnnotations(commitPhaseAnnotation)
                .addAllTimers(PlayerMapper.buildTimers())
                .setUpdate(GameStateUpdate.SendAndRecord)
        if (state.hasTurnInfo()) commitStateBuilder.setTurnInfo(state.turnInfo)
        return listOf(
            makeGRE(GREMessageType.GameStateMessage_695e, state.gameStateId, contentMsgId) {
                it.gameStateMessage = contentState
            },
            makeGRE(GREMessageType.GameStateMessage_695e, echoLink.gsId, echoMsgId) {
                it.gameStateMessage = echoStateBuilder.build()
            },
            makeGRE(GREMessageType.GameStateMessage_695e, commitLink.gsId, commitMsgId) {
                it.gameStateMessage = commitStateBuilder.build()
            },
        )
    }

    private fun applicablePhaseTransitionAnnotations(state: GameStateMessage): List<AnnotationInfo> {
        if (!state.hasTurnInfo() || state.annotationsList.none { AnnotationType.DamageDealt_af5a in it.typeList }) {
            return state.annotationsList
        }
        val applicable =
            state.annotationsList.lastOrNull { annotation ->
                AnnotationType.PhaseOrStepModified in annotation.typeList &&
                    annotation.detailsList.any { detail ->
                        detail.key == DetailKeys.STEP && state.turnInfo.step.number in detail.valueInt32List
                    }
            } ?: return state.annotationsList
        return listOf(applicable) +
            state.annotationsList.filterNot { AnnotationType.PhaseOrStepModified in it.typeList }
    }

    private fun resolveFrameUpdateType(
        snap: GsmSnapshot,
        events: FrameEventLog,
    ): GameStateUpdate =
        if (isTurnOrTriggerDraw(events.events, snap, snap.phase.activePlayer)) {
            GameStateUpdate.SendHiFi
        } else {
            StateMapper.resolveUpdateType(snap, seatId)
        }

    internal fun materializePlaybackCut(
        game: Game,
        counter: LogicalSequencePlanner,
        turnStarted: Boolean,
        events: FrameEventLog,
    ): PlaybackCut = materializePlaybackCut(game, counter, listOf(PlaybackFrameSpec(events, turnStarted)))

    /** Materializes every frame for one closed journal before projection starts. */
    internal fun materializePlaybackCut(
        game: Game,
        counter: LogicalSequencePlanner,
        frameSpecs: List<PlaybackFrameSpec>,
    ): PlaybackCut {
        require(frameSpecs.isNotEmpty()) { "Playback cut must contain at least one frame" }
        val initialProjection = bridge.projectionStateSnapshot()
        val shellPromptFacts = bridge.materializePromptProjectionFacts()
        val shellEffectFacts = bridge.materializeEffectProjectionFacts()
        val laterEffectFacts = shellEffectFacts.withoutPendingEarthbendResolutions()
        var captureProjection = initialProjection
        var actions: ActionsAvailableReq? = null
        val frames =
            frameSpecs.mapIndexed { index, spec ->
                val input =
                    stateFrameInputCapture.captureNeutral(
                        game = game,
                        gameStateId = counter.nextGsId(),
                        revealForSeat = null,
                        events = StateFrameInputCapture.Events.Supplied(spec.events),
                        priorProjectionOverride = captureProjection,
                        promptFactsOverride = if (index == 0) shellPromptFacts else PromptProjectionFacts(),
                        effectFactsOverride = if (index == 0) shellEffectFacts else laterEffectFacts,
                    )
                captureProjection = input.priorProjection
                if (actions == null) {
                    val (mappedActions, actionProjection) =
                        bridge.editProjection(captureProjection) {
                            ActionMapper.buildNaiveActionsFromSnapshot(seatId, input.frame.snapshot, bridge)
                        }
                    actions = mappedActions
                    captureProjection = actionProjection.copy(revision = initialProjection.revision)
                }
                val pending = captureProjection.viewerCursors[SeatId(seatId)]?.pendingSubmittedTargets.takeIf { index == 0 }
                val supplements =
                    listOfNotNull(
                        ProjectionSupplement.NewTurnStarted.takeIf { spec.turnStarted },
                        pending?.let {
                            ProjectionSupplement.SubmitPendingTargets(it.spellInstanceId, it.casterSeatId, it.version)
                        },
                    )
                val contentMsgId = counter.nextMsgId()
                val coinFlipMsgIds =
                    input.frame.events.events
                        .filterIsInstance<GameEvent.CoinFlipped>()
                        .map { counter.nextMsgId() }
                val echoLink = counter.nextGameStateLink()
                val echoMsgId = counter.nextMsgId()
                PlaybackFrameCut(
                    frame = input.frame,
                    intent = ViewerProjectionIntent.of(supplements),
                    contentMsgId = contentMsgId,
                    coinFlipMsgIds = coinFlipMsgIds,
                    echoLink = echoLink,
                    echoMsgId = echoMsgId,
                    lifeTotals = spec.lifeTotals.toMap(),
                )
            }
        return PlaybackCut(
            priorProjection = captureProjection.copy(revision = initialProjection.revision),
            actions = checkNotNull(actions),
            frames = frames,
        )
    }

    internal fun compilePlaybackCut(
        cut: PlaybackCut,
        routes: List<ViewerRoute> =
            listOf(ViewerRoute(ProjectionViewer(SeatId(seatId), ProjectionViewerRole.Player), this)),
    ): PreparedViewerCut<Unit> {
        var framePrior = cut.priorProjection
        var acknowledgements = ProjectionAcknowledgements()
        val batches = routes.associate { it.viewer.seatId to mutableListOf<List<GREToClientMessage>>() }
        cut.frames.forEach { frame ->
            val inputs =
                routes.map { route ->
                    val viewer = route.viewer
                    val state =
                        frame.frame.forViewer(
                            viewingSeatId = viewer.seatId.value,
                            previousSnapshot = framePrior.viewerCursors[viewer.seatId]?.previousSnapshot,
                            updateType = GameStateUpdate.SendHiFi,
                        )
                    StateProjectionCompiler.ViewerInput(
                        input = state,
                        intent = frame.intent,
                        actions = cut.actions.takeIf { viewer.role == ProjectionViewerRole.Player && viewer.seatId.value == seatId },
                        decisionPending = false,
                        role = viewer.role,
                    )
                }
            val fold = StateProjectionCompiler.compileViewers(stateProjectionEnvironment, framePrior, inputs)
            bridge.diffListener?.invoke(
                framePrior,
                inputs.zip(fold.viewers) { input, projected ->
                    val gsm = playbackGsm(projected.result.gsm)
                    GameBridge.ProjectionFoldViewer(input.copy(input = input.input.copy(updateType = gsm.update)), gsm)
                },
            )
            routes.zip(fold.viewers).forEach { entry ->
                val (viewer, builder) = entry.first
                val projected = entry.second
                check(viewer.seatId == projected.seatId)
                val state = inputs.first { it.input.viewingSeatId == viewer.seatId.value }.input
                val gsm = playbackGsm(projected.result.gsm)
                val content =
                    builder.makeGRE(GREMessageType.GameStateMessage_695e, state.gameStateId, frame.contentMsgId) {
                        it.gameStateMessage = gsm
                    }
                val prompts = builder.coinFlipPromptMessages(state.events.events, state.gameStateId, frame.coinFlipMsgIds)
                val echo = builder.buildEchoDiffGsm(frame.echoLink, frame.echoMsgId, GameStateUpdate.SendHiFi, state.gameStateId)
                batches.getValue(viewer.seatId) += (listOf(content) + prompts + echo).withLifeTotals(frame.lifeTotals)
            }
            framePrior = fold.transition.nextState
            acknowledgements = acknowledgements.merge(fold.transition.acknowledgements)
        }
        return PreparedViewerCut(
            player = Unit,
            viewers = routes.map { ViewerBatches(it.viewer.seatId, batches.getValue(it.viewer.seatId)) },
            transition =
                ProjectionTransition(
                    expectedRevision = cut.priorProjection.revision,
                    nextState = framePrior.copy(revision = cut.priorProjection.revision + 1),
                    acknowledgements = acknowledgements,
                ),
        )
    }

    private fun playbackGsm(gsm: GameStateMessage): GameStateMessage =
        if (gsm.persistentAnnotationsList.any { AnnotationType.ModifiedType in it.typeList }) {
            gsm.toBuilder().setUpdate(GameStateUpdate.SendAndRecord).build()
        } else {
            gsm
        }

    private fun EffectProjectionFacts.withoutPendingEarthbendResolutions(): EffectProjectionFacts =
        EffectProjectionFacts(
            boostEntries = boostEntries,
            keywordEntries = keywordEntries,
            crewStates = crewStates,
            saddleStates = saddleStates,
            reconfigureStates = reconfigureStates,
            battlefieldEarthbendSignatures = battlefieldEarthbendSignatures,
        )

    private fun ProjectionAcknowledgements.merge(next: ProjectionAcknowledgements): ProjectionAcknowledgements =
        ProjectionAcknowledgements(
            consumedEarthbendResolutionVersions =
                consumedEarthbendResolutionVersions + next.consumedEarthbendResolutionVersions,
            promptFacts = promptFacts.merge(next.promptFacts),
        )

    private fun PromptFactConsumption.merge(next: PromptFactConsumption): PromptFactConsumption =
        PromptFactConsumption(
            choiceResults = choiceResults + next.choiceResults,
            staleReveals = staleReveals + next.staleReveals,
            convokePayments = convokePayments + next.convokePayments,
            collectEvidenceCosts = collectEvidenceCosts + next.collectEvidenceCosts,
            targetSpecs = targetSpecs + next.targetSpecs,
        )

    private fun List<GREToClientMessage>.withLifeTotals(lifeTotals: Map<Int, Int>): List<GREToClientMessage> {
        if (lifeTotals.isEmpty()) return this
        return mapIndexed { index, message ->
            if (index != 0 || !message.hasGameStateMessage()) return@mapIndexed message
            val gsm = message.gameStateMessage
            val patchedPlayers =
                gsm.playersList.map { player ->
                    val life = lifeTotals[player.systemSeatNumber]
                    if (life == null) player else player.toBuilder().setLifeTotal(life).build()
                }
            message
                .toBuilder()
                .setGameStateMessage(
                    gsm
                        .toBuilder()
                        .clearPlayers()
                        .addAllPlayers(patchedPlayers)
                        .build(),
                ).build()
        }
    }

    /**
     * True when the only action available is Pass (no Cast, Play, Activate).
     * Used by the coordinator runtime to skip empty priority
     * points — mainly on the opponent's turn.
     *
     * This is the protocol-shape check used by the runtime policy:
     *
     * 1. **Engine-side** — [leyline.bridge.PriorityActionCandidates.hasLegalNonManaAction] runs
     *    inside [PlayerController.chooseSpellAbilityToPlay] on the engine
     *    thread, own-turn only. When false, the engine auto-passes before the
     *    bridge round-trip even happens. The session thread never sees it.
     *
     * The coordinator applies this check to the immutable action list it is
     * about to publish. Session code does not invoke it to drive progression.
     *
     * Stateless — lives in [Companion] so callers don't need an instance.
     */

    // --- Request builders (delegate to RequestBuilder) ---
    // MatchSession uses these instead of calling RequestBuilder directly,
    // keeping RequestBuilder as an internal dependency of the bundle layer.

    /** Build a [DeclareAttackersReq] listing legal attackers. */
    fun buildDeclareAttackersReq(): DeclareAttackersReq = RequestBuilder.buildDeclareAttackersReq(SeatId(seatId), bridge)

    @Suppress("CanBeNonNullable", "UnnecessarySafeCall")
    internal fun optionalInteractionBundle(
        game: Game,
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.Optional,
        routes: List<ViewerRoute>,
        sourceCard: Card? = null,
    ): PreparedViewerCut<BlockingInteractionMaterializer.Prepared> {
        require(interaction.commanderReturn != null || interaction.forceSnapshotBeforePrompt || interaction.etbPayLifeReplacement) {
            "Optional interaction does not require a state snapshot"
        }
        if (interaction.etbPayLifeReplacement) {
            val card = checkNotNull(sourceCard) { "ETB replacement interaction requires its source card" }
            val data = checkNotNull(stateProjectionEnvironment.cardReferences.cardDataByName(card.name))
            val abilityGrpId = checkNotNull(stateProjectionEnvironment.cardReferences.choiceSourceAbilityGrpId(data))
            val player =
                blockingInteractions.etbPayLifeOptional(
                    bridge.projectionStateSnapshot(),
                    counter,
                    interaction,
                    ForgeCardId(card.id),
                    data,
                    abilityGrpId,
                    stateProjectionEnvironment.cardProto,
                )
            val content = player.bundle.messages.first { it.hasGameStateMessage() }
            return PreparedViewerCut(
                player,
                routes.map { route ->
                    val messages = if (route.viewer.seatId.value == seatId) player.bundle.messages else listOf(content)
                    ViewerBatches(route.viewer.seatId, listOf(messages))
                },
                checkNotNull(player.transition),
                player.closesPlaybackFrame,
                player.bundle.actionGameStateId,
            )
        }
        val projectedSourceCard = sourceCard ?: interaction.sourceId?.let(bridge::findCard)
        val transientSourceCard =
            projectedSourceCard?.let { card ->
                bridge
                    .editProjection(bridge.projectionStateSnapshot()) {
                        SnapshotCapture.captureBoundCard(card, game, bridge)
                    }.first
            }
        val intent =
            transientSourceCard
                ?.let { ViewerProjectionIntent.of(listOf(ProjectionSupplement.PreStackSpell(it))) }
                ?: ViewerProjectionIntent.EMPTY
        val frame = prepareViewerPromptProjection(game, counter, routes, intent)
        val playerResult = frame.fold.viewers[frame.playerIndex].result
        val stateMessages = stateOnlyMessages(playerResult.gsm, frame.playerInput.events.events, counter)
        val player =
            interaction.commanderReturn?.let { context ->
                commanderOptionalInteractionBundle(
                    game,
                    counter,
                    interaction,
                    context,
                    frame.fold.transition,
                    stateMessages,
                )
            } ?: blockingInteractions.snapshotOptional(
                stateMessages,
                counter,
                interaction,
                frame.fold.transition,
            )
        return PreparedViewerCut(
            player,
            frame.outputs(player.bundle.messages),
            checkNotNull(player.transition),
            player.closesPlaybackFrame,
            player.bundle.actionGameStateId,
        )
    }

    internal fun generalOptionalInteractionBundle(
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.Optional,
    ): BlockingInteractionMaterializer.Prepared =
        blockingInteractions.generalOptional(bridge.projectionStateSnapshot(), counter, interaction)

    private fun commanderOptionalInteractionBundle(
        game: Game,
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.Optional,
        context: CommanderReturnPromptContext,
        transition: ProjectionTransition,
        stateMessages: List<GREToClientMessage>,
    ): BlockingInteractionMaterializer.Prepared {
        val tentative = transition.nextState.copy(revision = transition.expectedRevision)
        val link = counter.nextGameStateLink()
        val (bundle, next) =
            bridge.editProjection(tentative) { editor ->
                val snap = GsmSnapshot.capture(game, bridge, matchId, link.gsId)
                val actions = ActionMapper.buildFromSnapshot(seatId, snap, bridge)
                blockingInteractions.commanderOptional(
                    stateMessages,
                    snap,
                    actions,
                    link,
                    counter,
                    interaction,
                    context,
                    editor,
                    bridge.cardProto,
                )
            }
        return BlockingInteractionMaterializer.Prepared(
            bundle = bundle,
            transition = transition.copy(nextState = next),
            closesPlaybackFrame = true,
        )
    }

    private fun stateOnlyMessages(
        gsm: GameStateMessage,
        events: List<GameEvent>,
        counter: LogicalSequencePlanner,
    ): List<GREToClientMessage> =
        listOf(
            makeGRE(GREMessageType.GameStateMessage_695e, gsm.gameStateId, counter.nextMsgId()) {
                it.gameStateMessage = gsm
            },
        ) + coinFlipPromptMessages(events, gsm.gameStateId, counter) +
            listOf(buildEchoDiffGsm(counter, gsm.update, previousGsId = gsm.gameStateId))

    internal fun commanderPromptCleanup(
        game: Game,
        counter: LogicalSequencePlanner,
        context: CommanderReturnPromptContext,
        beforeMaterialization: (() -> Unit)? = null,
    ): BlockingInteractionMaterializer.Prepared {
        val prior = bridge.projectionStateSnapshot()
        val link = counter.nextGameStateLink()
        beforeMaterialization?.invoke()
        val (snap, frameProjection) =
            bridge.editProjection(prior) { GsmSnapshot.capture(game, bridge, matchId, link.gsId) }
        return blockingInteractions.commanderCleanup(
            frameProjection.copy(revision = prior.revision),
            snap,
            link,
            counter,
            context,
        )
    }

    internal fun numericInteractionBundle(
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.Numeric,
    ): BlockingInteractionMaterializer.Prepared = blockingInteractions.numeric(bridge.projectionStateSnapshot(), counter, interaction)

    internal fun damageInteractionBundle(
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.Damage,
        blockerToughness: Map<ForgeCardId, Int>,
    ): BlockingInteractionMaterializer.Prepared =
        blockingInteractions.damage(bridge.projectionStateSnapshot(), counter, interaction, blockerToughness)

    internal fun damageAssignmentConfirmation(counter: LogicalSequencePlanner): BundleResult =
        blockingInteractions.damageConfirmation(counter)

    internal fun preparePhaseTransitionDiff(
        game: Game,
        counter: LogicalSequencePlanner,
        priorityActions: ActionsAvailableReq? = null,
        includePriorityPrompt: Boolean = true,
        priorProjection: ProjectionState = bridge.projectionStateSnapshot(),
    ): ActionWindowPrepared {
        val (result, next) =
            bridge.editProjection(priorProjection) {
                buildPhaseTransitionDiff(game, counter, priorityActions, includePriorityPrompt)
            }
        val viewerSeatId = SeatId(seatId)
        val priorCursor = next.viewerCursors[viewerSeatId] ?: ViewerProjectionCursor()
        val fullState =
            priorCursor.fullState?.let { retained ->
                result.bundle.messages
                    .asSequence()
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
                    .fold(retained) { baseline, diff -> baseline.applyDiff(diff) }
                    .toBuilder()
                    .setGameStateId(checkNotNull(result.bundle.actionGameStateId))
                    .build()
            }
        return ActionWindowPrepared(
            result.bundle,
            ProjectionTransition(
                expectedRevision = priorProjection.revision,
                nextState =
                    next.copy(
                        viewerCursors =
                            next.viewerCursors +
                                (
                                    viewerSeatId to
                                        priorCursor.copy(
                                            previousSnapshot = result.snapshot,
                                            fullState = fullState,
                                        )
                                ),
                    ),
            ),
        )
    }

    private data class PhaseTransitionResult(
        val bundle: BundleResult,
        val snapshot: GsmSnapshot,
    )

    private fun buildPhaseTransitionDiff(
        game: Game,
        counter: LogicalSequencePlanner,
        priorityActions: ActionsAvailableReq?,
        includePriorityPrompt: Boolean,
    ): PhaseTransitionResult {
        val prevGs = counter.currentGsId()
        val nextGs = counter.nextGsId()
        val snap = GsmSnapshot.capture(game, bridge, matchId, nextGs)

        val frame = GsmFrame.from(snap)
        // Naive actions: always show human's full hand (Cast/Play) regardless of phase.
        // Client expects Cast/Play actions embedded regardless of current phase (cosmetic only;
        // actual priority gating uses ActionsAvailableReq sent when human gets priority).
        val priorityProjection =
            if (priorityActions == null) {
                ActionMapper.buildProjectionFromSnapshot(seatId, snap, bridge)
            } else {
                null
            }
        val projectedPriority = priorityActions ?: checkNotNull(priorityProjection).actions
        val actions = priorityActions ?: ActionMapper.buildNaiveActionsFromSnapshot(seatId, snap, bridge)
        val actionOffers = priorityProjection?.offers ?: emptyList()

        // Message 1: SendHiFi with 2x PhaseOrStepModified + gameInfo
        val gs1 =
            GsmBuilder.buildTransitionState(
                nextGs,
                prevGameStateId = prevGs,
                matchId,
                bridge,
                frame,
                snap = snap,
                isStageTransition = true,
                actions = actions,
                actionSeatId = seatId,
            )
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gs1
            }

        // Message 2: SendHiFi echo (turnInfo + actions, no annotations)
        val msg1GsId = nextGs
        val echoGs = counter.nextGsId()
        val echoBuilder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(echoGs)
                .setPrevGameStateId(msg1GsId)
                .setTurnInfo(frame.turnInfo())
                .setUpdate(GameStateUpdate.SendHiFi)
        embedActions(echoBuilder, actions, seatId, pending = false)
        val msg2 =
            makeGRE(GREMessageType.GameStateMessage_695e, echoGs, counter.nextMsgId()) {
                it.gameStateMessage = echoBuilder.build()
            }

        // Message 3: SendAndRecord with 1x PhaseOrStepModified
        val commitGs = counter.nextGsId()
        val commitBuilder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(commitGs)
                .setPrevGameStateId(echoGs)
                .setTurnInfo(frame.turnInfo())
                .addAnnotations(frame.phaseAnnotation { bridge.nextAnnotationId() })
                .addAllTimers(PlayerMapper.buildTimers())
                .setUpdate(GameStateUpdate.SendAndRecord)
        embedActions(commitBuilder, actions, seatId, pending = includePriorityPrompt)
        val msg3 =
            makeGRE(GREMessageType.GameStateMessage_695e, commitGs, counter.nextMsgId()) {
                it.gameStateMessage = commitBuilder.build()
            }

        val messages = mutableListOf(msg1, msg2, msg3)
        if (includePriorityPrompt) {
            messages +=
                makeGRE(GREMessageType.PromptReq, commitGs, counter.nextMsgId()) {
                    it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.STARTING_PLAYER).build())
                }
            messages +=
                makeGRE(GREMessageType.ActionsAvailableReq_695e, commitGs, counter.nextMsgId()) {
                    it.actionsAvailableReq = projectedPriority
                    it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                }
        }

        return PhaseTransitionResult(
            BundleResult(
                messages,
                actionOffers = actionOffers,
                actionGameStateId = commitGs,
            ),
            snap,
        )
    }

    /** Embed stripped-down actions from ActionsAvailableReq into a GSM builder. */
    private fun embedActions(
        builder: GameStateMessage.Builder,
        actions: ActionsAvailableReq,
        seatId: Int,
        pending: Boolean = true,
    ) {
        if (pending) builder.setPendingMessageCount(1)
        for (action in actions.actionsList) {
            builder.addActions(
                ActionInfo
                    .newBuilder()
                    .setSeatId(seatId)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }
    }

    /**
     * Echo-back bundle for iterative attacker toggle: thin Diff with base creature
     * objects + fresh DeclareAttackersReq.
     *
     * Echo objects carry no combat state; the refreshed DeclareAttackersReq carries
     * selectedDamageRecipient on currently selected attacker options.
     *
     * @param selectedAttackerIds instanceIds currently selected as attackers
     * @param allLegalAttackerIds all instanceIds eligible to attack (for deselect detection)
     */
    internal fun prepareEchoAttackers(
        game: Game,
        counter: LogicalSequencePlanner,
        selectedAttackerIds: List<Int>,
        allLegalAttackerIds: List<Int>,
        selectedAttackAlternatives: Map<Int, Int> = emptyMap(),
        selectedDamageRecipients: Map<Int, DamageRecipient> = emptyMap(),
        presentationActions: ActionsAvailableReq,
    ): ActionWindowPrepared =
        prepareCombatEcho(game, counter, allLegalAttackerIds, GREMessageType.DeclareAttackersReq_695e, presentationActions) {
            val req =
                RequestBuilder.buildDeclareAttackersReq(
                    SeatId(seatId),
                    bridge,
                    committedAttackerIds = selectedAttackerIds.toSet(),
                    committedAttackAlternatives = selectedAttackAlternatives,
                    committedDamageRecipients = selectedDamageRecipients,
                )
            val configureRequest: (GREToClientMessage.Builder) -> Unit = {
                it.declareAttackersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
            }
            configureRequest
        }

    internal fun prepareDeclareAttackers(
        game: Game,
        counter: LogicalSequencePlanner,
        prebuiltReq: DeclareAttackersReq? = null,
    ): ActionWindowPrepared {
        val input =
            frameInput(
                game,
                counter,
                revealForSeat = null,
                eventsOverride = null,
            ) { snap, _ -> StateMapper.resolveUpdateType(snap, seatId) }
        val diff = prepareFrameInputLocked(input)
        val tentative =
            diff.result.transition.nextState
                .copy(revision = diff.result.transition.expectedRevision)
        val (projected, next) =
            bridge.editProjection(tentative) {
                val req = prebuiltReq ?: RequestBuilder.buildDeclareAttackersReq(SeatId(seatId), bridge)
                promptRequestBundle(diff, counter, diff.result.gsm, GREMessageType.DeclareAttackersReq_695e) {
                    it.declareAttackersReq = req
                    it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.DECLARE_ATTACKERS).build())
                }.copy(actionGameStateId = diff.gameStateId) to ActionMapper.buildNaiveActionsFromSnapshot(seatId, diff.snap, bridge)
            }
        return ActionWindowPrepared(
            projected.first,
            diff.result.transition.copy(nextState = next),
            closesPlaybackFrame = true,
            presentationActions = projected.second,
        )
    }

    /**
     * Echo-back for iterative blocker toggle: thin Diff GSM with provisional
     * blocker state on toggled creatures + fresh DeclareBlockersReq.
     *
     * Same pattern as [echoAttackersBundle] — engine's combat object doesn't
     * track provisional blocker selections during iterative declaration.
     */
    internal fun prepareEchoBlockers(
        game: Game,
        counter: LogicalSequencePlanner,
        blockAssignments: Map<Int, Int>,
        presentationActions: ActionsAvailableReq,
    ): ActionWindowPrepared =
        prepareCombatEcho(game, counter, blockAssignments.keys, GREMessageType.DeclareBlockersReq_695e, presentationActions) {
            // Re-prompt with assigned blockers' attackerInstanceIds cleared
            val req =
                RequestBuilder.buildDeclareBlockersReq(
                    game,
                    SeatId(seatId),
                    bridge,
                    blockerAssignments = blockAssignments,
                )
            val configureRequest: (GREToClientMessage.Builder) -> Unit = {
                it.declareBlockersReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
            }
            configureRequest
        }

    private fun prepareCombatEcho(
        game: Game,
        counter: LogicalSequencePlanner,
        includedInstanceIds: Collection<Int>,
        requestType: GREMessageType,
        presentationActions: ActionsAvailableReq,
        buildRequestConfig: () -> (GREToClientMessage.Builder) -> Unit,
    ): ActionWindowPrepared {
        val player =
            bridge.getPlayer(SeatId(seatId)) ?: return BundleResult(emptyList())
                .let(::ActionWindowPrepared)
        val prior = bridge.projectionStateSnapshot()
        val (bundle, next) =
            bridge.editProjection(prior) {
                val nextGs = counter.nextGsId()
                val snap = GsmSnapshot.capture(game, bridge, matchId, nextGs)

                // Echo objects carry no combat state; selection lives in the re-prompt.
                val objects = mutableListOf<GameObjectInfo>()
                for (card in player.getZone(ForgeZoneType.Battlefield).cards) {
                    if (!card.isCreature) continue
                    val fid = ForgeCardId(card.id)
                    val iid = bridge.getOrAllocInstanceId(fid).value
                    if (iid !in includedInstanceIds) continue
                    val cardSnap = snap.objects[fid] ?: continue

                    objects.add(
                        ObjectMapper.buildProvisionalCombatObject(
                            cardSnap,
                            iid,
                            ZoneIds.BATTLEFIELD,
                            ownerSeatId = seatId,
                            cardProto = bridge.cardProto,
                            parentLinkage = snap.boundCards[fid]?.parentLinkage,
                        ),
                    )
                }

                // Cumulative turn-level actions (Cast, Play, ActivateMana, Activate).
                // Client expects echo GSMs to include this running log.
                val gsmBuilder =
                    GameStateMessage
                        .newBuilder()
                        .setType(GameStateType.Diff)
                        .setGameStateId(nextGs)
                        .addAllGameObjects(objects)
                        .setPrevGameStateId(nextGs - 1)
                        .setUpdate(GameStateUpdate.SendAndRecord)
                embedActions(gsmBuilder, presentationActions, seatId, pending = false)

                val msg1 =
                    makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                        it.gameStateMessage = gsmBuilder.build()
                    }

                val configureRequest = buildRequestConfig()
                val msg2 = makeGRE(requestType, nextGs, counter.nextMsgId(), configureRequest)

                BundleResult(listOf(msg1, msg2), actionGameStateId = nextGs)
            }
        return ActionWindowPrepared(bundle, ProjectionTransition(prior.revision, next))
    }

    internal fun prepareDeclareBlockers(
        game: Game,
        counter: LogicalSequencePlanner,
    ): ActionWindowPrepared {
        val input =
            frameInput(
                game,
                counter,
                revealForSeat = null,
                eventsOverride = null,
            ) { snap, _ -> StateMapper.resolveUpdateType(snap, seatId) }
        val diff = prepareFrameInputLocked(input)
        val tentative =
            diff.result.transition.nextState
                .copy(revision = diff.result.transition.expectedRevision)
        val (projected, next) =
            bridge.editProjection(tentative) {
                val req = RequestBuilder.buildDeclareBlockersReq(game, SeatId(seatId), bridge)
                promptRequestBundle(diff, counter, diff.result.gsm, GREMessageType.DeclareBlockersReq_695e) {
                    it.declareBlockersReq = req
                    it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.ORDER_BLOCKERS).build())
                }.copy(actionGameStateId = diff.gameStateId) to ActionMapper.buildNaiveActionsFromSnapshot(seatId, diff.snap, bridge)
            }
        return ActionWindowPrepared(
            projected.first,
            diff.result.transition.copy(nextState = next),
            closesPlaybackFrame = true,
            presentationActions = projected.second,
        )
    }

    /** Prepare, but do not install, one coordinator-owned targeting window. */
    internal fun prepareTargetingWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        window: TargetingWindowValue,
        transientSourceCard: BoundCard? = null,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<TargetingWindowMaterializer.Prepared> {
        val intent = ViewerProjectionIntent.of(targetingSupplements(window, transientSourceCard))
        val frame = prepareViewerPromptProjection(game, counter, routes, intent)
        return finishViewerPrompt(
            frame,
            { gsm, gameStateId, transition ->
                targetingWindows.initial(gsm, gameStateId, counter, transition.nextState, transition, window)
            },
            { it.bundle.messages },
        )
    }

    private fun prepareViewerPromptProjection(
        game: Game,
        counter: LogicalSequencePlanner,
        routes: List<ViewerRoute>,
        intent: ViewerProjectionIntent = ViewerProjectionIntent.EMPTY,
        intentForViewer: (ProjectionViewer) -> ViewerProjectionIntent = { intent },
        promptFacts: PromptProjectionFacts? = null,
        revealPlayerCards: Boolean = false,
        requirePlayer: Boolean = true,
        updateType: (GsmSnapshot, FrameEventLog) -> GameStateUpdate = { _, _ -> GameStateUpdate.Send },
    ): ViewerPromptProjection {
        val gameStateId = counter.nextGsId()
        val observation =
            stateFrameInputCapture.captureNeutral(
                game = game,
                gameStateId = gameStateId,
                revealForSeat = null,
                events = StateFrameInputCapture.Events.CloseBundleFrame,
                promptFactsOverride = promptFacts,
            )
        val playerIndex = routes.indexOfFirst { it.viewer.role == ProjectionViewerRole.Player }
        if (requirePlayer) require(playerIndex >= 0) { "A state-bearing prompt requires one Player viewer" }
        val inputs =
            routes.map { route ->
                val viewer = route.viewer
                StateProjectionCompiler.ViewerInput(
                    observation.frame.forViewer(
                        viewingSeatId = viewer.seatId.value,
                        previousSnapshot = observation.priorProjection.viewerCursors[viewer.seatId]?.previousSnapshot,
                        updateType = updateType(observation.frame.snapshot, observation.frame.events),
                        revealForSeat = viewer.seatId.value.takeIf { revealPlayerCards && viewer.role.seesSeatPrivateCards },
                    ),
                    intentForViewer(viewer),
                    role = viewer.role,
                )
            }
        val fold = StateProjectionCompiler.compileViewers(stateProjectionEnvironment, observation.priorProjection, inputs)
        val selectedPlayerIndex = playerIndex.coerceAtLeast(0)
        return ViewerPromptProjection(
            gameStateId,
            selectedPlayerIndex,
            routes,
            inputs[selectedPlayerIndex].input,
            fold,
            observation.closesPlaybackFrame,
        )
    }

    private fun <T> finishViewerPrompt(
        frame: ViewerPromptProjection,
        prepare: (GameStateMessage, Int, ProjectionTransition) -> T,
        messages: (T) -> List<GREToClientMessage>,
    ): PreparedViewerCut<T> {
        val result = frame.fold.viewers[frame.playerIndex].result
        val player = prepare(result.gsm, frame.gameStateId, frame.fold.transition)
        return PreparedViewerCut(
            player,
            frame.outputs(messages(player)),
            frame.fold.transition,
            frame.closesPlaybackFrame,
            frame.gameStateId,
        )
    }

    private fun <T> finishSettledPrompt(
        frame: ViewerPromptProjection,
        counter: LogicalSequencePlanner,
        prepare: (SettledPromptMaterializationContext) -> T,
        messages: (T) -> List<GREToClientMessage>,
        gameState: (GameStateMessage) -> GameStateMessage = { it },
    ): PreparedViewerCut<T> =
        finishViewerPrompt(
            frame,
            { gsm, gameStateId, transition ->
                prepare(
                    SettledPromptMaterializationContext(
                        gameState(gsm),
                        gameStateId,
                        counter,
                        transition.nextState,
                        transition,
                        seatId,
                    ),
                )
            },
            messages,
        )

    internal fun prepareTargetingRePrompt(
        counter: LogicalSequencePlanner,
        projection: ProjectionState,
        window: TargetingWindowValue,
        selectedOptionIndices: Set<Int>,
        legalOptionIndices: Set<Int>,
    ): TargetingWindowMaterializer.Prepared =
        targetingWindows.rePrompt(counter, projection, window, selectedOptionIndices, legalOptionIndices)

    internal fun prepareTargetingSubmit(
        counter: LogicalSequencePlanner,
        prior: ProjectionState,
        sourceInstanceId: InstanceId?,
        casterSeatId: SeatId,
    ): TargetingWindowMaterializer.Prepared = targetingWindows.submit(counter, prior, sourceInstanceId, casterSeatId)

    /** Prepare, but do not install, one coordinator-owned library-search window. */
    internal fun prepareSearchWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        window: SearchWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<SettledPromptMaterialization> {
        val pendingSubmittedTargets = bridge.viewerProjectionCursor(SeatId(seatId)).pendingSubmittedTargets
        val supplements =
            buildList {
                pendingSubmittedTargets?.let {
                    add(ProjectionSupplement.SubmitPendingTargets(it.spellInstanceId, it.casterSeatId, it.version))
                }
                window.source
                    ?.takeIf { it.abilityOnStack && it.forgeAbilityId != 0 }
                    ?.let { add(ProjectionSupplement.ReserveTriggeredAbility(it.forgeAbilityId)) }
            }
        val frame =
            prepareViewerPromptProjection(
                game,
                counter,
                routes,
                intent = ViewerProjectionIntent.of(supplements),
                intentForViewer = { viewer ->
                    ViewerProjectionIntent.of(
                        supplements.filterNot { it is ProjectionSupplement.SubmitPendingTargets && viewer.seatId.value != seatId },
                    )
                },
                revealPlayerCards = true,
                updateType = { snap, events -> resolveFrameUpdateType(snap, events) },
            )
        return finishSettledPrompt(
            frame,
            counter,
            { context ->
                val stateMessages = stateOnlyMessages(context.gameState, emptyList(), context.sequence)
                searchWindows.initial(stateMessages, context.atCurrentGameState(), window)
            },
            { it.bundle.messages },
        )
    }

    internal fun prepareSearchBaselineReset(prior: ProjectionState): ProjectionTransition = searchWindows.resetBaseline(prior)

    /** Prepare, but do not install, one coordinator-owned competing-replacement window. */
    internal fun prepareReplacementWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        window: ReplacementWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<SettledPromptMaterialization> {
        val frame = prepareViewerPromptProjection(game, counter, routes)
        return finishSettledPrompt(
            frame,
            counter,
            { context -> replacementWindows.prepare(context, window) },
            { it.bundle.messages },
        )
    }

    /**
     * Prepare, but do not install, one coordinator-owned simultaneous-trigger ordering window.
     * The client resolves each option id to an object it already knows, so the pending triggers
     * are shown on the stack in this frame, ahead of Forge putting them there.
     */
    internal fun prepareTriggerOrderWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        window: TriggerOrderWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<SettledPromptMaterialization> {
        val supplements =
            window.options.reversed().map {
                ProjectionSupplement.PreStackAbility(
                    forgeAbilityId = it.forgeAbilityId,
                    sourceForgeCardId = it.sourceForgeCardId,
                    abilityGrpId = it.abilityGrpId,
                    sourceCardGrpId = it.sourceCardGrpId,
                    ownerSeatId = it.ownerSeatId,
                    controllerSeatId = it.controllerSeatId,
                    targetForgeCardIds = emptyList(),
                    isActivatedAbility = false,
                    deferAnnouncement = true,
                )
            }
        val frame = prepareViewerPromptProjection(game, counter, routes, ViewerProjectionIntent.of(supplements))
        return finishSettledPrompt(
            frame,
            counter,
            { context -> triggerOrderWindows.prepare(context, window) },
            { it.bundle.messages },
        )
    }

    /** Prepare, but do not install, one coordinator-owned ordered-card window. */
    internal fun prepareOrderWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        window: OrderWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<SettledPromptMaterialization> {
        val orderPrompt =
            OrderPromptProjection.of(
                window.candidates.map { it.forgeCardId },
                window.sourceForgeCardId,
                window.move?.let { OrderZoneMoveFact.of(it.seatId, it.forgeCardIds, it.putOnTop) },
            )
        val frame =
            prepareViewerPromptProjection(
                game,
                counter,
                routes,
                ViewerProjectionIntent.of(orderPrompt = orderPrompt),
            )
        return finishSettledPrompt(
            frame,
            counter,
            { context -> orderWindows.prepare(context, window) },
            { it.bundle.messages },
        )
    }

    /** Prepare, but do not install, one coordinator-owned divided-allocation window. */
    internal fun prepareDistributionWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        window: DistributionWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<SettledPromptMaterialization> {
        val intent =
            ViewerProjectionIntent.of(
                supplements =
                    listOfNotNull(
                        window.sourceForgeAbilityId
                            .takeIf { !window.sourceIsSpell && it != 0 }
                            ?.let { ProjectionSupplement.ReserveTriggeredAbility(it) },
                    ),
            )
        val frame = prepareViewerPromptProjection(game, counter, routes, intent)
        return finishSettledPrompt(
            frame,
            counter,
            { context -> distributionWindows.prepare(context, window) },
            { it.bundle.messages },
        )
    }

    /** Prepare, but do not install, one coordinator-owned Scry or Surveil window. */
    internal fun prepareGroupingWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        window: GroupingWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<SettledPromptMaterialization> {
        val sourceForgeId =
            window.source
                ?.takeIf { it.abilityOnStack && it.forgeAbilityId != 0 }
                ?.let { FrameIdResolver.triggerStackAbilityForgeId(it.forgeAbilityId) }
                ?: window.source?.hostCardId
        val intent =
            ViewerProjectionIntent.of(
                supplements =
                    listOfNotNull(
                        window.source
                            ?.takeIf { it.abilityOnStack && it.forgeAbilityId != 0 }
                            ?.let { ProjectionSupplement.ReserveTriggeredAbility(it.forgeAbilityId) },
                    ),
                privateCardPrompt =
                    PrivateCardPromptProjection.of(window.candidates.map { it.forgeCardId }, sourceForgeId),
            )
        val frame = prepareViewerPromptProjection(game, counter, routes, intent)
        return finishSettledPrompt(
            frame,
            counter,
            { context -> groupingWindows.prepare(context, window) },
            { it.bundle.messages },
        )
    }

    /** Prepare, but do not install, one coordinator-owned card-backed SelectN window. */
    internal fun prepareCardSelectWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        window: CardSelectWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<SettledPromptMaterialization> {
        val privatePrompt =
            window
                .takeIf {
                    it.kind == CardSelectKind.ManifestDread || it.kind == CardSelectKind.Resolution || it.kind == CardSelectKind.Learn
                }?.let {
                    PrivateCardPromptProjection.of(it.candidates.map { candidate -> candidate.forgeCardId }, it.sourceForgeCardId)
                }
        val frame =
            prepareViewerPromptProjection(
                game,
                counter,
                routes,
                ViewerProjectionIntent.of(privateCardPrompt = privatePrompt),
            )
        return finishSettledPrompt(
            frame,
            counter,
            { context -> cardSelectWindows.prepare(context, window) },
            { it.bundle.messages },
        )
    }

    /** Prepare, but do not install, one coordinator-owned reveal-backed SelectN window. */
    internal fun prepareRevealChoiceWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        window: RevealChoiceWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<SettledPromptMaterialization> {
        val promptFacts =
            bridge.materializePromptProjectionFacts().withClaimedReveal(PromptFactKey(window.journalSeatId, window.revealVersion))
        val frame = prepareViewerPromptProjection(game, counter, routes, promptFacts = promptFacts)
        return finishSettledPrompt(
            frame,
            counter,
            { context -> revealChoiceWindows.prepare(context, window) },
            { it.bundle.messages },
        )
    }

    /** Prepare, but do not install, one coordinator-owned static enum SelectN window. */
    internal fun prepareStaticChoiceWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        window: leyline.bridge.handoff.StaticChoiceWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<SettledPromptMaterialization> {
        val supplements =
            if (window.kind == StaticChoiceKind.Parity && window.sourceForgeCardId != null) {
                val creatures = game.getCardsIn(ForgeZoneType.Battlefield).filter { it.isCreature }
                listOf(
                    ProjectionSupplement.StaticParityChoice(
                        window.sourceForgeCardId,
                        creatures.filter { it.getCMC() % 2 == 0 }.map { ForgeCardId(it.id) },
                        creatures.filter { it.getCMC() % 2 != 0 }.map { ForgeCardId(it.id) },
                    ),
                )
            } else {
                emptyList()
            }
        val frame =
            prepareViewerPromptProjection(game, counter, routes, ViewerProjectionIntent.of(supplements = supplements))
        return finishSettledPrompt(
            frame,
            counter,
            { context -> staticChoiceWindows.prepare(context, window) },
            { it.bundle.messages },
            gameState = { gsm ->
                val playerSnapshot =
                    frame.fold.viewers[frame.playerIndex]
                        .result.projectionSnapshot
                gsm.toBuilder().setTurnInfo(GsmFrame.from(playerSnapshot).turnInfo()).build()
            },
        )
    }

    /** Prepare, but do not install, one coordinator-owned modal choice window. */
    internal fun prepareModalChoiceWindow(
        game: Game,
        counter: LogicalSequencePlanner,
        window: leyline.bridge.handoff.ModalChoiceWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<ModalChoiceWindowMaterializer.Prepared> {
        val intent =
            ViewerProjectionIntent.of(
                supplements =
                    listOfNotNull(
                        window.sourceForgeAbilityId
                            .takeIf { window.triggered && it != 0 }
                            ?.let { ProjectionSupplement.ReserveTriggeredAbility(it) },
                    ),
            )
        val frame = prepareViewerPromptProjection(game, counter, routes, intent)
        return finishSettledPrompt(
            frame,
            counter,
            { context -> modalChoiceWindows.prepare(context, window) },
            { it.materialization.bundle.messages },
        )
    }

    /** Prepare, but do not install, one coordinator-owned mana-source payment presentation. */
    internal fun prepareManaSourcePayment(
        game: Game,
        counter: LogicalSequencePlanner,
        window: leyline.bridge.handoff.ManaSourcePaymentWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<ManaSourcePaymentMaterializer.Prepared> {
        val frame =
            prepareViewerPromptProjection(
                game,
                counter,
                routes,
                updateType = { _, _ -> GameStateUpdate.Send },
            )
        return finishViewerPrompt(
            frame,
            { gsm, gameStateId, transition ->
                manaSourcePayments.prepare(
                    gameState = gsm,
                    gameStateId = gameStateId,
                    counter = counter,
                    projection = transition.nextState,
                    transition = transition,
                    window = window,
                )
            },
            { it.bundle.messages },
        )
    }

    /** Prepare, but do not install, one coordinator-owned one-shot PayCosts window. */
    internal fun prepareOneShotPayCosts(
        game: Game,
        counter: LogicalSequencePlanner,
        window: leyline.bridge.handoff.OneShotPayCostsWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<SettledPromptMaterialization> {
        val frame =
            prepareViewerPromptProjection(
                game,
                counter,
                routes,
                intent = ViewerProjectionIntent.of(payCostsSupplements(window)),
                updateType = { _, _ -> GameStateUpdate.Send },
            )
        return finishSettledPrompt(
            frame,
            counter,
            { context -> oneShotPayCosts.prepare(context, window) },
            { it.bundle.messages },
        )
    }

    /** Prepare, but do not install, the bounded GatherCounters payment window. */
    internal fun prepareGatherCounters(
        game: Game,
        counter: LogicalSequencePlanner,
        window: leyline.bridge.handoff.GatherCountersWindowValue,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<SettledPromptMaterialization> {
        val source = window.promptSource
        val frame =
            prepareViewerPromptProjection(
                game,
                counter,
                routes,
                intent =
                    ViewerProjectionIntent.of(
                        listOf(
                            ProjectionSupplement.PreStackAbility(
                                forgeAbilityId = source.forgeAbilityId,
                                sourceForgeCardId = source.sourceForgeCardId,
                                abilityGrpId = source.abilityGrpId,
                                sourceCardGrpId = source.sourceCardGrpId,
                                ownerSeatId = SeatId(source.ownerSeatId),
                                controllerSeatId = SeatId(source.controllerSeatId),
                                targetForgeCardIds = source.targetForgeCardIds,
                            ),
                        ),
                    ),
                updateType = { _, _ -> GameStateUpdate.Send },
            )
        return finishSettledPrompt(
            frame,
            counter,
            { context -> gatherCounters.prepare(context, window) },
            { it.bundle.messages },
        )
    }

    private fun payCostsSupplements(window: leyline.bridge.handoff.OneShotPayCostsWindowValue): List<ProjectionSupplement> =
        when (val source = window.promptSource) {
            is leyline.bridge.handoff.PayCostsPromptSourceValue.StackAbility ->
                listOf(
                    ProjectionSupplement.PreStackAbility(
                        forgeAbilityId = source.forgeAbilityId,
                        sourceForgeCardId = source.sourceForgeCardId,
                        abilityGrpId = source.abilityGrpId,
                        sourceCardGrpId = source.sourceCardGrpId,
                        ownerSeatId = SeatId(source.ownerSeatId),
                        controllerSeatId = SeatId(source.controllerSeatId),
                        targetForgeCardIds = source.targetForgeCardIds,
                    ),
                )
            is leyline.bridge.handoff.PayCostsPromptSourceValue.StackCard,
            null,
            -> emptyList()
        }

    private fun targetingSupplements(
        window: TargetingWindowValue,
        transientSourceCard: BoundCard?,
    ): List<ProjectionSupplement> {
        val abilityId = window.forgeAbilityId.takeIf { (window.isTriggeredAbility || window.isActivatedAbility) && it != 0 }
        val sourceId = window.sourceForgeCardId
        return buildList {
            transientSourceCard?.let { add(ProjectionSupplement.PreStackSpell(it)) }
            if ((window.isTriggeredAbility || window.isActivatedAbility) &&
                abilityId != null &&
                sourceId != null &&
                transientSourceCard != null
            ) {
                val source = transientSourceCard.snapshot
                add(
                    ProjectionSupplement.PreStackAbility(
                        forgeAbilityId = abilityId,
                        sourceForgeCardId = sourceId,
                        abilityGrpId = window.stackAbilityGrpId,
                        sourceCardGrpId = source.grpId,
                        ownerSeatId = source.owner,
                        controllerSeatId = source.controller,
                        targetForgeCardIds = emptyList(),
                        isActivatedAbility = window.isActivatedAbility,
                    ),
                )
            }
            when {
                sourceId != null ->
                    add(
                        ProjectionSupplement.PlayerSelectingTargets(
                            sourceId,
                            SeatId(seatId),
                            abilityId,
                        ),
                    )
                abilityId != null -> add(ProjectionSupplement.ReserveTriggeredAbility(abilityId))
            }
        }
    }

    private fun promptRequestBundle(
        diff: FrameDiff,
        counter: LogicalSequencePlanner,
        gameStateMessage: GameStateMessage,
        requestType: GREMessageType,
        configureRequest: (GREToClientMessage.Builder) -> Unit,
    ): BundleResult {
        val nextGs = diff.gameStateId
        val msg1 =
            makeGRE(GREMessageType.GameStateMessage_695e, nextGs, counter.nextMsgId()) {
                it.gameStateMessage = gameStateMessage
            }
        val msg2 = makeGRE(requestType, nextGs, counter.nextMsgId(), configureRequest)

        return BundleResult(listOf(msg1, msg2))
    }

    /** Prepare the deferred cast-cost CastingTimeOptionsReq cut without installing projection state. */
    internal fun prepareCastingTimeOptions(
        game: Game,
        counter: LogicalSequencePlanner,
        req: CastingTimeOptionsReq,
        routes: List<ViewerRoute>,
    ): PreparedViewerCut<ActionWindowPrepared> {
        val frame =
            prepareViewerPromptProjection(
                game,
                counter,
                routes,
                updateType = { _, _ -> GameStateUpdate.Send },
            )
        val player = frame.fold.viewers[frame.playerIndex].result
        val diff =
            FrameDiff(
                gameStateId = frame.gameStateId,
                snap = player.projectionSnapshot,
                result = player,
                events = frame.playerInput.events,
                previousSnap = frame.playerInput.previousSnapshot,
            )
        val gsBuilder =
            player.gsm
                .toBuilder()
                .setPendingMessageCount(1)

        val gs = gsBuilder.build()
        val bundle =
            promptRequestBundle(diff, counter, gs, GREMessageType.CastingTimeOptionsReq_695e) {
                it.castingTimeOptionsReq = req
                it.setPrompt(Prompt.newBuilder().setPromptId(PromptIds.CASTING_TIME_OPTIONS).build())
                it.allowCancel = AllowCancel.Abort
                it.allowUndo = true
            }.copy(actionGameStateId = diff.gameStateId)
        val prepared =
            ActionWindowPrepared(
                bundle = bundle,
                transition = frame.fold.transition,
                closesPlaybackFrame = frame.closesPlaybackFrame,
            )
        return PreparedViewerCut(
            player = prepared,
            viewers = frame.outputs(prepared.bundle.messages),
            transition = frame.fold.transition,
            closesPlaybackFrame = frame.closesPlaybackFrame,
            gameStateId = frame.gameStateId,
        )
    }

    /**
     * Wrap a GameStateMessage as QueuedGameStateMessage (type 51) for opponent during prompts.
     */
    fun queuedGameState(
        gameState: GameStateMessage,
        counter: LogicalSequencePlanner,
    ): GREToClientMessage =
        makeGRE(GREMessageType.QueuedGameStateMessage, counter.currentGsId(), counter.nextMsgId()) {
            it.gameStateMessage = gameState
        }

    /**
     * Server-forced pass (EdictalMessage). Tells the client "I'm passing priority for seat X".
     * Breaks the client out of autoPassPriority mode so it re-renders action buttons.
     */
    fun edictalPass(counter: LogicalSequencePlanner): BundleResult {
        val edictal =
            EdictalMessage
                .newBuilder()
                .setEdictMessage(
                    ClientToGREMessage
                        .newBuilder()
                        .setType(ClientMessageType.PerformActionResp_097b)
                        .setSystemSeatId(seatId)
                        .setPerformActionResp(
                            PerformActionResp
                                .newBuilder()
                                .addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                        ),
                ).build()
        val msg =
            makeGRE(GREMessageType.EdictalMessage_695e, counter.currentGsId(), counter.nextMsgId()) {
                it.edictalMessage = edictal
            }
        return BundleResult(listOf(msg))
    }

    /**
     * Game-over sequence: 3x GS Diff + IntermissionReq.
     * Pure proto construction — no bridge or game engine access.
     *
     * Protocol pattern:
     * - gs1: GameInfo(stage=GameOver, matchState=GameComplete, 1 result scope=Game),
     *        players with PendingLoss, teams, LossOfGame annotation (if lethal)
     * - gs2: GameInfo(stage=GameOver, matchState=MatchComplete, 2 results Game+Match)
     * - gs3: bare diff with pendingMessageCount=1
     * - IntermissionReq: options, intermissionPrompt(27) with WinningTeamId param
     *
     * @param reason Game_ae0a for natural game end, Concede for concession
     * @param losingPlayerSeatId seat of the losing player (for LossOfGame annotation)
     * @param lossReason wire-level loss reason for the LossOfGame annotation
     */
    fun gameOverBundle(
        winningTeam: Int,
        counter: LogicalSequencePlanner,
        result: ResultType = ResultType.WinLoss,
        reason: ResultReason = ResultReason.Game_ae0a,
        losingPlayerSeatId: Int = 0,
        lossReason: AnnotationLossReason = AnnotationLossReason.LifeTotal,
    ): BundleResult =
        prepareGameOverBundle(
            result = result,
            winningTeam = winningTeam,
            counter = counter,
            routes = listOf(ViewerRoute(ProjectionViewer(SeatId(seatId), ProjectionViewerRole.Player), this)),
            reason = reason,
            losingPlayerSeatId = losingPlayerSeatId,
            lossReason = lossReason,
        ).viewers.single().batches.single().let(::BundleResult)

    /** Prepare the terminal lifecycle bundle without installing projection state. */
    internal fun prepareGameOverBundle(
        result: ResultType = ResultType.WinLoss,
        winningTeam: Int,
        counter: LogicalSequencePlanner,
        routes: List<ViewerRoute>,
        reason: ResultReason = ResultReason.Game_ae0a,
        losingPlayerSeatId: Int = 0,
        lossReason: AnnotationLossReason = AnnotationLossReason.LifeTotal,
        priorProjection: ProjectionState = bridge.projectionStateSnapshot(),
    ): PreparedViewerCut<Unit> {
        val ids = allocateGameOverIds(counter)
        val (outputs, next) =
            bridge.editProjection(priorProjection) {
                val snapshot = bridge.getGame()?.let { GsmSnapshot.capture(it, bridge, matchId, 0) }
                routes.map { route ->
                    val (viewer, builder) = route
                    ViewerBatches(
                        viewer.seatId,
                        listOf(
                            builder
                                .buildGameOverBundle(result, winningTeam, ids, snapshot, reason, losingPlayerSeatId, lossReason)
                                .messages,
                        ),
                    )
                }
            }
        return PreparedViewerCut(Unit, outputs, ProjectionTransition(priorProjection.revision, next))
    }

    private data class GameOverIds(
        val previousGsId: Int,
        val firstGsId: Int,
        val secondGsId: Int,
        val thirdGsId: Int,
        val firstMsgId: Int,
        val secondMsgId: Int,
        val thirdMsgId: Int,
        val intermissionMsgId: Int,
    )

    private fun allocateGameOverIds(counter: LogicalSequencePlanner): GameOverIds {
        val previous = counter.currentGsId()
        val firstGsId = counter.nextGsId()
        val secondGsId = counter.nextGsId()
        val thirdGsId = counter.nextGsId()
        return GameOverIds(
            previous,
            firstGsId,
            secondGsId,
            thirdGsId,
            counter.nextMsgId(),
            counter.nextMsgId(),
            counter.nextMsgId(),
            counter.nextMsgId(),
        )
    }

    @Suppress("LongMethod") // fixed three-message game-over protocol sequence
    private fun buildGameOverBundle(
        result: ResultType,
        winningTeam: Int,
        ids: GameOverIds,
        gameOverSnap: GsmSnapshot?,
        reason: ResultReason,
        losingPlayerSeatId: Int,
        lossReason: AnnotationLossReason,
    ): BundleResult {
        val prevGsId = ids.previousGsId
        val losingTeam = if (winningTeam == 1) 2 else 1

        // Shared GameInfo fields matching initial state projection.
        fun baseGameInfo() =
            GameInfo
                .newBuilder()
                .setMatchID(matchId)
                .setGameNumber(1)
                .setStage(GameStage.GameOver)
                .setType(GameType.Duel)
                .setVariant(GameVariant.Normal)
                .setMatchWinCondition(MatchWinCondition.SingleElimination)
                .setSuperFormat(SuperFormat.Constructed)
                .setMulliganType(MulliganType.London)
                .setDeckConstraintInfo(
                    DeckConstraintInfo
                        .newBuilder()
                        .setMinDeckSize(60)
                        .setMaxDeckSize(250)
                        .setMaxSideboardSize(15),
                )

        val gameResult =
            ResultSpec
                .newBuilder()
                .setScope(MatchScope.Game_a146)
                .setResult(result)
                .setWinningTeamId(winningTeam)
                .setReason(reason)

        val matchResult =
            ResultSpec
                .newBuilder()
                .setScope(MatchScope.Match)
                .setResult(result)
                .setWinningTeamId(winningTeam)
                .setReason(reason)

        // gs1: GameComplete with Game result only, PendingLoss players
        val gs1Info =
            baseGameInfo()
                .setMatchState(MatchState.GameComplete)
                .addResults(gameResult)
        val gs1Id = ids.firstGsId
        val gs1 =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gs1Id)
                .setPrevGameStateId(prevGsId)
                .setGameInfo(gs1Info)
                .setUpdate(GameStateUpdate.SendAndRecord)
        // Teams with PendingLoss for losing team
        if (losingPlayerSeatId != 0) {
            gs1.addTeams(
                TeamInfo
                    .newBuilder()
                    .setId(losingTeam)
                    .addPlayerIds(losingPlayerSeatId)
                    .setStatus(TeamStatus.PendingLoss_a458),
            )
        }
        // Players: loser with full state (lifeTotal, maxHandSize, etc.) + PendingLoss status
        if (gameOverSnap != null && losingPlayerSeatId != 0) {
            val loserInfo =
                PlayerMapper
                    .buildFromSnapshot(gameOverSnap, losingPlayerSeatId)
                    .toBuilder()
                    .setStatus(PlayerStatus.PendingLoss_a1c6)
            gs1.addPlayers(loserInfo)
        }
        // Timers — inactivity timer on gs1
        gs1.addAllTimers(PlayerMapper.buildTimers())
        // LossOfGame annotation
        if (losingPlayerSeatId != 0) {
            gs1.addAnnotations(AnnotationBuilder.lossOfGame(SeatId(losingPlayerSeatId), lossReason))
        }

        // gs2: MatchComplete with both Game + Match results
        val gs2Info =
            baseGameInfo()
                .setMatchState(MatchState.MatchComplete)
                .addResults(gameResult)
                .addResults(matchResult)
        val gs2Id = ids.secondGsId
        val gs2 =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gs2Id)
                .setPrevGameStateId(gs1Id)
                .setGameInfo(gs2Info)
                .setUpdate(GameStateUpdate.SendAndRecord)

        // gs3: bare diff with pendingMessageCount=1 (IntermissionReq follows)
        val gs3Id = ids.thirdGsId
        val gs3 =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gs3Id)
                .setPrevGameStateId(gs2Id)
                .setPendingMessageCount(1)
                .setUpdate(GameStateUpdate.SendAndRecord)

        val messages =
            mutableListOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gs1Id, ids.firstMsgId) { it.gameStateMessage = gs1.build() },
                makeGRE(GREMessageType.GameStateMessage_695e, gs2Id, ids.secondMsgId) { it.gameStateMessage = gs2.build() },
                makeGRE(GREMessageType.GameStateMessage_695e, gs3Id, ids.thirdMsgId) { it.gameStateMessage = gs3.build() },
            )

        messages.add(
            makeGRE(GREMessageType.IntermissionReq_695e, gs3Id, ids.intermissionMsgId) {
                it.intermissionReq =
                    IntermissionReq
                        .newBuilder()
                        .setResult(
                            ResultSpec
                                .newBuilder()
                                .setScope(MatchScope.Match)
                                .setResult(result)
                                .setWinningTeamId(winningTeam)
                                .setReason(reason),
                        ).addOptions(
                            UserOption
                                .newBuilder()
                                .setOptionPrompt(Prompt.newBuilder().setPromptId(PromptIds.DRAW_CARD))
                                .setResponseType(ClientMessageType.DrawCardResp),
                        ).addOptions(
                            UserOption
                                .newBuilder()
                                .setOptionPrompt(Prompt.newBuilder().setPromptId(PromptIds.REVEAL_HAND))
                                .setResponseType(ClientMessageType.RevealHandResp),
                        ).setIntermissionPrompt(
                            Prompt
                                .newBuilder()
                                .setPromptId(PromptIds.MATCH_RESULT_WIN_LOSS)
                                .addParameters(
                                    PromptParameter
                                        .newBuilder()
                                        .setParameterName("WinningTeamId")
                                        .setType(ParameterType.Number)
                                        .setNumberValue(winningTeam),
                                ),
                        ).build()
            },
        )

        return BundleResult(messages)
    }

    /**
     * Timer start: sends [TimerStateMessage] (GRE type 56) with Decision timer running.
     * Sent on priority grant — the client shows a rope countdown.
     */
    fun timerStart(
        counter: LogicalSequencePlanner,
        durationSec: Int = DECISION_TIMER_SEC,
    ): BundleResult = buildTimerBundle(counter, running = true, durationSec = durationSec)

    /**
     * Timer stop: sends [TimerStateMessage] with running=false.
     * Sent when client responds to an action (pass/cast/play).
     */
    fun timerStop(
        counter: LogicalSequencePlanner,
        durationSec: Int = DECISION_TIMER_SEC,
    ): BundleResult = buildTimerBundle(counter, running = false, durationSec = durationSec)

    private fun buildTimerBundle(
        counter: LogicalSequencePlanner,
        running: Boolean,
        durationSec: Int,
    ): BundleResult {
        val timer =
            TimerStateMessage
                .newBuilder()
                .setSeatId(seatId)
                .addTimers(
                    TimerInfo
                        .newBuilder()
                        .setTimerId(1)
                        .setType(TimerType.Decision)
                        .setDurationSec(durationSec)
                        .setElapsedSec(0)
                        .setRunning(running)
                        .setBehavior(TimerBehavior.Timeout_a3cd),
                ).build()
        val msg =
            makeGRE(GREMessageType.TimerStateMessage_695e, counter.currentGsId(), counter.nextMsgId()) {
                it.timerStateMessage = timer
            }
        return BundleResult(listOf(msg))
    }

    /**
     * Builds the empty diff echo used after content GSMs prepared by
     * [compilePlaybackCut], [optionalInteractionBundle], [prepareSearchWindow],
     * and [preparePhaseTransitionDiff]. Other prompt materializers return their
     * prepared state/request batches without adding an echo here.
     */
    fun buildEchoDiffGsm(
        counter: LogicalSequencePlanner,
        updateType: GameStateUpdate = GameStateUpdate.Send,
        previousGsId: Int? = null,
    ): GREToClientMessage = buildEchoDiffGsm(counter.nextGameStateLink(), counter.nextMsgId(), updateType, previousGsId)

    private fun buildEchoDiffGsm(
        link: LogicalSequencePlanner.GameStateLink,
        msgId: Int,
        updateType: GameStateUpdate,
        previousGsId: Int? = null,
    ): GREToClientMessage {
        val prev = previousGsId ?: link.prevGsId
        return makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, msgId) {
            it.gameStateMessage =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(link.gsId)
                    .setPrevGameStateId(prev)
                    .setUpdate(updateType)
                    .build()
        }
    }

    /** Build a single GRE message. */

    private fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage {
        val gre =
            GREToClientMessage
                .newBuilder()
                .setType(type)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .addSystemSeatIds(seatId)
        configure(gre)
        return gre.build()
    }

    internal fun coinFlipPromptMessages(
        events: List<GameEvent>,
        gsId: Int,
        counter: LogicalSequencePlanner,
    ): List<GREToClientMessage> {
        val coinEvents = events.filterIsInstance<GameEvent.CoinFlipped>()
        return coinFlipPromptMessages(coinEvents, gsId, coinEvents.map { counter.nextMsgId() })
    }

    private fun coinFlipPromptMessages(
        events: List<GameEvent>,
        gsId: Int,
        msgIds: List<Int>,
    ): List<GREToClientMessage> =
        events.filterIsInstance<GameEvent.CoinFlipped>().zip(msgIds).map { (event, msgId) ->
            makeGRE(GREMessageType.PromptReq, gsId, msgId) {
                it.setPrompt(
                    Prompt
                        .newBuilder()
                        .setPromptId(PromptIds.COIN_FLIP)
                        .addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("PlayerId")
                                .setType(ParameterType.Reference_a14a)
                                .setReference(
                                    Reference
                                        .newBuilder()
                                        .setType(ReferenceType.PlayerSeatId)
                                        .setId(event.flipperSeatId.value),
                                ),
                        ).addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("CoinFlipResult")
                                .setType(ParameterType.Reference_a14a)
                                .setReference(
                                    Reference
                                        .newBuilder()
                                        .setType(ReferenceType.LocalizationId)
                                        .setId(if (event.result == 1) COIN_FLIP_WIN_LOCALIZATION_ID else COIN_FLIP_LOSS_LOCALIZATION_ID),
                                ),
                        ).build(),
                )
            }
        }

    companion object {
        private const val COIN_FLIP_WIN_LOCALIZATION_ID = 47
        private const val COIN_FLIP_LOSS_LOCALIZATION_ID = 48

        /**
         * The client reveals the rope once the running timer has this much time left
         * (`LowTimeWarning.SHOW_TIMER_THRESHOLD`; it hides again at 40s). Anything at
         * or below this duration puts the rope on screen the instant priority arrives.
         */
        private const val ROPE_VISIBLE_SEC = 30

        /** Quiet thinking time before the rope appears. */
        private const val DECISION_GRACE_SEC = 30

        /** Decision timer duration: grace first, then the rope for its last stretch. */
        const val DECISION_TIMER_SEC = ROPE_VISIBLE_SEC + DECISION_GRACE_SEC

        /**
         * True when the drained [events] describe a turn-boundary or trigger-driven
         * draw — one that should be emitted as [GameStateUpdate.SendHiFi] rather
         * than the default [GameStateUpdate.SendAndRecord].
         *
         * The wire contract marks spell-driven draws in Main1
         * (Divination, Opt, etc.) as `SendAndRecord`, but turn-boundary auto-draws
         * and upkeep-triggered draws as `SendHiFi`. This helper detects the
         * latter by requiring all of:
         *
         * 1. A Library→Hand [GameEvent.ZoneChanged] whose card owner is the
         *    active seat.
         * 2. No [GameEvent.SpellCast] for that seat in the same bundle
         *    (filters out cast-Divination-draw chains).
         * 3. No [GameEvent.SpellResolved] for that seat in the same bundle
         *    (filters out resolve-Divination-draw chains).
         * 4. The snapshot phase is UPKEEP, DRAW, or MAIN1 — the window leyline
         *    bundles the auto-draw into (MAIN1 covers the common case where the
         *    DRAW step's card move lands in the first MAIN1 priority grant's
         *    GSM).
         * 5. A non-null snapshot phase — fall back to the default updateType
         *    when phase is unknown.
         */
        internal fun isTurnOrTriggerDraw(
            events: List<GameEvent>,
            snap: GsmSnapshot,
            activeSeat: SeatId,
        ): Boolean {
            val phase = snap.phase.phase ?: return false
            if (phase != PhaseType.UPKEEP && phase != PhaseType.DRAW && phase != PhaseType.MAIN1) return false

            val hasActiveSeatDraw =
                events.any { ev ->
                    ev is GameEvent.ZoneChanged &&
                        ev.from == Zone.Library &&
                        ev.to == Zone.Hand &&
                        snap.objects[ev.cardId]?.owner == activeSeat
                }
            if (!hasActiveSeatDraw) return false

            val hasActiveSeatSpellCast =
                events.any { ev -> ev is GameEvent.SpellCast && ev.seatId == activeSeat && !ev.isTrigger }
            if (hasActiveSeatSpellCast) return false

            val hasActiveSeatSpellResolved =
                events.any { ev ->
                    ev is GameEvent.SpellResolved && snap.objects[ev.cardId]?.owner == activeSeat
                }
            if (hasActiveSeatSpellResolved) return false

            return true
        }
    }
}
