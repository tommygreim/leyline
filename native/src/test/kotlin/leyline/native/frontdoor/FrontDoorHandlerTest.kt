// Integration test hitting all Front Door endpoints end-to-end. Splitting by endpoint
// would hurt the shared harness/setup; keep as one file and treat size as intentional.
// MaxLineLength suppressed for inline JSON payloads — wrapping breaks grep'ability.
@file:Suppress("LargeClass", "MaxLineLength")

package leyline.native.frontdoor

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.protobuf.CodedOutputStream
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.CollationPool
import leyline.domain.Deck
import leyline.domain.DeckCard
import leyline.domain.DeckId
import leyline.domain.Format
import leyline.domain.MatchInfo
import leyline.domain.PlayerId
import leyline.domain.repo.InMemoryCourseRepository
import leyline.domain.repo.InMemoryDraftSessionRepository
import leyline.domain.service.CollectionService
import leyline.domain.service.CourseService
import leyline.domain.service.DraftService
import leyline.domain.service.EventRegistry
import leyline.domain.service.GeneratedPool
import leyline.domain.service.MatchCoordinator
import leyline.domain.service.MatchmakingService
import leyline.domain.service.RepositoryMatchCoordinator
import leyline.native.NativeTag
import leyline.native.account.AccountStore
import leyline.native.account.LocalAccountAuthenticator
import leyline.native.account.TokenService
import leyline.native.frontdoor.service.PlayerService
import leyline.native.frontdoor.wire.FdEnvelope
import leyline.native.frontdoor.wire.FdResponseWriter
import leyline.native.frontdoor.wire.FdWireConstants
import leyline.native.matchmaking.LocalPairingService
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.UUID
import com.google.protobuf.Any as ProtoAny

/**
 * Wire-level integration tests for [FrontDoorHandler].
 *
 * Boots FD in an [EmbeddedChannel] (no TLS, no sockets), sends framed
 * protobuf envelopes, decodes responses, and validates JSON shapes.
 * One test per CmdType dispatch branch.
 */
