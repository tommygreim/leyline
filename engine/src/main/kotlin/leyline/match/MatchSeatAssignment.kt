package leyline.match

import leyline.bridge.types.SeatId

/** Player identities fixed by the host before either participant joins the game. */
data class MatchPlayerIdentity(
    val playerId: String,
    val displayName: String,
    val seatId: SeatId,
)

/** Trusted host assignment; transport credentials are validated outside the engine. */
data class MatchSeatAssignment(
    val matchId: String,
    val playerId: String,
    val seatId: SeatId,
    val eventName: String,
    val players: List<MatchPlayerIdentity>,
    val humanVsHuman: Boolean,
    val familiar: Boolean = false,
) {
    init {
        require(seatId.value in 1..2) { "Only seats 1 and 2 are available" }
        require(!humanVsHuman || !familiar) { "A human seat cannot be a Familiar" }
    }
}
