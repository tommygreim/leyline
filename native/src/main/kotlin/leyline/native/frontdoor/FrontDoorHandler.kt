package leyline.native.frontdoor

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leyline.domain.CourseDeck
import leyline.domain.CourseDeckSummary
import leyline.domain.CourseModule
import leyline.domain.Deck
import leyline.domain.DeckId
import leyline.domain.MatchInfo
import leyline.domain.PlayerId
import leyline.domain.Preferences
import leyline.domain.json.productionJson
import leyline.domain.repo.DeckRepository
import leyline.domain.service.CollectionService
import leyline.domain.service.CourseService
import leyline.domain.service.DraftService
import leyline.domain.service.EventRegistry
import leyline.domain.service.MatchCoordinator
import leyline.domain.service.MatchmakingService
import leyline.native.account.LocalAccountAuthenticator
import leyline.native.frontdoor.service.LobbyStubs
import leyline.native.frontdoor.service.PlayerService
import leyline.native.frontdoor.wire.CmdType
import leyline.native.frontdoor.wire.DeckWireBuilder
import leyline.native.frontdoor.wire.DraftWireBuilder
import leyline.native.frontdoor.wire.EventWireBuilder
import leyline.native.frontdoor.wire.FdEnvelope
import leyline.native.frontdoor.wire.FdRequests
import leyline.native.frontdoor.wire.FdResponse
import leyline.native.frontdoor.wire.FdResponseWriter
import leyline.native.frontdoor.wire.FdWireConstants
import leyline.native.frontdoor.wire.PlayerWireBuilder
import leyline.native.frontdoor.wire.StartHookBuilder
import leyline.native.matchmaking.LocalPairingService
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.UUID

/**
 * Front Door handler (port 30010).
 *
 * Dispatches by CmdType to layered services ([DeckRepository], [PlayerService],
 * [MatchmakingService]) with [LobbyStubs] for unimplemented endpoints.
 *
 * Static protocol data (formats, sets, graph defs) comes from [FrontDoorBootstrapData].
 * Wire serialization is handled by [DeckWireBuilder] / [PlayerWireBuilder]
 * and responses go through [FdResponseWriter].
 */
