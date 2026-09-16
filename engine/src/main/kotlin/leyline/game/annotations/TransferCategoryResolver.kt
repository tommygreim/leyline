package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.game.event.DestructionCause
import leyline.game.event.GameEvent
import leyline.game.event.Zone

/**
 * Operation-specific fallback for transfers without an authoritative
 * [ZoneMoveIntent]. Zone-pair classification belongs to
 * [ZoneTransferDetector.inferCategory].
 */
object TransferCategoryResolver {
    /** Return the strongest operation fact for [forgeCardId], if one exists. */
    fun categoryFromEvents(
        forgeCardId: ForgeCardId,
        events: List<GameEvent>,
    ): TransferCategory? {
        val destruction =
            events.filterIsInstance<GameEvent.CardDestroyed>().firstOrNull { it.cardId == forgeCardId }?.destruction
        return when {
            events.has<GameEvent.OpeningHandAction>(forgeCardId) -> TransferCategory.Put
            events.has<GameEvent.LandPlayed>(forgeCardId) -> TransferCategory.PlayLand
            events.hasSpellCast(forgeCardId) -> TransferCategory.CastSpell
            events.hasFizzledResolution(forgeCardId) -> TransferCategory.Countered
            events.hasResolvedSpell(forgeCardId) -> TransferCategory.Resolve
            events.has<GameEvent.LegendRuleDeath>(forgeCardId) -> TransferCategory.SbaLegendRule
            events.has<GameEvent.CardSurveiled>(forgeCardId) -> TransferCategory.Surveil
            events.has<GameEvent.CardExiled>(forgeCardId) -> TransferCategory.Exile
            events.has<GameEvent.CardBounced>(forgeCardId) -> TransferCategory.Bounce
            events.has<GameEvent.CardDiscarded>(forgeCardId) -> TransferCategory.Discard
            events.has<GameEvent.CardMilled>(forgeCardId) -> TransferCategory.Mill
            events.has<GameEvent.SpellCountered>(forgeCardId) -> TransferCategory.Countered
            events.has<GameEvent.CardSacrificed>(forgeCardId) -> TransferCategory.Sacrifice
            destruction != null -> destructionCategory(destruction)
            else ->
                events
                    .filterIsInstance<GameEvent.ZoneChanged>()
                    .firstOrNull { it.cardId == forgeCardId }
                    ?.let { categoryFromZonePair(it.from, it.to) }
        }
    }

    /** Wire category for a destroyed permanent: destroy effects and the
     *  lethal-damage / deathtouch state-based actions carry distinct labels. */
    fun destructionCategory(cause: DestructionCause): TransferCategory =
        when (cause) {
            DestructionCause.Effect -> TransferCategory.Destroy
            DestructionCause.LethalDamage -> TransferCategory.SbaDamage
            DestructionCause.Deathtouch -> TransferCategory.SbaDeathtouch
        }

    @Suppress("CyclomaticComplexMethod") // Exhaustive transfer matrix is clearest as one table.
    internal fun categoryFromZonePair(
        from: Zone,
        to: Zone,
    ): TransferCategory =
        when {
            to == Zone.Exile -> TransferCategory.Exile
            from == Zone.Hand && to == Zone.Battlefield -> TransferCategory.PlayLand
            from == Zone.Hand && to == Zone.Stack -> TransferCategory.CastSpell
            from == Zone.Hand && to == Zone.Graveyard -> TransferCategory.Discard
            from == Zone.Stack && to == Zone.Battlefield -> TransferCategory.Resolve
            from == Zone.Stack && to == Zone.Graveyard -> TransferCategory.Countered
            from == Zone.Battlefield && to == Zone.Graveyard -> TransferCategory.Destroy
            from == Zone.Battlefield && to in setOf(Zone.Hand, Zone.Library) -> TransferCategory.Bounce
            from == Zone.Library && to == Zone.Hand -> TransferCategory.Draw
            from == Zone.Library && to == Zone.Battlefield -> TransferCategory.Search
            from == Zone.Library && to == Zone.Graveyard -> TransferCategory.Mill
            from == Zone.Hand && to == Zone.Library -> TransferCategory.Put
            from == Zone.Sideboard && to == Zone.Hand -> TransferCategory.Put
            from in setOf(Zone.Graveyard, Zone.Exile) && to in setOf(Zone.Hand, Zone.Battlefield) ->
                TransferCategory.Return
            from == Zone.Exile && to == Zone.Graveyard -> TransferCategory.Put
            else -> TransferCategory.ZoneTransfer
        }

    /** Return source-card context available on operation-specific fallback events. */
    fun affectorSourceFromEvents(
        forgeCardId: ForgeCardId,
        events: List<GameEvent>,
    ): ForgeCardId? =
        events.firstNotNullOfOrNull { event ->
            when (event) {
                is GameEvent.CardMilled -> event.sourceCardId.takeIf { event.cardId == forgeCardId }
                is GameEvent.CardSurveiled -> event.sourceCardId.takeIf { event.cardId == forgeCardId }
                is GameEvent.CardDestroyed -> event.sourceCardId.takeIf { event.cardId == forgeCardId }
                is GameEvent.CardSacrificed -> event.sourceCardId.takeIf { event.cardId == forgeCardId }
                else -> null
            }
        }
}

private inline fun <reified T : GameEvent> List<GameEvent>.has(forgeCardId: ForgeCardId): Boolean =
    filterIsInstance<T>().any { it.cardId() == forgeCardId }

@Suppress("ElseCaseInsteadOfExhaustiveWhen") // Only operation-specific events carry a fallback card id.
private fun GameEvent.cardId(): ForgeCardId? =
    when (this) {
        is GameEvent.OpeningHandAction -> cardId
        is GameEvent.LandPlayed -> cardId
        is GameEvent.LegendRuleDeath -> cardId
        is GameEvent.CardSurveiled -> cardId
        is GameEvent.CardExiled -> cardId
        is GameEvent.CardBounced -> cardId
        is GameEvent.CardDiscarded -> cardId
        is GameEvent.CardMilled -> cardId
        is GameEvent.SpellCountered -> cardId
        is GameEvent.CardSacrificed -> cardId
        is GameEvent.CardDestroyed -> cardId
        else -> null
    }

private fun List<GameEvent>.hasSpellCast(forgeCardId: ForgeCardId): Boolean =
    filterIsInstance<GameEvent.SpellCast>().any { it.cardId == forgeCardId && !it.isAbility }

private fun List<GameEvent>.hasFizzledResolution(forgeCardId: ForgeCardId): Boolean =
    filterIsInstance<GameEvent.SpellResolved>().any { it.cardId == forgeCardId && it.hasFizzled }

private fun List<GameEvent>.hasResolvedSpell(forgeCardId: ForgeCardId): Boolean =
    filterIsInstance<GameEvent.SpellResolved>().any { it.cardId == forgeCardId && !it.hasFizzled }
