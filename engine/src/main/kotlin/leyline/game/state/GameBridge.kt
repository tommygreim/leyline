package leyline.game.state

import forge.ai.LobbyPlayerAi
import forge.game.Game
import forge.game.GameEntity
import forge.game.GameType
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.card.CardTraitChanges
import forge.game.player.Player
import forge.game.player.PlayerView
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import forge.gamemodes.puzzle.Puzzle
import forge.player.PlayerControllerHuman
import forge.util.MyRandom
import leyline.DevCheck
import leyline.bridge.bootstrap.DeckLoader
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.coord.GameLoopController
import leyline.bridge.coord.MatchCutCoordinator
import leyline.bridge.coord.PriorityPolicyRuntime
import leyline.bridge.forge.RevealTrackingAiController
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.BlockingInteractionRuntime
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.MulliganBridge
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PublishedOneShotPayCostsInteraction
import leyline.bridge.handoff.RuntimeHorizonMode
import leyline.bridge.types.AbilityDefinitionRef
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.MulliganPhase
import leyline.bridge.types.PrioritySignal
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.bridge.types.SeatId
import leyline.bridge.types.Seating
import leyline.bridge.types.opponent
import leyline.config.EngineSettings
import leyline.domain.deck.DeckSource
import leyline.game.GamePlayback
import leyline.game.annotations.AnnotationBuilder
import leyline.game.bundle.LogicalSequenceState
import leyline.game.codes.CounterTypes
import leyline.game.data.CardData
import leyline.game.data.CardProtoBuilder
import leyline.game.data.CardRepository
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.GameEventCollector
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.StateProjectionEnvironmentCapture
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GrpIdResolver
import leyline.game.snapshot.GsmSnapshot
import org.jetbrains.annotations.VisibleForTesting
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import java.lang.reflect.InvocationTargetException
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import forge.game.player.PlayerController as ForgePlayerController
import leyline.bridge.forge.PlayerController as BridgedPlayerController

/**
 * Bridges the client protocol to a real Forge [Game] engine.
 *
 * Creates a constructed game (human seat 1 + AI seat 2), starts the game loop,
 * and blocks until the engine reaches mulligan. The client handler reads hands
 * and submits keep/mull decisions through this bridge.
 *
 * [ProjectionState] is the single committed authority for client-facing history.
 * Prompt journals and live Forge reads remain shell-owned observations.
 *
 * Threading: [start] blocks the caller (~2-3s first call for card DB, <100ms after).
 * The engine thread blocks at mulligan via [MulliganBridge]. Projection compile
 * through commit is serialized per bridge; engine-thread lifecycle writes must
 * not overlap that boundary.
 */
