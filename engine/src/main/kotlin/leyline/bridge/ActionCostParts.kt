package leyline.bridge

import forge.game.cost.CostDiscard
import forge.game.cost.CostExile
import forge.game.cost.CostPayLife
import forge.game.cost.CostSacrifice
import forge.game.cost.CostTap
import forge.game.cost.CostUntap
import wotc.mtgo.gre.external.messaging.Messages.Cost
import wotc.mtgo.gre.external.messaging.Messages.CostType
import wotc.mtgo.gre.external.messaging.Messages.EffectCost
import wotc.mtgo.gre.external.messaging.Messages.LifeCost
import forge.game.cost.Cost as ForgeCost

/**
 * Non-mana cost preview for [wotc.mtgo.gre.external.messaging.Messages.Action.costs].
 *
 * `Cost.type` pairs with a oneof (`manaCost`/`effectCost`/`lifeCost`/`loyaltyCost`/
 * `orCost`/`andCost`); before this, the only variant ever built anywhere in
 * the engine was `manaCost`. This covers the self-referential cost kinds
 * `CostType` has a dedicated wire shape for — tap/sacrifice/exile/untap the
 * source, pay life, and a fixed-number sacrifice of another permanent
 * (`Effect`) — so a non-mana activation or spell cost gets *some* preview
 * instead of none.
 * Every other Forge `CostPart` (draw, mill, reveal, discard-a-different-card,
 * ...) has no corresponding `CostType` value and is left out rather than
 * guessed at. See ISSUES.md I37.
 */
internal object ActionCostParts {
    fun of(payCosts: ForgeCost?): List<Cost> {
        if (payCosts == null) return emptyList()
        var nextId = 1
        return payCosts.costParts.mapNotNull { part ->
            val builder =
                when {
                    part is CostTap -> Cost.newBuilder().setType(CostType.TapSelf).setEffectCost(effectCost(part))
                    part is CostUntap -> Cost.newBuilder().setType(CostType.UntapSelf).setEffectCost(effectCost(part))
                    part is CostSacrifice && part.payCostFromSource() ->
                        Cost.newBuilder().setType(CostType.SacSelf).setEffectCost(effectCost(part))
                    part is CostSacrifice && part.convertAmount() != null ->
                        Cost.newBuilder().setType(CostType.Effect).setEffectCost(effectCost(part))
                    part is CostExile && part.payCostFromSource() ->
                        Cost.newBuilder().setType(CostType.ExileSelf).setEffectCost(effectCost(part))
                    part is CostDiscard && part.payCostFromSource() ->
                        Cost.newBuilder().setType(CostType.DiscardSelf).setEffectCost(effectCost(part))
                    part is CostPayLife ->
                        Cost.newBuilder().setType(CostType.Life).setLifeCost(LifeCost.newBuilder().setCount(part.convertAmount() ?: 0))
                    else -> null
                } ?: return@mapNotNull null
            builder.setId(nextId++).build()
        }
    }

    private fun effectCost(part: forge.game.cost.CostPart): EffectCost.Builder = EffectCost.newBuilder().setCount(part.convertAmount() ?: 1)
}
