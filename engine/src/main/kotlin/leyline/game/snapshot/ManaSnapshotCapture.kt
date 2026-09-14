package leyline.game.snapshot

import forge.card.MagicColor
import forge.game.card.Card
import forge.game.player.Player
import leyline.bridge.types.ManaColorMapping
import leyline.game.data.KeywordAbilityIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType

internal object ManaSnapshotCapture {
    private const val INITIAL_MANA_ID = 10

    fun capturePool(
        player: Player,
        bridge: GameBridge,
    ): List<ManaPoolEntry> {
        data class PoolKey(
            val color: ManaColor,
            val srcInstanceId: Int,
            val abilityGrpId: Int,
            val specs: List<ManaSpecType>,
        )

        val counts = linkedMapOf<PoolKey, Int>()
        for (mana in player.manaPool) {
            val color = ManaColorMapping.fromProduced(MagicColor.toShortString(mana.color)) ?: continue
            val source = mana.sourceCard ?: continue
            val srcInstanceId = bridge.instanceId(source)
            val sourceGrpId = bridge.resolveGrpId(source, srcInstanceId)
            val cardData = bridge.cardRepository.findByGrpId(sourceGrpId)
            val manaAbility = mana.manaAbility
            val abilityDefinitionId = manaAbility?.sourceSA?.definitionId ?: 0
            val abilityGrpId =
                if (abilityDefinitionId != 0) {
                    bridge.abilityRegistryFor(source, cardData)?.forSpellAbility(abilityDefinitionId) ?: 0
                } else {
                    0
                }.takeIf { it != 0 }
                    ?: if (manaAbility?.isCombatMana == true) {
                        bridge.cardRepository.findKeywordAbilityGrpId(sourceGrpId, KeywordAbilityIds.FIREBENDING) ?: 0
                    } else {
                        0
                    }
            val key = PoolKey(color, srcInstanceId, abilityGrpId, manaSpecs(manaAbility))
            counts[key] = (counts[key] ?: 0) + 1
        }
        var nextManaId = INITIAL_MANA_ID
        return counts.map { (key, count) ->
            ManaPoolEntry(
                manaId = nextManaId++,
                color = key.color,
                srcInstanceId = key.srcInstanceId,
                abilityGrpId = key.abilityGrpId,
                count = count,
                specs = key.specs,
            )
        }
    }

    private fun manaSpecs(manaAbility: forge.game.spellability.AbilityManaPart?): List<ManaSpecType> =
        if (manaAbility?.isCombatMana == true || manaAbility?.isPersistentMana == true) {
            listOf(ManaSpecType.DoesNotEmpty)
        } else {
            emptyList()
        }

    fun captureProductionColors(
        card: Card,
        onBattlefield: Boolean,
    ): List<Int> {
        if (!onBattlefield) return emptyList()
        // Only the ability's own conditions (e.g. "Activate only if you control an Island"),
        // not canPlay: that also fails while the card is tapped for a payment.
        return card.manaAbilities
            .filter { sa -> sa.restrictions.checkOtherRestrictions(card, sa, card.controller) }
            .flatMap { sa ->
                val mana = sa.manaPart ?: return@flatMap emptyList()
                val produced = if (mana.isComboMana) mana.getComboColors(sa) else mana.origProduced
                produced.split(" ").mapNotNull { ManaColorMapping.fromProduced(it)?.number }
            }.distinct()
    }
}
