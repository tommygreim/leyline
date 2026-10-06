package leyline.bridge.handoff

import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/** One modal option in the full Forge mode list. */
data class ModalChoiceOptionValue(
    val fullIndex: Int,
    val grpId: Int,
    val cost: List<Pair<ManaColor, Int>> = emptyList(),
)

/** Immutable protocol input for one modal CastingTimeOptionsReq window. */
data class ModalChoiceWindowValue(
    val sourceForgeCardId: ForgeCardId,
    val sourceCardGrpId: Int,
    val sourceForgeAbilityId: Int,
    val parentGrpId: Int,
    val ctoGrpId: Int,
    val ctoId: Int,
    val min: Int,
    val max: Int,
    val defaultOptionIndex: Int,
    val allowRepeat: Boolean,
    val possible: List<ModalChoiceOptionValue>,
    val excluded: List<ModalChoiceOptionValue>,
    val triggered: Boolean,
    val isVote: Boolean = false,
    val allowCancel: Boolean = !isVote,
) {
    init {
        require(min in 0..max)
        require(allowRepeat || max <= possible.size)
        require(possible.map { it.grpId }.distinct().size == possible.size)
        require(possible.map { it.fullIndex }.distinct().size == possible.size)
        require(excluded.map { it.grpId }.distinct().size == excluded.size)
        require(excluded.map { it.fullIndex }.distinct().size == excluded.size)
        require((possible + excluded).map { it.fullIndex }.distinct().size == possible.size + excluded.size)
        require((possible + excluded).map { it.grpId }.distinct().size == possible.size + excluded.size)
        require(excluded.none { option -> option.grpId in possible.map { it.grpId } })
        require(defaultOptionIndex in possible.indices)
    }
}

/** Session-visible identity of the latest modal prompt. */
data class PublishedModalChoiceInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val sourceInstanceId: Int,
)

/** Exact result returned to Forge after a modal response is accepted. */
data class ModalChoiceInteractionResult(
    val optionIndices: List<Int>,
    val handles: List<AbilitySub>,
    val timedOut: Boolean,
)

/** Exact Forge context retained for the harness policy while a modal window is active. */
internal data class ModalChoiceAiContext(
    val sourceAbility: SpellAbility,
    val possible: List<AbilitySub>,
    val possibleFullIndices: List<Int>,
)

/** Engine-thread owner for route-bound modal choices. */
interface ModalChoiceInteractionRuntime {
    fun awaitSelection(
        request: PromptRequest,
        possible: List<AbilitySub>,
        sourceCard: Card,
        sourceAbility: SpellAbility,
        timeoutMs: Long?,
    ): ModalChoiceInteractionResult
}
