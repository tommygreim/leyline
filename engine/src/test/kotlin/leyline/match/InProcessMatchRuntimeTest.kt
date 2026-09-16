package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.config.EngineSettings
import leyline.config.PuzzleDefinition
import leyline.config.RuntimeMatchConfig
import leyline.copilot.CombatDamageAssignment
import leyline.copilot.CombatDamageRecipient
import leyline.domain.service.MatchCoordinator
import leyline.game.InMemoryCardRepository
import leyline.game.data.ForgeCardRepository
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AuthenticateRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchDoorConnectRequest
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.PerformActionResp
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class InProcessMatchRuntimeTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("launches, processes serialized GRE, publishes frames and observes one result") {
            val frames = CopyOnWriteArrayList<ByteArray>()
            val runtime = runtime()
            val handle =
                runtime.launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("runtime", puzzleDefinition = PuzzleDefinition("runtime", lifecyclePuzzle)),
                        frames::add,
                    ),
                )

            handle.receive(auth("player"))
            handle.receive(connect("runtime"))
            handle.receive(concede())

            val types =
                frames
                    .map(MatchServiceToClientMessage::parseFrom)
                    .flatMap { it.greToClientEvent.greToClientMessagesList }
                    .map { it.type }
            assertSoftly {
                handle.response.matchId shouldBe "runtime"
                types shouldContain GREMessageType.GameStateMessage_695e
                handle.result
                    .toCompletableFuture()
                    .get()
                    .matchId shouldBe "runtime"
                handle.result
                    .toCompletableFuture()
                    .get()
                    .won shouldBe false
                handle.copilotProposal().reason shouldBe "match runtime handle is terminal"
            }
            handle.close()
        }

        test("advises the owned pending prompt without advancing the match") {
            val frames = CopyOnWriteArrayList<ByteArray>()
            val handle =
                runtime().launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("advice", puzzleDefinition = PuzzleDefinition("advice", advicePuzzle)),
                        frames::add,
                    ),
                )

            handle.receive(auth("player"))
            handle.receive(connect("advice"))
            val before = frames.toList()
            val prompt = before.greMessages().last { it.type == GREMessageType.ActionsAvailableReq_695e }

            val proposal = handle.copilotProposal()

            assertSoftly {
                proposal.intent shouldBe "pass"
                proposal.card shouldBe null
                proposal.promptKey shouldBe "${prompt.gameStateId}:${prompt.msgId}"
                proposal.gameStateId shouldBe prompt.gameStateId
                proposal.respId shouldBe prompt.msgId
                frames.toList() shouldBe before
                handle.result.toCompletableFuture().isDone shouldBe false
            }
            handle.close()
        }

        test("advice leaves the target prompt produced by a later cast unchanged") {
            val runtime = catalogRuntime()
            val baselineFrames = CopyOnWriteArrayList<ByteArray>()
            val advisedFrames = CopyOnWriteArrayList<ByteArray>()
            val baseline =
                runtime.launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("baseline-bolt", puzzleDefinition = PuzzleDefinition("baseline-bolt", boltPuzzle)),
                        baselineFrames::add,
                    ),
                )
            val advised =
                runtime.launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("advised-bolt", puzzleDefinition = PuzzleDefinition("advised-bolt", boltPuzzle)),
                        advisedFrames::add,
                    ),
                )
            try {
                baseline.receive(auth("baseline-player"))
                baseline.receive(connect("baseline-bolt"))
                advised.receive(auth("advised-player"))
                advised.receive(connect("advised-bolt"))
                val baselinePrompt = baselineFrames.greMessages().last { it.type == GREMessageType.ActionsAvailableReq_695e }
                val advisedPrompt = advisedFrames.greMessages().last { it.type == GREMessageType.ActionsAvailableReq_695e }
                val baselineCount = baselineFrames.greMessages().size

                advised.copilotProposal().intent shouldBe "cast"
                val advisedCount = advisedFrames.greMessages().size
                baseline.receive(cast(baselinePrompt))
                advised.receive(cast(advisedPrompt))

                val baselineNext = baselineFrames.greMessages().drop(baselineCount).map { it.type }
                val advisedNext = advisedFrames.greMessages().drop(advisedCount).map { it.type }
                assertSoftly {
                    baselineNext shouldContain GREMessageType.SelectTargetsReq_695e
                    advisedNext shouldContain GREMessageType.SelectTargetsReq_695e
                    baseline.result.toCompletableFuture().isDone shouldBe false
                    advised.result.toCompletableFuture().isDone shouldBe false
                }
            } finally {
                baseline.close()
                advised.close()
            }
        }

        test("advice projects the pending combat damage response without submitting it") {
            val frames = CopyOnWriteArrayList<ByteArray>()
            val handle =
                catalogRuntime().launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig(
                            "advice-damage",
                            puzzleDefinition = PuzzleDefinition("advice-damage", damageAssignmentPuzzle),
                        ),
                        frames::add,
                    ),
                )
            try {
                handle.receive(auth("damage-player"))
                handle.receive(connect("advice-damage"))

                var proposal = handle.copilotProposal()
                var attempts = 0
                while (proposal.promptType != GREMessageType.AssignDamageReq_695e.name && attempts++ < 200) {
                    proposal.responses.firstOrNull()?.let { handle.receive(greResponse(it)) } ?: delay(25)
                    proposal = handle.copilotProposal()
                }

                proposal.promptType shouldBe GREMessageType.AssignDamageReq_695e.name
                val before = frames.toList()
                val prompt = before.greMessages().last { it.hasAssignDamageReq() }
                val advised = handle.copilotProposal()
                val expected =
                    prompt.assignDamageReq.damageAssignersList.map { assigner ->
                        CombatDamageAssignment(
                            assignerId = assigner.instanceId,
                            recipients =
                                assigner.assignmentsList.map { assignment ->
                                    CombatDamageRecipient(assignment.instanceId, assignment.assignedDamage)
                                },
                        )
                    }
                assertSoftly {
                    advised.combatDamageAssignments shouldBe expected
                    advised.combatDamageAssignments
                        .single()
                        .recipients
                        .map { it.amount } shouldBe listOf(2, 2, 1)
                    frames.toList() shouldBe before
                    handle.result.toCompletableFuture().isDone shouldBe false
                }
            } finally {
                handle.close()
            }
        }

        test("advice remains confined to each launched handle") {
            val runtime = runtime()
            val connectedHandle =
                runtime.launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("connected-advice", puzzleDefinition = PuzzleDefinition("connected-advice", advicePuzzle)),
                        onFrame = {},
                    ),
                )
            val disconnectedHandle =
                runtime.launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig(
                            "disconnected-advice",
                            puzzleDefinition = PuzzleDefinition("disconnected-advice", lifecyclePuzzle),
                        ),
                        onFrame = {},
                    ),
                )

            connectedHandle.receive(auth("connected-player"))
            connectedHandle.receive(connect("connected-advice"))

            assertSoftly {
                connectedHandle.copilotProposal().intent shouldBe "pass"
                disconnectedHandle.copilotProposal().intent shouldBe "unrealizable"
                disconnectedHandle.copilotProposal().reason shouldContain "not connected"
            }
            connectedHandle.close()
            disconnectedHandle.close()
        }

        test("advice reports not connected and closed handles explicitly") {
            val handle =
                runtime().launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("unavailable", puzzleDefinition = PuzzleDefinition("unavailable", advicePuzzle)),
                        onFrame = {},
                    ),
                )

            val notConnected = handle.copilotProposal()
            assertSoftly {
                notConnected.intent shouldBe "unrealizable"
                notConnected.reason shouldContain "not connected"
            }

            handle.close()

            val closed = handle.copilotProposal()
            assertSoftly {
                closed.intent shouldBe "unrealizable"
                closed.reason shouldContain "closed"
            }
        }

        test("reports engine failure and close is idempotent") {
            val closed = AtomicBoolean()
            val handle =
                runtime().launch(
                    MatchRuntimeLaunch(
                        RuntimeMatchConfig("failure", puzzle = "missing"),
                        onFrame = {},
                        onClosed = { closed.set(true) },
                    ),
                )

            handle.receive(auth("player"))
            handle.receive(connect("failure"))
            handle.result.toCompletableFuture().isCompletedExceptionally shouldBe true
            handle.close()
            handle.close()

            closed.get() shouldBe true
        }

        test("public runtime seam has no Ktor types") {
            val types =
                listOf(MatchRuntime::class.java, MatchRuntimeHandle::class.java, MatchRuntimeLaunch::class.java)
                    .flatMap { type ->
                        type.methods.flatMap { method -> listOf(method.returnType) + method.parameterTypes }
                    }

            types.none { it.name.startsWith("io.ktor.") } shouldBe true
        }
    })