class FrontDoorHandler(
    authenticator: LocalAccountAuthenticator,
    private val deckRepository: DeckRepository,
    private val playerService: PlayerService,
    private val matchmaking: MatchmakingService,
    private val collectionService: CollectionService,
    private val courseService: CourseService,
    private val draftService: DraftService,
    private val writer: FdResponseWriter,
    private val bootstrapData: FrontDoorBootstrapData,
    coordinatorFactory: (PlayerId) -> MatchCoordinator = { MatchCoordinator.NOOP },
    private val pairingService: LocalPairingService? = null,
) : ChannelInboundHandlerAdapter() {
    private val log = LoggerFactory.getLogger(FrontDoorHandler::class.java)
    private val identity = FrontDoorIdentity(authenticator, playerService, writer, coordinatorFactory)
    private val account get() = identity.account
    private val playerId get() = identity.playerId
    private val coordinator get() = identity.coordinator
    private val connectionId get() = identity.connectionId

    /**
     * Deck selected via 622 (Event_SetDeckV2), keyed by eventName. Consumed by 603 (EnterPairing).
     *
     * Only used for constructed events where the deck lives in [DeckRepository].
     * Sealed events get their deck from [CourseService] instead — 622 writes
     * the deck into the Course, and 603 reads it back via [CourseService.enterPairing].
     * Entries are never cleaned up (harmless — handler is per-connection).
     */
    private val selectedDeckByEvent = mutableMapOf<String, String>()

    private val lenientJson =
        productionJson {
            ignoreUnknownKeys = true
            isLenient = true
        }

    init {
        log.info(
            "Front Door: loaded bootstrap data — formats={}B sets={}B",
            bootstrapData.getFormatsProto.size,
            bootstrapData.getSetsProto.size,
        )
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        log.info("Front Door: client connected")
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        pairingService?.leave(connectionId)
    }

    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any,
    ) {
        if (msg !is ByteBuf) {
            ReferenceCountUtil.release(msg)
            return
        }
        try {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)

            if (bytes.size < FdWireConstants.HEADER_SIZE) return

            val frameType = bytes[1]
            log.trace("Front Door: frame type=0x{} size={}", String.format(Locale.ROOT, "%02x", frameType), bytes.size)

            // Control frames
            if (frameType == FdWireConstants.TYPE_CTRL_INIT) {
                log.trace("Front Door: CTRL_INIT received, sending ACK")
                writer.sendCtrlAck(ctx, bytes)
                return
            }
            if (frameType == FdWireConstants.TYPE_CTRL_ACK) {
                log.trace("Front Door: CTRL_ACK received (ignored)")
                return
            }

            val payload =
                if (bytes.size > FdWireConstants.HEADER_SIZE) {
                    bytes.copyOfRange(FdWireConstants.HEADER_SIZE, bytes.size)
                } else {
                    null
                }

            if (payload == null) {
                log.debug("Front Door: header-only message (ack/heartbeat)")
                return
            }

            val decoded =
                try {
                    FdEnvelope.decode(payload)
                } catch (e: Exception) {
                    log
                        .atError()
                        .setCause(e)
                        .addKeyValue("event", "frontdoor.envelope_decode_failed")
                        .addKeyValue("subsystem", "frontdoor")
                        .addKeyValue("request", "envelope")
                        .addKeyValue("payload_bytes", payload.size)
                        .log("Front Door envelope decode failed")
                    writer.send(ctx, null, FdResponse.Empty)
                    return
                }
            val json = decoded.jsonPayload
            val transactionId = decoded.transactionId
            val cmdType = decoded.cmdType
            val cmdName = cmdType?.let { CmdType.nameOf(it) } ?: "unknown"

            log.debug(
                "Front Door: cmd={} cmdType={} txId={} envelope={} protobufType={}",
                cmdName,
                cmdType,
                transactionId,
                decoded.envelopeType,
                decoded.protobufTypeUrl,
            )

            dispatch(ctx, cmdType, transactionId, json, decoded.protobufTypeUrl)
        } finally {
            msg.release()
        }
    }

    private val stubs = frontDoorStubs(bootstrapData)

    @Suppress(
        "CanBeNonNullable",
        "CyclomaticComplexMethod",
        "LongMethod",
    ) // `json` comes from the decoder which may emit null for empty bodies.
    private fun dispatch(ctx: ChannelHandlerContext, cmdType: Int?, txId: String?, json: String?, protobufTypeUrl: String?) {
        if (!identity.accept(ctx, cmdType, txId, json)) return
        // Fast path: table-driven stubs (no logic, just data)
        stubs[cmdType]?.let { supplier ->
            writer.send(ctx, txId, supplier())
            return
        }

        // Commands with real logic
        when (cmdType) {
            CmdType.START_HOOK.value -> {
                val decks = deckRepository.findAllForPlayer(playerId)
                val hook = StartHookBuilder.build(decks)
                log.info("Front Door: StartHook ({}B, {} decks)", hook.length, decks.size)
                val response =
                    if (protobufTypeUrl != null) {
                        FdResponse.RawProto(FdProtoBuilder.buildStartHookProto(hook))
                    } else {
                        FdResponse.Json(hook)
                    }
                writer.send(ctx, txId, response)
            }

            CmdType.GET_PLAYER_INBOX.value -> {
                val response =
                    if (protobufTypeUrl != null) {
                        FdResponse.TypedProto("Wizards.Arena.Models.Network.GetPlayerInboxResp")
                    } else {
                        FdResponse.Json(LobbyStubs.playerInbox())
                    }
                writer.send(ctx, txId, response)
            }

            CmdType.GRAPH_GET_STATE.value -> handleGraphRequest(ctx, txId, json)

            CmdType.GRAPH_ADVANCE_NODE.value -> handleGraphRequest(ctx, txId, json)

            CmdType.EVENT_AI_BOT_MATCH.value -> {
                val req = FdRequests.parseAiBotMatch(json)
                val deckId = req?.deckId
                if (deckId == null || ownedDeck(deckId) == null) {
                    writer.send(ctx, txId, FdResponse.Empty)
                    return
                }
                coordinator.selectDeck(deckId)
                coordinator.selectEvent("AIBotMatch")
                val match = matchmaking.startAiMatch(playerId, DeckId(deckId), "AIBotMatch")
                try {
                    pairingService?.leave(connectionId)
                    pairingService?.registerBot(match, playerId, checkNotNull(account).displayName)
                } catch (e: IllegalArgumentException) {
                    log.info("Front Door: Bot Match unavailable: {}", e.message)
                    writer.send(ctx, txId, FdResponse.Empty)
                    return
                }
                log.info("Front Door: Event_AiBotMatch deckId={} botDeckId={} → ack + pushing MatchCreated", deckId, req.botDeckId)
                writer.send(ctx, txId, FdResponse.Empty)
                sendMatchCreated(ctx, match)
            }

            CmdType.GET_PLAY_BLADE_QUEUE_CONFIG.value -> {
                val configJson = EventWireBuilder.toQueueConfigJson(EventRegistry.queues)
                log.info("Front Door: PlayBladeQueueConfig ({} queues)", EventRegistry.queues.size)
                writer.send(ctx, txId, FdResponse.Json(configJson))
            }

            CmdType.EVENT_GET_ACTIVE_EVENTS_V2.value -> {
                val homeEvents = EventRegistry.activeEvents
                val eventsJson = EventWireBuilder.toActiveEventsJson(homeEvents, EventRegistry.aiBotMatches)
                log.info("Front Door: ActiveEventsV2 ({} events, {} aiBotMatches)", homeEvents.size, EventRegistry.aiBotMatches.size)
                writer.send(ctx, txId, FdResponse.Json(eventsJson))
            }

            CmdType.EVENT_GET_COURSES_V2.value -> {
                log.info("Front Door: Event_GetCoursesV2")
                val courses =
                    courseService
                        .getCoursesForPlayer(playerId)
                        .filter { it.module != CourseModule.BotDraft } // hide draft-in-progress courses from merged list
                val realEventNames = courses.map { it.eventName }.toSet()
                val defaultJson =
                    EventRegistry.defaultCourses
                        .filter { it.first !in realEventNames }
                writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.toMergedCoursesJson(courses, defaultJson)))
            }

            CmdType.GET_PLAYER_PREFERENCES.value -> {
                val prefs = playerService.getPreferences(playerId)
                val raw = prefs?.json?.takeIf { it != "{}" }
                log.info("Front Door: PlayerPreferences ({})", if (raw != null) "db" else "empty")
                writer.send(ctx, txId, FdResponse.Json(raw ?: """{"Preferences":{}}"""))
            }

            CmdType.SET_PLAYER_PREFERENCES.value -> {
                requireJson(ctx, txId, json) { body ->
                    val cleaned = PlayerWireBuilder.parsePreferences(body)
                    playerService.savePreferences(playerId, Preferences(cleaned))
                    log.info("Front Door: SetPlayerPreferences saved")
                    writer.send(ctx, txId, FdResponse.Json("{}"))
                }
            }

            CmdType.DECK_GET.value -> {
                val req = FdRequests.parseGetDeck(json)
                val deck = req?.let { deckRepository.findById(DeckId(it.deckId)) }?.takeIf { it.playerId == playerId }
                val response =
                    if (deck != null) {
                        log.info("Front Door: Deck_GetDeck '{}'", deck.name)
                        FdResponse.Json(DeckWireBuilder.toStartHookEntry(deck).toString())
                    } else {
                        log.warn("Front Door: Deck_GetDeck requested an unavailable deck")
                        FdResponse.Empty
                    }
                writer.send(ctx, txId, response)
            }

            CmdType.DECK_DELETE.value -> {
                val req = FdRequests.parseDeleteDeck(json)
                if (req != null) {
                    val deleted =
                        synchronized(deckRepository) {
                            if (ownedDeck(req.deckId) == null) {
                                false
                            } else {
                                deckRepository.delete(DeckId(req.deckId))
                                true
                            }
                        }
                    if (!deleted) {
                        writer.send(ctx, txId, FdResponse.Empty)
                        return
                    }
                    log.info("Front Door: Deck_DeleteDeck '{}'", req.deckId)
                }
                writer.send(ctx, txId, FdResponse.Json("Success"))
            }

            CmdType.DECK_UPSERT_V2.value -> {
                requireJson(ctx, txId, json) { body ->
                    val savedDeck = DeckWireBuilder.parseDeckUpdate(body, playerId)
                    val resp =
                        if (savedDeck != null && saveOwnedDeck(savedDeck)) {
                            log.info("Front Door: Deck_UpsertDeckV2 saved '{}'", savedDeck.name)
                            val summary = DeckWireBuilder.toV2Summary(savedDeck)
                            buildJsonObject { put("Summary", summary) }
                        } else {
                            log.warn("Front Door: Deck_UpsertDeckV2 parse failed")
                            buildJsonObject {}
                        }
                    writer.send(ctx, txId, FdResponse.Json(lenientJson.encodeToString(JsonObject.serializer(), resp)))
                }
            }

            CmdType.DECK_UPSERT_V3.value -> {
                requireJson(ctx, txId, json) { body ->
                    val savedDeck = DeckWireBuilder.parseDeckUpdate(body, playerId)
                    val resp =
                        if (savedDeck != null && saveOwnedDeck(savedDeck)) {
                            log.info("Front Door: Deck_UpsertDeckV3 saved '{}'", savedDeck.name)
                            DeckWireBuilder.toStartHookSummary(savedDeck)
                        } else {
                            log.warn("Front Door: Deck_UpsertDeckV3 parse failed")
                            buildJsonObject {}
                        }
                    writer.send(ctx, txId, FdResponse.Json(lenientJson.encodeToString(JsonObject.serializer(), resp)))
                }
            }

            CmdType.DECK_GET_SUMMARIES_V2.value -> {
                val decks = deckRepository.findAllForPlayer(playerId)
                val summaries = buildJsonArray { decks.forEach { add(DeckWireBuilder.toV2Summary(it)) } }
                log.info("Front Door: DeckSummariesV2 ({} decks)", decks.size)
                val resp = buildJsonObject { put("Summaries", summaries) }
                writer.send(ctx, txId, FdResponse.Json(lenientJson.encodeToString(JsonObject.serializer(), resp)))
            }

            CmdType.DECK_GET_SUMMARIES_V3.value -> {
                val decks = deckRepository.findAllForPlayer(playerId)
                val summaries = buildJsonArray { decks.forEach { add(DeckWireBuilder.toStartHookSummary(it)) } }
                val response = buildJsonObject { put("Summaries", summaries) }
                log.info("Front Door: DeckSummariesV3 ({} decks)", decks.size)
                writer.send(ctx, txId, FdResponse.Json(response.toString()))
            }

            CmdType.CARD_GET_ALL.value -> {
                val collection = collectionService.getCollection(playerId)
                log.info("Front Door: CardGetAllCards ({} cards)", collection.size)
                writer.send(ctx, txId, FdResponse.Json(collectionService.toJson(collection)))
            }

            CmdType.EVENT_JOIN.value -> {
                val req = FdRequests.parseEventJoin(json)
                val eventName = req?.eventName
                log.info("Front Door: Event_Join event={}", eventName)
                if (eventName != null) {
                    val course =
                        if (EventRegistry.isDraft(eventName)) {
                            draftService.joinDraft(playerId, eventName)
                        } else {
                            courseService.join(playerId, eventName)
                        }
                    writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildJoinResponse(course)))
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.EVENT_DROP.value -> {
                val req = FdRequests.parseEventName(json)
                log.info("Front Door: Event_Drop event={}", req?.eventName)
                respondForEvent(
                    ctx,
                    txId,
                    req?.eventName,
                    "Event_Drop",
                    FdResponse.Json("{}"),
                    ::dropEvent,
                )
            }

            CmdType.EVENT_ENTER_PAIRING.value -> {
                val req = FdRequests.parseEnterPairing(json)
                val eventName = req?.eventName
                log.info("Front Door: Event_EnterPairing event={}", eventName)

                try {
                    if (eventName != null) coordinator.selectEvent(eventName)

                    // Resolve deck: course-based (sealed/draft) or selected (constructed)
                    val course = if (eventName != null) courseService.getCourse(playerId, eventName) else null
                    val courseDeckId = course?.deck?.deckId?.value
                    val deckId = courseDeckId ?: eventName?.let { selectedDeckByEvent[it] }
                    if (deckId != null) coordinator.selectDeck(deckId)

                    if (pairingService != null) {
                        require(eventName != null && deckId != null) { "An event and owned deck are required for pairing" }
                        val cards =
                            coordinator.resolveDeckCards(deckId)
                                ?: throw IllegalArgumentException("Selected deck is unavailable")
                        pairingService.queue(connectionId, playerId, checkNotNull(account).displayName, eventName, cards) { seat ->
                            ctx.executor().execute {
                                if (ctx.channel().isActive) FrontDoorMatchNotifications.sendPaired(ctx, writer, seat)
                            }
                        }
                        writer.send(ctx, txId, FdResponse.Json("""{"CurrentModule":"CreateMatch","Payload":"Success"}"""))
                        return
                    }

                    // Ack immediately — spinner shows while waiting for MatchCreated push
                    writer.send(ctx, txId, FdResponse.Json("""{"CurrentModule":"CreateMatch","Payload":"Success"}"""))

                    val match =
                        if (courseDeckId != null) {
                            matchmaking.createMatchInfo(eventName.orEmpty())
                        } else if (eventName != null) {
                            MatchInfo(
                                matchmaking.createMatchId(eventName),
                                matchmaking.matchDoorHost,
                                matchmaking.matchDoorPort,
                                eventName,
                            )
                        } else {
                            matchmaking.startMatch(DeckId(deckId.orEmpty()), "")
                        }
                    sendMatchCreated(ctx, match)
                } catch (e: IllegalArgumentException) {
                    log.warn("Front Door: Event_EnterPairing rejected — {}", e.message)
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.EVENT_LEAVE_PAIRING.value -> {
                pairingService?.leave(connectionId)
                val req = FdRequests.parseEventName(json)
                log.info("Front Door: Event_LeavePairing event={}", req?.eventName)
                writer.send(ctx, txId, FdResponse.Empty)
            }

            CmdType.EVENT_RESIGN.value -> {
                val req = FdRequests.parseEventName(json)
                log.info("Front Door: Event_Resign event={}", req?.eventName)
                respondForEvent(ctx, txId, req?.eventName, "Event_Resign", action = ::dropEvent)
            }

            CmdType.EVENT_CLAIM_PRIZE.value -> {
                val req = FdRequests.parseEventName(json)
                val eventName = req?.eventName
                log.info("Front Door: Event_ClaimPrize event={}", eventName)
                respondForEvent(ctx, txId, eventName, "Event_ClaimPrize") {
                    FdResponse.Json(
                        EventWireBuilder.buildClaimPrizeResponse(
                            courseService.claimPrize(playerId, it),
                        ),
                    )
                }
            }

            CmdType.EVENT_GET_MATCH_RESULT.value -> {
                val req = FdRequests.parseMatchResult(json)
                log.info("Front Door: Event_GetMatchResultReport event={}", req?.eventName)
                val eventName = req?.eventName
                val course = if (eventName != null) courseService.getCourse(playerId, eventName) else null
                if (course != null) {
                    writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildMatchResultReport(course)))
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.EVENT_SET_JUMPSTART_PACKET.value -> {
                error("EVENT_SET_JUMPSTART_PACKET: not supported — should not be reachable in local mode")
            }

            CmdType.BOT_DRAFT_START.value -> {
                val req = FdRequests.parseEventName(json)
                val eventName = req?.eventName
                log.info("Front Door: BotDraft_StartDraft event={}", eventName)
                if (eventName != null) {
                    val session = draftService.startDraft(playerId, eventName)
                    writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.BOT_DRAFT_PICK.value -> {
                val req = FdRequests.parseDraftPick(json)
                log.info("Front Door: BotDraft_DraftPick card={}", req?.cardId)
                if (req != null) {
                    val session = draftService.pick(playerId, req.eventName, req.cardId)
                    writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            CmdType.BOT_DRAFT_STATUS.value -> {
                val req = FdRequests.parseEventName(json)
                val eventName = req?.eventName
                log.info("Front Door: BotDraft_DraftStatus event={}", eventName)
                if (eventName != null) {
                    val session = draftService.getStatus(playerId, eventName)
                    if (session != null) {
                        writer.send(ctx, txId, FdResponse.Json(DraftWireBuilder.buildDraftResponse(session)))
                    } else {
                        writer.send(ctx, txId, FdResponse.Empty)
                    }
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            // Stub: client sends these after last BotDraft pick as fallback when our
            // completed-draft response doesn't fully convince it the draft ended.
            // Keep as no-op until the completed-draft response shape is tighter.
            CmdType.EVENT_PLAYER_DRAFT_CONFIRM_CARD_POOL_GRANT.value,
            CmdType.DRAFT_COMPLETE_DRAFT.value,
            -> {
                log.warn("Front Door: stub no-op for CmdType {} ({})", cmdType, CmdType.nameOf(cmdType))
                writer.send(ctx, txId, FdResponse.Empty)
            }

            CmdType.EVENT_SET_DECK_V2.value, CmdType.EVENT_SET_COURSE_DECK.value -> {
                // 622 = legacy `Event_SetDeckV2`; 627 = Arena 58-0-1 `Event_SetCourseDeck`.
                // Same model: attach a deck to the course; differ only in JSON envelope shape
                // (`Deck.{MainDeck,Sideboard}` for 622, `MainDeck`/`Sideboard` at top for 627).
                val req = FdRequests.parseSetDeck(json)
                val existing = req?.deckId?.let { deckRepository.findById(DeckId(it)) }
                if (existing != null && existing.playerId != playerId) {
                    writer.send(ctx, txId, FdResponse.Empty)
                    return
                }
                if (req != null && req.deckId != null) {
                    selectedDeckByEvent[req.eventName] = req.deckId
                }
                val cmdName = if (cmdType == CmdType.EVENT_SET_COURSE_DECK.value) "Event_SetCourseDeck" else "Event_SetDeckV2"
                log.info("Front Door: {} event={} deck={}", cmdName, req?.eventName, req?.deckId)
                if (req != null) {
                    try {
                        val resolvedDeckId = DeckId(req.deckId ?: UUID.randomUUID().toString())
                        val deck =
                            CourseDeck(
                                deckId = resolvedDeckId,
                                mainDeck = req.mainDeck,
                                sideboard = req.sideboard,
                            )
                        val summary =
                            CourseDeckSummary(
                                deckId = resolvedDeckId,
                                name = req.deckName ?: "Draft Deck",
                                tileId = req.tileId ?: 0,
                                format = req.deckFormat ?: "Limited",
                                deckArtId = req.deckArtId ?: 0,
                                preferredSleeve = req.preferredSleeve.orEmpty(),
                            )
                        val course = courseService.setDeck(playerId, req.eventName, deck, summary)
                        writer.send(ctx, txId, FdResponse.Json(EventWireBuilder.buildCourseJson(course).toString()))
                    } catch (e: IllegalArgumentException) {
                        log.warn("Front Door: {} failed: {}", cmdName, e.message)
                        writer.send(ctx, txId, FdResponse.Empty)
                    }
                } else {
                    writer.send(ctx, txId, FdResponse.Empty)
                }
            }

            // Response-type envelope (field 1 is UUID, not varint) — no CmdType extracted.
            // Legacy fallback: may be dead now that FdEnvelope handles CmdType=0 (Authenticate).
            // Each branch duplicates a named CmdType handler above. Track hits to confirm removal.
            null -> {
                if (json == null) return
                when {
                    "GraphId" in json -> {
                        log.info("Front Door: graph (fallback, no CmdType) — txId={}", txId)
                        handleGraphRequest(ctx, txId, json)
                    }
                    "AIBotMatch" in json || "PlayQueue" in json -> {
                        log.info("Front Door: AI match (fallback, no CmdType) — txId={}", txId)
                        val match = matchmaking.startAiMatch(playerId, DeckId(""))
                        sendMatchCreated(ctx, match)
                    }
                    else -> {
                        log.info("Front Door: unrecognized request without CmdType")
                        writer.send(ctx, txId, FdResponse.Empty)
                    }
                }
            }

            else -> {
                log.warn("Front Door: UNHANDLED CmdType {} ({})", cmdType, CmdType.nameOf(cmdType))
                writer.send(ctx, txId, FdResponse.Empty)
            }
        }
    }

    // --- Helpers ---

    private fun ownedDeck(deckId: String): Deck? = deckRepository.findById(DeckId(deckId))?.takeIf { it.playerId == playerId }

    private fun saveOwnedDeck(deck: Deck): Boolean =
        synchronized(deckRepository) {
            val existing = deckRepository.findById(deck.id)
            if (existing != null && existing.playerId != playerId) {
                false
            } else {
                deckRepository.save(deck)
                true
            }
        }

    private fun respondForEvent(
        ctx: ChannelHandlerContext,
        txId: String?,
        eventName: String?,
        operation: String,
        failureResponse: FdResponse = FdResponse.Empty,
        action: (String) -> FdResponse,
    ) {
        val response =
            if (eventName == null) {
                failureResponse
            } else {
                try {
                    action(eventName)
                } catch (e: IllegalArgumentException) {
                    log.debug("Front Door: {} failed: {}", operation, e.message)
                    failureResponse
                }
            }
        writer.send(ctx, txId, response)
    }

    private fun dropEvent(eventName: String): FdResponse {
        val course =
            if (EventRegistry.isDraft(eventName)) {
                draftService.drop(playerId, eventName)
            } else {
                courseService.drop(playerId, eventName)
            }
        return FdResponse.Json(EventWireBuilder.buildCourseJson(course).toString())
    }

    private inline fun requireJson(
        ctx: ChannelHandlerContext,
        txId: String?,
        json: String?,
        block: (String) -> Unit,
    ) {
        if (json == null) {
            log.warn("Front Door: expected JSON payload, got null")
            writer.send(ctx, txId, FdResponse.Empty)
            return
        }
        block(json)
    }

    private fun sendMatchCreated(
        ctx: ChannelHandlerContext,
        match: MatchInfo,
        yourSeat: Int = 1,
    ) {
        val commanders = coordinator.selectedDeckId?.let { ownedDeck(it)?.commandZone?.map { card -> card.grpId } }.orEmpty()
        FrontDoorMatchNotifications.sendBot(ctx, writer, match, checkNotNull(account).displayName, commanders, yourSeat)
    }

    private fun handleGraphRequest(
        ctx: ChannelHandlerContext,
        transactionId: String?,
        json: String?,
    ) {
        val graphId = json?.let { GRAPH_ID_PATTERN.find(it)?.groupValues?.get(1) } ?: "unknown"
        log.info("Front Door: GraphState graphId={}", graphId)
        val response = bootstrapData.graphStateResponses[graphId] ?: GRAPH_DEFAULT
        writer.send(ctx, transactionId, FdResponse.Json(response))
    }

    override fun exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable,
    ) {
        log
            .atError()
            .setCause(cause)
            .addKeyValue("event", "frontdoor.request_failed")
            .addKeyValue("subsystem", "frontdoor")
            .addKeyValue("request", "channel")
            .log("Front Door request failed")
        ctx.close()
    }

    companion object {
        private val GRAPH_ID_PATTERN = Regex(""""GraphId"\s*:\s*"([^"]+)"""")

        /** Fallback for unknown graph IDs — not expected in normal flow. Known graphs need real state files. */
        private const val GRAPH_DEFAULT = """{"NodeStates":{},"MilestoneStates":{}}"""
    }
}
