package leyline.game.mapping

import leyline.game.event.FrameEventLog
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.PromptProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.ActionInfo
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/**
 * Immutable, cut-scoped input for state projection.
 *
 * The shell captures the snapshot and cut-specific facts once, normalizes
 * ordered events, then reuses this value for every retry of the same projection
 * attempt.
 */
data class StateFrameInput(
    val gameStateId: Int,
    val snapshot: GsmSnapshot,
    val previousSnapshot: GsmSnapshot?,
    val events: FrameEventLog,
    val promptFacts: PromptProjectionFacts,
    val updateType: GameStateUpdate,
    val viewingSeatId: Int,
    val revealForSeat: Int?,
    /** One immutable synthetic-effect observation for this frame cut. */
    val effectFacts: EffectProjectionFacts,
    /** Event-relevant source attribution observed at this frame cut. */
    val mechanicSourceFacts: MechanicSourceFacts,
    /** Final ordered display rows for exhausted abilities at this frame cut. */
    val abilityExhaustionFacts: AbilityExhaustionFacts,
    /** Time-sensitive observations used only by persistent-feed projection. */
    val persistentFeedFacts: PersistentFeedFacts,
    /** Display permissions for public non-hand cards, independent of priority. */
    val zoneCastActions: List<ActionInfo> = emptyList(),
)

/** Viewer-neutral observation read once at an engine safe point. */
data class CapturedStateFrame(
    val gameStateId: Int,
    val snapshot: GsmSnapshot,
    val events: FrameEventLog,
    val promptFacts: PromptProjectionFacts,
    val revealForSeat: Int?,
    val effectFacts: EffectProjectionFacts,
    val mechanicSourceFacts: MechanicSourceFacts,
    val abilityExhaustionFacts: AbilityExhaustionFacts,
    val persistentFeedFacts: PersistentFeedFacts,
    val zoneCastActions: List<ActionInfo> = emptyList(),
) {
    fun forViewer(
        viewingSeatId: Int,
        previousSnapshot: GsmSnapshot?,
        updateType: GameStateUpdate,
        revealForSeat: Int? = this.revealForSeat,
    ): StateFrameInput {
        val normalizedEvents =
            FrameEventLog(
                events =
                    events.events +
                        previousSnapshot
                            ?.let {
                                leyline.game.event.SnapDeltaSynthesizer
                                    .synthesize(it, snapshot)
                            }.orEmpty(),
                zoneMoves = events.zoneMoves,
            )
        return StateFrameInput(
            gameStateId = gameStateId,
            snapshot = snapshot,
            previousSnapshot = previousSnapshot,
            events = normalizedEvents,
            promptFacts = promptFacts,
            updateType = updateType,
            viewingSeatId = viewingSeatId,
            revealForSeat = revealForSeat,
            effectFacts = effectFacts,
            mechanicSourceFacts = mechanicSourceFacts,
            abilityExhaustionFacts = abilityExhaustionFacts,
            persistentFeedFacts = persistentFeedFacts,
            zoneCastActions = zoneCastActions,
        )
    }
}
