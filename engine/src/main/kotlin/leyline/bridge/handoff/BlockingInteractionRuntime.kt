package leyline.bridge.handoff

import forge.game.GameEntity
import forge.game.card.Card
import forge.game.card.CardCollectionView

/** Shell runtime; live handles remain engine-side and never cross the session boundary. */
interface BlockingInteractionRuntime {
    fun awaitOptional(
        interaction: BlockingInteraction.Optional,
        timeoutMs: Long?,
        defaultOnTimeout: Boolean,
    ): Boolean

    /** Optional source used when the Forge card is not reachable from a zone yet. */
    fun awaitOptional(
        interaction: BlockingInteraction.Optional,
        sourceCard: Card?,
        timeoutMs: Long?,
        defaultOnTimeout: Boolean,
    ): Boolean = awaitOptional(interaction, timeoutMs, defaultOnTimeout)

    fun awaitTopOrBottom(
        interaction: BlockingInteraction.TopOrBottom,
        timeoutMs: Long?,
        defaultOnTimeout: Boolean,
    ): Boolean = defaultOnTimeout

    fun awaitNumeric(
        interaction: BlockingInteraction.Numeric,
        timeoutMs: Long?,
    ): Int

    fun awaitDamage(
        interaction: BlockingInteraction.Damage,
        attacker: Card,
        blockers: CardCollectionView,
        defender: GameEntity?,
        timeoutMs: Long?,
        fallback: () -> MutableMap<Card?, Int>?,
    ): MutableMap<Card?, Int>?

    fun takeCachedDamage(
        attacker: Card,
        blockers: CardCollectionView,
    ): MutableMap<Card?, Int>?
}
