package leyline.game.mapping

import forge.card.mana.ManaCost
import leyline.bridge.ActionManaCosts
import leyline.bridge.getPlayableManaAbilities
import leyline.bridge.types.ManaColorMapping
import wotc.mtgo.gre.external.messaging.Messages.AutoTapAction
import wotc.mtgo.gre.external.messaging.Messages.AutoTapSolution
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo
import wotc.mtgo.gre.external.messaging.Messages.ManaPaymentOption
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Builds predictive auto-tap suggestions for payable action costs.
 *
 * Preserve the client-visible mana details here: predictive ids start at 10,
 * snow sources carry `FromSnow`, mana ability ids match the tapped source, and
 * two-generic hybrid costs may be paid either by color or by two generic mana.
 *
 * `X` is not part of that cost. The caster picks it, its minimum is zero, and no
 * source produces "X" mana, so treating it as a colored requirement leaves every
 * X spell unsolvable. That matters beyond auto-tap: the client's
 * `Action.CanAffordToCast` treats a cast action with no `AutoTapSolution` and a
 * cost that is not entirely `X` as unaffordable, and `HighlightUtil` then gives it
 * `HighlightType.None` — so the card never lights up in hand.
 */
internal object ActionAutoTapSupport {
    private const val INITIAL_MANA_ID = 10

    private data class ManaSource(
        val instanceId: Int,
        val color: ManaColor,
        val abilityGrpId: Int,
        val fromSnow: Boolean,
        val kindSpec: ManaSpecType?,
    )

    fun build(
        manaCost: ManaCost,
        context: ActionBuildContext,
    ): AutoTapSolution? =
        if (manaCost.any { ManaColorMapping.fromOrTwoGenericShard(it) != null }) {
            buildOrTwoGenericAutoTapSolution(manaCost, context)
        } else {
            build(ActionManaCosts.forgeManaCostToPairs(manaCost), context)
        }

