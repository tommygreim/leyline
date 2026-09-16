package leyline.game

import com.google.common.eventbus.Subscribe
import forge.game.event.*
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import leyline.bridge.types.SeatId
import leyline.game.event.combatDamageFact
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.ConcurrentHashMap
import leyline.game.event.GameEvent as LeylineGameEvent

/**
 * Requests and delivers per-action GRE state diffs for the client.
 *
 * EventBus callbacks classify mutations and delegate immutable requests to the
 * match-scoped cut coordinator. Forge completion hooks delegate the matching
 * journal boundary. This adapter owns no projection, feed, or terminal state.
 *
 * @param bridge the GameBridge for state mapping and zone tracking
 * @param seatId the human player's seat (messages are from their perspective)
 */
class GamePlayback(
    private val bridge: GameBridge,
    private val seatId: Int,
    /** Spectator playback captures both seats because every turn is remote to the viewer. */
    private val captureLocalActions: Boolean = false,
) : IGameEventVisitor.Base<Unit>() {
    private val log = LoggerFactory.getLogger(GamePlayback::class.java)

    init {
        bridge.cutCoordinator.requireViewer(SeatId(seatId))
    }

    /** Dedup: last turn+phase captured by TurnBegan, so TurnPhase can skip the duplicate. */
    private var lastCapturedTurn = 0
    private var lastCapturedPhase: PhaseType? = null

    // -- EventBus entry point --

    @Subscribe
    fun receiveGameEvent(ev: forge.game.event.GameEvent) {
        ev.visit(this)
    }

    override fun visit(ev: GameEventLandPlayed) {
        if (!isRemoteActing()) return
        requestCut(PlaybackCutReason.LandPlayed, LAND_DELAY)
    }

    /** Local stack objects seen at their cast/enter event and awaiting a
     * matching resolve event. Independent of GameEventCollector's trigger maps:
     * collector and playback are separate EventBus subscribers. */
    private val pendingLocalTriggers = ConcurrentHashMap<LocalStackKey, Int>()
    private val pendingLocalAbilities = ConcurrentHashMap<LocalStackKey, Int>()
    private val pendingLocalCasts = ConcurrentHashMap<LocalStackKey, Int>()

    override fun visit(ev: GameEventSpellAbilityCast) {
        val isTrigger = ev.si()?.isTrigger == true
        val isAbility = ev.si()?.isAbility == true
        val key = localStackKey(ev.sa()?.id ?: 0, ev.sa()?.hostCard?.id)
        val remoteActing = isRemoteActing()

        if (!remoteActing) {
            when {
                isTrigger -> {
                    if (key != null) markPending(pendingLocalTriggers, key)
                    requestCut(PlaybackCutReason.StackObjectCast, CAST_DELAY)
                }
                isAbility -> {
                    if (key != null) markPending(pendingLocalAbilities, key)
                    requestCut(PlaybackCutReason.StackObjectCast, CAST_DELAY)
                }
                !isAbility -> {
                    if (key != null) markPending(pendingLocalCasts, key)
                    requestCut(PlaybackCutReason.StackObjectCast, CAST_DELAY)
                }
            }
            return
        }

        requestCut(PlaybackCutReason.StackObjectCast, CAST_DELAY)
    }

    override fun visit(ev: GameEventSpellResolved) {
        val key = localStackKey(ev.spell()?.id ?: 0, ev.spell()?.hostCard?.id)
        val splitLocalStackObject =
            key != null &&
                (
                    consumePending(pendingLocalTriggers, key) ||
                        consumePending(pendingLocalAbilities, key) ||
                        consumePending(pendingLocalCasts, key)
                )
        if (!isRemoteActing() && !splitLocalStackObject) return
        requestCut(PlaybackCutReason.StackObjectResolved, RESOLVE_DELAY)
    }

    override fun visit(ev: GameEventCardChangeZone) {
        if (ev.from()?.zoneType != ZoneType.Stack) return
        requestCut(PlaybackCutReason.ResolutionZoneCompleted, 0)
    }

    override fun visit(ev: GameEventCardCounters) {
        if (isRemoteActing()) return
        requestCut(PlaybackCutReason.CountersChanged, COUNTER_DELAY)
    }

    override fun visit(ev: GameEventPlayerPoisoned) {
        if (isRemoteActing()) return
        requestCut(PlaybackCutReason.PoisonChanged, COUNTER_DELAY)
    }

    override fun visit(ev: GameEventTurnBegan) {
        // Capture for BOTH local and remote turns. The client plays its turn
        // transition from NewTurnStarted, which only rides a turnStarted cut, so
        // skipping our own turn left the client able to announce the opponent's
        // turn and never ours.
        val game =
            bridge.getGame() ?: run {
                log.debug("GamePlayback: TurnBegan during teardown (game null), dropping event")
                return
            }
        lastCapturedTurn = game.phaseHandler.turn
        lastCapturedPhase = game.phaseHandler.phase
        requestCut(PlaybackCutReason.TurnBegan, PHASE_DELAY, turnStarted = true)
    }

    override fun visit(ev: GameEventTurnPhase) {
        if (!isRemoteActing()) return
        val game =
            bridge.getGame() ?: run {
                log.debug("GamePlayback: TurnPhase during teardown (game null), dropping event")
                return
            }
        val turn = game.phaseHandler.turn
        val phase = game.phaseHandler.phase
        // Skip if TurnBegan already captured this exact turn+phase
        if (turn == lastCapturedTurn && phase == lastCapturedPhase) return
        lastCapturedTurn = turn
        lastCapturedPhase = phase
        val delay =
            when (ev.phase()) {
                PhaseType.COMBAT_DECLARE_ATTACKERS,
                PhaseType.COMBAT_DECLARE_BLOCKERS,
                PhaseType.COMBAT_END,
                -> COMBAT_DELAY
                else -> PHASE_DELAY
            }
        requestCut(PlaybackCutReason.PhaseChanged, delay)
    }

    override fun visit(ev: GameEventAttackersDeclared) {
        // Capture for BOTH local and remote attackers. The client expects a
        // combat-state diff (tapped creatures + attackState=Attacking) after
        // attackers are declared regardless of whose turn it is. Without this,
        // the transport continuation overshoots past combat before building
        // a diff, and the client never sees attackers tapped.
        if (isRemoteActing()) {
            requestCut(PlaybackCutReason.AttackersDeclared, COMBAT_DELAY, boundary = PlaybackCutBoundary.AttackersDeclared)
        } else {
            requestCut(PlaybackCutReason.AttackersDeclared, 0, boundary = PlaybackCutBoundary.AttackersDeclared)
        }
    }

    override fun visit(ev: GameEventBlockersDeclared) {
        if (!isRemoteActing()) return
        requestCut(PlaybackCutReason.BlockersDeclared, COMBAT_DELAY, boundary = PlaybackCutBoundary.BlockersDeclared)
    }

    override fun visit(ev: GameEventCombatEnded) {
        // Local turn needs a post-damage combat snapshot too. Without this,
        // damage/life/death annotations can sit in the collector queue until the
        // next later priority stop and get folded into a post-combat action GSM.
        requestCut(PlaybackCutReason.CombatEnded, 0, boundary = PlaybackCutBoundary.CombatEnded)
    }

    /** Forge main-loop completion entry point. Event visitors do no projection work. */
    fun onMainLoopStepCompleted() {
        val viewerSeat = SeatId(seatId)
        if (bridge.getGame()?.isGameOver == true) {
            bridge.cutCoordinator.publishGameOverFromEngine(viewerSeat)
        } else {
            bridge.cutCoordinator.flushPlaybackCut(viewerSeat, PlaybackCutBoundary.MainLoopStep)
        }
    }

    fun onAttackersDeclaredCompleted() = bridge.cutCoordinator.flushPlaybackCut(SeatId(seatId), PlaybackCutBoundary.AttackersDeclared)

    fun onBlockersDeclaredCompleted() = bridge.cutCoordinator.flushPlaybackCut(SeatId(seatId), PlaybackCutBoundary.BlockersDeclared)

    fun onCombatEndedCompleted() = bridge.cutCoordinator.flushPlaybackCut(SeatId(seatId), PlaybackCutBoundary.CombatEnded)

    /**
     * Establishes the ownership boundary between setup/mulligan state and ordinary
     * playback before Forge enters its first main-loop step.
     */
    fun onMainGameLoopStarted() {
        bridge.eventCollector?.closeOpeningHandActionWindow()
        bridge.cutCoordinator.onMainGameLoopStarted(SeatId(seatId))
    }

    /** A successfully installed shell frame subsumes the pending request for its journal. */
    internal fun onFrameCommitted() = bridge.cutCoordinator.acknowledgeExternalFrame(SeatId(seatId))

    // -- Queue access (called from MatchHandler / Netty thread) --

    /** Drain all queued message batches. Returns empty list if nothing queued. */
    fun drainQueue(): List<List<GREToClientMessage>> = bridge.cutCoordinator.drain(SeatId(seatId))

    /** Drain queued batches that must precede the caller's next outbound message. */
    fun drainQueueBeforeMsgId(
        msgId: Int,
        maxGsId: Int = 0,
    ): List<List<GREToClientMessage>> = bridge.cutCoordinator.drain(SeatId(seatId), msgId, maxGsId)

    /** True if there are messages waiting to be sent. */
    fun hasPendingMessages(): Boolean = bridge.cutCoordinator.hasCommittedBatches(SeatId(seatId))

    internal fun failure(): PlaybackTerminalFailure? = bridge.cutCoordinator.failure()

    // -- Internal --

    private fun requestCut(
        reason: PlaybackCutReason,
        delayMs: Int,
        turnStarted: Boolean = false,
        boundary: PlaybackCutBoundary = PlaybackCutBoundary.MainLoopStep,
    ) {
        bridge.cutCoordinator.requestPlaybackCut(
            SeatId(seatId),
            PlaybackCutRequest(reason, delayMs, turnStarted, boundary),
        )
    }

    /**
     * True when the current turn's active player is not this playback's seat.
     * Fires for AI turns and remote-seat turns uniformly.
     */
    private fun isRemoteActing(): Boolean {
        // No log here — called from every event listener; logging would
        // duplicate the teardown messages emitted by the listeners themselves.
        val game = bridge.getGame() ?: return false
        val turnPlayer = game.phaseHandler.playerTurn ?: return false
        val myPlayer = bridge.getPlayer(SeatId(seatId)) ?: return false
        return captureLocalActions || turnPlayer != myPlayer
    }

    companion object {
        const val PHASE_DELAY = 200 // ms
        const val COMBAT_DELAY = 400
        const val CAST_DELAY = 400
        const val RESOLVE_DELAY = 400
        const val COUNTER_DELAY = 300
        const val LAND_DELAY = 300
    }

    private data class LocalStackKey(
        val abilityId: Int,
        val hostCardId: Int,
    )

    private fun localStackKey(
        abilityId: Int,
        hostCardId: Int?,
    ): LocalStackKey? {
        if (abilityId == 0 || hostCardId == null) return null
        return LocalStackKey(abilityId, hostCardId)
    }

    private fun markPending(
        pending: ConcurrentHashMap<LocalStackKey, Int>,
        key: LocalStackKey,
    ) {
        pending.merge(key, 1, Int::plus)
    }

    private fun consumePending(
        pending: ConcurrentHashMap<LocalStackKey, Int>,
        key: LocalStackKey,
    ): Boolean {
        var consumed = false
        pending.computeIfPresent(key) { _, count ->
            consumed = true
            if (count <= 1) null else count - 1
        }
        return consumed
    }
}

/** Split only source-homogeneous combat windows; mixed damage keeps causal frame ownership. */
internal fun List<LeylineGameEvent>.shouldSplitCombatDamageWindow(): Boolean {
    val damageFacts = mapNotNull { it.combatDamageFact() }
    return damageFacts.isNotEmpty() && damageFacts.all { it }
}
