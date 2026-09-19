package leyline.bridge.coord

import forge.game.Game
import forge.game.phase.PhaseType
import leyline.bridge.types.AutoPassReason
import leyline.bridge.types.PriorityDecision
import leyline.game.mapping.StopTypeMapping
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

internal data class PriorityStackObject(
    val id: Int,
    val controllerId: Int,
)

/** Engine facts sampled together at a genuine rules-priority boundary. */
internal data class PriorityWindowObservation(
    val isOwnTurn: Boolean,
    val phase: PhaseType?,
    val smartPhaseSkip: Boolean,
    val promptJustResolved: Boolean,
    val stackEmpty: Boolean,
    val forceVisible: Boolean,
    val hasMeaningfulAction: Boolean,
    val turn: Int = 0,
    val playerId: Int = 1,
    val stack: List<PriorityStackObject> = emptyList(),
)

internal sealed interface PriorityWindowDecision {
    data class Present(
        val mode: PriorityWindowMode,
        val autoResolve: Boolean,
    ) : PriorityWindowDecision

    data class Skip(
        val reason: AutoPassReason,
    ) : PriorityWindowDecision
}

private enum class PriorityWindowReason(
    val visible: Boolean,
) {
    FULL_CONTROL(true),
    ACTION_HOLD(true),
    EXPLICIT_STOP(true),
    MANUAL_MANA(true),
    TURN_YIELD(false),
    STACK_YIELD(false),
    NO_EXECUTABLE_ACTION(false),
    OWN_STACK(true),
    OPPONENT_STACK(true),
    ENABLED_PHASE(true),
    PHASE_NOT_ENABLED(false),
}

