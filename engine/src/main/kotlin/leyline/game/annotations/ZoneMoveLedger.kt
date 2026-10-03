package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.event.ZoneMove
import leyline.game.event.ZoneMoveCause
import leyline.game.mapping.ZoneIds

enum class TransferPlanOrigin {
    Event,
    SnapshotFallback,
}

data class ZoneMoveIntent(
    val move: ZoneMove,
    val category: TransferCategory,
    val sourceCardId: ForgeCardId?,
    val sourceAbilityForgeId: Int,
    val rootAbilityForgeId: Int = 0,
    val stackAbilityForgeId: Int = 0,
    val origin: TransferPlanOrigin = TransferPlanOrigin.Event,
) {
    fun matches(
        forgeCardId: ForgeCardId,
        srcZoneId: Int,
        destZoneId: Int,
    ): Boolean =
        move.cardId == forgeCardId &&
            move.from.matchesProtocolZone(srcZoneId) &&
            move.to.matchesProtocolZone(destZoneId)
}

/** Pure event-first classification of ordered Forge zone operations. */
object ZoneMoveLedger {
    fun fold(
        moves: List<ZoneMove>,
        events: List<GameEvent>,
    ): List<ZoneMoveIntent> =
        moves.sortedBy { it.order }.map { move ->
            val eventBacked = move.cause != null || hasSpecificOperationEvent(move, events)
            ZoneMoveIntent(
                move = move,
                category = category(move, events),
                sourceCardId = move.cause?.sourceCardId ?: sourceFromSpecificEvent(move, events),
                sourceAbilityForgeId = move.cause?.abilityForgeId ?: 0,
                rootAbilityForgeId = move.cause?.rootAbilityForgeId ?: 0,
                stackAbilityForgeId = move.cause?.stackAbilityForgeId ?: 0,
                origin = if (eventBacked) TransferPlanOrigin.Event else TransferPlanOrigin.SnapshotFallback,
            )
        }

    @Suppress("CyclomaticComplexMethod")
    private fun category(
        move: ZoneMove,
        events: List<GameEvent>,
    ): TransferCategory {
        val cardId = move.cardId
        val cast = events.filterIsInstance<GameEvent.SpellCast>().firstOrNull { it.cardId == cardId }
        val resolutions = events.filterIsInstance<GameEvent.SpellResolved>()
        val resolved = resolutions.firstOrNull { it.cardId == cardId }
        val warpResolution =
            move.cause?.let { cause ->
                resolutions.any {
                    it.isTrigger &&
                        it.abilityGrpId == KeywordAbilityIds.WARP_DELAYED_TRIGGER &&
                        it.matches(cause)
                }
            } == true
        val destruction =
            events.filterIsInstance<GameEvent.CardDestroyed>().firstOrNull { it.cardId == cardId }?.destruction
        return when {
            events.any { it is GameEvent.OpeningHandAction && it.cardId == cardId } &&
                move.from == Zone.Hand &&
                move.to == Zone.Battlefield -> TransferCategory.Put
            events.any { it is GameEvent.LandPlayed && it.cardId == cardId } &&
                move.from == Zone.Hand &&
                move.to == Zone.Battlefield -> TransferCategory.PlayLand
            move.to == Zone.Stack && cast?.isAbility != true -> TransferCategory.CastSpell
            move.from == Zone.Stack && resolved?.hasFizzled == true -> TransferCategory.Countered
            move.from == Zone.Stack && move.cause?.api == "Counter" -> TransferCategory.Countered
            move.from == Zone.Battlefield &&
                move.to == Zone.Exile &&
                warpResolution -> TransferCategory.Warp
            move.to == Zone.Exile -> TransferCategory.Exile
            move.from == Zone.Stack && resolved != null -> TransferCategory.Resolve
            events.any { it is GameEvent.LegendRuleDeath && it.cardId == cardId } &&
                move.from == Zone.Battlefield &&
                move.to == Zone.Graveyard -> TransferCategory.SbaLegendRule
            events.any { it is GameEvent.CardSurveiled && it.cardId == cardId } &&
                move.from == Zone.Library &&
                move.to == Zone.Graveyard -> TransferCategory.Surveil
            events.any { it is GameEvent.CardDiscarded && it.cardId == cardId } &&
                move.from == Zone.Hand &&
                move.to == Zone.Graveyard -> TransferCategory.Discard
            events.any { it is GameEvent.CardMilled && it.cardId == cardId } &&
                move.from == Zone.Library &&
                move.to == Zone.Graveyard -> TransferCategory.Mill
            events.any { it is GameEvent.CardBounced && it.cardId == cardId } &&
                move.from == Zone.Battlefield &&
                move.to in setOf(Zone.Hand, Zone.Library) -> TransferCategory.Bounce
            events.any { it is GameEvent.CardSacrificed && it.cardId == cardId } &&
                move.from == Zone.Battlefield &&
                move.to == Zone.Graveyard -> TransferCategory.Sacrifice
            destruction != null &&
                move.from == Zone.Battlefield &&
                move.to == Zone.Graveyard -> TransferCategoryResolver.destructionCategory(destruction)
            move.cause?.api == "Discard" -> TransferCategory.Discard
            move.cause?.api == "Mill" -> TransferCategory.Mill
            move.cause?.api == "Surveil" -> TransferCategory.Surveil
            move.cause?.api == "Draw" -> TransferCategory.Draw
            move.cause?.api == "ChangeZone" && move.from == Zone.Library && move.to == Zone.Hand -> TransferCategory.Put
            else -> TransferCategoryResolver.categoryFromZonePair(move.from, move.to)
        }
    }

