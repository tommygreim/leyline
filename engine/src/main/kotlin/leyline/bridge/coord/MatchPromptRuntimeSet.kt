package leyline.bridge.coord

import leyline.bridge.handoff.PromptRuntimeBindings
import leyline.bridge.handoff.PublishedOneShotPayCostsInteraction
import leyline.bridge.types.SeatId
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import leyline.game.PromptTerminalEvidence

/** Owns the coordinator prompt-runtime inventory for one interactive seat. */
internal class MatchPromptRuntimeSet(
    private val owner: MatchCutCoordinator,
    private val runtimeSeat: SeatId,
) {
    private val lifecycle = mutableListOf<PromptLifecycle>()
    internal val settled = own(SettledPromptOwner(owner, runtimeSeat = runtimeSeat))

    val targeting = own(MatchTargetingInteractionRuntime(owner, runtimeSeat = runtimeSeat))
    val compatibilityCostSelection = MatchCompatibilityCostSelectionRuntime(owner, runtimeSeat = runtimeSeat)
    val blocking = own(MatchBlockingInteractionRuntime(owner, runtimeSeat = runtimeSeat))
    val search = MatchSearchInteractionRuntime(owner, settled, runtimeSeat = runtimeSeat)
    val replacement = MatchReplacementInteractionRuntime(owner, settled, runtimeSeat = runtimeSeat)
    val order = MatchOrderInteractionRuntime(owner, settled, runtimeSeat = runtimeSeat)
    val triggerOrder = MatchTriggerOrderInteractionRuntime(owner, settled, runtimeSeat = runtimeSeat)
    val distribution = MatchDistributionInteractionRuntime(owner, settled, runtimeSeat = runtimeSeat)
    val grouping = MatchGroupingInteractionRuntime(owner, settled, runtimeSeat = runtimeSeat)
    val cardSelect = MatchCardSelectInteractionRuntime(owner, settled, runtimeSeat = runtimeSeat)
    val staticChoices = MatchStaticChoiceInteractionRuntime(owner, settled, runtimeSeat = runtimeSeat)
    val revealChoices = MatchRevealChoiceInteractionRuntime(owner, settled, runtimeSeat = runtimeSeat)
    val modalChoices = MatchModalChoiceRuntime(owner, settled, runtimeSeat = runtimeSeat)
    val manaSourcePayments = own(MatchManaSourcePaymentRuntime(owner, runtimeSeat = runtimeSeat))
    val oneShotPayCosts = MatchOneShotPayCostsRuntime(owner, settled, runtimeSeat = runtimeSeat)

    fun bindings(seatId: SeatId): PromptRuntimeBindings {
        check(seatId == runtimeSeat) { "Prompt runtime seat does not match its bridge" }
        return PromptRuntimeBindings(
            targeting = targeting,
            compatibilityCostSelection = compatibilityCostSelection,
            search = search,
            replacement = replacement,
            order = order,
            triggerOrder = triggerOrder,
            distribution = distribution,
            grouping = grouping,
            cardSelect = cardSelect,
            staticChoice = staticChoices,
            revealChoice = revealChoices,
            modalChoice = modalChoices,
            manaSourcePayment = manaSourcePayments,
            oneShotPayCosts = oneShotPayCosts,
        )
    }

    fun hasPendingInteraction(): Boolean = lifecycle.any { it.current() != null }

    fun hasRevealProjectionPrompt(): Boolean = revealChoices.current() != null || cardSelect.current() != null

    fun currentOneShotPayCosts(): PublishedOneShotPayCostsInteraction? = oneShotPayCosts.current()

    fun terminate(cause: Throwable) = lifecycle.forEach { it.terminate(cause) }

    fun reset() = lifecycle.forEach { it.reset() }

    fun failDelivery(cause: Throwable): Nothing =
        synchronized(owner.feedLock) {
            val candidate =
                lifecycle
                    .filterIsInstance<PromptTerminalCutOwner>()
                    .mapNotNull(PromptTerminalCutOwner::terminalCutCandidateLocked)
                    .minByOrNull { it.priority }
            candidate?.let { owner.failPrompt(cause, it.cut) }
            owner.fail(cause)
        }

    private fun <T : PromptLifecycle> own(runtime: T): T = runtime.also(lifecycle::add)
}

internal fun MatchCutCoordinator.failPrompt(
    cause: Throwable,
    pending: PendingPromptCut<*>? = null,
    diagnostic: PromptMaterializationDiagnostic<*>? = null,
): Nothing {
    check(pending == null || diagnostic == null) { "Prompt failure cannot retain both a cut and a materialization diagnostic" }
    val evidence =
        pending?.let(PromptTerminalEvidence::Pending)
            ?: diagnostic?.let(PromptTerminalEvidence::Materialization)
    failTerminal(cause, MatchCutTerminalRuntime.Context(promptEvidence = evidence))
}
