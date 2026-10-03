package leyline.game.bundle

import leyline.bridge.handoff.OneShotPayCostsWindowValue
import leyline.bridge.handoff.PayCostsPromptSourceValue
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.TapPaymentKind
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.*

/** Value-only GRE preparation for coordinator-owned one-shot PayCosts windows. */
internal class OneShotPayCostsMaterializer {
    fun prepare(
        context: SettledPromptMaterializationContext,
        window: OneShotPayCostsWindowValue,
    ): SettledPromptMaterialization {
        val messages =
            listOf(
                context.message(GREMessageType.GameStateMessage_695e) {
                    it.gameStateMessage =
                        context.gameState
                            .toBuilder()
                            .setPendingMessageCount(1)
                            .build()
                },
                context.message(GREMessageType.PayCostsReq_695e) {
                    it.payCostsReq = payCostsRequest(window, context)
                    it.prompt = paymentPrompt(window, context)
                    it.allowCancel = AllowCancel.Abort
                    it.allowUndo = true
                },
            )
        return context.prepared(messages, awaitedRequest = messages.last())
    }

    private fun payCostsRequest(
        window: OneShotPayCostsWindowValue,
        context: SettledPromptMaterializationContext,
    ): PayCostsReq {
        val selection =
            SelectNReq
                .newBuilder()
                .setMinSel(wireMinSelections(window))
                .setMaxSel(wireMaxSelections(window))
                .setContext(SelectionContext.NonManaPayment)
                .setOptionContext(OptionContext.Payment)
                .setListType(SelectionListType.Dynamic)
                .setIdType(IdType.InstanceId_ab2c)
                .setValidationType(SelectionValidationType.NonRepeatable)
                .setMinWeight(
                    when (window.kind) {
                        PayCostsRouteKind.CollectEvidence -> checkNotNull(window.minimumWeight)
                        PayCostsRouteKind.Sacrifice,
                        PayCostsRouteKind.SelectCostExileFromGrave,
                        PayCostsRouteKind.SelectCostReturnAttacker,
                        PayCostsRouteKind.SelectCostReturnTappedCreature,
                        PayCostsRouteKind.StationTapCost,
                        PayCostsRouteKind.EnlistCost,
                        PayCostsRouteKind.TapPayment,
                        PayCostsRouteKind.ConvokeCost,
                        PayCostsRouteKind.ImproviseCost,
                        PayCostsRouteKind.WaterbendCost,
                        -> Int.MIN_VALUE
                    },
                ).setMaxWeight(Int.MAX_VALUE)
        window.candidates.forEach { candidate ->
            selection.addIds(context.requiredInstanceId(candidate.forgeCardId, "PayCosts card"))
            selection.addWeights(candidate.weight)
        }
        return PayCostsReq
            .newBuilder()
            .setPaymentActions(ActionsAvailableReq.getDefaultInstance())
            .setEffectCostReq(
                EffectCostReq
                    .newBuilder()
                    .setEffectCostType(EffectCostType.Select_a59c)
                    .setCostSelection(selection),
            ).build()
    }

    private fun paymentPrompt(
        window: OneShotPayCostsWindowValue,
        context: SettledPromptMaterializationContext,
    ): Prompt =
        promptWithCardId(
            promptId(window),
            promptSourceId(window, context),
        )

    private fun promptId(window: OneShotPayCostsWindowValue): Int =
        when (window.kind) {
            PayCostsRouteKind.Sacrifice -> PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE
            PayCostsRouteKind.SelectCostExileFromGrave -> PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE
            PayCostsRouteKind.SelectCostReturnAttacker -> PromptIds.NINJUTSU_RETURN_UNBLOCKED_ATTACKER_COST
            PayCostsRouteKind.SelectCostReturnTappedCreature -> PromptIds.WEB_SLINGING_RETURN_TAPPED_CREATURE_COST
            PayCostsRouteKind.CollectEvidence -> PromptIds.COLLECT_EVIDENCE_COST
            PayCostsRouteKind.StationTapCost -> PromptIds.STATION_TAP_COST
            PayCostsRouteKind.EnlistCost -> PromptIds.ENLIST_TAP_COST
            PayCostsRouteKind.TapPayment -> checkNotNull(window.tapPayment).promptId
            PayCostsRouteKind.ConvokeCost,
            PayCostsRouteKind.ImproviseCost,
            PayCostsRouteKind.WaterbendCost,
            -> error("Iterative payment cannot use one-shot materializer")
        }

    private fun wireMinSelections(window: OneShotPayCostsWindowValue): Int =
        window.tapPayment
            ?.takeIf { it.kind == TapPaymentKind.TotalPower }
            ?.required
            ?: window.minSelections

    private fun wireMaxSelections(window: OneShotPayCostsWindowValue): Int =
        if (window.tapPayment?.kind == TapPaymentKind.TotalPower) Int.MAX_VALUE else window.maxSelections

    private fun promptSourceId(
        window: OneShotPayCostsWindowValue,
        context: SettledPromptMaterializationContext,
    ): Int =
        when (val source = window.promptSource) {
            is PayCostsPromptSourceValue.StackAbility ->
                context.requiredInstanceId(FrameIdResolver.triggerStackAbilityForgeId(source.forgeAbilityId), "PayCosts card")
            is PayCostsPromptSourceValue.StackCard -> context.requiredInstanceId(source.forgeCardId, "PayCosts card")
            null -> 0
        }
}
