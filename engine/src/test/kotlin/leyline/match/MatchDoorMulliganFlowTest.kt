package leyline.match

import com.google.protobuf.ByteString
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.netty.channel.embedded.EmbeddedChannel
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.coord.groupResp
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.DeckCard
import leyline.domain.deck.DeckCards
import leyline.domain.deck.DeckSource
import leyline.domain.service.MatchCoordinator
import leyline.game.mapping.ZoneIds
import leyline.testkit.TestCardRegistry
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ChooseStartingPlayerResp
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.ConnectReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.MulliganOption
import wotc.mtgo.gre.external.messaging.Messages.MulliganResp
import wotc.mtgo.gre.external.messaging.Messages.PerformActionResp
import wotc.mtgo.gre.external.messaging.Messages.TeamType
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MatchDoorMulliganFlowTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        val deck = "60 Forest"

        fun engineSettings() =
            EngineSettings(
                seed = 42L,
                dieRollWinner = 1,
                skipMulligan = false,
                bridgeTimeoutMs = 2_000L,
                promptFailsafeMs = 2_000L,
                aiTurnWaitMs = 2_000L,
                mulliganWaitMs = 2_000L,
            )

        val runtimeMatchConfigs = RuntimeMatchConfigRegistry()

        fun handler(registry: MatchRegistry) =
            MatchHandler(
                registry = registry,
                engineSettings = engineSettings(),
                cardRepository = TestCardRegistry.repo,
                runtimeMatchConfigs = runtimeMatchConfigs,
            )

        fun serviceMessage(
            type: ClientToMatchServiceMessageType,
            payload: ByteString,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            ClientToMatchServiceMessage
                .newBuilder()
                .setRequestId(requestId)
                .setClientToMatchServiceMessageType(type)
                .setPayload(payload)
                .build()

        fun greMessage(
            seatId: Int,
            type: ClientMessageType,
            customize: ClientToGREMessage.Builder.() -> Unit = {},
        ): ClientToGREMessage =
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(seatId)
                .setType(type)
                .apply(customize)
                .build()

        fun auth(
            clientId: String,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            serviceMessage(
                ClientToMatchServiceMessageType.AuthenticateRequest_f487,
                AuthenticateRequest
                    .newBuilder()
                    .setClientId(clientId)
                    .setPlayerName(clientId)
                    .build()
                    .toByteString(),
                requestId,
            )

        fun connect(
            matchId: String,
            seatId: Int,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            serviceMessage(
                ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487,
                ClientToMatchDoorConnectRequest
                    .newBuilder()
                    .setMatchId(matchId)
                    .setClientToGreMessageBytes(
                        greMessage(seatId, ClientMessageType.ConnectReq_097b) {
                            setConnectReq(ConnectReq.newBuilder())
                        }.toByteString(),
                    ).build()
                    .toByteString(),
                requestId,
            )

        fun greServiceMessage(
            gre: ClientToGREMessage,
            requestId: Int,
        ): ClientToMatchServiceMessage =
            serviceMessage(
                ClientToMatchServiceMessageType.ClientToGremessage,
                gre.toByteString(),
                requestId,
            )

        fun greOutbound(channel: EmbeddedChannel): List<GREToClientMessage> =
            generateSequence { channel.readOutbound<MatchServiceToClientMessage>() }
                .filter { it.hasGreToClientEvent() }
                .flatMap { it.greToClientEvent.greToClientMessagesList }
                .toList()

        fun connectPair(
            registry: MatchRegistry,
            matchId: String,
            deckList: String = deck,
            familiarFirst: Boolean = false,
            drainInitial: Boolean = true,
        ): Pair<EmbeddedChannel, EmbeddedChannel> {
            runtimeMatchConfigs.put(
                RuntimeMatchConfig(matchId = matchId, seat1 = DeckSource.ForgeText(deckList), seat2 = DeckSource.ForgeText(deckList)),
            )
            val local = EmbeddedChannel(handler(registry))
            val familiar = EmbeddedChannel(handler(registry))

            local.writeInbound(auth("local-player", 1))
            familiar.writeInbound(auth("local-player_Familiar", 2))
            greOutbound(local)
            greOutbound(familiar)

            if (familiarFirst) {
                familiar.writeInbound(connect(matchId, seatId = 2, requestId = 4))
                local.writeInbound(connect(matchId, seatId = 1, requestId = 3))
            } else {
                local.writeInbound(connect(matchId, seatId = 1, requestId = 3))
                familiar.writeInbound(connect(matchId, seatId = 2, requestId = 4))
            }
            if (drainInitial) {
                greOutbound(local)
                greOutbound(familiar)
            }
            return local to familiar
        }

        fun dealHandCount(messages: List<GREToClientMessage>): Int =
            messages.count { message ->
                message.hasGameStateMessage() &&
                    message.gameStateMessage.playersList.any {
                        it.pendingMessageType == ClientMessageType.MulliganResp_097b
                    }
            }

        fun chooseStartingPlayer(
            respId: Int,
            seatId: Int = 1,
        ): ClientToGREMessage =
            greMessage(2, ClientMessageType.ChooseStartingPlayerResp_097b) {
                setRespId(respId)
                setChooseStartingPlayerResp(
                    ChooseStartingPlayerResp
                        .newBuilder()
                        .setTeamType(TeamType.Individual)
                        .setSystemSeatId(seatId)
                        .setTeamId(seatId),
                )
            }

        listOf(false to "player-first", true to "Familiar-first").forEach { (familiarFirst, order) ->
            test("$order startup progresses exactly once after both sessions connect") {
                val registry = MatchRegistry()
                val matchId = "startup-$order"
                val (local, familiar) =
                    connectPair(registry, matchId, familiarFirst = familiarFirst, drainInitial = false)

                try {
                    val playerMessages = greOutbound(local)
                    val observerMessages = greOutbound(familiar)
                    assertSoftly {
                        dealHandCount(playerMessages) shouldBe 1
                        playerMessages.count { it.type == GREMessageType.MulliganReq_aa0d } shouldBe 1
                        observerMessages.count { it.type == GREMessageType.MulliganReq_aa0d } shouldBe 0
                        observerMessages.count { it.type == GREMessageType.ChooseStartingPlayerReq_695e } shouldBe 0
                    }
                } finally {
                    local.close()
                    familiar.close()
                }
            }
        }

        test("Familiar reconnect and legacy starting-player response do not replay startup") {
            val registry = MatchRegistry()
            val matchId = "familiar-startup-replay"
            val (local, familiar) = connectPair(registry, matchId, drainInitial = false)

            try {
                val initialPlayerMessages = greOutbound(local)
                greOutbound(familiar)
                val bridge = registry.getMatch(matchId)!!.bridge
                val committedAfterStartup = bridge.committedSequence()

                familiar.writeInbound(auth("local-player_Familiar", 5))
                greOutbound(familiar)
                familiar.writeInbound(connect(matchId, seatId = 2, requestId = 6))
                val reconnectMessages = greOutbound(familiar)
                val committedAfterReconnect = bridge.committedSequence()

                familiar.writeInbound(
                    greServiceMessage(
                        chooseStartingPlayer(committedAfterStartup.lastPromptMsgId),
                        7,
                    ),
                )
                val legacyResponseMessages = greOutbound(familiar)

                assertSoftly {
                    dealHandCount(initialPlayerMessages) shouldBe 1
                    initialPlayerMessages.count { it.type == GREMessageType.MulliganReq_aa0d } shouldBe 1
                    reconnectMessages.count { it.type == GREMessageType.MulliganReq_aa0d } shouldBe 0
                    reconnectMessages.map { it.type } shouldBe listOf(GREMessageType.GameStateMessage_695e)
                    legacyResponseMessages shouldBe emptyList()
                    greOutbound(local) shouldBe emptyList()
                    committedAfterReconnect.currentGsId shouldBeGreaterThan committedAfterStartup.currentGsId
                    bridge.committedSequence() shouldBe committedAfterReconnect
                }
            } finally {
                local.close()
                familiar.close()
            }
        }

        test("one-shot AI deck override is consumed once per shared match") {
            val registry = MatchRegistry()
            val configs = RuntimeMatchConfigRegistry()
            val override = AtomicReference<String?>("Green test")
            val overrideReads = AtomicInteger()
            val forestGrpId = TestCardRegistry.repo.findGrpIdByName("Forest")!!
            val mountainGrpId = TestCardRegistry.repo.findGrpIdByName("Mountain")!!
            val coordinator =
                object : MatchCoordinator by MatchCoordinator.NOOP {
                    override fun resolveDeckCardsByName(name: String): DeckCards? =
                        if (name == "Green test") {
                            DeckCards(mainDeck = listOf(DeckCard(forestGrpId, 60)))
                        } else {
                            null
                        }
                }

            fun testHandler() =
                MatchHandler(
                    registry = registry,
                    engineSettings = engineSettings(),
                    coordinator = coordinator,
                    cardRepository = TestCardRegistry.repo,
                    runtimeMatchConfigs = configs,
                    aiDeckNameOverride = {
                        overrideReads.incrementAndGet()
                        override.getAndSet(null)
                    },
                )

            fun connectWithSeatOneDeck(matchId: String): Pair<EmbeddedChannel, EmbeddedChannel> {
                configs.put(RuntimeMatchConfig(matchId = matchId, seat1 = DeckSource.ForgeText("60 Mountain")))
                val local = EmbeddedChannel(testHandler())
                val familiar = EmbeddedChannel(testHandler())
                local.writeInbound(auth("local-player", 1))
                familiar.writeInbound(auth("local-player_Familiar", 2))
                greOutbound(local)
                greOutbound(familiar)
                local.writeInbound(connect(matchId, seatId = 1, requestId = 3))
                familiar.writeInbound(connect(matchId, seatId = 2, requestId = 4))
                greOutbound(local)
                greOutbound(familiar)
                return local to familiar
            }

            val first = connectWithSeatOneDeck("ai-override-first")
            try {
                assertSoftly {
                    override.get() shouldBe null
                    overrideReads.get() shouldBe 1
                    registry
                        .getMatch("ai-override-first")!!
                        .bridge
                        .getDeckGrpIds(SeatId(2))
                        .toSet() shouldBe setOf(forestGrpId)
                }
            } finally {
                first.first.close()
                first.second.close()
            }

            val second = connectWithSeatOneDeck("ai-override-second")
            try {
                assertSoftly {
                    overrideReads.get() shouldBe 2
                    registry
                        .getMatch("ai-override-second")!!
                        .bridge
                        .getDeckGrpIds(SeatId(2))
                        .toSet() shouldBe setOf(mountainGrpId)
                }
            } finally {
                second.first.close()
                second.second.close()
            }
        }

        fun mulliganDecision(
            decision: MulliganOption,
            respId: Int,
        ): ClientToGREMessage =
            greMessage(1, ClientMessageType.MulliganResp_097b) {
                setRespId(respId)
                setMulliganResp(MulliganResp.newBuilder().setDecision(decision))
            }

        fun passPriority(prompt: GREToClientMessage): ClientToGREMessage =
            greMessage(1, ClientMessageType.PerformActionResp_097b) {
                setGameStateId(prompt.gameStateId)
                setRespId(prompt.msgId)
                setPerformActionResp(
                    PerformActionResp
                        .newBuilder()
                        .addActions(Action.newBuilder().setActionType(ActionType.Pass)),
                )
            }

        test("normal keep flows through MatchHandler mulligan request and response path") {
            val registry = MatchRegistry()
            val matchId = "mulligan-flow-keep"
            val (local, familiar) = connectPair(registry, matchId, drainInitial = false)

            try {
                val mulliganPrompt = greOutbound(local).map { it.type }
                greOutbound(familiar)

                local.writeInbound(
                    greServiceMessage(
                        mulliganDecision(
                            MulliganOption.AcceptHand,
                            registry
                                .getMatch(matchId)!!
                                .bridge
                                .committedSequence()
                                .lastPromptMsgId,
                        ),
                        6,
                    ),
                )
                val postKeepGre = greOutbound(local)
                val postKeep = postKeepGre.map { it.type }
                val session = registry.getConnection(matchId, leyline.bridge.types.SeatId(1))?.session as MatchSession
                val pending = checkNotNull(session.gameBridge.actionBridge(leyline.bridge.types.SeatId(1)).getPending())
                val pendingGameStateId = checkNotNull(pending.promptGameStateId)
                val committedPromptMsgId = session.gameBridge.committedSequence().lastPromptMsgId
                val actionPrompt =
                    postKeepGre.single {
                        it.hasActionsAvailableReq() &&
                            it.gameStateId == pendingGameStateId &&
                            it.msgId == committedPromptMsgId
                    }

                assertSoftly {
                    mulliganPrompt shouldContain GREMessageType.GameStateMessage_695e
                    mulliganPrompt shouldContain GREMessageType.PromptReq
                    mulliganPrompt shouldContain GREMessageType.MulliganReq_aa0d
                    postKeep shouldContain GREMessageType.GameStateMessage_695e
                    postKeep shouldContain GREMessageType.ActionsAvailableReq_695e
                    pendingGameStateId shouldBe actionPrompt.gameStateId
                    session.gameBridge
                        .getGame()
                        ?.isGameOver shouldBe false
                }
            } finally {
                local.close()
                familiar.close()
            }
        }

        test("mulligan timeout still delivers the first action horizon") {
            val registry = MatchRegistry()
            val matchId = "mulligan-flow-timeout"
            val (local, familiar) = connectPair(registry, matchId, drainInitial = false)

            try {
                greOutbound(local)
                greOutbound(familiar)
                val bridge = registry.getMatch(matchId)!!.bridge
                val deadline = System.nanoTime() + 8_000_000_000L
                val postTimeout = mutableListOf<GREToClientMessage>()
                while (System.nanoTime() < deadline && postTimeout.none { it.hasActionsAvailableReq() }) {
                    postTimeout += greOutbound(local)
                    bridge.cutCoordinator.deliverySignal.await(100)
                }

                assertSoftly {
                    bridge.mulliganBridge(SeatId(1)).pendingPrompt() shouldBe null
                    postTimeout.map { it.type } shouldContain GREMessageType.ActionsAvailableReq_695e
                    postTimeout.any { it.hasGameStateMessage() } shouldBe true
                }
            } finally {
                local.close()
                familiar.close()
            }
        }

        test("actual match session preserves opening-hand lifecycle for both seats") {
            TestCardRegistry.ensureCardRegistered("Leyline Axe")
            val registry = MatchRegistry()
            val matchId = "opening-hand-lifecycle-both-seats"
            val (local, familiar) = connectPair(registry, matchId, deckList = "60 Leyline Axe", drainInitial = false)

            try {
                greOutbound(local)
                greOutbound(familiar)
                local.writeInbound(
                    greServiceMessage(
                        mulliganDecision(
                            MulliganOption.AcceptHand,
                            registry
                                .getMatch(matchId)!!
                                .bridge
                                .committedSequence()
                                .lastPromptMsgId,
                        ),
                        6,
                    ),
                )
                val postKeep = greOutbound(local)
                val openingGameStates =
                    postKeep.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
                val openingActions =
                    openingGameStates
                        .flatMap { message ->
                            message.annotationsList
                        }.filter { annotation ->
                            AnnotationType.UserActionTaken in annotation.typeList &&
                                annotation.detailInt("actionType") == ActionType.OpeningHandAction.number
                        }
                val openingAbilityIds = openingActions.flatMap { it.affectedIdsList }.toSet()
                val deletedInstanceIds = openingGameStates.flatMap { it.diffDeletedInstanceIdsList }.toSet()
                val axeGrpId = checkNotNull(TestCardRegistry.repo.findGrpIdByName("Leyline Axe"))
                val battlefieldIds =
                    openingGameStates
                        .flatMap { gsm ->
                            gsm.zonesList
                                .filter { it.zoneId == ZoneIds.BATTLEFIELD }
                                .flatMap { it.objectInstanceIdsList }
                        }.toSet()
                val battlefieldAxeIds =
                    openingGameStates
                        .flatMap { it.gameObjectsList }
                        .filter { it.zoneId == ZoneIds.BATTLEFIELD && it.grpId == axeGrpId }
                        .map { it.instanceId }
                        .toSet()

                assertSoftly {
                    openingActions.size shouldBe 14
                    openingActions.map { it.affectorId }.toSet() shouldBe setOf(1, 2)
                    openingActions.map { it.detailInt("abilityGrpId") }.toSet() shouldBe setOf(175903)
                    deletedInstanceIds shouldBe openingAbilityIds
                    battlefieldAxeIds.size shouldBe 14
                    battlefieldIds shouldBe battlefieldAxeIds
                }
            } finally {
                local.close()
                familiar.close()
            }
        }

        test("web reconnect after keep receives the current action horizon") {
            val registry = MatchRegistry()
            val matchId = "post-keep-reconnect"
            val (local, familiar) = connectPair(registry, matchId, drainInitial = false)

            try {
                greOutbound(local)
                greOutbound(familiar)
                local.writeInbound(
                    greServiceMessage(
                        mulliganDecision(
                            MulliganOption.AcceptHand,
                            registry
                                .getMatch(matchId)!!
                                .bridge
                                .committedSequence()
                                .lastPromptMsgId,
                        ),
                        6,
                    ),
                )
                val priorPrompt = greOutbound(local).last { it.hasActionsAvailableReq() }
                greOutbound(familiar)

                local.writeInbound(auth("local-player", 7))
                greOutbound(local)
                local.writeInbound(connect(matchId, seatId = 1, requestId = 8))
                val reconnect = greOutbound(local)
                val reconnectPrompt = reconnect.last { it.hasActionsAvailableReq() }

                local.writeInbound(greServiceMessage(passPriority(reconnectPrompt), 9))
                val postReconnect = greOutbound(local)

                assertSoftly {
                    reconnect.map { it.type } shouldBe
                        listOf(
                            GREMessageType.ConnectResp_695e,
                            GREMessageType.GameStateMessage_695e,
                            GREMessageType.ActionsAvailableReq_695e,
                        )
                    reconnectPrompt.gameStateId shouldNotBe priorPrompt.gameStateId
                    reconnectPrompt.actionsAvailableReq shouldBe priorPrompt.actionsAvailableReq
                    postReconnect.map { it.type } shouldContain GREMessageType.GameStateMessage_695e
                    greOutbound(familiar) shouldBe emptyList()
                }
            } finally {
                local.close()
                familiar.close()
            }
        }

        test("familiar channel ignores mirrored stale gameplay responses") {
            val registry = MatchRegistry()
            val matchId = "familiar-stale-gameplay-response"
            val (local, familiar) = connectPair(registry, matchId)

            try {
                familiar.writeInbound(
                    greServiceMessage(
                        greMessage(2, ClientMessageType.PerformActionResp_097b) {
                            setRespId(1)
                        },
                        5,
                    ),
                )

                greOutbound(familiar).map { it.type } shouldBe emptyList()
            } finally {
                local.close()
                familiar.close()
            }
        }

        test("two mulligans then keep preserve the final redraw hand") {
            val registry = MatchRegistry()
            val matchId = "mulligan-flow-redraw"
            val mixedDeck =
                """
                30 Forest
                30 Mountain
                """.trimIndent()
            val (local, familiar) = connectPair(registry, matchId, deckList = mixedDeck, drainInitial = false)

            try {
                greOutbound(local)
                greOutbound(familiar)
                val session = registry.getConnection(matchId, leyline.bridge.types.SeatId(1))?.session as MatchSession
                val firstHand = session.gameBridge.getHandGrpIds(leyline.bridge.types.SeatId(1))

                local.writeInbound(
                    greServiceMessage(
                        mulliganDecision(
                            MulliganOption.Mulligan,
                            registry
                                .getMatch(matchId)!!
                                .bridge
                                .committedSequence()
                                .lastPromptMsgId,
                        ),
                        6,
                    ),
                )
                val firstRedrawPrompt = greOutbound(local)
                val firstRedrawTypes = firstRedrawPrompt.map { it.type }
                val firstRedrawMulligan = firstRedrawPrompt.last { it.type == GREMessageType.MulliganReq_aa0d }
                val firstRedrawHand = session.gameBridge.getHandGrpIds(leyline.bridge.types.SeatId(1))

                local.writeInbound(
                    greServiceMessage(
                        mulliganDecision(
                            MulliganOption.Mulligan,
                            registry
                                .getMatch(matchId)!!
                                .bridge
                                .committedSequence()
                                .lastPromptMsgId,
                        ),
                        7,
                    ),
                )
                val secondRedrawPrompt = greOutbound(local)
                val secondRedrawMulligan = secondRedrawPrompt.last { it.type == GREMessageType.MulliganReq_aa0d }
                val secondRedrawHand = session.gameBridge.getHandGrpIds(leyline.bridge.types.SeatId(1))

                local.writeInbound(
                    greServiceMessage(
                        mulliganDecision(
                            MulliganOption.AcceptHand,
                            registry
                                .getMatch(matchId)!!
                                .bridge
                                .committedSequence()
                                .lastPromptMsgId,
                        ),
                        8,
                    ),
                )
                val tuckPrompt = greOutbound(local)
                val groupReq = tuckPrompt.last { it.hasGroupReq() }
                val handIds = groupReq.groupReq.instanceIdsList
                local.writeInbound(
                    greServiceMessage(
                        groupResp(handIds.dropLast(2), handIds.takeLast(2))
                            .toBuilder()
                            .setRespId(groupReq.msgId)
                            .setGameStateId(groupReq.gameStateId)
                            .build(),
                        9,
                    ),
                )
                val postKeep = greOutbound(local).map { it.type }
                val keptHand = session.gameBridge.getHandGrpIds(leyline.bridge.types.SeatId(1))

                assertSoftly {
                    firstRedrawTypes shouldContain GREMessageType.GameStateMessage_695e
                    firstRedrawTypes shouldContain GREMessageType.PromptReq
                    firstRedrawTypes shouldContain GREMessageType.MulliganReq_aa0d
                    firstRedrawMulligan.mulliganReq.mulliganCount shouldBe 1
                    firstRedrawMulligan.prompt.parametersList.map { it.numberValue } shouldContain 7
                    firstRedrawHand.size shouldBe 7
                    firstRedrawHand shouldNotBe firstHand
                    secondRedrawMulligan.mulliganReq.mulliganCount shouldBe 2
                    secondRedrawMulligan.prompt.parametersList.map { it.numberValue } shouldContain 7
                    secondRedrawHand.size shouldBe 7
                    tuckPrompt.map { it.type } shouldContain GREMessageType.GroupReq_695e
                    groupReq.groupReq.groupSpecsList[1].lowerBound shouldBe 2
                    keptHand.size shouldBe 5
                    postKeep shouldContain GREMessageType.GameStateMessage_695e
                    postKeep shouldContain GREMessageType.ActionsAvailableReq_695e
                }
            } finally {
                local.close()
                familiar.close()
            }
        }
    })
