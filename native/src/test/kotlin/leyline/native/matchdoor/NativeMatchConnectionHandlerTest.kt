package leyline.native.matchdoor

import com.google.protobuf.ByteString
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.concurrent.DefaultEventExecutorGroup
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.DeckCard
import leyline.domain.MatchInfo
import leyline.domain.PlayerId
import leyline.domain.deck.DeckCards
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleLibrary
import leyline.match.MatchConnection
import leyline.match.MatchConnectionPort
import leyline.match.MatchRegistry
import leyline.match.MatchSeatAssignment
import leyline.native.NativeTag
import leyline.native.account.AccountStore
import leyline.native.account.LocalAccountAuthenticator
import leyline.native.account.TokenService
import leyline.native.matchmaking.LocalPairingService
import org.jetbrains.exposed.v1.jdbc.Database
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeMatchConnectionHandlerTest :
    FunSpec({
        tags(NativeTag)

        val database = File.createTempFile("md-auth", ".db").also { it.deleteOnExit() }
        val accounts = AccountStore(Database.connect("jdbc:sqlite:${database.absolutePath}", "org.sqlite.JDBC"))
        accounts.createTables()
        accounts.seed("account-alice", "alice", "alice@local", "Alice", "test")
        accounts.seed("account-bob", "bob", "bob@local", "Bob", "test")
        val tokens = TokenService(store = accounts)
        val authenticator = LocalAccountAuthenticator(accounts, tokens)
        val channels = mutableListOf<EmbeddedChannel>()
        var configs = RuntimeMatchConfigRegistry()
        lateinit var pairing: LocalPairingService
        var factories = 0

        beforeEach {
            configs = RuntimeMatchConfigRegistry()
            pairing = LocalPairingService(configs, { MatchInfo("room", "localhost", 30003, it) })
            factories = 0
        }

        afterEach {
            channels.forEach { it.finishAndReleaseAll() }
            channels.clear()
        }

        fun channel(): EmbeddedChannel {
            val registry = MatchRegistry { pairing.complete(it) }
            return EmbeddedChannel(
                NativeMatchConnectionHandler(
                    { output, _ ->
                        factories++
                        MatchConnection(
                            registry = registry,
                            output = output,
                            engineSettings = EngineSettings(),
                            puzzleLibrary = PuzzleLibrary(File("data/puzzles")),
                            cardRepository = AdmissionCardRepository,
                            runtimeMatchConfigs = configs,
                        )
                    },
                    authenticator,
                    pairing,
                ),
            ).also(channels::add)
        }

        fun auth(
            channel: EmbeddedChannel,
            persona: String,
            claimedId: String = persona,
            token: String? = null,
            legacyAuthToken: Boolean = false,
        ) {
            val credential = token ?: tokens.issueTokens(accounts.findByPersonaId(persona)!!).accessToken
            channel.writeInbound(
                ClientToMatchServiceMessage
                    .newBuilder()
                    .setRequestId(1)
                    .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.AuthenticateRequest_f487)
                    .setPayload(
                        AuthenticateRequest
                            .newBuilder()
                            .setClientId(claimedId)
                            .setPlayerName("Untrusted Name")
                            .apply {
                                if (legacyAuthToken) {
                                    setClientAuthToken(ByteString.copyFromUtf8(credential))
                                } else {
                                    setPlayFabSessionTicket(credential)
                                }
                            }.build()
                            .toByteString(),
                    ).build(),
            )
        }

        fun connect(
            channel: EmbeddedChannel,
            seat: Int,
            match: String = "room",
        ) {
            val gre =
                ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.ConnectReq_097b)
                    .setSystemSeatId(seat)
                    .build()
            channel.writeInbound(
                ClientToMatchServiceMessage
                    .newBuilder()
                    .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487)
                    .setPayload(
                        ClientToMatchDoorConnectRequest
                            .newBuilder()
                            .setMatchId(match)
                            .setClientToGreMessageBytes(gre.toByteString())
                            .build()
                            .toByteString(),
                    ).build(),
            )
        }

        fun reserve() {
            val deck = DeckCards(listOf(DeckCard(1, 60)))
            pairing.queue("fd-alice", PlayerId("alice"), "Alice", "Play", deck) {}
            pairing.queue("fd-bob", PlayerId("bob"), "Bob", "Play", deck) {}
        }

        test("signed account determines the authenticated persona and display name") {
            val ch = channel()
            auth(ch, "alice", "account-alice")
            val response = ch.readOutbound<MatchServiceToClientMessage>().authenticateResponse
            assertSoftly {
                ch.isActive shouldBe true
                factories shouldBe 1
                response.clientId shouldBe "alice"
                response.screenName shouldBe "Alice"
            }
        }

        test("legacy byte token field accepts the same signed account credential") {
            val ch = channel()
            auth(ch, "alice", legacyAuthToken = true)
            ch.isActive shouldBe true
            ch.readOutbound<MatchServiceToClientMessage>().authenticateResponse.clientId shouldBe "alice"
        }

        test("invalid credentials and mismatched identity are rejected before creating a match connection") {
            val invalid = channel()
            auth(invalid, "alice", token = "unsigned-token")
            val swapped = channel()
            auth(swapped, "alice", "bob")
            assertSoftly {
                invalid.isActive shouldBe false
                swapped.isActive shouldBe false
                factories shouldBe 0
            }
        }

        test("unauthenticated connect cannot claim a room") {
            reserve()
            val ch = channel()
            connect(ch, 1)
            assertSoftly {
                ch.isActive shouldBe false
                factories shouldBe 0
                pairing.claim("room", PlayerId("alice"), 1, false, "valid").seatId.value shouldBe 1
            }
        }

        test("invalid match or swapped seat does not tear down the legitimate reservation") {
            reserve()
            val wrongMatch = channel()
            auth(wrongMatch, "alice")
            connect(wrongMatch, 1, "unreserved")
            val wrongSeat = channel()
            auth(wrongSeat, "alice")
            connect(wrongSeat, 2)
            assertSoftly {
                wrongMatch.isActive shouldBe false
                wrongSeat.isActive shouldBe false
                pairing.claim("room", PlayerId("alice"), 1, false, "valid").matchId shouldBe "room"
            }
        }

        test("Familiar and duplicate connections cannot replace a reserved human player") {
            reserve()
            pairing.claim("room", PlayerId("alice"), 1, false, "existing")
            val duplicate = channel()
            auth(duplicate, "alice")
            connect(duplicate, 1)
            val familiar = channel()
            auth(familiar, "alice", "alice_Familiar")
            connect(familiar, 2)
            assertSoftly {
                duplicate.isActive shouldBe false
                familiar.isActive shouldBe false
                pairing.claim("room", PlayerId("bob"), 2, false, "valid-b").seatId.value shouldBe 2
            }
        }

        test("offloaded match handler keeps inbound and close lifecycle ordered off the I/O loop") {
            val handlers = DefaultEventExecutorGroup(1)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(3)
            val events = CopyOnWriteArrayList<String>()
            var handlerThread: Thread? = null
            val fake =
                object : MatchConnectionPort {
                    override fun opened() = Unit

                    override fun receive(msg: ClientToMatchServiceMessage) {
                        handlerThread = Thread.currentThread()
                        events.add("receive:${msg.requestId}")
                        completed.countDown()
                        if (msg.requestId == 1) {
                            entered.countDown()
                            check(release.await(3, TimeUnit.SECONDS))
                        }
                    }

                    override fun assignSeat(assignment: MatchSeatAssignment) = Unit

                    override fun disconnected() {
                        events.add("disconnected")
                        completed.countDown()
                    }

                    override fun failed(cause: Throwable) {
                        events.add("failed")
                        completed.countDown()
                    }
                }
            val channel = EmbeddedChannel()
            channel.pipeline().addLast(
                handlers,
                "handler",
                NativeMatchConnectionHandler({ _, _ -> fake }),
            )
            val first = ClientToMatchServiceMessage.newBuilder().setRequestId(1).build()
            val second = ClientToMatchServiceMessage.newBuilder().setRequestId(2).build()

            try {
                channel.pipeline().context("handler").executor() shouldNotBe channel.eventLoop()
                channel.writeInbound(first)
                entered.await(3, TimeUnit.SECONDS) shouldBe true
                channel.writeInbound(second)
                events shouldBe listOf("receive:1")

                channel.close()
                release.countDown()
                completed.await(3, TimeUnit.SECONDS) shouldBe true
                events shouldBe listOf("receive:1", "receive:2", "disconnected")
                handlerThread shouldNotBe Thread.currentThread()
            } finally {
                release.countDown()
                channel.finishAndReleaseAll()
                handlers.shutdownGracefully().syncUninterruptibly()
            }
        }
    })

private object AdmissionCardRepository : CardRepository {
    override fun findByGrpId(grpId: Int): CardData? = null

    override fun findNameByGrpId(grpId: Int): String? = null

    override fun findGrpIdByName(name: String): Int? = null

    override fun findAllGrpIds(): List<Int> = emptyList()
}
