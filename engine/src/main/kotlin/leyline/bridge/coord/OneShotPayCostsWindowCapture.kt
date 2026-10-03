package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.OneShotPayCostsCandidateValue
import leyline.bridge.handoff.OneShotPayCostsWindowValue
import leyline.bridge.handoff.PayCostsPromptSourceInput
import leyline.bridge.handoff.PayCostsPromptSourceValue
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.TapPaymentKind
import leyline.bridge.types.AbilityDefinitionRef
import leyline.bridge.types.ForgeCardId

/** Engine-thread freezing for the seven non-iterative PayCosts routes. */
internal class OneShotPayCostsWindowCapture(
    private val owner: MatchCutCoordinator,
) {
    data class Initial(
        val value: OneShotPayCostsWindowValue,
        val handlesByOption: Map<Int, Card>,
    )

    fun initial(
        request: PromptRequest,
        candidateHandles: List<Card>,
    ): Initial {
        val route = request.route as? ResolvedPromptRoute.PayCosts ?: error("One-shot payment requires a PayCosts route")
        require(route.descriptor.manaSourcePayment == null) { "Iterative mana-source payment cannot use one-shot runtime" }
        val handlesById = candidateHandles.associateBy { it.id }
        require(handlesById.size == candidateHandles.size) { "One-shot PayCosts candidates require unique Forge ids" }
        val handlesByOption =
            request.candidateRefs.associate { ref ->
                ref.index to (handlesById[ref.entityId] ?: error("Missing PayCosts candidate ${ref.entityId}"))
            }
        require(handlesByOption.size == request.candidateRefs.size) { "PayCosts option indices must be unique" }
        val weights = selectionWeights(route.descriptor.kind, request)
        val candidates =
            request.candidateRefs.mapIndexed { index, ref ->
                OneShotPayCostsCandidateValue(
                    originalOptionIndex = ref.index,
                    forgeCardId = ForgeCardId(ref.entityId),
                    weight = weights[index],
                )
            }
        val (minSelections, maxSelections) = selectionRange(route.descriptor.kind, request)
        val sourceForgeCardId = request.sourceEntityId?.let(::ForgeCardId)
        val tapPayment = route.descriptor.tapPayment
        require((route.descriptor.kind == PayCostsRouteKind.TapPayment) == (tapPayment != null)) {
            "Tap-payment routes require exactly one tap descriptor"
        }
        if (tapPayment?.kind == TapPaymentKind.TotalPower) {
            require(request.minSelectionWeight == tapPayment.required) {
                "Total-power threshold must match the route descriptor"
            }
        }
        val promptSource = freezePromptSource(request.payCostsPromptSource, sourceForgeCardId)
        if (tapPayment != null) require(promptSource != null) { "Tap payment requires an exact stack source" }
        return Initial(
            value =
                OneShotPayCostsWindowValue(
                    kind = route.descriptor.kind,
                    candidates = candidates,
                    sourceForgeCardId = sourceForgeCardId,
                    promptSource = promptSource,
                    minSelections = minSelections,
                    maxSelections = maxSelections,
                    minimumWeight = request.minSelectionWeight,
                    tapPayment = tapPayment,
                    defaultOptionIndex = request.defaultIndex,
                ),
            handlesByOption = handlesByOption,
        )
    }

    private fun selectionWeights(
        kind: PayCostsRouteKind,
        request: PromptRequest,
    ): List<Int> =
        when (kind) {
            PayCostsRouteKind.CollectEvidence,
            -> {
                require(request.costSelectionWeights.size == request.candidateRefs.size) {
                    "$kind requires one weight per candidate"
                }
                request.costSelectionWeights.map { it.coerceAtLeast(0) }
            }
            PayCostsRouteKind.TapPayment ->
                if (routeTapPayment(request).kind == TapPaymentKind.TotalPower) {
                    require(request.costSelectionWeights.size == request.candidateRefs.size) {
                        "Total-power tap payment requires one weight per candidate"
                    }
                    request.costSelectionWeights.map { it.coerceAtLeast(0) }
                } else {
                    List(request.candidateRefs.size) { 1 }
                }
            PayCostsRouteKind.Sacrifice,
            PayCostsRouteKind.SelectCostExileFromGrave,
            PayCostsRouteKind.SelectCostReturnAttacker,
            PayCostsRouteKind.SelectCostReturnTappedCreature,
            PayCostsRouteKind.StationTapCost,
            PayCostsRouteKind.EnlistCost,
            PayCostsRouteKind.ConvokeCost,
            PayCostsRouteKind.ImproviseCost,
            PayCostsRouteKind.WaterbendCost,
            -> List(request.candidateRefs.size) { 1 }
        }

    private fun selectionRange(
        kind: PayCostsRouteKind,
        request: PromptRequest,
    ): Pair<Int, Int> =
        when (kind) {
            PayCostsRouteKind.CollectEvidence,
            -> request.min.coerceAtLeast(0) to request.max.coerceAtLeast(request.min)
            PayCostsRouteKind.TapPayment ->
                routeTapPayment(request).let { tap ->
                    if (tap.kind == TapPaymentKind.TotalPower) {
                        1 to request.candidateRefs.size
                    } else {
                        tap.required to tap.required
                    }
                }
            PayCostsRouteKind.Sacrifice,
            PayCostsRouteKind.SelectCostExileFromGrave,
            PayCostsRouteKind.SelectCostReturnAttacker,
            PayCostsRouteKind.SelectCostReturnTappedCreature,
            PayCostsRouteKind.StationTapCost,
            PayCostsRouteKind.EnlistCost,
            PayCostsRouteKind.ConvokeCost,
            PayCostsRouteKind.ImproviseCost,
            PayCostsRouteKind.WaterbendCost,
            -> {
                val exact = request.max.coerceAtLeast(1)
                exact to exact
            }
        }

    private fun routeTapPayment(request: PromptRequest) =
        checkNotNull((request.route as? ResolvedPromptRoute.PayCosts)?.descriptor?.tapPayment) {
            "Tap payment requires a route descriptor"
        }

    internal fun freezePromptSource(
        input: PayCostsPromptSourceInput?,
        fallbackCardId: ForgeCardId?,
    ): PayCostsPromptSourceValue? =
        when (input) {
            is PayCostsPromptSourceInput.StackAbility -> {
                val source = owner.bridge.findCard(input.sourceForgeCardId) ?: error("PayCosts ability source is unavailable")
                val identity =
                    owner.bridge.resolveAbilityIdentity(
                        source,
                        AbilityDefinitionRef.SpellAbility(input.abilityDefinitionId),
                    ) ?: error("PayCosts ability identity is unavailable")
                PayCostsPromptSourceValue.StackAbility(
                    forgeAbilityId = input.forgeAbilityId,
                    sourceForgeCardId = input.sourceForgeCardId,
                    abilityGrpId = identity.abilityGrpId,
                    sourceCardGrpId =
                        owner.bridge.cardGrpId(input.sourceForgeCardId) ?: error("PayCosts source card identity is unavailable"),
                    ownerSeatId = owner.bridge.seatOf(source.owner)?.value ?: error("PayCosts source owner is unavailable"),
                    controllerSeatId = owner.bridge.seatOf(source.controller)?.value ?: error("PayCosts source controller is unavailable"),
                    targetForgeCardIds = input.targetForgeCardIds,
                )
            }
            is PayCostsPromptSourceInput.StackCard -> PayCostsPromptSourceValue.StackCard(input.forgeCardId)
            null -> fallbackCardId?.let(PayCostsPromptSourceValue::StackCard)
        }
}
