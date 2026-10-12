package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Manages shared matches and peer sessions across connections.
 * Replaces process-wide match state.
 *
 * Production: singleton instance. Tests: fresh per test.
 */
class MatchRegistry(
    private val reconnectGraceMs: Long = 120_000,
    private val onMatchTeardown: (String) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(MatchRegistry::class.java)

    /** matchId -> shared Match. First seat creates, second reuses. */
    private val matches = ConcurrentHashMap<String, Match>()

    /** matchId -> (seatId -> SessionOps). For cross-connection signaling. */
    private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<Int, SessionOps>>()

    /** matchId -> (seatId -> MatchConnection). For pre-mulligan cross-connection messaging. */
    private val connections = ConcurrentHashMap<String, ConcurrentHashMap<Int, MatchConnection>>()

    internal class ReconnectLease(
        val session: MatchSession,
    )

    private val detached = mutableMapOf<Pair<String, Int>, ReconnectLease>()
    private val expiryJobs = mutableMapOf<Pair<String, Int>, ScheduledFuture<*>>()

    /** Preserve the engine and exact pending interaction, but release only this transport. */
    @Synchronized
    internal fun detachConnection(
        matchId: String,
        seatId: SeatId,
        connection: MatchConnection,
    ): Boolean {
        if (matches[matchId]?.bridge?.humanVsHuman != true) return false
        if (connections[matchId]?.remove(seatId.value, connection) != true) return false
        val session = sessions[matchId]?.get(seatId.value) as? MatchSession ?: return false
        val key = matchId to seatId.value
        val lease = ReconnectLease(session)
        detached[key] = lease
        expiryJobs.remove(key)?.cancel(false)
        expiryJobs[key] =
            reconnectExpiry.schedule(
                { expireDisconnected(matchId, seatId, lease) },
                reconnectGraceMs,
                TimeUnit.MILLISECONDS,
            )
        log.info("Match transport detached; seat {} has {} ms to reconnect", seatId.value, reconnectGraceMs)
        return true
    }

    @Synchronized
    internal fun detachedSession(
        matchId: String,
        seatId: SeatId,
    ): MatchSession? = detached[matchId to seatId.value]?.session

    @Synchronized
    internal fun reconnectLease(
        matchId: String,
        seatId: SeatId,
    ): ReconnectLease? = detached[matchId to seatId.value]

    /** Native admission has already checked the authenticated reservation. Check it again here. */
    @Synchronized
    internal fun reattachConnection(
        matchId: String,
        seatId: SeatId,
        playerId: String,
        connection: MatchConnection,
        expectedSession: MatchSession,
    ) {
        val key = matchId to seatId.value
        require(
            detached[key]?.session === expectedSession && expectedSession.playerId == playerId,
        ) { "No disconnected session for this player" }
        require(connections[matchId]?.get(seatId.value) == null) { "The human seat is already connected" }
        connections.getValue(matchId)[seatId.value] = connection
        detached.remove(key)
        expiryJobs.remove(key)?.cancel(false)
    }

    internal fun expireDisconnected(
        matchId: String,
        seatId: SeatId,
        expectedLease: ReconnectLease,
    ) {
        synchronized(this) {
            val key = matchId to seatId.value
            if (detached[key] !== expectedLease) return
            detached.remove(key)
        }
        teardownMatch(matchId, MatchTeardownReason.Disconnect, seatId)
    }

    fun getOrCreateMatch(
        matchId: String,
        factory: () -> Match,
    ): Match = matches.computeIfAbsent(matchId) { factory() }

    /** Look up a match by id. */
    fun getMatch(matchId: String): Match? = matches[matchId]

    fun registerSession(
        matchId: String,
        seatId: SeatId,
        session: SessionOps,
    ) {
        sessions.computeIfAbsent(matchId) { ConcurrentHashMap() }.compute(seatId.value) { _, previous ->
            require(matches[matchId]?.bridge?.humanVsHuman != true || previous == null || previous === session) {
                "A human seat already has a session"
            }
            if (previous is SpectatorSession && previous !== session) previous.close()
            session
        }
    }

    /** Get the OTHER seat's session (seat 1 -> seat 2, seat 2 -> seat 1). */
    fun getPeer(
        matchId: String,
        seatId: SeatId,
    ): SessionOps? {
        val peerSeat = SeatId(if (seatId.value == 1) 2 else 1)
        return sessions[matchId]?.get(peerSeat.value)
    }

    /** Return the active human session; Familiar registration never replaces it. */
    fun activeHumanSession(): MatchSession? =
        sessions.values
            .asSequence()
            .mapNotNull { it[1] as? MatchSession }
            .firstOrNull()

    /**
     * Remove all matches and sessions except [currentMatchId].
     * Returns list of evicted matches (already closed).
     */
    fun evictStale(currentMatchId: String): List<Match> {
        val staleKeys = matches.keys.filter { it != currentMatchId }
        val evicted = staleKeys.mapNotNull { matches[it] }
        staleKeys.forEach { teardownMatch(it, MatchTeardownReason.Disconnect) }
        return evicted
    }

    fun registerConnection(
        matchId: String,
        seatId: SeatId,
        connection: MatchConnection,
    ) {
        connections.computeIfAbsent(matchId) { ConcurrentHashMap() }.compute(seatId.value) { _, previous ->
            require(matches[matchId]?.bridge?.humanVsHuman != true || previous == null || previous === connection) {
                "A human seat already has a connection"
            }
            connection
        }
    }

    fun getConnection(
        matchId: String,
        seatId: SeatId,
    ): MatchConnection? = connections[matchId]?.get(seatId.value)

    fun removeMatch(matchId: String): Match? = matches.remove(matchId)

    fun teardownMatch(
        matchId: String,
        reason: MatchTeardownReason,
        seatId: SeatId? = null,
        fallbackBridge: GameBridge? = null,
    ) {
        val (matchConnections, removedSessions, match) =
            synchronized(this) {
                detached.keys.filter { it.first == matchId }.forEach { key ->
                    detached.remove(key)
                }
                expiryJobs.keys.filter { it.first == matchId }.forEach { key ->
                    expiryJobs.remove(key)?.cancel(false)
                }
                Triple(
                    connections
                        .remove(matchId)
                        ?.values
                        ?.toList()
                        .orEmpty(),
                    sessions
                        .remove(matchId)
                        ?.values
                        ?.toList()
                        .orEmpty(),
                    matches.remove(matchId),
                )
            }
        val sessionsRemoved = removedSessions.size

        removedSessions.filterIsInstance<MatchSession>().forEach { it.close() }
        removedSessions.filterIsInstance<SpectatorSession>().forEach { it.close() }
        matchConnections.forEach { it.detachAfterTeardown() }

        if (match != null) {
            match.close()
        } else {
            fallbackBridge?.shutdown()
        }
        onMatchTeardown(matchId)

        val event =
            log
                .atInfo()
                .addKeyValue("event", "match.teardown")
                .addKeyValue("match_id", matchId)
        val seatedEvent = seatId?.value?.let { event.addKeyValue("seat", it) } ?: event
        seatedEvent
            .addKeyValue("reason", reason.name)
            .addKeyValue("sessions_removed", sessionsRemoved)
            .addKeyValue("connections_removed", matchConnections.size)
            .addKeyValue("match_closed", match != null || fallbackBridge != null)
            .log("Match torn down")
    }

    private companion object {
        val reconnectExpiry: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "match-reconnect-expiry").apply { isDaemon = true }
            }
    }
}
