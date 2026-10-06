package leyline.game.mapping

import forge.card.mana.ManaCost
import forge.game.spellability.SpellAbility
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
        val sourceSpecs: List<ManaSpecType>,
        val count: Int = 1,
    )

    fun build(
        manaCost: ManaCost,
        context: ActionBuildContext,
        ability: SpellAbility? = null,
    ): AutoTapSolution? =
        if (ability != null && !manaCost.any { it.isPhyrexian() }) {
            buildFromForgePlan(manaCost, context, ability)
        } else if (manaCost.any { it.isPhyrexian() }) {
            buildWithPhyrexian(manaCost, context)
        } else if (manaCost.any { ManaColorMapping.fromOrTwoGenericShard(it) != null }) {
            buildOrTwoGenericAutoTapSolution(manaCost, context)
        } else {
            build(ActionManaCosts.forgeManaCostToPairs(manaCost), context)
        }

    private fun buildFromForgePlan(
        manaCost: ManaCost,
        context: ActionBuildContext,
        ability: SpellAbility,
    ): AutoTapSolution? {
        // The engine's dry run understands production amounts, floating mana,
        // and restrictions tied to the spell being paid for. It never taps or
        // sacrifices the selected sources.
        val preview = ActionManaCosts.predictManaPayment(manaCost, ability, context.player) ?: return null
        val plan = preview.sources
        // Forge's dry-run API returns source abilities, not the chosen color
        // for a flexible source. Preserve the color-aware predictor there
        // rather than claiming that its first available color was selected.
        if (plan.any { manaAbility ->
                val colors = ActivatedActionEmitter.producedManaColors(manaAbility)
                colors.size != 1 || ManaColor.AnyColor in colors
            }
        ) {
            return build(ActionManaCosts.forgeManaCostToPairs(preview.cost), context, plan.toSet())
        }
        val sources =
            plan.mapNotNull { manaAbility ->
                val card = manaAbility.hostCard ?: return@mapNotNull null
                val color = ActivatedActionEmitter.producedManaColors(manaAbility).firstOrNull() ?: return@mapNotNull null
                val registry = context.abilityRegistry(card, context.cardData(context.grpId(card)))
                ManaSource(
                    context.instanceId(card),
                    color,
                    registry?.forSpellAbility(manaAbility.definitionId) ?: ActivatedActionEmitter.basicLandAbilityGrpId(card, manaAbility),
                    card.type.isSnow,
                    ActivatedActionEmitter.sourceKindSpec(card),
                    ActivatedActionEmitter.manaSourceSpecs(manaAbility),
                    manaAbility.amountOfManaGenerated(false).coerceAtLeast(1),
                ) to color
            }
        return buildAutoTapSolution(sources)
    }

    /**
     * Build a source-only auto-tap projection for Phyrexian costs.  Life is an
     * implicit payment source, so a Phyrexian pip may recurse without consuming
     * a battlefield source.  The actual life payment is applied later from the
     * ManaType choice stash in CostPaymentCoordinator.
     */
    private fun buildWithPhyrexian(
        manaCost: ManaCost,
        context: ActionBuildContext,
    ): AutoTapSolution? {
        val sources = collectManaSources(context)
        val shards = manaCost.toList()
        val used = mutableSetOf<Int>()
        val matched = mutableListOf<Pair<ManaSource, ManaColor>>()

        fun paySource(
            color: ManaColor,
            then: () -> Boolean,
        ): Boolean {
            for (source in sources) {
                if (source.instanceId in used || !canPayRequirement(source, color)) continue
                used.add(source.instanceId)
                matched.add(source to source.color)
                if (then()) return true
                matched.removeAt(matched.lastIndex)
                used.remove(source.instanceId)
            }
            return false
        }

        fun payGeneric(
            count: Int,
            then: () -> Boolean,
        ): Boolean {
            if (count == 0) return then()
            for (source in sources) {
                if (source.instanceId in used) continue
                used.add(source.instanceId)
                matched.add(source to source.color)
                if (payGeneric(count - 1, then)) return true
                matched.removeAt(matched.lastIndex)
                used.remove(source.instanceId)
            }
            return false
        }

        fun payPip(index: Int): Boolean {
            if (index == shards.size) return payGeneric(manaCost.genericCost) { true }
            val shard = shards[index]
            if (shard == forge.card.mana.ManaCostShard.X || shard == forge.card.mana.ManaCostShard.GENERIC) {
                return payPip(index + 1)
            }
            if (shard.isPhyrexian()) {
                val colors = ManaColorMapping.phyrexianColors(shard).dropLast(1)
                return colors.any { color -> paySource(color) { payPip(index + 1) } } || payPip(index + 1)
            }
            if (shard.isOr2Generic()) {
                val color = ManaColorMapping.fromOrTwoGenericShard(shard) ?: return false
                return paySource(color) { payPip(index + 1) } || payGeneric(2) { payPip(index + 1) }
            }
            val color = ManaColorMapping.fromShard(shard) ?: return false
            return paySource(color) { payPip(index + 1) }
        }

        return if (payPip(0)) buildAutoTapSolution(matched) else null
    }

    @Suppress("CyclomaticComplexMethod")
    private fun build(
        manaCost: List<Pair<ManaColor, Int>>,
        context: ActionBuildContext,
        plannedAbilities: Set<SpellAbility>? = null,
    ): AutoTapSolution? {
        if (manaCost.isEmpty()) return null
        val sources = collectManaSources(context, plannedAbilities)

        val usedSourceInstanceIds = mutableSetOf<Int>()
        val matched = mutableListOf<Pair<ManaSource, ManaColor>>()
        val coloredRequirements =
            manaCost
                .filter { it.first != ManaColor.Generic && it.first != ManaColor.X }
                .flatMap { (color, count) -> List(count) { color } }
        val genericNeeded = manaCost.filter { it.first == ManaColor.Generic }.sumOf { it.second }
        if (coloredRequirements.size + genericNeeded > sources.map { it.instanceId }.distinct().size) return null

        // A flexible source can satisfy an earlier pip but be the only source
        // for a later one. Greedy first-fit then reports an affordable spell as
        // unpayable, leaving it unhighlighted even though Forge can cast it.
        fun assign(index: Int): Boolean {
            if (index == coloredRequirements.size) {
                val remaining = sources.distinctBy { it.instanceId }.filterNot { it.instanceId in usedSourceInstanceIds }
                if (remaining.size < genericNeeded) return false
                remaining.take(genericNeeded).forEach { matched.add(it to it.color) }
                return true
            }
            val requirement = coloredRequirements[index]
            for (source in sources) {
                if (source.instanceId in usedSourceInstanceIds || !canPayRequirement(source, requirement)) continue
                usedSourceInstanceIds.add(source.instanceId)
                matched.add(source to if (source.color == ManaColor.AnyColor) requirement else source.color)
                if (assign(index + 1)) return true
                matched.removeAt(matched.lastIndex)
                usedSourceInstanceIds.remove(source.instanceId)
            }
            return false
        }

        return if (assign(0)) buildAutoTapSolution(matched) else null
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

    private fun collectManaSources(
        context: ActionBuildContext,
        plannedAbilities: Set<SpellAbility>? = null,
    ): List<ManaSource> {
        val sources = mutableListOf<ManaSource>()
        for (card in context.player.getCardsIn(ForgeZoneType.Battlefield)) {
            if (card.isTapped) continue
            for (sa in getPlayableManaAbilities(card, context.player)) {
                if (plannedAbilities != null && sa !in plannedAbilities) continue
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
                            sourceSpecs = ActivatedActionEmitter.manaSourceSpecs(sa),
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
                    .setCount(src.count)
            if (src.fromSnow) {
                manaInfo.addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.FromSnow))
            }
            src.sourceSpecs.forEach { spec ->
                manaInfo.addSpecs(ManaInfo.Spec.newBuilder().setType(spec))
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
