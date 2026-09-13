package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.handoff.TargetingCommandReceipt
import leyline.bridge.handoff.TargetingInteractionKind
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles targeting-related client messages.
 *
 * Response admission reads the committed prompt horizon through [SessionCounters].
 */
class TargetingHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val ctx: SessionContext,
    private val matchId: String,
) {
    companion object {
        /** Stash optional cost indices after client response — writes to journal only. */
        fun stashOptionalCostIndices(
            prompt: InteractivePromptBridge,
            indices: List<Int>,
        ) {
            prompt.journal.record(PromptSideEffect.OptionalCostStash(indices))
        }
    }

    private val log = LoggerFactory.getLogger(TargetingHandler::class.java)
    private val manaSourcePaymentHandler = ManaSourcePaymentHandler(sink, counters, ctx)
    private val deferredCastCostInteractionHandler =
        DeferredCastCostInteractionHandler(
            sink = sink,
            counters = counters,
            ctx = ctx,
            matchId = matchId,
        )

    /** Clear targeting state for puzzle hot-swap. */
    fun reset() {
        ctx.bridge.cutCoordinator
            .deferredCast
            .discard()
    }

    /**
     * Handle SelectTargetsResp (phase 1): store selection, send echo-back re-prompt.
     *
     * Does NOT submit to engine — waits for [onSubmitTargets] (SubmitTargetsReq).
     * The echo-back re-prompt reflects the selection per client wire spec:
     * only selected targets, legalAction=Unselect, selectedTargets count set.
     *
     * Player targets use seatId (1/2) as instanceId.
     */
    internal fun onSelectTargets(greMsg: ClientToGREMessage): HandlerResult {
        val bridge = ctx.bridge
        val resp = greMsg.selectTargetsResp
        Tap.targetSelection(ctx.seatId.value, greMsg.gameStateId, resp.target.targetIdx, resp.target.targetsList)
        val compatibility =
            bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .compatibilityCostSelection
                .current()
        if (compatibility != null) {
            val receipt =
                bridge.cutCoordinator.promptRuntimes(ctx.seatId).compatibilityCostSelection.submitToggle(
                    compatibility.interactionId,
                    greMsg.gameStateId,
                    resp.target.targetIdx,
                    resp.target.targetsList.map { target ->
                        TargetToggleValue(target.targetInstanceId, target.legalAction != SelectAction.Unselect)
                    },
                ) ?: return HandlerResult.Waiting
            return deliverCompatibilityReceipt(receipt)
        }
        val targeting =
            bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .targeting
                .current()
                ?.takeIf { it.kind == TargetingInteractionKind.Targeting }
        if (targeting != null) {
            val receipt =
                bridge.cutCoordinator.promptRuntimes(ctx.seatId).targeting.submitToggle(
                    targeting.interactionId,
                    greMsg.gameStateId,
                    resp.target.targetIdx,
                    resp.target.targetsList.map { target ->
                        TargetToggleValue(
                            instanceId = target.targetInstanceId,
                            selected = target.legalAction != SelectAction.Unselect,
                        )
                    },
                ) ?: return HandlerResult.Waiting
            return deliverTargetingReceipt(receipt)
        }
        log.warn("TargetingHandler: SelectTargetsResp did not match a coordinator-owned window")
        DevCheck.failOnAutoPass { "SelectTargetsResp but no coordinator-owned window" }
        return HandlerResult.NotHandled
    }

    /**
     * Handle SubmitTargetsReq (phase 2): submit stored selection to engine.
     *
     * Type-only message (no payload). Uses selection stored by [onSelectTargets].
     */
    internal fun onSubmitTargets(greMsg: ClientToGREMessage): HandlerResult {
        val bridge = ctx.bridge
        val compatibility =
            bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .compatibilityCostSelection
                .current()
        val compatibilityReceipt =
            bridge.cutCoordinator.promptRuntimes(ctx.seatId).compatibilityCostSelection.submitTargets(
                compatibility?.interactionId,
                greMsg.gameStateId,
            )
        if (compatibilityReceipt != null) {
            return deliverCompatibilityReceipt(compatibilityReceipt)
        }
        val targeting =
            bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .targeting
                .current()
                ?.takeIf { it.kind == TargetingInteractionKind.Targeting }
        val migrated =
            bridge.cutCoordinator
                .promptRuntimes(
                    ctx.seatId,
                ).targeting
                .submitTargets(targeting?.interactionId, greMsg.gameStateId)
        if (migrated != null) {
            return deliverTargetingReceipt(migrated)
        }
        log.warn("TargetingHandler: SubmitTargetsReq did not match a coordinator-owned window")
        DevCheck.failOnAutoPass { "SubmitTargetsReq but no coordinator-owned window" }
        return HandlerResult.NotHandled
    }

    internal fun onEffectCost(greMsg: ClientToGREMessage): HandlerResult {
        val payment = manaSourcePaymentHandler.tryHandleEffectCost(greMsg)
        if (payment != HandlerResult.NotHandled) return payment
        log.warn("TargetingHandler: EffectCostResp did not match a coordinator-owned window")
        DevCheck.failOnAutoPass { "EffectCostResp but no coordinator-owned window" }
        return HandlerResult.NotHandled
    }

    /**
     * Native PayCostsReq mana-payment UIs answer through PerformActionResp.
     * Waterbend reducer clicks are MakePayment actions; the Done button is Pass.
     */
    internal fun tryHandlePayCostsPerformAction(greMsg: ClientToGREMessage): HandlerResult =
        manaSourcePaymentHandler.tryHandlePerformAction(greMsg)

    /**
     * Handle UndoReq: the player released the last mana source they tapped. Only the
     * iterative payment window advertises `allowUndo`, so nothing else can own this.
     */
    internal fun onUndo(greMsg: ClientToGREMessage): HandlerResult {
        val payment = manaSourcePaymentHandler.tryHandleUndo(greMsg)
        if (payment != HandlerResult.NotHandled) return payment
        // Targeting, distribution, gather-counters, top-ordering and one-shot PayCosts
        // also advertise allowUndo but carry no undo command yet. Say so rather than
        // leaving the client waiting on a response that never comes.
        log.warn("TargetingHandler: UndoReq but no coordinator-owned window accepts undo")
        DevCheck.failOnAutoPass { "UndoReq but no coordinator-owned window accepts undo" }
        return HandlerResult.NotHandled
    }

    /**
     * Handle CancelActionReq: player backed out of targeting (cancel spell cast).
     *
     * Submits an empty target list to the pending prompt. The engine interprets
     * empty indices as "no targets chosen" → `TargetSelectionResult(false, false)`
     * → spell targeting fails → engine unwinds the cast (removes from stack,
     * returns mana). We then resend the game state so the client sees the
     * board return to pre-cast state with available actions.
     */
    internal fun onCancelAction(greMsg: ClientToGREMessage): HandlerResult {
        val bridge = ctx.bridge
        if (bridge.cutCoordinator.deferredCast.hasPrompt()) {
            return cancelDeferredCast(greMsg.gameStateId)
        }

        val compatibility =
            bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .compatibilityCostSelection
                .current()
        if (compatibility != null) {
            bridge.cutCoordinator
                .promptRuntimes(
                    ctx.seatId,
                ).compatibilityCostSelection
                .cancel(compatibility.interactionId, greMsg.gameStateId)
                ?.let { receipt ->
                    return deliverCompatibilityReceipt(receipt)
                }
        }
        val targeting =
            bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .targeting
                .current()
                ?.takeIf { it.kind == TargetingInteractionKind.Targeting }
        if (targeting != null) {
            bridge.cutCoordinator.promptRuntimes(ctx.seatId).targeting.cancel(targeting.interactionId, greMsg.gameStateId)?.let { receipt ->
                return deliverTargetingReceipt(receipt)
            }
        }

        val payment = manaSourcePaymentHandler.tryHandleCancel(greMsg)
        if (payment != HandlerResult.NotHandled) return payment

        log.warn("TargetingHandler: CancelActionReq but no coordinator-owned window")
        DevCheck.failOnAutoPass { "CancelActionReq but no coordinator-owned window" }
        return HandlerResult.NotHandled
    }

    private fun cancelDeferredCast(gameStateId: Int): HandlerResult {
        val deferredCast = ctx.bridge.cutCoordinator.deferredCast
        if (!deferredCast.cancel(gameStateId)) {
            log.warn("TargetingHandler: CancelActionReq did not match current deferred cast window")
            return HandlerResult.Waiting
        }
        log.info("TargetingHandler: CancelActionReq — cancelling deferred cast before engine submit")
        return HandlerResult.Resume
    }

    private fun deliverTargetingReceipt(receipt: TargetingCommandReceipt): HandlerResult =
        deliverReceipt(receipt) { interactionId, deliveryToken ->
            ctx.bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .targeting
                .acknowledgeDelivery(interactionId, deliveryToken)
        }

    private fun deliverCompatibilityReceipt(receipt: TargetingCommandReceipt): HandlerResult =
        deliverReceipt(receipt) { interactionId, deliveryToken ->
            ctx.bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .compatibilityCostSelection
                .acknowledgeDelivery(interactionId, deliveryToken)
        }

    private fun deliverReceipt(
        receipt: TargetingCommandReceipt,
        acknowledge: (interactionId: String, deliveryToken: Long) -> Boolean,
    ): HandlerResult {
        val bridge = ctx.bridge
        receipt.deliveryToken?.let { deliveryToken ->
            val batches = bridge.cutCoordinator.drain(counters.seatId)
            try {
                batches.forEach(sink::sendBundledGRE)
            } catch (ex: Exception) {
                bridge.cutCoordinator.failDelivery(ex)
            }
            check(acknowledge(receipt.interactionId, deliveryToken)) {
                "Targeting delivery acknowledgement was stale"
            }
        }
        return if (receipt.engineWillResume) HandlerResult.Resume else HandlerResult.Waiting
    }

    // --- Helpers ---

    /**
     * Handle CastingTimeOptionsResp: dispatches to modal or kicker/optional cost handler.
     */
    internal fun onCastingTimeOptions(greMsg: ClientToGREMessage): HandlerResult =
        deferredCastCostInteractionHandler.onCastingTimeOptions(greMsg)

    internal fun checkHybridManaTypeOptions(actionClaim: leyline.bridge.coord.MatchActionWindowRuntime.ActionClaim): Boolean =
        deferredCastCostInteractionHandler.checkHybridManaTypeOptions(actionClaim)

    /**
     * Check if a Cast action targets a card with optional costs (kicker, buyback, etc.).
     * If yes, sends CastingTimeOptionsReq to client and returns true (caller should NOT submit to engine).
     * If no, returns false (caller should proceed normally).
     */
    internal fun checkOptionalCosts(
        actionClaim: leyline.bridge.coord.MatchActionWindowRuntime.ActionClaim,
        preserveHybridStash: Boolean = false,
    ): Boolean =
        deferredCastCostInteractionHandler.checkOptionalCosts(
            actionClaim = actionClaim,
            preserveHybridStash = preserveHybridStash,
        )

    internal fun checkAlternateAdditionalCostChoice(actionClaim: leyline.bridge.coord.MatchActionWindowRuntime.ActionClaim): Boolean =
        deferredCastCostInteractionHandler.checkAlternateAdditionalCostChoice(actionClaim)
}
