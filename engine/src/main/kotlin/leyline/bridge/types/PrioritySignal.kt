package leyline.bridge.types

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Shared signal between [leyline.bridge.handoff.GameActionBridge], [leyline.bridge.handoff.InteractivePromptBridge], and an
 * external observer (e.g. [GameBridge.awaitPriorityWithTimeout]).
 *
 * Bridges call [signal] when they post a pending item (action or prompt).
 * The observer calls [awaitSignal] instead of polling with Thread.sleep.
 *
 * Each observer remembers its own generation. A published signal wakes every
 * observer and remains visible when another observer has already consumed it.
 */
class PrioritySignal {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var generation = 0L
    private val observedGeneration = ThreadLocal.withInitial { 0L }

    /**
     * Set after a prompt resolves so the next priority check skips smart-phase-skip
     * and lets the player see the updated board. Consumed by [consumePromptResolved].
     */
    @Volatile
    private var promptJustResolved: Boolean = false

    /** Marked after a prompt resolves so the next priority check skips smart-phase-skip. */
    fun markPromptResolved() {
        promptJustResolved = true
    }

    /** Single-consumer check-and-clear for the priority loop. */
    fun consumePromptResolved(): Boolean {
        if (!promptJustResolved) return false
        promptJustResolved = false
        return true
    }

    /** Notify every waiter to re-check its exit conditions. */
    fun signal() {
        lock.withLock {
            generation++
            changed.signalAll()
        }
    }

    /**
     * Wait for a signal or timeout. Returns true if signaled, false on timeout.
     * Coalesces repeated signals independently for each observing thread.
     */
    fun awaitSignal(timeoutMs: Long): Boolean =
        lock.withLock {
            val observed = observedGeneration.get()
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (generation == observed) {
                if (remaining <= 0) return false
                remaining = changed.awaitNanos(remaining)
            }
            observedGeneration.set(generation)
            true
        }
}
