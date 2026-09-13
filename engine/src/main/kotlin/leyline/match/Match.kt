package leyline.match

import leyline.domain.deck.DeckSource
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/** Lifecycle states for a match. */
enum class MatchState { WAITING, RUNNING, FINISHED }

/**
 * Owns the full lifecycle of a single game match.
 * Tracks state (WAITING → RUNNING → FINISHED) and provides deterministic teardown via [close].
 */
class Match(
    val matchId: String,
    val bridge: GameBridge,
) {
    private val log = LoggerFactory.getLogger(Match::class.java)
    private val stateRef = AtomicReference(MatchState.WAITING)

    /** Current lifecycle state. */
    val state: MatchState get() = stateRef.get()

    /** Optional callback fired on every state transition. Volatile for JMM visibility. */
    @Volatile var onStateChanged: ((MatchState) -> Unit)? = null

    fun start(
        seed: Long? = null,
        deckList: String? = null,
        deckList1: String? = null,
        deckList2: String? = null,
        variant: String? = null,
    ) {
        bridge.start(seed, deckList, deckList1, deckList2, variant)
        markStarted()
    }

    fun start(
        seed: Long? = null,
        deck1: DeckSource,
        deck2: DeckSource,
        variant: String? = null,
    ) {
        bridge.start(seed, deck1, deck2, variant)
        markStarted()
    }

    fun startHumanVsHuman(
        seed: Long? = null,
        deck1: DeckSource,
        deck2: DeckSource,
        variant: String? = null,
    ) {
        bridge.startHumanVsHuman(seed, deck1, deck2, variant)
        markStarted()
    }

    @Synchronized
    fun startAiVsAi(
        seed: Long? = null,
        deckList: String? = null,
        deckList1: String? = null,
        deckList2: String? = null,
        variant: String? = null,
        startGameHook: Runnable? = null,
    ) {
        if (stateRef.get() != MatchState.WAITING) return
        bridge.startAiVsAi(seed, deckList, deckList1, deckList2, variant, startGameHook)
        markStarted()
    }

    @Synchronized
    fun startAiVsAi(
        seed: Long? = null,
        deck1: DeckSource,
        deck2: DeckSource,
        variant: String? = null,
        startGameHook: Runnable? = null,
    ) {
        if (stateRef.get() != MatchState.WAITING) return
        bridge.startAiVsAi(seed, deck1, deck2, variant, startGameHook)
        markStarted()
    }

    private fun markStarted() {
        if (stateRef.compareAndSet(MatchState.WAITING, MatchState.RUNNING)) {
            log
                .atInfo()
                .addKeyValue("event", "match.started")
                .addKeyValue("match_id", matchId)
                .log("Match started")
            onStateChanged?.invoke(MatchState.RUNNING)
        }
    }

    /**
     * Idempotent teardown: transitions to FINISHED, deterministically tears down
     * heavyweight resources (EventBus, game loop), then clears per-seat bridge state.
     * Safe to call from any thread, multiple times.
     */
    fun close() {
        val prev = stateRef.getAndSet(MatchState.FINISHED)
        if (prev == MatchState.FINISHED) return // already closed
        bridge.shutdown()
        onStateChanged?.invoke(MatchState.FINISHED)
    }
}
