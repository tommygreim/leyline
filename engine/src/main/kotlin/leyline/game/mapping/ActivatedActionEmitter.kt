package leyline.game.mapping

import forge.card.mana.ManaCost
import forge.game.card.Card
import forge.game.cost.CostTap
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.ActionAvailability
import leyline.bridge.ActionCostParts
import leyline.bridge.ActionManaCosts
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.getPlayableManaAbilities
import leyline.game.data.BasicLandAbilities
import leyline.game.data.CardData
import leyline.game.state.AbilityRegistry
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AutoTapSolution
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaColorCount
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo
import wotc.mtgo.gre.external.messaging.Messages.ManaPaymentOption
import wotc.mtgo.gre.external.messaging.Messages.ManaSelection
import wotc.mtgo.gre.external.messaging.Messages.ManaSelectionOption
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType

/**
 * Emits activated-action families after [ActionMapper] selects eligible sources.
 *
 * The envelope is part of the action shape contract: battlefield activations
 * include source identity and may stop priority, while hand/graveyard-style
 * ability-only activations omit source identity fields. Mana abilities keep
 * their predictive mana options and unique ability ids in this seam.
 */
internal object ActivatedActionEmitter {
    private const val INITIAL_MANA_ID = 10
    private const val INITIAL_UNIQUE_ABILITY_ID = 50

    enum class Envelope(
        val includesSourceIdentity: Boolean,
        val activeShouldStop: Boolean,
        val activeManaCost: Boolean,
    ) {
        PERMANENT_SOURCE(includesSourceIdentity = true, activeShouldStop = true, activeManaCost = true),
        ABILITY_ONLY(includesSourceIdentity = false, activeShouldStop = false, activeManaCost = true),
    }

    data class ManaAction(
        val action: Action,
        val abilityIndex: Int,
        val ability: SpellAbility,
    )

    @Suppress("LongParameterList")
    fun emitPlayableNonManaActivatedAbilities(
        builder: ActionsAvailableReq.Builder,
        card: Card,
        player: Player,
        instanceId: () -> Int,
        grpId: (Card) -> Int,
        cardData: (Int) -> CardData?,
        envelope: Envelope,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
        autoTapSolution: (ManaCost) -> AutoTapSolution? = { null },
        skipSpecialTurnFaceUp: Boolean = false,
        onActive: (Action, Int, SpellAbility, Int) -> Unit = { _, _, _, _ -> },
        abilities: List<SpellAbility> = getNonManaActivatedAbilities(card, player),
    ) {
        for ((abilityIndex, ability) in abilities.withIndex()) {
            if (!ability.canPlay()) continue
            if (skipSpecialTurnFaceUp && ability.isTurnFaceUp) continue
            val canPay = ActionAvailability.canExecute(ability, player)
            val abilityCost = CastDisplayCost.of(ability, player) ?: ability.payCosts?.totalMana
            val autoTap =
                if (canPay && abilityCost != null && !abilityCost.isNoCost) {
                    autoTapSolution(abilityCost)
                } else {
                    null
                }
            val actionInstanceId = instanceId()
            val actionGrpId = grpId(card)
            val actionCardData = cardData(actionGrpId)
            val identityCard = ability.grantorStatic?.hostCard ?: card
            val identityCardData = cardData(grpId(identityCard))
            val registry = abilityRegistryLookup(identityCard, identityCardData)
            val abilityGrpId = registry?.forSpellAbility(ability) ?: 0
            val grantedIndex =
                ability
                    .takeIf { it.grantorStatic != null }
                    ?.let { abilities.take(abilityIndex).count { prior -> prior.grantorStatic != null } }
            emitActivatedAbilityAction(
                builder = builder,
                instanceId = actionInstanceId,
                grpId = actionGrpId,
                abilityGrpId = abilityGrpId,
                uniqueAbilityId = uniqueAbilityIdFor(actionCardData, abilityGrpId, grantedIndex = grantedIndex),
                abilityCost = abilityCost,
                autoTapSolution = autoTap,
                canPay = canPay,
                envelope = envelope,
                nonManaCosts = ability.payCosts,
                onActive = { action -> onActive(action, abilityIndex, ability, abilityGrpId) },
            )
        }
    }

