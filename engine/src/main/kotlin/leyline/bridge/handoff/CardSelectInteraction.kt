package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId

data class CardSelectCandidateValue(
    val originalOptionIndex: Int,
    val forgeCardId: ForgeCardId,
    val originZone: CardSelectOriginZone,
)

enum class CardSelectOriginZone {
    Hand,
    Library,
    Sideboard,
    Other,
}

/** Immutable materialization input for one card-backed SelectN window. */
data class CardSelectWindowValue(
    val kind: CardSelectKind,
    val candidates: List<CardSelectCandidateValue>,
    val sourceForgeCardId: ForgeCardId?,
    val min: Int,
    val max: Int,
    val defaultOptionIndex: Int,
    val choiceResultSentiment: Int?,
    /** True when the client's cancel may retire this window with an empty selection. */
    val cancellable: Boolean = false,
)

data class PublishedCardSelectInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val kind: CardSelectKind,
)
