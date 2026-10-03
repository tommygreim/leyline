package leyline.bridge.coord

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.PlaybackCutReason
import leyline.game.PlaybackCutRequest
import leyline.game.bundle.BundleBuilder
import leyline.game.event.DamageSourceKind
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.combatDamageFact
import leyline.game.shouldSplitCombatDamageWindow
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step

/** Pure frame planning for the established first-strike/combat-damage wire split. */
internal object CombatPlaybackFramePlanner {
    fun plan(
        request: PlaybackCutRequest,
        events: FrameEventLog,
        defaultSeat: SeatId,
        currentTurnSeat: Int?,
        matchSeats: Set<Int>,
        sourceControllerSeats: Map<ForgeCardId, Int>,
    ): List<BundleBuilder.PlaybackFrameSpec> {
        if (!events.events.shouldSplitCombatDamageWindow()) {
            return listOf(
                BundleBuilder.PlaybackFrameSpec(
                    events,
                    turnStarted = request.turnStarted,
                    // Arena's compulsory draw frame is only a zone transfer.
                    // Advertising the newly drawn card as castable in that same
                    // frame makes the client stage it at screen centre before
                    // the later priority frame moves it into the hand.
                    embedActions = request.reason != PlaybackCutReason.DrawStep,
                ),
            )
        }
        val damageFrames =
            events.events.combatDamageFrames(defaultSeat, currentTurnSeat, matchSeats, sourceControllerSeats)
        val endCombatEvents =
            events.events.filter { event ->
                event is GameEvent.PhaseChanged && event.step == Step.EndCombat_a2cb.number
            }
        return buildList {
            damageFrames.forEach { frame ->
                add(BundleBuilder.PlaybackFrameSpec(FrameEventLog(frame.events), lifeTotals = frame.lifeTotals))
            }
            if (endCombatEvents.isNotEmpty()) add(BundleBuilder.PlaybackFrameSpec(FrameEventLog(endCombatEvents)))
        }
    }

    private data class CombatDamageFrame(
        val events: List<GameEvent>,
        val lifeTotals: Map<Int, Int> = emptyMap(),
    )

    private fun List<GameEvent>.hasCombatDamage(): Boolean = any { it.combatDamageFact() == true }

    private fun List<GameEvent>.combatDamageFrames(
        defaultSeat: SeatId,
        currentTurnSeat: Int?,
        matchSeats: Set<Int>,
        sourceControllerSeats: Map<ForgeCardId, Int>,
    ): List<CombatDamageFrame> {
        if (!canSafelySplitCombatDamage()) {
            return listOf(
                CombatDamageFrame(
                    filterNot { event -> event is GameEvent.PhaseChanged }
                        .prependCombatDamagePhase(this, defaultSeat, currentTurnSeat, matchSeats, sourceControllerSeats),
                ),
            )
        }

        val frames = mutableListOf<CombatDamageFrame>()
        val current = mutableListOf<GameEvent>()

        fun flushFrame() {
            if (current.hasCombatDamage()) frames += CombatDamageFrame(current.toList(), current.lifeTotals())
            current.clear()
        }
        for (event in this) {
            if (event is GameEvent.PhaseChanged) {
                if (event.isDamageStep()) {
                    if (current.hasCombatDamage()) {
                        flushFrame()
                    } else {
                        current.removeAll { pending -> pending is GameEvent.PhaseChanged && pending.isDamageStep() }
                    }
                    current += event
                }
                continue
            }
            current += event
        }
        flushFrame()
        if (frames.isNotEmpty()) return frames
        return listOf(
            CombatDamageFrame(
                filterNot { event -> event is GameEvent.PhaseChanged }
                    .prependCombatDamagePhase(this, defaultSeat, currentTurnSeat, matchSeats, sourceControllerSeats),
            ),
        )
    }

    private fun List<GameEvent>.canSafelySplitCombatDamage(): Boolean {
        if (!shouldSplitCombatDamageWindow()) return false
        var inDamageStep = false
        for (event in this) {
            val damageFact = event.combatDamageFact()
            if (event is GameEvent.PhaseChanged) {
                if (event.isDamageStep()) inDamageStep = true
            } else if (damageFact != null) {
                if (!damageFact || !inDamageStep || event is GameEvent.DamageDealtToCard) return false
            } else if (event is GameEvent.LifeChanged || event == GameEvent.CombatEnded) {
                if (!inDamageStep) return false
            } else if (!inDamageStep && !event.isSafeBeforeDamageStep()) {
                return false
            }
        }
        return true
    }

    private fun GameEvent.isSafeBeforeDamageStep(): Boolean =
        this is GameEvent.CardTapped || this is GameEvent.AttackersDeclared || this is GameEvent.BlockersDeclared

    private fun List<GameEvent>.lifeTotals(): Map<Int, Int> =
        filterIsInstance<GameEvent.LifeChanged>().associate { event -> event.seatId.value to event.newLife }

    private fun GameEvent.PhaseChanged.isDamageStep(): Boolean =
        step == Step.FirstStrikeDamage_a2cb.number || step == Step.CombatDamage_a2cb.number

    private fun List<GameEvent>.prependCombatDamagePhase(
        sourceEvents: List<GameEvent>,
        defaultSeat: SeatId,
        currentTurnSeat: Int?,
        matchSeats: Set<Int>,
        sourceControllerSeats: Map<ForgeCardId, Int>,
    ): List<GameEvent> {
        val activeSeat =
            combatDamageSourceSeat(sourceEvents, matchSeats, sourceControllerSeats) ?: currentTurnSeat ?: defaultSeat.value
        return listOf(
            GameEvent.PhaseChanged(
                SeatId(activeSeat),
                Phase.Combat_a549.number,
                sourceEvents.combatDamageStep(),
            ),
        ) + this
    }

    private fun List<GameEvent>.combatDamageStep(): Int =
        run {
            var currentDamageStep = Step.CombatDamage_a2cb.number
            for (event in this) {
                if (event is GameEvent.PhaseChanged &&
                    (event.step == Step.FirstStrikeDamage_a2cb.number || event.step == Step.CombatDamage_a2cb.number)
                ) {
                    currentDamageStep = event.step
                }
                if (event.combatDamageFact() == true) return@run currentDamageStep
            }
            Step.CombatDamage_a2cb.number
        }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun combatDamageSourceSeat(
        events: List<GameEvent>,
        matchSeats: Set<Int>,
        sourceControllerSeats: Map<ForgeCardId, Int>,
    ): Int? {
        events
            .firstNotNullOfOrNull { event ->
                (event as? GameEvent.DamageDealtToPlayer)
                    ?.takeIf { it.sourceKind == DamageSourceKind.Combat }
                    ?.targetSeatId
                    ?.value
            }?.let { defenderSeat ->
                val otherSeats = matchSeats - defenderSeat
                if (otherSeats.size == 1) return otherSeats.single()
                return if (defenderSeat == 1) 2 else 1
            }
        val sourceId =
            events.firstNotNullOfOrNull { event ->
                when (event) {
                    is GameEvent.DamageDealtToCard -> event.sourceCardId.takeIf { event.sourceKind == DamageSourceKind.Combat }
                    is GameEvent.DamageDealtToPlayer -> event.sourceCardId.takeIf { event.sourceKind == DamageSourceKind.Combat }
                    else -> null
                }
            }
        return sourceId?.let(sourceControllerSeats::get)
    }
}
