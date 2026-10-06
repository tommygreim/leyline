package leyline.bridge.handoff

import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import leyline.bridge.NonInteractiveScope
import leyline.bridge.types.PrioritySignal

/** Thin engine/session handoff for exact-handle modal requests. */
internal class ModalChoicePromptAdapter(
    private val timeoutMs: Long?,
    private val strict: Boolean,
    private val isGameLoopThread: () -> Boolean,
    private val runtime: () -> ModalChoiceInteractionRuntime?,
    private val prioritySignal: PrioritySignal?,
    private val record: (PromptRequest, PromptCallStatus, List<Int>) -> Unit,
) {
    fun request(
        request: PromptRequest,
        possible: List<AbilitySub>,
        sourceCard: Card,
        sourceAbility: SpellAbility,
    ): List<AbilitySub> {
        resolvePromptPolicyDefault(request) { indices ->
            record(request, PromptCallStatus.DEFAULTED_POLICY, indices)
            prioritySignal?.markPromptResolved()
        }?.let { indices -> return indices.mapNotNull(possible::getOrNull) }
        val fallback =
            if (request.route.semantic == PromptSemantic.VoteChoice && request.min == 0) emptyList() else listOf(request.defaultIndex)
        val scope = NonInteractiveScope.active
        if (scope != null && strict) {
            refuseStrictPrompt(
                "[strict] Prompt [${request.promptType}] \"${request.message}\" requested inside non-interactive scope $scope",
            )
        }
        if (!isGameLoopThread() && strict) {
            refuseStrictPrompt(
                "[strict] Prompt [${request.promptType}] \"${request.message}\" requested " +
                    "from non-game thread ${Thread.currentThread().name}",
            )
        }
        if (scope != null) {
            record(request, PromptCallStatus.NON_INTERACTIVE_SCOPE, fallback)
            return fallback.mapNotNull(possible::getOrNull)
        }
        if (!isGameLoopThread()) {
            record(request, PromptCallStatus.NON_GAME_THREAD, fallback)
            return fallback.mapNotNull(possible::getOrNull)
        }
        if (timeoutMs == 0L) {
            return fallback.mapNotNull(possible::getOrNull)
        }
        val modalRuntime = checkNotNull(runtime()) { "ModalChoice runtime is not registered" }
        return try {
            val result = modalRuntime.awaitSelection(request, possible, sourceCard, sourceAbility, timeoutMs)
            record(
                request,
                if (result.timedOut) PromptCallStatus.TIMEOUT else PromptCallStatus.RESPONDED,
                result.optionIndices,
            )
            if (result.timedOut) prioritySignal?.signal() else prioritySignal?.markPromptResolved()
            result.handles
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }
}
