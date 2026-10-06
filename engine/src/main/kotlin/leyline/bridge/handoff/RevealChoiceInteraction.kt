package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

data class RevealChoiceCandidateValue(
    val originalOptionIndex: Int,
    val forgeCardId: ForgeCardId,
)

/** Immutable materialization input for one reveal-backed SelectN window. */
data class RevealChoiceWindowValue(
    val candidates: List<RevealChoiceCandidateValue>,
    val fullRevealCardIds: List<ForgeCardId>,
    val journalSeatId: SeatId,
    val revealVersion: Long,
    val revealOwnerSeatId: SeatId,
    val sourceForgeCardId: ForgeCardId?,
    val exileUnderSourceForgeCardId: ForgeCardId?,
    val min: Int,
    val max: Int,
    val defaultOptionIndex: Int,
    val promptId: Int? = null,
)

data class PublishedRevealChoiceInteraction(
    val interactionId: String,
    val gameStateId: Int,
)
