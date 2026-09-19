package leyline.bridge.coord

import forge.game.Game
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.card.CardCollectionView
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.BlockingInteractionRuntime
import leyline.bridge.handoff.DamageAssignmentCommand
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.opponent
import leyline.game.PendingPromptCut
import leyline.game.bundle.BlockingInteractionMaterializer
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.LogicalSequencePlanner
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class PublishedBlockingInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val interaction: BlockingInteraction,
)

/** Blocking prompt handles and value answers beneath [MatchCutCoordinator]. */
internal class MatchBlockingInteractionRuntime(
    private val owner: MatchCutCoordinator,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : BlockingInteractionRuntime,
    PromptTerminalCutOwner {
    private sealed interface Answer {
        data class Optional(
            val accepted: Boolean,
        ) : Answer

        data class Numeric(
            val value: Int,
        ) : Answer

        data class Damage(
            val assignments: List<DamageAssignmentValue>,
        ) : Answer
    }

    private data class DamageAssignmentValue(
        val attackerId: ForgeCardId,
        val assignments: Map<ForgeCardId?, Int>,
    )

    private data class PublishedDamageAssigner(
        val attackerInstanceId: Int,
        val attackerId: ForgeCardId,
        val totalDamage: Int,
        val slots: List<PublishedDamageSlot>,
    )

    private data class PublishedDamageSlot(
        val instanceId: Int,
        val minDamage: Int,
        val maxDamage: Int,
        val targetId: ForgeCardId?,
    )

    private data class Window(
        val published: PublishedBlockingInteraction,
        val cut: PendingPromptCut<BlockingInteraction>,
        val future: CompletableFuture<Answer>,
        val damageCards: Map<ForgeCardId, Card> = emptyMap(),
        val damageAssigners: List<PublishedDamageAssigner> = emptyList(),
    )

    private var window: Window? = null
    private val damageCache = mutableMapOf<ForgeCardId, DamageAssignmentValue>()

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterMaterialization: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null

    override fun awaitOptional(
        interaction: BlockingInteraction.Optional,
        timeoutMs: Long?,
        defaultOnTimeout: Boolean,
    ): Boolean =
        awaitOptional(
            interaction = interaction,
            sourceCard = null,
            timeoutMs = timeoutMs,
            defaultOnTimeout = defaultOnTimeout,
        )

    override fun awaitOptional(
        interaction: BlockingInteraction.Optional,
        sourceCard: Card?,
        timeoutMs: Long?,
        defaultOnTimeout: Boolean,
    ): Boolean {
        val pending =
            publish(interaction, sourceCard = sourceCard) { feed, _, planner ->
                feed.builder.generalOptionalInteractionBundle(planner, interaction)
            }
        return try {
            (await(pending, timeoutMs ?: 45_000L) as Answer.Optional).accepted
        } catch (_: TimeoutException) {
            defaultOnTimeout
        } finally {
            clear(pending)
        }
    }

    override fun awaitNumeric(
        interaction: BlockingInteraction.Numeric,
        timeoutMs: Long?,
    ): Int {
        val pending = publish(interaction) { feed, _, planner -> feed.builder.numericInteractionBundle(planner, interaction) }
        return try {
            (await(pending, timeoutMs ?: 45_000L) as Answer.Numeric).value
        } catch (_: TimeoutException) {
            interaction.defaultValue
        } finally {
            clear(pending)
        }
    }

    override fun awaitDamage(
        interaction: BlockingInteraction.Damage,
        attacker: Card,
        blockers: CardCollectionView,
        defender: GameEntity?,
        timeoutMs: Long?,
        fallback: () -> MutableMap<Card?, Int>?,
    ): MutableMap<Card?, Int>? {
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            damageCache.clear()
        }
        val cards = (listOf(attacker) + blockers).associateBy { ForgeCardId(it.id) }
        val toughness = blockers.associate { ForgeCardId(it.id) to maxOf(0, it.netToughness - it.damage) }
        val pending =
            publish(interaction, cards) { feed, _, planner ->
                feed.builder.damageInteractionBundle(planner, interaction, toughness)
            }
        return try {
            val answer = await(pending, timeoutMs ?: 45_000L) as Answer.Damage
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                val first = answer.assignments.firstOrNull { it.attackerId == interaction.attackerId } ?: return mutableMapOf()
                answer.assignments.filter { it.attackerId != interaction.attackerId }.forEach { assignment ->
                    damageCache[assignment.attackerId] = assignment
                }
                resolveDamageMap(pending.damageCards, first.assignments)
            }
        } catch (_: TimeoutException) {
            fallback()
        } finally {
            clear(pending)
        }
    }

    override fun takeCachedDamage(
        attacker: Card,
        blockers: CardCollectionView,
    ): MutableMap<Card?, Int>? =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val assignment = damageCache.remove(ForgeCardId(attacker.id)) ?: return@synchronized null
            val cards = (listOf(attacker) + blockers).associateBy { ForgeCardId(it.id) }
            resolveDamageMap(cards, assignment.assignments)
        }

    override fun current(): PublishedBlockingInteraction? =
        synchronized(owner.feedLock) { window?.takeUnless { it.future.isDone }?.published }

    override fun terminalCutCandidateLocked(): PromptTerminalCutCandidate? =
        window
            ?.takeUnless { it.future.isDone }
            ?.cut
            ?.let { PromptTerminalCutCandidate(PromptTerminalPriority.Blocking, it) }

    fun submitOptional(
        interactionId: String,
        gameStateId: Int,
        accepted: Boolean,
    ): Boolean =
        synchronized(owner.feedLock) {
            val pending = matching(interactionId, gameStateId) ?: return false
            val optional = pending.published.interaction as? BlockingInteraction.Optional ?: return false
            optional.commanderReturn?.let { context ->
                val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                val prior = owner.bridge.projectionStateSnapshot()
                val planner = LogicalSequencePlanner(prior.sequence)
                val prepared =
                    try {
                        owner.feed(runtimeSeat).builder.commanderPromptCleanup(
                            game,
                            planner,
                            context,
                            owner.beforeCommanderCleanupMaterialization,
                        )
                    } catch (ex: Exception) {
                        owner.fail(ex)
                    }
                owner.cutInstaller.install(
                    owner.feed(runtimeSeat),
                    PreparedCut.prepare(
                        prior,
                        planner,
                        prepared.bundle.messages,
                        prepared.transition,
                        prepared.closesPlaybackFrame,
                    ),
                    CutInstallHooks(beforeInstall = owner.beforeCommanderCleanupInstall),
                ) { ex -> owner.fail(ex) }
            }
            pending.future.complete(Answer.Optional(accepted))
        }

    fun submitNumeric(
        interactionId: String,
        gameStateId: Int,
        value: Int,
    ): Boolean = complete(interactionId, gameStateId, Answer.Numeric(value))

    /** Admit the numeric child response used by Arena's dedicated Replicate workflow. */
    fun submitReplicate(
        gameStateId: Int,
        ctoId: Int,
        value: Int,
    ): Boolean =
        synchronized(owner.feedLock) {
            val pending = window ?: return@synchronized false
            val numeric = pending.published.interaction as? BlockingInteraction.Numeric ?: return@synchronized false
            if (pending.future.isDone ||
                pending.published.gameStateId != gameStateId ||
                numeric.presentation != BlockingInteraction.NumericPresentation.Replicate ||
                ctoId != REPLICATE_CTO_ID ||
                value !in numeric.min..numeric.max
            ) {
                return@synchronized false
            }
            pending.future.complete(Answer.Numeric(value))
        }

    fun submitDamageCommand(
        interactionId: String,
        gameStateId: Int,
        commands: List<DamageAssignmentCommand>,
    ): Boolean =
        synchronized(owner.feedLock) {
            val pending = matching(interactionId, gameStateId) ?: return false
            if (pending.damageAssigners.isEmpty()) return false
            if (commands.map { it.attackerInstanceId } != pending.damageAssigners.map { it.attackerInstanceId }) return false
            val assignments =
                commands.zip(pending.damageAssigners).map { (command, published) ->
                    if (command.totalDamage != 0 && command.totalDamage != published.totalDamage) return false
                    if (command.assignments.map { it.targetInstanceId } != published.slots.map { it.instanceId }) return false
                    val amounts = command.assignments.map { it.assignedDamage }
                    if (amounts.any { it < 0 }) return false
                    if (amounts.sumOf(Int::toLong) != published.totalDamage.toLong()) return false
                    if (published.slots.zip(amounts).any { (slot, amount) -> slot.maxDamage > 0 && amount > slot.maxDamage }) {
                        return false
                    }
                    if (published.slots.indices.any { index ->
                            amounts.drop(index + 1).any { it > 0 } && amounts[index] < published.slots[index].minDamage
                        }
                    ) {
                        return false
                    }
                    DamageAssignmentValue(
                        published.attackerId,
                        published.slots.zip(amounts).associateTo(linkedMapOf()) { (slot, amount) -> slot.targetId to amount },
                    )
                }
            val feed = owner.feed(runtimeSeat)
            val prior = owner.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            val confirmation = feed.builder.damageAssignmentConfirmation(planner).messages
            owner.cutInstaller.install(
                feed,
                PreparedCut.prepare(prior, planner, confirmation, projection = null, closesPlaybackFrame = false),
                onFailure = owner::fail,
            )
            pending.future.complete(Answer.Damage(assignments))
        }

    override fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) {
            window?.future?.completeExceptionally(cause)
            window = null
            damageCache.clear()
        }
    }

    override fun reset() {
        synchronized(owner.feedLock) {
            window = null
            damageCache.clear()
        }
    }

    @Suppress("LongMethod", "CanBeNonNullable") // One lock-scoped publication owns blocking-window metadata and installation.
    private fun publish(
        interaction: BlockingInteraction,
        damageCards: Map<ForgeCardId, Card> = emptyMap(),
        sourceCard: Card? = null,
        build: (MatchCutCoordinator.ViewerFeed, Game, LogicalSequencePlanner) -> BlockingInteractionMaterializer.Prepared,
    ): Window {
        owner.beforePublicationLock?.invoke()
        val pending =
            synchronized(owner.feedLock) {
                owner.ensureOpen()
                check(window == null) { "A blocking interaction is already pending" }
                val feed = owner.feed(runtimeSeat)
                val game = owner.bridge.getGame() ?: owner.fail(IllegalStateException("Game unavailable"))
                val prior = owner.bridge.projectionStateSnapshot()
                val planner = LogicalSequencePlanner(prior.sequence)
                var viewerPrepared: BundleBuilder.PreparedViewerCut<BlockingInteractionMaterializer.Prepared>? = null
                val prepared =
                    try {
                        viewerPrepared =
                            (interaction as? BlockingInteraction.Optional)
                                ?.takeIf {
                                    it.commanderReturn != null ||
                                        it.forceSnapshotBeforePrompt ||
                                        it.etbPayLifeReplacement
                                }?.let { optional ->
                                    feed.builder.optionalInteractionBundle(
                                        game,
                                        planner,
                                        optional,
                                        owner.viewerRoutes(runtimeSeat),
                                        sourceCard,
                                    )
                                }
                        (viewerPrepared?.player ?: build(feed, game, planner)).also { afterMaterialization?.invoke() }
                    } catch (ex: Exception) {
                        owner.fail(ex)
                    }
                val published =
                    PublishedBlockingInteraction(
                        UUID.randomUUID().toString(),
                        checkNotNull(prepared.bundle.actionGameStateId),
                        interaction,
                    )
                val exact =
                    PendingPromptCut(
                        published.interactionId,
                        published.gameStateId,
                        interaction,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                val projection = prepared.transition?.nextState ?: prior
                val cardIdsByInstanceId = projection.identities.instanceIdToForgeId.mapKeys { it.key.value }
                val damageAssigners =
                    prepared.bundle.messages
                        .asSequence()
                        .filter { it.hasAssignDamageReq() }
                        .flatMap { it.assignDamageReq.damageAssignersList.asSequence() }
                        .map { assigner ->
                            PublishedDamageAssigner(
                                assigner.instanceId,
                                checkNotNull(cardIdsByInstanceId[assigner.instanceId]) { "Damage attacker was not published" },
                                assigner.totalDamage,
                                assigner.assignmentsList.map { slot ->
                                    PublishedDamageSlot(
                                        slot.instanceId,
                                        slot.minDamage,
                                        slot.maxDamage,
                                        if (slot.instanceId == runtimeSeat.opponent.value) {
                                            null
                                        } else {
                                            checkNotNull(cardIdsByInstanceId[slot.instanceId]) { "Damage recipient was not published" }
                                        },
                                    )
                                },
                            )
                        }.toList()
                val created =
                    Window(
                        published,
                        exact,
                        CompletableFuture(),
                        damageCards,
                        damageAssigners,
                    )
                val cut =
                    if (viewerPrepared == null) {
                        PreparedCut.prepare(
                            prior,
                            planner,
                            prepared.bundle.messages,
                            prepared.transition,
                            prepared.closesPlaybackFrame,
                        )
                    } else {
                        PreparedCut.prepareForViewers(
                            prior,
                            planner,
                            viewerPrepared.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                            viewerPrepared.transition,
                            viewerPrepared.closesPlaybackFrame,
                            playbackOwnerSeatId = runtimeSeat.takeIf { viewerPrepared.closesPlaybackFrame },
                        )
                    }
                if (viewerPrepared == null) {
                    owner.cutInstaller.install(
                        feed,
                        cut,
                        CutInstallHooks(beforeInstall = beforeInstall),
                    ) { ex -> owner.fail(ex, exact) }
                } else {
                    owner.cutInstaller.install(
                        cut,
                        CutInstallHooks(beforeInstall = beforeInstall),
                    ) { ex -> owner.fail(ex, exact) }
                }
                window = created
                created
            }
        owner.bridge.prioritySignal.signal()
        return pending
    }

    private fun await(
        pending: Window,
        timeoutMs: Long,
    ): Answer =
        try {
            pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            beforeTimeoutClaim?.invoke()
            synchronized(owner.feedLock) {
                if (pending.future.completeExceptionally(timeout)) {
                    if (window === pending) window = null
                    damageCache.clear()
                    throw timeout
                }
                owner.ensureOpen()
                try {
                    checkNotNull(pending.future.getNow(null)) { "Completed interaction has no answer" }
                } catch (ex: java.util.concurrent.CompletionException) {
                    throw ex.cause ?: ex
                }
            }
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        }

    private fun clear(pending: Window) {
        synchronized(owner.feedLock) {
            if (window === pending) window = null
        }
    }

    private fun complete(
        interactionId: String,
        gameStateId: Int,
        answer: Answer,
    ): Boolean =
        synchronized(owner.feedLock) {
            matching(interactionId, gameStateId)?.future?.complete(answer) ?: false
        }

    private fun matching(
        interactionId: String,
        gameStateId: Int,
    ): Window? {
        val pending = window ?: return null
        if (pending.future.isDone) return null
        if (pending.published.interactionId != interactionId || pending.published.gameStateId != gameStateId) {
            return null
        }
        return pending
    }

    private fun resolveDamageMap(
        cards: Map<ForgeCardId, Card>,
        assignments: Map<ForgeCardId?, Int>,
    ): MutableMap<Card?, Int> = assignments.entries.associateTo(linkedMapOf()) { (id, amount) -> id?.let(cards::get) to amount }

    private companion object {
        const val REPLICATE_CTO_ID = 1
    }
}
