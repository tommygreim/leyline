package leyline.match

import leyline.bridge.handoff.BlockingInteraction
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles numeric-input prompts (`Cost$ X`, `Announce$ X`, "choose X") via
 * `NumericInputReq` (GRE type 43).
 *
 * Lifecycle:
 * 1. Engine thread calls one of `PlayerController`'s `chooseNumber` overrides →
 *    the coordinator commits `NumericInputReq` output → blocks on its answer.
 * 2. Client responds with `NumericInputResp{numericInputValue: N}`. The response
 *    body is empty (`{}`) when `N == 0` due to proto3 default-omission — wire-
 *    equivalent to `numericInputValue: 0`.
 * 3. [MatchHandler] dispatches to [onNumericInputResp] → submits the value →
 *    engine unblocks → cost/announce resolves with the chosen value.
 *
 * v1 only emits `numericInputType = ChooseX_ad80` with `maxValue = INT_MAX` and
 * `stepSize = 1`. ChooseAnyAmount and ChooseDieRoll variants need separate emit
 * paths and are deferred — exercise via a puzzle fixture before extending.
 */
class NumericInputHandler(
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Handle `NumericInputResp` from client. Treats absent and `0` identically
     * (proto3 default-omission ships `0` as an empty body).
     */
    fun onNumericInputResp(greMsg: ClientToGREMessage): Boolean {
        val bridge = ctx.bridge
        val pending =
            bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .blocking
                .current()
                ?.takeIf { it.interaction is BlockingInteraction.Numeric }
                ?: run {
                    log.warn("NumericInputHandler: no pending prompt for NumericInputResp")
                    return false
                }
        val prompt = pending.interaction as BlockingInteraction.Numeric

        val value = greMsg.numericInputResp.numericInputValue

        log.info(
            "NumericInputHandler: client picked {} for {}",
            value,
            prompt.sourceId ?: "unknown",
        )

        if (!bridge.cutCoordinator
                .promptRuntimes(
                    ctx.seatId,
                ).blocking
                .submitNumeric(pending.interactionId, greMsg.gameStateId, value)
        ) {
            return false
        }
        return true
    }
}
