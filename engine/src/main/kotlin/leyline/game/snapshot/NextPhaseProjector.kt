package leyline.game.snapshot

import forge.game.phase.PhaseType

/**
 * The phase the engine will actually enter next, with Forge's rule-based skips applied.
 *
 * `TurnInfo.nextPhase`/`nextStep` are the sole drivers of the client's two-line
 * priority button ("Next" over "To Combat", "End Turn" over "To End"); left at the
 * proto3 default the client reads `Phase.None`/`Step.None` and falls back to a bare
 * "Pass"/"My Turn". Arena sends the phase the engine will genuinely enter, not the
 * mechanical successor: on turn one Upkeep points at Main1 because the draw is
 * skipped, and DeclareAttack points at EndCombat when nobody attacked.
 *
 * This mirrors [PhaseType.getNext] plus `PhaseHandler.isSkippingPhase`, which is
 * private, so its conditions are restated here rather than called.
 */
object NextPhaseProjector {
    /**
     * Walk forward from [current] until a phase the game will not skip.
     *
     * @param skipDraw Forge: `turn == 1 && players.size == 2`.
     * @param skipCombat Forge: `playerTurn.isSkippingCombat()`.
     * @param skipDamageSteps Forge: `!inCombat() || combat.attackers.isEmpty()`.
     * @param phasesReversed Forge's `isTopsy` — `playerTurn.isPhasesReversed()`.
     */
    fun project(
        current: PhaseType?,
        skipDraw: Boolean,
        skipCombat: Boolean,
        skipDamageSteps: Boolean,
        phasesReversed: Boolean,
    ): PhaseType? {
        if (current == null) return null
        var next = PhaseType.getNext(current, phasesReversed)
        // Bounded by the phase count: every phase is visited at most once before
        // the walk wraps, so a board that somehow skips everything still terminates.
        repeat(PhaseType.entries.size) {
            if (!isSkipped(next, skipDraw, skipCombat, skipDamageSteps)) return next
            next = PhaseType.getNext(next, phasesReversed)
        }
        return next
    }

    private fun isSkipped(
        phase: PhaseType,
        skipDraw: Boolean,
        skipCombat: Boolean,
        skipDamageSteps: Boolean,
    ): Boolean =
        when (phase) {
            PhaseType.DRAW -> skipDraw
            PhaseType.COMBAT_BEGIN, PhaseType.COMBAT_DECLARE_ATTACKERS -> skipCombat
            PhaseType.COMBAT_DECLARE_BLOCKERS,
            PhaseType.COMBAT_FIRST_STRIKE_DAMAGE,
            PhaseType.COMBAT_DAMAGE,
            -> skipDamageSteps
            PhaseType.UNTAP, PhaseType.UPKEEP, PhaseType.MAIN1, PhaseType.COMBAT_END,
            PhaseType.MAIN2, PhaseType.END_OF_TURN, PhaseType.CLEANUP,
            -> false
        }
}