    @Suppress("LongParameterList") // protocol identity, cost, and exact-source callback form one action emission.
    fun emitActivatedAbilityAction(
        builder: ActionsAvailableReq.Builder,
        instanceId: Int,
        grpId: Int,
        abilityGrpId: Int,
        uniqueAbilityId: Int?,
        abilityCost: ManaCost?,
        autoTapSolution: AutoTapSolution? = null,
        canPay: Boolean,
        envelope: Envelope,
        nonManaCosts: forge.game.cost.Cost? = null,
        onActive: (Action) -> Unit = {},
    ) {
        val actionBuilder =
            Action
                .newBuilder()
                .setActionType(ActionType.Activate_add3)
                .setInstanceId(instanceId)
        if (envelope.includesSourceIdentity) {
            actionBuilder
                .setGrpId(grpId)
                .setFacetId(instanceId)
        }
        if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
        uniqueAbilityId?.let(actionBuilder::setUniqueAbilityId)
        if (canPay && envelope.activeShouldStop) {
            actionBuilder.setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Activate_add3))
        }
        if ((!canPay || envelope.activeManaCost) && abilityCost != null && !abilityCost.isNoCost) {
            ActionManaCosts.addManaCostFromForge(abilityCost, actionBuilder, abilityGrpId)
        }
        actionBuilder.addAllCosts(ActionCostParts.of(nonManaCosts))
        autoTapSolution?.let(actionBuilder::setAutoTapSolution)
        if (canPay) {
            val action = actionBuilder.build()
            builder.addActions(action)
            onActive(action)
        } else {
            builder.addInactiveActions(actionBuilder)
        }
    }

    fun buildActivateManaAction(
        card: Card,
        instanceId: Int,
        grpId: Int,
        cardDataLookup: (leyline.bridge.types.GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
    ): List<Action> = buildActivateManaActions(card, instanceId, grpId, cardDataLookup, abilityRegistryLookup).map { it.action }

    fun buildActivateManaActions(
        card: Card,
        instanceId: Int,
        grpId: Int,
        cardDataLookup: (leyline.bridge.types.GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
        abilities: List<SpellAbility> = getPlayableManaAbilities(card, card.controller),
    ): List<ManaAction> {
        val cardData = cardDataLookup(leyline.bridge.types.GrpId(grpId))
        val registry = abilityRegistryLookup(card, cardData)
        return distinctManaAbilities(card, abilities).mapNotNull { (abilityIndex, sa) ->
            val basicLandAbilityGrpId = basicLandAbilityGrpId(card, sa)
            val abilityGrpId = registry?.forSpellAbility(sa.definitionId) ?: basicLandAbilityGrpId
            val colors = producedManaColors(sa)
            if (colors.isEmpty()) return@mapNotNull null

            val actionBuilder =
                Action
                    .newBuilder()
                    .setActionType(ActionType.ActivateMana)
                    .setInstanceId(instanceId)
                    .setGrpId(grpId)
                    .setFacetId(instanceId)
                    .setIsBatchable(true)
            if (abilityGrpId != 0) actionBuilder.setAbilityGrpId(abilityGrpId)
            uniqueAbilityIdFor(cardData, abilityGrpId, fallbackWhenUnmapped = abilityGrpId == basicLandAbilityGrpId)
                ?.let(actionBuilder::setUniqueAbilityId)

            for ((idx, manaColor) in colors.withIndex()) {
                val manaInfo =
                    ManaInfo
                        .newBuilder()
                        .setManaId(INITIAL_MANA_ID + idx)
                        .setColor(manaColor)
                        .setSrcInstanceId(instanceId)
                        .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                        .setAbilityGrpId(abilityGrpId)
                        .setCount(1)
                if (card.type.isSnow) {
                    manaInfo.addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.FromSnow))
                }
                sourceKindSpec(card)?.let { manaInfo.addSpecs(ManaInfo.Spec.newBuilder().setType(it)) }
                actionBuilder.addManaPaymentOptions(
                    ManaPaymentOption.newBuilder().addMana(manaInfo),
                )
            }

            val selection =
                ManaSelection
                    .newBuilder()
                    .setInstanceId(instanceId)
                    .setAbilityGrpId(abilityGrpId)
                    .setSelectionCount(1)
                    .setValidationType(SelectionValidationType.NonRepeatable)
            for (manaColor in colors) {
                selection.addOptions(
                    ManaSelectionOption
                        .newBuilder()
                        .setSelectedColor(manaColor)
                        .addMana(
                            ManaColorCount.newBuilder().setColor(manaColor).setCount(1),
                        ),
                )
            }
            actionBuilder.addManaSelections(selection)

            ManaAction(actionBuilder.build(), abilityIndex, sa)
        }
    }

    /**
     * Build the minimal inactive mana-action rail for a tapped source.
     *
     * Inactive mana actions identify the source and ability but deliberately
     * omit payment options and selections: the client only needs enough shape
     * to render the source as unavailable.
     */
    fun buildInactiveActivateManaActions(
        card: Card,
        instanceId: Int,
        grpId: Int,
        cardDataLookup: (leyline.bridge.types.GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
    ): List<Action> {
        val cardData = cardDataLookup(leyline.bridge.types.GrpId(grpId))
        val registry = abilityRegistryLookup(card, cardData)
        return distinctManaAbilities(card, card.manaAbilities).mapNotNull { (_, sa) ->
            sa.setActivatingPlayer(card.controller)
            if (sa.canPlay()) return@mapNotNull null
            val basicLandAbilityGrpId = basicLandAbilityGrpId(card, sa)
            val abilityGrpId = registry?.forSpellAbility(sa.definitionId) ?: basicLandAbilityGrpId
            val actionBuilder =
                Action
                    .newBuilder()
                    .setActionType(ActionType.ActivateMana)
                    .setInstanceId(instanceId)
                    .setGrpId(grpId)
                    .setFacetId(instanceId)
            actionBuilder
                .apply {
                    if (abilityGrpId != 0) setAbilityGrpId(abilityGrpId)
                    uniqueAbilityIdFor(cardData, abilityGrpId, fallbackWhenUnmapped = abilityGrpId == basicLandAbilityGrpId)
                        ?.let(::setUniqueAbilityId)
                }
            sa.payCosts
                ?.totalMana
                ?.takeIf { !it.isNoCost }
                ?.let { ActionManaCosts.addManaCostFromForge(it, actionBuilder, abilityGrpId) }
            actionBuilder.build()
        }
    }

    fun basicLandAbilityGrpId(
        card: Card,
        ability: SpellAbility,
    ): Int =
        BasicLandAbilities.byTypeDerivedManaAbility(card, ability)
            ?: BasicLandAbilities.byForgeSubtypeNames(card.type.subtypes)
            ?: 0

    private fun distinctManaAbilities(
        card: Card,
        abilities: Iterable<SpellAbility>,
    ): List<IndexedValue<SpellAbility>> {
        val all = abilities.toList()
        return all.withIndex().filterNot { (_, candidate) ->
            isGeneratedBasicManaAbility(card, candidate) &&
                all.any { existing ->
                    existing !== candidate &&
                        !isGeneratedBasicManaAbility(card, existing) &&
                        equivalentBasicManaExecution(existing, candidate)
                }
        }
    }

    private fun isGeneratedBasicManaAbility(
        card: Card,
        ability: SpellAbility,
    ): Boolean =
        BasicLandAbilities.byTypeDerivedManaAbility(card, ability) != null &&
            ability.getOriginalParam("Cost") == "T" &&
            producedManaColors(ability).singleOrNull() != null &&
            ability.payCosts.costParts.size == 1 &&
            ability.payCosts.hasOnlySpecificCostType(CostTap::class.java) &&
            ability.subAbility == null &&
            ability.additionalAbilities.isEmpty() &&
            ability.additionalAbilityLists.isEmpty()

    private fun equivalentBasicManaExecution(
        existing: SpellAbility,
        generated: SpellAbility,
    ): Boolean =
        existing.api == generated.api &&
            producedManaColors(existing) == producedManaColors(generated) &&
            existing.payCosts.costParts.map { it.javaClass to it.toString() } ==
            generated.payCosts.costParts.map { it.javaClass to it.toString() } &&
            existing.mapParams.withoutSecondaryMarker() == generated.mapParams.withoutSecondaryMarker() &&
            existing.originalMapParams.withoutSecondaryMarker() == generated.originalMapParams.withoutSecondaryMarker() &&
            existing.sVars == generated.sVars &&
            existing.subAbility == null &&
            existing.additionalAbilities.isEmpty() &&
            existing.additionalAbilityLists.isEmpty()

    private fun Map<String, String>.withoutSecondaryMarker(): Map<String, String> = this - "Secondary"

    fun uniqueAbilityIdFor(
        cardData: CardData?,
        abilityGrpId: Int,
        fallbackWhenUnmapped: Boolean = false,
        grantedIndex: Int? = null,
    ): Int? {
        if (abilityGrpId == 0) return null
        if (cardData == null) return INITIAL_UNIQUE_ABILITY_ID
        grantedIndex?.let { return INITIAL_UNIQUE_ABILITY_ID + cardData.abilityIds.size + it }
        val index = cardData.abilityIds.indexOfFirst { (grpId, _) -> grpId == abilityGrpId }
        return when {
            index >= 0 -> INITIAL_UNIQUE_ABILITY_ID + index
            fallbackWhenUnmapped -> INITIAL_UNIQUE_ABILITY_ID
            else -> null
        }
    }

    /**
     * `ManaSpecType.FromBasic`/`FromTreasure`/`FromCave` — the mana source's
     * kind, mutually exclusive with each other and with the separate
     * `FromSnow` check above (a card can be both snow and one of these; the
     * two calls are independent). No card is more than one of Basic/
     * Treasure/Cave, so `firstOrNull` is exhaustive, not a priority order.
     */
    fun sourceKindSpec(card: Card): ManaSpecType? =
        when {
            card.type.isBasicLand -> ManaSpecType.FromBasic
            card.type.hasSubtype("Treasure") -> ManaSpecType.FromTreasure
            card.type.hasSubtype("Cave") -> ManaSpecType.FromCave
            else -> null
        }

    fun producedManaColors(sa: forge.game.spellability.SpellAbility): List<ManaColor> {
        val mana = sa.manaPart ?: return emptyList()
        val produced = if (mana.isComboMana) mana.getComboColors(sa) else mana.origProduced
        return produced.split(" ").mapNotNull { ActionManaCosts.producedToManaColor(it) }.distinct()
    }
}
