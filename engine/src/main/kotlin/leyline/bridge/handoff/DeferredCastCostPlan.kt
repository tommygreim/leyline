package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.AutoTapSolution
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.util.Collections

/** Immutable client-prompt facts and opaque runtime choices for one offered cast. */
@ConsistentCopyVisibility
internal data class DeferredCastCostPlan private constructor(
    val sourceCardId: ForgeCardId,
    val instanceId: Int,
    val grpId: Int,
    val hybrid: HybridManaPlan?,
    val optional: OptionalCostPlan?,
    val alternate: AlternateCostPlan?,
) {
    @ConsistentCopyVisibility
    data class HybridManaPlan private constructor(
        val promptColors: List<ManaColor>,
        val paymentColors: List<ManaColor>,
        /** The non-mana alternative for each prompt/payment pip (2 or P). */
        val promptManaTypes: List<ManaColor>,
        val paymentManaTypes: List<ManaColor>,
        /** Additional coloured alternatives for multi-colour Phyrexian pips. */
        val promptColorOptions: List<List<ManaColor>>,
        val paymentColorOptions: List<List<ManaColor>>,
        val manaCost: List<ManaRequirementSpec>,
    ) {
        companion object {
            fun frozen(
                promptColors: List<ManaColor>,
                paymentColors: List<ManaColor>,
                manaCost: List<ManaRequirementSpec>,
                promptManaTypes: List<ManaColor> = promptColors.map { ManaColor.TwoGeneric },
                paymentManaTypes: List<ManaColor> = paymentColors.map { ManaColor.TwoGeneric },
                promptColorOptions: List<List<ManaColor>> = promptColors.map(::listOf),
                paymentColorOptions: List<List<ManaColor>> = paymentColors.map(::listOf),
            ) = HybridManaPlan(
                frozenList(promptColors),
                frozenList(paymentColors),
                frozenList(promptManaTypes),
                frozenList(paymentManaTypes),
                frozenNestedList(promptColorOptions),
                frozenNestedList(paymentColorOptions),
                frozenList(manaCost.map { ManaRequirementSpec.frozen(it.colors, it.count) }),
            )
        }
    }

    @ConsistentCopyVisibility
    data class OptionalCostPlan private constructor(
        val entries: List<OptionalCostEntry>,
        val baseManaCost: List<Pair<ManaColor, Int>>,
        val baseAutoTapSolution: AutoTapSolution?,
    ) {
        companion object {
            fun frozen(
                entries: List<OptionalCostEntry>,
                baseManaCost: List<Pair<ManaColor, Int>>,
                baseAutoTapSolution: AutoTapSolution? = null,
            ) = OptionalCostPlan(
                frozenList(entries.map { it.copy(manaCost = it.manaCost?.let(::frozenList)) }),
                frozenList(baseManaCost),
                baseAutoTapSolution,
            )
        }
    }

    data class OptionalCostEntry(
        val type: CastingTimeOptionType,
        val abilityGrpId: Int,
        val keywordName: String?,
        val manaCost: List<Pair<ManaColor, Int>>? = null,
        val isAffordable: Boolean = true,
        val autoTapSolution: AutoTapSolution? = null,
    )

    @ConsistentCopyVisibility
    data class AlternateCostPlan private constructor(
        val choices: List<AlternateCostChoice>,
    ) {
        companion object {
            fun frozen(choices: List<AlternateCostChoice>) = AlternateCostPlan(frozenList(choices))
        }
    }

    data class AlternateCostChoice(
        val runtimeToken: Long,
        val promptId: Int?,
        val chosenCostPromptId: Int? = null,
    )

    companion object {
        fun frozen(
            sourceCardId: ForgeCardId,
            instanceId: Int,
            grpId: Int,
            hybrid: HybridManaPlan?,
            optional: OptionalCostPlan?,
            alternate: AlternateCostPlan?,
        ): DeferredCastCostPlan =
            DeferredCastCostPlan(
                sourceCardId,
                instanceId,
                grpId,
                hybrid?.let {
                    HybridManaPlan.frozen(
                        frozenList(it.promptColors),
                        frozenList(it.paymentColors),
                        frozenList(it.manaCost.map { requirement -> ManaRequirementSpec.frozen(requirement.colors, requirement.count) }),
                        frozenList(it.promptManaTypes),
                        frozenList(it.paymentManaTypes),
                        frozenNestedList(it.promptColorOptions),
                        frozenNestedList(it.paymentColorOptions),
                    )
                },
                optional?.let { OptionalCostPlan.frozen(it.entries, it.baseManaCost, it.baseAutoTapSolution) },
                alternate?.let { AlternateCostPlan.frozen(it.choices) },
            )

        fun hybrid(
            promptColors: List<ManaColor>,
            paymentColors: List<ManaColor>,
            manaCost: List<ManaRequirementSpec>,
            promptManaTypes: List<ManaColor> = promptColors.map { ManaColor.TwoGeneric },
            paymentManaTypes: List<ManaColor> = paymentColors.map { ManaColor.TwoGeneric },
            promptColorOptions: List<List<ManaColor>> = promptColors.map(::listOf),
            paymentColorOptions: List<List<ManaColor>> = paymentColors.map(::listOf),
        ): HybridManaPlan =
            HybridManaPlan.frozen(
                promptColors,
                paymentColors,
                manaCost,
                promptManaTypes,
                paymentManaTypes,
                promptColorOptions,
                paymentColorOptions,
            )

        fun optional(
            entries: List<OptionalCostEntry>,
            baseManaCost: List<Pair<ManaColor, Int>>,
            baseAutoTapSolution: AutoTapSolution? = null,
        ): OptionalCostPlan = OptionalCostPlan.frozen(entries, baseManaCost, baseAutoTapSolution)

        fun alternate(choices: List<AlternateCostChoice>): AlternateCostPlan = AlternateCostPlan.frozen(choices)

        private fun <T> frozenList(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

        private fun <T> frozenNestedList(values: List<List<T>>): List<List<T>> =
            Collections.unmodifiableList(values.map { Collections.unmodifiableList(it.toList()) })
    }
}
