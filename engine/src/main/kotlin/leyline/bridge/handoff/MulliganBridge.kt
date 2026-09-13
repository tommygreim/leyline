package leyline.bridge.handoff

import forge.game.card.Card
import forge.game.card.CardCollectionView
import leyline.DevCheck
import leyline.bridge.types.MulliganPhase
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * CompletableFuture-based bridge for mulligan decisions.
 *
 * The engine's [forge.game.mulligan.MulliganService] runs on the game thread and
 * calls [forge.game.player.PlayerController.mulliganKeepHand] /
 * [forge.game.player.PlayerController.tuckCardsViaMulligan], both of which block
 * until the client submits a decision via the Netty handler.
 *
 * For tests, [autoKeep] mode auto-submits keep immediately.
 */
class MulliganBridge(
    private val autoKeep: Boolean = false,
    private val timeoutMs: Long? = 60_000,
) {
    companion object {
        private val log = LoggerFactory.getLogger(MulliganBridge::class.java)
    }

    data class PendingPrompt(
        val phase: MulliganPhase,
        val playerId: Int,
        val mulliganCount: Int,
        val cardsToTuck: Int,
        val sequence: Int,
    )

    private sealed interface MulliganState {
        data object Idle : MulliganState

        data class WaitingKeep(
            val playerId: Int,
            val mulliganCount: Int,
            val sequence: Int,
            val future: CompletableFuture<Boolean>,
        ) : MulliganState

        data class WaitingTuck(
            val playerId: Int,
            val mulliganCount: Int,
            val cardsToTuck: Int,
            val sequence: Int,
            val future: CompletableFuture<List<Card>>,
        ) : MulliganState
    }

    @Volatile private var state: MulliganState = MulliganState.Idle
    private var promptSequenceValue: Int = 0

    /** Engine-thread publication hook, invoked after installing each exact prompt. */
    var onPrompt: ((PendingPrompt) -> Unit)? = null

    /** Monotonic counter — increments each time a keep/tuck prompt is posted. */
    val promptSequence: Int
        get() = synchronized(this) { promptSequenceValue }

    fun pendingPrompt(): PendingPrompt? =
        synchronized(this) {
            when (val current = state) {
                MulliganState.Idle -> null
                is MulliganState.WaitingKeep ->
                    PendingPrompt(
                        phase = MulliganPhase.WaitingKeep,
                        playerId = current.playerId,
                        mulliganCount = current.mulliganCount,
                        cardsToTuck = 0,
                        sequence = current.sequence,
                    )
                is MulliganState.WaitingTuck ->
                    PendingPrompt(
                        phase = MulliganPhase.WaitingTuck,
                        playerId = current.playerId,
                        mulliganCount = current.mulliganCount,
                        cardsToTuck = current.cardsToTuck,
                        sequence = current.sequence,
                    )
            }
        }

    fun pendingPromptAfter(sequence: Int): PendingPrompt? = pendingPrompt()?.takeIf { it.sequence > sequence }

    private fun nextPromptSequence(): Int {
        promptSequenceValue += 1
        return promptSequenceValue
    }

    /**
     * Called by [leyline.bridge.forge.PlayerController.mulliganKeepHand] on the game thread.
     * Blocks until the client calls [submitKeep] or [submitMull].
     *
     * @return true to keep, false to mulligan
     */
    fun awaitKeepDecision(
        playerId: Int,
        mulliganCount: Int,
    ): Boolean {
        if (autoKeep) {
            log.debug("MulliganBridge: auto-keep for player {}", playerId)
            return true
        }

        val future = CompletableFuture<Boolean>()
        synchronized(this) {
            state =
                MulliganState.WaitingKeep(
                    playerId = playerId,
                    mulliganCount = mulliganCount,
                    sequence = nextPromptSequence(),
                    future = future,
                )
        }
        log.info("MulliganBridge: awaiting keep/mull for player {} (mulls={})", playerId, mulliganCount)
        return try {
            onPrompt?.invoke(checkNotNull(pendingPrompt()))
            if (timeoutMs == null) {
                future.get()
            } else {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            }
        } catch (_: TimeoutException) {
            log.warn("MulliganBridge: timeout waiting for keep decision, auto-keeping")
            DevCheck.failOnAutoPass { "Mulligan keep decision timed out" }
            true
        } finally {
            synchronized(this) {
                if ((state as? MulliganState.WaitingKeep)?.future === future) {
                    state = MulliganState.Idle
                }
            }
        }
    }

    /**
     * Called by [leyline.bridge.forge.PlayerController.tuckCardsViaMulligan] on the game thread.
     * Blocks until the client calls [submitTuck].
     *
     * @return the cards to put on bottom of library
     */
    fun awaitTuckDecision(
        playerId: Int,
        count: Int,
        hand: CardCollectionView,
    ): List<Card> {
        if (autoKeep) {
            log.debug("MulliganBridge: auto-tuck {} for player {}", count, playerId)
            return hand.toList().take(count)
        }

        val future = CompletableFuture<List<Card>>()
        synchronized(this) {
            state =
                MulliganState.WaitingTuck(
                    playerId = playerId,
                    mulliganCount = count,
                    cardsToTuck = count,
                    sequence = nextPromptSequence(),
                    future = future,
                )
        }
        log.info("MulliganBridge: awaiting tuck {} cards for player {}", count, playerId)
        return try {
            onPrompt?.invoke(checkNotNull(pendingPrompt()))
            if (timeoutMs == null) {
                future.get()
            } else {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            }
        } catch (_: TimeoutException) {
            log.warn("MulliganBridge: timeout waiting for tuck, auto-tucking first {}", count)
            DevCheck.failOnAutoPass { "Mulligan tuck decision timed out" }
            hand.toList().take(count)
        } finally {
            synchronized(this) {
                if ((state as? MulliganState.WaitingTuck)?.future === future) {
                    state = MulliganState.Idle
                }
            }
        }
    }

    fun submitKeep(): Boolean {
        val future = synchronized(this) { (state as? MulliganState.WaitingKeep)?.future }
        return future?.complete(true) == true
    }

    fun submitMull(): Boolean {
        val future = synchronized(this) { (state as? MulliganState.WaitingKeep)?.future }
        return future?.complete(false) == true
    }

    fun submitTuck(cards: List<Card>) {
        val future = synchronized(this) { (state as? MulliganState.WaitingTuck)?.future }
        future?.complete(cards)
    }

    fun cancelPending() {
        val current =
            synchronized(this) {
                val pending = state
                state = MulliganState.Idle
                pending
            }
        when (current) {
            MulliganState.Idle -> Unit
            is MulliganState.WaitingKeep -> current.future.cancel(true)
            is MulliganState.WaitingTuck -> current.future.cancel(true)
        }
    }
}
