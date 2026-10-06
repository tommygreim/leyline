package leyline.game.mapping

import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationConstants
import leyline.game.snapshot.SeatSnapshot
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Emits the acquisition edge of a lasting player designation. */
internal fun insertCitysBlessingDesignationTransients(
    annotations: MutableList<AnnotationInfo>,
    prev: List<SeatSnapshot>,
    cur: List<SeatSnapshot>,
) {
    cur
        .filter { it.hasBlessing && prev.none { old -> old.seatId == it.seatId && old.hasBlessing } }
        .forEach { seat ->
            annotations.add(
                AnnotationBuilder
                    .gainDesignation(seat.seatId, AnnotationConstants.DESIGNATION_TYPE_CITYS_BLESSING)
                    .toBuilder()
                    .setAffectorId(seat.seatId.value)
                    .build(),
            )
        }
}