    @Suppress("CyclomaticComplexMethod")
    private fun build(
        manaCost: List<Pair<ManaColor, Int>>,
        context: ActionBuildContext,
    ): AutoTapSolution? {
        if (manaCost.isEmpty()) return null
        val sources = collectManaSources(context)

        val usedSourceInstanceIds = mutableSetOf<Int>()
        val matched = mutableListOf<Pair<ManaSource, ManaColor>>()
        val coloredReqs = manaCost.filter { it.first != ManaColor.Generic && it.first != ManaColor.X }
        val genericReqs = manaCost.filter { it.first == ManaColor.Generic }

        for ((reqColor, reqCount) in coloredReqs) {
            var remaining = reqCount
            for (src in sources) {
                if (remaining <= 0) break
                if (src.instanceId in usedSourceInstanceIds) continue
                if (canPayRequirement(src, reqColor)) {
                    usedSourceInstanceIds.add(src.instanceId)
                    matched.add(src to src.color)
                    remaining--
                }
            }
            if (remaining > 0) return null
        }

        for ((_, reqCount) in genericReqs) {
            var remaining = reqCount
            for (src in sources) {
                if (remaining <= 0) break
                if (src.instanceId in usedSourceInstanceIds) continue
                usedSourceInstanceIds.add(src.instanceId)
                matched.add(src to src.color)
                remaining--
            }
            if (remaining > 0) return null
        }

        return buildAutoTapSolution(matched)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun buildOrTwoGenericAutoTapSolution(
        manaCost: ManaCost,
        context: ActionBuildContext,
    ): AutoTapSolution? {
        val sources = collectManaSources(context)
        if (sources.isEmpty()) return null

        val coloredRequirements = mutableListOf<ManaColor>()
        val hybridRequirements = mutableListOf<ManaColor>()
        for (shard in manaCost) {
            val hybridColor = ManaColorMapping.fromOrTwoGenericShard(shard)
            if (hybridColor != null) {
                hybridRequirements.add(hybridColor)
            } else {
                ManaColorMapping.fromShard(shard)?.takeIf { it != ManaColor.X }?.let(coloredRequirements::add)
            }
        }

        val used = mutableSetOf<Int>()
        val matched = mutableListOf<Pair<ManaSource, ManaColor>>()

        fun payColor(
            color: ManaColor,
            then: () -> Boolean,
        ): Boolean {
            for (src in sources) {
                if (src.instanceId in used || !canPayRequirement(src, color)) continue
                used.add(src.instanceId)
                matched.add(src to src.color)
                if (then()) return true
                matched.removeAt(matched.lastIndex)
                used.remove(src.instanceId)
            }
            return false
        }

        fun payGeneric(
            needed: Int,
            then: () -> Boolean,
        ): Boolean {
            if (needed == 0) return then()
            for (src in sources) {
                if (src.instanceId in used) continue
                used.add(src.instanceId)
                matched.add(src to src.color)
                if (payGeneric(needed - 1, then)) return true
                matched.removeAt(matched.lastIndex)
                used.remove(src.instanceId)
            }
            return false
        }

        fun payHybrids(index: Int): Boolean {
            if (index == hybridRequirements.size) {
                return payGeneric(manaCost.genericCost) { true }
            }
            val color = hybridRequirements[index]
            return payColor(color) { payHybrids(index + 1) } ||
                payGeneric(2) { payHybrids(index + 1) }
        }

        fun payColored(index: Int): Boolean {
            if (index == coloredRequirements.size) return payHybrids(0)
            return payColor(coloredRequirements[index]) { payColored(index + 1) }
        }

        return if (payColored(0)) buildAutoTapSolution(matched) else null
    }

    private fun canPayRequirement(
        src: ManaSource,
        reqColor: ManaColor,
    ): Boolean =
        if (reqColor == ManaColor.Snow_afc9) {
            src.fromSnow
        } else {
            reqColor == ManaColor.Generic ||
                src.color == ManaColor.Generic ||
                src.color == ManaColor.AnyColor ||
                src.color == reqColor
        }

    private fun collectManaSources(context: ActionBuildContext): List<ManaSource> {
        val sources = mutableListOf<ManaSource>()
        for (card in context.player.getZone(ForgeZoneType.Battlefield).cards) {
            if (card.isTapped) continue
            for (sa in getPlayableManaAbilities(card, context.player)) {
                val colors = ActivatedActionEmitter.producedManaColors(sa)
                if (colors.isEmpty()) continue
                val instanceId = context.instanceId(card)
                val grpId = context.grpId(card)
                val cardData = context.cardData(grpId)
                val registry = context.abilityRegistry(card, cardData)
                val abilityGrpId = registry?.forSpellAbility(sa.definitionId) ?: ActivatedActionEmitter.basicLandAbilityGrpId(card, sa)
                for (color in colors) {
                    sources.add(
                        ManaSource(
                            instanceId,
                            color,
                            abilityGrpId,
                            fromSnow = card.type.isSnow,
                            kindSpec = ActivatedActionEmitter.sourceKindSpec(card),
                        ),
                    )
                }
            }
        }
        return sources
    }

    private fun buildAutoTapSolution(matched: List<Pair<ManaSource, ManaColor>>): AutoTapSolution {
        val builder = AutoTapSolution.newBuilder()
        var manaIdCounter = INITIAL_MANA_ID
        for ((src, payingColor) in matched) {
            val manaId = manaIdCounter++
            val manaInfo =
                ManaInfo
                    .newBuilder()
                    .setManaId(manaId)
                    .setColor(payingColor)
                    .setSrcInstanceId(src.instanceId)
                    .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                    .setAbilityGrpId(src.abilityGrpId)
                    .setCount(1)
            if (src.fromSnow) {
                manaInfo.addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.FromSnow))
            }
            src.kindSpec?.let { manaInfo.addSpecs(ManaInfo.Spec.newBuilder().setType(it)) }
            builder.addAutoTapActions(
                AutoTapAction
                    .newBuilder()
                    .setInstanceId(src.instanceId)
                    .setAbilityGrpId(src.abilityGrpId)
                    .setManaPaymentOption(
                        ManaPaymentOption.newBuilder().addMana(manaInfo),
                    ),
            )
        }
        return builder.build()
    }
}
