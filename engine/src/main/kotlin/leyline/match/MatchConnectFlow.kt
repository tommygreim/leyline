package leyline.match

import leyline.bridge.handoff.RuntimeHorizonMode
import leyline.config.EngineSettings
import leyline.domain.deck.DeckSource
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardRepository
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory

internal data class ConnectAttempt(
    val matchId: String,
    val seatId: Int,
    val familiar: Boolean,
)

@Suppress("LongParameterList")
internal class MatchConnectFlow(
    private val registry: MatchRegistry,
    private val engineSettings: EngineSettings,
    private val coordinator: MatchCoordinator?,
    private val cardRepository: CardRepository,
    private val puzzleHandler: PuzzleHandler,
    private val createMatchSession: (GameBridge) -> MatchSession,
    private val createFamiliarSession: () -> FamiliarSession,
    private val createSpectatorSession: (GameBridge) -> SpectatorSession,
    private val sendRoomState: () -> Unit,
    private val sendInitialBundle: () -> Unit,
    private val resolveSeatDecks: () -> Pair<DeckSource, DeckSource>,
    private val resolveGameVariant: () -> String?,
    private val isSpectatorMode: () -> Boolean,
    private val isHumanVsHuman: () -> Boolean,
    private val onLocalPlayerConnected: (GameBridge) -> Unit,
) {
    private val log = LoggerFactory.getLogger(MatchConnectFlow::class.java)

    fun onConnect(attempt: ConnectAttempt) {
        val eventName = coordinator?.selectedEventName
        if (eventName != null) log.info("Match Door: event={}", eventName)

        // Evict stale bridges from previous matches and reset debug collectors.
        val evicted = registry.evictStale(attempt.matchId)
        if (evicted.isNotEmpty()) {
            log.info("Match Door: evicted {} stale match(es)", evicted.size)
        }

        if (puzzleHandler.isPuzzleMatch(attempt.matchId)) {
            connectPuzzle(attempt)
        } else {
            connectConstructed(attempt)
        }
    }

    private fun connectPuzzle(attempt: ConnectAttempt) {
        sendRoomState()
        if (attempt.familiar) {
            log.info("Match Door: puzzle mode, familiar (seat {}) connected — no-op", attempt.seatId)
            return
        }
        val bridge = puzzleHandler.getOrCreatePuzzleBridge(attempt.matchId)
        val ms = createMatchSession(bridge)
        puzzleHandler.sendPuzzleInitialBundle(ms, attempt.matchId, attempt.seatId)
    }

    private fun connectConstructed(attempt: ConnectAttempt) {
        require(!isHumanVsHuman() || !attempt.familiar) { "Two-human matches do not accept Familiar observers" }
        val gameVariant = resolveGameVariant()
        val match =
            registry.getOrCreateMatch(attempt.matchId) {
                val bridge =
                    GameBridge(
                        matchId = attempt.matchId,
                        bridgeTimeoutMs = engineSettings.bridgeTimeoutMs,
                        promptFailsafeMs = engineSettings.promptFailsafeMs,
                        runtimeHorizonMode = RuntimeHorizonMode.Observed,
                        engineSettings = engineSettings,
                        cardRepository = cardRepository,
                    )
                Match(attempt.matchId, bridge).also { newMatch ->
                    // Start the game at match creation (once — CAS-guarded) so both
                    // client connections see a running game and just send their
                    // bundles. Mirrors the non-spectator flow; avoids two connects
                    // racing to start it.
                    val decks = resolveSeatDecks()
                    if (isHumanVsHuman()) {
                        newMatch.startHumanVsHuman(
                            seed = engineSettings.seed,
                            deck1 = decks.first,
                            deck2 = decks.second,
                            variant = gameVariant,
                        )
                    } else if (isSpectatorMode()) {
                        newMatch.startAiVsAi(
                            seed = engineSettings.seed,
                            deck1 = decks.first,
                            deck2 = decks.second,
                            variant = gameVariant,
                        )
                    } else {
                        newMatch.start(
                            seed = engineSettings.seed,
                            deck1 = decks.first,
                            deck2 = decks.second,
                            variant = gameVariant,
                        )
                    }
                }
            }
        val bridge = match.bridge
        if (isSpectatorMode()) {
            connectSpectator(attempt, match)
        } else if (attempt.familiar) {
            createFamiliarSession()
            sendRoomState()
            sendInitialBundle()
        } else {
            onLocalPlayerConnected(bridge)
        }
    }

    private fun connectSpectator(
        attempt: ConnectAttempt,
        match: Match,
    ) {
        // The game is already running (started at match creation). Both connections
        // drain their initial observer batches. Only the primary streams gameplay.
        val spectator = createSpectatorSession(match.bridge)
        sendRoomState()
        sendInitialBundle()
        if (!attempt.familiar) spectator.startPump()
    }
}
