package leyline.game.bundle

import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.OrderWindowValue
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.OrderReq
import wotc.mtgo.gre.external.messaging.Messages.OrderingContext

/** Value-only GRE preparation for coordinator-owned ordered-card windows. */
internal class OrderWindowMaterializer {
    fun prepare(
        context: SettledPromptMaterializationContext,
        window: OrderWindowValue,
    ): SettledPromptMaterialization {
        val request =
            OrderReq
                .newBuilder()
                .addAllIds(window.candidates.map { candidate -> context.requiredInstanceId(candidate.forgeCardId, "Order card") })
                .apply {
                    if (window.kind == OrderRouteKind.Bottom) {
                        orderingContext = OrderingContext.OrderingForBottom
                    }
                }.build()
        val sourceId = window.sourceForgeCardId?.let { source -> context.requiredInstanceId(source, "Order card") } ?: 0
        val state =
            context.gameState
                .toBuilder()
                .setPendingMessageCount(1)
                .build()
        val messages =
            listOf(
                context.message(GREMessageType.GameStateMessage_695e) {
                    it.gameStateMessage = state
                },
                context.message(GREMessageType.OrderReq_695e) {
                    it.orderReq = request
                    it.prompt = promptWithCardId(promptId(window.kind), sourceId)
                    it.allowCancel = AllowCancel.No_a526
                },
            )
        return context.prepared(messages, awaitedRequest = messages.last())
    }

    private fun promptId(kind: OrderRouteKind): Int =
        when (kind) {
            OrderRouteKind.Bottom -> PromptIds.ORDER_LIBRARY_BOTTOM
            OrderRouteKind.Top -> PromptIds.ORDER_LIBRARY_TOP
        }
}
