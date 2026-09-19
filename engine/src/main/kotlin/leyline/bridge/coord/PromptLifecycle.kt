package leyline.bridge.coord

import leyline.game.PendingPromptCut

/** Common lifecycle owned by the match prompt-runtime inventory. */
internal interface PromptLifecycle {
    fun current(): Any?

    fun terminate(cause: Throwable)

    fun reset()
}

/** A prompt lifecycle that can report its read-only terminal-cut candidate. */
internal interface PromptTerminalCutOwner : PromptLifecycle {
    fun terminalCutCandidateLocked(): PromptTerminalCutCandidate?
}

internal data class PromptTerminalCutCandidate(
    val priority: PromptTerminalPriority,
    val cut: PendingPromptCut<*>,
)

/** Inner prompt cuts take precedence over the outer windows that led to them. */
internal enum class PromptTerminalPriority {
    OneShotPayCosts,
    ManaSourcePayment,
    Search,
    Replacement,
    Distribution,
    Order,
    TriggerOrder,
    Grouping,
    CardSelect,
    StaticChoice,
    ModalChoice,
    RevealChoice,
    Blocking,
}
