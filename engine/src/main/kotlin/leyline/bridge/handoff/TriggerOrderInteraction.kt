package leyline.bridge.handoff

import forge.game.spellability.SpellAbility
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

/** Immutable facts for one simultaneous trigger, retained beside its exact Forge handle. */
data class TriggerOrderOptionValue(
    val originalOptionIndex: Int,
    val forgeAbilityId: Int,
    val sourceForgeCardId: ForgeCardId,
    val abilityGrpId: Int,
    val sourceCardGrpId: Int,
    val ownerSeatId: SeatId,
    val controllerSeatId: SeatId,
)

/**
 * Immutable materialization input for one simultaneous-trigger ordering window.
 * [options] keep Forge's order: the first option is the one Forge would resolve first.
 */
data class TriggerOrderWindowValue(
    val options: List<TriggerOrderOptionValue>,
) {
    init {
        require(options.size >= 2) { "Trigger ordering requires at least two options" }
        require(options.map { it.originalOptionIndex }.distinct().size == options.size) {
            "Trigger options must preserve distinct option indexes"
        }
        require(options.map { it.forgeAbilityId }.distinct().size == options.size) {
            "Trigger options must be distinct abilities"
        }
    }
}

/** [optionIndices] and [handles] are in resolve-first order, as Forge's `orderSimultaneousSa` expects. */
data class TriggerOrderInteractionResult(
    val optionIndices: List<Int>,
    val handles: List<SpellAbility>,
    val timedOut: Boolean = false,
)

/** Blocking engine-thread contract for the simultaneous-trigger ordering choice (CR 603.3b). */
interface TriggerOrderInteractionRuntime {
    fun awaitTriggerOrder(
        request: PromptRequest,
        abilities: List<SpellAbility>,
        timeoutMs: Long?,
    ): TriggerOrderInteractionResult?
}

data class PublishedTriggerOrderInteraction(
    val interactionId: String,
    val gameStateId: Int,
)
