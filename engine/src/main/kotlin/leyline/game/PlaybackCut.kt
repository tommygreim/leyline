package leyline.game

import leyline.game.bundle.BundleBuilder
import leyline.game.event.FrameEventLog
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

internal enum class PlaybackCutReason {
    OpeningHandAction,
    LandPlayed,
    StackObjectCast,
    StackObjectResolved,
    ResolutionZoneCompleted,
    CountersChanged,
    PoisonChanged,
    TurnBegan,
    PhaseChanged,
    DrawStep,
    AttackersDeclared,
    BlockersDeclared,
    CombatEnded,
}

internal enum class PlaybackCutBoundary {
    MainLoopStep,
    AttackersDeclared,
    BlockersDeclared,
    CombatEnded,
}

internal data class PlaybackCutRequest(
    val reason: PlaybackCutReason,
    val delayMs: Int,
    val turnStarted: Boolean,
    val boundary: PlaybackCutBoundary = PlaybackCutBoundary.MainLoopStep,
) {
    fun aggregate(next: PlaybackCutRequest): PlaybackCutRequest =
        copy(
            delayMs = maxOf(delayMs, next.delayMs),
            turnStarted = turnStarted || next.turnStarted,
            boundary = if (next.boundary == PlaybackCutBoundary.MainLoopStep) boundary else next.boundary,
        )
}

internal data class PendingCut(
    val request: PlaybackCutRequest,
    val projection: BundleBuilder.PlaybackCut,
)

internal data class MaterializationDiagnostic(
    val request: PlaybackCutRequest,
    val events: FrameEventLog?,
)

/** Exact pre-publication prompt cut retained on terminal failure. */
internal data class PendingPromptCut<out T : Any>(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: T,
    val messages: List<GREToClientMessage>,
    val transition: ProjectionTransition?,
)

/** Frozen prompt input retained when materialization itself fails. */
internal data class PromptMaterializationDiagnostic<out T : Any>(
    val interactionId: String,
    val interaction: T,
)

internal sealed interface PromptTerminalEvidence {
    data class Pending(
        val cut: PendingPromptCut<*>,
    ) : PromptTerminalEvidence

    data class Materialization(
        val diagnostic: PromptMaterializationDiagnostic<*>,
    ) : PromptTerminalEvidence
}

internal class PlaybackTerminalFailure(
    val pendingCut: PendingCut?,
    val diagnostic: MaterializationDiagnostic?,
    val promptEvidence: PromptTerminalEvidence? = null,
    cause: Throwable,
) : IllegalStateException("Playback projection terminated", cause)