private fun runtime() =
    InProcessMatchRuntime(
        EngineSettings(seed = 42L, bridgeTimeoutMs = 2_000L, promptFailsafeMs = 2_000L),
        MatchCoordinator.NOOP,
        InMemoryCardRepository(),
        java.io.File("data/puzzles"),
    )

private fun catalogRuntime() =
    InProcessMatchRuntime(
        EngineSettings(seed = 42L, bridgeTimeoutMs = 2_000L, promptFailsafeMs = 2_000L, aiSpeed = 0.0),
        MatchCoordinator.NOOP,
        ForgeCardRepository.open(),
        java.io.File("data/puzzles"),
    )

private fun auth(clientId: String): ByteArray =
    ClientToMatchServiceMessage
        .newBuilder()
        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.AuthenticateRequest_f487)
        .setPayload(
            AuthenticateRequest
                .newBuilder()
                .setClientId(clientId)
                .build()
                .toByteString(),
        ).build()
        .toByteArray()

private fun connect(matchId: String): ByteArray =
    ClientToMatchServiceMessage
        .newBuilder()
        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487)
        .setPayload(
            ClientToMatchDoorConnectRequest
                .newBuilder()
                .setMatchId(matchId)
                .setClientToGreMessageBytes(
                    ClientToGREMessage
                        .newBuilder()
                        .setSystemSeatId(1)
                        .setType(ClientMessageType.ConnectReq_097b)
                        .build()
                        .toByteString(),
                ).build()
                .toByteString(),
        ).build()
        .toByteArray()

