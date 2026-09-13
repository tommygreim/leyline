package leyline.match

import leyline.bridge.types.SeatId
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages shared matches and peer sessions across connections.
 * Replaces process-wide match state.
 *
 * Production: singleton instance. Tests: fresh per test.
 */
class MatchRegistry(
    private val onMatchTeardown: (String) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(MatchRegistry::class.java)

    /** matchId -> shared Match. First seat creates, second reuses. */
    private val matches = ConcurrentHashMap<String, Match>()

    /** matchId -> (seatId -> SessionOps). For cross-connection signaling. */
    private val sessions = ConcurrentHashMap<String, ConcurrentHashMap<Int, SessionOps>>()

    /** matchId -> (seatId -> MatchConnection). For pre-mulligan cross-connection messaging. */
    private val connections = ConcurrentHashMap<String, ConcurrentHashMap<Int, MatchConnection>>()

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
        val matchConnections = connections.remove(matchId)?.values.orEmpty()
        val removedSessions = sessions.remove(matchId)?.values.orEmpty()
        val sessionsRemoved = removedSessions.size
        val match = matches.remove(matchId)

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
}
