package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId

data class StaticChoiceOptionValue(
    val originalOptionIndex: Int,
    val protocolValue: Int,
    val label: String = "",
    val promptParameterId: Int? = null,
)

/** Immutable materialization input for one static enum SelectN window. */
data class StaticChoiceWindowValue(
    val kind: StaticChoiceKind,
    val options: List<StaticChoiceOptionValue>,
    val sourceForgeCardId: ForgeCardId?,
    val min: Int,
    val max: Int,
    val defaultOptionIndex: Int,
    val protocolPromptId: Int? = null,
)

data class PublishedStaticChoiceInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val kind: StaticChoiceKind,
)
