package leyline.bridge.handoff

import forge.game.Game
import forge.game.card.Card
import forge.game.replacement.ReplacementEffect
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import leyline.DevCheck
import leyline.bridge.NonInteractiveScope
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.PrioritySignal
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.bridge.types.RevealZone
import leyline.bridge.types.SeatId
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import java.util.concurrent.ConcurrentLinkedQueue

internal class StrictPromptRefusalException(
    message: String,
) : IllegalStateException(message)

internal fun refuseStrictPrompt(message: String): Nothing = throw StrictPromptRefusalException(message)

/**
 * Thread-safe bridge between the blocking engine thread and async Netty handlers.
 *
 * When the engine needs interactive input, the bridge dispatches to the
 * match-scoped runtime that owns that wire family. Untyped choices resolve
 * synchronously; client-visible windows never share a generic pending slot.
 */
class InteractivePromptBridge(
    private val timeoutMs: Long? = DEFAULT_TIMEOUT_MS,
    private val prioritySignal: PrioritySignal? = null,
    private val strict: Boolean = DevCheck.strict,
) {
    /**
     * Forge-id → leyline iid lookup. Set by [leyline.game.state.GameBridge]
     * after construction (the bridge is created before its owning GameBridge
     * is fully initialised, so a setter is the lowest-coupling way to wire
     * this). Used for record-time iid resolution of pending TargetSpec
     * entries — see [PendingTarget.affectorInstanceIdAtRecord].
     */
    @Volatile
    var forgeIidResolver: ((ForgeCardId) -> InstanceId)? = null

    /** Last zone recorded by diff tracking, used as prompt transfer context. */
    @Volatile
    var trackedZoneResolver: ((ForgeCardId) -> ZoneType?)? = null

    @Volatile
    var instanceIdReservoir: (() -> InstanceId)? = null

    @Volatile
    var abilityIdentityResolver: ((SpellAbility) -> ResolvedAbilityIdentity?)? = null

    @Volatile
    var cardGrpIdResolver: ((Card) -> Int)? = null

    @Volatile
    var triggerStackAbilityInstanceIdResolver: ((Int) -> Int?)? = null

    @Volatile
    var triggerStackAbilitySourceInstanceIdResolver: ((Int) -> Int?)? = null

    /** Match-scoped prompt owners. One immutable value is installed and cleared at the match boundary. */
    @Volatile
    internal var runtimeBindings: PromptRuntimeBindings = PromptRuntimeBindings()

    private val modalChoiceAdapter =
        ModalChoicePromptAdapter(
            timeoutMs = timeoutMs,
            strict = strict,
            isGameLoopThread = ::isGameLoopThread,
            runtime = { runtimeBindings.modalChoice },
            prioritySignal = prioritySignal,
            record = ::record,
        )

    /**
     * Typed per-seat journal of prompt side-effects. Coordinators record
     * [PromptSideEffect] entries on the engine thread; shell-owned consumers
     * materialize and acknowledge them around GSM assembly.
     */
    val journal: PromptJournal = PromptJournal()

    // --- Pending TargetSpec data (recorded after chooseTargetsFor completes) ---

    /**
     * Pending target group: spell/ability source, ordered affectees, and 1-based group index.
     * [isStackAbility] flips the affector iid from the spell card's iid to the synthesised
     * stack-resident-ability iid via [leyline.game.mapping.FrameIdResolver.stackAbilityForgeId].
     *
     * [abilityIdentity] fixes the definition and client row while the callback
     * still owns the exact Forge ability. [forgeAbilityId] is the pre-stack
     * `SpellAbility.id`; stack and resolution state provide the canonical id,
     * with this value retained as a defensive fallback.
     *
     * [affectorInstanceIdAtRecord] is the spell/ability iid as it stood at
     * record time (after the target group is finalized while the spell is on
     * the stack). Multi-target spells (e.g. Bite Down) emit one TargetSpec per
     * group across multiple GSM drains; the spell's live iid changes when it
     * resolves Stack→Graveyard, so re-deriving the affector iid at emission
     * time would split the per-group TargetSpecs across two iids. Freezing
     * the iid here keeps every group of the same cast pointing at the same
     * stack-resident affector.
     */
    data class PendingTarget(
        val spellForgeCardId: Int,
        val spellName: String,
        val index: Int,
        val affectorInstanceIdAtRecord: Int,
        val affectees: List<TargetAffectee>,
        val isStackAbility: Boolean = false,
        val promptId: Int? = null,
        val abilityIdentity: ResolvedAbilityIdentity? = null,
        /** Forge `SpellAbility.id` for the targeting spell/ability. */
        val forgeAbilityId: Int = 0,
    ) {
        data class TargetAffectee(
            val targetForgeCardId: Int? = null,
            val targetSeatId: Int? = null,
            val distribution: Int? = null,
        )
    }

    data class PendingTargetEntry(
        val version: Long,
        val spec: PendingTarget,
    )

    private val pendingTargetSpecs = PendingTargetStore()

    fun addPendingTargetSpec(spec: PendingTarget) {
        pendingTargetSpecs.add(spec)
    }

    fun removePendingTargetSpecs(predicate: (PendingTarget) -> Boolean) {
        pendingTargetSpecs.removeIf(predicate)
    }

    fun snapshotPendingTargetSpecs(): List<PendingTarget> = pendingTargetSpecs.specs()

    fun snapshotPendingTargetSpecEntries(): List<PendingTargetEntry> = pendingTargetSpecs.entries()

    fun consumePendingTargetSpecs(specs: List<PendingTarget>) = pendingTargetSpecs.consume(specs)

    fun consumePendingTargetSpecEntries(entries: List<PendingTargetEntry>) = pendingTargetSpecs.consumeEntries(entries)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val HISTORY_CAP = 100
        private val log = LoggerFactory.getLogger(InteractivePromptBridge::class.java)
    }

    fun getTimeoutMs(): Long? = timeoutMs

    // ── Call history ────────────────────────────────────────────────────────
    // Records every requestChoice invocation with its outcome. The engine
    // calls controller overrides that silently block on this bridge; without
    // a trace it's impossible to tell WHAT prompted and WHETHER it timed out.
    // Tests inspect `history` to diagnose unexpected blocking calls.

    private val promptHistory = PromptHistory(HISTORY_CAP)

    /** Immutable snapshot of recent prompt calls (oldest first, capped at [HISTORY_CAP]). */
    val history: List<PromptRecord> get() = promptHistory.snapshot

    private fun record(
        request: PromptRequest,
        outcome: PromptCallStatus,
        result: List<Int>,
    ) = promptHistory.record(request, outcome, result)
    // ────────────────────────────────────────────────────────────────────────

    // -- Diagnostic context (set by GameLoopController after thread launch) --

    @Volatile private var diagnosticGame: Game? = null

    @Volatile private var diagnosticThread: Thread? = null

    /** Set diagnostic context for timeout messages. Called by [leyline.bridge.coord.GameLoopController]. */
    fun setDiagnosticContext(
        game: Game,
        engineThread: Thread,
    ) {
        diagnosticGame = game
        diagnosticThread = engineThread
    }

    // ── Reveal tracking ─────────────────────────────────────────────────────
    // Engine fires reveal(); the leyline.bridge.forge.PlayerController override
    // pushes forge card IDs here. Leyline drains at diff-build time to produce
    // RevealedCardCreated annotations and populate Revealed zones.

    /**
     * Record of revealed cards: list of forge card IDs + the seatId of the player
     * who revealed them.
     */
    data class RevealRecord(
        val forgeCardIds: List<ForgeCardId>,
        val ownerSeatId: SeatId,
        val viewerSeatId: SeatId,
        val sourceZone: RevealZone? = null,
        val sourceCardId: ForgeCardId? = null,
    )

    private val revealQueue = ConcurrentLinkedQueue<RevealRecord>()

    /** Push a batch of revealed card IDs (called from engine thread via the PlayerController.reveal override). */
    fun recordReveal(
        forgeCardIds: List<ForgeCardId>,
        ownerSeatId: SeatId,
        viewerSeatId: SeatId,
        sourceZone: RevealZone? = null,
        sourceCardId: ForgeCardId? = null,
    ) {
        if (forgeCardIds.isEmpty()) return
        revealQueue.add(RevealRecord(forgeCardIds, ownerSeatId, viewerSeatId, sourceZone, sourceCardId))
        log.debug("Reveal recorded: {} cards for seat {}", forgeCardIds.size, ownerSeatId)
    }

    /** Clear all accumulated state for puzzle hot-swap. */
    fun resetForPuzzle() {
        promptHistory.clear()
        revealQueue.clear()
        pendingTargetSpecs.clear()
        journal.resetForPuzzle()
    }

    /** Drain all pending reveal records (called from annotation-build thread). */
    fun drainReveals(): List<RevealRecord> {
        val result = mutableListOf<RevealRecord>()
        while (true) {
            val record = revealQueue.poll() ?: break
            result.add(record)
        }
        return result
    }

    /**
     * Called from the engine thread. Interactive routes block until a client
     * response or timeout; [ResolvedPromptRoute.AutoResolve] returns its
     * configured default synchronously without publishing pending state.
     *
     * @param request describes the prompt to show the client
     * @return list of selected indices into [request.options]
     */
    fun requestChoice(
        request: PromptRequest,
        targetingSa: SpellAbility? = null,
    ): List<Int> {
        resolvePromptPolicyDefault(request) { indices ->
            record(request, PromptCallStatus.DEFAULTED_POLICY, indices)
            prioritySignal?.markPromptResolved()
        }?.let { return it }
        // A callback reached outside a real prompt window must refuse, not
        // guess: each fallback below manufactures an answer indistinguishable
        // from a real choice. Strict mode surfaces the bug on the first
        // occurrence; production degrades loudly.
        val scope = NonInteractiveScope.active
        if (scope != null) {
            if (strict) {
                refuseStrictPrompt(
                    "[strict] Prompt [${request.promptType}] \"${request.message}\" requested inside non-interactive scope $scope",
                )
            }
            val fallback = listOf(request.defaultIndex)
            record(request, PromptCallStatus.NON_INTERACTIVE_SCOPE, fallback)
            return fallback
        }

        if (!isGameLoopThread()) {
            if (strict) {
                val thread = Thread.currentThread().name
                refuseStrictPrompt(
                    "[strict] Prompt [${request.promptType}] \"${request.message}\" requested from non-game thread $thread",
                )
            }
            val fallback = listOf(request.defaultIndex)
            record(request, PromptCallStatus.NON_GAME_THREAD, fallback)
            return fallback
        }

        val configuredTimeoutMs = timeoutMs
        if (configuredTimeoutMs == 0L) {
            return listOf(request.defaultIndex)
        }

        requestMigratedChoice(request, targetingSa, configuredTimeoutMs)?.let { return it }
        if (strict) refuseStrictPrompt("No coordinator-owned runtime for ${request.route}")
        val fallback = listOf(request.defaultIndex)
        record(request, PromptCallStatus.DEFAULTED_POLICY, fallback)
        return fallback
    }

    /** Route one Forge modal choice through the match-scoped runtime. */
    fun requestModalChoice(
        request: PromptRequest,
        possible: List<AbilitySub>,
        sourceCard: Card,
        sourceAbility: SpellAbility,
    ): List<AbilitySub> = modalChoiceAdapter.request(request, possible, sourceCard, sourceAbility)

    /** Route one iterative mana-source payment with its exact Forge option handles. */
    fun requestManaSourcePayment(
        request: PromptRequest,
        candidateHandles: List<Card>,
    ): ManaSourcePaymentResult {
        val route = request.route as? ResolvedPromptRoute.PayCosts
        val runtime = runtimeBindings.manaSourcePayment
        if (route?.descriptor?.manaSourcePayment == null || runtime == null) {
            return ManaSourcePaymentResult(requestChoice(request), emptyList())
        }
        if (NonInteractiveScope.active != null || !isGameLoopThread() || timeoutMs == 0L) {
            return ManaSourcePaymentResult(requestChoice(request), emptyList())
        }

        return try {
            val result = runtime.awaitPayment(request, candidateHandles, timeoutMs)
            record(request, PromptCallStatus.RESPONDED, result)
            prioritySignal?.markPromptResolved()
            result
        } catch (_: ManaSourcePaymentTimeoutException) {
            val fallback = ManaSourcePaymentResult(listOf(request.defaultIndex), emptyList())
            record(request, PromptCallStatus.TIMEOUT, fallback)
            prioritySignal?.signal()
            fallback
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }

    /** Route one non-iterative PayCosts request with its exact Forge option handles. */
    fun requestOneShotPayCosts(
        request: PromptRequest,
        candidateHandles: List<Card>,
    ): OneShotPayCostsResult {
        val route = request.route as? ResolvedPromptRoute.PayCosts
        val runtime = runtimeBindings.oneShotPayCosts
        if (route?.descriptor?.manaSourcePayment != null || runtime == null) {
            val fallback = listOf(request.defaultIndex)
            record(request, PromptCallStatus.DEFAULTED_POLICY, fallback)
            return fallbackOneShot(fallback, candidateHandles)
        }
        if (NonInteractiveScope.active != null || !isGameLoopThread() || timeoutMs == 0L) {
            val fallback = listOf(request.defaultIndex)
            val outcome =
                when {
                    NonInteractiveScope.active != null -> PromptCallStatus.NON_INTERACTIVE_SCOPE
                    timeoutMs == 0L -> PromptCallStatus.DEFAULTED_POLICY
                    else -> PromptCallStatus.NON_GAME_THREAD
                }
            record(request, outcome, fallback)
            return fallbackOneShot(fallback, candidateHandles)
        }

        return try {
            val result = runtime.awaitPayment(request, candidateHandles, timeoutMs)
            record(request, PromptCallStatus.RESPONDED, result.optionIndices)
            prioritySignal?.markPromptResolved()
            result
        } catch (_: OneShotPayCostsTimeoutException) {
            val fallback = fallbackOneShot(listOf(request.defaultIndex), candidateHandles)
            record(request, PromptCallStatus.TIMEOUT, fallback.optionIndices)
            prioritySignal?.signal()
            fallback
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }

    /** Route the grounded GatherCounters payment through its match-scoped window and exact handles. */
    fun requestGatherCounters(
        window: GatherCountersWindowInput,
        candidateHandles: List<Card>,
    ): GatherCountersResult {
        val runtime = runtimeBindings.oneShotPayCosts
        if (runtime == null || NonInteractiveScope.active != null || !isGameLoopThread() || timeoutMs == 0L) {
            return window.firstFitResult(candidateHandles)
        }
        val result = runtime.awaitGatherCounters(window, candidateHandles, timeoutMs)
        if (result.timedOut) prioritySignal?.signal() else prioritySignal?.markPromptResolved()
        return result
    }

    /** Route one ordered-card request with its exact Forge option handles. */
    fun requestOrder(
        request: PromptRequest,
        candidateHandles: List<Card>,
        move: OrderMoveIntent? = null,
    ): OrderInteractionResult {
        check(request.route is ResolvedPromptRoute.Order) { "Order route required" }
        if (NonInteractiveScope.active != null || !isGameLoopThread() || timeoutMs == 0L) {
            return fallbackOrder(requestChoice(request), candidateHandles)
        }
        val runtime = checkNotNull(runtimeBindings.order) { "Order runtime is not registered" }
        return try {
            val result = runtime.awaitOrder(request, candidateHandles, move, timeoutMs)
            record(request, PromptCallStatus.RESPONDED, result.optionIndices)
            prioritySignal?.markPromptResolved()
            result
        } catch (_: OrderInteractionTimeoutException) {
            val fallback = fallbackOrder(listOf(request.defaultIndex), candidateHandles)
            record(request, PromptCallStatus.TIMEOUT, fallback.optionIndices)
            prioritySignal?.signal()
            fallback
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }

    /** Route a fixed-total divided allocation across the exact selected entities. */
    fun requestDistribution(
        request: PromptRequest,
        window: DistributionWindowValue,
    ): DistributionInteractionResult {
        check(request.route is ResolvedPromptRoute.Distribution) { "Distribution route required" }
        if (NonInteractiveScope.active != null || !isGameLoopThread() || timeoutMs == 0L) {
            val fallback = window.fallback()
            record(request, PromptCallStatus.DEFAULTED_POLICY, fallback.amounts.values.toList())
            return fallback
        }
        val runtime = checkNotNull(runtimeBindings.distribution) { "Distribution runtime is not registered" }
        return try {
            val result = runtime.awaitDistribution(window, timeoutMs)
            record(
                request,
                if (result.timedOut) PromptCallStatus.TIMEOUT else PromptCallStatus.RESPONDED,
                result.amounts.values.toList(),
            )
            if (result.timedOut) prioritySignal?.signal() else prioritySignal?.markPromptResolved()
            result
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }

    /** Route one Scry or Surveil grouping request with its exact Forge card handles. */
    fun requestGrouping(
        request: PromptRequest,
        candidateHandles: List<Card>,
    ): GroupingInteractionResult {
        check(request.route is ResolvedPromptRoute.Grouping) { "Grouping route required" }
        if (NonInteractiveScope.active != null || !isGameLoopThread() || timeoutMs == 0L) {
            return fallbackGrouping(request, requestChoice(request), candidateHandles)
        }
        val runtime = checkNotNull(runtimeBindings.grouping) { "Grouping runtime is not registered" }
        return try {
            val result = runtime.awaitGrouping(request, candidateHandles, timeoutMs)
            val selected =
                if (candidateHandles.size == 1 && request.options.size == 2) {
                    listOf(if (result.awayHandles.isEmpty()) 0 else 1)
                } else {
                    result.awayHandles.map(candidateHandles::indexOf)
                }
            record(
                request,
                if (result.timedOut) PromptCallStatus.TIMEOUT else PromptCallStatus.RESPONDED,
                selected,
            )
            if (result.timedOut) prioritySignal?.signal() else prioritySignal?.markPromptResolved()
            result
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }

    fun finalizeGroupingArrangement(
        result: GroupingInteractionResult,
        finalTopHandles: List<Card>,
        awayHandles: List<Card>,
    ) {
        if (result.interactionId.isEmpty()) return
        checkNotNull(result.finalizer) { "Grouping result has no exact finalization owner" }
            .finalizeArrangement(result, finalTopHandles, awayHandles)
    }

    /** Route one card-backed SelectN request with its exact Forge option handles. */
    fun requestCardSelect(
        request: PromptRequest,
        candidateHandles: List<Card>,
    ): CardSelectInteractionResult {
        check(request.route is ResolvedPromptRoute.CardSelect) { "CardSelect route required" }
        if (NonInteractiveScope.active != null || !isGameLoopThread() || timeoutMs == 0L) {
            return fallbackCardSelect(listOf(request.defaultIndex), candidateHandles)
        }
        val runtime = checkNotNull(runtimeBindings.cardSelect) { "CardSelect runtime is not registered" }
        return try {
            val result = runtime.awaitSelection(request, candidateHandles, timeoutMs)
            record(request, PromptCallStatus.RESPONDED, result.optionIndices)
            prioritySignal?.markPromptResolved()
            result
        } catch (_: CardSelectInteractionTimeoutException) {
            val fallback = fallbackCardSelect(listOf(request.defaultIndex), candidateHandles)
            record(request, PromptCallStatus.TIMEOUT, fallback.optionIndices)
            prioritySignal?.signal()
            fallback
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }

    /** Route one residual card choice through the SelectTargets compatibility runtime. */
    fun requestCompatibilityCostSelection(
        request: PromptRequest,
        candidateHandles: List<Card>,
    ): CompatibilityCostSelectionResult {
        check(request.route is ResolvedPromptRoute.CompatibilityCostSelection) {
            "CompatibilityCostSelection route required"
        }
        val runtime = runtimeBindings.compatibilityCostSelection
        if (runtime == null || NonInteractiveScope.active != null || !isGameLoopThread() || timeoutMs == 0L) {
            val fallback = listOf(request.defaultIndex).filter { it in candidateHandles.indices }
            val outcome =
                when {
                    runtime == null || timeoutMs == 0L -> PromptCallStatus.DEFAULTED_POLICY
                    NonInteractiveScope.active != null -> PromptCallStatus.NON_INTERACTIVE_SCOPE
                    else -> PromptCallStatus.NON_GAME_THREAD
                }
            record(request, outcome, fallback)
            return CompatibilityCostSelectionResult(fallback, fallback.map(candidateHandles::get))
        }
        return try {
            val result = runtime.awaitSelection(request, candidateHandles, timeoutMs)
            record(request, PromptCallStatus.RESPONDED, result.optionIndices)
            prioritySignal?.markPromptResolved()
            result.copy(handles = result.optionIndices.mapNotNull(candidateHandles::getOrNull))
        } catch (_: TargetingInteractionTimeoutException) {
            val fallback = listOf(request.defaultIndex).filter { it in candidateHandles.indices }
            record(request, PromptCallStatus.TIMEOUT, fallback)
            prioritySignal?.signal()
            CompatibilityCostSelectionResult(
                optionIndices = fallback,
                handles = fallback.map(candidateHandles::get),
                timedOut = true,
            )
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }

    /** Route one static enum SelectN request through its frozen protocol values. */
    fun requestStaticChoice(request: PromptRequest): List<Int> {
        check(request.route is ResolvedPromptRoute.StaticChoice) { "StaticChoice route required" }
        if (NonInteractiveScope.active != null || !isGameLoopThread() || timeoutMs == 0L) {
            return requestChoice(request)
        }
        val runtime = checkNotNull(runtimeBindings.staticChoice) { "StaticChoice runtime is not registered" }
        return try {
            val result = runtime.awaitSelection(request, timeoutMs)
            record(request, PromptCallStatus.RESPONDED, result)
            prioritySignal?.markPromptResolved()
            result
        } catch (_: StaticChoiceInteractionTimeoutException) {
            val fallback = listOf(request.defaultIndex)
            record(request, PromptCallStatus.TIMEOUT, fallback)
            prioritySignal?.signal()
            fallback
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }

    /** Route one reveal-backed SelectN request through its exact journal entry and card handles. */
    fun requestRevealChoice(
        request: PromptRequest,
        candidateHandles: List<Card>,
        revealEntry: PromptJournal.RevealEntry,
        recordExiledUnderSource: Boolean,
    ): RevealChoiceInteractionResult {
        check(request.route is ResolvedPromptRoute.RevealChoice) { "RevealChoice route required" }
        if (NonInteractiveScope.active != null || !isGameLoopThread() || timeoutMs == 0L) {
            val indices =
                try {
                    requestChoice(request)
                } catch (ex: Exception) {
                    journal.clearActiveReveal(revealEntry)
                    throw ex
                }
            return fallbackRevealChoice(
                indices,
                request,
                candidateHandles,
                revealEntry,
                recordExiledUnderSource,
                journal,
            )
        }
        val runtime = checkNotNull(runtimeBindings.revealChoice) { "RevealChoice runtime is not registered" }
        return try {
            val result = runtime.awaitSelection(request, candidateHandles, revealEntry, recordExiledUnderSource, timeoutMs)
            record(
                request,
                if (result.timedOut) PromptCallStatus.TIMEOUT else PromptCallStatus.RESPONDED,
                result.optionIndices,
            )
            if (result.timedOut) prioritySignal?.signal() else prioritySignal?.markPromptResolved()
            result
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }

    /** Replace provisional payment facts with the exact map returned to Forge. */
    fun recordFinalManaSourcePayment(value: FinalManaSourcePaymentValue) {
        runtimeBindings.manaSourcePayment?.recordFinalPayment(value)
    }

    private fun requestTargetingChoice(
        request: PromptRequest,
        targetingSa: SpellAbility?,
        runtime: TargetingInteractionRuntime,
        configuredTimeoutMs: Long?,
    ): List<Int> =
        try {
            val result =
                runtime.awaitTargeting(
                    request,
                    targetingSa,
                    targetingSa?.let(::resolveAbilityIdentity),
                    configuredTimeoutMs,
                )
            record(request, PromptCallStatus.RESPONDED, result)
            prioritySignal?.markPromptResolved()
            result
        } catch (_: TargetingInteractionTimeoutException) {
            val fallback = listOf(request.defaultIndex)
            record(request, PromptCallStatus.TIMEOUT, fallback)
            prioritySignal?.signal()
            fallback
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }

    private fun requestSearchChoice(
        request: PromptRequest,
        runtime: SearchInteractionRuntime,
        configuredTimeoutMs: Long?,
    ): List<Int> =
        try {
            val result = runtime.awaitSearch(request, configuredTimeoutMs)
            record(request, PromptCallStatus.RESPONDED, result)
            prioritySignal?.markPromptResolved()
            result
        } catch (_: SearchInteractionTimeoutException) {
            val fallback = listOf(request.defaultIndex)
            record(request, PromptCallStatus.TIMEOUT, fallback)
            prioritySignal?.signal()
            fallback
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }

    /** Route one competing replacement choice with its exact Forge handles. */
    fun requestReplacement(
        request: PromptRequest,
        possibleReplacers: List<ReplacementEffect>,
    ): ReplacementInteractionResult? {
        check(request.route is ResolvedPromptRoute.SelectReplacement) { "SelectReplacement route required" }
        if (NonInteractiveScope.active != null || !isGameLoopThread() || timeoutMs == 0L) return null
        val runtime = checkNotNull(runtimeBindings.replacement) { "Replacement runtime is not registered" }
        return try {
            val result = runtime.awaitReplacement(request, possibleReplacers, timeoutMs)
            if (result == null) {
                record(request, PromptCallStatus.DEFAULTED_POLICY, emptyList())
                null
            } else {
                record(
                    request,
                    if (result.timedOut) PromptCallStatus.TIMEOUT else PromptCallStatus.RESPONDED,
                    listOf(result.optionIndex),
                )
                if (result.timedOut) prioritySignal?.signal() else prioritySignal?.markPromptResolved()
                result
            }
        } catch (ex: Exception) {
            record(request, PromptCallStatus.ERROR, emptyList())
            throw ex
        }
    }

    private fun requestMigratedChoice(
        request: PromptRequest,
        targetingSa: SpellAbility?,
        configuredTimeoutMs: Long?,
    ): List<Int>? {
        if (request.route is ResolvedPromptRoute.Targeting) {
            return runtimeBindings.targeting?.let { requestTargetingChoice(request, targetingSa, it, configuredTimeoutMs) }
        }
        if (request.route is ResolvedPromptRoute.Search) {
            return runtimeBindings.search?.let { requestSearchChoice(request, it, configuredTimeoutMs) }
        }
        if (request.route is ResolvedPromptRoute.ModalChoice) {
            error("ModalChoice requests must use requestModalChoice with exact Forge handles")
        }
        return null
    }

    private fun isGameLoopThread(): Boolean {
        val engineThread = diagnosticThread ?: return true
        return Thread.currentThread() == engineThread
    }

    fun resolveAbilityIdentity(ability: SpellAbility): ResolvedAbilityIdentity? = abilityIdentityResolver?.invoke(ability)

    fun resolveCardGrpId(card: Card): Int = cardGrpIdResolver?.invoke(card) ?: 0

    fun resolveTriggerStackAbilityInstanceId(abilityId: Int): Int? = triggerStackAbilityInstanceIdResolver?.invoke(abilityId)

    fun resolveTriggerStackAbilitySourceInstanceId(abilityId: Int): Int? = triggerStackAbilitySourceInstanceIdResolver?.invoke(abilityId)
}

/**
 * Describes an interactive prompt the engine needs answered.
 */
enum class PromptSemantic {
    Generic,

    /** Target selection performed by Forge's targeting coordinator. */
    TargetSelection,
    GroupingSurveil,
    GroupingScry,
    ModalChoice,
    SelectNLegendRule,
    SelectNDiscard,

    /** Resolution-time discard choice, including optional rummage effects. */
    SelectNDiscardEffect,
    Search,
    GroupedSearch,

    /** Choose which of several competing self-replacement effects applies first. */
    SelectReplacement,

    /** Order cards going to the bottom of a library. */
    OrderForBottom,

    /** Order cards going to the top of a library. */
    OrderForTop,

    /** Allocate a fixed damage total across already-selected targets. */
    DividedAllocationDamage,

    /** Allocate a fixed counter total across already-selected targets. */
    DividedAllocationCounters,

    /** "Choose from revealed hand" — Duress, Revealing Eye, Thoughtseize, etc. */
    RevealChoose,

    /**
     * Resolution-time choice. Dig-shaped all-Card Library candidates and
     * complete chooser-visible Card candidates bind CardSelect; other entity
     * domains remain residual.
     */
    SelectNResolution,

    /** Manifest Dread's mandatory top-two resolution choice. */
    ManifestDread,

    /** Triggered resolution choice that suspects one of the triggering cards. */
    SuspectChoice,

    /**
     * Brainstorm-style resolution pick: choose hand cards to put into the
     * library, followed by an `OrderReq` for top-library ordering.
     */
    SelectNLibraryPutback,

    /**
     * Sacrifice selection during effect resolution (edict-style prompts).
     * Routes to a regular `SelectNReq`, not the cost-payment envelope.
     */
    SelectNSacrificeEffect,

    /**
     * "Sacrifice N permanents" as a cost-payment selection. Routes to
     * `PayCostsReq` (NonManaPayment / Payment context); resolution sacrifice
     * effects should use [SelectNSacrificeEffect] instead.
     */
    SelectNCostSacrifice,

    /**
     * "Exile N cards from your graveyard" as an additional cost — Escape's
     * `K:Escape:<mana>|Type:Card|N:<n>` cost-payment selection. Routes to
     * `PayCostsReq` (NonManaPayment / Payment context) so the client renders
     * the cost-payment picker, parallel to the existing sacrifice cost path.
     */
    SelectNCostExileFromGrave,

    /**
     * Collect Evidence additional cost: choose any number of graveyard cards
     * whose total mana value meets the threshold. Routes to a weighted
     * `PayCostsReq` rather than the fixed-count Escape envelope.
     */
    SelectNCostCollectEvidence,

    /** Enlist's optional attack cost: a PayCostsReq, not target selection. */
    EnlistCost,

    /** Grounded total-power and exact-count tap or untap payments. */
    TapPaymentCost,

    /**
     * Station's tap-a-creature activation cost. Routes to the Station-specific
     * `PayCostsReq` promptId while keeping the normal non-mana payment envelope.
     */
    StationTapCost,

    /** Ninjutsu's "return an unblocked attacker" activation cost. */
    ReturnUnblockedAttackerCost,

    /** Convoke's tap-creature cost payment. */
    ConvokeCost,

    /** Improvise's tap-artifact cost payment. */
    ImproviseCost,

    /** Waterbend's tap artifact/creature cost payment. */
    WaterbendCost,

    /** Mutate's resolution-time choice for which component is on top. */
    MutateTopBottom,

    /** Learn's exact Lesson/discard card picker. */
    LearnLesson,

    /** Static enum choice: choose one or more colors via `StaticList_Colors`. */
    StaticColorChoice,

    /** Static enum choice: choose a subtype via `StaticList_SubTypes`. */
    StaticSubtypeChoice,

    /** Static enum choice: choose odd or even via `StaticList_Parities`. */
    StaticParityChoice,

    /** Static enum choice: choose a keyword via `StaticList_Keywords`. */
    StaticKeywordChoice,

    /** Static enum choice: choose a card type via `StaticList_CardTypes`. */
    StaticCardTypeChoice,
}

/**
 * Engine-thread request for one blocking Forge choice.
 *
 * This is the handoff shape between Forge controller/gui overrides and the
 * typed prompt runtime. It is not the client protocol prompt. Producers fill
 * in the source choice shape, option labels, selection cardinality, semantic
 * route, and optional entity metadata while the engine is blocked in a typed
 * bridge request. The owning runtime materializes the appropriate GRE request
 * (`SelectNReq`, `SelectTargetsReq`, `PayCostsReq`, etc.) and maps a correlated
 * client response back to [options].
 */
data class PromptRequest(
    /** Coarse source shape from the Forge API override; diagnostic only. */
    val promptType: String,
    /** Source/debug text from Forge. MTGA UI text is selected by outbound GRE Prompt.promptId + parameters. */
    val message: String,
    val options: List<String>,
    val min: Int = 1,
    val max: Int = 1,
    val allowRepeat: Boolean = false,
    val defaultIndex: Int = 0,
    val candidateRefs: List<PromptCandidateRefDto> = emptyList(),
    /** Exact stack objects provided by Forge's stack-target callback. */
    val targetingCandidates: List<TargetingCandidateValue.StackObject> = emptyList(),
    /** Original callback option index for Forge's optional finish-targeting sentinel. */
    val targetingFinishOptionIndex: Int? = null,
    /** Sole route authority; data-class copies used for re-prompts retain this value. */
    val route: ResolvedPromptRoute = PromptRouteResolver.resolve(PromptSemantic.Generic, candidateRefs.isNotEmpty()),
    /** Per-candidate selection weights for weighted cost-payment prompts. */
    val costSelectionWeights: List<Int> = emptyList(),
    /** Minimum total selected weight for weighted cost-payment prompts. */
    val minSelectionWeight: Int? = null,
    /** Exact stack object used by a grounded tap-payment prompt's CardId parameter. */
    val payCostsPromptSource: PayCostsPromptSourceInput? = null,
    /** Source card entity ID for targeting prompts (spell or ability source). */
    val sourceEntityId: Int? = null,
    /** One-based target-group index within the spell or ability. */
    val targetIndex: Int = 1,
    /** Target-group prompt localization id, shared with TargetSpec. */
    val targetPromptId: Int? = null,
    /** Source card name when the live Forge id no longer resolves to card data. */
    val sourceCardName: String? = null,
    /** True when modal originates from a triggered ability (ETB), not spell-time. */
    val isTriggeredAbility: Boolean = false,
    /**
     * Forge `SpellAbility.id` for triggered modal prompts. Drives SA-id-keyed
     * surrogate iid resolution for the modal CTO request's `sourceInstanceId`,
     * matching the iid the StateMapper emits on the matching
     * AbilityInstanceCreated. Zero when the prompt isn't a triggered modal.
     */
    val forgeAbilityId: Int = 0,
    /**
     * All revealed cards (unfiltered) for reveal-choose prompts.
     * Maps to `unfilteredIds` in SelectNReq — shows the full hand even when
     * only a subset is selectable (e.g., noncreature nonland for Duress).
     */
    val unfilteredRefs: List<PromptCandidateRefDto> = emptyList(),
    /** Static enum domain for SelectN prompts whose ids are not game object instanceIds. */
    val staticList: StaticList? = null,
    /** Per-option static enum values frozen into coordinator-owned StaticChoice windows. */
    val staticOptionIds: List<Int> = emptyList(),
    /** Waterbend mana component carried into its PayCostsReq payment envelope. */
    val waterbendManaCost: List<Pair<wotc.mtgo.gre.external.messaging.Messages.ManaColor, Int>> = emptyList(),
    /** Non-localized cost string for Waterbend's PayCostsReq prompt parameter. */
    val waterbendCostString: String? = null,
    /** Frozen source/shape facts for the migrated library-search route. */
    val searchSource: SearchSourceValue? = null,
    /** Ordered, disjoint option-index partitions for SearchFromGroupsReq. */
    val searchGroupOptionIndices: List<List<Int>> = emptyList(),
    /** Frozen source identity for coordinator-owned Scry and Surveil grouping. */
    val groupingSource: GroupingSourceValue? = null,
    /**
     * True when the player may back out of the window entirely (Forge's `optional`
     * cost contract): the client's Decline/cancel returns an empty selection, which
     * Forge rejects as an illegal payment and unwinds the activation. Mandatory
     * windows leave this false so a stray cancel cannot skip a required choice.
     */
    val cancellable: Boolean = false,
) {
    /** Diagnostic identity derived from the immutable route. */
    val semantic: PromptSemantic get() = route.semantic
}
