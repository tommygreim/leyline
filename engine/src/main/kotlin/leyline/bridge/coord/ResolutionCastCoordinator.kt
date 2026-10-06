package leyline.bridge.coord

import forge.game.Game
import forge.game.GameEntity
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.keyword.Keyword
import forge.game.spellability.SpellAbility
import forge.game.trigger.WrappedAbility
import forge.util.collect.FCollectionView
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.BlockingInteractionRuntime
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.PromptIds

/** Turns Forge's Play-effect card choice into Arena's resolution-cast window. */
internal class ResolutionCastCoordinator(
    private val game: Game,
    private val runtime: BlockingInteractionRuntime,
    private val timeoutMs: () -> Long?,
) {
    sealed interface Selection {
        data object NotApplicable : Selection

        data class Answered(
            val card: Card?,
        ) : Selection
    }

    private data class Consent(
        val playEffect: SpellAbility,
        val selectedCardId: Int,
    )

    private var consent: Consent? = null

    fun <T : GameEntity> select(
        options: FCollectionView<T>,
        playEffect: SpellAbility?,
    ): Selection {
        val source = playEffect?.hostCard ?: return Selection.NotApplicable
        if (playEffect.api != ApiType.Play || playEffect.isKeyword(Keyword.MADNESS) || !game.stack.isResolving(source)) {
            return Selection.NotApplicable
        }
        val cards = options.filterIsInstance<Card>()
        if (cards.isEmpty() || cards.size != options.size || cards.any { it.isLand }) return Selection.NotApplicable
        val resolvingEntry = game.stack.firstOrNull { it.sourceCard.id == source.id } ?: return Selection.NotApplicable
        val stackAbility = resolvingEntry.spellAbility
        val root = (stackAbility as? WrappedAbility)?.wrappedAbility ?: stackAbility
        // Cascade and Discover already have their own free-cast action shape.
        if (root.hostCard?.hasKeyword("Cascade") == true ||
            generateSequence(root) { it.subAbility }.any { it.api == ApiType.Discover }
        ) {
            return Selection.NotApplicable
        }
        val wrapper = stackAbility as? WrappedAbility
        if (wrapper?.trigger?.getParam("Execute") == "ParadigmCopy" &&
            wrapper.hostCard?.effectSource?.hasKeyword("Paradigm") == true
        ) {
            return Selection.NotApplicable
        }

        consent = null
        val selectedId =
            runtime.awaitResolutionCast(
                BlockingInteraction.ResolutionCast(
                    sourceId = ForgeCardId(source.id),
                    sourceAbilityForgeId = resolvingEntry.takeIf { it.isAbility }?.spellAbility?.id,
                    candidateIds = cards.map { ForgeCardId(it.id) },
                    optional = playEffect.hasParam("Optional"),
                    withoutManaCost = playEffect.hasParam("WithoutManaCost"),
                    promptId = promptId(playEffect),
                ),
                timeoutMs(),
            )
        val selected = cards.firstOrNull { it.id == selectedId?.value }
        if (selected != null) consent = Consent(playEffect, selected.id)
        return Selection.Answered(selected)
    }

    fun alreadyConfirmed(
        playEffect: SpellAbility?,
        card: Card?,
    ): Boolean = playEffect != null && card != null && consent?.let { it.playEffect === playEffect && it.selectedCardId == card.id } == true

    /** Consume the card picker decision once. Forge still owns targeting and the actual cast. */
    fun consumeCast(spell: SpellAbility): Boolean {
        val selected = consent
        consent = null
        return selected?.let { choice ->
            spell.hostCard?.let { card ->
                card.id == choice.selectedCardId || card.copiedPermanent?.id == choice.selectedCardId
            }
        } == true
    }

    private fun promptId(playEffect: SpellAbility): Int =
        when {
            playEffect.hasParam("CopyCard") -> PromptIds.RESOLUTION_CAST_COPIES
            playEffect.hasParam("WithoutManaCost") && playEffect.getParamOrDefault("Amount", "") == "All" ->
                PromptIds.RESOLUTION_CAST_ANY_FREE
            playEffect.hasParam("WithoutManaCost") -> PromptIds.FREE_CAST_FROM_REVEAL
            else -> PromptIds.RESOLUTION_CAST_PAID
        }
}
