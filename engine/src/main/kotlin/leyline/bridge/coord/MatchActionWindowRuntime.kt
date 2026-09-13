package leyline.bridge.coord

import leyline.bridge.handoff.DeclarationAnswer
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.mapping.ProjectionSupplement
import leyline.game.mapping.ViewerProjectionIntent
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionViewerRole
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ResultCode
import wotc.mtgo.gre.external.messaging.Messages.SubmitAttackersResp
import wotc.mtgo.gre.external.messaging.Messages.SubmitBlockersResp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/** Runtime action catalogs and exact presentation ownership beneath [MatchCutCoordinator]. */
@Suppress("LargeClass") // Action-window publication and response lifecycle share one owner.
internal class MatchActionWindowRuntime(
    private val owner: MatchCutCoordinator,
) : DeferredCastActionOwner {
    internal data class ReconnectHorizon(
        val actionId: String,
        val decisionMessage: GREToClientMessage,
        val publishedBatch: List<GREToClientMessage>,
    )

    // Written under the coordinator feed lock; read lock-free by the engine wait
    // adapter and session threads asking whether a window is still open.
    private val actionWindows = ConcurrentHashMap<String, RuntimeActionWindow>()
    private var nextActionToken = 1L

    internal var beforeEnqueue: (() -> Unit)? = null
    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
    internal var beforeCatalogInstall: (() -> Unit)? = null
    internal var beforePublished: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var beforeSynchronizationTimeoutClaim: (() -> Unit)? = null

    @ConsistentCopyVisibility
    data class ActionClaim internal constructor(
        val actionId: String,
        internal val token: Long,
        internal val kind: ActionClaimKind,
        val deferredCostPlan: leyline.bridge.handoff.DeferredCastCostPlan?,
    )

    @ConsistentCopyVisibility
    data class PriorityResponseClaim internal constructor(
        val cardId: ForgeCardId?,
        val actionClaim: ActionClaim,
    )

    fun bridge(seatId: SeatId): GameActionBridge.ActionWindowRuntime = CoordinatorActionWindowBridge(this, seatId)

    override fun isDeferredClaim(claim: ActionClaim): Boolean =
        actionWindows[claim.actionId]?.status == ActionWindowStatus.Claimed(ActionClaimKind.Deferred, claim.token)

    override fun seatFor(actionId: String): SeatId = checkNotNull(actionWindows[actionId]?.seatId)

    override fun completeDeferredClaim(
        claim: ActionClaim,
        childToken: Long?,
    ): Boolean = complete(claim, childToken)

    override fun reopenDeferredClaim(claim: ActionClaim): Boolean = reopenClaim(claim)

    /**
     * The engine and client see a window only while it is
     * [ActionWindowStatus.Published]. Read without the feed lock: the engine wait
     * adapter polls it from threads that must not block behind a publication.
     */
    internal fun isVisible(actionId: String): Boolean = actionWindows[actionId]?.status == ActionWindowStatus.Published

    internal fun promptGameStateId(actionId: String): Int? = actionWindows[actionId]?.promptGameStateId

    @org.jetbrains.annotations.VisibleForTesting
    internal fun hideForTest(actionId: String) {
        synchronized(owner.feedLock) { actionWindows[actionId]?.status = ActionWindowStatus.Publishing }
    }

    /** Record a published state-only synchronization stop; it has no action catalog. */
    internal fun markSynchronizationPublished(
        seatId: SeatId,
        actionId: String,
        publishedBatch: List<GREToClientMessage>,
    ) {
        actionWindows[actionId] = synchronizationActionWindow(seatId, actionId).copy(publishedBatch = publishedBatch)
    }

    internal fun synchronizationBatch(actionId: String): List<GREToClientMessage> {
        val window = actionWindows[actionId] ?: error("No synchronization window $actionId")
        check(window.status == ActionWindowStatus.Published && window.publishedBatch.isNotEmpty()) {
            "Synchronization window $actionId is no longer pending"
        }
        check(
            owner
                .feed(window.seatId)
                .queue
                .lastOrNull()
                ?.messages === window.publishedBatch,
        ) {
            "Synchronization window $actionId is already visible"
        }
        return window.publishedBatch
    }

    internal fun claimTimeout(
        pending: GameActionBridge.PendingAction,
        cause: TimeoutException,
    ): Boolean {
        beforeTimeoutClaim?.invoke()
        return synchronized(owner.feedLock) {
            val window = actionWindows[pending.actionId]
            if (window?.status != ActionWindowStatus.Published) return@synchronized false
            val claimed = pending.future.completeExceptionally(cause)
            if (claimed) {
                actionWindows.remove(pending.actionId)?.selections?.clear()
            }
            claimed
        }
    }

    internal fun claimSynchronizationTimeout(
        pending: GameActionBridge.PendingAction,
        cause: TimeoutException,
    ): Boolean {
        beforeSynchronizationTimeoutClaim?.invoke()
        return synchronized(owner.feedLock) {
            if (!pending.future.completeExceptionally(cause)) return@synchronized false
            owner.fail(cause)
        }
    }

    internal fun completeSynchronization(pending: GameActionBridge.PendingAction): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            pending.future.complete(GameActionBridge.ActionSubmission.RuntimeToken(GameActionBridge.ENGINE_PASS_TOKEN))
        }

    @org.jetbrains.annotations.VisibleForTesting
    internal fun actionOffersForTest(actionId: String): List<GameActionBridge.ActionOffer> =
        synchronized(owner.feedLock) {
            actionWindows[actionId]
                ?.offers
                ?.values
                ?.flatten()
                ?.map { it.second }
                .orEmpty()
        }

    internal fun reconnectHorizon(seatId: SeatId): ReconnectHorizon? =
        synchronized(owner.feedLock) {
            actionWindows.entries
                .singleOrNull { (_, window) -> window.seatId == seatId && window.status == ActionWindowStatus.Published }
                ?.let { (actionId, window) ->
                    window.publishedBatch
                        .singleOrNull {
                            it.hasActionsAvailableReq() || it.hasDeclareAttackersReq() || it.hasDeclareBlockersReq()
                        }?.let { ReconnectHorizon(actionId, it, window.publishedBatch) }
                }
        }

    internal fun bindReconnectHorizon(
        horizon: ReconnectHorizon,
        gameStateId: Int,
        publishedBatch: List<GREToClientMessage>,
    ) {
        synchronized(owner.feedLock) {
            val window = actionWindows[horizon.actionId] ?: error("No action window ${horizon.actionId}")
            check(window.status == ActionWindowStatus.Published) { "Action window ${horizon.actionId} is no longer pending" }
            actionWindows[horizon.actionId] = window.copy(promptGameStateId = gameStateId, publishedBatch = publishedBatch)
        }
    }

    fun legalAttackerIds(actionId: String): List<Int> = synchronized(owner.feedLock) { actionWindows[actionId]?.legalAttackerIds.orEmpty() }

    fun hasLegalAttackers(actionId: String): Boolean =
        synchronized(owner.feedLock) {
            actionWindows[actionId]?.combat?.hasLegalAttackers() == true
        }

    fun legalBlockerCount(actionId: String): Int = synchronized(owner.feedLock) { actionWindows[actionId]?.legalBlockerCount ?: 0 }

    fun complete(
        claim: ActionClaim,
        childToken: Long? = null,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            completeDeferredLocked(claim, childToken)
        }

    private fun completeDeferredLocked(
        claim: ActionClaim,
        childToken: Long?,
    ): Boolean {
        val window = actionWindows[claim.actionId] ?: return false
        if (window.status != ActionWindowStatus.Claimed(claim.kind, claim.token)) return false
        val completionToken = childToken ?: claim.token
        if (childToken != null) {
            val selection = window.deferredChildSelections[childToken] ?: return false
            window.selections[childToken] = selection
        }
        val completed = owner.bridge.actionBridge(window.seatId).submitRuntimeToken(claim.actionId, completionToken)
        if (completed) window.status = ActionWindowStatus.Completed
        return completed
    }

    @Suppress("UNUSED_PARAMETER")
    fun failClaim(
        claim: ActionClaim,
        cause: Throwable,
    ): Nothing =
        synchronized(owner.feedLock) {
            owner.fail(cause)
        }

    fun reopenClaim(claim: ActionClaim): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val window = actionWindows[claim.actionId] ?: return false
            if (window.status != ActionWindowStatus.Claimed(claim.kind, claim.token)) return false
            owner.bridge.actionBridge(window.seatId).exactPending(claim.actionId) ?: return false
            val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
            val feed = owner.feed(window.seatId)
            val prior = owner.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            val prepared =
                try {
                    feed.builder.preparePhaseTransitionDiff(game, planner, window.actions)
                } catch (ex: Exception) {
                    owner.fail(ex)
                }
            publishPresentation(feed, window, prepared, prior, planner)
            val reopened = checkNotNull(actionWindows[claim.actionId])
            reopened.selections.clear()
            reopened.status = ActionWindowStatus.Published
            owner.bridge.priorityPolicy(window.seatId).actionCompleted(false)
            true
        }

    fun updateDeclaration(
        actionId: String,
        responseGameStateId: Int,
        answer: DeclarationAnswer,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val window = actionWindows[actionId] ?: return false
            val pending = owner.bridge.actionBridge(window.seatId).getPending() ?: return false
            if (pending.actionId != actionId ||
                window.status != ActionWindowStatus.Published ||
                responseGameStateId != window.promptGameStateId
            ) {
                return false
            }
            val combat = window.combat ?: return false
            val prior = owner.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            when (pending.state.kind) {
                PendingActionKind.DECLARE_ATTACKERS -> {
                    val attackers = answer as? DeclarationAnswer.Attackers ?: return false
                    val next = combat.nextAttackers(attackers) ?: return false
                    combat.replaceAttackers(next)
                    check(publishDeclarationPresentation(window, PendingActionKind.DECLARE_ATTACKERS, prior, planner))
                }
                PendingActionKind.DECLARE_BLOCKERS -> {
                    val blockers = answer as? DeclarationAnswer.Blockers ?: return false
                    val next = combat.nextBlockers(blockers) ?: return false
                    combat.replaceBlockers(next)
                    check(publishDeclarationPresentation(window, PendingActionKind.DECLARE_BLOCKERS, prior, planner))
                }
                PendingActionKind.PRIORITY,
                PendingActionKind.SYNC_ONLY,
                -> return false
            }
            true
        }

    fun republishDeclaration(actionId: String): Boolean =
        synchronized(owner.feedLock) {
            val window = actionWindows[actionId] ?: return false
            val pending = owner.bridge.actionBridge(window.seatId).getPending() ?: return false
            if (pending.actionId != actionId ||
                pending.state.kind !in setOf(PendingActionKind.DECLARE_ATTACKERS, PendingActionKind.DECLARE_BLOCKERS) ||
                window.status != ActionWindowStatus.Published ||
                window.combat == null
            ) {
                return false
            }
            val prior = owner.bridge.projectionStateSnapshot()
            publishDeclarationPresentation(window, pending.state.kind, prior, LogicalSequencePlanner(prior.sequence))
        }

    private fun publishDeclarationPresentation(
        window: RuntimeActionWindow,
        kind: PendingActionKind,
        prior: ProjectionState,
        planner: LogicalSequencePlanner,
    ): Boolean {
        val combat = window.combat ?: return false
        val feed = owner.feed(window.seatId)
        val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
        val prepared =
            try {
                when (kind) {
                    PendingActionKind.DECLARE_ATTACKERS ->
                        feed.builder.prepareEchoAttackers(
                            game,
                            planner,
                            combat.selectedAttackerInstanceIds(),
                            window.legalAttackerIds,
                            combat.selectedAttackAlternatives(),
                            combat.selectedDamageRecipients(),
                            window.presentationActions,
                        )
                    PendingActionKind.DECLARE_BLOCKERS ->
                        feed.builder.prepareEchoBlockers(game, planner, combat.selectedBlockAssignments(), window.presentationActions)
                    PendingActionKind.PRIORITY,
                    PendingActionKind.SYNC_ONLY,
                    -> return false
                }
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        publishPresentation(feed, window, prepared, prior, planner)
        return true
    }

    fun bindInitial(
        actionId: String,
        gameStateId: Int,
    ): ActionsAvailableReq =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val window = actionWindows[actionId] ?: error("No action window $actionId")
            val feed = owner.feed(window.seatId)
            if (window.publishedBatch.isNotEmpty()) {
                check(owner.removeOwnedBatch(feed, window.publishedBatch)) { "Action window $actionId is already visible" }
            } else {
                check(window.status == ActionWindowStatus.Published) { "Action window $actionId is no longer pending" }
                check(owner.bridge.actionBridge(window.seatId).exactPending(actionId) != null) {
                    "Action window $actionId is no longer pending"
                }
            }
            actionWindows[actionId] = window.copy(promptGameStateId = gameStateId, publishedBatch = emptyList())
            window.actions
        }

    fun replaceWithPhaseTransition(
        actionId: String,
        includePriorityPrompt: Boolean = true,
    ): List<GREToClientMessage> =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val window = actionWindows[actionId] ?: error("No action window $actionId")
            val feed = owner.feed(window.seatId)
            check(feed.queue.any { it.messages === window.publishedBatch }) { "Action window $actionId is already visible" }
            val game = owner.bridge.getGame() ?: error("Game unavailable")
            val prior = owner.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            val prepared =
                try {
                    feed.builder.preparePhaseTransitionDiff(
                        game,
                        planner,
                        window.actions,
                        includePriorityPrompt,
                    )
                } catch (ex: Exception) {
                    owner.fail(ex)
                }
            publishPresentation(feed, window, prepared, prior, planner, replaces = window.publishedBatch)
        }

    fun claimPriority(
        actionId: String,
        responseGameStateId: Int,
        response: Action,
        defer: Boolean,
    ): PriorityResponseClaim? =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val window = actionWindows[actionId] ?: return null
            val pending = owner.bridge.actionBridge(window.seatId).getPending() ?: return null
            if (pending.actionId != actionId || pending.future.isDone || window.status != ActionWindowStatus.Published) return null
            val (token, offer) = window.resolveOfferedSelection(responseGameStateId, response) ?: return null
            window.selections[token] = RuntimeActionSelection(offer, response)
            val kind = if (defer) ActionClaimKind.Deferred else ActionClaimKind.Immediate
            window.status = ActionWindowStatus.Claimed(kind, token)
            if (owner.bridge.engineSettings.timer) {
                val prior = owner.bridge.projectionStateSnapshot()
                val planner = LogicalSequencePlanner(prior.sequence)
                val timer = owner.feed(window.seatId).builder.timerStop(planner)
                owner.cutInstaller.install(
                    owner.feed(window.seatId),
                    PreparedCut.prepare(prior, planner, timer.messages, projection = null, closesPlaybackFrame = false),
                    onFailure = owner::fail,
                )
            }
            PriorityResponseClaim(offer.sourceCardId(), ActionClaim(actionId, token, kind, window.deferredCostPlans[token]))
        }

    fun submitDeclaration(
        actionId: String,
        responseGameStateId: Int,
    ): Boolean =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val window = actionWindows[actionId] ?: return false
            val pending = owner.bridge.actionBridge(window.seatId).getPending() ?: return false
            if (pending.actionId != actionId ||
                pending.state.kind !in setOf(PendingActionKind.DECLARE_ATTACKERS, PendingActionKind.DECLARE_BLOCKERS) ||
                window.status != ActionWindowStatus.Published ||
                responseGameStateId != window.promptGameStateId
            ) {
                return false
            }
            val action = window.combat?.resolveDeclaration(pending.state.kind) ?: return false
            val token = nextActionToken++
            val prior = owner.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            val confirmation = declarationConfirmation(window.seatId, pending.state.kind, planner)
            owner.cutInstaller.install(
                feed = owner.feed(window.seatId),
                cut = PreparedCut.prepare(prior, planner, listOf(confirmation), projection = null, closesPlaybackFrame = false),
                hooks = CutInstallHooks(beforeEnqueue = beforeEnqueue, beforeInstall = beforeInstall, afterInstall = afterInstall),
                onInstalled = {
                    window.status = ActionWindowStatus.Claimed(ActionClaimKind.Immediate, token)
                    window.selections[token] =
                        RuntimeActionSelection(
                            GameActionBridge.ActionOffer(Action.getDefaultInstance(), action),
                            Action.getDefaultInstance(),
                        )
                },
            ) { ex ->
                nextActionToken = token
                owner.fail(ex)
            }
            val completed = owner.bridge.actionBridge(window.seatId).submitRuntimeToken(actionId, token)
            if (completed) window.status = ActionWindowStatus.Completed
            completed
        }

    private fun declarationConfirmation(
        seatId: SeatId,
        kind: PendingActionKind,
        planner: LogicalSequencePlanner,
    ): GREToClientMessage {
        val builder =
            GREToClientMessage
                .newBuilder()
                .addSystemSeatIds(seatId.value)
                .setMsgId(planner.nextMsgId())
                .setGameStateId(planner.currentGsId())
        when (kind) {
            PendingActionKind.DECLARE_ATTACKERS ->
                builder
                    .setType(GREMessageType.SubmitAttackersResp_695e)
                    .setSubmitAttackersResp(SubmitAttackersResp.newBuilder().setResult(ResultCode.Success_a500))
            PendingActionKind.DECLARE_BLOCKERS ->
                builder
                    .setType(GREMessageType.SubmitBlockersResp_695e)
                    .setSubmitBlockersResp(SubmitBlockersResp.newBuilder().setResult(ResultCode.Success_a500))
            PendingActionKind.PRIORITY,
            PendingActionKind.SYNC_ONLY,
            -> error("Unsupported declaration kind $kind")
        }
        return builder.build()
    }

    fun terminate() {
        synchronized(owner.feedLock) {
            actionWindows.values.forEach { it.selections.clear() }
            actionWindows.clear()
        }
    }

    fun reset() {
        terminate()
        nextActionToken = 1L
    }

    internal fun publish(
        seatId: SeatId,
        pending: GameActionBridge.PendingAction,
    ) {
        owner.bridge
            .priorityPolicy(seatId)
            .takeChangedSettings()
            ?.let { owner.publishSettings(seatId, it) }
        if (pending.state.kind == PendingActionKind.SYNC_ONLY) {
            owner.syncOnly.publish(seatId, pending)
            return
        }
        owner.beforePublicationLock?.invoke()
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
            val feed = owner.feed(seatId)
            val routes = owner.viewerRoutes(seatId)
            val playerRoute = routes.single { it.viewer.role == ProjectionViewerRole.Player }
            val playerSeatId = playerRoute.viewer.seatId
            val tokenBefore = nextActionToken
            val prior = owner.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            val intent = initialActionIntent(pending.state.kind, prior, playerSeatId)
            val prepared =
                try {
                    playerRoute.builder.prepareInitialActionWindow(
                        game = game,
                        counter = planner,
                        routes = routes,
                        kind = pending.state.kind,
                        priorityCandidates = pending.priorityCandidates,
                        intent = intent,
                    )
                } catch (ex: Exception) {
                    owner.fail(ex)
                }
            val actionPrepared = prepared.player
            val result = actionPrepared.bundle
            val messages =
                try {
                    if (pending.state.kind == PendingActionKind.PRIORITY && owner.bridge.engineSettings.timer) {
                        result.messages + playerRoute.builder.timerStart(planner).messages
                    } else {
                        result.messages
                    }
                } catch (ex: Exception) {
                    owner.fail(ex)
                }
            val promptGsId = result.actionGameStateId ?: result.messages.firstOrNull()?.gameStateId ?: planner.currentGsId()
            if (hasAmbiguousActionCatalog(result.actionOffers)) {
                owner.fail(IllegalStateException("Ambiguous action offer catalog"))
            }
            val created =
                try {
                    createRuntimeActionWindow(
                        seatId,
                        pending,
                        actionPrepared,
                        messages,
                        promptGsId,
                        nextToken = { nextActionToken++ },
                        materializeDeferredCost = { _, offer ->
                            DeferredCastCostPlanMaterializer.materialize(owner.bridge, offer) { nextActionToken++ }
                        },
                    ).copy(combat = RuntimeCombatWindow.capture(owner, game, messages))
                } catch (ex: Exception) {
                    nextActionToken = tokenBefore
                    owner.fail(ex)
                }
            try {
                beforeCatalogInstall?.invoke()
            } catch (ex: Exception) {
                nextActionToken = tokenBefore
                owner.fail(ex)
            }
            val cut =
                try {
                    val outputs = viewerOutputs(prepared, playerSeatId, messages)
                    PreparedCut.prepareForViewers(
                        prior,
                        planner,
                        outputs,
                        actionPrepared.transition,
                        actionPrepared.closesPlaybackFrame,
                        playbackOwnerSeatId = seatId,
                    )
                } catch (ex: Exception) {
                    nextActionToken = tokenBefore
                    owner.fail(ex)
                }
            owner.cutInstaller.install(
                cut = cut,
                CutInstallHooks(beforeEnqueue = beforeEnqueue, beforeInstall = beforeInstall, afterInstall = afterInstall),
                onRollback = { nextActionToken = tokenBefore },
            ) { ex -> owner.fail(ex) }
            actionWindows.remove(pending.actionId)?.selections?.clear()
            actionWindows[pending.actionId] = created
            feed.requestedCut = null
            beforePublished?.invoke()
            created.status = ActionWindowStatus.Published
        }
    }

    private fun initialActionIntent(
        kind: PendingActionKind,
        prior: ProjectionState,
        playerSeatId: SeatId,
    ): ViewerProjectionIntent =
        if (kind != PendingActionKind.PRIORITY) {
            ViewerProjectionIntent.EMPTY
        } else {
            prior.viewerCursors[playerSeatId]
                ?.pendingSubmittedTargets
                ?.let {
                    ViewerProjectionIntent.of(
                        listOf(ProjectionSupplement.SubmitPendingTargets(it.spellInstanceId, it.casterSeatId, it.version)),
                    )
                } ?: ViewerProjectionIntent.EMPTY
        }

    private fun viewerOutputs(
        prepared: BundleBuilder.PreparedViewerCut<BundleBuilder.ActionWindowPrepared>,
        playerSeatId: SeatId,
        messages: List<GREToClientMessage>,
    ): List<PreparedViewerOutput> =
        prepared.viewers.map { output ->
            val batches =
                if (output.seatId == playerSeatId) {
                    listOf(messages)
                } else {
                    output.batches
                }
            PreparedViewerOutput(output.seatId, batches)
        }

    private fun publishPresentation(
        feed: MatchCutCoordinator.ViewerFeed,
        window: RuntimeActionWindow,
        prepared: BundleBuilder.ActionWindowPrepared,
        prior: ProjectionState,
        planner: LogicalSequencePlanner,
        replaces: List<GREToClientMessage> = emptyList(),
    ): List<GREToClientMessage> {
        val messages = prepared.bundle.messages
        val promptGsId = checkNotNull(prepared.bundle.actionGameStateId)
        owner.cutInstaller.install(
            feed,
            PreparedCut.prepare(prior, planner, messages, prepared.transition, prepared.closesPlaybackFrame),
            CutInstallHooks(
                beforeEnqueue = beforeEnqueue,
                beforeInstall = beforeInstall,
                afterInstall = afterInstall,
            ),
            replaces = replaces,
        ) { ex -> owner.fail(ex) }
        actionWindows[window.actionId] = window.copy(promptGameStateId = promptGsId, publishedBatch = messages)
        return messages
    }

    internal fun resolve(
        pending: GameActionBridge.PendingAction,
        submission: GameActionBridge.ActionSubmission.RuntimeToken,
    ): PlayerAction? =
        synchronized(owner.feedLock) {
            if (submission.token == GameActionBridge.ENGINE_PASS_TOKEN) return PlayerAction.PassPriority
            val selection = actionWindows[pending.actionId]?.selections?.remove(submission.token) ?: return null
            val command = selection.offer.command
            if (command is PlayerAction.CastSpell) owner.bridge.setSelectedSpellGrpId(command.cardId, selection.offer.spellGrpId)
            if (command is PlayerAction.ActivateMana) command.copy(selectedColor = selectedManaColor(selection.response)) else command
        }

    internal fun close(actionId: String) {
        synchronized(owner.feedLock) {
            owner.deferredCast.close(actionId)
            actionWindows.remove(actionId)?.selections?.clear()
        }
    }
}
