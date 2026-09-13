package leyline.native.matchmaking

import leyline.bridge.types.SeatId
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.MatchInfo
import leyline.domain.PlayerId
import leyline.domain.deck.DeckCards
import leyline.domain.deck.DeckSource
import leyline.match.MatchPlayerIdentity
import leyline.match.MatchSeatAssignment

data class PairedPlayer(
    val playerId: PlayerId,
    val displayName: String,
    val seatId: Int,
    val commanderGrpIds: List<Int> = emptyList(),
)

data class PairedSeat(
    val match: MatchInfo,
    val yourSeat: Int,
    val players: List<PairedPlayer>,
)

/**
 * One local room shared by Front Door pairing and Match Door admission.
 * A room reservation binds an authenticated persona to one seat; the client
 * cannot manufacture a seat by changing its GRE systemSeatId or Familiar suffix.
 * Decks are copied when queued, so edits while waiting cannot change the match.
 */
class LocalPairingService(
    private val runtimeMatchConfigs: RuntimeMatchConfigRegistry,
    private val matchInfoFactory: (String) -> MatchInfo,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val admissionTimeoutMillis: Long = 300_000,
) {
    private data class Entrant(
        val connectionId: String,
        val playerId: PlayerId,
        val displayName: String,
        val eventName: String,
        val deck: DeckCards,
        val onPaired: (PairedSeat) -> Unit,
    )

    private data class Room(
        val match: MatchInfo,
        val players: List<PairedPlayer>,
        val humanVsHuman: Boolean,
        val createdAtMillis: Long,
        val frontDoorConnections: Set<String> = emptySet(),
        val claims: MutableMap<Int, String> = mutableMapOf(),
    )

    private var waiting: Entrant? = null
    private var room: Room? = null

    fun queue(
        connectionId: String,
        playerId: PlayerId,
        displayName: String,
        eventName: String,
        deck: DeckCards,
        onPaired: (PairedSeat) -> Unit,
    ) {
        val notifications =
            synchronized(this) {
                expireUnclaimedRoom()
                require(room == null) { "The local room already has an active match" }
                val entrant = Entrant(connectionId, playerId, displayName, eventName, deck.snapshot(), onPaired)
                val first = waiting
                if (first == null) {
                    waiting = entrant
                    return
                }
                if (first.connectionId == connectionId) return
                require(first.playerId != playerId) { "Two different local profiles are required" }
                require(first.eventName == eventName) { "Both players must select the same event" }
                val match = matchInfoFactory(eventName)
                val players = listOf(first.player(1), entrant.player(2))
                runtimeMatchConfigs.put(
                    RuntimeMatchConfig(
                        matchId = match.matchId,
                        seat1 = DeckSource.Cards(first.deck),
                        seat2 = DeckSource.Cards(entrant.deck),
                        humanVsHuman = true,
                    ),
                )
                room =
                    Room(
                        match,
                        players,
                        humanVsHuman = true,
                        createdAtMillis = nowMillis(),
                        frontDoorConnections = setOf(first.connectionId, connectionId),
                    )
                waiting = null
                listOf(
                    first.onPaired to PairedSeat(match, 1, players),
                    entrant.onPaired to PairedSeat(match, 2, players),
                )
            }
        // Each FD callback schedules its write on that connection's event loop.
        try {
            notifications.forEach { (notify, seat) -> notify(seat) }
        } catch (failure: Exception) {
            complete(
                notifications
                    .first()
                    .second.match.matchId,
            )
            throw failure
        }
    }

    @Synchronized
    fun leave(connectionId: String) {
        if (waiting?.connectionId == connectionId) waiting = null
        room?.takeIf { it.claims.isEmpty() && connectionId in it.frontDoorConnections }?.let { complete(it.match.matchId) }
    }

    @Synchronized
    fun registerBot(
        match: MatchInfo,
        playerId: PlayerId,
        displayName: String,
    ) {
        expireUnclaimedRoom()
        require(room == null) { "The local room already has an active match" }
        require(waiting == null) { "Leave the player queue before starting a bot match" }
        room =
            Room(
                match,
                listOf(
                    PairedPlayer(playerId, displayName, 1),
                    PairedPlayer(PlayerId("${playerId.value}_Familiar"), "AI Opponent", 2),
                ),
                humanVsHuman = false,
                createdAtMillis = nowMillis(),
            )
    }

    /** Claim a reserved seat once for a particular Match Door connection. */
    @Synchronized
    fun claim(
        matchId: String,
        playerId: PlayerId,
        requestedSeat: Int,
        familiar: Boolean,
        connectionId: String,
    ): MatchSeatAssignment {
        expireUnclaimedRoom()
        val active = requireNotNull(room?.takeIf { it.match.matchId == matchId }) { "No reserved local match" }
        val expectedSeat =
            if (familiar) {
                require(!active.humanVsHuman && active.players.first().playerId == playerId) { "Familiar seat is unavailable" }
                2
            } else {
                requireNotNull(active.players.firstOrNull { it.playerId == playerId }) { "Player is not reserved in this match" }.seatId
            }
        require(requestedSeat == 0 || requestedSeat == expectedSeat) { "Requested seat does not match the reservation" }
        val previous = active.claims[expectedSeat]
        require(previous == null || previous == connectionId) { "The reserved seat is already connected" }
        active.claims[expectedSeat] = connectionId
        return MatchSeatAssignment(
            matchId,
            if (familiar) "${playerId.value}_Familiar" else playerId.value,
            SeatId(expectedSeat),
            active.match.eventName,
            active.players.map { MatchPlayerIdentity(it.playerId.value, it.displayName, SeatId(it.seatId)) },
            active.humanVsHuman,
            familiar,
        )
    }

    @Synchronized
    fun complete(matchId: String) {
        if (room?.match?.matchId != matchId) return
        room = null
        runtimeMatchConfigs.remove(matchId)
    }

    private fun expireUnclaimedRoom() {
        room
            ?.takeIf { it.claims.isEmpty() && nowMillis() - it.createdAtMillis >= admissionTimeoutMillis }
            ?.let { complete(it.match.matchId) }
    }

    private fun Entrant.player(seat: Int): PairedPlayer = PairedPlayer(playerId, displayName, seat, deck.commandZone.map { it.grpId })

    private fun DeckCards.snapshot(): DeckCards =
        copy(
            mainDeck = mainDeck.toList(),
            sideboard = sideboard.toList(),
            commandZone = commandZone.toList(),
            companions = companions.toList(),
        )
}