@Suppress("LargeClass")
class GameBridge(
    /** Stable match identifier for cut compilation and outbound envelopes. */
    private val matchId: String = "forge-match-1",
    /** Timeout for player priority/action windows. Null waits indefinitely. */
    private val bridgeTimeoutMs: Long? = null,
    /** Timeout for client-visible prompts. Null waits indefinitely. */
    private val promptFailsafeMs: Long? = DEFAULT_PROMPT_FAILSAFE_TIMEOUT_MS,
    /** Whether runtime horizons are observed and delivered outside the engine loop. */
    private val runtimeHorizonMode: RuntimeHorizonMode = RuntimeHorizonMode.Direct,
    /** Playtest config — controls AI speed, die roll, etc. */
    val engineSettings: EngineSettings = EngineSettings(),
    /** Logical sequence at match creation, normally the protocol defaults. */
    initialSequence: LogicalSequenceState = LogicalSequenceState(),
    /** Card data repository — lookups for grpId ↔ name, card metadata. */
    val cardRepository: CardRepository,
    /** Proto builder for GameObjectInfo — uses [cardRepository] for static card data. */
    val cardProto: CardProtoBuilder = CardProtoBuilder(cardRepository),
) : IdMapping,
    PlayerLookup,
    RuntimePriorityView,
    ZoneTracking,
    AnnotationIds,
    EventDrain {
    private val log = LoggerFactory.getLogger(GameBridge::class.java)

    /** One imperative-shell owner for projection cuts and committed playback feeds. */
    internal val cutCoordinator =
        MatchCutCoordinator(
            bridge = this,
            matchId = matchId,
            delayMultiplier = engineSettings.aiDelayMultiplier,
        )

    /** Shell observation state; it does not allocate logical output. */
    val responseAcceptance = ResponseAcceptanceTracker()

    /** Match-scoped owner of mutable priority policy and client settings. */
    val priorityPolicy = PriorityPolicyRuntime(matchId = matchId)
    private val seatPriorityPolicies = mutableMapOf<SeatId, PriorityPolicyRuntime>()

    fun priorityPolicy(seatId: SeatId): PriorityPolicyRuntime = seatPriorityPolicies[seatId] ?: priorityPolicy

    var humanVsHuman: Boolean = false
        private set
    private val playersReady = java.util.concurrent.CountDownLatch(1)
    private val terminalSeats =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<SeatId>()

    fun markHumanTerminalDelivered(seatId: SeatId): Boolean {
        terminalSeats.add(seatId)
        return terminalSeats.size == 2
    }

    fun releaseHumanStartup() = playersReady.countDown()

    fun isInteractiveSeat(seatId: SeatId): Boolean = humanVsHuman || seatId == seating.humanSeat

    /** Puzzle application uses inert choices before journal/feed ownership starts. */
    private val setupBlockingInteractionRuntime =
        object : BlockingInteractionRuntime {
            override fun awaitOptional(
                interaction: BlockingInteraction.Optional,
                timeoutMs: Long?,
                defaultOnTimeout: Boolean,
            ): Boolean = defaultOnTimeout

            override fun awaitNumeric(
                interaction: BlockingInteraction.Numeric,
                timeoutMs: Long?,
            ): Int = interaction.defaultValue

            override fun awaitDamage(
                interaction: BlockingInteraction.Damage,
                attacker: Card,
                blockers: CardCollectionView,
                defender: GameEntity?,
                timeoutMs: Long?,
                fallback: () -> MutableMap<Card?, Int>?,
            ): MutableMap<Card?, Int>? = fallback()

            override fun takeCachedDamage(
                attacker: Card,
                blockers: CardCollectionView,
            ): MutableMap<Card?, Int>? = null
        }

    /** Rebind an unpublished puzzle priority window to its initial wire state. */
    fun bindInitialActionWindow(
        actionId: String,
        gameStateId: Int,
    ): ActionsAvailableReq = cutCoordinator.bindInitialActionWindow(actionId, gameStateId)

    /** Bind a client-owned puzzle horizon, or preserve a state-only barrier for runtime delivery. */
    fun bindInitialPuzzleHorizon(
        actionId: String,
        gameStateId: Int,
    ): ActionsAvailableReq? {
        val pending = actionBridge(seating.humanSeat).exactPending(actionId) ?: error("Puzzle action window is no longer pending")
        if (pending.state.kind != PendingActionKind.SYNC_ONLY) return bindInitialActionWindow(actionId, gameStateId)
        cutCoordinator.replaceWithPhaseTransition(actionId, includePriorityPrompt = false)
        return null
    }

    /** Immutable reference data shared by every projection path for this match. */
    internal val stateProjectionEnvironment by lazy { StateProjectionEnvironmentCapture.from(this) }

    /** Guards the committed projection value and compare-and-set installation. */
    internal val projectionLock = Any()

    private var projectionState = ProjectionState.initial(sequence = initialSequence)
    private val activeProjectionEditor = ThreadLocal<ProjectionState.Editor?>()

    internal fun projectionStateSnapshot(): ProjectionState = synchronized(projectionLock) { projectionState }

    fun committedSequence(): LogicalSequenceState = projectionStateSnapshot().sequence

    @VisibleForTesting
    internal fun replaceProjectionStateForTest(state: ProjectionState) {
        synchronized(projectionLock) { projectionState = state }
    }

    internal fun <T> editProjection(
        prior: ProjectionState,
        block: (ProjectionState.Editor) -> T,
    ): Pair<T, ProjectionState> {
        check(activeProjectionEditor.get() == null) { "Nested projection edits are not supported" }
        val editor = prior.editor()
        activeProjectionEditor.set(editor)
        return try {
            block(editor) to editor.freeze()
        } finally {
            activeProjectionEditor.remove()
        }
    }

    internal fun installProjection(transition: ProjectionTransition): Boolean =
        synchronized(projectionLock) {
            if (projectionState.revision != transition.expectedRevision) return false
            check(transition.nextState.revision == transition.expectedRevision + 1) {
                "Projection transition revision must advance exactly once"
            }
            projectionState = transition.nextState
            true
        }

    internal fun commitProjection(
        transition: ProjectionTransition,
        afterInstall: () -> Unit = {},
    ) {
        if (!installProjection(transition)) throw StaleProjectionTransitionException()
        afterInstall()
        pendingEarthbendResolutions.removeAll {
            it.version in transition.acknowledgements.consumedEarthbendResolutionVersions
        }
        consumePromptFacts(transition.acknowledgements.promptFacts)
    }

    private fun <T> updateProjection(block: (ProjectionState.Editor) -> T): T =
        synchronized(projectionLock) {
            val prior = projectionState
            val editor = prior.editor()
            val result = block(editor)
            val next = editor.freeze()
            check(next.sequence == prior.sequence) { "Direct projection edits cannot change logical sequence" }
            if (next.copy(revision = prior.revision) != prior) {
                projectionState = next
            }
            result
        }

    internal fun viewerProjectionCursor(seatId: SeatId = seating.humanSeat): ViewerProjectionCursor =
        projectionStateSnapshot().viewerCursors[seatId] ?: ViewerProjectionCursor()

    internal fun updateViewerProjectionCursor(
        seatId: SeatId = seating.humanSeat,
        block: (ViewerProjectionCursor) -> ViewerProjectionCursor,
    ) {
        updateProjection { editor ->
            editor.viewerCursors[seatId] = block(editor.viewerCursors[seatId] ?: ViewerProjectionCursor())
        }
    }

    private var game: Game? = null

    /** True when the active game uses Commander/Brawl/Oathbreaker rules. */
    val isBrawlOrCommander: Boolean
        get() = game?.let { isCommanderGame(it) } ?: false
    private val players: MutableMap<Int, Player> = mutableMapOf()
    private var loopController: GameLoopController? = null

    /** Committed cross-frame annotation correlation. Projection writes only through a tentative planner. */
    private val selectedSpellGrpIds = ConcurrentHashMap<ForgeCardId, Int>()
    private val selectedAdditionalCostGrpIds = ConcurrentHashMap<ForgeCardId, Int>()
    private val selectedChosenCostPromptIds = ConcurrentHashMap<ForgeCardId, Int>()
    private val stackAbilityIdentitiesByRuntimeId = ConcurrentHashMap<Int, ResolvedAbilityIdentity>()

    fun recordStackAbilityIdentity(
        runtimeAbilityId: Int,
        identity: ResolvedAbilityIdentity,
    ) {
        if (runtimeAbilityId != 0) {
            stackAbilityIdentitiesByRuntimeId[runtimeAbilityId] = identity
        }
    }

    fun stackAbilityIdentity(runtimeAbilityId: Int): ResolvedAbilityIdentity? = stackAbilityIdentitiesByRuntimeId[runtimeAbilityId]

    fun consumeStackAbilityIdentity(runtimeAbilityId: Int): ResolvedAbilityIdentity? =
        stackAbilityIdentitiesByRuntimeId.remove(runtimeAbilityId)

    fun setSelectedSpellGrpId(
        cardId: ForgeCardId,
        grpId: Int?,
    ) {
        if (grpId == null) {
            selectedSpellGrpIds.remove(cardId)
        } else {
            selectedSpellGrpIds[cardId] = grpId
        }
    }

    fun consumeSelectedSpellGrpId(cardId: ForgeCardId): Int? = selectedSpellGrpIds.remove(cardId)

    fun setSelectedAdditionalCostGrpId(
        cardId: ForgeCardId,
        grpId: Int,
    ) {
        selectedAdditionalCostGrpIds[cardId] = grpId
    }

    fun consumeSelectedAdditionalCostGrpId(cardId: ForgeCardId): Int? = selectedAdditionalCostGrpIds.remove(cardId)

    fun setSelectedChosenCostPromptId(
        cardId: ForgeCardId,
        promptId: Int?,
    ) {
        if (promptId == null) {
            selectedChosenCostPromptIds.remove(cardId)
        } else {
            selectedChosenCostPromptIds[cardId] = promptId
        }
    }

    fun consumeSelectedChosenCostPromptId(cardId: ForgeCardId): Int? = selectedChosenCostPromptIds.remove(cardId)

    /** Read-only committed correlation for event collection and snapshot capture. */
    fun pendingSpellCast(cardId: ForgeCardId): GameEvent.SpellCast? =
        projectionStateSnapshot().annotations.pendingSpellCasts.find(cardId, cardGrpId(cardId))

    /** Read-only committed correlation for event collection and snapshot capture. */
    fun pendingSpellResolution(cardId: ForgeCardId): GameEvent.SpellResolved? =
        projectionStateSnapshot().annotations.pendingSpellResolutions.find(cardId, cardGrpId(cardId))

    internal fun cardGrpId(cardId: ForgeCardId): Int? = findCard(cardId)?.name?.let { cardRepository.findGrpIdByName(it) }

    private val selectedModalAbilityGrpIds = ConcurrentHashMap<ForgeCardId, Int>()
    private val pendingTriggerAbilityGrpIds = ConcurrentHashMap<Int, Int>()
    private val pendingTriggerCleanupGrpIds = ConcurrentHashMap<Int, Int>()

    fun recordSelectedModalAbilityGrpId(
        source: ForgeCardId,
        abilityGrpId: Int,
    ) {
        selectedModalAbilityGrpIds[source] = abilityGrpId
    }

    fun resolvePendingTriggerAbilityIdentity(
        triggerId: Int,
        source: ForgeCardId,
        fallback: () -> Int?,
    ): Int? =
        pendingTriggerAbilityGrpIds[triggerId]
            ?: (selectedModalAbilityGrpIds.remove(source) ?: fallback())?.also { resolved ->
                pendingTriggerAbilityGrpIds[triggerId] = resolved
            }

    fun retainPendingTriggerAbilityIdentities(triggerIds: Set<Int>) {
        pendingTriggerAbilityGrpIds.keys.removeIf { it !in triggerIds }
        pendingTriggerCleanupGrpIds.keys.removeIf { it !in triggerIds }
    }

    fun recordPendingTriggerCleanupIdentity(
        triggerId: Int,
        cleanupAbilityGrpId: Int,
    ) {
        pendingTriggerCleanupGrpIds[triggerId] = cleanupAbilityGrpId
    }

    fun pendingTriggerCleanupAbilityGrpId(triggerId: Int): Int? = pendingTriggerCleanupGrpIds[triggerId]

    fun paradigmSourceStackIidFor(fid: ForgeCardId): Int? =
        projectionStateSnapshot().annotations.paradigmSourceStackIids[fid]
            ?: findCard(fid)
                ?.effectSource
                ?.let { source -> projectionStateSnapshot().annotations.paradigmSourceStackIids[ForgeCardId(source.id)] }

    /** Shared signal — bridges notify when they have a pending item, replacing poll loops. */
    val prioritySignal = PrioritySignal()

    /**
     * Resolved die roll winner for this match.
     * Uses config override if set, otherwise randomizes (1 or 2) via Forge RNG.
     * Lazy — evaluated once, after [start] seeds the RNG.
     */
    val dieRollWinner: Int by lazy {
        engineSettings.dieRollWinner ?: (MyRandom.getRandom().nextInt(2) + 1)
    }

    // --- Per-seat bridge maps ---

    /** Forge cardId → AbilityRegistry for multi-ability abilityGrpId resolution. */
    private val abilityRegistries = ConcurrentHashMap<Int, AbilityRegistry>()

    private val actionBridges = mutableMapOf<Int, GameActionBridge>()
    private val promptBridges = mutableMapOf<Int, InteractivePromptBridge>()
    private val mulliganBridges = mutableMapOf<Int, MulliganBridge>()

    init {
        configureInteractiveSeat(SeatId(1))
    }

    private fun configureInteractiveSeat(seatId: SeatId) {
        actionBridges[seatId.value] =
            GameActionBridge(
                timeoutMs = bridgeTimeoutMs,
                prioritySignal = prioritySignal,
                windowRuntime = cutCoordinator.actionWindowRuntime(seatId),
            )
        promptBridges[seatId.value] =
            InteractivePromptBridge(timeoutMs = promptFailsafeMs, prioritySignal = prioritySignal).also {
                it.forgeIidResolver = ::getOrAllocInstanceId
                it.trackedZoneResolver = ::trackedZoneFor
                it.instanceIdReservoir = ::reserveInstanceId
                it.abilityIdentityResolver = { sa -> sa.hostCard?.let { card -> resolvePromptAbilityIdentity(card, sa) } }
                it.cardGrpIdResolver = ::resolveGrpId
                it.triggerStackAbilityInstanceIdResolver = { abilityId ->
                    peekInstanceId(FrameIdResolver.triggerStackAbilityForgeId(abilityId))?.value
                }
                it.triggerStackAbilitySourceInstanceIdResolver = { abilityId ->
                    val abilityIid = peekInstanceId(FrameIdResolver.triggerStackAbilityForgeId(abilityId))?.value
                    abilityIid?.let { iid -> annotationProjectionStateSnapshot().abilityLineage.find(iid)?.sourceIidAtCreate }
                }
            }
        mulliganBridges[seatId.value] =
            MulliganBridge(
                autoKeep = engineSettings.skipMulligan && !humanVsHuman,
                timeoutMs = engineSettings.mulliganWaitMs,
            )
    }

    /** Small seat-scoped facade — keeps handlers off global seat-1 defaults. */
    data class SeatBridges(
        val action: GameActionBridge,
        val prompt: InteractivePromptBridge,
        val mulligan: MulliganBridge,
    ) {
        fun drainReveals(): List<InteractivePromptBridge.RevealRecord> = prompt.drainReveals()
    }

    /** Parameterized accessor — throws if seat not populated. */
    fun actionBridge(seatId: SeatId): GameActionBridge = actionBridges[seatId.value] ?: error("No action bridge for seat ${seatId.value}")

    internal fun failActionWindows(cause: Throwable) {
        actionBridges.values.forEach { it.failPending(cause) }
    }

    /** Parameterized accessor — throws if seat not populated. */
    fun promptBridge(seatId: SeatId): InteractivePromptBridge =
        promptBridges[seatId.value] ?: error("No prompt bridge for seat ${seatId.value}")

    /** All populated bridge seat IDs (for iterating prompt bridges). */
    fun allSeatIds(): Set<Int> = promptBridges.keys

    /** All protocol seat IDs for players in the current game. */
    fun gameSeatIds(): Set<Int> = players.keys.takeIf { it.isNotEmpty() } ?: promptBridges.keys

    /** Parameterized accessor — throws if seat not populated. */
    fun mulliganBridge(seatId: SeatId): MulliganBridge =
        mulliganBridges[seatId.value] ?: error("No mulligan bridge for seat ${seatId.value}")

    /** Seat-scoped facade — use in handlers instead of raw seat-1 aliases. */
    override fun seat(seatId: SeatId): SeatBridges =
        SeatBridges(
            action = actionBridge(seatId),
            prompt = promptBridge(seatId),
            mulligan = mulliganBridge(seatId),
        )

    /** Drain reveal queue(s) for a specific viewer; seat 0 drains all seats. */
    fun drainReveals(viewingSeatId: Int): List<InteractivePromptBridge.RevealRecord> =
        if (viewingSeatId == 0) {
            promptBridges.toSortedMap().values.flatMap { it.drainReveals() }
        } else {
            seat(SeatId(viewingSeatId)).drainReveals()
        }

    /**
     * Pre-populate auto-pass bridges for a synthetic seat.
     * Used by tests that need an extra passive seat without AI wiring.
     *
     * timeout=0 means: action bridge returns PassPriority immediately,
     * prompt bridge returns defaultIndex immediately, mulligan auto-keeps.
     */
    fun configureSyntheticSeat(seatId: SeatId) {
        actionBridges[seatId.value] = GameActionBridge(timeoutMs = 0, prioritySignal = prioritySignal)
        promptBridges[seatId.value] =
            InteractivePromptBridge(timeoutMs = 0, prioritySignal = prioritySignal).also {
                it.forgeIidResolver = ::getOrAllocInstanceId
                it.trackedZoneResolver = ::trackedZoneFor
                it.instanceIdReservoir = ::reserveInstanceId
                it.abilityIdentityResolver = { sa -> sa.hostCard?.let { card -> resolvePromptAbilityIdentity(card, sa) } }
            }
        mulliganBridges[seatId.value] = MulliganBridge(autoKeep = true, timeoutMs = 0)
        log.info("GameBridge: seat {} configured as synthetic (auto-pass)", seatId.value)
    }

    /** Human player's controller — set during [start]/[startFromPuzzle] for debug observability. */
    var humanController: BridgedPlayerController? = null
        private set

    private class PlaybackRegistry {
        private val bySeat = mutableMapOf<SeatId, GamePlayback>()

        fun get(seatId: SeatId): GamePlayback? = bySeat[seatId]

        fun register(
            seatId: SeatId,
            playback: GamePlayback,
        ) {
            bySeat[seatId] = playback
        }

        fun values(): Collection<GamePlayback> = bySeat.values

        fun clear() = bySeat.clear()
    }

    /** Per-seat action playback — captures remote-action state diffs via EventBus. Empty before start(). */
    private val playbackRegistry = PlaybackRegistry()

    /** Convenience: controlled-player playback for single-player (1vAI) matches. */
    val playback: GamePlayback? get() = playbackFor(seating.humanSeat)

    override fun playbackFor(seatId: SeatId): GamePlayback? = playbackRegistry.get(seatId)

    fun throwIfGameLoopFailed() {
        loopController?.throwIfFailed()
        playbackRegistry.values().firstNotNullOfOrNull(GamePlayback::failure)?.let { throw it }
    }

    @VisibleForTesting
    internal fun gameLoopControllerForTest(): GameLoopController? = loopController

    internal fun acknowledgePlaybackFrame(seatId: SeatId) {
        playbackFor(if (humanVsHuman) SeatId(1) else seatId)?.onFrameCommitted()
    }

    @VisibleForTesting
    internal fun registerPlaybackForTest(
        seatId: SeatId,
        playback: GamePlayback,
    ) {
        playbackRegistry.register(seatId, playback)
    }

    private fun registerPlayback(
        game: Game,
        seatId: SeatId,
        captureLocalActions: Boolean,
    ): GamePlayback {
        val playback =
            GamePlayback(
                bridge = this,
                seatId = seatId.value,
                captureLocalActions = captureLocalActions,
            )
        playbackRegistry.register(seatId, playback)
        game.subscribeToEvents(playback)
        return playback
    }

    /** Registers journal collection and playback hooks before the engine thread starts. */
    private fun registerPlaybackPipeline(
        game: Game,
        seatId: SeatId,
        captureLocalActions: Boolean,
    ) {
        promptBridge(seatId).runtimeBindings = cutCoordinator.promptRuntimes(seatId).bindings(seatId)
        val collector = GameEventCollector(this)
        eventCollector = collector
        game.subscribeToEvents(collector)
        val playback = registerPlayback(game, seatId, captureLocalActions)
        game.phaseHandler.setMainGameLoopStartedHook(playback::onMainGameLoopStarted)
        game.phaseHandler.setMainLoopStepCompletionHook(playback::onMainLoopStepCompleted)
        game.phaseHandler.setAttackersDeclaredCompletionHook(playback::onAttackersDeclaredCompleted)
        game.phaseHandler.setBlockersDeclaredCompletionHook(playback::onBlockersDeclaredCompleted)
        game.phaseHandler.setCombatEndedCompletionHook(playback::onCombatEndedCompleted)
    }

    /** Event collector — captures Forge engine events for annotation building. Null before start(). */
    var eventCollector: GameEventCollector? = null
        private set

    /**
     * Look up a Forge [Card] by [ForgeCardId]. Used by snapshot-based pipeline stages
     * that need per-card Forge state while [ObjectMapper] and downstream callers
     * migrate to [GsmSnapshot]. Returns null if the card is not in any zone.
     */
    fun findCard(fid: ForgeCardId): Card? = game?.findById(fid.value)

    /**
     * Resolve a Forge [Card] to its client grpId — single entry point for runtime
     * callers that hold a live Forge card without a [leyline.game.snapshot.CardSnapshot]
     * in scope (e.g. [leyline.match.ActionPerformer] resolving an incoming action).
     *
     * Threads the token registry + card repository so callers can't accidentally
     * omit dependencies. Delegates to [GrpIdResolver.resolve].
     *
     * @param card the Forge card to resolve
     * @param instanceId client instanceId for registry cache lookups (0 = skip cache)
     */
    fun resolveGrpId(
        card: Card,
        instanceId: Int = 0,
    ): Int = GrpIdResolver.resolve(card, cardRepository, instanceId, tokenRegistry)

    // --- Composed components ---

    data class ProjectionFoldViewer(
        val input: leyline.game.mapping.StateProjectionCompiler.ViewerInput,
        val diff: GameStateMessage,
    )

    /** Test-only hook invoked once per ordered viewer fold immediately before commit. */
    @VisibleForTesting
    @Volatile
    var diffListener: (
        (
            prior: ProjectionState,
            viewers: List<ProjectionFoldViewer>,
        ) -> Unit
    )? = null

    // ── Reveal proxy lifecycle ──────────────────────────────────────────────
    // RevealedCard proxies exist during an active reveal-choose effect.
    // StateMapper drives RevealProxyTracker (allocate/lookup/drain) during
    // GSM assembly. After the choice resolves, proxy IDs move to
    // pendingProxyDeletions so the next diff emits RevealedCardDeleted +
    // diffDeletedInstanceIds.

    /** Explicit projection identity operation for frame-local resolvers. */
    internal fun projectionIdentityWorkspace(): ProjectionIdentityWorkspace =
        activeProjectionEditor.get()?.identities ?: ProjectionIdentityWorkspace(::getOrAllocInstanceId)

    private val pendingEarthbendResolutions = mutableListOf<EffectProjectionFacts.PendingEarthbendResolution>()
    private var nextEarthbendResolutionVersion = 1L

    /** Cached token grpId per instanceId — stable across diff ticks. */
    val tokenRegistry =
        TokenIdentityRegistry(
            read = { iid -> activeProjectionEditor.get()?.tokenGrpIds?.get(iid) ?: projectionStateSnapshot().tokenGrpIds[iid] },
            write = { iid, grpId ->
                val editor = activeProjectionEditor.get()
                if (editor != null) {
                    editor.tokenGrpIds.putIfAbsent(iid, grpId)
                } else {
                    updateProjection { it.tokenGrpIds.putIfAbsent(iid, grpId) }
                }
            },
            remove = { iid ->
                val editor = activeProjectionEditor.get()
                if (editor != null) {
                    editor.tokenGrpIds.remove(iid)
                } else {
                    updateProjection { it.tokenGrpIds.remove(iid) }
                }
            },
            clearAll = {
                activeProjectionEditor.get()?.tokenGrpIds?.clear()
                    ?: updateProjection { it.tokenGrpIds.clear() }
            },
        )

    fun activeDecayedCleanupSources(): Set<ForgeCardId> = projectionStateSnapshot().annotations.decayedCleanupSources

    internal fun annotationProjectionStateSnapshot(): AnnotationProjectionState = projectionStateSnapshot().annotations

    /** Records callback data; synthetic ids and lifecycle changes belong to projection compilation. */
    fun recordEarthbendResolution(
        sourceCardId: ForgeCardId,
        sourceAbilityGrpId: Int,
        abilityForgeId: Int,
        targetCardIds: List<ForgeCardId>,
    ) {
        pendingEarthbendResolutions +=
            EffectProjectionFacts.PendingEarthbendResolution(
                version = nextEarthbendResolutionVersion++,
                sourceCardId = sourceCardId,
                sourceAbilityGrpId = sourceAbilityGrpId,
                abilityForgeId = abilityForgeId,
                targetCardIds = targetCardIds.toList(),
            )
    }

    /** Snapshot pending target specs from all seat prompt bridges without consuming them. */
    fun snapshotPendingTargetSpecs(): List<TargetSpecFact> =
        promptBridges.toSortedMap().flatMap { (seatId, prompt) ->
            prompt.snapshotPendingTargetSpecEntries().map { entry ->
                TargetSpecFact(
                    PromptFactKey(SeatId(seatId), entry.version),
                    entry.spec.let { spec ->
                        TargetSpec(
                            spec.spellForgeCardId,
                            spec.spellName,
                            spec.index,
                            spec.affectorInstanceIdAtRecord,
                            spec.affectees.map { TargetAffectee(it.targetForgeCardId, it.targetSeatId, it.distribution) },
                            spec.isStackAbility,
                            spec.promptId,
                            spec.abilityIdentity,
                            spec.forgeAbilityId,
                        )
                    },
                )
            }
        }

    /** Materialize the prompt data projection needs for one immutable frame input. */
    fun materializePromptProjectionFacts(): PromptProjectionFacts {
        val choiceResults = mutableListOf<PromptProjectionFacts.ChoiceResultFact>()
        val reveals = mutableListOf<PromptProjectionFacts.RevealFact>()
        val convokePayments = mutableListOf<PromptProjectionFacts.ConvokePaymentsFact>()
        val collectEvidenceCosts = mutableListOf<PromptProjectionFacts.CollectEvidenceFact>()
        for ((seatValue, prompt) in promptBridges.toSortedMap()) {
            val seatId = SeatId(seatValue)
            choiceResults +=
                prompt.journal.snapshotChoiceResults().map { entry ->
                    PromptProjectionFacts.ChoiceResultFact(
                        PromptFactKey(seatId, entry.version),
                        entry.result.let {
                            ChoiceResult(it.sourceForgeCardId, it.chooserSeatId, it.choiceValue, it.choiceDomain, it.sentiment)
                        },
                    )
                }
            prompt.journal.activeRevealEntry()?.let { entry ->
                reveals +=
                    PromptProjectionFacts.RevealFact(
                        PromptFactKey(seatId, entry.version),
                        RevealStarted(entry.reveal.allHandCardIds.toList(), entry.reveal.ownerSeatId),
                        cutCoordinator.allPromptRuntimes().any { it.hasRevealProjectionPrompt() },
                    )
            }
            convokePayments +=
                prompt.journal.activeConvokePaymentEntries().map { entry ->
                    PromptProjectionFacts.ConvokePaymentsFact(
                        PromptFactKey(seatId, entry.version),
                        entry.sourceForgeCardId,
                        entry.payments.map {
                            ConvokePayment(it.paymentForgeCardId, it.color, it.substitutionGrpId, it.paymentAbilityGrpId)
                        },
                    )
                }
            prompt.journal.activeCollectEvidenceEntry()?.let { entry ->
                collectEvidenceCosts +=
                    PromptProjectionFacts.CollectEvidenceFact(
                        PromptFactKey(seatId, entry.version),
                        CollectEvidenceCost(entry.context.sourceForgeCardId, entry.context.threshold),
                    )
            }
        }
        return PromptProjectionFacts(
            choiceResults = choiceResults.toList(),
            reveals = reveals.toList(),
            convokePayments = convokePayments.toList(),
            collectEvidenceCosts = collectEvidenceCosts.toList(),
            targetSpecs = snapshotPendingTargetSpecs().toList(),
        )
    }

    /** Consume only the observed pending target records represented in an applied frame. */
    fun consumePendingTargetSpecs(keys: List<PromptFactKey>) {
        keys.groupBy({ it.seatId }, { it.version }).forEach { (seatId, versions) ->
            val entries = promptBridge(seatId).snapshotPendingTargetSpecEntries().filter { it.version in versions }
            promptBridge(seatId).consumePendingTargetSpecEntries(entries)
        }
    }

    private fun consumePromptFacts(consumption: PromptFactConsumption) {
        consumption.choiceResults.groupBy { it.seatId }.forEach { (seatId, keys) ->
            val versions = keys.mapTo(hashSetOf()) { it.version }
            promptBridge(seatId).journal.consumeChoiceResults(
                promptBridge(seatId).journal.snapshotChoiceResults().filter { it.version in versions },
            )
        }
        consumption.staleReveals.forEach { key ->
            promptBridge(key.seatId).journal.activeRevealEntry()?.takeIf { it.version == key.version }?.let {
                promptBridge(key.seatId).journal.clearActiveReveal(it)
            }
        }
        consumption.convokePayments.forEach { key ->
            promptBridge(key.seatId).journal.activeConvokePaymentEntries().firstOrNull { it.version == key.version }?.let {
                promptBridge(key.seatId).journal.clearConvokePayments(it)
            }
        }
        consumption.collectEvidenceCosts.forEach { key ->
            promptBridge(key.seatId).journal.activeCollectEvidenceEntry()?.takeIf { it.version == key.version }?.let {
                promptBridge(key.seatId).journal.clearCollectEvidenceCost(it)
            }
        }
        consumePendingTargetSpecs(consumption.targetSpecs)
    }

    override fun nextAnnotationId(): Int =
        editActiveOrCommitted { editor ->
            val id = editor.persistentAnnotations.nextAnnotationId
            editor.persistentAnnotations = editor.persistentAnnotations.copy(nextAnnotationId = id + 1)
            id
        }

    override fun nextPersistentAnnotationId(): Int =
        editActiveOrCommitted { editor ->
            val id = editor.persistentAnnotations.nextPersistentId
            editor.persistentAnnotations = editor.persistentAnnotations.copy(nextPersistentId = id + 1)
            id
        }

    private fun addPersistentAnnotation(annotation: wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo) {
        editActiveOrCommitted { editor ->
            editor.persistentAnnotations =
                editor.persistentAnnotations.copy(
                    activeAnnotations = editor.persistentAnnotations.activeAnnotations + (annotation.id to annotation),
                )
        }
    }

    private fun <T> editActiveOrCommitted(block: (ProjectionState.Editor) -> T): T =
        activeProjectionEditor.get()?.let(block) ?: updateProjection(block)

    // --- Interface implementations (IdMapping, PlayerLookup, ZoneTracking, etc.) ---

    override fun getOrAllocInstanceId(forgeCardId: ForgeCardId): InstanceId {
        activeProjectionEditor.get()?.let { return it.identities.getOrAlloc(forgeCardId) }
        return synchronized(projectionLock) {
            projectionState.identities.forgeIdToInstanceId[forgeCardId]
                ?: run {
                    val prior = projectionState
                    val editor = projectionState.editor()
                    val allocated = editor.identities.getOrAlloc(forgeCardId)
                    val next = editor.freeze()
                    check(next.sequence == prior.sequence) { "Direct identity allocation cannot change logical sequence" }
                    projectionState = next
                    allocated
                }
        }
    }

    fun reserveInstanceId(): InstanceId =
        activeProjectionEditor.get()?.identities?.reserve()
            ?: updateProjection { it.identities.reserve() }

    fun bindInstanceId(
        forgeCardId: ForgeCardId,
        instanceId: InstanceId,
    ) {
        activeProjectionEditor.get()?.identities?.bind(forgeCardId, instanceId)
            ?: updateProjection { it.identities.bind(forgeCardId, instanceId) }
    }

    fun peekInstanceId(forgeCardId: ForgeCardId): InstanceId? =
        activeProjectionEditor.get()?.identities?.peek(forgeCardId)
            ?: synchronized(projectionLock) { projectionState.identities.forgeIdToInstanceId[forgeCardId] }

    override fun reallocInstanceId(forgeCardId: ForgeCardId): InstanceIdRegistry.IdReallocation =
        activeProjectionEditor.get()?.identities?.realloc(forgeCardId)
            ?: updateProjection { it.identities.realloc(forgeCardId) }

    override fun getForgeCardId(instanceId: InstanceId): ForgeCardId? =
        activeProjectionEditor.get()?.identities?.getForgeCardId(instanceId)
            ?: synchronized(projectionLock) { projectionState.identities.instanceIdToForgeId[instanceId] }

    /** Read-only snapshot of instanceId → forgeCardId (all, including retired). */
    fun getInstanceIdMap(): Map<InstanceId, ForgeCardId> =
        synchronized(projectionLock) { projectionState.identities.instanceIdToForgeId.toMap() }

    internal fun projectionIdentities(): InstanceIdRegistry.State =
        activeProjectionEditor.get()?.identities?.freeze() ?: projectionStateSnapshot().identities

    @VisibleForTesting
    internal fun committedEffectProjection(): SyntheticEffectProjection = projectionStateSnapshot().effects

    fun resetInstanceIds(): List<InstanceId> =
        updateProjection { editor ->
            val old =
                editor.identities
                    .freeze()
                    .forgeIdToInstanceId.values
                    .toList()
            val nextId = editor.identities.freeze().nextInstanceId
            editor.identities.replace(InstanceIdRegistry.initialState(nextId))
            old
        }

    /** Proto zone tracking — where the protocol last placed each instanceId. */
    fun getProtoZones(): Map<Int, Int> = activeProjectionEditor.get()?.protoZones?.toMap() ?: projectionStateSnapshot().protoZones

    override fun retireToLimbo(instanceId: InstanceId) {
        activeProjectionEditor.get()?.limboInstanceIds?.add(instanceId.value)
            ?: updateProjection { it.limboInstanceIds += instanceId.value }
        tokenRegistry.retire(instanceId.value)
    }

    override fun getLimboInstanceIds(): List<InstanceId> =
        (activeProjectionEditor.get()?.limboInstanceIds ?: projectionStateSnapshot().limboInstanceIds).map(::InstanceId)

    override fun recordZone(
        instanceId: InstanceId,
        zoneId: Int,
    ): Int? {
        val editor = activeProjectionEditor.get()
        if (editor != null) return editor.protoZones.put(instanceId.value, zoneId)
        return updateProjection { it.protoZones.put(instanceId.value, zoneId) }
    }

    override fun getPreviousZone(instanceId: InstanceId): Int? =
        activeProjectionEditor.get()?.protoZones?.get(instanceId.value)
            ?: projectionStateSnapshot().protoZones[instanceId.value]

    override fun closeFrame(): FrameEventLog = eventCollector?.closeFrame() ?: FrameEventLog.EMPTY

    /**
     * Close the event frame for one bundle build: collector events + reveal records
     * for [viewingSeatId] (promoted to [GameEvent.CardsRevealed]). Caller passes
     * the returned log to [leyline.game.mapping.StateProjectionCompiler].
     *
     * One close per call; per-seat reveal consumption is seat-scoped. A multi-seat
     * close (so two per-seat builds of the same snapshot see the same reveals) is
     * a separate design concern if the pattern ever matters.
     */
    fun closeBundleFrame(viewingSeatId: Int = 0): FrameEventLog {
        val frame = closeFrame()
        val events = frame.events.toMutableList()
        for (reveal in drainReveals(viewingSeatId)) {
            events.add(
                GameEvent.CardsRevealed(
                    reveal.forgeCardIds,
                    reveal.ownerSeatId,
                    reveal.viewerSeatId,
                    reveal.sourceZone,
                    reveal.sourceCardId,
                ),
            )
        }
        return FrameEventLog(events, frame.zoneMoves)
    }

    /** True if the open frame has accumulated events not yet closed into a GSM. */
    fun hasPendingEvents(): Boolean = eventCollector?.hasEvents() ?: false

    companion object {
        private const val OPENING_HAND_ABILITY_CATEGORY = 9
        private val PT_BOOST_KEYWORDS = listOf(KeywordAbilityIds.PROWESS, KeywordAbilityIds.ENLIST)

        /** Fallback grpId for cards not in client DB (renders face-down). */
        const val FALLBACK_GRPID = 0

        /** Prompt-like waits keep a fail-safe even when human action windows wait indefinitely. */
        const val DEFAULT_PROMPT_FAILSAFE_TIMEOUT_MS = 45_000L

        /** Synthetic identity range for engine-level pending trigger records. */
        const val PENDING_TRIGGER_HOLDER_FORGE_OFFSET = 91_000_000

        /** Separate range for recurring-effect holders whose card ids may overlap trigger ids. */
        const val PARADIGM_TRIGGER_HOLDER_FORGE_OFFSET = 92_000_000

        /** Default deck when no decklist is provided (tests, puzzles without decks). */
        private const val FALLBACK_DECK = """
20 Llanowar Elves
4 Elvish Mystic
4 Giant Growth
32 Forest
"""

        private const val DEFAULT_PRIORITY_WAIT_MS = 15_000L

        /** Poll interval for mulligan ready check (no signal available for mulligan). */
        private const val POLL_INTERVAL_MS = 5L
    }

    /**
     * How long [awaitPriority] waits for the engine to reach a priority stop.
     * Production default: 15s. Tests should use ~2s since the engine responds
     * in <100ms and the extra headroom only delays timeout-based test failures.
     */
    var priorityWaitMs: Long = DEFAULT_PRIORITY_WAIT_MS

    /**
     * Wrap an existing [Game] without starting the engine loop thread.
     *
     * The game should already be at the desired phase (via `devModeSet`).
     * Cards should already be in zones (via `Zone.add`). This method wires
     * the components needed for proto output — [GameEventCollector] for
     * annotations, [InstanceIdRegistry] for card IDs — and registers the
     * bridged player controller so engine callbacks (cost calculation,
     * choice answers) behave as in a started game.
     *
     * No threads, no game loop — fully synchronous. Forge events fire
     * inline when you call `game.action.*` methods.
     *
     * Use in conformance tests where you need [leyline.game.mapping.StateMapper] + [leyline.game.bundle.BundleBuilder]
     * but don't need the game loop or player interaction.
     */
    fun wrapGame(g: Game) {
        game = g
        populateSeatMap(g)
        // Register the bridged controller exactly as the started-game paths
        // do — a wrapped game whose cost calculations reach the default
        // controller would block on desktop input machinery.
        registerHumanController(g)
        val collector = GameEventCollector(this)
        eventCollector = collector
        g.subscribeToEvents(collector)
    }

    private fun seedRandom(seed: Long?) {
        if (seed != null) {
            log.info("GameBridge: using deterministic seed={}", seed)
            MyRandom.setRandom(Random(seed))
        } else {
            log.info("GameBridge: using random seed")
        }
    }

    private fun registerHumanController(g: Game) {
        val human = g.players.firstOrNull { it.lobbyPlayer !is LobbyPlayerAi } ?: return
        val aiPlayer = g.players.first { it.lobbyPlayer is LobbyPlayerAi }
        priorityPolicy.installPhaseStops(human.id, aiPlayer.id)
        val controller =
            BridgedPlayerController(
                game = g,
                player = human,
                lobbyPlayer = human.lobbyPlayer,
                bridge = promptBridge(SeatId(1)),
                seating = seating,
                actionBridge = actionBridge(SeatId(1)),
                mulliganBridge = mulliganBridge(SeatId(1)),
                priorityPolicy = priorityPolicy,
                runtimeHorizonMode = runtimeHorizonMode,
                interactionRuntime = cutCoordinator,
            )
        humanController = controller
        human.addController(Long.MAX_VALUE - 1, human, controller, false)
        aiPlayer.addController(
            Long.MAX_VALUE - 1,
            aiPlayer,
            RevealTrackingAiController(g, aiPlayer, promptBridge(seating.humanSeat), seating.familiarSeat),
            false,
        )
    }

    /**
     * Initialize card DB, create game, start engine loop, wait for mulligan.
     * Blocks caller until engine has dealt hands and is waiting for keep/mull.
     *
     * @param seed if non-null, seeds the RNG for deterministic shuffles (tests/replays)
     * @param deckList1 decklist text for seat 1 (human). Falls back to [deckList] or built-in fallback.
     * @param deckList2 decklist text for seat 2 (AI). Falls back to [deckList1].
     * @param deckList single-deck shorthand — applies to both seats when deckList1/deckList2 omitted.
     */
    fun start(
        seed: Long? = null,
        deckList: String? = null,
        deckList1: String? = null,
        deckList2: String? = null,
        variant: String? = null,
    ) {
        val seat1Str = (deckList1 ?: deckList ?: FALLBACK_DECK).trimIndent()
        val seat2Str = (deckList2 ?: deckList ?: seat1Str).trimIndent()
        start(
            seed = seed,
            deck1 = DeckSource.ForgeText(seat1Str),
            deck2 = DeckSource.ForgeText(seat2Str),
            variant = variant,
        )
    }

    /**
     * Initialize card DB, realize both decks through [DeckLoader], create game, start
     * engine loop, wait for mulligan. Blocks caller until engine has dealt hands and is
     * waiting for keep/mull.
     *
     * Card database initialization runs before deck realization: [DeckSource.ForgeText]
     * resolves card names against the Forge card database via
     * [forge.deck.DeckRecognizer], which requires it to already be loaded.
     *
     * @param seed if non-null, seeds the RNG for deterministic shuffles (tests/replays)
     * @param deck1 seat 1 (human) deck source.
     * @param deck2 seat 2 (AI) deck source.
     */
    fun start(
        seed: Long? = null,
        deck1: DeckSource,
        deck2: DeckSource,
        variant: String? = null,
    ) {
        log.info("GameBridge: initializing card database")
        GameBootstrap.initializeCardDatabase()
        seedRandom(seed)
        val realizedDeck1 = DeckLoader.load(deck1, cardRepository::findNameByGrpId)
        val realizedDeck2 = DeckLoader.load(deck2, cardRepository::findNameByGrpId)
        log.info(
            "GameBridge: parsed decks (seat1={} cards, seat2={} cards)",
            realizedDeck1.main.countAll(),
            realizedDeck2.main.countAll(),
        )

        val g =
            if (variant != null && isCommanderVariantName(variant)) {
                log.info("GameBridge: creating commander-variant game (variant={})", variant)
                GameBootstrap.createCommanderGame(realizedDeck1, realizedDeck2, variant)
            } else {
                GameBootstrap.createConstructedGame(realizedDeck1, realizedDeck2)
            }
        game = g

        populateSeatMap(g)

        // Wire the interactive seat and retain native AI decisions with reveal observation.
        registerHumanController(g)

        cutCoordinator.registerViewers(
            listOf(
                ProjectionViewer(seating.humanSeat, ProjectionViewerRole.Player),
                ProjectionViewer(seating.humanSeat.opponent, ProjectionViewerRole.Observer),
            ),
        )
        registerPlaybackPipeline(g, seating.humanSeat, captureLocalActions = false)
        log.info("GameBridge: registered playback pipeline for seat 1")

        val loop =
            GameLoopController(
                g,
                actionBridges = actionBridges.values.toList(),
                promptBridges = promptBridges.values.toList(),
                mulliganBridges = mulliganBridges.values.toList(),
                prioritySignal = prioritySignal,
            )
        loopController = loop
        loop.start()
        loop.awaitStarted()

        if (engineSettings.skipMulligan) {
            log.info("GameBridge: skipMulligan — engine auto-kept, waiting for priority")
            awaitPriority()
            log.info("GameBridge: engine reached priority after auto-keep")
        } else {
            log.info("GameBridge: game loop started, waiting for mulligan")
            awaitMulliganReady()
            log.info("GameBridge: engine reached mulligan, hand ready")
        }
    }

    /** One rules engine with independent interactive state for each of the two players. */
    fun startHumanVsHuman(
        seed: Long? = null,
        deck1: DeckSource,
        deck2: DeckSource,
        variant: String? = null,
    ) {
        humanVsHuman = true
        GameBootstrap.initializeCardDatabase()
        seedRandom(seed)
        val seats = listOf(SeatId(1), SeatId(2))
        seats.forEach(::configureInteractiveSeat)
        val g =
            GameBootstrap.createHumanVsHumanGame(
                DeckLoader.load(deck1, cardRepository::findNameByGrpId),
                DeckLoader.load(deck2, cardRepository::findNameByGrpId),
                variant,
            )
        game = g
        populateSeatMap(g)
        seats.forEach { seat ->
            val player = checkNotNull(getPlayer(seat))
            val other = checkNotNull(getPlayer(SeatId(if (seat.value == 1) 2 else 1)))
            val policy = PriorityPolicyRuntime(matchId).also { it.installPhaseStops(player.id, other.id) }
            seatPriorityPolicies[seat] = policy
            val controller =
                BridgedPlayerController(
                    game = g,
                    player = player,
                    lobbyPlayer = player.lobbyPlayer,
                    bridge = promptBridge(seat),
                    seating = seating,
                    actionBridge = actionBridge(seat),
                    mulliganBridge = mulliganBridge(seat),
                    priorityPolicy = policy,
                    runtimeHorizonMode = runtimeHorizonMode,
                    interactionRuntime = cutCoordinator.promptRuntimes(seat).blocking,
                )
            player.addController(Long.MAX_VALUE - 1, player, controller, false)
            if (seat.value == 1) humanController = controller
            promptBridge(seat).runtimeBindings = cutCoordinator.promptRuntimes(seat).bindings(seat)
            mulliganBridge(seat).onPrompt = { prompt ->
                playersReady.await()
                cutCoordinator.lifecycle.publishHumanMulliganPrompt(seat, prompt)
            }
        }
        cutCoordinator.registerViewers(seats.map { ProjectionViewer(it, ProjectionViewerRole.Player) })
        // One collector and playback journal own the shared Forge event stream.
        registerPlaybackPipeline(g, SeatId(1), captureLocalActions = false)
        val loop =
            GameLoopController(
                g,
                actionBridges.values.toList(),
                promptBridges.values.toList(),
                mulliganBridges.values.toList(),
                prioritySignal,
            )
        loopController = loop
        loop.start()
        loop.awaitStarted()
        val deadline = System.currentTimeMillis() + engineSettings.mulliganWaitMs
        while (seats.none { mulliganBridge(it).pendingPrompt() != null }) {
            loop.throwIfFailed()
            check(System.currentTimeMillis() < deadline) { "Two-player game did not reach its initial mulligan" }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    /**
     * Initialize a native Forge AI-vs-AI game for spectator mode.
     *
     * No bridged [PlayerController] is installed; both seats keep Forge's AI
     * controllers. [startGameHook] runs after opening-hand setup and before the
     * main loop, giving the observer path a stable point to emit its initial GSM.
     */
    fun startAiVsAi(
        seed: Long? = null,
        deckList: String? = null,
        deckList1: String? = null,
        deckList2: String? = null,
        variant: String? = null,
        startGameHook: Runnable? = null,
    ) {
        val seat1Str = (deckList1 ?: deckList ?: FALLBACK_DECK).trimIndent()
        val seat2Str = (deckList2 ?: deckList ?: seat1Str).trimIndent()
        startAiVsAi(
            seed = seed,
            deck1 = DeckSource.ForgeText(seat1Str),
            deck2 = DeckSource.ForgeText(seat2Str),
            variant = variant,
            startGameHook = startGameHook,
        )
    }

    /**
     * Initialize a native Forge AI-vs-AI game for spectator mode, realizing both decks
     * through [DeckLoader] before Forge card-lookup-dependent construction runs.
     *
     * No bridged [PlayerController] is installed; both seats keep Forge's AI
     * controllers. [startGameHook] runs after opening-hand setup and before the
     * main loop, giving the observer path a stable point to emit its initial GSM.
     */
    fun startAiVsAi(
        seed: Long? = null,
        deck1: DeckSource,
        deck2: DeckSource,
        variant: String? = null,
        startGameHook: Runnable? = null,
    ) {
        log.info("GameBridge: initializing AI-vs-AI spectator game")
        GameBootstrap.initializeCardDatabase()
        seedRandom(seed)
        val realizedDeck1 = DeckLoader.load(deck1, cardRepository::findNameByGrpId)
        val realizedDeck2 = DeckLoader.load(deck2, cardRepository::findNameByGrpId)

        val g =
            if (variant != null && isCommanderVariantName(variant)) {
                GameBootstrap.createAiVsAiCommanderGame(realizedDeck1, realizedDeck2, variant)
            } else {
                GameBootstrap.createAiVsAiGame(realizedDeck1, realizedDeck2)
            }
        game = g
        populateSeatMap(g)

        g.players.forEachIndexed { index, player ->
            player.addController(
                Long.MAX_VALUE - 1,
                player,
                RevealTrackingAiController(g, player, promptBridge(SeatId(1)), SeatId(index + 1)),
                false,
            )
        }

        cutCoordinator.registerViewers(
            listOf(
                ProjectionViewer(SeatId(1), ProjectionViewerRole.SeatObserver),
                ProjectionViewer(SeatId(2), ProjectionViewerRole.SeatObserver),
            ),
        )
        registerPlaybackPipeline(g, SeatId(1), captureLocalActions = true)
        log.info("GameBridge: registered spectator playback pipeline")

        val loop = GameLoopController(g, prioritySignal = prioritySignal)
        loopController = loop
        loop.start(startGameHook)

        loop.awaitStarted()
    }

    /** Get the current hand for a seat as client grpIds. */
    fun getHandGrpIds(seatId: SeatId): List<Int> {
        val player = getPlayer(seatId) ?: return emptyList()
        return player.getZone(ZoneType.Hand).cards.map { card ->
            DevCheck.requireOrNull(cardRepository.findGrpIdByName(card.name)) { "hand grpId miss: '${card.name}'" }
                ?: FALLBACK_GRPID
        }
    }

    /** Full deck as client grpIds (for initial bundle deck message). */
    fun getDeckGrpIds(seatId: SeatId): List<Int> {
        val player = getPlayer(seatId) ?: return emptyList()
        // Combine library + hand + any other zones to reconstruct full deck
        val allCards = mutableListOf<String>()
        for (zone in listOf(ZoneType.Library, ZoneType.Hand)) {
            player.getZone(zone).cards.forEach { allCards.add(it.name) }
        }
        return allCards.map {
            DevCheck.requireOrNull(cardRepository.findGrpIdByName(it)) { "deck grpId miss: '$it'" }
                ?: FALLBACK_GRPID
        }
    }

    /** Commander grpIds for the initial handshake deck message. */
    fun getCommanderGrpIds(seatId: SeatId): List<Int> {
        val seatFirst = listOfNotNull(getPlayer(seatId))
        val remaining = players.values.filterNot { it === seatFirst.firstOrNull() }
        return (seatFirst + remaining).flatMap { player -> player.commanders }.map { card ->
            DevCheck.requireOrNull(cardRepository.findGrpIdByName(card.name)) { "commander grpId miss: '${card.name}'" }
                ?: FALLBACK_GRPID
        }
    }

    override fun getGame(): Game? = game

    override fun getPlayer(seatId: SeatId): Player? = players[seatId.value]

    /** Resolve an engine player to its protocol seat for this match. */
    fun seatOf(player: Player?): SeatId? {
        if (player == null) return null
        return players.entries.firstOrNull { (_, candidate) -> candidate === player || candidate.id == player.id }?.let { SeatId(it.key) }
    }

    /** Resolve a Forge player view to its protocol seat for this match. */
    fun seatOf(player: PlayerView?): SeatId? {
        if (player == null) return null
        return players.entries.firstOrNull { (_, candidate) -> candidate.id == player.id }?.let { SeatId(it.key) }
    }

    /**
     * Look up or lazily build the [AbilityRegistry] for a Forge card.
     *
     * Pre-populated for puzzle cards via [registerPuzzleCards]. For all other
     * game types (constructed, tokens, zone transfers), built on first access
     * from the live [card] + [cardData].
     */
    fun abilityRegistryFor(
        card: Card,
        cardData: CardData?,
    ): AbilityRegistry? {
        if (cardData == null) return null
        return abilityRegistries.compute(card.id) { _, cached ->
            cached?.takeIf { it.sourceCardGrpId == cardData.grpId } ?: AbilityRegistry.build(card, cardData)
        }
    }

    @VisibleForTesting
    internal fun cachedAbilityRegistryCardIds(): Set<ForgeCardId> = abilityRegistries.keys.mapTo(linkedSetOf(), ::ForgeCardId)

    @VisibleForTesting
    internal fun clearAbilityRegistryCacheForTesting() {
        abilityRegistries.clear()
    }

    fun resolveAbilityIdentity(
        card: Card,
        ability: SpellAbility,
    ): ResolvedAbilityIdentity? {
        val definition =
            ability.trigger?.let { AbilityDefinitionRef.Trigger(it.definitionId) }
                ?: AbilityDefinitionRef.SpellAbility(ability.definitionId)
        val grpId = cardRepository.findGrpIdByName(card.name) ?: return null
        val cardData = cardRepository.findByGrpId(grpId) ?: return null
        val registry = abilityRegistryFor(card, cardData) ?: return null
        if (ability.trigger != null) return registry.resolve(definition)
        val abilityGrpId = registry.forSpellAbility(ability) ?: return null
        return registry.resolve(definition)?.takeIf { it.abilityGrpId == abilityGrpId }
            ?: ResolvedAbilityIdentity(definition, abilityGrpId)
    }

    internal fun openingHandAbilityGrpId(cardName: String): Int? {
        val grpId = cardRepository.findGrpIdByName(cardName) ?: return null
        val cardData = cardRepository.findByGrpId(grpId) ?: return null
        if (cardData.abilityCategories.size != cardData.abilityIds.size) return null
        return cardData.abilityIds
            .zip(cardData.abilityCategories)
            .singleOrNull { (_, category) -> category == OPENING_HAND_ABILITY_CATEGORY }
            ?.first
            ?.first
    }

    private fun resolvePromptAbilityIdentity(
        card: Card,
        ability: SpellAbility,
    ): ResolvedAbilityIdentity? {
        val identity = resolveAbilityIdentity(card, ability)
        val modalGrpId = selectedModalAbilityGrpIds[ForgeCardId(card.id)] ?: return identity
        val definition =
            ability.trigger?.let { AbilityDefinitionRef.Trigger(it.definitionId) }
                ?: AbilityDefinitionRef.SpellAbility(ability.definitionId)
        return identity?.copy(abilityGrpId = modalGrpId) ?: ResolvedAbilityIdentity(definition, modalGrpId)
    }

    fun resolveAbilityIdentity(
        card: Card,
        definition: AbilityDefinitionRef,
    ): ResolvedAbilityIdentity? {
        val grpId = cardRepository.findGrpIdByName(card.name) ?: return null
        val cardData = cardRepository.findByGrpId(grpId) ?: return null
        return abilityRegistryFor(card, cardData)?.resolve(definition)
    }

    /** Evict cached AbilityRegistry for a card (e.g. after DFC transform). */
    fun evictAbilityRegistry(forgeCardId: Int) {
        abilityRegistries.remove(forgeCardId)
    }

    /** Shell-side cache invalidation for normalized state-frame facts. */
    fun invalidateAbilityRegistries(events: List<GameEvent>) {
        events.filterIsInstance<GameEvent.CardTransformed>().forEach { evictAbilityRegistry(it.cardId.value) }
        events.filterIsInstance<GameEvent.ZoneChanged>().forEach { evictAbilityRegistry(it.cardId.value) }
    }

    /**
     * Seat role mapping for this match — human vs Familiar.
     * Populated by [populateSeatMap] at game-start.
     */
    lateinit var seating: Seating
        private set

    /** Populate seat map by registration order (seat 1 = first, seat 2 = second). */
    private fun populateSeatMap(g: Game) {
        g.players.forEachIndexed { index, player -> players[index + 1] = player }
        val humanIdx = g.players.indexOfFirst { it.lobbyPlayer !is LobbyPlayerAi }.takeIf { it >= 0 } ?: 0
        val humanSeat = SeatId(humanIdx + 1)
        seating =
            Seating(
                humanSeat = humanSeat,
                familiarSeat =
                    SeatId(
                        if (humanSeat.value ==
                            1
                        ) {
                            2
                        } else {
                            1
                        },
                    ),
                playerSeats = players.map { (seat, player) -> player.id to SeatId(seat) }.toMap(),
            )
        log.info("GameBridge: seating resolved human={} familiar={}", seating.humanSeat.value, seating.familiarSeat.value)
    }

    private fun isCommanderGame(game: Game): Boolean =
        listOf(GameType.Commander, GameType.Brawl, GameType.Oathbreaker)
            .any { game.rules.gameType == it || game.rules.hasAppliedVariant(it) }

    private fun isCommanderVariantName(variant: String): Boolean = variant.lowercase() in setOf("commander", "brawl", "oathbreaker")

    /**
     * Block until the engine reaches a priority stop (via [GameActionBridge]).
     * After keep, the engine auto-advances through Beginning → Main1.
     */
    override fun awaitPriority() {
        awaitPriorityWithTimeout(priorityWaitMs)
    }

    /** Wait specifically for this seat's next executable priority window. */
    fun awaitActionPriority(seatId: SeatId): Boolean {
        val deadline = System.currentTimeMillis() + priorityWaitMs
        val actionBridge = seat(seatId).action
        while (true) {
            loopController?.throwIfFailed()
            if (actionBridge.getPending() != null) return true
            val g = game
            if (g == null || g.isGameOver) return false
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return false
            prioritySignal.awaitSignal(remaining)
        }
    }

    /** Wait for this seat's next committed action or routed interaction horizon. */
    fun awaitSeatHorizonWithTimeout(
        seatId: SeatId,
        timeoutMs: Long,
        ignoredActionId: String? = null,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val actionBridge = seat(seatId).action
        while (true) {
            loopController?.throwIfFailed()
            val g = game
            if (g != null && g.isGameOver) return false
            val pending = actionBridge.getPending()
            if (humanVsHuman && cutCoordinator.hasCommittedBatches(seatId)) return true
            if ((pending != null && pending.actionId != ignoredActionId) || hasPendingNonActionInteraction()) return true
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return false
            prioritySignal.awaitSignal(remaining)
        }
    }

    /**
     * Block until the engine reaches a priority stop, an interactive prompt
     * is pending, or the game ends.
     *
     * Uses [PrioritySignal] (generation broadcast) instead of polling — both
     * [GameActionBridge] and [InteractivePromptBridge] signal when they post
     * a pending item, so we wake up immediately with no 50ms poll latency.
     *
     * The prompt check is needed because targeted spells (e.g. Giant Growth)
     * block the engine thread in [InteractivePromptBridge.requestChoice] before
     * the next action-bridge priority stop is reached. Without this, casting a
     * targeted spell would appear to time out.
     *
     * Migrated action and blocking-interaction signals are emitted only after
     * their coordinator-owned output is committed. Routed prompt bridges keep
     * their own publication contract.
     *
     * @param timeoutMs max wait time (use longer values for AI turns)
     * @return true if priority was reached, false if timed out or game over
     */
    override fun awaitPriorityWithTimeout(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            loopController?.throwIfFailed()
            // Check conditions first (handles already-pending case)
            val g = game
            if (g != null && g.isGameOver) {
                log.info("GameBridge: game over detected while waiting for priority")
                return false
            }
            if (hasPendingInteraction()) {
                return true
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                log.warn("GameBridge: timed out waiting for priority ({}ms)", timeoutMs)
                return false
            }

            // Wait for signal from either bridge (or timeout)
            prioritySignal.awaitSignal(remaining)
        }
    }

    private fun hasPendingInteraction(): Boolean =
        actionBridges.values.any { it.getPending() != null } ||
            hasPendingNonActionInteraction()

    fun hasPendingNonActionInteraction(): Boolean = cutCoordinator.allPromptRuntimes().any { it.hasPendingInteraction() }

    /** Current typed one-shot PayCosts window for harness policy inspection. */
    fun currentOneShotPayCostsInteraction(): PublishedOneShotPayCostsInteraction? =
        cutCoordinator.allPromptRuntimes().firstNotNullOfOrNull {
            it.currentOneShotPayCosts()
        }

    /** Exact targeting ability retained by the active coordinator window. */
    internal fun currentTargetingAbility(): SpellAbility? = cutCoordinator.targeting.aiContext()

    /** Submit keep only to a seat with an interactive controller. */
    fun submitKeep(seatId: SeatId): Boolean {
        log.info("GameBridge: seat {} keeps hand", seatId.value)
        if (!isInteractiveSeat(seatId)) return false
        val accepted = mulliganBridge(seatId).submitKeep()
        if (!accepted) log.debug("ignored stale keep for seat {}", seatId.value)
        return accepted
    }

    /**
     * Submit mulligan decision for seat.
     * Blocks until engine re-deals and reaches mulligan again.
     *
     * London mulligan: after mull, the engine draws 7 then calls
     * [tuckCardsViaMulligan] which blocks on [MulliganPhase.WaitingTuck].
     * We auto-tuck first N cards (same as forge-web) to unblock the engine,
     * then wait for the next [MulliganPhase.WaitingKeep].
     */
    fun submitMull(seatId: SeatId): Boolean {
        log.info("GameBridge: seat {} mulligans", seatId.value)
        if (humanVsHuman) return mulliganBridge(seatId).submitMull()
        if (seatId == seating.humanSeat) {
            // Capture current prompt sequence BEFORE submitting —
            // avoids race where we see the stale WaitingKeep from the current round.
            val bridge = mulliganBridge(seatId)
            val seqBefore = bridge.promptSequence
            if (!bridge.submitMull()) {
                log.debug("ignored stale mulligan for seat {}", seatId.value)
                return false
            }
            // London: engine draws 7 then calls tuckCardsViaMulligan() → WaitingTuck.
            // Wait for a NEW prompt (higher sequence) that's either WaitingTuck or WaitingKeep.
            val deadline = System.currentTimeMillis() + engineSettings.mulliganWaitMs
            while (System.currentTimeMillis() < deadline) {
                val prompt = bridge.pendingPromptAfter(seqBefore)
                if (prompt != null) {
                    when (prompt.phase) {
                        MulliganPhase.WaitingKeep -> {
                            log.info("GameBridge: engine re-dealt hand after mulligan (no tuck)")
                            return true
                        }
                        MulliganPhase.WaitingTuck -> {
                            val n = prompt.cardsToTuck
                            val hand = getHandCards(seatId)
                            log.info("GameBridge: auto-tucking {} cards (London mulligan)", n)
                            bridge.submitTuck(hand.take(n))
                            // After tuck, engine continues → next WaitingKeep
                            awaitMulliganReady()
                            log.info("GameBridge: engine re-dealt hand after mulligan+tuck")
                            return true
                        }
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            log.warn("GameBridge: timed out waiting for engine after mull+tuck")
        }
        return false
    }

    /**
     * Block until the engine reaches the tuck phase after a kept mulligan.
     * The engine calls [MulliganBridge.awaitTuckDecision] on the game thread,
     * publishing a [MulliganPhase.WaitingTuck] prompt.
     */
    fun awaitTuckReady() {
        val deadline = System.currentTimeMillis() + engineSettings.mulliganWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (mulliganBridge(SeatId(1)).pendingPrompt()?.phase == MulliganPhase.WaitingTuck) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("GameBridge: timed out waiting for engine to reach tuck phase")
    }

    // TODO: parameterize by seatId for paired-flow mulligan support

    /** How many cards the player must put on bottom (London mulligan). */
    fun getTuckCount(): Int = mulliganBridge(SeatId(1)).pendingPrompt()?.cardsToTuck ?: 0

    /** Get the current hand as Card objects for a seat. */
    fun getHandCards(seatId: SeatId): List<Card> {
        val player = getPlayer(seatId) ?: return emptyList()
        return player.getZone(ZoneType.Hand).cards.toList()
    }

    /** Submit tuck decision — cards to put on bottom of library. */
    fun submitTuck(
        seatId: SeatId,
        cards: List<Card>,
    ) {
        log.info("GameBridge: seat {} tucking {} cards", seatId.value, cards.size)
        if (isInteractiveSeat(seatId)) mulliganBridge(seatId).submitTuck(cards)
    }

    /** True when this bridge is running a puzzle game. */
    val isPuzzle: Boolean
        get() = game?.rules?.gameType == GameType.Puzzle

    /**
     * Initialize card DB, create a puzzle game, apply puzzle state, finalize,
     * start the game loop from current state (no mulligan), and wait for priority.
     *
     * @param puzzle the parsed [Puzzle] object to apply
     * @param seed if non-null, seeds the RNG so RNG-dependent puzzle decisions
     *             are reproducible and independent of prior games in this JVM
     */
    fun startPuzzle(
        puzzle: Puzzle,
        controlledSeat: SeatId = SeatId(1),
        seed: Long? = null,
        aiControllerFactory: ((Game, Player) -> ForgePlayerController)? = null,
        beforeRuntimeStart: ((Game) -> Unit)? = null,
    ) = startPuzzle(
        puzzle,
        controlledSeat,
        seed,
        aiControllerFactory,
        beforeRuntimeStart,
        startRuntime = true,
    )

    /** Initialize a disposable puzzle snapshot without launching an autonomous game loop. */
    internal fun startStaticPuzzle(
        puzzle: Puzzle,
        controlledSeat: SeatId,
        beforeRuntimeStart: (Game) -> Unit,
    ) = startPuzzle(
        puzzle,
        controlledSeat,
        seed = null,
        aiControllerFactory = null,
        beforeRuntimeStart,
        startRuntime = false,
    )

    private fun startPuzzle(
        puzzle: Puzzle,
        controlledSeat: SeatId,
        seed: Long?,
        aiControllerFactory: ((Game, Player) -> ForgePlayerController)?,
        beforeRuntimeStart: ((Game) -> Unit)?,
        startRuntime: Boolean,
    ) {
        log.info("GameBridge: starting puzzle mode")
        GameBootstrap.initializeCardDatabase()

        if (seed != null) {
            log.info("GameBridge: using deterministic seed={}", seed)
            MyRandom.setRandom(Random(seed))
        }
        configureInteractiveSeat(controlledSeat)

        val g = GameBootstrap.createPuzzleGame(controlledSeat)
        game = g
        populateSeatMap(g)
        check(seating.humanSeat == controlledSeat) {
            "Puzzle controlled seat ${controlledSeat.value} resolved as human seat ${seating.humanSeat.value}"
        }

        // Apply puzzle state via reflection (applyGameOnThread is protected).
        // Install temp PlayerControllers with autoKeep + zero-timeout bridges
        // to handle any SBAs/triggers during setup (forge-web pattern).
        applyPuzzleSafely(puzzle, g)

        // Auto-detect commander presence → apply Brawl variant for commander tax,
        // zone-return rules, and correct starting life. Must happen after puzzle
        // state is applied (commanders are placed by GameState.applyToGame).
        val hasCommander = g.players.any { it.commanders.isNotEmpty() }
        if (hasCommander) {
            g.rules.addAppliedVariant(GameType.Brawl)
            log.info("GameBridge: puzzle has commander — applied Brawl variant")
        }

        // Finalize: set age=Play, position at MAIN1 turn 1
        GameBootstrap.finalizeForPuzzle(g)
        log.info("GameBridge: puzzle applied, game at {} turn {}", g.phaseHandler.phase, g.phaseHandler.turn)

        beforeRuntimeStart?.invoke(g)

        // Register all puzzle cards in CardRepository and InstanceIdRegistry.
        // Puzzle.applyGameOnThread creates cards via Card.fromPaperCard — they
        // need synthetic grpIds and instanceId mappings for proto output.
        registerPuzzleCards(g)

        // Seed persistent Attachment annotations for pre-attached cards (equipment/auras).
        // No CardAttached events fire for cards that start attached — seed from engine state.
        seedAttachmentAnnotations(g)

        // Forge's puzzle loader applies counters via addCounterInternal(fireEvents=false),
        // so no CountersChanged event reaches MechanicAnnotations. Without Counter_803b,
        // MTGA renders permanents at 0 counters (planeswalkers lose their loyalty UI and
        // suppress the activation modal). Seed from engine state.
        seedCounterAnnotations(g)

        // Wire PlayerController for the controlled seat — same as constructed
        // but no mulligan bridge needed (autoKeep=true, unused).
        val human = g.players.first { it.lobbyPlayer !is LobbyPlayerAi }
        val aiPlayer = g.players.first { it.lobbyPlayer is LobbyPlayerAi }
        priorityPolicy.installPhaseStops(human.id, aiPlayer.id)
        if (aiControllerFactory == null) {
            val controller =
                BridgedPlayerController(
                    game = g,
                    player = human,
                    lobbyPlayer = human.lobbyPlayer,
                    bridge = promptBridge(controlledSeat),
                    seating = seating,
                    actionBridge = actionBridge(controlledSeat),
                    mulliganBridge = mulliganBridge(controlledSeat),
                    priorityPolicy = priorityPolicy,
                    runtimeHorizonMode = runtimeHorizonMode,
                    interactionRuntime = cutCoordinator,
                )
            humanController = controller
            human.addController(Long.MAX_VALUE - 1, human, controller, false)
        } else {
            human.addController(Long.MAX_VALUE - 1, human, aiControllerFactory(g, human), false)
        }

        cutCoordinator.registerViewers(listOf(ProjectionViewer(controlledSeat, ProjectionViewerRole.Player)))
        registerPlaybackPipeline(g, controlledSeat, captureLocalActions = false)

        if (!startRuntime) return

        // Start game loop from current state (skip Match.startGame/mulligan)
        val loop =
            GameLoopController(
                g,
                actionBridges = actionBridges.values.toList(),
                promptBridges = promptBridges.values.toList(),
                mulliganBridges = mulliganBridges.values.toList(),
                prioritySignal = prioritySignal,
            )
        loopController = loop
        loop.startFromCurrentState()
        loop.awaitStarted()

        if (aiControllerFactory == null) {
            log.info("GameBridge: puzzle loop started, waiting for priority")
            awaitPriority()
            log.info("GameBridge: puzzle engine reached priority, ready")
        } else {
            log.info("GameBridge: puzzle loop started with direct AI controller")
        }
    }

    /**
     * Tear down the current game and start a new puzzle in-place.
     * Clears all bridge state (instanceIds, limbo, zones, snapshots, annotations)
     * so the new puzzle gets a clean slate. The client receives a Full GSM after.
     *
     * @return old instanceIds that the client should delete (for diffDeletedInstanceIds)
     */
    fun resetForPuzzle(puzzle: Puzzle): List<Int> {
        log.info("GameBridge: resetting for new puzzle")

        shutdown()

        // Clear all mapping/tracking state from the previous game
        val deletedIds = resetInstanceIds().map { it.value }
        pendingEarthbendResolutions.clear()
        nextEarthbendResolutionVersion = 1L
        abilityRegistries.clear()
        selectedModalAbilityGrpIds.clear()
        pendingTriggerAbilityGrpIds.clear()
        pendingTriggerCleanupGrpIds.clear()
        stackAbilityIdentitiesByRuntimeId.clear()
        selectedSpellGrpIds.clear()
        selectedAdditionalCostGrpIds.clear()
        selectedChosenCostPromptIds.clear()
        tokenRegistry.clear()
        synchronized(projectionLock) {
            val prior = projectionState
            val next =
                ProjectionState.initial(
                    startInstanceId = prior.identities.nextInstanceId,
                    sequence = prior.sequence,
                )
            check(next.sequence == prior.sequence) { "Puzzle reset cannot change logical sequence" }
            projectionState = next
        }

        // Drain bridge state from previous game
        for (bridge in promptBridges.values) bridge.resetForPuzzle()

        cutCoordinator.resetForNewGame()
        startPuzzle(puzzle, seed = engineSettings.seed)
        log.info("GameBridge: puzzle hot-swap complete, deleted {} old instanceIds", deletedIds.size)
        return deletedIds
    }

    /**
     * Tear down heavyweight resources: unsubscribe EventBus listeners, stop game loop.
     * Called by [leyline.match.Match.close] for deterministic lifecycle management.
     * Idempotent — safe to call before [shutdown].
     */
    @Synchronized
    fun teardownResources() {
        val loop = loopController
        loop?.beginShutdown()
        cutCoordinator.shutdown()
        promptBridges.values.forEach { it.runtimeBindings = leyline.bridge.handoff.PromptRuntimeBindings() }
        val g = game
        if (g != null) {
            g.phaseHandler.setMainGameLoopStartedHook(null)
            g.phaseHandler.setMainLoopStepCompletionHook(null)
            g.phaseHandler.setAttackersDeclaredCompletionHook(null)
            g.phaseHandler.setBlockersDeclaredCompletionHook(null)
            g.phaseHandler.setCombatEndedCompletionHook(null)
            eventCollector?.let { unsubscribeFromEventsIfPresent(g, it) }
            for (pb in playbackRegistry.values()) {
                unsubscribeFromEventsIfPresent(g, pb)
            }
        }
        loop?.shutdown()
        loopController = null
        playbackRegistry.clear()
        cutCoordinator.unregisterViewers()
        eventCollector = null
    }

    private fun unsubscribeFromEventsIfPresent(
        game: Game,
        subscriber: Any,
    ) {
        try {
            game.unsubscribeFromEvents(subscriber)
        } catch (_: IllegalArgumentException) {
            log.debug("Event subscriber was already absent during teardown: {}", subscriber.javaClass.simpleName)
        }
    }

    /**
     * Full shutdown: tear down resources + clear per-seat state.
     * Tests and puzzle reset call this directly. Production code goes through
     * [leyline.match.Match.close] which calls [teardownResources] then this.
     */
    fun shutdown() {
        log.info("GameBridge: shutting down")
        teardownResources()
        game = null
        players.clear()
    }

    // --- Puzzle internals ---

    /**
     * Apply puzzle state to the game via reflection.
     * Installs temp [PlayerController]s with autoKeep/zero-timeout during
     * application to handle any SBAs or triggers that fire during setup.
     */
    @Suppress("SwallowedException") // InvocationTargetException.targetException forwarded as cause
    private fun applyPuzzleSafely(puzzle: Puzzle, game: Game) {
        val method = puzzle.javaClass.superclass.getDeclaredMethod("applyGameOnThread", Game::class.java)
        method.isAccessible = true
        runWithTempControllers(game.players.filter { it.controller is PlayerControllerHuman }) {
            try {
                method.invoke(puzzle, game)
            } catch (e: InvocationTargetException) {
                throw IllegalStateException("Puzzle application failed", e.targetException)
            }
        }
    }

    /**
     * Recursively install temp [PlayerController]s with zero-timeout bridges
     * on each human-controlled player during [block]. Removed automatically after.
     */
    private fun runWithTempControllers(
        players: List<Player>,
        block: () -> Unit,
    ) {
        val player =
            players.firstOrNull() ?: run {
                block()
                return
            }
        val tempPrompt = InteractivePromptBridge(timeoutMs = 0)
        val tempAction = GameActionBridge(timeoutMs = 0)
        val tempMulligan = MulliganBridge(autoKeep = true, timeoutMs = 0)
        val tempController =
            BridgedPlayerController(
                game = player.game,
                player = player,
                lobbyPlayer = player.lobbyPlayer,
                bridge = tempPrompt,
                seating = seating,
                actionBridge = tempAction,
                mulliganBridge = tempMulligan,
                interactionRuntime = setupBlockingInteractionRuntime,
            )
        player.runWithController(
            { runWithTempControllers(players.drop(1), block) },
            tempController,
        )
    }

    /**
     * After puzzle application: allocate instanceIds and pre-populate
     * [AbilityRegistry] for all cards in all zones.
     */
    private fun registerPuzzleCards(game: Game) {
        val allZones =
            listOf(
                ZoneType.Hand,
                ZoneType.Battlefield,
                ZoneType.Library,
                ZoneType.Graveyard,
                ZoneType.Exile,
                ZoneType.Command,
                ZoneType.Sideboard,
            )
        var registered = 0
        val playerToSeat: Map<forge.game.player.Player, Int> =
            players.entries.associate { it.value to it.key }
        for (player in game.players) {
            val seatId = playerToSeat[player] ?: continue
            for (zone in allZones) {
                val protocolZoneId = puzzleZoneId(zone, seatId) ?: continue
                for (card in player.getZone(zone).cards) {
                    if (card.rules != null) {
                        val grpId = cardRepository.findGrpIdByName(card.name)
                        if (grpId != null) {
                            val cardData = cardRepository.findByGrpId(grpId)
                            abilityRegistryFor(card, cardData)
                        }
                    }
                    val iid = instance(card)
                    // Seed zone tracking so the FIRST diff after the puzzle
                    // GSM can detect zone changes against the puzzle's
                    // initial state (cycling discard, unearth return, …).
                    // Without this seed, ZoneTransferDetector reads
                    // previousZones[iid]==null and silently skips emission.
                    recordZone(iid, protocolZoneId)
                    registered++
                }
            }
        }
        log.info("GameBridge: registered {} puzzle cards in InstanceIdRegistry", registered)
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun puzzleZoneId(
        zone: ZoneType,
        seatId: Int,
    ): Int? =
        when (zone) {
            ZoneType.Hand -> ZoneIds.handOf(seatId)
            ZoneType.Library -> ZoneIds.libraryOf(seatId)
            ZoneType.Graveyard -> ZoneIds.graveyardOf(seatId)
            ZoneType.Battlefield -> ZoneIds.BATTLEFIELD
            ZoneType.Exile -> ZoneIds.EXILE
            ZoneType.Command -> ZoneIds.COMMAND
            ZoneType.Sideboard -> ZoneIds.sideboardOf(seatId)
            // Stack / RareAside / etc. — not zones we seed for puzzles.
            else -> null
        }

    private fun trackedZoneFor(cardId: ForgeCardId): ZoneType? {
        val instanceId = peekInstanceId(cardId) ?: return null
        return when (getPreviousZone(instanceId)) {
            ZoneIds.BATTLEFIELD -> ZoneType.Battlefield
            ZoneIds.EXILE -> ZoneType.Exile
            ZoneIds.COMMAND -> ZoneType.Command
            ZoneIds.P1_HAND, ZoneIds.P2_HAND -> ZoneType.Hand
            ZoneIds.P1_LIBRARY, ZoneIds.P2_LIBRARY -> ZoneType.Library
            ZoneIds.P1_GRAVEYARD, ZoneIds.P2_GRAVEYARD -> ZoneType.Graveyard
            else -> null
        }
    }

    /**
     * Seed persistent [AnnotationType.Attachment] annotations for cards that start
     * pre-attached in a puzzle. No [GameEventCardAttachment] fires for these — the
     * attachment relationship exists only in Forge's [Card.attachedTo] field.
     */
    private fun seedAttachmentAnnotations(game: Game) {
        for (player in game.players) {
            for (card in player.getZone(ZoneType.Battlefield).cards) {
                val target = card.attachedTo ?: continue
                val auraIid = instance(card)
                val targetIid = instance(target)
                val ann =
                    AnnotationBuilder
                        .attachment(auraIid, targetIid)
                        .toBuilder()
                        .setId(nextPersistentAnnotationId())
                        .build()
                addPersistentAnnotation(ann)
                log.debug(
                    "seedAttachment: {} (iid={}) → {} (iid={})",
                    card.name,
                    auraIid.value,
                    target.name,
                    targetIid.value,
                )
            }
        }
    }

    /**
     * Seed persistent [AnnotationType.Counter_803b] annotations for player poison
     * counters and permanents that start with counters (loyalty on planeswalkers,
     * +1/+1 on creatures, etc.). Forge's puzzle loader bypasses the event chain
     * when applying counters, so no counter-change event fires.
     */
    private fun seedCounterAnnotations(game: Game) {
        for ((seatNum, player) in players) {
            val poisonCount = player.poisonCounters
            if (poisonCount <= 0) continue
            val ann =
                AnnotationBuilder
                    .playerCounter(SeatId(seatNum), CounterTypes.counterTypeId("POISON"), poisonCount)
                    .toBuilder()
                    .setId(nextPersistentAnnotationId())
                    .build()
            addPersistentAnnotation(ann)
            log.debug("seedCounter: seat={} POISON = {}", seatNum, poisonCount)
        }
        for (player in game.players) {
            for (card in player.getZone(ZoneType.Battlefield).cards) {
                val counters = card.counters
                if (counters.isEmpty()) continue
                val instanceId = instance(card)
                for (entry in counters.entrySet()) {
                    val counterType = entry.element
                    val count = entry.count
                    if (count <= 0) continue
                    val counterTypeId = CounterTypes.counterTypeId(counterType.name)
                    if (counterTypeId == 0) {
                        log.debug("seedCounter: skipped unknown counter type {} on {}", counterType.name, card.name)
                        continue
                    }
                    val ann =
                        AnnotationBuilder
                            .counter(instanceId, counterTypeId, count)
                            .toBuilder()
                            .setId(nextPersistentAnnotationId())
                            .build()
                    addPersistentAnnotation(ann)
                    log.debug(
                        "seedCounter: {} (iid={}) {} = {}",
                        card.name,
                        instanceId.value,
                        counterType.name,
                        count,
                    )
                }
            }
        }
    }

    /** Materialize all synthetic-effect inputs once at the bundle safe point. */
    fun materializeEffectProjectionFacts(): EffectProjectionFacts {
        val currentGame = game ?: return EffectProjectionFacts(pendingEarthbendResolutions = pendingEarthbendResolutions.toList())
        val boosts = mutableListOf<EffectProjectionFacts.BoostEntry>()
        val keywords = mutableListOf<EffectProjectionFacts.KeywordEntry>()
        val grantedAbilities = mutableListOf<EffectProjectionFacts.GrantedAbilityEntry>()
        val crew = mutableListOf<EffectProjectionFacts.CrewState>()
        val saddle = mutableListOf<EffectProjectionFacts.SaddleState>()
        val reconfigure = mutableListOf<EffectProjectionFacts.ReconfigureState>()
        val earthbendSignatures = mutableListOf<EffectProjectionFacts.BattlefieldEarthbendSignature>()
        val battlefieldCards =
            currentGame.players.flatMap { player ->
                player.getZone(ZoneType.Battlefield).cards
            }
        val keywordAffectorByStaticId = keywordAffectorByStaticId(battlefieldCards)
        val boostSourceByStaticId = boostSourceByStaticId(battlefieldCards)

        for (player in currentGame.players) {
            for (card in player.getZone(ZoneType.Battlefield).cards) {
                val forgeCardId = ForgeCardId(card.id)
                val boostTable = card.ptBoostTable
                if (!boostTable.isEmpty) {
                    for (cell in boostTable.cellSet()) {
                        val sourceCard = boostSourceByStaticId[cell.columnKey]
                        boosts +=
                            EffectProjectionFacts.BoostEntry(
                                forgeCardId = forgeCardId,
                                timestamp = cell.rowKey,
                                staticId = cell.columnKey,
                                power = cell.value.left,
                                toughness = cell.value.right,
                                sourceAbilityGrpId = resolveBoostSourceAbilityGrpId(card, cell.columnKey, sourceCard),
                                sourceForgeCardId = sourceCard?.let { ForgeCardId(it.id) },
                            )
                    }
                }
                val keywordTable = card.changedCardKeywords
                if (!keywordTable.isEmpty) {
                    for (cell in keywordTable.cellSet()) {
                        for (keyword in cell.value.keywords) {
                            keywords +=
                                EffectProjectionFacts.KeywordEntry(
                                    forgeCardId = forgeCardId,
                                    timestamp = cell.rowKey,
                                    staticId = cell.columnKey,
                                    keyword = keyword.keyword.toString(),
                                    affectorForgeCardId = keywordAffectorByStaticId[cell.columnKey],
                                )
                        }
                    }
                }

                grantedAbilities += grantedAbilityEntries(card, forgeCardId)

                card.getCrewedByThisTurn()?.takeIf { it.isNotEmpty() }?.let { sources ->
                    crew +=
                        EffectProjectionFacts.CrewState(
                            vehicleForgeCardId = forgeCardId,
                            crewSourceForgeCardIds = sources.map { ForgeCardId(it.id) },
                            isCreature = card.isCreature,
                            crewAbilityGrpId = resolveCrewAbilityGrpId(card),
                        )
                }
                card.getSaddledByThisTurn()?.takeIf { it.isNotEmpty() }?.let { sources ->
                    saddle +=
                        EffectProjectionFacts.SaddleState(
                            mountForgeCardId = forgeCardId,
                            saddleSourceForgeCardIds = sources.map { ForgeCardId(it.id) },
                        )
                }
                val isAttached = card.attachedTo != null
                if (isAttached && hasReconfigureUnattach(card)) {
                    val grpId = cardRepository.findGrpIdByName(card.name)
                    reconfigure +=
                        EffectProjectionFacts.ReconfigureState(
                            forgeCardId = forgeCardId,
                            isAttached = true,
                            isCreature = card.isCreature,
                            attachAbilityGrpId = grpId?.let { cardRepository.findKeywordAbilityGrpId(it, KeywordAbilityIds.RECONFIGURE) },
                        )
                }
                earthbendSignatureFor(card)?.let { signature ->
                    earthbendSignatures +=
                        EffectProjectionFacts.BattlefieldEarthbendSignature(forgeCardId, signature)
                }
            }
        }

        return EffectProjectionFacts(
            boostEntries = boosts,
            keywordEntries = keywords,
            grantedAbilityEntries = sortGrantedAbilityEntries(grantedAbilities),
            crewStates = crew,
            saddleStates = saddle,
            reconfigureStates = reconfigure,
            pendingEarthbendResolutions = pendingEarthbendResolutions.toList(),
            battlefieldEarthbendSignatures = earthbendSignatures,
        )
    }

    private fun boostSourceByStaticId(cards: List<Card>): Map<Long, Card> =
        buildMap {
            for (card in cards) {
                for (staticAbility in card.staticAbilities.orEmpty()) {
                    if (staticAbility.id > 0) putIfAbsent(staticAbility.id.toLong(), card)
                }
            }
        }

    private fun sortGrantedAbilityEntries(
        entries: List<EffectProjectionFacts.GrantedAbilityEntry>,
    ): List<EffectProjectionFacts.GrantedAbilityEntry> =
        entries.sortedWith(
            compareBy(
                { it.forgeCardId.value },
                { it.timestamp },
                { it.staticId },
                { it.abilityGrpId },
            ),
        )

    private fun grantedAbilityEntries(
        card: Card,
        forgeCardId: ForgeCardId,
    ): List<EffectProjectionFacts.GrantedAbilityEntry> {
        val cardGrpId = cardRepository.findGrpIdByName(card.name)
        val cardData = cardGrpId?.let(cardRepository::findByGrpId) ?: return emptyList()
        val registry = abilityRegistryFor(card, cardData) ?: return emptyList()
        return buildList {
            for (cell in card.changedCardTraits.cellSet()) {
                for (ability in (cell.value as? CardTraitChanges)?.getAbilities().orEmpty()) {
                    if (!ability.isActivatedAbility || ability.isManaAbility()) continue
                    val abilityGrpId = registry.forSpellAbility(ability) ?: continue
                    val hiddenIndex = registry.grantedAbilityUniqueIndex(ability) ?: continue
                    add(
                        EffectProjectionFacts.GrantedAbilityEntry(
                            forgeCardId = forgeCardId,
                            timestamp = cell.rowKey,
                            staticId = cell.columnKey,
                            abilityGrpId = abilityGrpId,
                            uniqueAbilityId = 50 + cardData.abilityIds.size + hiddenIndex,
                            sourceForgeCardId = ability.grantorStatic?.hostCard?.let { ForgeCardId(it.id) } ?: forgeCardId,
                        ),
                    )
                }
            }
        }
    }

    /** Resolve boost source ability metadata while the shell owns the live Forge cut. */
    private fun resolveBoostSourceAbilityGrpId(
        card: Card,
        staticId: Long,
        sourceCard: Card? = null,
    ): Int? {
        val source = sourceCard ?: card
        val grpId = cardRepository.findGrpIdByName(source.name) ?: return null
        val cardData = cardRepository.findByGrpId(grpId) ?: return null

        if (staticId == 0L) {
            return PT_BOOST_KEYWORDS.firstNotNullOfOrNull { keywordId ->
                cardRepository.findKeywordAbilityGrpId(grpId, keywordId)
            }
        }
        if (staticId > Int.MAX_VALUE) return null

        val registry = abilityRegistryFor(source, cardData) ?: return null
        val sourceStatic = source.staticAbilities?.firstOrNull { it.id == staticId.toInt() } ?: return null
        return registry.forStaticAbility(sourceStatic.definitionId)
            ?: sourceStatic.keyword?.let { keyword ->
                keyword.abilities.firstNotNullOfOrNull { registry.forSpellAbility(it.definitionId) }
                    ?: keyword.triggers.firstNotNullOfOrNull { registry.forTrigger(it.definitionId) }
                    ?: keyword.staticAbilities.firstNotNullOfOrNull { registry.forStaticAbility(it.definitionId) }
            }
    }

    /** First battlefield owner for each live static ability, in player/zone order. */
    private fun keywordAffectorByStaticId(cards: List<Card>): Map<Long, ForgeCardId> =
        buildMap {
            for (card in cards) {
                val forgeCardId = ForgeCardId(card.id)
                for (staticAbility in card.staticAbilities.orEmpty()) {
                    if (staticAbility.id > 0) putIfAbsent(staticAbility.id.toLong(), forgeCardId)
                }
            }
        }

    /** Forge-only extraction; projection consumes the resulting signature value. */
    private fun earthbendSignatureFor(card: Card): EarthbendTracker.Signature? {
        if (!card.isInZone(ZoneType.Battlefield) || !card.type.isLand || !card.type.isCreature) return null

        val hasteKeys =
            card.changedCardKeywords.cellSet().mapNotNullTo(mutableSetOf()) { cell ->
                if (cell.value.keywords.any { it.keyword.toString() == "Haste" }) {
                    EarthbendTracker.Signature(cell.rowKey, cell.columnKey)
                } else {
                    null
                }
            }
        if (hasteKeys.isEmpty()) return null

        return card.setPTTable
            .cellSet()
            .asSequence()
            .mapNotNull { cell ->
                if (cell.value.left == 0 && cell.value.right == 0) {
                    EarthbendTracker.Signature(cell.rowKey, cell.columnKey)
                } else {
                    null
                }
            }.filter { it in hasteKeys }
            .maxWithOrNull(compareBy<EarthbendTracker.Signature> { it.timestamp }.thenBy { it.staticId })
    }

    private fun hasReconfigureUnattach(card: Card): Boolean =
        card.allSpellAbilities.orEmpty().any {
            it.api == ApiType.Unattach && it.getParam("PrecostDesc") == "Reconfigure"
        }

    /**
     * Resolve crew ability grpId for a card via its ability registry.
     * Finds the SpellAbility where `sa.isCrew == true` and resolves its grpId.
     */
    private fun resolveCrewAbilityGrpId(card: Card): Int? {
        val grpId = cardRepository.findGrpIdByName(card.name) ?: return null
        val cardData = cardRepository.findByGrpId(grpId) ?: return null
        val registry = abilityRegistryFor(card, cardData) ?: return null

        val crewSa = card.spellAbilities?.firstOrNull { it.isCrew } ?: return null
        return registry.forSpellAbility(crewSa.definitionId)
    }

    // --- Internal ---

    /**
     * Poll until seat 1's mulligan bridge is in "waiting_keep" state,
     * meaning the engine has dealt the hand and is blocking.
     */
    private fun awaitMulliganReady() {
        val deadline = System.currentTimeMillis() + engineSettings.mulliganWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (mulliganBridge(SeatId(1)).pendingPrompt()?.phase == MulliganPhase.WaitingKeep) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        log.warn("GameBridge: timed out waiting for engine to reach mulligan")
    }
}
