package leyline.match

import leyline.DevCheck
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Value-only session adapter for coordinator-owned iterative and one-shot PayCosts windows. */
internal class ManaSourcePaymentHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(ManaSourcePaymentHandler::class.java)

    fun tryHandlePerformAction(greMsg: ClientToGREMessage): HandlerResult {
        val actions = greMsg.performActionResp.actionsList
        if (actions.none { it.actionType == ActionType.MakePayment || it.actionType == ActionType.Pass }) {
            return HandlerResult.NotHandled
        }
        val runtime =
            ctx.bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .manaSourcePayments
        val pending = runtime.current() ?: return HandlerResult.NotHandled
        val selectedIds =
            actions
                .flatMap { action ->
                    buildList {
                        if (action.actionType == ActionType.MakePayment && action.instanceId != 0) add(action.instanceId)
                        action.manaSelectionsList.mapNotNullTo(this) { it.instanceId.takeIf { id -> id != 0 } }
                    }
                }.distinct()
        val receipt =
            if (actions.any { it.actionType == ActionType.Pass }) {
                runtime.complete(pending.interactionId, greMsg.gameStateId, selectedIds)
            } else {
                runtime.select(pending.interactionId, greMsg.gameStateId, selectedIds)
            }
        if (receipt == null) {
            log.warn("Mana-source payment action did not match the current interaction")
            DevCheck.failOnAutoPass { "Mana-source payment action did not match the current interaction" }
            return HandlerResult.Waiting
        }
        return if (deliver(receipt)) HandlerResult.Resume else HandlerResult.Waiting
    }

    fun tryHandleCancel(greMsg: ClientToGREMessage): HandlerResult {
        val runtime =
            ctx.bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .manaSourcePayments
        val pending = runtime.current() ?: return HandlerResult.NotHandled
        val receipt = runtime.cancel(pending.interactionId, greMsg.gameStateId)
        if (receipt == null) {
            log.warn("Mana-source payment cancel did not match the current interaction")
            DevCheck.failOnAutoPass { "Mana-source payment cancel did not match the current interaction" }
            return HandlerResult.Waiting
        }
        return if (deliver(receipt)) HandlerResult.Resume else HandlerResult.Waiting
    }

    /** Release the most recently tapped mana source, leaving the window open. */
    fun tryHandleUndo(greMsg: ClientToGREMessage): HandlerResult {
        val runtime =
            ctx.bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .manaSourcePayments
        val pending = runtime.current() ?: return HandlerResult.NotHandled
        val receipt = runtime.undo(pending.interactionId, greMsg.gameStateId)
        if (receipt == null) {
            log.warn("Mana-source payment undo did not match the current interaction")
            DevCheck.failOnAutoPass { "Mana-source payment undo did not match the current interaction" }
            return HandlerResult.Waiting
        }
        return if (deliver(receipt)) HandlerResult.Resume else HandlerResult.Waiting
    }

    fun tryHandleEffectCost(greMsg: ClientToGREMessage): HandlerResult {
        val runtime =
            ctx.bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .manaSourcePayments
        val pending = runtime.current() ?: return HandlerResult.NotHandled
        val selectedIds = greMsg.effectCostResp.costSelection.idsList
        val receipt = runtime.complete(pending.interactionId, greMsg.gameStateId, selectedIds)
        if (receipt == null) {
            log.warn("Mana-source EffectCost response did not match the current interaction")
            DevCheck.failOnAutoPass { "Mana-source EffectCost response did not match the current interaction" }
            return HandlerResult.Waiting
        }
        return if (deliver(receipt)) HandlerResult.Resume else HandlerResult.Waiting
    }

    private fun deliver(receipt: leyline.bridge.handoff.ManaSourcePaymentCommandReceipt): Boolean {
        val bridge = ctx.bridge
        receipt.deliveryToken?.let { token ->
            val batches = bridge.cutCoordinator.drain(counters.seatId)
            try {
                batches.forEach(sink::sendBundledGRE)
            } catch (ex: Exception) {
                bridge.cutCoordinator.failDelivery(ex)
            }
            check(
                bridge.cutCoordinator
                    .promptRuntimes(ctx.seatId)
                    .manaSourcePayments
                    .acknowledgeDelivery(receipt.interactionId, token),
            ) {
                "Mana-source payment delivery acknowledgement was stale"
            }
        }
        return receipt.completed
    }
}