private fun concede(): ByteArray =
    ClientToMatchServiceMessage
        .newBuilder()
        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.ClientToGremessage)
        .setPayload(
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(1)
                .setType(ClientMessageType.ConcedeReq_097b)
                .build()
                .toByteString(),
        ).build()
        .toByteArray()

private fun cast(prompt: wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage): ByteArray {
    val action = prompt.actionsAvailableReq.actionsList.single { it.actionType == ActionType.Cast }
    return ClientToMatchServiceMessage
        .newBuilder()
        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.ClientToGremessage)
        .setPayload(
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(1)
                .setType(ClientMessageType.PerformActionResp_097b)
                .setGameStateId(prompt.gameStateId)
                .setRespId(prompt.msgId)
                .setPerformActionResp(PerformActionResp.newBuilder().addActions(action))
                .build()
                .toByteString(),
        ).build()
        .toByteArray()
}

private fun greResponse(hex: String): ByteArray {
    val bytes = ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    return ClientToMatchServiceMessage
        .newBuilder()
        .setClientToMatchServiceMessageType(ClientToMatchServiceMessageType.ClientToGremessage)
        .setPayload(ClientToGREMessage.parseFrom(bytes).toByteString())
        .build()
        .toByteArray()
}

private fun List<ByteArray>.greMessages() =
    map(MatchServiceToClientMessage::parseFrom)
        .flatMap { it.greToClientEvent.greToClientMessagesList }

private val advicePuzzle =
    """
    [metadata]
    Name:Runtime advice
    Goal:Win
    Turns:2
    Difficulty:Easy
    Description:Play a land.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Forest
    humanlibrary=Forest
    ailibrary=Forest
    """.trimIndent()

private val lifecyclePuzzle =
    """
    [metadata]
    Name:Runtime lifecycle
    Goal:Win
    Turns:1
    Difficulty:Easy
    Description:Concede after initial publication.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanbattlefield=Mountain
    humanlibrary=Mountain
    ailibrary=Mountain
    """.trimIndent()

private val boltPuzzle =
    """
    [metadata]
    Name:Runtime Bolt
    Goal:Win
    Turns:1
    Difficulty:Easy
    Description:Cast Lightning Bolt at the opponent.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=3

    humanhand=Lightning Bolt
    humanbattlefield=Mountain
    humanlibrary=Mountain
    ailibrary=Mountain
    """.trimIndent()

private val damageAssignmentPuzzle =
    """
    [metadata]
    Name:Runtime damage assignment
    Goal:Win
    Turns:10
    Difficulty:Tutorial
    Description:Assign lethal damage and trample overflow.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=1

    humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain;Charging Monstrosaur
    humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    aibattlefield=Forest;Forest;Grizzly Bears;Runeclaw Bear
    ailibrary=Forest;Forest;Forest;Forest;Forest
    """.trimIndent()
