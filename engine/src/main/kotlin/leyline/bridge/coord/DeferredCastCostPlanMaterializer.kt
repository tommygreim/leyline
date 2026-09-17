package leyline.bridge.coord

import forge.card.mana.ManaCost
import forge.game.GameActionUtil
import forge.game.card.Card
import forge.game.cost.CostBlight
import forge.game.cost.CostDiscard
import forge.game.cost.CostPayLife
import forge.game.keyword.Keyword
import forge.game.spellability.OptionalCost
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.DeferredCastCostPlan
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.ManaRequirementSpec
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ManaColorMapping
import leyline.game.data.CardData
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.PromptIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/** Forge-thread materialization of deferred-cast values and exact child handles. */
internal object DeferredCastCostPlanMaterializer {
    data class Result(
        val plan: DeferredCastCostPlan,
        val childSelections: Map<Long, RuntimeActionSelection>,
    )

    fun materialize(
        bridge: GameBridge,
        offer: GameActionBridge.ActionOffer,
        nextToken: () -> Long,
    ): Result? {
        val card = (offer.command as? PlayerAction.CastSpell)?.ability?.hostCard ?: return null
        val cardData = bridge.cardRepository.findByGrpId(offer.action.grpId)
        val keywordCount = bridge.abilityRegistryFor(card, cardData)?.slotLayout?.keywordCount ?: 0
        return materialize(offer, cardData, keywordCount, nextToken)
    }

    fun materialize(
        offer: GameActionBridge.ActionOffer,
        cardData: CardData?,
        keywordCount: Int,
        nextToken: () -> Long,
    ): Result? {
        val command = offer.command as? PlayerAction.CastSpell ?: return null
        val ability = command.ability ?: return null
        val card = ability.hostCard ?: return null
        val player = ability.activatingPlayer ?: return null

        val hybrid =
            if (offer.action.alternativeGrpId == 0) {
                val effectiveCost = ActionMapper.computeEffectiveCost(ability, player)
                val paymentColors = effectiveCost?.hybridOrTwoGenericColors().orEmpty()
                if (effectiveCost != null && paymentColors.isNotEmpty()) {
                    val baseCost = ability.payCosts?.totalMana
                    val promptCost = baseCost?.takeIf { it.hybridOrTwoGenericColors().size == paymentColors.size } ?: effectiveCost
                    DeferredCastCostPlan.hybrid(
                        promptCost.hybridOrTwoGenericColors(),
                        paymentColors,
                        promptCost.toManaRequirementSpecs(),
                    )
                } else {
                    null
                }
            } else {
                null
            }

        val optionalCosts = GameActionUtil.getOptionalCostValues(ability)
        val keywordCosts = card.binaryKeywordCosts()
        val optional =
            if (optionalCosts.isEmpty() && keywordCosts.isEmpty()) {
                null
            } else {
                val entries =
                    optionalCosts.mapIndexed { index, cost ->
                        val type = optionalCostType(cost.type)
                        val abilityGrpId =
                            if (cost.type == OptionalCost.Bargain || cost.type == OptionalCost.Teamwork) {
                                card
                                    .findKeywordSlot(cost.type.name, keywordCount)
                                    ?.let { cardData?.abilityIds?.getOrNull(it)?.first }
                                    ?: 0
                            } else {
                                cardData?.abilityIds?.getOrNull(keywordCount + index)?.first ?: 0
                            }
                        DeferredCastCostPlan.OptionalCostEntry(type, abilityGrpId, null)
                    } +
                        keywordCosts.map { name ->
                            val slot = card.findKeywordSlot(name, keywordCount)
                            val abilityGrpId = slot?.let { cardData?.abilityIds?.getOrNull(it)?.first } ?: 0
                            DeferredCastCostPlan.OptionalCostEntry(keywordCostType(name), abilityGrpId, name)
                        }
                DeferredCastCostPlan.optional(entries, cardData?.manaCost.orEmpty())
            }

        val childSelections = linkedMapOf<Long, RuntimeActionSelection>()
        val alternateChoices =
            if (card.keywords.any { it.original.startsWith("AlternateAdditionalCost") } && offer.castCandidates.size > 1) {
                offer.castCandidates
                    .mapIndexed { index, alternateAbility -> index to alternateAbility }
                    .filter { (_, alternateAbility) -> hasUsableAlternateCost(alternateAbility) }
                    .map { (index, alternateAbility) ->
                        val token = nextToken()
                        childSelections[token] =
                            RuntimeActionSelection(
                                offer.copy(
                                    command = command.copy(abilityId = index, ability = alternateAbility),
                                    castCandidates = emptyList(),
                                ),
                                offer.action,
                            )
                        DeferredCastCostPlan.AlternateCostChoice(
                            runtimeToken = token,
                            promptId = promptIdForAdditionalCostBranch(alternateAbility),
                            chosenCostPromptId = chosenCostPromptId(alternateAbility),
                        )
                    }
            } else {
                emptyList()
            }
        val alternate = alternateChoices.takeIf { it.isNotEmpty() }?.let(DeferredCastCostPlan::alternate)
        if (hybrid == null && optional == null && alternate == null) return null

        return Result(
            DeferredCastCostPlan.frozen(command.cardId, offer.action.instanceId, offer.action.grpId, hybrid, optional, alternate),
            childSelections.toMap(),
        )
    }

