package leyline.bridge.coord

import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.BlockingInteractionRuntime
import leyline.bridge.handoff.DamageAssignmentCommand
import leyline.bridge.handoff.DeclarationAnswer
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.types.SeatId
import leyline.game.MaterializationDiagnostic
import leyline.game.PendingCut
import leyline.game.PendingPromptCut
import leyline.game.PlaybackCutBoundary
import leyline.game.PlaybackCutReason
import leyline.game.PlaybackCutRequest
import leyline.game.PlaybackTerminalFailure
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.FailureReason
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.IllegalRequestMessage
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.PromptParameter
import wotc.mtgo.gre.external.messaging.Messages.SetSettingsResp
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage
import java.util.concurrent.CancellationException

/**
 * Owns journal close, exact cuts, projection install, committed viewer feeds,
 * terminal state, and the focused interaction runtimes beneath that boundary.
 */
internal class MatchCutCoordinator(
    internal val bridge: GameBridge,
    internal val matchId: String,
    private val delayMultiplier: Double,
) : BlockingInteractionRuntime {
    internal data class ViewerFeed(
        val seatId: SeatId,
        val builder: BundleBuilder,
        val queue: ArrayDeque<CommittedOutputBatch> = ArrayDeque(),
        var requestedCut: PlaybackCutRequest? = null,
        var pendingCut: PendingCut? = null,
        var beforeBatchEnqueue: ((Int, List<GREToClientMessage>) -> Unit)? = null,
    )

    internal val feedLock = Any()
    internal val deliverySignal = MatchDeliverySignal()
    private val feeds = mutableMapOf<SeatId, ViewerFeed>()
    private val viewers = linkedMapOf<SeatId, ProjectionViewer>()
    internal val cutInstaller = CoordinatorCutInstaller(this)
    internal val syncOnly = MatchSyncOnlyRuntime(this)
    internal val gameOver = MatchGameOverRuntime(this)
    internal val lifecycle = MatchLifecycleRuntime(this)
    internal val actions = MatchActionWindowRuntime(this)
    internal val deferredCast = DeferredCastWindowRuntime(this, actions)
    private val promptSets = mutableMapOf<SeatId, MatchPromptRuntimeSet>()
    internal val prompts: MatchPromptRuntimeSet get() = promptRuntimes(humanSeat)

    internal fun promptRuntimes(seatId: SeatId): MatchPromptRuntimeSet =
        synchronized(feedLock) {
            promptSets.getOrPut(seatId) { MatchPromptRuntimeSet(this, seatId) }
        }

    internal fun allPromptRuntimes(): List<MatchPromptRuntimeSet> = synchronized(feedLock) { promptSets.values.toList() }

    // Read-only views; [prompts] remains the sole lifecycle owner.
    internal val targeting get() = prompts.targeting
    internal val compatibilityCostSelection get() = prompts.compatibilityCostSelection
    internal val search get() = prompts.search
    internal val replacement get() = prompts.replacement
    internal val order get() = prompts.order
    internal val triggerOrder get() = prompts.triggerOrder
    internal val distribution get() = prompts.distribution
    internal val grouping get() = prompts.grouping
    internal val cardSelect get() = prompts.cardSelect
    internal val staticChoices get() = prompts.staticChoices
    internal val revealChoices get() = prompts.revealChoices
    internal val modalChoices get() = prompts.modalChoices
    internal val manaSourcePayments get() = prompts.manaSourcePayments
    internal val oneShotPayCosts get() = prompts.oneShotPayCosts
    internal val interactions get() = prompts.blocking

    private val terminal = MatchCutTerminalRuntime(this)

    internal val humanSeat: SeatId get() = bridge.seating.humanSeat

    internal var beforeCommanderCleanupMaterialization: (() -> Unit)? = null
    internal var beforeCommanderCleanupInstall: (() -> Unit)? = null

    internal var beforePublicationLock: (() -> Unit)? = null

    fun actionWindowRuntime(seatId: SeatId): GameActionBridge.ActionWindowRuntime = actions.bridge(seatId)

    /** Publish one terminal lifecycle cut across every registered viewer feed. Delivery stays with sessions. */
    fun publishGameOver(
        seatId: SeatId,
        outcome: GameOverOutcome,
    ) = gameOver.publish(seatId, outcome)

    fun publishGameOverFromEngine(seatId: SeatId) = gameOver.publishFromEngine(seatId)

    fun publishConcession(seatId: SeatId) = gameOver.publishConcession(seatId)

    fun committedGameOverOutcome(): GameOverOutcome? = gameOver.committed()

    /** Commit the settings acknowledgement behind older coordinator output. */
    fun publishSettings(
        seatId: SeatId,
        settings: SettingsMessage?,
    ) {
        registerViewer(seatId)
        synchronized(feedLock) {
            ensureOpen()
            val prior = bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            val msgId = planner.currentMsgId()
            val response = SetSettingsResp.newBuilder()
            settings?.let(response::setSettings)
            val message =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.SetSettingsResp_695e)
                    .addSystemSeatIds(seatId.value)
                    .setMsgId(msgId)
                    .setGameStateId(planner.currentGsId())
                    .setSetSettingsResp(response)
                    .build()
            planner.setMsgId(msgId + 1)
            cutInstaller.install(
                feed(seatId),
                PreparedCut.prepare(prior, planner, listOf(message), projection = null, closesPlaybackFrame = false),
                onFailure = ::fail,
            )
        }
    }

    /** Materialize and commit one rejected client response in gameplay order. */
    fun publishIllegalRequest(
        seatId: SeatId,
        invalid: ClientToGREMessage,
        reason: FailureReason,
    ) {
        registerViewer(seatId)
        synchronized(feedLock) {
            ensureOpen()
            val prior = bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            val message =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.IllegalRequest)
                    .setMsgId(planner.nextMsgId())
                    .setGameStateId(planner.currentGsId())
                    .addSystemSeatIds(invalid.systemSeatId)
                    .setPrompt(
                        Prompt
                            .newBuilder()
                            .setPromptId(PromptIds.ILLEGAL_REQUEST)
                            .addParameters(
                                PromptParameter
                                    .newBuilder()
                                    .setParameterName("FailureReason")
                                    .setType(ParameterType.Number)
                                    .setNumberValue(reason.number),
                            ),
                    ).setIllegalRequestMessage(
                        IllegalRequestMessage
                            .newBuilder()
                            .setInvalidMessage(invalid)
                            .setReason(reason),
                    ).build()
            cutInstaller.install(
                feed(seatId),
                PreparedCut.prepare(prior, planner, listOf(message), projection = null, closesPlaybackFrame = false),
                onFailure = ::fail,
            )
        }
    }

    fun legalAttackerIds(actionId: String): List<Int> = actions.legalAttackerIds(actionId)

    fun hasLegalAttackers(actionId: String): Boolean = actions.hasLegalAttackers(actionId)

    fun legalBlockerCount(actionId: String): Int = actions.legalBlockerCount(actionId)

    fun updateDeclaration(
        actionId: String,
        responseGameStateId: Int,
        answer: DeclarationAnswer,
    ): Boolean = actions.updateDeclaration(actionId, responseGameStateId, answer)

    fun republishDeclaration(actionId: String): Boolean = actions.republishDeclaration(actionId)

    fun bindInitialActionWindow(
        actionId: String,
        gameStateId: Int,
    ): ActionsAvailableReq = actions.bindInitial(actionId, gameStateId)

    fun replaceWithPhaseTransition(
        actionId: String,
        includePriorityPrompt: Boolean = true,
    ): List<GREToClientMessage> = actions.replaceWithPhaseTransition(actionId, includePriorityPrompt)

    fun claimPriorityResponse(
        actionId: String,
        responseGameStateId: Int,
        response: Action,
        defer: Boolean,
    ): MatchActionWindowRuntime.PriorityResponseClaim? = actions.claimPriority(actionId, responseGameStateId, response, defer)

    fun completeActionClaim(
        claim: MatchActionWindowRuntime.ActionClaim,
        childToken: Long? = null,
    ): Boolean = actions.complete(claim, childToken)

    fun failActionClaim(
        claim: MatchActionWindowRuntime.ActionClaim,
        cause: Throwable,
    ): Nothing = actions.failClaim(claim, cause)

    fun reopenActionClaim(claim: MatchActionWindowRuntime.ActionClaim): Boolean = actions.reopenClaim(claim)

    fun submitDeclaredAction(
        actionId: String,
        responseGameStateId: Int,
    ): Boolean = actions.submitDeclaration(actionId, responseGameStateId)

    fun currentBlockingInteraction(): PublishedBlockingInteraction? = interactions.current()

    fun submitOptionalAnswer(
        interactionId: String,
        gameStateId: Int,
        accepted: Boolean,
    ): Boolean = interactions.submitOptional(interactionId, gameStateId, accepted)

    fun submitNumericAnswer(
        interactionId: String,
        gameStateId: Int,
        value: Int,
    ): Boolean = interactions.submitNumeric(interactionId, gameStateId, value)

    fun submitDamageCommand(
        interactionId: String,
        gameStateId: Int,
        assignments: List<DamageAssignmentCommand>,
    ): Boolean = interactions.submitDamageCommand(interactionId, gameStateId, assignments)

    override fun awaitOptional(
        interaction: BlockingInteraction.Optional,
        timeoutMs: Long?,
        defaultOnTimeout: Boolean,
    ): Boolean =
        awaitOptional(
            interaction = interaction,
            sourceCard = null,
            timeoutMs = timeoutMs,
            defaultOnTimeout = defaultOnTimeout,
        )

    override fun awaitOptional(
        interaction: BlockingInteraction.Optional,
        sourceCard: forge.game.card.Card?,
        timeoutMs: Long?,
        defaultOnTimeout: Boolean,
    ): Boolean = interactions.awaitOptional(interaction, sourceCard, timeoutMs, defaultOnTimeout)

    override fun awaitNumeric(
        interaction: BlockingInteraction.Numeric,
        timeoutMs: Long?,
    ): Int = interactions.awaitNumeric(interaction, timeoutMs)

    override fun awaitDamage(
        interaction: BlockingInteraction.Damage,
        attacker: forge.game.card.Card,
        blockers: forge.game.card.CardCollectionView,
        defender: forge.game.GameEntity?,
        timeoutMs: Long?,
        fallback: () -> MutableMap<forge.game.card.Card?, Int>?,
    ): MutableMap<forge.game.card.Card?, Int>? = interactions.awaitDamage(interaction, attacker, blockers, defender, timeoutMs, fallback)

    override fun takeCachedDamage(
        attacker: forge.game.card.Card,
        blockers: forge.game.card.CardCollectionView,
    ): MutableMap<forge.game.card.Card?, Int>? = interactions.takeCachedDamage(attacker, blockers)

    fun registerViewer(
        seatId: SeatId,
        role: ProjectionViewerRole = ProjectionViewerRole.Player,
    ) {
        synchronized(feedLock) {
            viewers.putIfAbsent(seatId, ProjectionViewer(seatId, role))
            feeds.getOrPut(seatId) { ViewerFeed(seatId, BundleBuilder(bridge, matchId, seatId.value)) }
        }
    }

    fun registerViewers(registered: List<ProjectionViewer>) {
        require(registered.map { it.seatId }.distinct().size == registered.size) { "Viewer seats must be unique" }
        synchronized(feedLock) {
            if (viewers.isNotEmpty()) {
                check(viewers.values.toList() == registered) { "Viewer roster is already registered" }
                return
            }
            registered.forEach { viewer ->
                viewers[viewer.seatId] = viewer
                feeds[viewer.seatId] = ViewerFeed(viewer.seatId, BundleBuilder(bridge, matchId, viewer.seatId.value))
            }
        }
    }

    internal fun registeredViewers(): List<ProjectionViewer> = synchronized(feedLock) { viewers.values.toList() }

    internal fun viewerRoutes(activeSeat: SeatId? = null): List<BundleBuilder.ViewerRoute> =
        registeredViewers().map { viewer ->
            val perspective =
                if (activeSeat != null && viewer.seatId != activeSeat && viewer.role == ProjectionViewerRole.Player) {
                    viewer.copy(role = ProjectionViewerRole.SeatObserver)
                } else {
                    viewer
                }
            BundleBuilder.ViewerRoute(perspective, feed(viewer.seatId).builder)
        }

    internal fun requireViewer(seatId: SeatId) {
        synchronized(feedLock) { check(seatId in viewers) { "Viewer $seatId is not registered" } }
    }

    /** Teardown disposition: committed-but-undrained batches are discarded with their viewer feeds. */
    fun unregisterViewers() {
        synchronized(feedLock) {
            feeds.clear()
            viewers.clear()
        }
    }

    /** Terminalize every owned waiter and reject later cuts/interactions. */
    fun shutdown(cause: Throwable = CancellationException("Match projection coordinator shut down")) {
        synchronized(feedLock) { terminal.current() ?: terminal.terminate(cause) }
        synchronized(feedLock) { feeds.values.forEach { it.requestedCut = null } }
        deliverySignal.signal()
        bridge.prioritySignal.signal()
    }

    fun resetForNewGame() {
        synchronized(feedLock) {
            check(feeds.isEmpty()) { "Cannot reset coordinator with registered viewers" }
            terminal.reset()
            gameOver.reset()
            actions.reset()
            deferredCast.discard()
            allPromptRuntimes().forEach { it.reset() }
        }
    }

    fun requestPlaybackCut(
        seatId: SeatId,
        request: PlaybackCutRequest,
    ) {
        beforePublicationLock?.invoke()
        synchronized(feedLock) {
            ensureOpen()
            val feed = feed(seatId)
            feed.requestedCut = feed.requestedCut?.aggregate(request) ?: request
        }
    }

    fun onMainGameLoopStarted(seatId: SeatId) {
        synchronized(feedLock) {
            ensureOpen()
            val feed = feed(seatId)
            val events = bridge.closeBundleFrame(seatId.value).openingHandActions()
            feed.requestedCut = null
            if (events.events.isNotEmpty()) {
                installPlaybackCut(
                    seatId,
                    feed,
                    PlaybackCutRequest(PlaybackCutReason.OpeningHandAction, 0, turnStarted = false),
                    events,
                )
            }
        }
    }

    fun flushPlaybackCut(
        seatId: SeatId,
        boundary: PlaybackCutBoundary,
    ) {
        val request =
            synchronized(feedLock) {
                ensureOpen()
                val feed = feed(seatId)
                val request = feed.requestedCut ?: return
                if (request.boundary != boundary) return
                val events =
                    try {
                        bridge.closeBundleFrame(seatId.value)
                    } catch (ex: Exception) {
                        failPlayback(ex, diagnostic = MaterializationDiagnostic(request, null))
                    }
                installPlaybackCut(seatId, feed, request, events)
                request
            }
        pacePlayback(request.delayMs, delayMultiplier)
        bridge.prioritySignal.signal()
    }

    private fun installPlaybackCut(
        seatId: SeatId,
        feed: ViewerFeed,
        request: PlaybackCutRequest,
        events: FrameEventLog,
    ) {
        val game =
            bridge.getGame()
                ?: failPlayback(
                    IllegalStateException("Game unavailable"),
                    diagnostic = MaterializationDiagnostic(request, events),
                )
        val prior = bridge.projectionStateSnapshot()
        val planner = LogicalSequencePlanner(prior.sequence)
        val pending =
            try {
                PendingCut(
                    request,
                    feed.builder.materializePlaybackCut(
                        game,
                        planner,
                        PlaybackFrameSpecMaterializer.materialize(bridge, game, seatId, request, events),
                    ),
                )
            } catch (ex: Exception) {
                failPlayback(ex, diagnostic = MaterializationDiagnostic(request, events))
            }
        feed.pendingCut = pending
        val prepared =
            try {
                feed.builder.compilePlaybackCut(pending.projection, viewerRoutes())
            } catch (ex: Exception) {
                failPlayback(ex, pending = pending)
            }
        val cut =
            PreparedCut.prepareForViewers(
                prior,
                planner,
                prepared.viewers.map { output -> PreparedViewerOutput(output.seatId, output.batches) },
                prepared.transition,
                closesPlaybackFrame = true,
                playbackOwnerSeatId = seatId,
            )
        cutInstaller.install(
            cut = cut,
            onInstalled = {
                feed.pendingCut = null
                feed.requestedCut = null
            },
            onFailure = { failPlayback(it, pending = pending) },
        )
    }

    private fun FrameEventLog.openingHandActions(): FrameEventLog {
        val cardIds = events.filterIsInstance<GameEvent.OpeningHandAction>().mapTo(mutableSetOf()) { it.cardId }
        if (cardIds.isEmpty()) return FrameEventLog.EMPTY
        return FrameEventLog(
            events.filter { event ->
                event is GameEvent.OpeningHandAction ||
                    event is GameEvent.ZoneChanged &&
                    event.cardId in cardIds &&
                    event.from == Zone.Hand &&
                    event.to == Zone.Battlefield
            },
            zoneMoves.filter { move ->
                move.cardId in cardIds && move.from == Zone.Hand && move.to == Zone.Battlefield
            },
        )
    }

    fun acknowledgeExternalFrame(seatId: SeatId) {
        synchronized(feedLock) { feeds[seatId]?.requestedCut = null }
    }

    fun drain(
        seatId: SeatId,
        beforeMsgId: Int? = null,
        maxGsId: Int = 0,
    ): List<List<GREToClientMessage>> =
        synchronized(feedLock) {
            val queue = feeds[seatId]?.queue ?: return emptyList()
            buildList {
                while (true) {
                    val batch = queue.firstOrNull() ?: break
                    if (beforeMsgId != null) {
                        val firstMsgId = batch.messages.firstOrNull()?.msgId ?: Int.MAX_VALUE
                        val firstGsId = batch.messages.firstGameStateId()
                        if (maxGsId != 0 && firstGsId != null) {
                            if (firstGsId >= maxGsId) break
                        } else if (firstMsgId >= beforeMsgId) {
                            break
                        }
                    }
                    add(queue.removeFirstOrNull()?.messages ?: break)
                }
            }
        }

    fun hasCommittedBatches(seatId: SeatId): Boolean = synchronized(feedLock) { feeds[seatId]?.queue?.isNotEmpty() == true }

    internal fun signalDelivery() {
        deliverySignal.signal()
    }

    fun failure(): PlaybackTerminalFailure? = terminal.current()

    fun setBeforeBatchEnqueue(
        seatId: SeatId,
        hook: ((Int, List<GREToClientMessage>) -> Unit)?,
    ) {
        synchronized(feedLock) { feed(seatId).beforeBatchEnqueue = hook }
    }

    fun requestedPlaybackCut(seatId: SeatId): PlaybackCutRequest? = synchronized(feedLock) { feeds[seatId]?.requestedCut }

    fun enqueueCommittedBatchForTest(
        seatId: SeatId,
        batch: List<GREToClientMessage>,
    ) {
        synchronized(feedLock) {
            val feed = feed(seatId)
            feed.queue.add(CommittedOutputBatch(TEST_OUTPUT_ORDINAL, 0, batch))
        }
        deliverySignal.signal()
    }

    internal fun feed(seatId: SeatId): ViewerFeed = feeds[seatId] ?: error("No projection feed registered for seat ${seatId.value}")

    internal fun ensureOpen() {
        terminal.ensureOpen()
    }

    internal fun removeOwnedBatch(
        feed: ViewerFeed,
        batch: CommittedOutputBatch,
    ): Boolean = feed.queue.remove(batch)

    internal fun takeOwnedBatch(
        feed: ViewerFeed,
        messages: List<GREToClientMessage>,
    ): CommittedOutputBatch? {
        val batch = feed.queue.firstOrNull { it.messages === messages } ?: return null
        feed.queue.remove(batch)
        return batch
    }

    internal fun removeOwnedBatch(
        feed: ViewerFeed,
        messages: List<GREToClientMessage>,
    ): Boolean = takeOwnedBatch(feed, messages) != null

    internal fun fail(
        cause: Throwable,
        pendingPrompt: PendingPromptCut<*>? = null,
    ): Nothing =
        if (pendingPrompt == null) {
            failTerminal(cause, MatchCutTerminalRuntime.Context())
        } else {
            failPrompt(cause, pendingPrompt)
        }

    internal fun failDelivery(cause: Throwable): Nothing =
        (allPromptRuntimes().firstOrNull { it.hasPendingInteraction() } ?: prompts).failDelivery(cause)

    internal fun failTerminal(
        cause: Throwable,
        context: MatchCutTerminalRuntime.Context,
    ): Nothing = throw terminal.terminate(cause, context)

    private fun failPlayback(
        cause: Throwable,
        pending: PendingCut? = null,
        diagnostic: MaterializationDiagnostic? = null,
    ): Nothing = failTerminal(cause, MatchCutTerminalRuntime.Context(pending = pending, diagnostic = diagnostic))

    internal fun retainPendingCut(pending: PendingCut) {
        feeds.values.firstOrNull { it.pendingCut === pending }?.pendingCut = pending
    }

    private fun List<GREToClientMessage>.firstGameStateId(): Int? = firstOrNull { it.hasGameStateMessage() }?.gameStateMessage?.gameStateId

    private companion object {
        const val TEST_OUTPUT_ORDINAL = -1L
    }
}
