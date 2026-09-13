package leyline.match

import com.google.protobuf.ByteString
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.coord.groupResp
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.deck.DeckSource
import leyline.game.generator.PuzzleLibrary
import leyline.infra.MatchOutput
import leyline.testkit.TestCardRegistry
import leyline.testkit.clientMessage
import leyline.testkit.declareAttackersResp
import leyline.testkit.declareBlockersResp
import leyline.testkit.performAction
import leyline.testkit.selectTargetsResp
import leyline.testkit.submitAttackersReq
import leyline.testkit.submitBlockersReq
import leyline.testkit.submitTargetsReq
import wotc.mtgo.gre.external.messaging.Messages.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HumanMatchFlowTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        test("two human clients receive distinct private hands and independent mulligans") {
            HumanMatchFixture("60 Forest", "60 Mountain").use { match ->
                val mulliganSeats = match.keepBoth()
                val forest = TestCardRegistry.repo.findGrpIdByName("Forest")!!
                val mountain = TestCardRegistry.repo.findGrpIdByName("Mountain")!!
                val firstCards = match.greHistory(1).flatMap { it.gameStateMessage.gameObjectsList }
                val secondCards = match.greHistory(2).flatMap { it.gameStateMessage.gameObjectsList }
                assertSoftly {
                    mulliganSeats shouldBe setOf(1, 2)
                    match.greHistory(1).count { it.hasConnectResp() } shouldBe 1
                    match.greHistory(2).count { it.hasConnectResp() } shouldBe 1
                    for (seat in 1..2) {
                        val initial = match.greHistory(seat).first { it.hasGameStateMessage() }.gameStateMessage
                        initial.type shouldBe GameStateType.Full
                        initial.hasTurnInfo() shouldBe true
                    }
                    firstCards.any { it.grpId == forest } shouldBe true
                    secondCards.any { it.grpId == mountain } shouldBe true
                    firstCards.any { it.grpId == mountain } shouldBe false
                    secondCards.any { it.grpId == forest } shouldBe false
                }
            }
        }

        test("both human seats redraw and choose their own London mulligan cards") {
            HumanMatchFixture("60 Forest", "60 Mountain").use { match ->
                val redrawn = mutableSetOf<Int>()
                val kept = mutableSetOf<Int>()
                repeat(4) {
                    val (seat, prompt) = match.awaitGre { it.hasMulliganReq() }
                    if (seat !in redrawn) {
                        match.reply(
                            seat,
                            prompt,
                            clientMessage(ClientMessageType.MulliganResp_097b) {
                                mulliganResp = MulliganResp.newBuilder().setDecision(MulliganOption.Mulligan).build()
                            },
                        )
                        val (_, tuck) = match.awaitGre(seat) { it.hasGroupReq() }
                        val ids = tuck.groupReq.instanceIdsList
                        ids.size shouldBe 7
                        match.reply(seat, tuck, groupResp(ids.dropLast(1), ids.takeLast(1)))
                        redrawn += seat
                    } else {
                        match.reply(
                            seat,
                            prompt,
                            clientMessage(ClientMessageType.MulliganResp_097b) {
                                mulliganResp = MulliganResp.newBuilder().setDecision(MulliganOption.AcceptHand).build()
                            },
                        )
                        kept += seat
                    }
                }
                match.awaitGre { it.hasActionsAvailableReq() }
                assertSoftly {
                    redrawn shouldBe setOf(1, 2)
                    kept shouldBe setOf(1, 2)
                    match.bridge.getHandGrpIds(SeatId(1)).size shouldBe 6
                    match.bridge.getHandGrpIds(SeatId(2)).size shouldBe 6
                }
            }
        }

        test("both human seats cast targeted spells and resolve damage against the opposing player") {
            HumanMatchFixture("30 Mountain\n30 Lightning Bolt", "30 Mountain\n30 Lightning Bolt").use { match ->
                match.keepBoth()
                val landed = mutableSetOf<Int>()
                val cast = mutableSetOf<Int>()
                val bolt = TestCardRegistry.repo.findGrpIdByName("Lightning Bolt")!!
                repeat(100) {
                    if (cast.size == 2 && (1..2).all { match.bridge.getPlayer(SeatId(it))!!.life == 17 }) return@repeat
                    val (seat, prompt) = match.awaitGre { it.hasActionsAvailableReq() }
                    val actions = prompt.actionsAvailableReq.actionsList
                    val land = actions.firstOrNull { it.actionType == ActionType.Play_add3 }
                    val spell = actions.firstOrNull { it.actionType == ActionType.Cast && it.grpId == bolt }
                    when {
                        land != null && seat !in landed -> {
                            match.reply(seat, prompt, performAction(land))
                            landed += seat
                        }
                        spell != null && seat in landed && seat !in cast -> {
                            match.reply(seat, prompt, performAction(spell))
                            val (_, target) = match.awaitGre(seat) { it.hasSelectTargetsReq() }
                            val opponent = if (seat == 1) 2 else 1
                            match.sendGre(
                                opponent,
                                clientMessage(ClientMessageType.CancelActionReq_097b) { gameStateId = target.gameStateId },
                            )
                            match.awaitGre(opponent) { it.type == GREMessageType.IllegalRequest }
                            match.reply(seat, target, selectTargetsResp(listOf(if (seat == 1) 2 else 1)))
                            val (_, selected) = match.awaitGre(seat) { it.hasSelectTargetsReq() }
                            match.reply(seat, selected, submitTargetsReq())
                            cast += seat
                        }
                        else -> match.reply(seat, prompt, performAction { actionType = ActionType.Pass })
                    }
                }
                assertSoftly {
                    landed shouldBe setOf(1, 2)
                    cast shouldBe setOf(1, 2)
                    match.bridge.getPlayer(SeatId(1))!!.life shouldBe 17
                    match.bridge.getPlayer(SeatId(2))!!.life shouldBe 17
                }
            }
        }

        test("the second human chooses a blocker and combat resolves both creatures into their graveyards") {
            HumanMatchFixture("30 Mountain\n30 Raging Goblin", "30 Mountain\n30 Raging Goblin").use { match ->
                match.keepBoth()
                val landed = mutableSetOf<Int>()
                val cast = mutableSetOf<Int>()
                var attacked = false
                var blocked = false
                val goblin = TestCardRegistry.repo.findGrpIdByName("Raging Goblin")!!
                repeat(100) {
                    if (blocked &&
                        (1..2).all {
                            match.bridge
                                .getPlayer(SeatId(it))!!
                                .getCardsIn(ZoneType.Graveyard)
                                .size == 1
                        }
                    ) {
                        return@repeat
                    }
                    val (seat, prompt) =
                        match.awaitGre {
                            it.hasActionsAvailableReq() || it.hasDeclareAttackersReq() || it.hasDeclareBlockersReq()
                        }
                    when {
                        prompt.hasActionsAvailableReq() -> {
                            val actions = prompt.actionsAvailableReq.actionsList
                            val land = actions.firstOrNull { it.actionType == ActionType.Play_add3 }
                            val spell = actions.firstOrNull { it.actionType == ActionType.Cast && it.grpId == goblin }
                            when {
                                land != null && seat !in landed -> {
                                    match.reply(seat, prompt, performAction(land))
                                    landed += seat
                                }
                                spell != null && seat in landed && seat !in cast -> {
                                    match.reply(seat, prompt, performAction(spell))
                                    cast += seat
                                }
                                else -> match.reply(seat, prompt, performAction { actionType = ActionType.Pass })
                            }
                        }
                        prompt.hasDeclareAttackersReq() -> {
                            if (seat == 1 && cast.size == 2 && !attacked) {
                                match.reply(seat, prompt, declareAttackersResp(autoDeclare = true, autoDeclareTarget = 2))
                                val (_, selected) = match.awaitGre(seat) { it.hasDeclareAttackersReq() }
                                match.reply(seat, selected, submitAttackersReq(seat))
                                attacked = true
                            } else {
                                match.reply(seat, prompt, submitAttackersReq(seat))
                            }
                        }
                        else -> {
                            seat shouldBe 2
                            val blocker = prompt.declareBlockersReq.blockersList.single()
                            match.reply(
                                seat,
                                prompt,
                                declareBlockersResp(
                                    mapOf(
                                        blocker.blockerInstanceId to blocker.attackerInstanceIdsList.single(),
                                    ),
                                ),
                            )
                            val (_, selected) = match.awaitGre(seat) { it.hasDeclareBlockersReq() }
                            match.reply(seat, selected, submitBlockersReq(seat))
                            blocked = true
                        }
                    }
                }
                assertSoftly {
                    attacked shouldBe true
                    blocked shouldBe true
                    (1..2).map {
                        match.bridge
                            .getPlayer(SeatId(it))!!
                            .getCardsIn(ZoneType.Graveyard)
                            .size
                    } shouldBe listOf(1, 1)
                    (1..2).map { match.bridge.getPlayer(SeatId(it))!!.life } shouldBe listOf(20, 20)
                }
            }
        }

        test("both human seats play lands and the other seat cannot answer their action prompt") {
            HumanMatchFixture("60 Forest", "60 Mountain").use { match ->
                match.keepBoth()
                val played = mutableSetOf<Int>()
                var checkedWrongSeat = false
                repeat(40) {
                    if (played.size == 2) return@repeat
                    val (seat, prompt) = match.awaitGre { it.hasActionsAvailableReq() }
                    val actions = prompt.actionsAvailableReq.actionsList
                    val land = actions.firstOrNull { it.actionType == ActionType.Play_add3 }
                    if (land != null && seat !in played) {
                        if (!checkedWrongSeat) {
                            val wrongSeat = if (seat == 1) 2 else 1
                            val pending =
                                match.bridge
                                    .actionBridge(SeatId(seat))
                                    .getPending()!!
                                    .actionId
                            match.reply(wrongSeat, prompt, performAction(land))
                            val (_, rejected) = match.awaitGre(wrongSeat) { it.type == GREMessageType.IllegalRequest }
                            assertSoftly {
                                rejected.type shouldBe GREMessageType.IllegalRequest
                                match.bridge
                                    .actionBridge(SeatId(seat))
                                    .getPending()!!
                                    .actionId shouldBe pending
                            }
                            checkedWrongSeat = true
                        }
                        match.reply(seat, prompt, performAction(land))
                        played += seat
                    } else {
                        match.reply(seat, prompt, performAction { actionType = ActionType.Pass })
                    }
                }
                assertSoftly {
                    played shouldBe setOf(1, 2)
                    checkedWrongSeat shouldBe true
                }
                match.sendGre(1, clientMessage(ClientMessageType.ConcedeReq_097b))
                match.awaitBothCompleted()
            }
        }
    })