/** Owns priority preferences, explicit stops and bounded player-requested yields. */
class PriorityPolicyRuntime(
    private val matchId: String? = null,
) {
    private val log = LoggerFactory.getLogger(PriorityPolicyRuntime::class.java)
    private val stateLock = Any()
    private var settings = defaultSettings()
    private var settingsChanged = false
    private var humanPlayerId: Int? = null
    private var opponentPlayerId: Int? = null
    private var lastObservation: PriorityWindowObservation? = null

    private data class StopKey(
        val type: StopType,
        val scope: SettingScope,
    )

    private data class Occurrence(
        val turn: Int,
        val ownTurn: Boolean,
        val phase: PhaseType?,
    )

    private val reachedStops = mutableMapOf<StopKey, Occurrence>()
    private var turnYield: Int? = null
    private var yieldStackIds: Set<Int>? = null
    private var pendingHold = false
    private var holdNextPriority = false

    /** Replace game identities while retaining preferences and clearing game-scoped controls. */
    fun installPhaseStops(
        humanPlayerId: Int,
        opponentPlayerId: Int,
    ) {
        synchronized(stateLock) {
            this.humanPlayerId = humanPlayerId
            this.opponentPlayerId = opponentPlayerId
            if (lastObservation != null) {
                settings =
                    settings
                        .toBuilder()
                        .setAutoPassOption(normalOption())
                        .setStackAutoPassOption(AutoPassOption.Clear_a465)
                        .clearTransientStops()
                        .addAllTransientStops(defaultSettings().transientStopsList)
                        .build()
            }
            lastObservation = null
            reachedStops.clear()
            turnYield = null
            yieldStackIds = null
            pendingHold = false
            holdNextPriority = false
        }
    }

    fun currentSettings(): SettingsMessage = synchronized(stateLock) { settings }

    /** Consumed by the coordinator when publishing the next engine-owned horizon. */
    internal fun takeChangedSettings(): SettingsMessage? =
        synchronized(stateLock) {
            if (!settingsChanged) null else settings.also { settingsChanged = false }
        }

    fun enabledPhaseStops(playerId: Int): Set<PhaseType> =
        synchronized(stateLock) {
            val scope =
                if (playerId ==
                    humanPlayerId
                ) {
                    SettingScope.Team_ac6e
                } else if (playerId == opponentPlayerId) {
                    SettingScope.Opponents
                } else {
                    null
                }
            if (scope == null) emptySet() else StopTypeMapping.parseStops(settings.stopsList, scope)
        }

    /**
     * Stops that apply at this phase boundary. Arena scopes its stop table by whose
     * turn it is — `StopsInLocalPlayersTurn` and `StopsInOpponentsTurn` in the client's
     * `GreClient.Rules.GameSettings` — not by which player holds priority. Callers hold
     * [stateLock].
     */
    private fun turnScopedStops(isOwnTurn: Boolean): Set<PhaseType> =
        StopTypeMapping.parseStops(
            settings.stopsList,
            if (isOwnTurn) SettingScope.Team_ac6e else SettingScope.Opponents,
        )

    fun isPhaseStopped(
        playerId: Int,
        phase: PhaseType,
    ): Boolean = phase in enabledPhaseStops(playerId)

    fun hasOpponentStop(phase: PhaseType): Boolean =
        synchronized(stateLock) {
            phase in StopTypeMapping.parseStops(settings.transientStopsList, SettingScope.Opponents)
        }

    fun shouldStopForOpponent(
        isAiTurn: Boolean,
        phase: PhaseType?,
    ): Boolean = isAiTurn && phase != null && hasOpponentStop(phase)

    fun isFullControl(): Boolean = synchronized(stateLock) { settings.autoPassOption == AutoPassOption.FullControl }

    fun shouldAutoPass(): Boolean = synchronized(stateLock) { settings.autoPassOption != AutoPassOption.FullControl && !holdNextPriority }

    /** Merge a protocol delta; turnNumber identifies the scope of a requested turn yield. */
    fun submit(
        incoming: SettingsMessage,
        turnNumber: Int = 0,
    ): SettingsMessage =
        synchronized(stateLock) {
            val builder = settings.toBuilder().mergeFrom(incoming)
            builder.clearStops().addAllStops(
                mergeStops(settings.stopsList, incoming.stopsList, incoming.clearAllStops == SettingStatus.Set),
            )
            builder.clearTransientStops().addAllTransientStops(
                mergeStops(
                    settings.transientStopsList,
                    incoming.transientStopsList,
                    incoming.clearAllStops == SettingStatus.Set,
                ),
            )
            if (incoming.clearAllStops == SettingStatus.Set) reachedStops.clear()
            incoming.transientStopsList.forEach { stop ->
                scopes(stop.appliesTo).forEach { reachedStops.remove(StopKey(stop.stopType, it)) }
            }
            settings = builder.clearClearAllStops().clearClearAllYields().build()
            if (incoming.clearAllYields == SettingStatus.Set) {
                settings =
                    settings
                        .toBuilder()
                        .setAutoPassOption(normalOption())
                        .setStackAutoPassOption(AutoPassOption.Clear_a465)
                        .build()
            }
            if (incoming.autoPassOption == AutoPassOption.Clear_a465) {
                settings = settings.toBuilder().setAutoPassOption(normalOption()).build()
                pendingHold = false
                holdNextPriority = false
            }
            if (incoming.autoPassOption != AutoPassOption.None_a465 || incoming.clearAllYields == SettingStatus.Set) {
                turnYield =
                    if (settings.autoPassOption ==
                        AutoPassOption.UnlessOpponentAction
                    ) {
                        turnNumber.takeIf { it > 0 } ?: lastObservation?.turn
                    } else {
                        null
                    }
                yieldStackIds = lastObservation?.stack?.mapTo(mutableSetOf()) { it.id }
            }
            if (incoming.stackAutoPassOption != AutoPassOption.None_a465) {
                yieldStackIds = lastObservation?.stack?.mapTo(mutableSetOf()) { it.id }
            }
            if (settings.autoPassOption == AutoPassOption.FullControl) cancelYields()
            settings
        }

    /** The wire hold applies after a completed action, not to unrelated future turns. */
    fun submitAutoPassPriority(priority: AutoPassPriority) =
        synchronized(stateLock) {
            if (priority != AutoPassPriority.None_a099) pendingHold = priority == AutoPassPriority.No_a099
        }

    internal fun actionCompleted(success: Boolean) =
        synchronized(stateLock) {
            holdNextPriority = success && pendingHold
            pendingHold = false
        }

    internal fun requestTurnYield(turn: Int) =
        synchronized(stateLock) {
            submit(SettingsMessage.newBuilder().setAutoPassOption(AutoPassOption.UnlessOpponentAction).build(), turn)
            settingsChanged = true
        }

    internal fun classifyPriorityWindow(observation: PriorityWindowObservation): PriorityWindowDecision =
        synchronized(stateLock) {
            val forcedStop = advanceControls(observation)
            val held = holdNextPriority
            holdNextPriority = false

            val reason =
                when {
                    settings.autoPassOption == AutoPassOption.FullControl -> PriorityWindowReason.FULL_CONTROL
                    held -> PriorityWindowReason.ACTION_HOLD
                    forcedStop -> PriorityWindowReason.EXPLICIT_STOP
                    observation.forceVisible -> PriorityWindowReason.MANUAL_MANA
                    settings.autoPassOption == AutoPassOption.UnlessOpponentAction -> PriorityWindowReason.TURN_YIELD
                    settings.stackAutoPassOption == AutoPassOption.ResolveAll -> PriorityWindowReason.STACK_YIELD
                    !observation.hasMeaningfulAction -> PriorityWindowReason.NO_EXECUTABLE_ACTION
                    !observation.stackEmpty &&
                        observation.stack.firstOrNull()?.controllerId == observation.playerId -> PriorityWindowReason.OWN_STACK
                    !observation.stackEmpty -> PriorityWindowReason.OPPONENT_STACK
                    observation.phase in turnScopedStops(observation.isOwnTurn) -> PriorityWindowReason.ENABLED_PHASE
                    else -> PriorityWindowReason.PHASE_NOT_ENABLED
                }
            val visible = reason.visible
            val decision =
                if (visible) {
                    PriorityWindowDecision.Present(PriorityWindowMode.Visible, autoResolve = false)
                } else if (!observation.stackEmpty ||
                    observation.promptJustResolved ||
                    !observation.smartPhaseSkip
                ) {
                    PriorityWindowDecision.Present(PriorityWindowMode.SyncOnly, autoResolve = true)
                } else {
                    PriorityWindowDecision.Skip(AutoPassReason.SmartPhaseSkip)
                }
            log
                .atDebug()
                .addKeyValue("event", "match.priority_decision")
                .addKeyValue("match_id", matchId)
                .addKeyValue("turn", observation.turn)
                .addKeyValue("phase", observation.phase?.name)
                .addKeyValue("reason", reason.name.lowercase())
                .addKeyValue("visible", visible)
                .addKeyValue("stack_top_id", observation.stack.firstOrNull()?.id)
                .addKeyValue("stack_controller", observation.stack.firstOrNull()?.controllerId)
                .log("Priority classified")
            decision
        }

    /** Update control lifetimes while holding stateLock, before deciding visibility. */
    private fun advanceControls(observation: PriorityWindowObservation): Boolean {
        val before = settings
        val occurrence = Occurrence(observation.turn, observation.isOwnTurn, observation.phase)
        val expired = reachedStops.filterValues { it != occurrence }.keys
        if (expired.isNotEmpty()) {
            settings =
                settings
                    .toBuilder()
                    .clearTransientStops()
                    .addAllTransientStops(
                        settings.transientStopsList.map { stop ->
                            if (StopKey(stop.stopType, stop.appliesTo) in
                                expired
                            ) {
                                stop.toBuilder().setStatus(SettingStatus.Clear_a3fe).build()
                            } else {
                                stop
                            }
                        },
                    ).build()
            expired.toList().forEach(reachedStops::remove)
        }
        val forcedStop =
            settings.transientStopsList.any { stop ->
                val matches =
                    stop.status == SettingStatus.Set &&
                        stop.appliesTo == (if (observation.isOwnTurn) SettingScope.Team_ac6e else SettingScope.Opponents) &&
                        StopTypeMapping.toPhaseType(stop.stopType) == observation.phase
                if (matches) reachedStops[StopKey(stop.stopType, stop.appliesTo)] = occurrence
                matches
            }
        val currentStackIds = observation.stack.mapTo(mutableSetOf()) { it.id }
        val knownIds = yieldStackIds
        val newOpponentObject =
            knownIds != null && observation.stack.any { it.id !in knownIds && it.controllerId != observation.playerId }
        if (settings.autoPassOption == AutoPassOption.UnlessOpponentAction && turnYield == null) turnYield = observation.turn
        val turnExpired = turnYield != null && turnYield != observation.turn
        val stackDrained = settings.stackAutoPassOption == AutoPassOption.ResolveAll && observation.stackEmpty
        if (turnExpired || newOpponentObject || forcedStop) {
            cancelYields()
        } else if (stackDrained) {
            settings = settings.toBuilder().setStackAutoPassOption(AutoPassOption.Clear_a465).build()
        }
        if (settings.autoPassOption == AutoPassOption.UnlessOpponentAction ||
            settings.stackAutoPassOption == AutoPassOption.ResolveAll
        ) {
            yieldStackIds = knownIds.orEmpty() + currentStackIds
        } else {
            yieldStackIds = null
        }
        lastObservation = observation
        if (before != settings) settingsChanged = true
        return forcedStop
    }

    private fun cancelYields() {
        if (settings.autoPassOption == AutoPassOption.UnlessOpponentAction || settings.autoPassOption == AutoPassOption.ResolveAll) {
            settings = settings.toBuilder().setAutoPassOption(normalOption()).build()
        }
        if (settings.stackAutoPassOption ==
            AutoPassOption.ResolveAll
        ) {
            settings = settings.toBuilder().setStackAutoPassOption(AutoPassOption.Clear_a465).build()
        }
        turnYield = null
        yieldStackIds = null
    }

    private fun normalOption(): AutoPassOption =
        settings.defaultAutoPassOption.takeIf { it == AutoPassOption.ResolveMyStackEffects } ?: AutoPassOption.ResolveMyStackEffects

    private fun scopes(scope: SettingScope): List<SettingScope> =
        if (scope ==
            SettingScope.AnyPlayer
        ) {
            listOf(SettingScope.Team_ac6e, SettingScope.Opponents)
        } else {
            listOf(scope)
        }

    private fun mergeStops(
        existing: List<Stop>,
        incoming: List<Stop>,
        clear: Boolean,
    ): List<Stop> {
        val stops = linkedMapOf<StopKey, Stop>()
        existing.forEach { stop ->
            stops[StopKey(stop.stopType, stop.appliesTo)] =
                if (clear) stop.toBuilder().setStatus(SettingStatus.Clear_a3fe).build() else stop
        }
        incoming.forEach { stop ->
            scopes(stop.appliesTo).forEach { scope ->
                stops[StopKey(stop.stopType, scope)] = stop.toBuilder().setAppliesTo(scope).build()
            }
        }
        return stops.values.toList()
    }

    internal fun recordDecision(
        game: Game,
        decision: PriorityDecision,
    ) {
        val skipped = decision as? PriorityDecision.Skip ?: return
        log
            .atDebug()
            .addKeyValue("event", "match.priority_skipped")
            .addKeyValue("match_id", matchId)
            .addKeyValue("reason", skipped.reason.toString())
            .addKeyValue("phase", game.phaseHandler.phase?.name)
            .addKeyValue("turn", game.phaseHandler.turn)
            .log("Priority skipped")
    }

    companion object {
        /** Default stop settings matching the expected initial configuration. */
        fun defaultSettings(): SettingsMessage {
            // (StopType, Team status, Opponents status)
            val stopDefs =
                listOf(
                    Triple(StopType.UpkeepStep, SettingStatus.Clear_a3fe, SettingStatus.Clear_a3fe),
                    Triple(StopType.DrawStep, SettingStatus.Clear_a3fe, SettingStatus.Clear_a3fe),
                    Triple(StopType.PrecombatMainPhase, SettingStatus.Set, SettingStatus.Clear_a3fe),
                    Triple(StopType.BeginCombatStep, SettingStatus.Set, SettingStatus.Set),
                    Triple(StopType.DeclareAttackersStep, SettingStatus.Set, SettingStatus.Set),
                    Triple(StopType.DeclareBlockersStep, SettingStatus.Set, SettingStatus.Set),
                    Triple(StopType.CombatDamageStep, SettingStatus.Clear_a3fe, SettingStatus.Clear_a3fe),
                    Triple(StopType.EndCombatStep, SettingStatus.Clear_a3fe, SettingStatus.Clear_a3fe),
                    Triple(StopType.PostcombatMainPhase, SettingStatus.Set, SettingStatus.Clear_a3fe),
                    Triple(StopType.EndStep_ad1f, SettingStatus.Clear_a3fe, SettingStatus.Set),
                    Triple(StopType.FirstStrikeDamageStep, SettingStatus.Set, SettingStatus.Set),
                )
            val builder = SettingsMessage.newBuilder()
            for ((type, teamStatus, oppStatus) in stopDefs) {
                builder.addStops(
                    Stop
                        .newBuilder()
                        .setStopType(type)
                        .setAppliesTo(SettingScope.Team_ac6e)
                        .setStatus(teamStatus),
                )
                builder.addStops(
                    Stop
                        .newBuilder()
                        .setStopType(type)
                        .setAppliesTo(SettingScope.Opponents)
                        .setStatus(oppStatus),
                )
                // Transient stops — all Clear
                builder.addTransientStops(
                    Stop
                        .newBuilder()
                        .setStopType(type)
                        .setAppliesTo(SettingScope.Team_ac6e)
                        .setStatus(SettingStatus.Clear_a3fe),
                )
                builder.addTransientStops(
                    Stop
                        .newBuilder()
                        .setStopType(type)
                        .setAppliesTo(SettingScope.Opponents)
                        .setStatus(SettingStatus.Clear_a3fe),
                )
            }
            builder
                .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                .setGraveyardOrder(OrderingType.OrderArbitraryAlways)
                .setManaSelectionType(ManaSelectionType.Auto_a88a)
                .setDefaultAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                .setSmartStopsSetting(SmartStopsSetting.Enable_a188)
                .setAutoTapStopsSetting(AutoTapStopsSetting.Enable_ac12)
                .setAutoOptionalPaymentCancellationSetting(Setting.Enable_a20a)
                .setStackAutoPassOption(AutoPassOption.Clear_a465)
            return builder.build()
        }
    }
}
