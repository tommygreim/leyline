package leyline.game.bundle

import leyline.bridge.handoff.ManaRequirementSpec
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.*

/** Pure proto builders for CastingTimeOptionsReq prompt variants. */
object CastingTimeOptionsBuilder {
    /** A modal option's emitted grpId and its `+ {cost}`; empty cost means free. */
    data class ModalOptionSpec(
        val grpId: Int,
        val cost: List<Pair<ManaColor, Int>> = emptyList(),
    )

    /**
     * Build a [ModalReq] + [CastingTimeOptionsReq] proto for a modal prompt.
     *
     * Per-mode `modeCost` and `excludedOptions` are populated when supplied.
     *
     * @param parentGrpId the abilityGrpId of the modal ability
     * @param modalOptions pickable modal options in render order
     * @param excludedOptions modes that exist on the card but aren't pickable now
     * @param sourceInstanceId the instanceId for affectedId/affectorId
     * @param grpId the grpId for the CTO entry (card grpId for spells, ability grpId for triggers)
     * @param ctoId CTO identifier (1-2 for spell-time, 3 for triggered abilities)
     * @param playerIdToPrompt seat number to prompt (null omits the field)
     */
    @Suppress("LongParameterList") // Each param maps to one explicit proto field; bundling into a struct just renames the bag.
    fun buildModalCastingTimeOptionsReq(
        parentGrpId: Int,
        modalOptions: List<ModalOptionSpec>,
        minSel: Int,
        maxSel: Int,
        sourceInstanceId: Int,
        grpId: Int,
        ctoId: Int = 2,
        playerIdToPrompt: Int? = null,
        excludedOptions: List<ModalOptionSpec> = emptyList(),
    ): CastingTimeOptionsReq {
        val modalReq =
            ModalReq
                .newBuilder()
                .setAbilityGrpId(parentGrpId)
                .setMinSel(minSel)
                .setMaxSel(maxSel)
        var modeCostId = 1
        for (option in modalOptions) {
            val opt = ModalOption.newBuilder().setGrpId(option.grpId)
            option.cost.forEach { (color, count) ->
                opt.addModeCost(buildManaCost(color, count, modeCostId++))
            }
            modalReq.addModalOptions(opt)
        }
        for (option in excludedOptions) {
            val opt = ModalOption.newBuilder().setGrpId(option.grpId)
            option.cost.forEach { (color, count) ->
                opt.addModeCost(buildManaCost(color, count, modeCostId++))
            }
            modalReq.addExcludedOptions(opt)
        }
        val ctoBuilder =
            CastingTimeOptionReq
                .newBuilder()
                .setCtoId(ctoId)
                .setCastingTimeOptionType(CastingTimeOptionType.Modal_a7b4)
                .setAffectedId(sourceInstanceId)
                .setAffectorId(sourceInstanceId)
                .setGrpId(grpId)
                .setIsRequired(true)
                .setModalReq(modalReq)
        if (playerIdToPrompt != null) {
            ctoBuilder.setPlayerIdToPrompt(playerIdToPrompt)
        }
        return CastingTimeOptionsReq
            .newBuilder()
            .addCastingTimeOptionReq(ctoBuilder)
            .build()
    }

    /** Build a single-color [Cost] message for a `+ {cost}` mode entry. */
    private fun buildManaCost(
        color: ManaColor,
        count: Int,
        id: Int,
    ): Cost =
        Cost
            .newBuilder()
            .setId(id)
            .setType(CostType.Mana)
            .setManaCost(
                ManaCost
                    .newBuilder()
                    .addColor(color)
                    .setCount(count),
            ).build()

    /**
     * Build a [CastingTimeOptionsReq] for optional costs.
     *
     * `playerIdToPrompt` and `baseManaCost` (with `objectId = instanceId`) are
     * populated on every entry including Done; some renderers require both.
     */
    fun buildOptionalCostCastingTimeOptionsReq(
        instanceId: Int,
        optionalCosts: List<Pair<CastingTimeOptionType, Int>>,
        playerIdToPrompt: Int,
        baseManaCost: List<Pair<ManaColor, Int>>,
        optionManaCosts: List<List<Pair<ManaColor, Int>>?> = emptyList(),
    ): Pair<CastingTimeOptionsReq, List<Int>> {
        val manaRequirements =
            baseManaCost.map { (color, count) ->
                ManaRequirement
                    .newBuilder()
                    .addColor(color)
                    .setCount(count)
                    .setObjectId(instanceId)
                    .build()
            }
        val ctoReqBuilder = CastingTimeOptionsReq.newBuilder()
        val costCtoIds = mutableListOf<Int>()
        for ((i, cost) in optionalCosts.withIndex()) {
            val ctoId = i + 1
            costCtoIds.add(ctoId)
            ctoReqBuilder.addCastingTimeOptionReq(
                CastingTimeOptionReq
                    .newBuilder()
                    .setCtoId(ctoId)
                    .setCastingTimeOptionType(cost.first)
                    .setAffectedId(instanceId)
                    .setAffectorId(instanceId)
                    .setGrpId(cost.second)
                    .setPlayerIdToPrompt(playerIdToPrompt)
                    .addAllManaCost(
                        optionManaCosts.getOrNull(i)?.map { (color, count) ->
                            ManaRequirement
                                .newBuilder()
                                .addColor(color)
                                .setCount(count)
                                .setObjectId(instanceId)
                                .build()
                        } ?: manaRequirements,
                    ),
            )
        }
        ctoReqBuilder.addCastingTimeOptionReq(
            CastingTimeOptionReq
                .newBuilder()
                .setCtoId(0)
                .setCastingTimeOptionType(CastingTimeOptionType.Done)
                .setIsRequired(true)
                .setPlayerIdToPrompt(playerIdToPrompt)
                .addAllManaCost(manaRequirements),
        )
        return Pair(ctoReqBuilder.build(), costCtoIds)
    }

