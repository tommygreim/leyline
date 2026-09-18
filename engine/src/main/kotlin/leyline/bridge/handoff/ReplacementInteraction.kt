package leyline.bridge.handoff

import forge.game.replacement.ReplacementEffect
import leyline.bridge.types.ForgeCardId

/** Immutable candidate facts retained beside its exact Forge replacement handle. */
data class ReplacementOptionValue(
    val originalOptionIndex: Int,
    val hostForgeCardId: ForgeCardId,
    val uniqueAbilityId: Int,
    val abilityGrpId: Int,
)

/** Immutable materialization input for one competing self-replacement window. */
data class ReplacementWindowValue(
    val options: List<ReplacementOptionValue>,
    val defaultOptionIndex: Int,
    /** True when every option is a Dredge replacement — wire signal for `SelectReplacementsType.AllDredge`. */
    val allDredge: Boolean = false,
) {
    init {
        require(options.size >= 2) { "Replacement selection requires at least two options" }
        require(options.map { it.originalOptionIndex }.distinct().size == options.size) {
            "Replacement options must preserve distinct option indexes"
        }
        require(options.map { it.hostForgeCardId }.distinct().size == options.size) {
            "Replacement options must be distinct self-replacements"
        }
        require(defaultOptionIndex in options.indices) { "Replacement default option is out of range" }
    }
}

data class ReplacementInteractionResult(
    val optionIndex: Int,
    val handle: ReplacementEffect,
    val timedOut: Boolean = false,
)

/** Blocking engine-thread contract for competing self-replacement choices. */
interface ReplacementInteractionRuntime {
    fun awaitReplacement(
        request: PromptRequest,
        possibleReplacers: List<ReplacementEffect>,
        timeoutMs: Long?,
    ): ReplacementInteractionResult?
}

data class PublishedReplacementInteraction(
    val interactionId: String,
    val gameStateId: Int,
)
