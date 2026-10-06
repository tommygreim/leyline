package leyline.bridge.handoff

import forge.card.mana.ManaCost
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.spellability.SpellAbility
import leyline.bridge.types.ForgeCardId

/** Shell runtime; live handles remain engine-side and never cross the session boundary. */
interface BlockingInteractionRuntime {
    /** Return an engine-thread command; no Forge state is mutated by the session thread. */
    fun awaitManaPayment(
        interaction: BlockingInteraction.ManaPayment,
        manaCost: ManaCost,
        ability: SpellAbility,
    ): ManaPaymentDecision = ManaPaymentDecision.Cancel

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

    /** Return the selected Forge card, or null when the player declines. */
    fun awaitResolutionCast(
        interaction: BlockingInteraction.ResolutionCast,
        timeoutMs: Long?,
    ): ForgeCardId? = null

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
