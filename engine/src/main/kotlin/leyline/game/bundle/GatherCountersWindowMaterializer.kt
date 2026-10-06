package leyline.game.bundle

import leyline.bridge.handoff.GatherCountersWindowValue
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.*

/** Value-only GRE preparation for the bounded GatherCounters PayCosts row. */
internal class GatherCountersWindowMaterializer {
    fun prepare(
        context: SettledPromptMaterializationContext,
        window: GatherCountersWindowValue,
    ): SettledPromptMaterialization {
        val destinationId =
            context.requiredInstanceId(
                FrameIdResolver.triggerStackAbilityForgeId(window.promptSource.forgeAbilityId),
                "PayCosts card",
            )
        val payCosts =
            PayCostsReq
                .newBuilder()
                .setPaymentActions(ActionsAvailableReq.getDefaultInstance())
                .setEffectCostReq(
                    EffectCostReq
                        .newBuilder()
                        .setEffectCostType(EffectCostType.GatherCounters)
                        .setGatherReq(
                            GatherReq
                                .newBuilder()
                                .setDestinationId(destinationId)
                                .setAmountToGather(window.amountToGather)
                                .also { gather ->
                                    window.sources.forEach { source ->
                                        gather.addSources(
                                            GatherSource
                                                .newBuilder()
                                                .setSourceId(context.requiredInstanceId(source.forgeCardId, "PayCosts card"))
                                                .setMaxAmount(source.maxAmount),
                                        )
                                    }
                                },
                        ),
                ).build()
        val messages =
            listOf(
                context.message(GREMessageType.GameStateMessage_695e) {
                    it.gameStateMessage = context.gameState
                },
                context.message(GREMessageType.PayCostsReq_695e) {
                    it.payCostsReq = payCosts
                    it.prompt = promptWithCardId(PromptIds.GATHER_COUNTERS, destinationId)
                    it.allowCancel = AllowCancel.Abort
                },
            )
        return context.prepared(messages, awaitedRequest = messages.last())
    }
}