/** Two independent production connections; output queues replace only the TCP edge. */
private class HumanMatchFixture(
    deck1: String,
    deck2: String,
) : AutoCloseable {
    private val matchId = UUID.randomUUID().toString()
    private val registry = MatchRegistry()
    private val requestId = AtomicInteger()
    private val output = LinkedBlockingQueue<Pair<Int, MatchServiceToClientMessage>>()
    private val history = CopyOnWriteArrayList<Pair<Int, MatchServiceToClientMessage>>()
    private val connections = mutableMapOf<Int, MatchConnection>()
    val bridge get() = checkNotNull(registry.getMatch(matchId)).bridge

    init {
        TestCardRegistry.ensureDeckRegistered(deck1)
        TestCardRegistry.ensureDeckRegistered(deck2)
        val configs =
            RuntimeMatchConfigRegistry().apply {
                put(RuntimeMatchConfig(matchId, DeckSource.ForgeText(deck1), DeckSource.ForgeText(deck2), humanVsHuman = true))
            }
        val players = (1..2).map { MatchPlayerIdentity("local-$it", "Player $it", SeatId(it)) }
        try {
            for (seat in 1..2) {
                val connection =
                    MatchConnection(
                        registry = registry,
                        output =
                            object : MatchOutput {
                                override fun send(message: MatchServiceToClientMessage) {
                                    check(
                                        message.greToClientEvent.greToClientMessagesList.all { gre ->
                                            gre.systemSeatIdsList.isEmpty() || seat in gre.systemSeatIdsList
                                        },
                                    ) { "Private output for another seat reached connection $seat" }
                                    val item = seat to message
                                    history += item
                                    output.add(item)
                                }

                                override fun close() = Unit
                            },
                        engineSettings =
                            EngineSettings(
                                seed = 42L,
                                dieRollWinner = 1,
                                skipMulligan = false,
                                bridgeTimeoutMs = 30_000,
                                promptFailsafeMs = 30_000,
                                aiTurnWaitMs = 2_000,
                                mulliganWaitMs = 30_000,
                            ),
                        puzzleLibrary = PuzzleLibrary(File("data/puzzles")),
                        cardRepository = TestCardRegistry.repo,
                        runtimeMatchConfigs = configs,
                    )
                connections[seat] = connection
                connection.assignSeat(MatchSeatAssignment(matchId, "local-$seat", SeatId(seat), "LocalPlay", players, humanVsHuman = true))
                connection.opened()
                connection.receive(
                    service(
                        ClientToMatchServiceMessageType.AuthenticateRequest_f487,
                        AuthenticateRequest
                            .newBuilder()
                            .setClientId("local-$seat")
                            .setPlayerName("Player $seat")
                            .build()
                            .toByteString(),
                    ),
                )
                val connect =
                    clientMessage(ClientMessageType.ConnectReq_097b) {
                        systemSeatId = seat
                        connectReq = ConnectReq.getDefaultInstance()
                    }
                connection.receive(
                    service(
                        ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487,
                        ClientToMatchDoorConnectRequest
                            .newBuilder()
                            .setMatchId(matchId)
                            .setClientToGreMessageBytes(connect.toByteString())
                            .build()
                            .toByteString(),
                    ),
                )
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    fun greHistory(seat: Int): List<GREToClientMessage> =
        history
            .filter { it.first == seat }
            .flatMap { it.second.greToClientEvent.greToClientMessagesList }

    fun keepBoth(): Set<Int> {
        val kept = mutableSetOf<Int>()
        repeat(2) {
            val (seat, prompt) = awaitGre { it.hasMulliganReq() }
            reply(
                seat,
                prompt,
                clientMessage(ClientMessageType.MulliganResp_097b) {
                    mulliganResp = MulliganResp.newBuilder().setDecision(MulliganOption.AcceptHand).build()
                },
            )
            kept += seat
        }
        return kept
    }

    fun reply(
        seat: Int,
        prompt: GREToClientMessage,
        message: ClientToGREMessage,
    ) = sendGre(
        seat,
        message
            .toBuilder()
            .setRespId(prompt.msgId)
            .setGameStateId(prompt.gameStateId)
            .build(),
    )

    fun sendGre(
        seat: Int,
        message: ClientToGREMessage,
    ) = connections.getValue(seat).receive(
        service(
            ClientToMatchServiceMessageType.ClientToGremessage,
            message
                .toBuilder()
                .setSystemSeatId(seat)
                .build()
                .toByteString(),
        ),
    )

    fun awaitGre(
        seat: Int? = null,
        predicate: (GREToClientMessage) -> Boolean,
    ): Pair<Int, GREToClientMessage> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val left = deadline - System.nanoTime()
            check(left > 0) {
                "No requested GRE message for seat $seat; types=${history.takeLast(8).map {
                    it.second.greToClientEvent.greToClientMessagesList.map { gre ->
                        gre.type
                    }
                }}"
            }
            val item =
                output.poll(left, TimeUnit.NANOSECONDS) ?: error(
                    "Timed out waiting for GRE for seat $seat; " +
                        "pending=${(1..2).map { it to bridge.actionBridge(SeatId(it)).getPending()?.state }}; " +
                        "life=${(1..2).map { bridge.getPlayer(SeatId(it))?.life }}; " +
                        "recent=${history.takeLast(10).map { entry ->
                            entry.first to
                                entry.second.greToClientEvent.greToClientMessagesList
                                    .map { it.type to it.msgId }
                        }}; workers=${Thread.getAllStackTraces().filterKeys { it.name.startsWith("match-runtime-delivery-") }
                            .map { (thread, stack) -> thread.name to stack.take(10).toList() }}",
                )
            if (seat != null && item.first != seat) continue
            item.second.greToClientEvent.greToClientMessagesList
                .firstOrNull(predicate)
                ?.let { return item.first to it }
        }
    }

    fun awaitBothCompleted() {
        val completed = mutableSetOf<Int>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (completed.size < 2) {
            val left = deadline - System.nanoTime()
            check(left > 0) { "Only seats $completed received match completion" }
            val item = output.poll(left, TimeUnit.NANOSECONDS) ?: error("Missing terminal room output")
            if (item.second.hasMatchGameRoomStateChangedEvent() &&
                item.second.matchGameRoomStateChangedEvent.gameRoomInfo.stateType == MatchGameRoomStateType.MatchCompleted
            ) {
                completed += item.first
            }
        }
        completed shouldBe setOf(1, 2)
    }

    private fun service(
        type: ClientToMatchServiceMessageType,
        payload: ByteString,
    ): ClientToMatchServiceMessage =
        ClientToMatchServiceMessage
            .newBuilder()
            .setRequestId(requestId.incrementAndGet())
            .setClientToMatchServiceMessageType(type)
            .setPayload(payload)
            .build()

    override fun close() {
        connections.values.forEach(MatchConnection::disconnected)
    }
}
