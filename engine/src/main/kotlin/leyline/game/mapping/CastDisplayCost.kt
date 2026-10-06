package leyline.game.mapping

import forge.card.mana.ManaCost
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.ActionManaCosts
import leyline.game.data.CardData
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement

/**
 * The single definition of an action offer's displayed mana cost.
 *
 * Displayed cost is the printed cost after every cost modification that
 * follows from game state alone, and before every reduction that requires
 * the player to choose which permanents or cards pay (Convoke, Improvise,
 * Delve, Waterbend, Offering, Emerge, Assist). The dividing line is the
 * choice, not the state read — see
 * docs/decisions/0007-displayed-cost-and-controller-contexts.md.
 *
 * [ActionManaCosts.computeEffectiveCost] enforces the rule by running Forge's
 * cost adjustment under a quiet [leyline.bridge.NonInteractiveScope], so no
 * keyword list is needed here. Affordability is a different question and must
 * not route through this object.
 */
internal object CastDisplayCost {
    /**
     * Displayed cost of [sa] for [player], or null when the ability carries
     * no mana cost.
     */
    fun of(
        sa: SpellAbility,
        player: Player,
    ): ManaCost? = ActionManaCosts.computeEffectiveCost(sa, player)

    /**
     * Displayed cost of [sa] as proto mana requirements, each echoing
     * [abilityGrpId] when set.
     *
     * Falls back to the ability's own printed cost when no effective cost is
     * computable, then to [printed] card data — so a null [sa] (no castable
     * ability resolved) still yields the printed cost of the card.
     */
    fun requirements(
        sa: SpellAbility?,
        player: Player,
        printed: CardData?,
        abilityGrpId: Int? = null,
    ): List<ManaRequirement> {
        if (sa != null) {
            val specs = ActionManaCosts.manaCostSpecs(sa.payCosts)
            of(sa, player)?.let {
                return inPrintedColorOrder(ActionManaCosts.forgeManaCostToRequirements(it, abilityGrpId, specs), printed)
            }
            val saCost = sa.payCosts?.totalMana
            if (saCost != null && !saCost.isNoCost) {
                return inPrintedColorOrder(ActionManaCosts.forgeManaCostToRequirements(saCost, abilityGrpId, specs), printed)
            }
        }
        return printedRequirements(printed, abilityGrpId)
    }

    private fun inPrintedColorOrder(
        requirements: List<ManaRequirement>,
        printed: CardData?,
    ): List<ManaRequirement> {
        val colors = printed?.manaCost?.map { it.first } ?: return requirements
        if (requirements.any { it.colorCount != 1 }) return requirements
        return requirements.sortedWith(
            compareBy<ManaRequirement> {
                if (it.colorList.single() == ManaColor.Generic) {
                    -1
                } else {
                    colors.indexOf(it.colorList.single()).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
                }
            }.thenBy { it.colorList.single().number },
        )
    }

    private fun printedRequirements(
        printed: CardData?,
        abilityGrpId: Int?,
    ): List<ManaRequirement> =
        printed?.manaCost.orEmpty().map { (color: ManaColor, count: Int) ->
            val req = ManaRequirement.newBuilder().addColor(color).setCount(count)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            req.build()
        }
}
