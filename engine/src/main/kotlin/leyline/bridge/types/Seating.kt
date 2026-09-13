package leyline.bridge.types

/**
 * Match-scoped player identity mapping and single-human role defaults.
 * [playerSeats] maps Forge player IDs to protocol seats, including matches
 * with two human controllers. The human/Familiar defaults retain the bot and
 * synthetic-fixture conventions when no explicit player mapping is supplied.
 *
 * Constructed once per match in `GameBridge.populateSeatMap` and exposed as
 * `gameBridge.seating`. Replaces hardcoded `seatId == 1` / `seatId == 2`
 * role gates throughout engine prod.
 *
 * This is a 2-player invariant; `humanSeat.opponent == familiarSeat` holds
 * by construction.
 */
data class Seating(
    val humanSeat: SeatId,
    val familiarSeat: SeatId,
    val playerSeats: Map<Int, SeatId> = emptyMap(),
) {
    fun seatOf(
        playerId: Int,
        isAi: Boolean = false,
    ): SeatId = playerSeats[playerId] ?: if (isAi) familiarSeat else humanSeat
}
