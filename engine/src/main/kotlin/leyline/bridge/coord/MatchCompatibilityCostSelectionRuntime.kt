package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.CompatibilityCostSelectionResult
import leyline.bridge.handoff.CompatibilityCostSelectionRuntime
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedTargetingInteraction
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.handoff.TargetingCommandReceipt
import leyline.bridge.handoff.TargetingInteractionKind

/** Match-scoped SelectTargets compatibility owner for residual card choices. */
internal class MatchCompatibilityCostSelectionRuntime(
    private val owner: MatchCutCoordinator,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : CompatibilityCostSelectionRuntime {
    private val targeting: MatchTargetingInteractionRuntime get() = owner.promptRuntimes(runtimeSeat).targeting

    override fun awaitSelection(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): CompatibilityCostSelectionResult {
        val indices = targeting.awaitCompatibility(request, candidateHandles, timeoutMs)
        return CompatibilityCostSelectionResult(indices, indices.mapNotNull(candidateHandles::getOrNull))
    }

    fun current(): PublishedTargetingInteraction? =
        targeting.current()?.takeIf {
            it.kind ==
                TargetingInteractionKind.CompatibilityCostSelection
        }

    fun submitToggle(
        interactionId: String,
        gameStateId: Int,
        targetIndex: Int,
        toggles: List<TargetToggleValue>,
    ): TargetingCommandReceipt? = targeting.submitToggle(interactionId, gameStateId, targetIndex, toggles)

    fun submitTargets(
        interactionId: String?,
        gameStateId: Int,
    ): TargetingCommandReceipt? = targeting.submitTargets(interactionId, gameStateId)

    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): TargetingCommandReceipt? = targeting.cancel(interactionId, gameStateId)

    fun acknowledgeDelivery(
        interactionId: String,
        token: Long,
    ): Boolean = targeting.acknowledgeDelivery(interactionId, token)

    fun terminate(cause: Throwable) = targeting.terminate(cause)

    fun reset() = targeting.reset()
}
