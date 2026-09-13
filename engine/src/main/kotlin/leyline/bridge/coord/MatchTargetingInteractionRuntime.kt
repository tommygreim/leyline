package leyline.bridge.coord

import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedTargetingInteraction
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.handoff.TargetingCommandReceipt
import leyline.bridge.handoff.TargetingInteractionKind
import leyline.bridge.handoff.TargetingInteractionRuntime
import leyline.bridge.handoff.TargetingInteractionTimeoutException
import leyline.bridge.handoff.TargetingWindowValue
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.bundle.TargetingWindowMaterializer
import leyline.game.mapping.FrameIdResolver
import leyline.game.snapshot.BoundCard
import leyline.game.state.ProjectionState
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Exact targeting-window lifecycle beneath [MatchCutCoordinator]. */
internal class MatchTargetingInteractionRuntime(
    private val owner: MatchCutCoordinator,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : TargetingInteractionRuntime,
    PromptLifecycle {
    internal var beforeInstall: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterCommandClaim: (() -> Unit)? = null
    internal var afterCompletedDeliveryRelease: (() -> Unit)? = null
    private val capture = TargetingWindowCapture(owner, runtimeSeat)

    private val nextDeliveryToken = AtomicLong()
    private var window: TargetingWindow? = null

    override fun awaitTargeting(
        request: PromptRequest,
        targetingAbility: SpellAbility?,
        abilityIdentity: ResolvedAbilityIdentity?,
        timeoutMs: Long?,
    ): List<Int> {
        check(request.route is ResolvedPromptRoute.Targeting)
        val value = capture.capture(request, targetingAbility, abilityIdentity)
        return awaitCaptured(
            value,
            targetingAbility,
            capture.captureTransientSourceCard(value, targetingAbility),
            timeoutMs,
            TargetingInteractionKind.Targeting,
        )
    }

    /** Shared SelectTargets lifecycle for residual card choices. */
    internal fun awaitCompatibility(
        request: PromptRequest,
        candidateHandles: List<forge.game.card.Card>,
        timeoutMs: Long?,
    ): List<Int> {
        check(request.route is ResolvedPromptRoute.CompatibilityCostSelection)
        require(candidateHandles.size == request.options.size) { "Compatibility candidates must match prompt options" }
        require(request.candidateRefs.all { ref -> candidateHandles.getOrNull(ref.index)?.id == ref.entityId }) {
            "Compatibility candidate handles no longer match the frozen prompt"
        }
        val value = capture.capture(request, targetingAbility = null, abilityIdentity = null)
        return awaitCaptured(
            value,
            targetingAbility = null,
            transientSourceCard = capture.captureTransientSourceCard(value, targetingAbility = null),
            timeoutMs,
            TargetingInteractionKind.CompatibilityCostSelection,
        )
    }

    private fun awaitCaptured(
        value: TargetingWindowValue,
        targetingAbility: SpellAbility?,
        transientSourceCard: BoundCard?,
        timeoutMs: Long?,
        kind: TargetingInteractionKind,
    ): List<Int> {
        val pending = publishInitial(value, targetingAbility, transientSourceCard, timeoutMs, kind)
        return awaitCommands(pending)
    }

    override fun current(): PublishedTargetingInteraction? = synchronized(owner.feedLock) { window?.published }

    /** Read-only Forge context for the in-process decision policy. */
    internal fun aiContext(): SpellAbility? = synchronized(owner.feedLock) { window?.targetingAbility }

    fun submitToggle(
        interactionId: String,
        gameStateId: Int,
        targetIndex: Int,
        toggles: List<TargetToggleValue>,
    ): TargetingCommandReceipt? = submit(TargetingCommand.Toggle(interactionId, gameStateId, targetIndex, toggles.toList()))

    fun submitTargets(
        interactionId: String?,
        gameStateId: Int,
    ): TargetingCommandReceipt? = interactionId?.let { submit(TargetingCommand.Submit(it, gameStateId)) }

    fun cancel(
        interactionId: String,
        gameStateId: Int,
    ): TargetingCommandReceipt? = submit(TargetingCommand.Cancel(interactionId, gameStateId))

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

    override fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            val pending = window ?: return
            clearChosenCostCorrelation(pending)
            pending.exchange.terminateLocked(cause, TargetingCommand.Terminal(cause))
            window = null
        }
    }

    override fun reset() {
        synchronized(owner.feedLock) {
            window?.let(::clearChosenCostCorrelation)
            window = null
        }
    }

    private fun publishInitial(
        value: TargetingWindowValue,
        targetingAbility: SpellAbility?,
        transientSourceCard: BoundCard?,
        timeoutMs: Long?,
        kind: TargetingInteractionKind = TargetingInteractionKind.Targeting,
    ): TargetingWindow {
        owner.beforePublicationLock?.invoke()
        val created =
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val prior = owner.bridge.projectionStateSnapshot()
                val planner = LogicalSequencePlanner(prior.sequence)
                check(window == null) { "A targeting interaction is already pending" }
                val feed = owner.feed(runtimeSeat)
                val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                val prepared =
                    try {
                        feed.builder.prepareTargetingWindow(
                            game,
                            planner,
                            value,
                            transientSourceCard,
                            owner.viewerRoutes(runtimeSeat),
                        )
                    } catch (ex: Exception) {
                        owner.fail(ex)
                    }
                publishPrepared(prior, planner, prepared)
                val projection = prepared.transition.nextState
                val published =
                    PublishedTargetingInteraction(
                        UUID.randomUUID().toString(),
                        checkNotNull(prepared.gameStateId),
                        value.targetIndex,
                        kind,
                    )
                val created =
                    TargetingWindow(
                        interactionId = published.interactionId,
                        value = value,
                        targetingAbility = targetingAbility,
                        entitiesByOptionIndex = capture.resolveEntities(value),
                        stackAbilitiesByOptionIndex = capture.resolveStackAbilities(value),
                        instanceIdByOptionIndex = capture.resolveInstanceIds(value, projection),
                        sourceInstanceId =
                            value.forgeAbilityId
                                .takeIf { (value.isTriggeredAbility || value.isActivatedAbility) && it != 0 }
                                ?.let(FrameIdResolver::triggerStackAbilityForgeId)
                                ?.let(projection.identities.forgeIdToInstanceId::get)
                                ?: value.sourceForgeCardId?.let(projection.identities.forgeIdToInstanceId::get),
                        exchange =
                            InteractiveCommandExchange(
                                deadlineNanos = timeoutMs?.let { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it) },
                                nextDeliveryToken = nextDeliveryToken::incrementAndGet,
                                replyOf = TargetingCommand::reply,
                            ),
                        published = published,
                    )
                window = created
                created
            }
        owner.bridge.prioritySignal.signal()
        return created
    }

    private fun awaitCommands(pending: TargetingWindow): List<Int> {
        while (true) {
            val command = poll(pending)
            when (command) {
                is TargetingCommand.Toggle -> {
                    applyToggles(pending, command)
                    publishRePrompt(pending, command)
                }
                is TargetingCommand.Submit -> {
                    publishSubmit(pending, command)
                    return if (pending.selectedOptionIndices.isEmpty() && pending.value.finishOptionIndex != null) {
                        listOf(checkNotNull(pending.value.finishOptionIndex))
                    } else {
                        pending.selectedOptionIndices.toList()
                    }
                }
                is TargetingCommand.Cancel -> {
                    completeWithoutPublication(pending, command)
                    return emptyList()
                }
                is TargetingCommand.Terminal -> throw command.cause
            }
        }
    }

    private fun applyToggles(
        pending: TargetingWindow,
        command: TargetingCommand.Toggle,
    ) {
        if (command.targetIndex != pending.value.targetIndex) return
        val optionByInstanceId = pending.instanceIdByOptionIndex.entries.associate { (option, iid) -> iid to option }
        command.toggles.forEach { toggle ->
            val option = optionByInstanceId[toggle.instanceId] ?: return@forEach
            if (toggle.selected) {
                if (option !in pending.selectedOptionIndices) pending.selectedOptionIndices += option
            } else {
                pending.selectedOptionIndices -= option
            }
        }
    }

    private fun publishRePrompt(
        pending: TargetingWindow,
        command: TargetingCommand.Toggle,
    ) {
        val selected = pending.selectedOptionIndices.toSet()
        val legal =
            capture.legalOptions(
                pending.value,
                pending.targetingAbility,
                pending.entitiesByOptionIndex,
                pending.stackAbilitiesByOptionIndex,
                selected,
            )
        val prepared =
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val prior = owner.bridge.projectionStateSnapshot()
                val planner = LogicalSequencePlanner(prior.sequence)
                val current =
                    matching(pending.interactionId, command.gameStateId, requireIdle = false)
                        ?: owner.fail(IllegalStateException("Targeting window changed during re-prompt"))
                val feed = owner.feed(runtimeSeat)
                val value =
                    try {
                        feed.builder.prepareTargetingRePrompt(
                            planner,
                            prior,
                            pending.value,
                            selected,
                            legal,
                        )
                    } catch (ex: Exception) {
                        owner.fail(ex)
                    }
                publishPrepared(feed, prior, planner, value)
                current.published =
                    current.published.copy(gameStateId = checkNotNull(value.bundle.actionGameStateId))
                beginDelivery(current, command, completed = false)
                value
            }
        check(prepared.bundle.messages.isNotEmpty())
        awaitDelivery(pending, completed = false)
    }

    private fun publishSubmit(
        pending: TargetingWindow,
        command: TargetingCommand,
    ) {
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val prior = owner.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            matching(pending.interactionId, commandGameStateId(command), requireIdle = false)
                ?: owner.fail(IllegalStateException("Targeting window changed during submit"))
            val feed = owner.feed(runtimeSeat)
            val prepared =
                try {
                    feed.builder.prepareTargetingSubmit(
                        planner,
                        prior,
                        pending.sourceInstanceId,
                        runtimeSeat,
                    )
                } catch (ex: Exception) {
                    owner.fail(ex)
                }
            publishPrepared(feed, prior, planner, prepared)
            beginDelivery(pending, command, completed = true)
        }
        awaitDelivery(pending, completed = true)
    }

    private fun completeWithoutPublication(
        pending: TargetingWindow,
        command: TargetingCommand,
    ) {
        synchronized(owner.feedLock) {
            matching(pending.interactionId, commandGameStateId(command), requireIdle = false)
                ?: owner.fail(IllegalStateException("Targeting window changed during completion"))
            clearChosenCostCorrelation(pending)
            command.reply.complete(
                TargetingCommandReceipt(
                    pending.interactionId,
                    deliveryToken = null,
                    completed = true,
                    engineWillResume = true,
                ),
            )
            window = null
        }
    }

    private fun beginDelivery(
        pending: TargetingWindow,
        command: TargetingCommand,
        completed: Boolean,
    ) {
        val delivery = pending.exchange.beginDeliveryLocked()
        command.reply.complete(
            TargetingCommandReceipt(
                pending.interactionId,
                delivery.token,
                completed,
                engineWillResume = completed,
            ),
        )
    }

    private fun awaitDelivery(
        pending: TargetingWindow,
        completed: Boolean,
    ) {
        val delivery = checkNotNull(pending.exchange.delivery)
        try {
            delivery.awaitAcknowledgement()
        } finally {
            synchronized(owner.feedLock) {
                pending.exchange.clearDeliveryLocked()
                if (completed && window === pending) window = null
                delivery.released.complete(Unit)
            }
            if (completed) afterCompletedDeliveryRelease?.invoke()
        }
    }

    private fun poll(pending: TargetingWindow): TargetingCommand = pending.exchange.next { claimTimedOutWindowOrCommand(pending) }

    private fun claimTimedOutWindowOrCommand(pending: TargetingWindow): TargetingCommand {
        beforeTimeoutClaim?.invoke()
        return synchronized(owner.feedLock) {
            if (window === pending && pending.exchange.inFlight != null) {
                return@synchronized checkNotNull(pending.exchange.pollQueuedLocked())
            }
            if (window === pending) {
                clearChosenCostCorrelation(pending)
                window = null
            }
            throw TargetingInteractionTimeoutException()
        }
    }

    private fun clearChosenCostCorrelation(pending: TargetingWindow) {
        pending.value.sourceForgeCardId?.let { cardId ->
            owner.bridge.setSelectedChosenCostPromptId(cardId, null)
        }
    }

    private fun submit(command: TargetingCommand): TargetingCommandReceipt? {
        val pending =
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val current = matching(commandInteractionId(command), commandGameStateId(command)) ?: return null
                current.exchange.admitLocked(command)
                afterCommandClaim?.invoke()
                current
            }
        return pending.exchange.awaitReply(command)
    }

    private fun matching(
        interactionId: String,
        gameStateId: Int,
        requireIdle: Boolean = true,
    ): TargetingWindow? {
        val pending = window ?: return null
        if (pending.interactionId != interactionId || pending.published.gameStateId != gameStateId) return null
        if (requireIdle && pending.exchange.inFlight != null) return null
        return pending
    }

    private fun publishPrepared(
        prior: ProjectionState,
        planner: LogicalSequencePlanner,
        prepared: BundleBuilder.PreparedViewerCut<TargetingWindowMaterializer.Prepared>,
    ) = owner.cutInstaller.install(
        PreparedCut.prepareForViewers(
            prior = prior,
            planner = planner,
            outputs = prepared.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
            projection = prepared.transition,
            closesPlaybackFrame = prepared.closesPlaybackFrame,
            playbackOwnerSeatId = runtimeSeat,
        ),
        CutInstallHooks(beforeInstall = beforeInstall),
    ) { ex -> owner.fail(ex) }

    private fun publishPrepared(
        feed: MatchCutCoordinator.ViewerFeed,
        prior: ProjectionState,
        planner: LogicalSequencePlanner,
        prepared: TargetingWindowMaterializer.Prepared,
    ) = owner.cutInstaller.install(
        feed,
        PreparedCut.prepare(prior, planner, prepared.bundle.messages, prepared.transition, prepared.closesPlaybackFrame),
        CutInstallHooks(beforeInstall = beforeInstall),
    ) { ex -> owner.fail(ex) }

    private fun commandInteractionId(command: TargetingCommand): String =
        when (command) {
            is TargetingCommand.Toggle -> command.interactionId
            is TargetingCommand.Submit -> command.interactionId
            is TargetingCommand.Cancel -> command.interactionId
            is TargetingCommand.Terminal -> "terminal"
        }

    private fun commandGameStateId(command: TargetingCommand): Int =
        when (command) {
            is TargetingCommand.Toggle -> command.gameStateId
            is TargetingCommand.Submit -> command.gameStateId
            is TargetingCommand.Cancel -> command.gameStateId
            is TargetingCommand.Terminal -> 0
        }
}
