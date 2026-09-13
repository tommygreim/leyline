package leyline.match

import leyline.bridge.coord.SettledPromptAdmission
import leyline.bridge.types.MulliganPhase
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.config.PuzzleDefinition
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.deck.DeckCards
import leyline.domain.deck.DeckSource
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleLibrary
import leyline.game.state.GameBridge
import leyline.infra.MatchOutput
import leyline.infra.MatchOutputMessageSink
import leyline.protocol.HandshakeMessages
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Transport-neutral GRE match connection — routes parsed match-service messages into the engine.
 *
 * **Pre-mulligan:** auth, connect, room state, deal hand, and mulligan use templated
 * proto senders (fixed message shapes). **Post-mulligan:** all game actions delegate
 * to [MatchSession], which drives the engine via bridge futures. The phase boundary
 * is [MatchSession.onMulliganKeep] — after that call, this handler only dispatches.
 * Mulligan and puzzle sub-flows are extracted into [MulliganHandler] / [PuzzleHandler]
 * to keep this class a thin message-routing layer.
 */
@Suppress("LongParameterList")
class MatchConnection(
    private val registry: MatchRegistry,
    private val output: MatchOutput,
    private val engineSettings: EngineSettings,
    private val puzzleLibrary: PuzzleLibrary,
    /** Cross-BC coordinator — deck/event selection, deck resolution, match results. */
    private val coordinator: MatchCoordinator? = null,
    /** Card data repository — used for grpId→name in deck conversion. */
    private val cardRepository: CardRepository,
    /** Runtime puzzle identity supplier — non-null activates puzzle mode. */
    private val puzzleIdentity: () -> String? = { null },
    /** Runtime inline puzzle definition supplier for host-provided challenge launches. */
    private val puzzleDefinition: () -> PuzzleDefinition? = { null },
    /** MatchId-keyed runtime config for native and external in-process hosts. */
    private val runtimeMatchConfigs: RuntimeMatchConfigRegistry? = null,
    /** One-shot opponent deck name consumed only while creating a new match. */
    private val aiDeckNameOverride: () -> String? = { null },
    /** Receives the committed result after terminal output is delivered. */
    private val resultObserver: ((MatchResultObservation) -> Unit)? = null,
    /** Optional setup after puzzle loading and before its runtime loop starts. */
    internal val beforePuzzleRuntimeStart: ((GameBridge) -> Unit)? = null,
) {
    private val log = LoggerFactory.getLogger(MatchConnection::class.java)
    private var runtimeDeliveryObserver: MatchRuntimeDeliveryObserver? = null
    private var runtimeDeliveryGeneration: MatchRuntimeDeliveryGeneration? = null
    private var lastConnectedMatchId: String? = null
    private var lastConnectedSeatId: Int? = null
    private var detachedAfterTeardown = false
    private var seatAssignment: MatchSeatAssignment? = null

    /**
     * Connection lifecycle. Identity accrues while [MatchHandlerState.Handshaking]
     * (auth → connect request), then freezes into [MatchHandlerState.Connected] once
     * the session exists. The split keeps placeholder identity out of the connected
     * state and makes "the session exists" a type-level fact at every post-handshake
     * dispatch rather than a `session?.onX(...)` safe-call that lies about an
     * impossible null.
     */
    private var state: MatchHandlerState = MatchHandlerState.Handshaking()

    private val handshaking: MatchHandlerState.Handshaking?
        get() = state as? MatchHandlerState.Handshaking

    private val connected: MatchHandlerState.Connected?
        get() = state as? MatchHandlerState.Connected

    private val matchId: String
        get() =
            when (val s = state) {
                is MatchHandlerState.Handshaking -> s.matchId
                is MatchHandlerState.Connected -> s.matchId
            }

    private val clientId: String
        get() =
            when (val s = state) {
                is MatchHandlerState.Handshaking -> s.clientId
                is MatchHandlerState.Connected -> s.clientId
            }

    private val seatId: Int
        get() =
            when (val s = state) {
                is MatchHandlerState.Handshaking -> s.seatId
                is MatchHandlerState.Connected -> s.seatId
            }

    private val isFamiliar: Boolean
        get() =
            when (val s = state) {
                is MatchHandlerState.Handshaking -> s.isFamiliar
                is MatchHandlerState.Connected -> s.isFamiliar
            }

    /**
     * Game session — null until connect completes, reassigned on puzzle hot-swap.
     * Reads/writes are backed by the [MatchHandlerState.Connected] state.
     */
    internal var session: SessionOps?
        get() = connected?.session
        set(value) {
            bindSession(value ?: error("session cannot be cleared via assignment"))
        }

    private var spectatorRandomDeckPair: Pair<DeckCards, DeckCards>? = null

    /** Mulligan flow delegate — owns mulligan state and DealHand/MulliganReq senders. */
    internal val mulliganHandler =
        MulliganHandler(
            engineSettings,
            registry,
            sessionProvider = { session as? GameOps },
            matchIdProvider = { matchId },
            seatIdProvider = { SeatId(seatId) },
        )

    /** Puzzle mode delegate — detection, loading, initial bundle. */
    private val puzzleHandler =
        PuzzleHandler(
            ::resolvePuzzleIdentity,
            cardRepository,
            registry,
            engineSettings,
            puzzleLibrary,
            ::resolvePuzzleDefinition,
            beforePuzzleRuntimeStart,
        )

    private val connectFlow =
        MatchConnectFlow(
            registry = registry,
            engineSettings = engineSettings,
            coordinator = coordinator,
            cardRepository = cardRepository,
            puzzleHandler = puzzleHandler,
            createMatchSession = ::createAndRegisterMatchSession,
            createFamiliarSession = ::createAndRegisterFamiliarSession,
            createSpectatorSession = ::createAndRegisterSpectatorSession,
            sendRoomState = ::sendRoomState,
            sendInitialBundle = ::sendInitialBundle,
            resolveSeatDecks = { resolveSeatDecks().let { it.seat1 to it.seat2 } },
            resolveGameVariant = ::resolveGameVariant,
            isSpectatorMode = ::isSpectatorMode,
            isHumanVsHuman = ::isHumanVsHuman,
            onLocalPlayerConnected = ::onLocalPlayerConnected,
        )

    private fun resolvePuzzleIdentity(matchId: String): String? {
        val config = runtimeMatchConfigs?.get(matchId)
        if (config != null) return config.puzzle?.takeIf { it.isNotBlank() }
        return puzzleIdentity()
    }

    private fun resolvePuzzleDefinition(matchId: String): PuzzleDefinition? =
        runtimeMatchConfigs?.get(matchId)?.puzzleDefinition ?: puzzleDefinition()

    private fun resolveRuntimeMatchConfig(): RuntimeMatchConfig? = runtimeMatchConfigs?.get(matchId)

    private fun isSpectatorMode(): Boolean =
        if (isHumanVsHuman()) false else runtimeMatchConfigs?.get(matchId)?.spectatorMode ?: engineSettings.spectatorMode

    private fun isHumanVsHuman(): Boolean = resolveRuntimeMatchConfig()?.humanVsHuman == true

    /** Bind the host-validated room reservation before the initial GRE connect. */
    fun assignSeat(assignment: MatchSeatAssignment) {
        check(seatAssignment == null && connected == null) { "This connection already has a seat" }
        val hs = checkNotNull(handshaking)
        hs.matchId = assignment.matchId
        hs.clientId = assignment.playerId
        hs.seatId = assignment.seatId.value
        hs.isFamiliar = assignment.familiar
        seatAssignment = assignment
    }

    fun opened() {
        log.info("Match connection opened")
    }

    fun receive(msg: ClientToMatchServiceMessage) {
        Tap.inbound(msg.clientToMatchServiceMessageType)

        when (msg.clientToMatchServiceMessageType) {
            ClientToMatchServiceMessageType.AuthenticateRequest_f487 -> handleMatchAuth(msg)
            ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487 -> handleMatchDoorConnect(msg)
            ClientToMatchServiceMessageType.ClientToGremessage -> handleGREMessage(msg)
            ClientToMatchServiceMessageType.ClientToGreuimessage -> handleGREMessage(msg)
            ClientToMatchServiceMessageType.None_a0f0,
            ClientToMatchServiceMessageType.CreateMatchGameRoomRequest_f487,
            ClientToMatchServiceMessageType.EchoRequest_f487,
            ClientToMatchServiceMessageType.UNRECOGNIZED,
            -> log.warn("Match Door: unhandled type: {}", msg.clientToMatchServiceMessageType)
        }
    }

    /** Submit one parsed gameplay message through the connection-owned session. */
    fun submitGREMessage(greMsg: ClientToGREMessage) = processGREMessage(greMsg)

    /** Arm delivery after the initial client-owned horizon has been bound. */
    fun armRuntimeDeliveryObserver() {
        stopRuntimeDeliveryObserver()
        val active = session as? MatchSession ?: return
        val generation = MatchRuntimeDeliveryGeneration()
        runtimeDeliveryGeneration = generation
        runtimeDeliveryObserver =
            MatchRuntimeDeliveryObserver(
                session = active,
                seatId = active.seatId,
                generation = generation,
            ).also { it.start() }
    }

    internal fun stopRuntimeDeliveryObserver() {
        runtimeDeliveryGeneration?.invalidate()
        runtimeDeliveryObserver?.stop()
        runtimeDeliveryGeneration = null
        runtimeDeliveryObserver = null
    }

    private fun handleMatchAuth(msg: ClientToMatchServiceMessage) {
        val authReq = AuthenticateRequest.parseFrom(msg.payload)
        val hs = requireHandshaking()
        hs.clientId = authReq.clientId.ifEmpty { "leyline-player-${hs.seatId}" }
        hs.isFamiliar = hs.clientId.endsWith("_Familiar")
        val playerName = authReq.playerName.ifEmpty { "Player" }
        log.info("Match Door: auth familiar={}", isFamiliar)

        val resp =
            MatchServiceToClientMessage
                .newBuilder()
                .setRequestId(msg.requestId)
                .setAuthenticateResponse(
                    AuthenticateResponse
                        .newBuilder()
                        .setClientId(clientId)
                        .setSessionId("forge-session-1")
                        .setScreenName(playerName),
                ).build()
        output.send(resp)
    }

    private fun handleMatchDoorConnect(msg: ClientToMatchServiceMessage) {
        val connectReq = ClientToMatchDoorConnectRequest.parseFrom(msg.payload)
        val hs = requireHandshaking()
        seatAssignment?.let { require(connectReq.matchId == it.matchId) { "Match does not match the assigned room" } }
        if (connectReq.matchId.isNotEmpty()) hs.matchId = connectReq.matchId
        log.info("Match Door: connect matchId={}", hs.matchId)

        if (connectReq.clientToGreMessageBytes.isEmpty) return

        val greMsg = ClientToGREMessage.parseFrom(connectReq.clientToGreMessageBytes)
        if (seatAssignment == null && greMsg.systemSeatId > 0) hs.seatId = greMsg.systemSeatId
        log.info("Match Door: detected seatId={}", hs.seatId)
        // Session creation deferred to the ConnectReq branch in processGREMessage:
        // MatchSession requires a non-null bridge at construction, so we wait for
        // getOrCreateMatch() to build the bridge before constructing the session.
        processGREMessage(greMsg)
    }

    /**
     * Return the current handshake, or re-open one on top of an already-[Connected]
     * state.
     *
     * A single [MatchHandler] instance can outlive more than one physical connection
     * attempt — the web relay attaches a fresh browser socket to the same engine
     * instance on every reconnect (page reload, retried websocket, duplicate
     * attach), unlike the native transport where every TCP connection gets its own
     * handler. Without this, a second Auth+Connect handshake on an already-Connected
     * handler used to `error()`, which [exceptionCaught] swallows into a silent
     * match teardown — the client gets no reply and no further game state, ever.
     * Re-opening the handshake (seeded with the frozen identity so an incoming
     * request that omits matchId/seatId still resolves correctly) lets the normal
     * connect flow run again and resend a full resync bundle instead.
     */
    private fun requireHandshaking(): MatchHandlerState.Handshaking {
        handshaking?.let { return it }
        val prior = connected ?: error("Expected handshaking state but connection is already established")
        log.info("Match Door: reconnect on already-established matchId={} seatId={}, resyncing", prior.matchId, prior.seatId)
        stopRuntimeDeliveryObserver()
        (prior.session as? MatchSession)?.close()
        val reopened =
            MatchHandlerState.Handshaking().also {
                it.matchId = prior.matchId
                it.clientId = prior.clientId
                it.seatId = prior.seatId
                it.isFamiliar = prior.isFamiliar
            }
        state = reopened
        return reopened
    }

    /**
     * Freeze the accrued handshake identity and bind the session — Handshaking → Connected.
     * The connect ctx was already stored in [MatchHandlerState.Handshaking.nettyCtx] during
     * the connect request; on re-bind (puzzle hot-swap) identity stays frozen and only the
     * session is replaced.
     */
    private fun bindSession(session: SessionOps) {
        lastConnectedMatchId = session.matchId
        lastConnectedSeatId = session.seatId.value
        detachedAfterTeardown = false
        state =
            when (val s = state) {
                is MatchHandlerState.Handshaking ->
                    MatchHandlerState.Connected(s.matchId, s.clientId, s.seatId, s.isFamiliar, session)
                is MatchHandlerState.Connected ->
                    MatchHandlerState.Connected(s.matchId, s.clientId, s.seatId, s.isFamiliar, session)
            }
    }

    /** Create and register a [MatchSession] bound to [bridge]. */
    private fun createAndRegisterMatchSession(bridge: GameBridge): MatchSession {
        val sink = MatchOutputMessageSink(output)
        val connection =
            ConnectionState(
                seatId = SeatId(seatId),
                matchId = matchId,
                sink = sink,
                registry = registry,
                resultObserver = { observation ->
                    if (resultObserver != null) {
                        resultObserver.invoke(observation)
                    } else if (seatAssignment?.humanVsHuman != true) {
                        coordinator?.reportMatchResult(observation.won)
                    }
                },
            ).also { connection ->
                connection.playerId = clientId.removeSuffix("_Familiar")
                seatAssignment?.takeIf { it.humanVsHuman }?.let { assignment ->
                    connection.roomPlayers =
                        assignment.players.map {
                            HandshakeMessages.RoomPlayer(it.playerId, it.displayName, it.seatId.value)
                        }
                    connection.eventName = assignment.eventName
                }
            }
        val s =
            MatchSession(
                connection = connection,
                gameBridge = bridge,
                paceDelayMs = engineSettings.paceDelayMs,
            )
        bindSession(s)
        registry.registerSession(matchId, SeatId(seatId), s)
        registry.registerConnection(matchId, SeatId(seatId), this)
        return s
    }

    /** Create and register a read-only familiar session. */
    private fun createAndRegisterFamiliarSession(): FamiliarSession {
        val sink = MatchOutputMessageSink(output)
        val bridge = checkNotNull(registry.getMatch(matchId)?.bridge) { "Familiar match is unavailable" }
        val s = FamiliarSession(SeatId(seatId), matchId, sink, bridge)
        bindSession(s)
        registry.registerSession(matchId, SeatId(seatId), s)
        registry.registerConnection(matchId, SeatId(seatId), this)
        return s
    }

    private fun createAndRegisterSpectatorSession(bridge: GameBridge): SpectatorSession {
        val sink = MatchOutputMessageSink(output)
        val sessionSeat = SeatId(seatId)
        val s =
            SpectatorSession(
                sessionSeat,
                matchId,
                sink,
                bridge,
                playerId = clientId.removeSuffix("_Familiar"),
                peerSession = { registry.getPeer(matchId, sessionSeat) as? SpectatorSession },
            )
        bindSession(s)
        registry.registerSession(matchId, SeatId(seatId), s)
        registry.registerConnection(matchId, SeatId(seatId), this)
        return s
    }

    private fun handleGREMessage(msg: ClientToMatchServiceMessage) {
        val greMsg = ClientToGREMessage.parseFrom(msg.payload)
        processGREMessage(greMsg)
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun processGREMessage(greMsg: ClientToGREMessage) {
        seatAssignment?.let { assignment ->
            require(greMsg.systemSeatId == 0 || greMsg.systemSeatId == assignment.seatId.value) {
                "GRE seat does not match the assigned player"
            }
            require(greMsg.type != ClientMessageType.ConnectReq_097b || session == null) { "The assigned seat is already connected" }
        }
        // MatchSession owns gameplay-response admission and emits the accepted/
        // rejected event. Keep Tap for pre-session and unowned protocol receipts,
        // where no structured lifecycle event exists.
        if (session !is MatchSession || !isGameplayResponse(greMsg.type)) {
            Tap.inboundGRE(greMsg.type, greMsg.systemSeatId, greMsg.gameStateId)
        }

        if (greMsg.type != ClientMessageType.ConnectReq_097b) {
            val admission = (session as? MatchSession)?.admitSettled(greMsg)
            if (admission != null && admission != SettledPromptAdmission.NotOwned) return
        }

        // Pre-session messages drive the handshake/mulligan flow, which read session
        // state defensively through providers. Everything else is a post-handshake
        // game action that requires a live session — dispatched against Connected,
        // where MatchSession owns response correlation. Read-only familiar and
        // spectator sessions intentionally ignore mirrored gameplay responses.
        when (greMsg.type) {
            ClientMessageType.ConnectReq_097b -> connectFlow.onConnect(ConnectAttempt(matchId, seatId, isFamiliar))

            // Startup is server-owned. Legacy responses are inert and cannot replay it.
            ClientMessageType.ChooseStartingPlayerResp_097b -> {}

            ClientMessageType.MulliganResp_097b ->
                withConnectionOwnedResponse(greMsg) { mulliganHandler.onMulliganResp(greMsg) }

            ClientMessageType.GroupResp_097b -> dispatchLondonTuck(greMsg)

            else -> dispatchToSession(greMsg)
        }
    }

    private fun dispatchLondonTuck(greMsg: ClientToGREMessage) {
        val gameSession = session as? GameOps
        val bridge = gameSession?.gameBridge
        if (bridge?.mulliganBridge(SeatId(seatId))?.pendingPrompt()?.phase == MulliganPhase.WaitingTuck) {
            checkNotNull(gameSession)
            withConnectionOwnedResponse(greMsg) { mulliganHandler.onGroupResp(greMsg) }
        } else {
            log.debug("Match Door GRE: stale GroupResp without London-tuck window")
        }
    }

    /** Validate responses consumed by the connection-owned pre-game flow. */
    private inline fun withConnectionOwnedResponse(
        greMsg: ClientToGREMessage,
        block: () -> Unit,
    ) {
        val activeSession = session ?: return
        val bridge = registry.getMatch(matchId)?.bridge ?: return
        val failure = ResponseEnvelopeGuard.mismatchReason(greMsg, bridge.committedSequence(), bridge.responseAcceptance)
        if (failure == null) {
            block()
        } else {
            bridge.cutCoordinator.publishIllegalRequest(activeSession.seatId, greMsg, failure)
            deliverCommittedCoordinatorBatches(activeSession, bridge, activeSession.seatId)
        }
    }

    /** Dispatch a post-handshake game action against the live [Connected] session. */
    @Suppress("CyclomaticComplexMethod", "ElseCaseInsteadOfExhaustiveWhen")
    private fun dispatchToSession(greMsg: ClientToGREMessage) {
        val s =
            connected?.session ?: run {
                when (greMsg.type) {
                    // Cosmetic relay / post-game ack — harmless without a session.
                    ClientMessageType.Uimessage_a39e -> {}
                    ClientMessageType.CheckpointReq ->
                        log.info("Match Door GRE: CheckpointReq (post-game acknowledgement)")
                    else -> log.warn("Match Door GRE: {} before session established", greMsg.type)
                }
                return
            }

        if (dispatchGameplayResponse(s, greMsg)) return

        when (greMsg.type) {
            ClientMessageType.SetSettingsReq_097b -> s.onSettings(greMsg)

            ClientMessageType.ConcedeReq_097b -> {
                log.info("Match Door GRE: concede")
                s.onConcede()
            }

            ClientMessageType.CheckpointReq -> {
                // Client acknowledges IntermissionReq — MatchCompleted room state
                // was already sent in sendGameOver(). Nothing to do here.
                log.info("Match Door GRE: CheckpointReq (post-game acknowledgement)")
            }

            // Cosmetic UI relay (emotes, card hover, pet animations) — no game state impact.
            // In single-player-vs-AI context there's nobody to relay to; silently ignore.
            ClientMessageType.Uimessage_a39e -> { }

            else -> log.warn("Match Door GRE: unhandled type: {}", greMsg.type)
        }
    }

    // --- Template-based senders (pre-mulligan) ---

    private fun sendRoomState() {
        val playerId = clientId.removeSuffix("_Familiar")
        val opponentName = "AI Opponent"
        val eventName = coordinator?.selectedEventName ?: "AIBotMatch"
        val assignment = seatAssignment
        val msg =
            if (assignment?.humanVsHuman == true) {
                HandshakeMessages.roomState(
                    matchId,
                    assignment.eventName,
                    assignment.players.map { HandshakeMessages.RoomPlayer(it.playerId, it.displayName, it.seatId.value) },
                )
            } else {
                HandshakeMessages.roomState(matchId, playerId, opponentName, eventName, true)
            }
        output.send(msg)
        Tap.outboundTemplate("room_state", matchId = matchId, seat = seatId)
    }

    private fun sendInitialBundle() {
        val s = session ?: return
        val bridge = registry.getMatch(matchId)?.bridge ?: return
        val seat = SeatId(seatId)
        bridge.cutCoordinator.lifecycle.publishInitial(
            seat,
            includeStartingPlayerPrompt = !isSpectatorMode(),
        )
        s.deliverLifecycle(bridge)
        Tap.outboundTemplate("initial_bundle", matchId = matchId, seat = seatId)
        if (isHumanVsHuman()) {
            mulliganHandler.startPlayersIfReady()
        } else if (!isSpectatorMode()) {
            mulliganHandler.startFamiliarIfReady()
        }
    }

    private fun onLocalPlayerConnected(bridge: GameBridge) {
        createAndRegisterMatchSession(bridge)
        mulliganHandler.seat1Hand = bridge.getHandGrpIds(SeatId(1))
        mulliganHandler.seat2Hand = bridge.getHandGrpIds(SeatId(2))
        log.info(
            "Match Door: seat {} connected, hand counts seat1={} seat2={}",
            seatId,
            mulliganHandler.seat1Hand.size,
            mulliganHandler.seat2Hand.size,
        )
        sendRoomState()
        sendInitialBundle()
    }

    fun disconnected() {
        val event = log.atInfo().addKeyValue("event", "match.disconnected")
        val correlatedEvent = lastConnectedMatchId?.let { event.addKeyValue("match_id", it) } ?: event
        val seatedEvent = lastConnectedSeatId?.let { correlatedEvent.addKeyValue("seat", it) } ?: correlatedEvent
        seatedEvent.log("Match client disconnected")
        stopRuntimeDeliveryObserver()
        if (detachedAfterTeardown || (session == null && seatAssignment == null)) return
        if (isSpectatorMode() && isFamiliar) {
            log.info("Match Door: spectator familiar disconnected, leaving AI match active")
            return
        }
        registry.teardownMatch(
            matchId = matchId,
            reason = MatchTeardownReason.Disconnect,
            seatId = SeatId(seatId),
            fallbackBridge = (session as? GameOps)?.gameBridge,
        )
    }

    fun failed(cause: Throwable) {
        val event = log.atError().setCause(cause).addKeyValue("event", "match.connection_failed")
        val correlatedEvent = lastConnectedMatchId?.let { event.addKeyValue("match_id", it) } ?: event
        val seatedEvent = lastConnectedSeatId?.let { correlatedEvent.addKeyValue("seat", it) } ?: correlatedEvent
        seatedEvent.log("Match connection failed")
        stopRuntimeDeliveryObserver()
        if (detachedAfterTeardown || (session == null && seatAssignment == null)) {
            output.close()
            return
        }
        registry.teardownMatch(
            matchId = matchId,
            reason = MatchTeardownReason.Exception,
            seatId = SeatId(seatId),
            fallbackBridge = (session as? GameOps)?.gameBridge,
        )
        output.close()
    }

    internal fun detachAfterTeardown() {
        // Connection is gone — drop session/ctx by reverting to a fresh handshake state.
        stopRuntimeDeliveryObserver()
        detachedAfterTeardown = true
        state = MatchHandlerState.Handshaking()
        if (seatAssignment != null) output.close()
        seatAssignment = null
    }

    /**
     * MatchHandler connection lifecycle.
     *
     * Identity is mutable and tentative during [Handshaking] (accrues across
     * AuthenticateRequest then ClientToMatchDoorConnectRequest). Once the session is
     * built it freezes into [Connected], where `session` is non-null — so every
     * post-handshake dispatch typechecks against a session that must exist instead of
     * a `session?.onX(...)` safe-call that lies about an impossible null. The
     * placeholder identity defaults live only in [Handshaking]; [Connected] carries
     * the resolved values.
     */
    private sealed class MatchHandlerState {
        class Handshaking : MatchHandlerState() {
            /** Tentative until ClientToMatchDoorConnectRequest carries a non-empty matchId. */
            var matchId: String = "forge-match-1"

            /** Tentative until AuthenticateRequest; otherwise a seat-derived fallback. */
            var clientId: String = "forge-player-1"

            /** Tentative until the connect-request GRE reports a positive systemSeatId. */
            var seatId: Int = 1

            var isFamiliar: Boolean = false
        }

        class Connected(
            val matchId: String,
            val clientId: String,
            val seatId: Int,
            val isFamiliar: Boolean,
            var session: SessionOps,
        ) : MatchHandlerState()
    }

    private data class SeatDecks(
        val seat1: DeckSource,
        val seat2: DeckSource,
    )

    private fun resolveSeatDecks(): SeatDecks {
        val randomDecks = spectatorRandomDecksIfEnabled()
        val runtimeMatchConfig = resolveRuntimeMatchConfig()
        val opponentDeckName = aiDeckNameOverride()
        val seat1Deck = resolveSeat1Deck(randomDecks, runtimeMatchConfig)
        return SeatDecks(
            seat1 = seat1Deck,
            seat2 = resolveSeat2Deck(randomDecks, runtimeMatchConfig, opponentDeckName, seat1Deck),
        )
    }

    /**
     * Resolve seat 1 deck: FD stored a deckId from 612 → look it up in player.db.
     */
    private fun resolveSeat1Deck(
        randomDecks: Pair<DeckCards, DeckCards>?,
        runtimeMatchConfig: RuntimeMatchConfig?,
    ): DeckSource {
        randomDecks?.first?.let {
            log.info("Match Door: spectator seat 1 deck from random pair")
            return DeckSource.Cards(it)
        }
        runtimeMatchConfig?.seat1?.let {
            log.info("Match Door: seat 1 deck from runtime override")
            return it
        }
        val deckId = coordinator?.selectedDeckId
        if (deckId != null) {
            val cards = coordinator.resolveDeckCards(deckId)
            if (cards != null) {
                log.info("Match Door: seat 1 deck from DB deckId={}", deckId)
                return DeckSource.Cards(cards)
            }
            log.warn("Match Door: deckId {} not in DB", deckId)
        }
        // Fallback: pick first deck from DB (AI bot events don't send deckId)
        val fallback = coordinator?.resolveFirstDeckCards()
        if (fallback != null) {
            log.info("Match Door: seat 1 using fallback deck (no deckId from client)")
            return DeckSource.Cards(fallback)
        }
        error("No deck selected for seat 1 — select a deck in the Arena client before queuing")
    }

    /**
     * Resolve seat 2 (AI) deck.
     *
     * Priority:
     *   1. Pod-bot deck for the active event (Quick Draft → one of the 7 bots that
     *      drafted alongside the player). Falls through if the event has no pod.
     *   2. AI deck name from `engineSettings.aiDeck` looked up in player.db.
     *   3. Mirror seat 1's deck.
     */
    private fun resolveSeat2Deck(
        randomDecks: Pair<DeckCards, DeckCards>?,
        runtimeMatchConfig: RuntimeMatchConfig?,
        opponentDeckName: String?,
        seat1Deck: DeckSource,
    ): DeckSource {
        randomDecks?.second?.let {
            log.info("Match Door: spectator seat 2 deck from random pair")
            return DeckSource.Cards(it)
        }
        runtimeMatchConfig?.seat2?.let {
            log.info("Match Door: seat 2 deck from runtime override")
            return it
        }
        opponentDeckName?.takeIf { it.isNotBlank() }?.let { name ->
            val cards = coordinator?.resolveDeckCardsByName(name)
            if (cards != null) {
                log.info("Match Door: seat 2 deck from one-shot override name={}", name)
                return DeckSource.Cards(cards)
            }
            log.warn("Match Door: one-shot AI deck '{}' not in DB, falling back", name)
        }
        val event = coordinator?.selectedEventName
        if (event != null) {
            val podCards = coordinator.resolveOpponentDeckCards(event)
            if (podCards != null) {
                log.info("Match Door: seat 2 deck from draft pod event={}", event)
                return DeckSource.Cards(podCards)
            }
        }

        val aiDeckName = engineSettings.aiDeck
        if (aiDeckName != null && coordinator != null) {
            val cards = coordinator.resolveDeckCardsByName(aiDeckName)
            if (cards != null) {
                log.info("Match Door: seat 2 deck from DB name={}", aiDeckName)
                return DeckSource.Cards(cards)
            }
            log.warn("Match Door: AI deck '{}' not in DB, mirroring seat 1", aiDeckName)
        }
        return seat1Deck
    }

    private fun spectatorRandomDecksIfEnabled(): Pair<DeckCards, DeckCards>? {
        if (!isSpectatorMode()) return null
        if (!engineSettings.aiDeck.equals("random", ignoreCase = true)) return null
        return spectatorRandomDecks()
    }

    private fun spectatorRandomDecks(): Pair<DeckCards, DeckCards>? {
        spectatorRandomDeckPair?.let { return it }
        val pair = coordinator?.resolveRandomDeckCardsPair()
        spectatorRandomDeckPair = pair
        if (pair == null) log.warn("Match Door: spectator random deck pair unavailable, falling back to selected deck")
        return pair
    }

    /** A runtime launch overrides the selected event; event selection remains the client-match fallback. */
    private fun resolveGameVariant(): String? =
        runtimeGameVariant(resolveRuntimeMatchConfig(), seatAssignment?.eventName ?: coordinator?.selectedEventName)
}

/** Explicit runtime variants take precedence over event-selected format inference. */
internal fun runtimeGameVariant(
    runtimeConfig: RuntimeMatchConfig?,
    selectedEventName: String?,
): String? {
    if (runtimeConfig?.gameVariant != null || runtimeConfig?.spectatorMode == true) return runtimeConfig.gameVariant
    return if (selectedEventName?.contains("Brawl", ignoreCase = true) == true) "brawl" else null
}