    private val binaryKeywordCostNames = setOf(Keyword.OFFSPRING, Keyword.CASUALTY, Keyword.CONSPIRE)

    private fun Card.binaryKeywordCosts(): List<String> =
        keywords.mapNotNull { keyword -> keyword.keyword?.takeIf { it in binaryKeywordCostNames }?.toString() }

    private fun optionalCostType(type: OptionalCost): CastingTimeOptionType =
        when (type) {
            OptionalCost.Kicker1, OptionalCost.Kicker2 -> CastingTimeOptionType.Kicker
            OptionalCost.Bargain -> CastingTimeOptionType.Bargain
            OptionalCost.Buyback,
            OptionalCost.Entwine,
            OptionalCost.PromiseGift,
            OptionalCost.Retrace,
            OptionalCost.Jumpstart,
            OptionalCost.Offering,
            OptionalCost.Teamwork,
            OptionalCost.ReduceW,
            OptionalCost.ReduceU,
            OptionalCost.ReduceB,
            OptionalCost.ReduceR,
            OptionalCost.ReduceG,
            OptionalCost.AltCost,
            OptionalCost.Flash,
            OptionalCost.Generic,
            -> CastingTimeOptionType.AdditionalCost
        }

    private fun keywordCostType(keywordName: String): CastingTimeOptionType =
        when (keywordName) {
            Keyword.CASUALTY.toString() -> CastingTimeOptionType.Casualty
            Keyword.CONSPIRE.toString() -> CastingTimeOptionType.Conspire
            else -> CastingTimeOptionType.AdditionalCost
        }

    private fun Card.findKeywordSlot(
        keywordName: String,
        slotBound: Int,
    ): Int? =
        rules
            ?.mainPart
            ?.keywords
            ?.toList()
            ?.withIndex()
            ?.firstOrNull { (index, text) -> index < slotBound && text.startsWith(keywordName) }
            ?.index

    private fun promptIdForAdditionalCostBranch(ability: SpellAbility): Int? {
        val costs = ability.payCosts ?: return null
        // Arena prompt 4160 is the card-specific text "Pay {3}{B}.", not a
        // generic mana-payment label. Leave arbitrary mana-only branches
        // neutral until their exact structured cost can be materialized.
        if (costs.isOnlyManaCost) return null
        val parts = costs.costParts.map { it.javaClass.simpleName }
        // A branch not covered by any case below returns null, not a guess:
        // an imprecise but valid fallback is filled in by the caller, since an
        // unfilled slot here is a client crash (see checkAlternateAdditionalCostChoice).
        return when {
            costs.costParts.any { it is CostBlight } -> PromptIds.CHOOSE_OR_COST_PAY_BLIGHT
            parts.any { it.contains("Sacrifice") } -> PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE
            parts.any { it.contains("Exile") } -> PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE
            costs.costParts
                .filterIsInstance<CostDiscard>()
                .singleOrNull()
                ?.convertAmount() == 1 ->
                PromptIds.CHOOSE_OR_COST_DISCARD
            else ->
                costs.costParts
                    .filterIsInstance<CostPayLife>()
                    .singleOrNull()
                    ?.convertAmount()
                    ?.let { PromptIds.CHOOSE_OR_COST_PAY_LIFE[it] }
        }
    }

    private fun chosenCostPromptId(ability: SpellAbility): Int? =
        PromptIds.CHOOSE_OR_COST_PAY_BLIGHT.takeIf {
            ability.payCosts?.costParts?.any { it is CostBlight } == true
        }

    private fun hasUsableAlternateCost(ability: SpellAbility): Boolean {
        val player = ability.activatingPlayer ?: return false
        return ability.payCosts
            ?.costParts
            ?.filterIsInstance<CostBlight>()
            ?.all { it.canPay(ability, player, false) }
            ?: true
    }

    private fun ManaCost.hybridOrTwoGenericColors(): List<ManaColor> = mapNotNull(ManaColorMapping::fromOrTwoGenericShard)

    private fun ManaCost.toManaRequirementSpecs(): List<ManaRequirementSpec> =
        buildList {
            for (shard in this@toManaRequirementSpecs) {
                val hybrid = ManaColorMapping.fromOrTwoGenericShard(shard)
                val color = hybrid ?: ManaColorMapping.fromShard(shard) ?: continue
                add(ManaRequirementSpec.frozen(if (hybrid == null) listOf(color) else listOf(ManaColor.TwoGeneric, color)))
            }
            if (genericCost > 0) add(ManaRequirementSpec.frozen(listOf(ManaColor.Generic), genericCost))
        }
}
