package leyline.copilot

import forge.card.CardStateName
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.game.data.CardRepository
import leyline.game.data.grantedKeywordAbilityGrpId
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.CastDisplayCost
import leyline.game.mapping.CastRails
import leyline.game.mapping.resolveAltGrpId
import leyline.game.snapshot.BoundCard
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement

private val COPILOT_CAST_OFFERS =
    setOf(
        ActionType.Cast,
        ActionType.CastAdventure,
        ActionType.CastMdfc,
        ActionType.CastOmen,
        ActionType.CastLeftRoom,
        ActionType.CastRightRoom,
    )

/** Cast offers the copilot can submit through [leyline.match.ActionPerformer]. */
internal fun ActionType.isCopilotCastOffer(): Boolean = this in COPILOT_CAST_OFFERS

internal fun choosePromptCastOfferForAbility(
    actions: List<Action>,
    sa: SpellAbility,
    player: Player,
    cardRepository: CardRepository,
    sourceInstanceId: Int,
    sourceGrpId: Int,
): Action? {
    val variant = expectedCastVariant(sa, sourceGrpId, player, cardRepository)
    return choosePromptCastOffer(
        actions = actions,
        sourceInstanceId = sourceInstanceId,
        sourceGrpId = sourceGrpId,
        displayedManaCost = CastDisplayCost.requirements(sa, player, null),
        expectedAlternativeGrpId = (variant as? ExpectedCastVariant.Alternative)?.alternativeGrpId,
        preferBaseCast = variant == ExpectedCastVariant.Base && sa.cardStateName == CardStateName.Original,
    )
}

internal fun chooseCastVariant(
    sa: SpellAbility,
    sourceGrpId: Int,
    player: Player,
    cardRepository: CardRepository,
    candidates: List<Action>,
): Action? = chooseCastActionByVariant(candidates, expectedCastVariant(sa, sourceGrpId, player, cardRepository))

/**
 * Selects the prompt's original cast action for a Forge-chosen spell ability.
 * Source identity is primary, displayed mana is the generic face/option
 * discriminator, and variant identity only resolves ties. Ambiguity fails
 * closed instead of defaulting to the first face.
 */
internal fun choosePromptCastOffer(
    actions: List<Action>,
    sourceInstanceId: Int,
    sourceGrpId: Int,
    displayedManaCost: List<ManaRequirement>,
    expectedAlternativeGrpId: Int? = null,
    preferBaseCast: Boolean = false,
): Action? {
    val castOffers = actions.filter { it.actionType.isCopilotCastOffer() }
    val instanceOffers = castOffers.filter { it.instanceId == sourceInstanceId }
    val sourceOffers =
        instanceOffers.ifEmpty {
            castOffers.filter { it.grpId != 0 && it.grpId == sourceGrpId }
        }
    if (sourceOffers.size == 1) return sourceOffers.single()
    if (sourceOffers.isEmpty()) return null

    val expectedMana = displayedManaCost.manaSignature()
    val matchingMana = sourceOffers.filter { it.manaCostList.manaSignature() == expectedMana }
    val narrowed = matchingMana.ifEmpty { sourceOffers }
    if (narrowed.size == 1) return narrowed.single()

    if (expectedAlternativeGrpId != null) {
        narrowed.singleOrNull { it.alternativeGrpId == expectedAlternativeGrpId }?.let { return it }
    }
    if (preferBaseCast) {
        narrowed.singleOrNull { it.actionType == ActionType.Cast && it.alternativeGrpId == 0 }?.let { return it }
    }
    return null
}

private fun List<ManaRequirement>.manaSignature(): List<String> =
    map { requirement ->
        "${requirement.colorList.map { it.number }.sorted().joinToString(",")}:${requirement.count}"
    }.sorted()

private fun expectedCastVariant(
    sa: SpellAbility,
    grpId: Int,
    player: Player,
    cardRepository: CardRepository,
): ExpectedCastVariant {
    if (!sa.isSpell) return ExpectedCastVariant.Base
    val rails = CastRails.all.filter { it.saPredicate(sa) }
    if (rails.isEmpty()) return ExpectedCastVariant.Base
    val altCosts = BoundCard.bindAltCosts(cardRepository.findByGrpId(grpId), cardRepository)
    val payCostPairs =
        ActionMapper
            .computeEffectiveCost(sa, player)
            ?.takeIf { !it.isNoCost }
            ?.let { ActionMapper.forgeManaCostToPairs(it) }
            ?: emptyList()
    val alternativeGrpId =
        cardRepository.grantedKeywordAbilityGrpId(sa) ?: rails.firstNotNullOfOrNull { rail ->
            resolveAltGrpId(rail, altCosts, payCostPairs).takeIf { it > 0 }
        }
    return alternativeGrpId?.let(ExpectedCastVariant::Alternative) ?: ExpectedCastVariant.UnresolvedAlternative
}
