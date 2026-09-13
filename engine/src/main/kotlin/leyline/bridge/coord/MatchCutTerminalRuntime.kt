package leyline.bridge.coord

import leyline.game.MaterializationDiagnostic
import leyline.game.PendingCut
import leyline.game.PlaybackTerminalFailure
import leyline.game.PromptTerminalEvidence

/** Write-once terminal state and waiter teardown for one match cut coordinator. */
internal class MatchCutTerminalRuntime(
    private val owner: MatchCutCoordinator,
) {
    data class Context(
        val pending: PendingCut? = null,
        val diagnostic: MaterializationDiagnostic? = null,
        val promptEvidence: PromptTerminalEvidence? = null,
    )

    @Volatile
    private var failure: PlaybackTerminalFailure? = null

    fun current(): PlaybackTerminalFailure? = failure

    fun reset() {
        failure = null
    }

    fun ensureOpen() {
        failure?.let { throw it }
    }

    fun terminate(
        cause: Throwable,
        context: Context = Context(),
    ): PlaybackTerminalFailure =
        synchronized(owner.feedLock) {
            failure?.let { return@synchronized it }
            PlaybackTerminalFailure(
                pendingCut = context.pending,
                diagnostic = context.diagnostic,
                promptEvidence = context.promptEvidence,
                cause = cause,
            ).also { terminal ->
                context.pending?.let(owner::retainPendingCut)
                failure = terminal
                owner.actions.terminate()
                owner.deferredCast.discard()
                owner.allPromptRuntimes().forEach { it.terminate(terminal) }
                owner.bridge.failActionWindows(terminal)
                owner.bridge.prioritySignal.signal()
            }
        }
}
