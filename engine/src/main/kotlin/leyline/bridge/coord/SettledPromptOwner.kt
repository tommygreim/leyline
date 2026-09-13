package leyline.bridge.coord

import forge.game.Game
import leyline.game.PendingPromptCut
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.bundle.SettledPromptCorrelation
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.FailureReason
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One match-scoped owner for every settled prompt slot. */
internal class SettledPromptOwner(
    private val owner: MatchCutCoordinator,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : PromptLifecycle,
    PromptTerminalCutOwner {
    private val log = LoggerFactory.getLogger(SettledPromptOwner::class.java)
    private val slots = mutableListOf<MountedSlot>()

    internal var beforeInstall: (() -> Unit)? = null
    internal var afterInstall: (() -> Unit)? = null
    internal var beforeTimeoutClaim: (() -> Unit)? = null
    internal var afterDeliveryCutLookup: (() -> Unit)? = null

    fun <W, R> mount(
        terminalPriority: PromptTerminalPriority,
        publicationFailure: (Throwable, W) -> Nothing,
        owns: (W, ClientToGREMessage) -> Boolean,
        admitLocked: (W, ClientToGREMessage) -> SlotAdmission<R>?,
        cancelCapable: Boolean = false,
        onTerminateLocked: (W?, Throwable) -> Unit = { _, _ -> },
        onResetLocked: (W?) -> Unit = {},
    ): Slot<W, R> where W : Window<R> =
        Slot(
            terminalPriority,
            publicationFailure,
            owns,
            admitLocked,
            cancelCapable,
            onTerminateLocked,
            onResetLocked,
        ).also(slots::add)

    private val retired = mutableMapOf<Int, RetiredCorrelation>()

    fun admit(message: ClientToGREMessage): SettledPromptAdmission =
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            if (message.type == ClientMessageType.CancelActionReq_097b) {
                admitControlLocked(message)
            } else {
                admitResponseLocked(message)
            }
        }

    override fun current(): Any? = synchronized(owner.feedLock) { slots.firstNotNullOfOrNull(MountedSlot::currentLocked) }

    override fun terminate(cause: Throwable) {
        synchronized(owner.feedLock) { slots.forEach { it.terminateLocked(cause) } }
    }

    override fun reset() {
        synchronized(owner.feedLock) {
            slots.forEach(MountedSlot::resetLocked)
            retired.clear()
        }
    }

    override fun terminalCutCandidateLocked(): PromptTerminalCutCandidate? {
        val candidate = slots.mapNotNull(MountedSlot::terminalCutCandidateLocked).minByOrNull { it.priority }
        afterDeliveryCutLookup?.invoke()
        return candidate
    }

    internal interface Window<R> {
        val interactionId: String
        val cut: PendingPromptCut<*>
        val future: CompletableFuture<R>
    }

    internal data class SlotAdmission<R>(
        val result: R,
        val beforeComplete: () -> Unit = {},
        val afterEngineResume: (() -> Unit)? = null,
    )

    internal data class Publication<W>(
        val window: W,
        val transition: ProjectionTransition,
        val closesPlaybackFrame: Boolean,
        val viewerOutputs: List<PreparedViewerOutput>,
        val correlation: SettledPromptCorrelation,
    ) {
        init {
            require(viewerOutputs.isNotEmpty()) { "A settled prompt publication requires viewer output" }
        }
    }

    internal inner class Slot<W, R>(
        private val terminalPriority: PromptTerminalPriority,
        private val publicationFailure: (Throwable, W) -> Nothing,
        private val owns: (W, ClientToGREMessage) -> Boolean,
        private val admitLocked: (W, ClientToGREMessage) -> SlotAdmission<R>?,
        override val cancelCapable: Boolean,
        private val onTerminateLocked: (W?, Throwable) -> Unit,
        private val onResetLocked: (W?) -> Unit,
    ) : MountedSlot where W : Window<R> {
        private var window: W? = null
        private var correlation: SettledPromptCorrelation? = null

        fun current(): W? = synchronized(owner.feedLock) { currentLocked() }

        fun ensureEmptyLocked(message: String) {
            check(window == null) { message }
        }

        fun completeLocked(
            pending: W,
            result: R,
        ): Boolean {
            if (window !== pending || pending.future.isDone) return false
            window = null
            return pending.future.complete(result)
        }

        fun publish(
            duplicateMessage: String,
            prepare: (String, MatchCutCoordinator.ViewerFeed, Game?, LogicalSequencePlanner) -> Publication<W>,
            ensureEmptyLocked: () -> Unit = {},
        ): W {
            owner.beforePublicationLock?.invoke()
            val created =
                synchronized(owner.feedLock) {
                    owner.ensureOpen()
                    ensureEmptyLocked(duplicateMessage)
                    ensureEmptyLocked()
                    val feed = owner.feed(runtimeSeat)
                    val prior = owner.bridge.projectionStateSnapshot()
                    val planner = LogicalSequencePlanner(prior.sequence)
                    val publication = prepare(UUID.randomUUID().toString(), feed, owner.bridge.getGame(), planner)
                    publishPrepared(prior, planner, publication)
                    window = publication.window
                    correlation = publication.correlation
                    publication.window
                }
            owner.bridge.prioritySignal.signal()
            return created
        }

        fun await(
            pending: W,
            timeoutMs: Long?,
            timeoutException: () -> Throwable,
            beforeTimeoutCompleteLocked: (() -> Unit)? = null,
        ): R =
            try {
                if (timeoutMs == null) pending.future.get() else pending.future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                beforeTimeoutClaim?.invoke()
                synchronized(owner.feedLock) {
                    if (window === pending && !pending.future.isDone) {
                        beforeTimeoutCompleteLocked?.invoke()
                        if (window === pending && !pending.future.isDone) {
                            window = null
                            pending.future.completeExceptionally(timeoutException())
                        }
                        retireLocked()
                    }
                }
                completedValue(pending)
            } catch (ex: ExecutionException) {
                throw ex.cause ?: ex
            }

        override fun currentLocked(): W? = window?.takeUnless { it.future.isDone }

        override fun correlationLocked(): SettledPromptCorrelation? = correlation?.takeIf { currentLocked() != null }

        override fun ownsLocked(message: ClientToGREMessage): Boolean = currentLocked()?.let { owns(it, message) } == true

        override fun admitLocked(message: ClientToGREMessage): SettledPromptAdmission {
            val pending = currentLocked() ?: return SettledPromptAdmission.Rejected(FailureReason.ReqRespMismatch)
            val accepted = admitLocked(pending, message) ?: return SettledPromptAdmission.Rejected(FailureReason.InvalidOptionSelection)
            accepted.beforeComplete()
            check(completeLocked(pending, accepted.result)) { "Settled prompt changed during admission" }
            val exact = checkNotNull(correlation)
            log
                .atDebug()
                .addKeyValue("event", "match.prompt_completed")
                .addKeyValue("match_id", owner.matchId)
                .addKeyValue("seat", runtimeSeat.value)
                .addKeyValue("response_type", message.type.name)
                .addKeyValue("game_state_id", exact.gameStateId)
                .log("Match prompt completed")
            retireLocked()
            if (message.type == ClientMessageType.CancelActionReq_097b) {
                owner.bridge.responseAcceptance.markPromptHandled(exact.requestMsgId)
            } else {
                owner.bridge.responseAcceptance.markResponseAccepted(message.respId)
            }
            return SettledPromptAdmission.Accepted(accepted.afterEngineResume)
        }

        override fun terminateLocked(cause: Throwable) {
            val pending = window
            onTerminateLocked(pending, cause)
            pending?.future?.completeExceptionally(cause)
            window = null
            correlation = null
        }

        override fun resetLocked() {
            onResetLocked(window)
            window = null
            correlation = null
        }

        override fun terminalCutCandidateLocked(): PromptTerminalCutCandidate? =
            currentLocked()?.let { PromptTerminalCutCandidate(terminalPriority, it.cut) }

        private fun publishPrepared(
            prior: ProjectionState,
            planner: LogicalSequencePlanner,
            publication: Publication<W>,
        ) {
            val cut =
                PreparedCut.prepareForViewers(
                    prior,
                    planner,
                    publication.viewerOutputs,
                    publication.transition,
                    publication.closesPlaybackFrame,
                    playbackOwnerSeatId = runtimeSeat.takeIf { publication.closesPlaybackFrame },
                )
            owner.cutInstaller.install(
                cut,
                CutInstallHooks(beforeInstall = beforeInstall, afterInstall = afterInstall),
            ) { ex -> publicationFailure(ex, publication.window) }
        }

        private fun retireLocked() {
            correlation?.let { retired[it.requestMsgId] = RetiredCorrelation(it.gameStateId, cancelCapable) }
            correlation = null
        }

        private fun completedValue(pending: W): R =
            try {
                pending.future.get()
            } catch (ex: ExecutionException) {
                throw ex.cause ?: ex
            }
    }

    private interface MountedSlot {
        val cancelCapable: Boolean

        fun currentLocked(): Any?

        fun correlationLocked(): SettledPromptCorrelation?

        fun ownsLocked(message: ClientToGREMessage): Boolean

        fun admitLocked(message: ClientToGREMessage): SettledPromptAdmission

        fun terminateLocked(cause: Throwable)

        fun resetLocked()

        fun terminalCutCandidateLocked(): PromptTerminalCutCandidate?
    }

    private fun admitResponseLocked(message: ClientToGREMessage): SettledPromptAdmission {
        val matches = slots.filter { it.correlationLocked()?.requestMsgId == message.respId }
        if (matches.isEmpty()) {
            return if (message.respId in retired) {
                SettledPromptAdmission.Rejected(FailureReason.ReqRespMismatch)
            } else {
                SettledPromptAdmission.NotOwned
            }
        }
        if (matches.size != 1) return SettledPromptAdmission.Rejected(FailureReason.ReqRespMismatch)
        val slot = matches.single()
        if (slot.correlationLocked()?.gameStateId != message.gameStateId || !slot.ownsLocked(message)) {
            return SettledPromptAdmission.Rejected(FailureReason.ReqRespMismatch)
        }
        return slot.admitLocked(message)
    }

    private fun admitControlLocked(message: ClientToGREMessage): SettledPromptAdmission {
        val claims =
            slots.filter {
                it.cancelCapable &&
                    it.correlationLocked()?.gameStateId == message.gameStateId &&
                    it.ownsLocked(message)
            }
        if (claims.size > 1) return SettledPromptAdmission.Rejected(FailureReason.ReqRespMismatch)
        if (claims.size == 1) return claims.single().admitLocked(message)
        if (retired.values.any { it.cancelCapable && it.gameStateId == message.gameStateId }) {
            return SettledPromptAdmission.Rejected(FailureReason.ReqRespMismatch)
        }
        return SettledPromptAdmission.NotOwned
    }

    private data class RetiredCorrelation(
        val gameStateId: Int,
        val cancelCapable: Boolean,
    )
}

internal sealed interface SettledPromptAdmission {
    data class Accepted(
        val afterEngineResume: (() -> Unit)? = null,
    ) : SettledPromptAdmission

    data class Rejected(
        val reason: FailureReason,
    ) : SettledPromptAdmission

    data object NotOwned : SettledPromptAdmission
}
