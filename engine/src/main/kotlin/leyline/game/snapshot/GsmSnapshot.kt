package leyline.game.snapshot

import forge.game.Game
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AbilityWordScanner
import leyline.game.state.GameBridge
import org.jetbrains.annotations.VisibleForTesting

/**
 * Immutable per-frame snapshot of every field the GSM pipeline reads from
 * the engine. Built once per bundle at entry; every downstream stage is a
 * pure function of it.
 *
 * Per-card state lives on [boundCards] as [BoundCard] — pairs the live
 * [CardSnapshot] with static [leyline.game.data.CardData], pre-resolved
 * alt-cost bindings, designations, and parent linkage. Downstream reducers
 * consume this stable cut; the outer mapper still owns explicit shell reads.
 * The [objects] map is a derived view exposing each [BoundCard]'s underlying
 * [CardSnapshot] by ForgeCardId.
 */
@Suppress("LongParameterList")
class GsmSnapshot internal constructor(
    val matchId: String,
    val gameStateId: Int,
    val seats: List<SeatSnapshot>,
    val zones: Map<Int, ZoneSnapshot>,
    val boundCards: Map<ForgeCardId, BoundCard>,
    val stack: StackSnapshot,
    val phase: PhaseSnapshot,
    val combat: CombatSnapshot?,
    val abilityWordEntries: List<AbilityWordScanner.AbilityWordEntry>,
    val pendingTriggers: List<PendingTriggerSnapshot>,
    /** Per-seat Venture/dungeon state projected into DungeonStatus annotations. */
    val dungeonStates: Map<SeatId, DungeonStateSnapshot> = emptyMap(),
    val capturedAt: CaptureMarker,
    /** Game-scope Day/Night state, mirroring `forge.game.Game.getDayTime()`.
     *  `null` = neither (pre-first-transition), `false` = Day, `true` = Night.
     *  Read directly each snapshot — Forge owns the rules-side flip; the bridge
     *  observes via state-tail diff on this field. */
    val dayTime: Boolean? = null,
    /** Active player's current-turn spell count — `Player.getSpellsCastThisTurn()`
     *  on `phase.activePlayer`. Resets at turn boundary; increments per cast.
     *  Surfaced as `ActivePlayerSpellCount` on the persistent Day/Night
     *  `Designation` annotation. The `0` default is paired with `dayTime=null`
     *  in normal flow — pre-game / between-turn-boundary edges where
     *  `playerTurn` is unset still emit no Day/Night annotation, so the 0
     *  fallback is a no-op for consumers. */
    val activePlayerSpellsCastThisTurn: Int = 0,
) {
    /**
     * Derived view exposing each [BoundCard]'s underlying [CardSnapshot] by
     * ForgeCardId. Memoized — diff-time loops iterate `snap.objects`
     * repeatedly and a recomputing accessor would re-allocate per access.
     */
    val objects: Map<ForgeCardId, CardSnapshot> by lazy {
        boundCards.mapValues { it.value.snapshot }
    }

    internal fun withGameStateId(gameStateId: Int): GsmSnapshot =
        if (gameStateId == this.gameStateId) {
            this
        } else {
            GsmSnapshot(
                matchId = matchId,
                gameStateId = gameStateId,
                seats = seats,
                zones = zones,
                boundCards = boundCards,
                stack = stack,
                phase = phase,
                combat = combat,
                abilityWordEntries = abilityWordEntries,
                pendingTriggers = pendingTriggers,
                dungeonStates = dungeonStates,
                capturedAt = capturedAt,
                dayTime = dayTime,
                activePlayerSpellsCastThisTurn = activePlayerSpellsCastThisTurn,
            )
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GsmSnapshot) return false
        // CaptureMarker excluded — wallClock is non-deterministic.
        return matchId == other.matchId &&
            gameStateId == other.gameStateId &&
            seats == other.seats &&
            zones == other.zones &&
            boundCards == other.boundCards &&
            stack == other.stack &&
            phase == other.phase &&
            combat == other.combat &&
            abilityWordEntries == other.abilityWordEntries &&
            pendingTriggers == other.pendingTriggers &&
            dungeonStates == other.dungeonStates &&
            dayTime == other.dayTime &&
            activePlayerSpellsCastThisTurn == other.activePlayerSpellsCastThisTurn
    }

    override fun hashCode(): Int {
        var h = matchId.hashCode()
        h = 31 * h + gameStateId
        h = 31 * h + seats.hashCode()
        h = 31 * h + zones.hashCode()
        h = 31 * h + boundCards.hashCode()
        h = 31 * h + stack.hashCode()
        h = 31 * h + phase.hashCode()
        h = 31 * h + (combat?.hashCode() ?: 0)
        h = 31 * h + abilityWordEntries.hashCode()
        h = 31 * h + pendingTriggers.hashCode()
        h = 31 * h + dungeonStates.hashCode()
        h = 31 * h + (dayTime?.hashCode() ?: 0)
        h = 31 * h + activePlayerSpellsCastThisTurn
        return h
    }

    companion object {
        /** Production capture — reads game + bridge. */
        fun capture(
            game: Game,
            bridge: GameBridge,
            matchId: String,
            gameStateId: Int,
        ): GsmSnapshot = SnapshotCapture.run(game, bridge, matchId, gameStateId)

        /** Test fixture builder — named args with sensible defaults. */
        @VisibleForTesting
        @Suppress("LongParameterList")
        fun forTest(
            matchId: String = "test-match",
            gameStateId: Int = 0,
            seats: List<SeatSnapshot> = emptyList(),
            zones: Map<Int, ZoneSnapshot> = emptyMap(),
            objects: Map<ForgeCardId, CardSnapshot> = emptyMap(),
            boundCards: Map<ForgeCardId, BoundCard>? = null,
            stack: StackSnapshot = StackSnapshot(emptyList()),
            phase: PhaseSnapshot =
                PhaseSnapshot(
                    turn = 1,
                    activePlayer = SeatId(1),
                    priorityPlayer = SeatId(1),
                    phase = null,
                ),
            combat: CombatSnapshot? = null,
            abilityWordEntries: List<AbilityWordScanner.AbilityWordEntry> = emptyList(),
            pendingTriggers: List<PendingTriggerSnapshot> = emptyList(),
            dungeonStates: Map<SeatId, DungeonStateSnapshot> = emptyMap(),
            capturedAt: CaptureMarker = CaptureMarker.unknown(),
            dayTime: Boolean? = null,
            activePlayerSpellsCastThisTurn: Int = 0,
        ): GsmSnapshot {
            val resolvedBoundCards =
                boundCards
                    ?: objects.mapValues { (fid, snap) -> BoundCard(fid, snap, data = null) }
            return GsmSnapshot(
                matchId,
                gameStateId,
                seats,
                zones,
                resolvedBoundCards,
                stack,
                phase,
                combat,
                abilityWordEntries,
                pendingTriggers,
                dungeonStates,
                capturedAt,
                dayTime,
                activePlayerSpellsCastThisTurn,
            )
        }
    }
}
