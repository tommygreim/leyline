package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PublishedStaticChoiceInteraction
import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PrioritySignal
import leyline.bridge.types.SeatId
import leyline.bridge.types.StaticChoiceIds
import leyline.game.codes.DetailKeys
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.annotations
import leyline.testkit.detailIntList
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchStaticChoiceInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:static choice runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Island;Grizzly Bears
            aibattlefield=Hurloon Minotaur
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        data class Case(
            val semantic: PromptSemantic,
            val kind: StaticChoiceKind,
            val labels: List<String>,
            val values: List<Int>,
            val staticList: StaticList,
            val listType: SelectionListType,
            val outerPromptId: Int,
        )

        val cases =
            listOf(
                Case(
                    PromptSemantic.StaticColorChoice,
                    StaticChoiceKind.Color,
                    listOf("Red", "Blue"),
                    listOf(StaticChoiceIds.colorIdForName("Red")!!, StaticChoiceIds.colorIdForName("Blue")!!),
                    StaticList.Colors,
                    SelectionListType.Static,
                    PromptIds.CHOOSE_COLOR,
                ),
                Case(
                    PromptSemantic.StaticSubtypeChoice,
                    StaticChoiceKind.Subtype,
                    listOf("Goblin", "Human"),
                    listOf(StaticChoiceIds.subtypeIdFor("Goblin")!!, StaticChoiceIds.subtypeIdFor("Human")!!),
                    StaticList.SubTypes,
                    SelectionListType.StaticSubset,
                    PromptIds.CHOOSE_TYPE,
                ),
                Case(
                    PromptSemantic.StaticParityChoice,
                    StaticChoiceKind.Parity,
                    listOf("Odds", "Evens"),
                    listOf(StaticChoiceIds.parityIdForName("Odds")!!, StaticChoiceIds.parityIdForName("Evens")!!),
                    StaticList.Parities,
                    SelectionListType.Static,
                    PromptIds.CHOOSE_TYPE,
                ),
                Case(
                    PromptSemantic.StaticKeywordChoice,
                    StaticChoiceKind.Keyword,
                    listOf("Double Strike", "Lifelink"),
                    listOf(4, 12),
                    StaticList.Keywords,
                    SelectionListType.StaticSubset,
                    PromptIds.CHOOSE_TYPE,
                ),
            )

        fun sourceId(board: Board): Int =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .single { it.name == "Island" }
                .id

        fun request(
            board: Board,
            case: Case,
            source: Int? = sourceId(board),
            min: Int = 1,
            max: Int = 1,
            defaultIndex: Int = 0,
        ): PromptRequest =
            PromptRequest(
                promptType = "choose_one",
                message = "Choose one",
                options = case.labels,
                min = min,
                max = max,
                defaultIndex = defaultIndex,
                route = PromptRouteResolver.resolve(case.semantic),
                sourceEntityId = source,
                staticList = case.staticList,
                staticOptionIds = case.values,
            )

        fun awaitPublished(coordinator: MatchCutCoordinator): PublishedStaticChoiceInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.staticChoices.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.staticChoices.current()
            }
            return checkNotNull(published)
        }

        cases.forEach { case ->
            test("${case.semantic} publishes its exact value envelope and returns the original option index") {
                val board = startPuzzleAtMain1(puzzle)
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                val result = AtomicReference<List<Int>>()
                val finished = CountDownLatch(1)
                Thread {
                    result.set(coordinator.staticChoices.awaitSelection(request(board, case), 3_000))
                    finished.countDown()
                }.start()

                val published = awaitPublished(coordinator)
                val batch = coordinator.drain(SeatId(1)).single()
                val message = batch.single { it.hasSelectNReq() }
                val req = message.selectNReq
                val sourceInstanceId =
                    board.bridge
                        .projectionStateSnapshot()
                        .identities.forgeIdToInstanceId
                        .getValue(ForgeCardId(sourceId(board)))
                        .value
                val sourceParameter = message.prompt.parametersList.single()
                val state = batch.first().gameStateMessage
                val evenCreatureId =
                    board.instanceId(
                        board.human
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .single { it.name == "Grizzly Bears" }
                            .id,
                    )
                val oddCreatureId =
                    board.instanceId(
                        board.ai
                            .getZone(ZoneType.Battlefield)
                            .cards
                            .single { it.name == "Hurloon Minotaur" }
                            .id,
                    )

                assertSoftly {
                    batch.map { it.type } shouldContainExactly listOf(GREMessageType.GameStateMessage_695e, GREMessageType.SelectNreq)
                    batch.first().gameStateMessage.pendingMessageCount shouldBe 1
                    state.turnInfo.decisionPlayer shouldBe 1
                    published.kind shouldBe case.kind
                    req.context shouldBe SelectionContext.Resolution_a163
                    req.listType shouldBe case.listType
                    req.validationType shouldBe SelectionValidationType.NonRepeatable
                    req.optionContext shouldBe OptionContext.Resolution_a9d7
                    req.idType shouldBe IdType.None_ab2c
                    req.staticList shouldBe case.staticList
                    req.minSel shouldBe 1
                    req.maxSel shouldBe 1
                    req.minWeight shouldBe Int.MIN_VALUE
                    req.maxWeight shouldBe Int.MAX_VALUE
                    req.prompt.parametersList.shouldBeEmpty()
                    req.idsList shouldBe
                        if (case.kind == StaticChoiceKind.Subtype || case.kind == StaticChoiceKind.Keyword) {
                            case.values
                        } else {
                            emptyList()
                        }
                    req.sourceId shouldBe sourceInstanceId
                    message.prompt.promptId shouldBe case.outerPromptId
                    sourceParameter.parameterName shouldBe "CardId"
                    sourceParameter.type shouldBe ParameterType.Number
                    sourceParameter.numberValue shouldBe sourceInstanceId
                    message.allowCancel shouldBe AllowCancel.No_a526
                    if (case.kind == StaticChoiceKind.Parity) {
                        val decorations = state.annotations(AnnotationType.SelectNdecoration)
                        decorations.map { it.affectedIdsList.single() } shouldContainExactly listOf(0, 1)
                        decorations[0].detailIntList(DetailKeys.AFFECTED_OBJECTS) shouldContainExactly
                            listOf(evenCreatureId)
                        decorations[1].detailIntList(DetailKeys.AFFECTED_OBJECTS) shouldContainExactly
                            listOf(oddCreatureId)
                        state.annotations(AnnotationType.ResolutionStart).single().affectorId shouldBe sourceInstanceId
                    }
                    coordinator.acceptSettled(leyline.testkit.selectNResp(listOf(case.values[1])), published.gameStateId) shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    result.get() shouldContainExactly listOf(1)
                    coordinator.staticChoices
                        .current()
                        .shouldBeNull()
                }
            }
        }

        test("source-less multi-color choice preserves bounds and selected value order") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val case = cases.single { it.kind == StaticChoiceKind.Color }
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    coordinator.staticChoices.awaitSelection(
                        request(board, case, source = null, min = 1, max = 2),
                        3_000,
                    ),
                )
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val req =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSelectNReq() }
                    .selectNReq

            coordinator.acceptSettled(leyline.testkit.selectNResp(listOf(case.values[1], case.values[0])), published.gameStateId) shouldBe
                true

            assertSoftly {
                req.sourceId shouldBe 0
                req.minSel shouldBe 1
                req.maxSel shouldBe 2
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(1, 0)
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .snapshotChoiceResults()
                    .shouldBeEmpty()
            }
        }

        test("choice fact is staged before the engine waiter is released") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val case = cases.single { it.kind == StaticChoiceKind.Parity }
            val responseEntered = CountDownLatch(1)
            val releaseResponse = CountDownLatch(1)
            val finished = CountDownLatch(1)
            coordinator.staticChoices.beforeResponseComplete = {
                responseEntered.countDown()
                check(releaseResponse.await(3, TimeUnit.SECONDS))
            }
            Thread {
                coordinator.staticChoices.awaitSelection(request(board, case), 3_000)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val submitted = AtomicReference<Boolean>()
            val submitFinished = CountDownLatch(1)
            Thread {
                submitted.set(
                    coordinator.acceptSettled(leyline.testkit.selectNResp(listOf(case.values[1])), published.gameStateId),
                )
                submitFinished.countDown()
            }.start()
            responseEntered.await(3, TimeUnit.SECONDS) shouldBe true
            val choice =
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .snapshotChoiceResults()
                    .single()
                    .result

            assertSoftly {
                choice.sourceForgeCardId.value shouldBe sourceId(board)
                choice.choiceValue shouldBe case.values[1]
                choice.choiceDomain shouldBe StaticList.Parities.number
                choice.sentiment shouldBe 2
                finished.count shouldBe 1
            }
            releaseResponse.countDown()
            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                submitFinished.await(3, TimeUnit.SECONDS) shouldBe true
                submitted.get() shouldBe true
            }
        }

        test("timeout uses the configured original default index and retires the window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val case = cases.single { it.kind == StaticChoiceKind.Color }
            val signal = PrioritySignal()
            val prompt =
                InteractivePromptBridge(timeoutMs = 25, prioritySignal = signal, strict = false).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                }
            val result = prompt.requestStaticChoice(request(board, case, source = null, defaultIndex = 1))

            assertSoftly {
                result shouldContainExactly listOf(1)
                signal.awaitSignal(3_000) shouldBe true
                coordinator.staticChoices
                    .current()
                    .shouldBeNull()
                coordinator.drain(SeatId(1)).single().any { it.hasSelectNReq() } shouldBe true
            }
        }
    })