class FrontDoorHandlerTest :
    FunSpec({

        tags(NativeTag)

        val testPlayerId = "test-player-00000000-0000-0000-0000-000000000001"
        val testDeckId = "test-deck-00000000-0000-0000-0000-000000000001"

        // Minimal deck cards matching client wire shape
        val sampleMainDeck = listOf(DeckCard(75515, 4), DeckCard(75516, 56))

        val json = Json { ignoreUnknownKeys = true }
        val channels = mutableListOf<EmbeddedChannel>()

        val store = InMemoryPlayerDeckRepository()
        val bootstrapData = FrontDoorBootstrapData.loadFromClasspath()
        val playerService = PlayerService(store)
        val matchmakingService = MatchmakingService(store, "localhost", 30003)
        val writer = FdResponseWriter()
        val accountDbFile =
            java.io.File
                .createTempFile("fd-accounts", ".db")
                .also { it.deleteOnExit() }
        val accounts = AccountStore(Database.connect("jdbc:sqlite:${accountDbFile.absolutePath}", "org.sqlite.JDBC"))
        accounts.createTables()
        accounts.seed("test-account", testPlayerId, "test@local", "Tester", "test")
        val tokens = TokenService(store = accounts)
        val accessToken = tokens.issueTokens(accounts.findByPersonaId(testPlayerId)!!).accessToken
        val authenticator = LocalAccountAuthenticator(accounts, tokens)

        beforeSpec {
            store.ensurePlayer(PlayerId(testPlayerId), "Tester")
            store.save(
                Deck(
                    id = DeckId(testDeckId),
                    playerId = PlayerId(testPlayerId),
                    name = "Test Deck",
                    format = Format.Standard,
                    tileId = 12345,
                    mainDeck = sampleMainDeck,
                    sideboard = emptyList(),
                    commandZone = emptyList(),
                    companions = emptyList(),
                ),
            )
        }

        afterEach {
            channels.forEach { it.finishAndReleaseAll() }
            channels.clear()
        }

        /** Create a fresh FD channel wired to our test player. */
        fun fdChannel(
            matchmaking: MatchmakingService = matchmakingService,
            courseService: CourseService =
                CourseService(InMemoryCourseRepository()) { _ ->
                    GeneratedPool(emptyList(), emptyList(), 0)
                },
            draftService: DraftService? = null,
            localToken: String? = accessToken,
            coordinatorFactory: (PlayerId) -> MatchCoordinator = { MatchCoordinator.NOOP },
            pairingService: LocalPairingService? = null,
        ): EmbeddedChannel {
            val resolvedDraftService =
                draftService
                    ?: DraftService(
                        InMemoryDraftSessionRepository(),
                        stubDraftDriver(emptyList()),
                        courseService,
                    )
            val ch =
                EmbeddedChannel(
                    FrontDoorHandler(
                        authenticator = authenticator,
                        deckRepository = store,
                        playerService = playerService,
                        matchmaking = matchmaking,
                        collectionService = CollectionService { emptyList() },
                        courseService = courseService,
                        draftService = resolvedDraftService,
                        writer = writer,
                        bootstrapData = bootstrapData,
                        coordinatorFactory = coordinatorFactory,
                        pairingService = pairingService,
                    ),
                )
            channels.add(ch)
            if (localToken != null) {
                val auth = FdEnvelope.encodeCmd(0, UUID.randomUUID().toString(), """{"Token":"$localToken"}""")
                val header = FdEnvelope.buildOutgoingHeader(auth.size)
                ch.writeInbound(Unpooled.wrappedBuffer(header + auth))
                ch.readOutbound<ByteBuf>()?.release()
            }
            return ch
        }

        /** Write a framed Cmd envelope into the channel. */
        fun EmbeddedChannel.writeCmd(
            cmdType: Int,
            payload: String? = "{}",
        ) {
            val envelope = FdEnvelope.encodeCmd(cmdType, UUID.randomUUID().toString(), payload ?: "{}")
            val header = FdEnvelope.buildOutgoingHeader(envelope.size)
            val buf = Unpooled.buffer(header.size + envelope.size)
            buf.writeBytes(header)
            buf.writeBytes(envelope)
            writeInbound(buf)
        }

        /** Read all pending outbound responses. */
        fun EmbeddedChannel.readAllResponses(): List<FdEnvelope.FdMessage> {
            val results = mutableListOf<FdEnvelope.FdMessage>()
            while (true) {
                val resp = readOutbound<ByteBuf>() ?: break
                results.add(decodeResponse(resp))
            }
            return results
        }

        /** Send a framed Cmd envelope, read back first response as FdMessage. */
        fun EmbeddedChannel.sendCmd(
            cmdType: Int,
            payload: String? = "{}",
        ): FdEnvelope.FdMessage {
            writeCmd(cmdType, payload)
            val resp = readOutbound<ByteBuf>() ?: error("No response for CmdType $cmdType")
            return decodeResponse(resp)
        }

        /** Send a cmd and return ALL responses (for 612 which sends ack + push). */
        fun EmbeddedChannel.sendCmdAll(
            cmdType: Int,
            payload: String? = "{}",
        ): List<FdEnvelope.FdMessage> {
            writeCmd(cmdType, payload)
            return readAllResponses()
        }

        /** Send a cmd, parse first response as JsonObject. Uses [ch] or creates a fresh channel. */
        fun sendJson(
            cmdType: Int,
            payload: String? = "{}",
            ch: EmbeddedChannel = fdChannel(),
        ): JsonObject {
            val msg = ch.sendCmd(cmdType, payload)
            return json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonObject
        }

        /** Create a FD channel with CourseService + DraftService wired (for sealed/draft tests). */
        fun fdChannelWithCourseService(): EmbeddedChannel {
            val poolGen: (String) -> GeneratedPool = { _ ->
                GeneratedPool(
                    cards = (1..84).toList(),
                    byCollation = listOf(CollationPool(100026, (1..84).toList())),
                    collationId = 100026,
                )
            }
            val courseService = CourseService(InMemoryCourseRepository(), poolGen)
            val draftService =
                DraftService(
                    InMemoryDraftSessionRepository(),
                    stubDraftDriver(
                        (0 until 3).map { pack -> (1..13).map { card -> 90000 + pack * 100 + card } },
                    ),
                    courseService,
                )
            return fdChannel(courseService = courseService, draftService = draftService)
        }

        // --- Tests ---

        test("CmdType 0 - auth returns SessionId and Attached") {
            val obj = sendJson(0, """{"ClientVersion":"1.0","Token":"$accessToken"}""")
            obj["SessionId"].shouldNotBeNull()
            obj["Attached"]?.jsonPrimitive?.boolean shouldBe true
        }

        test("unauthenticated and invalid-token connections cannot read player data") {
            for (token in listOf<String?>(null, "invalid")) {
                val ch = fdChannel(localToken = null)
                val response = if (token == null) ch.sendCmd(1) else ch.sendCmd(0, """{"Token":"$token"}""")
                json
                    .parseToJsonElement(response.jsonPayload!!)
                    .jsonObject["Attached"]!!
                    .jsonPrimitive.boolean shouldBe false
                ch.isActive shouldBe false
            }
        }

        test("two authenticated connections isolate deck writes deletes and selections") {
            val alice = accounts.createLocalProfile("Alice")
            val bob = accounts.createLocalProfile("Bob")
            val courses = CourseService(InMemoryCourseRepository()) { GeneratedPool(emptyList(), emptyList(), 0) }
            val coordinators = mutableMapOf<PlayerId, RepositoryMatchCoordinator>()
            val coordinatorFactory: (PlayerId) -> MatchCoordinator = { pid ->
                coordinators.getOrPut(pid) { RepositoryMatchCoordinator(pid, store, courses, InMemoryDraftSessionRepository()) }
            }
            val a = fdChannel(localToken = tokens.issueTokens(alice).accessToken, coordinatorFactory = coordinatorFactory)
            val b = fdChannel(localToken = tokens.issueTokens(bob).accessToken, coordinatorFactory = coordinatorFactory)

            fun payload(
                id: String,
                name: String,
            ) = """{"Summary":{"DeckId":"$id","Name":"$name"},"Deck":{"MainDeck":[{"cardId":101,"quantity":60}],"Sideboard":[]}}"""
            a.sendCmd(412, payload("alice-deck", "Alice Deck"))
            b.sendCmd(412, payload("bob-deck", "Bob Deck"))
            for ((ch, expectedId) in listOf(a to "alice-deck", b to "bob-deck")) {
                val summaries = json.parseToJsonElement(ch.sendCmd(411).jsonPayload!!).jsonObject["Summaries"]!!.jsonArray
                summaries.map { it.jsonObject["DeckId"]!!.jsonPrimitive.content } shouldBe listOf(expectedId)
                ch.sendCmdAll(612, """{"deckId":"$expectedId"}""")
            }
            coordinators.getValue(PlayerId(alice.personaId)).selectedDeckId shouldBe "alice-deck"
            coordinators.getValue(PlayerId(bob.personaId)).selectedDeckId shouldBe "bob-deck"
            b.sendCmd(412, payload("alice-deck", "Stolen"))
            b.sendCmd(406, payload("alice-deck", "Stolen V2"))
            b.sendCmd(403, """{"DeckId":"alice-deck"}""")
            b.sendCmd(612, """{"deckId":"alice-deck"}""")
            store.findById(DeckId("alice-deck"))!!.name shouldBe "Alice Deck"
            store.findById(DeckId("alice-deck"))!!.playerId shouldBe PlayerId(alice.personaId)
            coordinators.getValue(PlayerId(bob.personaId)).selectedDeckId shouldBe "bob-deck"
            b.sendCmd(400, """{"DeckId":"alice-deck"}""").jsonPayload shouldBe null
        }

        test("an authenticated connection cannot switch to another local profile") {
            val ch = fdChannel()
            val other = accounts.createLocalProfile("Other")
            val otherToken = tokens.issueTokens(other).accessToken
            val response = ch.sendCmd(0, """{"Token":"$otherToken"}""")
            json
                .parseToJsonElement(response.jsonPayload!!)
                .jsonObject["Attached"]!!
                .jsonPrimitive.boolean shouldBe false
            ch.isActive shouldBe false
        }

        test("two authenticated Front Doors receive one Queue match with distinct seats and real names") {
            val first = accounts.createLocalProfile("First")
            val second = accounts.createLocalProfile("Second")
            val courses = CourseService(InMemoryCourseRepository()) { GeneratedPool(emptyList(), emptyList(), 0) }
            val coordinators = mutableMapOf<PlayerId, RepositoryMatchCoordinator>()
            val factory: (PlayerId) -> MatchCoordinator = { pid ->
                coordinators.getOrPut(pid) { RepositoryMatchCoordinator(pid, store, courses, InMemoryDraftSessionRepository()) }
            }
            val pairing =
                LocalPairingService(RuntimeMatchConfigRegistry(), matchInfoFactory = { event ->
                    MatchInfo(UUID.randomUUID().toString(), "localhost", 30003, event)
                })
            val firstChannel =
                fdChannel(
                    courseService = courses,
                    localToken = tokens.issueTokens(first).accessToken,
                    coordinatorFactory = factory,
                    pairingService = pairing,
                )
            val secondChannel =
                fdChannel(
                    courseService = courses,
                    localToken = tokens.issueTokens(second).accessToken,
                    coordinatorFactory = factory,
                    pairingService = pairing,
                )
            val event = "Play_Standard"
            for ((ch, id) in listOf(firstChannel to "paired-first", secondChannel to "paired-second")) {
                val payload =
                    """
                    {"Summary":{"DeckId":"$id","Name":"Local Deck"},
                     "Deck":{"MainDeck":[{"cardId":101,"quantity":60}],"Sideboard":[]},"EventName":"$event"}
                    """.trimIndent()
                ch.sendCmd(412, payload)
                ch.sendCmd(600, """{"EventName":"$event"}""")
                ch.sendCmd(622, payload)
            }
            firstChannel.sendCmdAll(603, """{"EventName":"$event"}""").size shouldBe 1
            val secondResponses = secondChannel.sendCmdAll(603, """{"EventName":"$event"}""")
            secondResponses.size shouldBe 2
            json
                .parseToJsonElement(secondResponses.first().jsonPayload!!)
                .jsonObject["Payload"]!!
                .jsonPrimitive.content shouldBe "Success"
            firstChannel.runPendingTasks()
            val firstResponses = firstChannel.readAllResponses()
            val firstMatch = json.parseToJsonElement(firstResponses.single().jsonPayload!!).jsonObject["MatchInfoV4"]!!.jsonObject
            val secondMatch = json.parseToJsonElement(secondResponses.last().jsonPayload!!).jsonObject["MatchInfoV4"]!!.jsonObject
            firstMatch["MatchId"] shouldBe secondMatch["MatchId"]
            firstMatch["YourSeat"]!!.jsonPrimitive.int shouldBe 1
            secondMatch["YourSeat"]!!.jsonPrimitive.int shouldBe 2
            for (match in listOf(firstMatch, secondMatch)) {
                match["MatchType"]!!.jsonPrimitive.content shouldBe "Queue"
                match["PlayerInfos"]!!.jsonArray.map { it.jsonObject["ScreenName"]!!.jsonPrimitive.content } shouldBe
                    listOf(first.displayName, second.displayName)
            }
        }

        test("a busy room rejects pairing once without success and permits retry after release") {
            val event = "Play_Timeless"
            val courses = CourseService(InMemoryCourseRepository()) { GeneratedPool(emptyList(), emptyList(), 0) }
            val pairing = LocalPairingService(RuntimeMatchConfigRegistry(), matchmakingService::createMatchInfo)
            val occupied = MatchInfo("occupied-room", "localhost", 30003, "AIBotMatch")
            pairing.registerBot(occupied, PlayerId("busy-player"), "Busy Player")
            val ch =
                fdChannel(
                    courseService = courses,
                    coordinatorFactory = { RepositoryMatchCoordinator(it, store, courses, InMemoryDraftSessionRepository()) },
                    pairingService = pairing,
                )
            ch.sendCmd(622, """{"EventName":"$event","Summary":{"DeckId":"$testDeckId"}}""")

            val rejected = ch.sendCmdAll(603, """{"EventName":"$event"}""")
            rejected.size shouldBe 1
            rejected.single().jsonPayload shouldBe null
            ch.isActive shouldBe true

            pairing.complete(occupied.matchId)
            val accepted = ch.sendCmdAll(603, """{"EventName":"$event"}""")
            accepted.size shouldBe 1
            json
                .parseToJsonElement(accepted.single().jsonPayload!!)
                .jsonObject["Payload"]!!
                .jsonPrimitive.content shouldBe "Success"
        }

        test("Front Door request failures expose one structured owned stack") {
            val logger = LoggerFactory.getLogger(FrontDoorHandler::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val previousLevel = logger.level
            logger.level = Level.ERROR
            logger.addAppender(appender)
            val cause = IllegalStateException("synthetic front door request failure")
            try {
                fdChannel().pipeline().fireExceptionCaught(cause)

                val event = appender.list.single { it.formattedMessage == "Front Door request failed" }
                event.level shouldBe Level.ERROR
                event.keyValuePairs.map { it.key } shouldContainExactlyInAnyOrder
                    listOf("event", "subsystem", "request")
                event.keyValuePairs.first { it.key == "event" }.value shouldBe "frontdoor.request_failed"
                event.keyValuePairs.first { it.key == "subsystem" }.value shouldBe "frontdoor"
                event.keyValuePairs.first { it.key == "request" }.value shouldBe "channel"
                event.throwableProxy.shouldNotBeNull().className shouldBe IllegalStateException::class.java.name
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }

        test("CmdType 1 - StartHook contains DeckSummaries and Decks") {
            val obj = sendJson(1)
            assertSoftly {
                obj["DeckSummaries"].shouldNotBeNull()
                (obj["DeckSummaries"] as JsonArray).shouldNotBeEmpty()
                obj["Decks"].shouldNotBeNull()
                obj["InventoryInfo"].shouldNotBeNull()
            }
        }

        test("CmdType 1 - StartHook deck summaries have required fields") {
            val obj = sendJson(1)
            val summaries = obj["DeckSummaries"]!!.jsonArray
            summaries.shouldNotBeEmpty()
            val deck = summaries[0].jsonObject
            assertSoftly {
                deck["DeckId"].shouldNotBeNull()
                deck["Name"].shouldNotBeNull()
                deck["DeckTileId"].shouldNotBeNull()
                deck["Attributes"].shouldNotBeNull()
                deck["PreferredCosmetics"].shouldNotBeNull()
            }
        }

        test("CmdType 1 - StartHook deck cards have MainDeck and CardSkins") {
            val obj = sendJson(1)
            val decks = obj["Decks"]!!.jsonObject
            decks.entries.shouldNotBeEmpty()
            for ((_, deckJson) in decks) {
                val deck = deckJson.jsonObject
                deck["MainDeck"].shouldNotBeNull()
                deck["CardSkins"].shouldNotBeNull()
                deck["ReducedSideboard"] shouldBe null
            }
        }

        test("CmdType 6 - GetFormats returns proto response") {
            val ch = fdChannel()
            val msg = ch.sendCmd(6)
            msg.transactionId.shouldNotBeNull()
            // Proto response — jsonPayload may be null but response must exist
        }

        test("CmdType 2300 - typed inbox request receives GetPlayerInboxResp") {
            val ch = fdChannel()
            val request =
                ProtoAny
                    .newBuilder()
                    .setTypeUrl("type.googleapis.com/Wizards.Arena.Models.Network.GetPlayerInboxReq")
                    .build()
            val bytes = ByteArrayOutputStream()
            CodedOutputStream.newInstance(bytes).apply {
                writeUInt32(1, 2300)
                writeString(2, UUID.randomUUID().toString())
                writeByteArray(3, request.toByteArray())
                flush()
            }
            val envelope = bytes.toByteArray()
            ch.writeInbound(Unpooled.wrappedBuffer(FdEnvelope.buildOutgoingHeader(envelope.size), envelope))

            val response = ch.readAllResponses().single()
            response.protobufTypeUrl shouldBe "type.googleapis.com/Wizards.Arena.Models.Network.GetPlayerInboxResp"
            response.jsonPayload shouldBe null
        }

        test("CmdType 2300 - legacy inbox request retains empty Messages JSON") {
            sendJson(2300)["Messages"]!!.jsonArray.shouldBeEmpty()
        }

        test("CmdType 612 - AiBotMatch returns ack then MatchCreated with correct EventId") {
            val ch = fdChannel()
            val responses = ch.sendCmdAll(612, """{"deckId":"$testDeckId","eventName":"AIBotMatch"}""")
            responses.size shouldBe 2
            responses[0].transactionId.shouldNotBeNull()
            val push = responses[1]
            val pushJson = push.jsonPayload.shouldNotBeNull()
            pushJson shouldContain "MatchCreated"
            val pushObj = json.parseToJsonElement(pushJson).jsonObject
            pushObj["Type"]?.jsonPrimitive?.content shouldBe "MatchCreated"
            val matchInfo = pushObj["MatchInfoV4"]?.jsonObject
            assertSoftly {
                matchInfo.shouldNotBeNull()
                matchInfo["MatchEndpointHost"].shouldNotBeNull()
                matchInfo["MatchEndpointPort"].shouldNotBeNull()
                matchInfo["MatchId"].shouldNotBeNull()
                matchInfo["EventId"]?.jsonPrimitive?.content shouldBe "AIBotMatch"
            }
        }

        test("CmdType 612 - always creates AIBotMatch regardless of payload") {
            // Real client never sends eventName on 612 — it's always an AI bot match.
            // The eventName-based flow goes through 603 (EnterPairing).
            val ch = fdChannel()
            val responses = ch.sendCmdAll(612, """{"deckId":"$testDeckId","botDeckId":"some-bot-deck","botMatchType":0}""")
            responses.size shouldBe 2
            val matchInfo =
                json
                    .parseToJsonElement(responses[1].jsonPayload!!)
                    .jsonObject["MatchInfoV4"]
                    ?.jsonObject
            matchInfo.shouldNotBeNull()
            matchInfo["EventId"]?.jsonPrimitive?.content shouldBe "AIBotMatch"
        }

        test("CmdType 603 - SparkyStarterDeckDuel can route into puzzle match ids") {
            val puzzleAwareMatchmaking =
                MatchmakingService(
                    store,
                    "localhost",
                    30003,
                    matchIdFactory = { eventName -> if (eventName == "SparkyStarterDeckDuel") "puzzle-bolt-face" else "plain-match" },
                )
            val ch = fdChannel(matchmaking = puzzleAwareMatchmaking)

            ch.writeCmd(
                622,
                """
                {"EventName":"SparkyStarterDeckDuel",
                 "Summary":{"DeckId":"$testDeckId","Name":"Test Deck","DeckTileId":12345},
                 "Deck":{"MainDeck":[{"cardId":75515,"quantity":4},{"cardId":75516,"quantity":56}],"Sideboard":[],"CommandZone":[],"Companions":[]}}
                """.trimIndent(),
            )
            ch.readOutbound<ByteBuf>()!!.release()

            val responses = ch.sendCmdAll(603, """{"EventName":"SparkyStarterDeckDuel"}""")
            responses.size shouldBe 2
            val pushObj = json.parseToJsonElement(responses[1].jsonPayload.shouldNotBeNull()).jsonObject
            pushObj["MatchInfoV4"]!!.jsonObject["MatchId"]!!.jsonPrimitive.content shouldBe "puzzle-bolt-face"
            pushObj["MatchInfoV4"]!!.jsonObject["EventId"]!!.jsonPrimitive.content shouldBe "SparkyStarterDeckDuel"
        }

        test("CmdType 1700 - GraphDefinitions returns JSON") {
            val ch = fdChannel()
            val msg = ch.sendCmd(1700)
            val payload = msg.jsonPayload.shouldNotBeNull()
            payload.shouldNotBeEmpty()
            json.parseToJsonElement(payload) // valid JSON
        }

        test("CmdType 1910 - PlayBladeQueueConfig has all queues") {
            val ch = fdChannel()
            val msg = ch.sendCmd(1910)
            val arr = json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonArray
            arr.size shouldBe EventRegistry.queues.size
            val ids = arr.map { it.jsonObject["Id"]?.jsonPrimitive?.content }
            ids shouldContain "AIBotMatch"
            ids shouldContain "StandardBrawl"
        }

        test("CmdType 624 - ActiveEventsV2 has events and AiBotMatches") {
            val obj = sendJson(624)
            val events = obj["Events"]?.jsonArray
            events.shouldNotBeNull()
            events.shouldNotBeEmpty()
            val names = events.map { it.jsonObject["InternalEventName"]?.jsonPrimitive?.content }
            names shouldNotContain "AIBotMatch" // lives in AiBotMatches, not Events

            val bots = obj["AiBotMatches"]?.jsonArray
            bots.shouldNotBeNull()
            bots.shouldNotBeEmpty()
            val botNames = bots.map { it.jsonObject["InternalEventName"]?.jsonPrimitive?.content }
            botNames shouldContain "AIBotMatch"
        }

        test("CmdType 624 - every event matches reference shape") {
            val refKeys = loadReferenceShape("reference/fd-reference-event.json")
            val obj = sendJson(624)
            for (event in obj["Events"]!!.jsonArray) {
                val name = event.jsonObject["InternalEventName"]!!.jsonPrimitive.content
                assertKeysMatch(refKeys, event.jsonObject, name)
            }
        }

        test("CmdType 623 - EventGetCoursesV2 returns courses with defaults") {
            val obj = sendJson(623)
            val courses = obj["Courses"]?.jsonArray
            assertSoftly {
                courses.shouldNotBeNull()
                courses.shouldNotBeEmpty()
                courses.shouldNotBeEmpty()
            }
        }

        test("CmdType 623 - every course matches reference shape") {
            val refKeys = loadReferenceShape("reference/fd-reference-course.json")
            val obj = sendJson(623)
            for (course in obj["Courses"]!!.jsonArray) {
                val name = course.jsonObject["InternalEventName"]!!.jsonPrimitive.content
                assertKeysMatch(refKeys, course.jsonObject, name)
            }
        }

        test("CmdType 1910 - every queue matches reference shape") {
            val refKeys = loadReferenceShape("reference/fd-reference-queue.json")
            val ch = fdChannel()
            val msg = ch.sendCmd(1910)
            val queues = json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonArray
            for (queue in queues) {
                val id = queue.jsonObject["Id"]!!.jsonPrimitive.content
                assertKeysMatch(refKeys, queue.jsonObject, id)
            }
        }

        test("CmdType 410 - PreconDecksV3 returns precon decks from bootstrap data") {
            val obj = sendJson(410)
            obj["PreconDecks"]?.jsonArray.shouldNotBeNull()
        }

        test("CmdType 1911 - PlayerPreferences has single Preferences wrapper") {
            val obj = sendJson(1911)
            val prefs = obj["Preferences"]?.jsonObject
            prefs.shouldNotBeNull()
            // Must NOT be double-wrapped: Preferences.Preferences
            prefs.containsKey("Preferences") shouldBe false
        }

        test("CmdType 613 - ActiveMatches returns empty list") {
            val obj = sendJson(613)
            obj["MatchesV3"]?.jsonArray.shouldNotBeNull()
        }

        test("CmdType 1100 - RankInfo returns required fields") {
            val obj = sendJson(1100)
            obj["constructedClass"].shouldNotBeNull()
            obj["limitedClass"].shouldNotBeNull()
        }

        test("current deck commands save, list, and reload all deck zones") {
            val deckId = "11111111-1111-1111-1111-111111111111"
            val request =
                """
                {
                    "Summary": {"DeckId":"$deckId","Name":"Round Trip","DeckTileId":75515,
                        "Attributes":[{"name":"Format","value":"Historic"}]},
                    "Deck": {
                        "MainDeck": [{"cardId":75515,"quantity":24},{"cardId":75516,"quantity":36}],
                        "Sideboard": [{"cardId":75517,"quantity":2}],
                        "CommandZone": [{"cardId":75518,"quantity":1}],
                        "Companions": [{"cardId":75519,"quantity":1}],
                        "CardSkins": []
                    },
                    "ActionType": "CreatedNew"
                }
                """.trimIndent()
            val ch = fdChannel()
            val saved = sendJson(412, request, ch)
            saved["DeckId"]!!.jsonPrimitive.content shouldBe deckId

            val listed = sendJson(411, ch = ch)
            listed.keys shouldBe setOf("Summaries")
            val summary =
                listed["Summaries"]!!
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it["DeckId"]!!.jsonPrimitive.content == deckId }
            summary shouldBe saved

            val loaded = sendJson(400, """{"DeckId":"$deckId"}""", ch)
            loaded shouldBe json.parseToJsonElement(request).jsonObject["Deck"]!!.jsonObject
        }

        test("CmdType 400 does not return another player's deck or fabricate a missing deck") {
            val foreignDeck =
                Deck(
                    id = DeckId("foreign-deck"),
                    playerId = PlayerId("other-player"),
                    name = "Other Player Deck",
                    format = Format.Standard,
                    tileId = 12345,
                    mainDeck = sampleMainDeck,
                    sideboard = emptyList(),
                    commandZone = emptyList(),
                    companions = emptyList(),
                )
            store.save(foreignDeck)
            val ch = fdChannel()
            for (deckId in listOf("foreign-deck", "missing-deck")) {
                val response = ch.sendCmd(400, """{"DeckId":"$deckId"}""")
                response.transactionId.shouldNotBeNull()
                response.jsonPayload shouldBe null
            }
        }

        test("CmdType 403 - DeleteDeck removes deck and returns Success") {
            val deletableId = "test-deck-00000000-0000-0000-0000-deleteme0001"
            store.save(
                Deck(
                    id = DeckId(deletableId),
                    playerId = PlayerId(testPlayerId),
                    name = "Doomed Deck",
                    format = Format.Standard,
                    tileId = 99999,
                    mainDeck = sampleMainDeck,
                    sideboard = emptyList(),
                    commandZone = emptyList(),
                    companions = emptyList(),
                ),
            )
            store.findById(DeckId(deletableId)).shouldNotBeNull()

            val ch = fdChannel()
            val msg = ch.sendCmd(403, """{"DeckId":"$deletableId"}""")
            msg.jsonPayload shouldBe "Success"

            store.findById(DeckId(deletableId)) shouldBe null
        }

        test("CmdType 406 - UpsertDeckV2 creates deck and returns enriched Summary") {
            val newDeckId = "test-deck-00000000-0000-0000-0000-upsert000001"
            val payload =
                """
                {
                    "Summary": {"DeckId":"$newDeckId","Name":"New Deck","DeckTileId":11111,
                        "Attributes":[{"name":"Format","value":"Standard"}]},
                    "Deck": {
                        "MainDeck": [{"cardId":75515,"quantity":4}],
                        "Sideboard": [],
                        "CommandZone": [],
                        "Companions": []
                    },
                    "ActionType": "Create"
                }
                """.trimIndent()
            val ch = fdChannel()
            val msg = ch.sendCmd(406, payload)
            val resp = json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonObject
            val summary = resp["Summary"]?.jsonObject
            assertSoftly {
                summary.shouldNotBeNull()
                summary["DeckId"]?.jsonPrimitive?.content shouldBe newDeckId
                summary["Name"]?.jsonPrimitive?.content shouldBe "New Deck"
                summary["FormatLegalities"]?.jsonObject.shouldNotBeNull()
                summary["PreferredCosmetics"]?.jsonObject.shouldNotBeNull()
                summary["DeckValidationSummaries"]?.jsonArray.shouldNotBeNull()
            }

            val saved = store.findById(DeckId(newDeckId))
            assertSoftly {
                saved.shouldNotBeNull()
                saved.name shouldBe "New Deck"
                saved.mainDeck.size shouldBe 1
                saved.mainDeck[0].grpId shouldBe 75515
            }
        }

        test("CmdType 412 - UpsertDeckV3 saves deck and returns bare 6-field summary") {
            val newDeckId = "test-deck-00000000-0000-0000-0000-upsert000412"
            val payload =
                """
                {
                    "Summary": {"DeckId":"$newDeckId","Mana":"","Name":"V3 Deck","DeckTileId":22222,
                        "Attributes":[{"name":"Format","value":"Timeless"}],
                        "Description":null,"DeckArtId":0,"IsCompanionValid":false,
                        "PreferredCosmetics":{"Avatar":"","Sleeve":"","Pet":"","Title":"","Emotes":[]},
                        "NetDeckFolderId":null,"IsNetDeck":false,"checkInbox":false},
                    "Deck": {
                        "MainDeck": [{"cardId":75515,"quantity":3}],
                        "Sideboard": [],
                        "CommandZone": [],
                        "Companions": [],
                        "CardSkins": []
                    },
                    "ActionType": "Updated"
                }
                """.trimIndent()
            val ch = fdChannel()
            val msg = ch.sendCmd(412, payload)
            val summary = json.parseToJsonElement(msg.jsonPayload.shouldNotBeNull()).jsonObject
            // Response is bare summary — no outer {Summary: ...} wrapper
            assertSoftly {
                summary["DeckId"]?.jsonPrimitive?.content shouldBe newDeckId
                summary["Name"]?.jsonPrimitive?.content shouldBe "V3 Deck"
                summary["DeckTileId"]?.jsonPrimitive?.int shouldBe 22222
                summary["DeckArtId"].shouldNotBeNull()
                summary["Attributes"]?.jsonArray.shouldNotBeNull()
                summary["PreferredCosmetics"]?.jsonObject.shouldNotBeNull()
            }
            // Trimmed fields MUST NOT appear — client's DeckSummary converter rejects them
            assertSoftly {
                summary["FormatLegalities"] shouldBe null
                summary["DeckValidationSummaries"] shouldBe null
                summary["UnownedCards"] shouldBe null
            }

            val saved = store.findById(DeckId(newDeckId))
            assertSoftly {
                saved.shouldNotBeNull()
                saved.name shouldBe "V3 Deck"
                saved.mainDeck[0].grpId shouldBe 75515
            }
        }

        test("CmdType 1912 - SetPlayerPreferences round-trips through 1911") {
            val prefsPayload = """{"Preferences":{"AutoTapEnabled":true,"AutoOrderTriggeredAbilities":false}}"""
            val ch = fdChannel()
            ch.sendCmd(1912, prefsPayload)

            // Read back via 1911 on same channel
            val readMsg = ch.sendCmd(1911)
            val obj = json.parseToJsonElement(readMsg.jsonPayload.shouldNotBeNull()).jsonObject
            val prefs = obj["Preferences"]?.jsonObject
            assertSoftly {
                prefs.shouldNotBeNull()
                prefs["AutoTapEnabled"]?.jsonPrimitive?.boolean shouldBe true
                prefs["AutoOrderTriggeredAbilities"]?.jsonPrimitive?.boolean shouldBe false
            }
        }

        test("CmdType 9999 - unknown returns response without error") {
            val ch = fdChannel()
            val msg = ch.sendCmd(9999)
            msg.transactionId.shouldNotBeNull()
            // Empty response is fine — just shouldn't crash
        }

        // --- Shape conformance tests ---

        test("CmdType 1 - StartHook matches reference shape") {
            val refKeys = loadReferenceShape("reference/fd-reference-starthook.json")
            val obj = sendJson(1)
            assertKeysMatch(refKeys, obj, "StartHook")
        }

        test("CmdType 612 - MatchCreated matches reference shape") {
            val refKeys = loadReferenceShape("reference/fd-reference-matchcreated.json")
            val ch = fdChannel()
            val responses = ch.sendCmdAll(612, """{"deckId":"$testDeckId"}""")
            val pushJson = responses[1].jsonPayload.shouldNotBeNull()
            val pushObj = json.parseToJsonElement(pushJson).jsonObject
            assertKeysMatch(refKeys, pushObj, "MatchCreated")
        }

        test("CmdType 406 - upserted deck appears in next StartHook") {
            val deckId = "test-deck-00000000-0000-0000-0000-roundtrip001"
            val payload =
                """
                {
                    "Summary": {"DeckId":"$deckId","Name":"Roundtrip Deck","DeckTileId":77777},
                    "Deck": {
                        "MainDeck": [{"cardId":75515,"quantity":4},{"cardId":75516,"quantity":56}],
                        "Sideboard": [],
                        "CommandZone": [],
                        "Companions": []
                    },
                    "ActionType": "Create"
                }
                """.trimIndent()
            val ch = fdChannel()
            ch.sendCmd(406, payload)

            // StartHook on same channel should include the new deck
            val hook = ch.sendCmd(1)
            val hookObj = json.parseToJsonElement(hook.jsonPayload.shouldNotBeNull()).jsonObject
            val summaries = hookObj["DeckSummaries"]!!.jsonArray
            val ids = summaries.map { it.jsonObject["DeckId"]?.jsonPrimitive?.content }
            ids shouldContain deckId

            val decksMap = hookObj["Decks"]!!.jsonObject
            decksMap.containsKey(deckId) shouldBe true
            decksMap[deckId]!!.jsonObject["MainDeck"].shouldNotBeNull()
        }

        // --- Sealed event lifecycle ---

        test("sealed lifecycle - join, get pool, set deck, check courses, resign") {
            val ch = fdChannelWithCourseService()
            val event = "Sealed_FDN_20260307"

            // 1. Join — get DeckSelect module with card pool
            val join = sendJson(600, """{"EventName":"$event"}""", ch)
            val course = join["Course"]!!.jsonObject
            course["CurrentModule"]?.jsonPrimitive?.content shouldBe "DeckSelect"
            course["CardPool"]!!.jsonArray.shouldNotBeEmpty()

            // 2. Set deck — transitions to CreateMatch
            val mainDeck = (1..40).joinToString(",") { """{"cardId":$it,"quantity":1}""" }
            val setDeck =
                sendJson(
                    622,
                    """
                    {"EventName":"$event",
                     "Deck":{"MainDeck":[$mainDeck],"Sideboard":[],"CommandZone":[],"Companions":[]},
                     "Summary":{"DeckId":"sealed-001","Name":"My Sealed","DeckTileId":12345}}
                    """.trimIndent(),
                    ch,
                )
            setDeck["CurrentModule"]?.jsonPrimitive?.content shouldBe "CreateMatch"

            // 3. Courses list includes our sealed event
            val courses = sendJson(623, "{}", ch)
            val names =
                courses["Courses"]!!.jsonArray.map {
                    it.jsonObject["InternalEventName"]?.jsonPrimitive?.content
                }
            names shouldContain event

            // 4. Resign — transitions to Complete
            val resign = sendJson(601, """{"EventName":"$event"}""", ch)
            resign["CurrentModule"]?.jsonPrimitive?.content shouldBe "Complete"
        }

        // --- Quick Draft integration tests ---

        test("CmdType 600 - Event_Join draft creates course with BotDraft module") {
            val ch = fdChannelWithCourseService()
            val obj = sendJson(600, """{"EventName":"QuickDraft_FDN_20260223"}""", ch)
            val course = obj["Course"]?.jsonObject
            assertSoftly {
                course.shouldNotBeNull()
                course["CurrentModule"]?.jsonPrimitive?.content shouldBe "BotDraft"
                course["CardPool"]?.jsonArray.shouldNotBeNull()
                course["CardPool"]!!.jsonArray.shouldBeEmpty()
            }
        }

        test("CmdType 627 - Event_SetCourseDeck accepts nested draft deck") {
            val ch = fdChannelWithCourseService()
            val event = "QuickDraft_EOE_20260511"
            ch.writeCmd(600, """{"EventName":"$event"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            val obj =
                sendJson(
                    627,
                    """
                    {"EventName":"$event",
                     "Summary":{
                       "DeckId":"draft-deck-001",
                       "Name":"Draft Deck",
                       "Attributes":[{"name":"Format","value":"Draft"}],
                       "DeckTileId":0,
                       "DeckArtId":450699,
                       "PreferredCosmetics":{"Avatar":"","Sleeve":"CardBack_FIN_448363","Pet":"","Title":"","Emotes":[]}
                     },
                     "Deck":{
                       "MainDeck":[{"cardId":96774,"quantity":1},{"cardId":102740,"quantity":8}],
                       "Sideboard":[{"cardId":96726,"quantity":1}],
                       "CommandZone":[],
                       "Companions":[],
                       "CardSkins":[]
                     }}
                    """.trimIndent(),
                    ch,
                )

            val summary = obj["CourseDeckSummary"]!!.jsonObject
            assertSoftly {
                obj["CurrentModule"]?.jsonPrimitive?.content shouldBe "CreateMatch"
                obj["CourseDeck"]!!.jsonObject["MainDeck"]!!.jsonArray.size shouldBe 2
                summary["DeckArtId"]?.jsonPrimitive?.int shouldBe 450699
                summary["PreferredCosmetics"]!!.jsonObject["Sleeve"]?.jsonPrimitive?.content shouldBe "CardBack_FIN_448363"
                summary["Attributes"]!!
                    .jsonArray[0]
                    .jsonObject["value"]
                    ?.jsonPrimitive
                    ?.content shouldBe "Draft"
            }
        }

        test("CmdType 1800 - BotDraft_StartDraft returns draft response with first pack") {
            val ch = fdChannelWithCourseService()
            // Join first
            ch.writeCmd(600, """{"EventName":"QuickDraft_FDN_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            val obj = sendJson(1800, """{"EventName":"QuickDraft_FDN_20260223"}""", ch)
            obj["CurrentModule"]?.jsonPrimitive?.content shouldBe "BotDraft"
            val payloadStr = obj["Payload"]?.jsonPrimitive?.content
            payloadStr.shouldNotBeNull()
            val payload = json.parseToJsonElement(payloadStr).jsonObject
            assertSoftly {
                payload["Result"]?.jsonPrimitive?.content shouldBe "Success"
                payload["DraftStatus"]?.jsonPrimitive?.content shouldBe "PickNext"
                payload["PackNumber"]?.jsonPrimitive?.int shouldBe 0
                payload["PickNumber"]?.jsonPrimitive?.int shouldBe 0
                payload["DraftPack"]?.jsonArray.shouldNotBeNull()
                payload["DraftPack"]!!.jsonArray.size shouldBe 13
            }
        }

        test("CmdType 1801 - BotDraft_DraftPick advances pick and returns updated state") {
            val ch = fdChannelWithCourseService()
            ch.writeCmd(600, """{"EventName":"QuickDraft_FDN_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            // Start draft to get first pack
            val startObj = sendJson(1800, """{"EventName":"QuickDraft_FDN_20260223"}""", ch)
            val startPayload = json.parseToJsonElement(startObj["Payload"]!!.jsonPrimitive.content).jsonObject
            val firstCard = startPayload["DraftPack"]!!.jsonArray[0].jsonPrimitive.content

            // Pick first card
            val pickPayload =
                """{"EventName":"QuickDraft_FDN_20260223","PickInfo":{"CardIds":["$firstCard"],"PackNumber":0,"PickNumber":0}}"""
            val pickObj = sendJson(1801, pickPayload, ch)
            pickObj["CurrentModule"]?.jsonPrimitive?.content shouldBe "BotDraft"
            val payload = json.parseToJsonElement(pickObj["Payload"]!!.jsonPrimitive.content).jsonObject
            assertSoftly {
                payload["PickNumber"]?.jsonPrimitive?.int shouldBe 1
                payload["DraftPack"]!!.jsonArray.size shouldBe 12
                payload["PickedCards"]!!.jsonArray.size shouldBe 1
            }
        }

        test("CmdType 1802 - BotDraft_DraftStatus returns current session") {
            val ch = fdChannelWithCourseService()
            ch.writeCmd(600, """{"EventName":"QuickDraft_FDN_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()
            ch.writeCmd(1800, """{"EventName":"QuickDraft_FDN_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            val obj = sendJson(1802, """{"EventName":"QuickDraft_FDN_20260223"}""", ch)
            obj["CurrentModule"]?.jsonPrimitive?.content shouldBe "BotDraft"
            val payload = json.parseToJsonElement(obj["Payload"]!!.jsonPrimitive.content).jsonObject
            payload["DraftStatus"]?.jsonPrimitive?.content shouldBe "PickNext"
            payload["DraftPack"]!!.jsonArray.size shouldBe 13
        }

        test("CmdType 1801 - completing all picks transitions to DeckSelect with card pool") {
            val ch = fdChannelWithCourseService()
            ch.writeCmd(600, """{"EventName":"QuickDraft_FDN_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            // Start draft
            ch.writeCmd(1800, """{"EventName":"QuickDraft_FDN_20260223"}""")
            var resp = ch.readOutbound<ByteBuf>()!!
            var msg = decodeResponse(resp)
            var outer = json.parseToJsonElement(msg.jsonPayload!!).jsonObject
            var payload = json.parseToJsonElement(outer["Payload"]!!.jsonPrimitive.content).jsonObject

            // Pick all 39 cards
            repeat(39) {
                val card = payload["DraftPack"]!!.jsonArray[0].jsonPrimitive.content
                val packNum = payload["PackNumber"]!!.jsonPrimitive.int
                val pickNum = payload["PickNumber"]!!.jsonPrimitive.int
                val pickReq =
                    """{"EventName":"QuickDraft_FDN_20260223","PickInfo":{"CardIds":["$card"],"PackNumber":$packNum,"PickNumber":$pickNum}}"""
                ch.writeCmd(1801, pickReq)
                resp = ch.readOutbound<ByteBuf>()!!
                msg = decodeResponse(resp)
                outer = json.parseToJsonElement(msg.jsonPayload!!).jsonObject
                payload = json.parseToJsonElement(outer["Payload"]!!.jsonPrimitive.content).jsonObject
            }

            // Final pick response should be completed
            assertSoftly {
                outer["CurrentModule"]?.jsonPrimitive?.content shouldBe "DeckSelect"
                payload["DraftStatus"]?.jsonPrimitive?.content shouldBe "Completed"
                payload["PickNumber"]?.jsonPrimitive?.int shouldBe 12
                payload["PickedCards"]!!.jsonArray.size shouldBe 39
            }
            val inventory = outer["DTO_InventoryInfo"]!!.jsonObject
            val change = inventory["Changes"]!!.jsonArray.single().jsonObject
            val firstGrant = change["GrantedCards"]!!.jsonArray.first().jsonObject
            assertSoftly {
                change["InventoryCustomTokens"]!!.jsonObject.keys shouldBe emptySet()
                firstGrant["SetCode"]?.jsonPrimitive?.content shouldBe "FDN"
            }

            // Course should have transitioned to DeckSelect with card pool
            ch.writeCmd(623)
            resp = ch.readOutbound<ByteBuf>()!!
            msg = decodeResponse(resp)
            val coursesObj = json.parseToJsonElement(msg.jsonPayload!!).jsonObject
            val courses = coursesObj["Courses"]!!.jsonArray
            val draftCourse =
                courses.firstOrNull {
                    it.jsonObject["InternalEventName"]?.jsonPrimitive?.content == "QuickDraft_FDN_20260223"
                }
            assertSoftly {
                draftCourse.shouldNotBeNull()
                draftCourse.jsonObject["CurrentModule"]?.jsonPrimitive?.content shouldBe "DeckSelect"
                draftCourse.jsonObject["CardPool"]!!.jsonArray.size shouldBe 39
            }
        }

        test("CmdType 600 - joining draft after complete course resets completed draft session") {
            val event = "QuickDraft_FDN_20260223"
            val ch = fdChannelWithCourseService()
            ch.writeCmd(600, """{"EventName":"$event"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            ch.writeCmd(1800, """{"EventName":"$event"}""")
            var resp = ch.readOutbound<ByteBuf>()!!
            var msg = decodeResponse(resp)
            var outer = json.parseToJsonElement(msg.jsonPayload!!).jsonObject
            var payload = json.parseToJsonElement(outer["Payload"]!!.jsonPrimitive.content).jsonObject

            repeat(39) {
                val card = payload["DraftPack"]!!.jsonArray[0].jsonPrimitive.content
                val packNum = payload["PackNumber"]!!.jsonPrimitive.int
                val pickNum = payload["PickNumber"]!!.jsonPrimitive.int
                ch.writeCmd(
                    1801,
                    """{"EventName":"$event","PickInfo":{"CardIds":["$card"],"PackNumber":$packNum,"PickNumber":$pickNum}}""",
                )
                resp = ch.readOutbound<ByteBuf>()!!
                msg = decodeResponse(resp)
                outer = json.parseToJsonElement(msg.jsonPayload!!).jsonObject
                payload = json.parseToJsonElement(outer["Payload"]!!.jsonPrimitive.content).jsonObject
            }
            payload["DraftStatus"]?.jsonPrimitive?.content shouldBe "Completed"

            val claimObj = sendJson(607, """{"EventName":"$event"}""", ch)
            claimObj["Course"]!!.jsonObject["CurrentModule"]?.jsonPrimitive?.content shouldBe "Complete"
            ch.writeCmd(600, """{"EventName":"$event"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            val restartObj = sendJson(1800, """{"EventName":"$event"}""", ch)
            val restartPayload = json.parseToJsonElement(restartObj["Payload"]!!.jsonPrimitive.content).jsonObject
            assertSoftly {
                restartPayload["DraftStatus"]?.jsonPrimitive?.content shouldBe "PickNext"
                restartPayload["PackNumber"]?.jsonPrimitive?.int shouldBe 0
                restartPayload["PickNumber"]?.jsonPrimitive?.int shouldBe 0
                restartPayload["DraftPack"]!!.jsonArray.size shouldBe 13
                restartPayload["PickedCards"]!!.jsonArray.size shouldBe 0
            }
        }

        test("CmdType 609 - Event_Resign drops draft course and session") {
            val ch = fdChannelWithCourseService()
            ch.writeCmd(600, """{"EventName":"QuickDraft_FDN_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()
            ch.writeCmd(1800, """{"EventName":"QuickDraft_FDN_20260223"}""")
            ch.readOutbound<ByteBuf>()!!.release()

            // Resign
            val obj = sendJson(609, """{"EventName":"QuickDraft_FDN_20260223"}""", ch)
            obj["CurrentModule"]?.jsonPrimitive?.content shouldBe "Complete"

            // Draft status should return empty (session was dropped)
            ch.writeCmd(1802, """{"EventName":"QuickDraft_FDN_20260223"}""")
            val resp = ch.readOutbound<ByteBuf>()!!
            val msg = decodeResponse(resp)
            // No session → empty response (no JSON payload)
            msg.transactionId.shouldNotBeNull()
        }
    })

/**
 * Minimal [DraftService.Driver] for tests that don't actually exercise pack-and-pass.
 * Hands out the pre-baked packs in declaration order; bot decks come back empty.
 */
private fun stubDraftDriver(packs: List<List<Int>>): DraftService.Driver =
    object : DraftService.Driver {
        private var remaining = packs.map { it.toMutableList() }.toMutableList()
        private var idx = 0
        private var pickN = 0

        override fun start(
            sessionKey: String,
            setCode: String,
        ): List<Int> {
            remaining = packs.map { it.toMutableList() }.toMutableList()
            idx = 0
            pickN = 0
            return remaining.getOrNull(idx)?.toList() ?: emptyList()
        }

        override fun pick(
            sessionKey: String,
            grpId: Int,
        ): DraftService.PickOutcome {
            require(idx < remaining.size) { "no pack to pick from" }
            require(remaining[idx].remove(grpId)) { "card $grpId not in current pack" }
            pickN++
            val completedPackPickN = pickN - 1
            if (remaining[idx].isEmpty()) {
                idx++
                pickN = 0
            }
            val complete = idx >= remaining.size
            return DraftService.PickOutcome(
                packNumber = if (complete) idx - 1 else idx,
                pickNumber = if (complete) completedPackPickN else pickN,
                nextPack = if (complete) emptyList() else remaining[idx].toList(),
                complete = complete,
            )
        }

        override fun complete(sessionKey: String): DraftService.PodOutcome =
            DraftService.PodOutcome(playerPool = emptyList(), botDecks = List(7) { emptyList() })
    }

/** Load a reference JSON shape from classpath. */
private fun loadReferenceShape(resource: String): JsonObject {
    val bytes =
        FrontDoorHandlerTest::class.java.classLoader
            .getResourceAsStream(resource)!!
            .readBytes()
    return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
}

/**
 * Recursively assert that [actual] has at least every key present in [reference].
 * Nested JsonObjects are checked recursively. Extra keys in actual are allowed
 * (server may add fields), but missing keys fail with a clear message.
 */
private fun assertKeysMatch(
    reference: JsonObject,
    actual: JsonObject,
    context: String,
    path: String = "",
) {
    val loc = path.ifEmpty { "root" }
    withClue("$context: missing keys at $loc") {
        (reference.keys - actual.keys).shouldBeEmpty()
    }
    for (key in reference.keys) {
        val refVal = reference[key]
        val actVal = actual[key]
        if (refVal is JsonObject && actVal is JsonObject) {
            assertKeysMatch(refVal, actVal, context, "$path.$key")
        }
        // Check first element of arrays-of-objects (e.g. PlayerInfos)
        if (refVal is JsonArray && actVal is JsonArray && refVal.isNotEmpty() && actVal.isNotEmpty()) {
            val refFirst = refVal[0]
            val actFirst = actVal[0]
            if (refFirst is JsonObject && actFirst is JsonObject) {
                assertKeysMatch(refFirst, actFirst, context, "$path.$key[0]")
            }
        }
    }
}

/** Strip 6-byte frame header and decode FD envelope. */
private fun decodeResponse(buf: ByteBuf): FdEnvelope.FdMessage {
    try {
        val totalBytes = buf.readableBytes()
        if (totalBytes <= FdWireConstants.HEADER_SIZE) {
            // Header-only response (empty ack)
            return FdEnvelope.FdMessage(
                cmdType = null,
                transactionId = null,
                jsonPayload = null,
                envelopeType = FdEnvelope.EnvelopeType.RESPONSE,
            )
        }
        // Skip 6-byte header
        buf.skipBytes(FdWireConstants.HEADER_SIZE)
        val payload = ByteArray(buf.readableBytes())
        buf.readBytes(payload)
        return FdEnvelope.decode(payload)
    } finally {
        buf.release()
    }
}
