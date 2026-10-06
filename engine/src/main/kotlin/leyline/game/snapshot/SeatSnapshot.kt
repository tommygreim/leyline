package leyline.game.snapshot

import leyline.bridge.types.SeatId
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType

data class ManaPoolEntry(
    val manaId: Int,
    val color: ManaColor,
    val srcInstanceId: Int,
    val abilityGrpId: Int,
    val count: Int,
    val specs: List<ManaSpecType> = emptyList(),
)

/**
 * Immutable per-seat state read by mappers. Field set grows as mappers migrate;
 * the minimal set here covers [leyline.game.mapping.PlayerMapper].
 */
data class SeatSnapshot(
    val seatId: SeatId,
    val life: Int,
    val startingLife: Int,
    val maxHandSize: Int,
    val speed: Int = 0,
    val manaPool: List<ManaPoolEntry> = emptyList(),
    val hasBlessing: Boolean = false,
)
