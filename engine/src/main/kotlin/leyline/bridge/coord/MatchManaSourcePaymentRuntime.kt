package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.FinalManaSourcePaymentValue
import leyline.bridge.handoff.ManaSourcePaymentCommandReceipt
import leyline.bridge.handoff.ManaSourcePaymentKind
import leyline.bridge.handoff.ManaSourcePaymentResult
import leyline.bridge.handoff.ManaSourcePaymentRuntime
import leyline.bridge.handoff.ManaSourcePaymentShardValue
import leyline.bridge.handoff.ManaSourcePaymentTimeoutException
import leyline.bridge.handoff.ManaSourcePaymentWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedManaSourcePaymentInteraction
import leyline.game.PendingPromptCut
import leyline.game.PlaybackTerminalFailure
import leyline.game.PromptMaterializationDiagnostic
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.data.KeywordAbilityIds
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Exact iterative mana-source payment lifecycle beneath [MatchCutCoordinator]. */
internal class MatchManaSourcePaymentRuntime(
    private val owner: MatchCutCoordinator,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : ManaSourcePaymentRuntime,
    PromptTerminalCutOwner {
    private sealed interface Command {
        val reply: CompletableFuture<ManaSourcePaymentCommandReceipt>

        data class Select(
            val interactionId: String,
            val gameStateId: Int,
            val optionIndices: List<Int>,
            override val reply: CompletableFuture<ManaSourcePaymentCommandReceipt> = CompletableFuture(),
        ) : Command

        data class Complete(
            val interactionId: String,
            val gameStateId: Int,
            val optionIndices: List<Int>,
            override val reply: CompletableFuture<ManaSourcePaymentCommandReceipt> = CompletableFuture(),
        ) : Command

        data class Cancel(
            val interactionId: String,
            val gameStateId: Int,
            override val reply: CompletableFuture<ManaSourcePaymentCommandReceipt> = CompletableFuture(),
        ) : Command

        data class Undo(
            val interactionId: String,
            val gameStateId: Int,
            override val reply: CompletableFuture<ManaSourcePaymentCommandReceipt> = CompletableFuture(),
        ) : Command

        data class Terminal(
            val cause: Throwable,
            override val reply: CompletableFuture<ManaSourcePaymentCommandReceipt> = CompletableFuture(),
        ) : Command
    }

    private data class Window(
        val interactionId: String,
        val handlesByOption: Map<Int, Card>,
        /** Opening value, replayed onto when a selection is released. */
        val initialValue: ManaSourcePaymentWindowValue,
        val exchange: InteractiveCommandExchange<Command, ManaSourcePaymentCommandReceipt>,
        var value: ManaSourcePaymentWindowValue,
        var published: PublishedManaSourcePaymentInteraction,
        var cut: PendingPromptCut<ManaSourcePaymentWindowValue>,
        var optionByInstanceId: Map<Int, Int>,
    )

    private data class Publication(
        val published: PublishedManaSourcePaymentInteraction,
        val cut: PendingPromptCut<ManaSourcePaymentWindowValue>,
        val optionByInstanceId: Map<Int, Int>,
    )

    private val capture = ManaSourcePaymentWindowCapture(owner)
    private val nextDeliveryToken = AtomicLong()
    private var window: Window? = null

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
    internal var afterCommandEnqueue: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null
    internal var beforeDeliveryRelease: (() -> Unit)? = null

    override fun awaitPayment(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): ManaSourcePaymentResult {
        val initial =
            try {
                capture.initial(request, candidateHandles)
            } catch (ex: Exception) {
                owner.failPrompt(ex)
            }
        val interactionId = UUID.randomUUID().toString()
        lateinit var pending: Window
        publish(interactionId, initial.value) { publication ->
            check(window == null) { "A mana-source payment is already pending" }
            pending =
                Window(
                    interactionId = interactionId,
                    handlesByOption = initial.handlesByOption,
                    initialValue = initial.value,
                    exchange =
                        InteractiveCommandExchange(
                            deadlineNanos = timeoutMs?.let { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it) },
                            nextDeliveryToken = nextDeliveryToken::incrementAndGet,
                            replyOf = Command::reply,
                        ),
                    value = initial.value,
                    published = publication.published,
                    cut = publication.cut,
                    optionByInstanceId = publication.optionByInstanceId,
                )
            window = pending
        }
        owner.bridge.prioritySignal.signal()
        return try {
            awaitCommands(pending)
        } catch (ex: ManaSourcePaymentTimeoutException) {
            throw ex
        } catch (ex: PlaybackTerminalFailure) {
            throw ex
        } catch (ex: Exception) {
            owner.failPrompt(ex, pending.cut)
        }
    }

    override fun recordFinalPayment(value: FinalManaSourcePaymentValue) {
        if (value.kind == ManaSourcePaymentKind.Waterbend) return
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            if (value.payments.isEmpty()) {
                owner.bridge
                    .promptBridge(runtimeSeat)
                    .journal
                    .clearConvokePayments(value.sourceForgeCardId)
            } else {
                recordPaymentFacts(value)
            }
        }
    }

    override fun current(): PublishedManaSourcePaymentInteraction? =
        synchronized(owner.feedLock) {
            window?.takeUnless { it.exchange.inFlight != null || it.exchange.delivery != null }?.published
        }

    fun select(
        interactionId: String,
        gameStateId: Int,
        instanceIds: List<Int>,
    ): ManaSourcePaymentCommandReceipt? = submitSelection(interactionId, gameStateId, instanceIds, complete = false)

    fun complete(
        interactionId: String,
        gameStateId: Int,
        instanceIds: List<Int>,
    ): ManaSourcePaymentCommandReceipt? = submitSelection(interactionId, gameStateId, instanceIds, complete = true)

    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): ManaSourcePaymentCommandReceipt? = submit(Command.Cancel(interactionId, gameStateId))

    /** Release the most recently selected mana source, keeping the window open. */
    fun undo(
        interactionId: String,
        gameStateId: Int,
    ): ManaSourcePaymentCommandReceipt? = submit(Command.Undo(interactionId, gameStateId))

    fun acknowledgeDelivery(
        interactionId: String,
        token: Long,
    ): Boolean {
        val delivery =
            synchronized(owner.feedLock) {
                val pending = window?.takeIf { it.interactionId == interactionId } ?: return@synchronized null
                pending.exchange.acknowledgeLocked(token)
            } ?: return false
        delivery.awaitRelease()
        return true
    }

    override fun terminalCutCandidateLocked(): PromptTerminalCutCandidate? =
        window
            ?.cut
            ?.let { PromptTerminalCutCandidate(PromptTerminalPriority.ManaSourcePayment, it) }
            .also { afterDeliveryCutLookup?.invoke() }

    override fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            val pending = window ?: return
            pending.exchange.terminateLocked(cause, Command.Terminal(cause))
            window = null
        }
    }

    override fun reset() {
        synchronized(owner.feedLock) { window = null }
    }

    private fun submitSelection(
        interactionId: String,
        gameStateId: Int,
        instanceIds: List<Int>,
        complete: Boolean,
    ): ManaSourcePaymentCommandReceipt? {
        if ((!complete && instanceIds.isEmpty()) || instanceIds.size != instanceIds.distinct().size) return null
        val command =
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val pending = matching(interactionId, gameStateId) ?: return@synchronized null
                val options = instanceIds.map { pending.optionByInstanceId[it] ?: return@synchronized null }
                if (pending.value.selections.size + options.size > pending.value.maxSelection) return@synchronized null
                if (!canPayFrozenShards(pending.value, options)) return@synchronized null
                if (complete) Command.Complete(interactionId, gameStateId, options) else Command.Select(interactionId, gameStateId, options)
            } ?: return null
        return submit(command)
    }

    private fun submit(command: Command): ManaSourcePaymentCommandReceipt? {
        val pending =
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val current = matching(command) ?: return null
                current.exchange.admitLocked(command)
                afterCommandEnqueue?.invoke()
                current
            }
        return pending.exchange.awaitReply(command)
    }

    private fun awaitCommands(pending: Window): ManaSourcePaymentResult {
        while (true) {
            val command = nextCommand(pending)
            when (command) {
                is Command.Select ->
                    republish(pending, command, capture.select(pending.value, pending.handlesByOption, command.optionIndices))
                is Command.Undo -> republish(pending, command, undone(pending))
                is Command.Complete -> {
                    val value = capture.select(pending.value, pending.handlesByOption, command.optionIndices)
                    return complete(pending, command, value, value.selections.map { it.originalOptionIndex })
                }
                is Command.Cancel ->
                    return complete(
                        pending,
                        command,
                        pending.value,
                        pending.value.selections.map { it.originalOptionIndex },
                    )
                is Command.Terminal -> throw command.cause
            }
        }
    }

    /**
     * Value with the most recent selection released. Replayed from the opening value
     * rather than inverted, so the remaining cost and freed handles stay exact.
     */
    private fun undone(pending: Window): ManaSourcePaymentWindowValue {
        val remaining =
            pending.value.selections
                .dropLast(1)
                .map { it.originalOptionIndex }
        if (remaining.isEmpty()) return pending.initialValue
        return capture.select(pending.initialValue, pending.handlesByOption, remaining)
    }

    /** Replace the open window's value and redeliver it to the client. */
    private fun republish(
        pending: Window,
        command: Command,
        next: ManaSourcePaymentWindowValue,
    ) {
        lateinit var delivery: CommandDelivery
        publish(
            interactionId = pending.interactionId,
            value = next,
            beforePrepare = { recordPaymentFacts(next) },
        ) { publication ->
            check(window === pending && pending.exchange.inFlight === command)
            pending.value = next
            pending.published = publication.published
            pending.cut = publication.cut
            pending.optionByInstanceId = publication.optionByInstanceId
            delivery = pending.exchange.beginDeliveryLocked()
            command.reply.complete(ManaSourcePaymentCommandReceipt(pending.interactionId, completed = false, delivery.token))
        }
        delivery.awaitAcknowledgement()
        beforeDeliveryRelease?.invoke()
        synchronized(owner.feedLock) {
            if (window === pending) pending.exchange.clearDeliveryLocked()
            delivery.released.complete(Unit)
        }
    }

    private fun complete(
        pending: Window,
        command: Command,
        finalValue: ManaSourcePaymentWindowValue,
        selectedOptions: List<Int>,
    ): ManaSourcePaymentResult {
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            check(window === pending && pending.exchange.inFlight === command)
            recordPaymentFacts(finalValue)
            window = null
            command.reply.complete(ManaSourcePaymentCommandReceipt(pending.interactionId, completed = true))
        }
        return ManaSourcePaymentResult(
            selectedOptions,
            finalValue.selections.map { ManaSourcePaymentShardValue(it.originalOptionIndex, it.costColor) },
        )
    }

    private fun publish(
        interactionId: String,
        value: ManaSourcePaymentWindowValue,
        beforePrepare: () -> Unit = {},
        onPublished: (Publication) -> Unit,
    ): Publication =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val prior = owner.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
            val routes = owner.viewerRoutes(runtimeSeat)
            val playerRoute = routes.single { it.viewer.role == leyline.game.state.ProjectionViewerRole.Player }
            val diagnostic = PromptMaterializationDiagnostic(interactionId, value)
            val prepared =
                try {
                    beforePrepare()
                    playerRoute.builder.prepareManaSourcePayment(game, planner, value, routes)
                } catch (ex: Exception) {
                    owner.failPrompt(ex, diagnostic = diagnostic)
                }
            val player = prepared.player
            val published =
                PublishedManaSourcePaymentInteraction(
                    interactionId,
                    checkNotNull(player.bundle.actionGameStateId),
                    value.kind,
                )
            val exact =
                PendingPromptCut(
                    interactionId,
                    published.gameStateId,
                    value,
                    player.bundle.messages,
                    player.transition,
                )
            val projection = player.transition.nextState
            val optionEntries =
                value.candidates.map { candidate ->
                    val instanceId =
                        projection.identities.forgeIdToInstanceId[candidate.forgeCardId]?.value
                            ?: owner.failPrompt(
                                IllegalStateException("Mana-source candidate ${candidate.forgeCardId.value} was not projected"),
                                exact,
                            )
                    instanceId to candidate.originalOptionIndex
                }
            val optionByInstanceId = optionEntries.toMap()
            if (optionByInstanceId.size != optionEntries.size) {
                owner.failPrompt(IllegalStateException("Mana-source candidates have ambiguous client identities"), exact)
            }
            val publication = Publication(published, exact, optionByInstanceId)
            owner.cutInstaller.install(
                PreparedCut.prepareForViewers(
                    prior = prior,
                    planner = planner,
                    outputs = prepared.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                    projection = prepared.transition,
                    closesPlaybackFrame = prepared.closesPlaybackFrame,
                    playbackOwnerSeatId = runtimeSeat.takeIf { prepared.closesPlaybackFrame },
                ),
                CutInstallHooks(beforeInstall = beforeInstall, afterInstall = afterInstall),
                onInstalled = { onPublished(publication) },
            ) { ex -> owner.failPrompt(ex, exact) }
            publication
        }

    private fun nextCommand(pending: Window): Command =
        pending.exchange.next {
            beforeTimeoutClaim?.invoke()
            synchronized(owner.feedLock) {
                if (window === pending && !pending.exchange.queuedLocked()) {
                    window = null
                    throw ManaSourcePaymentTimeoutException()
                }
            }
            pending.exchange.takeQueued()
        }

    private fun matching(
        interactionId: String,
        gameStateId: Int,
    ): Window? {
        val pending = window ?: return null
        if (pending.exchange.inFlight != null || pending.exchange.delivery != null) return null
        if (pending.published.interactionId != interactionId || pending.published.gameStateId != gameStateId) return null
        return pending
    }

    private fun matching(command: Command): Window? =
        when (command) {
            is Command.Select -> matching(command.interactionId, command.gameStateId)
            is Command.Complete -> matching(command.interactionId, command.gameStateId)
            is Command.Cancel -> matching(command.interactionId, command.gameStateId)
            is Command.Undo -> matching(command.interactionId, command.gameStateId)
            is Command.Terminal -> null
        }

    private fun canPayFrozenShards(
        value: ManaSourcePaymentWindowValue,
        options: List<Int>,
    ): Boolean {
        val candidates = value.candidates.associateBy { it.originalOptionIndex }
        val requested = options.map { candidates[it]?.costColor ?: return false }.groupingBy { it }.eachCount()
        val available = value.manaCost.toMap()
        return requested.all { (color, count) -> count <= available.getOrDefault(color, 0) }
    }

    private fun recordPaymentFacts(value: ManaSourcePaymentWindowValue) {
        if (value.kind == ManaSourcePaymentKind.Waterbend || value.selections.isEmpty()) return
        val source = value.sourceForgeCardId ?: return
        val substitutionGrpId =
            if (value.kind == ManaSourcePaymentKind.Improvise) KeywordAbilityIds.IMPROVISE else KeywordAbilityIds.CONVOKE
        val paymentAbilityGrpId =
            if (value.kind == ManaSourcePaymentKind.Improvise) KeywordAbilityIds.IMPROVISE else KeywordAbilityIds.CONVOKE_PAYMENT
        owner.bridge
            .promptBridge(runtimeSeat)
            .journal
            .record(
                PromptSideEffect.ConvokePayments(
                    sourceForgeCardId = source,
                    payments =
                        value.selections.map {
                            PromptSideEffect.ConvokePayment(
                                paymentForgeCardId = it.forgeCardId,
                                color = it.paymentColor.number,
                                substitutionGrpId = substitutionGrpId,
                                paymentAbilityGrpId = paymentAbilityGrpId,
                            )
                        },
                ),
            )
    }

    private fun recordPaymentFacts(value: FinalManaSourcePaymentValue) {
        val substitutionGrpId =
            if (value.kind == ManaSourcePaymentKind.Improvise) KeywordAbilityIds.IMPROVISE else KeywordAbilityIds.CONVOKE
        val paymentAbilityGrpId =
            if (value.kind == ManaSourcePaymentKind.Improvise) KeywordAbilityIds.IMPROVISE else KeywordAbilityIds.CONVOKE_PAYMENT
        owner.bridge
            .promptBridge(runtimeSeat)
            .journal
            .record(
                PromptSideEffect.ConvokePayments(
                    sourceForgeCardId = value.sourceForgeCardId,
                    payments =
                        value.payments.map {
                            PromptSideEffect.ConvokePayment(
                                paymentForgeCardId = it.paymentForgeCardId,
                                color = it.paymentColor.number,
                                substitutionGrpId = substitutionGrpId,
                                paymentAbilityGrpId = paymentAbilityGrpId,
                            )
                        },
                ),
            )
    }
}