    private fun GameEvent.SpellResolved.matches(cause: ZoneMoveCause): Boolean {
        val resolutionIds = setOf(abilityForgeId, rootAbilityForgeId, stackAbilityForgeId) - 0
        val causeIds = setOf(cause.abilityForgeId, cause.rootAbilityForgeId, cause.stackAbilityForgeId) - 0
        return resolutionIds.any(causeIds::contains)
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen") // Only source-bearing operation events participate.
    private fun sourceFromSpecificEvent(
        move: ZoneMove,
        events: List<GameEvent>,
    ): ForgeCardId? =
        events.firstNotNullOfOrNull { event ->
            when (event) {
                is GameEvent.OpeningHandAction -> event.cardId.takeIf { matchesSpecificOperation(event, move) }
                is GameEvent.CardDestroyed -> event.sourceCardId.takeIf { matchesSpecificOperation(event, move) }
                is GameEvent.CardSacrificed -> event.sourceCardId.takeIf { matchesSpecificOperation(event, move) }
                is GameEvent.CardMilled -> event.sourceCardId.takeIf { matchesSpecificOperation(event, move) }
                is GameEvent.CardSurveiled -> event.sourceCardId.takeIf { matchesSpecificOperation(event, move) }
                else -> null
            }
        }

    private fun hasSpecificOperationEvent(
        move: ZoneMove,
        events: List<GameEvent>,
    ): Boolean = events.any { matchesSpecificOperation(it, move) }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen") // Non-zone-operation events cannot match a move.
    private fun matchesSpecificOperation(
        event: GameEvent,
        move: ZoneMove,
    ): Boolean {
        val matchingCard = event.cardIdOrNull() == move.cardId
        if (!matchingCard) return false
        return when (event) {
            is GameEvent.OpeningHandAction -> move.from == Zone.Hand && move.to == Zone.Battlefield
            is GameEvent.LandPlayed -> move.from == Zone.Hand && move.to == Zone.Battlefield
            is GameEvent.SpellCast -> move.to == Zone.Stack
            is GameEvent.SpellResolved -> move.from == Zone.Stack
            is GameEvent.LegendRuleDeath,
            is GameEvent.CardSacrificed,
            is GameEvent.CardDestroyed,
            -> move.from == Zone.Battlefield && move.to == Zone.Graveyard
            is GameEvent.CardSurveiled,
            is GameEvent.CardMilled,
            -> move.from == Zone.Library && move.to == Zone.Graveyard
            is GameEvent.CardDiscarded -> move.from == Zone.Hand && move.to == Zone.Graveyard
            is GameEvent.CardBounced -> move.from == Zone.Battlefield && move.to in setOf(Zone.Hand, Zone.Library)
            is GameEvent.CardExiled -> move.to == Zone.Exile
            else -> false
        }
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen") // Non-zone-operation events have no relevant card id.
    private fun GameEvent.cardIdOrNull(): ForgeCardId? =
        when (this) {
            is GameEvent.OpeningHandAction -> cardId
            is GameEvent.LandPlayed -> cardId
            is GameEvent.SpellCast -> cardId
            is GameEvent.SpellResolved -> cardId
            is GameEvent.LegendRuleDeath -> cardId
            is GameEvent.CardSurveiled -> cardId
            is GameEvent.CardDiscarded -> cardId
            is GameEvent.CardMilled -> cardId
            is GameEvent.CardBounced -> cardId
            is GameEvent.CardExiled -> cardId
            is GameEvent.CardSacrificed -> cardId
            is GameEvent.CardDestroyed -> cardId
            else -> null
        }
}

private fun Zone.matchesProtocolZone(zoneId: Int): Boolean =
    when (this) {
        Zone.Hand -> zoneId == ZoneIds.P1_HAND || zoneId == ZoneIds.P2_HAND
        Zone.Library -> zoneId == ZoneIds.P1_LIBRARY || zoneId == ZoneIds.P2_LIBRARY
        Zone.Graveyard -> zoneId == ZoneIds.P1_GRAVEYARD || zoneId == ZoneIds.P2_GRAVEYARD
        Zone.Battlefield -> zoneId == ZoneIds.BATTLEFIELD
        Zone.Exile -> zoneId == ZoneIds.EXILE
        Zone.Stack -> zoneId == ZoneIds.STACK
        Zone.Command -> zoneId == ZoneIds.COMMAND
        Zone.Sideboard -> zoneId == ZoneIds.P1_SIDEBOARD || zoneId == ZoneIds.P2_SIDEBOARD
        Zone.Other -> false
    }
