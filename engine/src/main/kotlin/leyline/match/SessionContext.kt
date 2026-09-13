package leyline.match

import forge.game.Game
import leyline.game.state.GameBridge

/**
 * Resolved session state -- non-null after bridge connection.
 * Constructed once per handler dispatch inside the synchronized block.
 */
data class SessionContext(
    val game: Game,
    val bridge: GameBridge,
    val seatId: leyline.bridge.types.SeatId = leyline.bridge.types.SeatId(1),
)
