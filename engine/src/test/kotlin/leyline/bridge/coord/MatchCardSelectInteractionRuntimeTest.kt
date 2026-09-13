package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.CardSelectInteractionResult
import leyline.bridge.handoff.CardSelectKind
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PublishedCardSelectInteraction
import leyline.bridge.handoff.ResolutionAbilityShape
import leyline.bridge.handoff.ResolutionRouteInput
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.EffectCostResp
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.FailureReason
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchCardSelectInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:card select runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Mountain;Forest
            humanlibrary=Grizzly Bears;Centaur Courser
            humansideboard=Environmental Sciences
            humanbattlefield=Island
            ailibrary=Forest
            """.trimIndent()

        fun options(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Hand)
                .cards
                .toList()

        fun manifestOptions(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Library)
                .cards
                .toList()

        fun source(board: Board): Card =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .single()

        fun learnOptions(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Sideboard)
                .cards
                .toList() + options(board)

        fun request(
            board: Board,
            semantic: PromptSemantic,
            sourceId: Int? = source(board).id,
            min: Int = 1,
            max: Int = 1,
            defaultIndex: Int = 0,
            candidates: List<Card> = options(board),
            cancellable: Boolean = false,
        ): PromptRequest =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose a card",
                options = candidates.map { it.name },
                min = min,
                max = max,
                defaultIndex = defaultIndex,
                candidateRefs =
                    candidates.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, card.zone.zoneType.name)
                    },
                unfilteredRefs =
                    if (semantic == PromptSemantic.SelectNResolution && candidates.none { it.isInZone(ZoneType.Library) }) {
                        candidates.mapIndexed { index, card ->
                            PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, card.zone.zoneType.name)
                        }
                    } else {
                        emptyList()
                    },
                route =
                    PromptRouteResolver.resolve(
                        semantic,
                        resolutionInput =
                            candidates
                                .takeIf { semantic == PromptSemantic.SelectNResolution }
                                ?.let {
                                    ResolutionRouteInput(
                                        optionCount = candidates.size,
                                        candidateCount = it.size,
                                        candidateKinds = setOf(PromptCandidateKind.Card),
                                        candidateZones = it.mapTo(linkedSetOf()) { card -> card.zone.zoneType.name },
                                        abilityShape = ResolutionAbilityShape.Dig,
                                        allCandidatesProjectable = it.none { card -> card.isInZone(ZoneType.Library) },
                                    )
                                },
                    ),
                sourceEntityId = sourceId,
                cancellable = cancellable,
            )

        fun awaitPublished(coordinator: MatchCutCoordinator): PublishedCardSelectInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.cardSelect.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.cardSelect.current()
            }
            return checkNotNull(published)
        }

        data class Case(
            val semantic: PromptSemantic,
            val kind: CardSelectKind,
            val context: SelectionContext,
            val listType: SelectionListType,
            val optionContext: OptionContext,
            val innerPromptId: Int = PromptIds.SELECT_N,
            val innerParameterId: Int? = null,
            val outerPromptId: Int,
            val allowCancel: AllowCancel,
            val includeRequestSource: Boolean = true,
            val requestMin: Int = 1,
            val wireMin: Int = requestMin,
        )

        val cases =
            listOf(
                Case(
                    PromptSemantic.SelectNLegendRule,
                    CardSelectKind.LegendRule,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    innerPromptId = 0,
                    outerPromptId = PromptIds.SELECT_N_LEGEND_RULE,
                    allowCancel = AllowCancel.No_a526,
                    includeRequestSource = false,
                ),
                Case(
                    PromptSemantic.SelectNDiscard,
                    CardSelectKind.Discard,
                    SelectionContext.Discard_a163,
                    SelectionListType.Static,
                    OptionContext.Payment,
                    innerPromptId = PromptIds.DISCARD_COST,
                    outerPromptId = PromptIds.DISCARD_COST,
                    allowCancel = AllowCancel.No_a526,
                    includeRequestSource = false,
                ),
                Case(
                    PromptSemantic.SelectNDiscardEffect,
                    CardSelectKind.DiscardEffect,
                    SelectionContext.Discard_a163,
                    SelectionListType.Static,
                    OptionContext.Resolution_a9d7,
                    innerPromptId = PromptIds.DISCARD_COST,
                    outerPromptId = PromptIds.DISCARD_COST,
                    allowCancel = AllowCancel.No_a526,
                ),
                Case(
                    PromptSemantic.SelectNSacrificeEffect,
                    CardSelectKind.SacrificeEffect,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    outerPromptId = PromptIds.SELECT_N,
                    allowCancel = AllowCancel.None_a526,
                ),
                Case(
                    PromptSemantic.SuspectChoice,
                    CardSelectKind.Suspect,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    innerPromptId = 0,
                    innerParameterId = PromptIds.SELECT_N_INNER_PARAMETER,
                    outerPromptId = PromptIds.SUSPECT_ONE_OF_THOSE_CREATURES,
                    allowCancel = AllowCancel.Continue,
                ),
                Case(
                    PromptSemantic.MutateTopBottom,
                    CardSelectKind.MutateTopBottom,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    outerPromptId = PromptIds.SELECT_N,
                    allowCancel = AllowCancel.No_a526,
                ),
                Case(
                    PromptSemantic.SelectNLibraryPutback,
                    CardSelectKind.LibraryPutback,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    innerPromptId = 0,
                    innerParameterId = PromptIds.SELECT_N_INNER_PARAMETER,
                    outerPromptId = PromptIds.SELECT_N_LIBRARY_PUTBACK,
                    allowCancel = AllowCancel.No_a526,
                ),
                Case(
                    PromptSemantic.ManifestDread,
                    CardSelectKind.ManifestDread,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    innerPromptId = 0,
                    innerParameterId = PromptIds.MANIFEST_DREAD_INNER_PARAMETER,
                    outerPromptId = PromptIds.MANIFEST_DREAD,
                    allowCancel = AllowCancel.No_a526,
                ),
                Case(
                    PromptSemantic.SelectNResolution,
                    CardSelectKind.Resolution,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    innerPromptId = 0,
                    innerParameterId = PromptIds.SELECT_N_INNER_PARAMETER,
                    outerPromptId = PromptIds.SELECT_N,
                    allowCancel = AllowCancel.No_a526,
                ),
                Case(
                    PromptSemantic.SelectNResolution,
                    CardSelectKind.ResolutionMapped,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    innerPromptId = 0,
                    innerParameterId = PromptIds.SELECT_N_INNER_PARAMETER,
                    outerPromptId = PromptIds.SELECT_N,
                    allowCancel = AllowCancel.No_a526,
                    includeRequestSource = false,
                ),
                Case(
                    PromptSemantic.LearnLesson,
                    CardSelectKind.Learn,
                    SelectionContext.Resolution_a163,
                    SelectionListType.Dynamic,
                    OptionContext.Resolution_a9d7,
                    innerPromptId = 0,
                    innerParameterId = PromptIds.SELECT_N_LEARN_INNER_PARAMETER,
                    outerPromptId = PromptIds.LEARN_LESSON_OR_DISCARD,
                    allowCancel = AllowCancel.Continue,
                    requestMin = 0,
                    wireMin = 1,
                ),
            )

        cases.forEach { case ->
            test("${case.semantic}/${case.kind} preserves its exact SelectN envelope and handle") {
                val board = startPuzzleAtMain1(puzzle)
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                val handles =
                    when {
                        case.kind == CardSelectKind.ManifestDread || case.kind == CardSelectKind.Resolution ->
                            manifestOptions(board)
                        case.kind == CardSelectKind.Learn -> learnOptions(board)
                        else -> options(board)
                    }
                val result = AtomicReference<CardSelectInteractionResult>()
                val finished = CountDownLatch(1)
                Thread {
                    result.set(
                        coordinator.cardSelect.awaitSelection(
                            request(
                                board,
                                case.semantic,
                                sourceId = source(board).id.takeIf { case.includeRequestSource },
                                min = case.requestMin,
                                candidates = handles,
                            ),
                            handles,
                            3_000,
                        ),
                    )
                    finished.countDown()
                }.start()

                val published = awaitPublished(coordinator)
                val batch = coordinator.drain(SeatId(1)).single()
                val message = batch.single { it.hasSelectNReq() }
                val req = message.selectNReq
                assertSoftly {
                    batch.map { it.type } shouldContainExactly
                        listOf(
                            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.GameStateMessage_695e,
                            wotc.mtgo.gre.external.messaging.Messages.GREMessageType.SelectNreq,
                        )
                    batch.first().gameStateMessage.pendingMessageCount shouldBe 1
                    published.kind shouldBe case.kind
                    req.context shouldBe case.context
                    req.listType shouldBe case.listType
                    req.optionContext shouldBe case.optionContext
                    req.validationType shouldBe SelectionValidationType.NonRepeatable
                    req.idType shouldBe IdType.InstanceId_ab2c
                    req.minSel shouldBe case.wireMin
                    req.maxSel shouldBe 1
                    req.minWeight shouldBe Int.MIN_VALUE
                    req.maxWeight shouldBe Int.MAX_VALUE
                    req.prompt.promptId shouldBe case.innerPromptId
                    case.innerParameterId?.let {
                        req.prompt.parametersList
                            .single()
                            .promptId shouldBe it
                    }
                    message.prompt.promptId shouldBe case.outerPromptId
                    message.allowCancel shouldBe case.allowCancel
                    req.sourceId shouldBe
                        when (case.kind) {
                            CardSelectKind.LegendRule -> PromptIds.SELECT_N_LEGEND_RULE_SOURCE
                            CardSelectKind.Discard,
                            CardSelectKind.ResolutionMapped,
                            -> 0
                            CardSelectKind.DiscardEffect,
                            CardSelectKind.SacrificeEffect,
                            CardSelectKind.Suspect,
                            CardSelectKind.MutateTopBottom,
                            CardSelectKind.LibraryPutback,
                            CardSelectKind.ManifestDread,
                            CardSelectKind.Resolution,
                            CardSelectKind.ResolutionMapped,
                            CardSelectKind.Learn,
                            -> board.bridge.instanceId(source(board))
                        }
                    if (case.kind == CardSelectKind.LegendRule) {
                        message.prompt.parametersList.map { it.numberValue } shouldContainExactly listOf(0)
                    }
                    if (case.kind == CardSelectKind.LibraryPutback) {
                        message.prompt.parametersList.map { it.numberValue } shouldContainExactly
                            listOf(req.sourceId, req.maxSel)
                    }
                    if (case.kind == CardSelectKind.ManifestDread) {
                        req.unfilteredIdsList shouldContainExactly req.idsList
                        message.prompt.parametersList.map { it.numberValue } shouldContainExactly
                            listOf(req.sourceId, req.maxSel)
                    }
                    if (case.kind == CardSelectKind.Resolution || case.kind == CardSelectKind.ResolutionMapped) {
                        req.unfilteredIdsList shouldContainExactly req.idsList
                        message.prompt.parametersList shouldBe emptyList()
                    }
                    if (case.kind == CardSelectKind.Learn) {
                        message.prompt.parametersList.map { it.numberValue } shouldContainExactly
                            listOf(req.sourceId, req.maxSel)
                    }
                    if (case.kind in privateCandidateKinds) {
                        val exposed =
                            batch
                                .first()
                                .gameStateMessage.gameObjectsList
                                .filter { it.instanceId in req.idsList }
                        exposed.map { it.instanceId } shouldContainExactly req.idsList
                        exposed.map { it.zoneId } shouldContainExactly handles.map(::projectedZoneId)
                        exposed.all { it.visibility == Visibility.Private && it.viewersList == listOf(1) } shouldBe true
                    }
                    if (case.kind == CardSelectKind.ResolutionMapped) {
                        batch
                            .first()
                            .gameStateMessage.gameObjectsList
                            .filter { it.instanceId in req.idsList }
                            .shouldBeEmpty()
                        coordinator.acceptSettled(leyline.testkit.effectCostResp(listOf(req.idsList[1])), published.gameStateId) shouldBe
                            false
                    }
                    coordinator.acceptSettled(leyline.testkit.selectNResp(listOf(req.idsList[1])), published.gameStateId) shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    result.get().optionIndices shouldContainExactly listOf(1)
                    (result.get().handles.single() === handles[1]) shouldBe true
                    coordinator.cardSelect
                        .current()
                        .shouldBeNull()
                }
            }
        }

        test("a cancellable discard window retires on client cancel and releases the engine") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val handles = options(board)
            val result = AtomicReference<CardSelectInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                try {
                    result.set(
                        coordinator.cardSelect.awaitSelection(
                            request(board, PromptSemantic.SelectNDiscard, cancellable = true),
                            handles,
                            3_000,
                        ),
                    )
                } finally {
                    finished.countDown()
                }
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))

            assertSoftly {
                finished.count shouldBe 1L
                coordinator.acceptSettled(leyline.testkit.cancelActionReq(), published.gameStateId) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                // Forge reads the empty payment as declined and unwinds the activation.
                result.get().handles.shouldBeEmpty()
                coordinator.cardSelect.current().shouldBeNull()
            }
        }

        test("a mandatory discard window ignores client cancel and stays pending") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val handles = options(board)
            val finished = CountDownLatch(1)
            Thread {
                try {
                    coordinator.cardSelect.awaitSelection(
                        request(board, PromptSemantic.SelectNDiscard),
                        handles,
                        3_000,
                    )
                } catch (ignored: Exception) {
                    // the window times out rather than resolving; the assertion is that cancel was refused
                } finally {
                    finished.countDown()
                }
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))

            assertSoftly {
                coordinator.admitSettled(leyline.testkit.cancelActionReq(), published.gameStateId) shouldBe
                    SettledPromptAdmission.NotOwned
                coordinator.cardSelect.current() shouldBe published
                finished.count shouldBe 1L
            }
        }

        test("EffectCost requires the Select discriminator and cost-selection payload") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val handles = options(board)
            val result = AtomicReference<CardSelectInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                try {
                    result.set(
                        coordinator.cardSelect.awaitSelection(
                            request(board, PromptSemantic.SelectNDiscard, min = 0),
                            handles,
                            3_000,
                        ),
                    )
                } finally {
                    finished.countDown()
                }
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val projection = board.bridge.projectionStateSnapshot()
            val acceptedBefore = board.bridge.responseAcceptance.responsesAccepted()
            val choicesBefore =
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .snapshotChoiceResults()
            val missingPayload =
                ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.EffectCostResp_097b)
                    .setEffectCostResp(EffectCostResp.newBuilder().setEffectCostType(EffectCostType.Select_a59c))
                    .build()

            assertSoftly {
                coordinator.admitSettled(
                    leyline.testkit.gatherCountersResp(emptyList()),
                    published.gameStateId,
                ) shouldBe SettledPromptAdmission.Rejected(FailureReason.ReqRespMismatch)
                coordinator.admitSettled(missingPayload, published.gameStateId) shouldBe
                    SettledPromptAdmission.Rejected(FailureReason.InvalidOptionSelection)
                coordinator.cardSelect.current() shouldBe published
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .snapshotChoiceResults() shouldBe choicesBefore
                finished.count shouldBe 1L
                coordinator.acceptSettled(leyline.testkit.effectCostResp(emptyList()), published.gameStateId) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().handles shouldBe emptyList()
            }
        }

        listOf(PromptSemantic.SelectNSacrificeEffect to 1, PromptSemantic.SuspectChoice to 2).forEach { (semantic, sentiment) ->
            test("$semantic stages its choice fact before the exact handle returns") {
                val board = startPuzzleAtMain1(puzzle)
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                val handles = options(board)
                val finished = CountDownLatch(1)
                Thread {
                    coordinator.cardSelect.awaitSelection(request(board, semantic), handles, 3_000)
                    finished.countDown()
                }.start()
                val published = awaitPublished(coordinator)
                val id =
                    coordinator
                        .drain(SeatId(1))
                        .flatten()
                        .single { it.hasSelectNReq() }
                        .selectNReq.idsList[1]

                coordinator.acceptSettled(leyline.testkit.selectNResp(listOf(id)), published.gameStateId) shouldBe true
                val result =
                    board.bridge
                        .promptBridge(SeatId(1))
                        .journal
                        .snapshotChoiceResults()
                        .single()
                        .result
                assertSoftly {
                    result.sourceForgeCardId.value shouldBe source(board).id
                    result.choiceValue shouldBe id
                    result.sentiment shouldBe sentiment
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                }
            }
        }
    })

private val privateCandidateKinds =
    setOf(CardSelectKind.ManifestDread, CardSelectKind.Resolution, CardSelectKind.Learn)

private fun projectedZoneId(card: Card): Int =
    when (card.zone.zoneType) {
        ZoneType.Hand -> ZoneIds.P1_HAND
        ZoneType.Library -> ZoneIds.P1_LIBRARY
        ZoneType.Sideboard -> ZoneIds.P1_SIDEBOARD
        else -> error("Unexpected private candidate zone ${card.zone.zoneType}")
    }
