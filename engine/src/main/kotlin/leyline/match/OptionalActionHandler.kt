package leyline.match

import leyline.bridge.handoff.BlockingInteraction
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles "you may" trigger decisions via OptionalActionMessage (GRE type 45).
 *
 * Lifecycle:
 * 1. Engine thread calls `PlayerController.confirmTrigger` → coordinator commits
 *    OptionalActionMessage output → blocks on its runtime answer.
 * 2. Client responds with OptionalResp (AllowYes / CancelNo)
 * 3. [MatchHandler] dispatches to [onOptionalActionResp] → submits the value →
 *    engine unblocks → ability resolves or is deleted
 */
class OptionalActionHandler(
    private val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Handle OptionalActionResp from client.
     */
    fun onOptionalActionResp(greMsg: ClientToGREMessage): Boolean {
        val bridge = ctx.bridge
        val pending =
            bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .blocking
                .current()
                ?.takeIf { it.interaction is BlockingInteraction.Optional }
                ?: run {
                    log.warn("OptionalActionHandler: no pending prompt for OptionalActionResp")
                    return false
                }
        val prompt = pending.interaction as BlockingInteraction.Optional

        val resp = greMsg.optionalResp
        val accepted = resp.response == OptionResponse.AllowYes

        log.info(
            "OptionalActionHandler: {} responded {} for {}",
            if (accepted) "Accept" else "Decline",
            resp.response,
            prompt.sourceId ?: "unknown",
        )

        if (!bridge.cutCoordinator
                .promptRuntimes(
                    ctx.seatId,
                ).blocking
                .submitOptional(pending.interactionId, greMsg.gameStateId, accepted)
        ) {
            return false
        }
        bridge.prioritySignal.markPromptResolved()
        return true
    }
}
