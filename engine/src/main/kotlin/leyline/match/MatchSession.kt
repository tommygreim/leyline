package leyline.match

import leyline.bridge.coord.SettledPromptAdmission
import leyline.bridge.types.SeatId
import leyline.copilot.CopilotProposal
import leyline.copilot.CopilotProposalService
import leyline.game.bundle.PROMPT_GRE_TYPES
import leyline.game.state.GameBridge
import leyline.infra.MessageSink
import leyline.protocol.HandshakeMessages
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Game orchestration session — thin dispatcher for post-mulligan game logic.
 *
 * Delegates combat flows to [CombatHandler] and targeting to [TargetingHandler].
 * Owns the [sessionLock], message sending, and committed-feed delivery.
 *
 * Transport-agnostic: sends messages through [MessageSink].
 * [MatchHandler] creates one per connection and delegates GRE messages here.
 */
class MatchSession(
    val connection: ConnectionState,
    override val gameBridge: GameBridge,
    val paceDelayMs: Long = 200L,
) : GameOps {
    data class PuzzleReplacementResult(
        val gameStateId: Int,
        val objectCount: Int,
        val zoneCount: Int,
    )

    private val log = LoggerFactory.getLogger(MatchSession::class.java)

    override val seatId: SeatId get() = connection.seatId
    override val matchId: String get() = connection.matchId
    val sink: MessageSink get() = connection.sink
    val registry: MatchRegistry get() = connection.registry

    /** Client player ID — delegate; mutable on connection. */
    var playerId: String
        get() = connection.playerId
        set(value) {
            connection.playerId = value
        }

    private val sessionLock get() = connection.sessionLock

    @Volatile
    private var lastPrompt: GREToClientMessage? = null
    private var terminalCompleted = false

    fun lastPromptMessage(): GREToClientMessage? = lastPrompt

    private val copilotProposalService by lazy { CopilotProposalService(gameBridge, seatId) }

    private val autopush: leyline.copilot.CopilotAutopush? by lazy {
        val dev = gameBridge.engineSettings.dev
        if (dev.copilotAutopush) {
            leyline.copilot.CopilotAutopush(gameBridge, seatId, dev.copilotBridgeUrl, service = copilotProposalService)
        } else {
            null
        }
    }

    private val runtimeContinuation = MatchRuntimeContinuation(this, gameBridge, seatId, matchId)

    /**
     * Game + bridge bound at construction. MatchSession is per-game; on
     * puzzle hot-swap MatchHandler builds a fresh instance for the new
     * game, so this snapshot stays valid for the session's lifetime.
     */
    val ctx: SessionContext =
        SessionContext(requireNotNull(gameBridge.getGame()) { "MatchSession requires non-null game" }, gameBridge, seatId)

    /** Sub-handlers for combat, targeting, and routed interaction flows. */
    val combatHandler =
        CombatHandler(
            sink = this,
            counters = this,
            ctx = ctx,
        )
    val targetingHandler =
        TargetingHandler(
            sink = this,
            counters = this,
            ctx = ctx,
            matchId = matchId,
        )
    val optionalActionHandler =
        OptionalActionHandler(
            ctx = ctx,
        )
    val numericInputHandler =
        NumericInputHandler(
            ctx = ctx,
        )
    internal val actionPerformer =
        ActionPerformer(
            sink = this,
            counters = this,
            targetingHandler = targetingHandler,
            priorityPolicy = gameBridge.priorityPolicy(seatId),
            ctx = ctx,
            continuation = runtimeContinuation,
            matchId = matchId,
        )

    // --- Public entry points (called by MatchHandler) ---

    /** Consult the advisor for this session's exact pending prompt without submitting a response. */
    fun copilotProposal(): CopilotProposal =
        synchronized(sessionLock) {
            val prompt =
                lastPrompt
                    ?: return@synchronized unavailableCopilotProposal("match has no pending prompt", seat = seatId.value)
            if (!isOutstandingCopilotPrompt(prompt)) {
                return@synchronized unavailableCopilotProposal(
                    "stale prompt: pending prompt is no longer outstanding",
                    prompt,
                    seatId.value,
                )
            }
            val proposal = copilotProposalService.propose(prompt)
            if (!isOutstandingCopilotPrompt(prompt)) {
                unavailableCopilotProposal(
                    "stale prompt: pending prompt changed during copilot consultation",
                    prompt,
                    seatId.value,
                )
            } else {
                proposal
            }
        }

    private fun isOutstandingCopilotPrompt(prompt: GREToClientMessage): Boolean {
        val current = lastPrompt
        val sequence = gameBridge.committedSequence()
        return current?.gameStateId == prompt.gameStateId &&
            current.msgId == prompt.msgId &&
            current.type == prompt.type &&
            sequence.lastPromptGsId == prompt.gameStateId &&
            sequence.lastPromptMsgId == prompt.msgId &&
            gameBridge.responseAcceptance.hasOutstandingPrompt(prompt.msgId)
    }

    /**
     * After keep: bind the first client-owned horizon and arm autonomous delivery.
     */
    override fun onMulliganKeep() =
        synchronized(sessionLock) {
            val bridge = gameBridge
            log.info("MatchSession: waiting for engine to reach priority after keep")

            bridge.awaitPriority()
            drainCoordinatorFeed()

            runtimeContinuation.awaitClientVisibleHorizon()
            registry.getConnection(matchId, seatId)?.armRuntimeDeliveryObserver()
            Unit
        }

    /**
     * Replace this session with a fresh one bound to a hot-swapped puzzle game.
     *
     * The connection (sink, identity, settings, sessionLock) and
     * the bridge instance survive — only the `Game` inside the bridge changes,
     * and a new MatchSession is built with handlers ctx-bound to the new game.
     *
     * Held under [connection.sessionLock] so concurrent inbound messages can't
     * interleave with the swap.
     *
     * The replacement session publishes its initial lifecycle batch before this
     * method releases the connection lock.
     */
    fun replaceForPuzzle(puzzle: forge.gamemodes.puzzle.Puzzle): PuzzleReplacementResult =
        synchronized(sessionLock) {
            val matchConnection = registry.getConnection(matchId, seatId)
            matchConnection?.stopRuntimeDeliveryObserver()
            close()
            val deletedIds = gameBridge.resetForPuzzle(puzzle)
            val replacement = MatchSession(connection, gameBridge, paceDelayMs)
            registry.registerSession(matchId, seatId, replacement)
            // Update the per-channel handler so future inbound GRE messages dispatch
            // to the new session. Without this, MatchHandler keeps a stale reference
            // and the next PerformActionResp builds a Diff against unrelated game
            // state, producing spurious diffDeletedInstanceIds.
            matchConnection?.session = replacement
            replacement.publishPuzzleReplacement(deletedIds).also {
                matchConnection?.armRuntimeDeliveryObserver()
            }
        }

    /** Commit and deliver the replacement puzzle's initial state and action horizon. */
    private fun publishPuzzleReplacement(deletedInstanceIds: List<Int>): PuzzleReplacementResult {
        gameBridge.awaitPriority()
        val pending = checkNotNull(gameBridge.seat(seatId).action.getPending()) { "Puzzle replacement has no pending priority window" }
        val published = gameBridge.cutCoordinator.lifecycle.publishPuzzleReplacement(seatId, deletedInstanceIds, pending.actionId)
        drainCoordinatorBarrier(this, gameBridge, seatId)
        return PuzzleReplacementResult(published.gameStateId, published.objectCount, published.zoneCount)
    }

    fun injectFullState(): PuzzleReplacementResult =
        synchronized(sessionLock) {
            val published = gameBridge.cutCoordinator.lifecycle.publishFullState(seatId)
            drainCoordinatorFeed()
            PuzzleReplacementResult(published.gameStateId, published.objectCount, published.zoneCount)
        }

    override fun onPuzzleStart() =
        synchronized(sessionLock) {
            if (!preparePuzzleStart()) return@synchronized

            runtimeContinuation.awaitClientVisibleHorizon()
        }

    internal fun preparePuzzleStart(): Boolean {
        // FamiliarSession inherits a no-op onPuzzleStart from SessionOps, so this
        // path only fires for MatchSession. Warn if somehow called for a non-human
        // MatchSession — it would consume the human seat's pending priority via the
        // shared ActionBridge, advancing the engine past Main1.
        val humanSeat = gameBridge.seating.humanSeat
        if (seatId != humanSeat) {
            log.warn("MatchSession: onPuzzleStart called for seat {} — expected humanSeat {}", seatId.value, humanSeat.value)
            return false
        }

        log.info("MatchSession: puzzle start, seeding snapshot and entering game loop")

        return true
    }

    /**
     * Handle a client action (land play, spell cast, pass) and advance the engine.
     * Delegates to [ActionPerformer] — this method is just the session-lock boundary
     * and context resolver.
     */
    override fun onPerformAction(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) { completedActionId ->
            actionPerformer.perform(greMsg, completedActionId)
        }

    /** Handle DeclareAttackersResp — delegates to [CombatHandler]. */
    override fun onDeclareAttackers(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) { completedActionId ->
            if (combatHandler.onDeclareAttackers(greMsg)) runtimeContinuation.awaitHorizon(completedActionId)
        }

    /** Handle DeclareBlockersResp — delegates to [CombatHandler]. */
    override fun onDeclareBlockers(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) { completedActionId ->
            if (combatHandler.onDeclareBlockers(greMsg)) runtimeContinuation.awaitHorizon(completedActionId)
        }

    /** Handle AssignDamageResp — delegates to [CombatHandler]. */
    override fun onAssignDamage(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) { completedActionId ->
            if (combatHandler.onAssignDamage(greMsg)) runtimeContinuation.awaitHorizon(completedActionId)
        }

    /** Handle OptionalActionResp — delegates to [OptionalActionHandler]. */
    override fun onOptionalActionResp(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) { completedActionId ->
            if (optionalActionHandler.onOptionalActionResp(greMsg)) runtimeContinuation.awaitHorizon(completedActionId)
        }

    /** Handle NumericInputResp — delegates to [NumericInputHandler]. */
    override fun onNumericInputResp(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) { completedActionId ->
            if (numericInputHandler.onNumericInputResp(greMsg)) runtimeContinuation.awaitHorizon(completedActionId)
        }

    /** Handle SelectTargetsResp — delegates to [TargetingHandler]. */
    override fun onSelectTargets(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) { completedActionId ->
            awaitHandlerResult(targetingHandler.onSelectTargets(greMsg), completedActionId)
        }

    /** Handle SubmitTargetsReq — finalizes two-phase targeting. */
    override fun onSubmitTargets(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) { completedActionId ->
            awaitHandlerResult(targetingHandler.onSubmitTargets(greMsg), completedActionId)
        }

    override fun onEffectCost(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) { completedActionId ->
            awaitHandlerResult(targetingHandler.onEffectCost(greMsg), completedActionId)
        }

    /** Handle CastingTimeOptionsResp — delegates to [TargetingHandler]. */
    override fun onCastingTimeOptions(greMsg: ClientToGREMessage) =
        withValidResponse(greMsg) { completedActionId ->
            awaitHandlerResult(targetingHandler.onCastingTimeOptions(greMsg), completedActionId)
        }

    internal fun admitSettled(greMsg: ClientToGREMessage): SettledPromptAdmission =
        synchronized(sessionLock) {
            val completedActionId = gameBridge.actionBridge(seatId).getPending()?.actionId
            gameBridge.cutCoordinator.promptRuntimes(seatId).settled.admit(greMsg).also {
                when (it) {
                    is SettledPromptAdmission.Accepted ->
                        runtimeContinuation.awaitHorizon(completedActionId, it.afterEngineResume)
                    is SettledPromptAdmission.Rejected -> {
                        log
                            .atDebug()
                            .addKeyValue("event", "match.response_rejected")
                            .addKeyValue("match_id", matchId)
                            .addKeyValue("seat", seatId.value)
                            .addKeyValue("response_type", greMsg.type.name)
                            .addKeyValue("game_state_id", greMsg.gameStateId)
                            .addKeyValue("reason", it.reason.name)
                            .log("Client response rejected")
                        gameBridge.cutCoordinator.publishIllegalRequest(seatId, greMsg, it.reason)
                        drainCoordinatorFeed()
                    }
                    SettledPromptAdmission.NotOwned -> Unit
                }
            }
        }

    private fun withValidResponse(
        greMsg: ClientToGREMessage,
        block: (completedActionId: String?) -> Unit,
    ): Unit =
        synchronized(sessionLock) {
            val wrongSeat = greMsg.systemSeatId != 0 && greMsg.systemSeatId != seatId.value
            val wrongPrompt =
                gameBridge.humanVsHuman && greMsg.type in CORRELATED_CLIENT_MESSAGE_TYPES && lastPrompt?.msgId != greMsg.respId
            val failure =
                if (wrongSeat || wrongPrompt) {
                    FailureReason.ReqRespMismatch
                } else {
                    ResponseEnvelopeGuard.mismatchReason(greMsg, gameBridge.committedSequence(), gameBridge.responseAcceptance)
                }
            if (failure == null) {
                log
                    .atDebug()
                    .addKeyValue("event", "match.response_accepted")
                    .addKeyValue("match_id", matchId)
                    .addKeyValue("seat", seatId.value)
                    .addKeyValue("response_type", greMsg.type.name)
                    .addKeyValue("game_state_id", greMsg.gameStateId)
                    .log("Client response accepted")
                block(gameBridge.actionBridge(seatId).getPending()?.actionId)
            } else {
                log
                    .atDebug()
                    .addKeyValue("event", "match.response_rejected")
                    .addKeyValue("match_id", matchId)
                    .addKeyValue("seat", seatId.value)
                    .addKeyValue("response_type", greMsg.type.name)
                    .addKeyValue("game_state_id", greMsg.gameStateId)
                    .addKeyValue("reason", failure.name)
                    .log("Client response rejected")
                gameBridge.cutCoordinator.publishIllegalRequest(seatId, greMsg, failure)
                drainCoordinatorFeed()
            }
        }

    private fun awaitHandlerResult(
        result: HandlerResult,
        completedActionId: String?,
    ) {
        if (result.resumes) runtimeContinuation.awaitHorizon(completedActionId)
    }

    /**
     * Handle CancelActionReq — player cancelled targeting (backed out of spell cast).
     *
     * Submits an empty target list to the pending prompt, which causes the engine
     * to return `TargetSelectionResult(false, false)` → spell targeting fails →
     * engine unwinds the cast (removes from stack, returns mana).
     */
    override fun onCancelAction(greMsg: ClientToGREMessage): Unit =
        synchronized(sessionLock) {
            if (gameBridge.humanVsHuman &&
                (lastPrompt == null || lastPrompt?.msgId != gameBridge.committedSequence().lastPromptMsgId)
            ) {
                gameBridge.cutCoordinator.publishIllegalRequest(seatId, greMsg, FailureReason.ReqRespMismatch)
                drainCoordinatorFeed()
                return@synchronized
            }
            val completedActionId = gameBridge.actionBridge(seatId).getPending()?.actionId
            // During combat declaration, cancel means "pass combat" (submit empty attackers).
            if (combatHandler.hasPendingAttackers()) {
                if (combatHandler.onCancelAttackers(greMsg.gameStateId)) {
                    runtimeContinuation.awaitHorizon(completedActionId)
                }
                return
            }
            awaitHandlerResult(targetingHandler.onCancelAction(greMsg), completedActionId)
        }

    /** Handle concede: send game-over sequence, then route through centralized teardown. */
    override fun onConcede() =
        synchronized(sessionLock) {
            gameBridge.cutCoordinator.publishConcession(seatId)
            sendGameOver()
        }

    /** Handle SetSettingsReq: submit immutable policy input, then echo the response. */
    override fun onSettings(greMsg: ClientToGREMessage) =
        synchronized(sessionLock) {
            val reqSettings = greMsg.setSettingsReq
            val incoming = reqSettings.settings
            log.info(
                "MatchSession: SetSettingsReq (stops={} transientStops={})",
                incoming.stopsCount,
                incoming.transientStopsCount,
            )

            val settings = gameBridge.priorityPolicy(seatId).submit(incoming, reqSettings.turnNumber)
            gameBridge.cutCoordinator.publishSettings(seatId, settings)
            drainCoordinatorFeed()
        }

    // --- Sending helpers ---

    /**
     * Build and send current game state + available actions from the live Forge engine.
     */
    override fun sendRealGameState(
        bridge: GameBridge,
        revealForSeat: Int?,
    ) {
        if (bridge.seat(seatId).action.getPending() == null && !bridge.hasPendingNonActionInteraction()) {
            bridge.awaitActionPriority(seatId)
        }
        if (bridge.seat(seatId).action.getPending() != null) {
            drainCoordinatorFeed()
            return
        }
        drainCoordinatorFeed()
    }

    override fun sendPriorityState(bridge: GameBridge) = drainCoordinatorFeed()

    internal fun deliverRuntimeHorizon() =
        synchronized(sessionLock) {
            runtimeContinuation.deliverHorizon().also { drainFamiliarFeed() }
        }

    private fun drainCoordinatorFeed() {
        drainCoordinatorBarrier(this, gameBridge, seatId)
        drainFamiliarFeed()
    }

    private fun drainFamiliarFeed() {
        (registry.getPeer(matchId, seatId) as? FamiliarSession)?.deliverCommitted()
    }

    /**
     * Send game-over sequence: 3x GS Diff + IntermissionReq + MatchCompleted room state.
     *
     * Per protocol analysis (post-game), the full sequence is:
     * 1. Server sends 3x GSM (GameOver) + IntermissionReq
     * 2. Client responds with CheckpointReq (handled in MatchHandler)
     * 3. Server sends MatchGameRoomStateChangedEvent (MatchCompleted)
     *
     * We send MatchCompleted immediately after IntermissionReq rather than
     * waiting for CheckpointReq — the client tolerates this ordering and it
     * avoids needing cross-layer coordination between MatchHandler and MatchSession.
     */
    override fun sendGameOver() {
        if (terminalCompleted) return
        val bridge = gameBridge
        val outcome = checkNotNull(bridge.cutCoordinator.committedGameOverOutcome()) { "Terminal outcome is not committed" }
        deliverCommittedCoordinatorBatches(this, bridge, seatId)
        drainFamiliarFeed()

        // Send MatchCompleted room state — triggers the client's result screen
        val matchCompletedMsg =
            HandshakeMessages.matchCompleted(
                matchId,
                outcome.winningTeam,
                playerId,
                outcome.result,
                outcome.reason,
                connection.roomPlayers,
                connection.eventName,
            )
        sink.sendRaw(matchCompletedMsg)
        terminalCompleted = true
        log
            .atInfo()
            .addKeyValue("event", "match.completed")
            .addKeyValue("match_id", matchId)
            .addKeyValue("seat", seatId.value)
            .addKeyValue("winning_team", outcome.winningTeam)
            .addKeyValue("reason", outcome.reason.name)
            .log("Match completed")

        // Publish the committed result after terminal output is visible.
        try {
            connection.resultObserver(MatchResultObservation(matchId, seatId.value, outcome.winningTeam))
        } catch (e: Exception) {
            log
                .atError()
                .setCause(e)
                .addKeyValue("event", "match.result_reporting_failed")
                .addKeyValue("match_id", matchId)
                .addKeyValue("seat", seatId.value)
                .log("Match result reporting failed")
        }

        if (bridge.humanVsHuman && !bridge.markHumanTerminalDelivered(seatId)) return

        registry.teardownMatch(
            matchId = matchId,
            reason = if (outcome.reason == ResultReason.Concede) MatchTeardownReason.Concede else MatchTeardownReason.GameOver,
            seatId = seatId,
            fallbackBridge = bridge,
        )
    }

    // --- Low-level helpers ---

    /**
     * Send multiple GRE messages bundled in one GreToClientEvent.
     *
     * Logical identity and prompt horizons are already committed before this sink runs.
     */
    override fun sendBundledGRE(messages: List<GREToClientMessage>) {
        val firstMsgId = messages.firstOrNull()?.msgId
        val maxGsId = messages.maxOfOrNull { it.gameStateId } ?: 0
        if (firstMsgId != null) {
            for (batch in ctx.bridge.cutCoordinator.drain(seatId, firstMsgId, maxGsId)) {
                sendBundledGREDirect(batch)
            }
        }
        sendBundledGREDirect(messages)
    }

    internal fun sendLifecycleGRE(messages: List<GREToClientMessage>) = sendBundledGREDirect(messages)

    private fun sendBundledGREDirect(messages: List<GREToClientMessage>) {
        val prompts = messages.filter { it.type in PROMPT_GRE_TYPES }
        for (prompt in prompts) {
            lastPrompt = prompt
            autopush?.onPrompt(prompt)
        }
        sink.send(messages)
        for (prompt in prompts) {
            log
                .atDebug()
                .addKeyValue("event", "match.prompt_published")
                .addKeyValue("match_id", matchId)
                .addKeyValue("seat", seatId.value)
                .addKeyValue("prompt_type", prompt.type.name)
                .addKeyValue("game_state_id", prompt.gameStateId)
                .log("Match prompt published")
        }
    }

    fun close() {
        autopush?.shutdown()
    }
}