    fun buildManaTypeCastingTimeOptionsReq(
        instanceId: Int,
        grpId: Int,
        playerIdToPrompt: Int,
        hybridColors: List<ManaColor>,
        manaCost: List<ManaRequirementSpec>,
        manaTypes: List<ManaColor> = hybridColors.map { ManaColor.TwoGeneric },
        colorOptions: List<List<ManaColor>> = hybridColors.map(::listOf),
    ): Pair<CastingTimeOptionsReq, List<Int>> {
        val manaRequirements = manaCost.map { it.toProto(instanceId) }
        val ctoReqBuilder = CastingTimeOptionsReq.newBuilder()
        val ctoIds = mutableListOf<Int>()
        for ((index, color) in hybridColors.withIndex()) {
            val ctoId = index + 2
            ctoIds.add(ctoId)
            val alternative = manaTypes.getOrNull(index) ?: ManaColor.TwoGeneric
            val options = colorOptions.getOrNull(index).orEmpty().ifEmpty { listOf(color) }
            val requestColors =
                if (alternative == ManaColor.Phyrexian_afc9) {
                    options + alternative
                } else {
                    listOf(alternative) + options
                }
            ctoReqBuilder.addCastingTimeOptionReq(
                CastingTimeOptionReq
                    .newBuilder()
                    .setCtoId(ctoId)
                    .setCastingTimeOptionType(CastingTimeOptionType.ManaType)
                    .setAffectedId(instanceId)
                    .setAffectorId(instanceId)
                    .setGrpId(grpId)
                    .setPlayerIdToPrompt(playerIdToPrompt)
                    .setIsRequired(true)
                    .setSelectManaTypeReq(
                        SelectManaTypeReq
                            .newBuilder()
                            .addAllManaColors(requestColors.distinct())
                            .setSourceId(instanceId),
                    ).addAllManaCost(manaRequirements),
            )
        }
        return ctoReqBuilder.build() to ctoIds
    }

    private fun ManaRequirementSpec.toProto(objectId: Int): ManaRequirement =
        ManaRequirement
            .newBuilder()
            .addAllColor(colors)
            .setCount(count)
            .setObjectId(objectId)
            .build()

    fun buildChooseOrCostCastingTimeOptionsReq(
        instanceId: Int,
        grpId: Int,
        playerIdToPrompt: Int,
        optionCount: Int,
        optionPromptIds: List<Int> = emptyList(),
    ): Pair<CastingTimeOptionsReq, List<Int>> {
        val ctoId = 2
        val selectPrompt =
            Prompt
                .newBuilder()
                .setPromptId(if (optionPromptIds.isNotEmpty()) PromptIds.CHOOSE_OR_COST else PromptIds.SELECT_N)
                .apply {
                    optionPromptIds.forEach { promptId ->
                        addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("Cost")
                                .setType(ParameterType.PromptId)
                                .setPromptId(promptId),
                        )
                    }
                }.build()
        val selectNReq =
            SelectNReq
                .newBuilder()
                .setMinSel(1)
                .setMaxSel(1)
                .setListType(SelectionListType.Dynamic)
                .setIdType(IdType.PromptParameterIndex)
                .setValidationType(SelectionValidationType.NonRepeatable)
                .setSourceId(instanceId)
                .setPrompt(selectPrompt)
                .apply {
                    repeat(optionCount) { index -> addIds(index + 1) }
                }.build()

        val req =
            CastingTimeOptionsReq
                .newBuilder()
                .addCastingTimeOptionReq(
                    CastingTimeOptionReq
                        .newBuilder()
                        .setCtoId(ctoId)
                        .setCastingTimeOptionType(CastingTimeOptionType.ChooseOrCost)
                        .setAffectedId(instanceId)
                        .setAffectorId(instanceId)
                        .setGrpId(grpId)
                        .setPlayerIdToPrompt(playerIdToPrompt)
                        .setIsRequired(true)
                        .setSelectNReq(selectNReq),
                ).build()
        return req to (1..optionCount).toList()
    }
}
