package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SearchLibraryValue
import leyline.bridge.handoff.SearchSourceValue
import leyline.bridge.handoff.SearchWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PrioritySignal
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.Group
import wotc.mtgo.gre.external.messaging.Messages.SearchFromGroupsResp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchSearchInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:search runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Forest
            humanlibrary=Mountain;Forest
            ailibrary=Forest
            """.trimIndent()

        fun request(
            board: Board,
            min: Int = 1,
            defaultIndex: Int = 0,
            source: SearchSourceValue? = null,
            grouped: Boolean = false,
        ): PromptRequest {
            val candidates = board.human.getZone(ZoneType.Library).cards
            return PromptRequest(
                promptType = "choose_cards",
                message = "Search",
                options = candidates.map { it.name },
                min = min,
                max = 1,
                defaultIndex = defaultIndex,
                candidateRefs =
                    candidates.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Library.name)
                    },
                route =
                    if (grouped) {
                        ResolvedPromptRoute.Search(PromptSemantic.GroupedSearch)
                    } else {
                        ResolvedPromptRoute.Search(PromptSemantic.Search)
                    },
                searchSource = source,
                searchGroupOptionIndices = if (grouped) listOf(listOf(0), listOf(1)) else emptyList(),
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedSearchInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.search.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.search.current()
            }
            return checkNotNull(published)
        }

        test("opponent library search exposes the full searched library to its chooser") {
            val board = startPuzzleAtMain1(puzzle.replace("ailibrary=Forest", "ailibrary=Mountain;Forest;Forest"))
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val candidates =
                board.ai
                    .getZone(ZoneType.Library)
                    .cards
                    .filter { it.name == "Forest" }
            val base = request(board)
            val searchRequest =
                base.copy(
                    options = candidates.map { it.name },
                    candidateRefs =
                        candidates.mapIndexed { index, card ->
                            PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Library.name)
                        },
                )
            val finished = CountDownLatch(1)
            Thread {
                coordinator.search.awaitSearch(searchRequest, 3_000)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val batch = coordinator.drain(SeatId(1)).flatten()
            val search = batch.single { it.hasSearchReq() }.searchReq
            search.zonesToSearchList shouldContainExactly listOf(ZoneIds.libraryOf(SeatId(2)))
            search.itemsToSearchCount shouldBe 3
            search.itemsSoughtCount shouldBe 2
            val objects =
                batch
                    .filter {
                        it.hasGameStateMessage()
                    }.flatMap { it.gameStateMessage.gameObjectsList }
                    .associateBy { it.instanceId }
            search.itemsToSearchList.forEach { id -> objects.getValue(id).grpId shouldBeGreaterThan 0 }
            coordinator.acceptSettled(leyline.testkit.searchResp(listOf(search.itemsSoughtList.first())), published.gameStateId) shouldBe
                true
            finished.await(3, TimeUnit.SECONDS) shouldBe true
        }

        test("empty search candidates retain the opponent library and allow fail to find") {
            val board = startPuzzleAtMain1(puzzle.replace("ailibrary=Forest", "ailibrary=Mountain;Forest;Forest"))
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val library =
                board.ai
                    .getZone(ZoneType.Library)
                    .cards
                    .map { ForgeCardId(it.id) }
            val searchRequest =
                request(board, min = 0).copy(
                    options = emptyList(),
                    candidateRefs = emptyList(),
                    searchLibrary = SearchLibraryValue(SeatId(2), library),
                )
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.search.awaitSearch(searchRequest, 3_000))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val batch = coordinator.drain(SeatId(1)).flatten()
            val search = batch.single { it.hasSearchReq() }.searchReq
            search.zonesToSearchList shouldContainExactly listOf(ZoneIds.libraryOf(SeatId(2)))
            search.itemsToSearchCount shouldBe 3
            search.itemsSoughtList.shouldBeEmpty()
            val objects =
                batch
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .associateBy { it.instanceId }
            search.itemsToSearchList.forEach { id -> objects.getValue(id).grpId shouldBeGreaterThan 0 }
            coordinator.acceptSettled(leyline.testkit.searchResp(emptyList()), published.gameStateId) shouldBe true
            finished.await(3, TimeUnit.SECONDS) shouldBe true
            result.get() shouldContainExactly listOf(0)
        }

        test("search exposes only the exact library view supplied by Forge") {
            val board = startPuzzleAtMain1(puzzle.replace("ailibrary=Forest", "ailibrary=Mountain;Forest;Forest"))
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val initial = handshakeFull(board.game, board.bridge, 1)
            val library =
                board.ai
                    .getZone(ZoneType.Library)
                    .cards
                    .map { ForgeCardId(it.id) }
            val searchRequest =
                request(board, min = 0).copy(
                    options = emptyList(),
                    candidateRefs = emptyList(),
                    searchLibrary = SearchLibraryValue(SeatId(2), library.take(1)),
                )
            val finished = CountDownLatch(1)
            Thread {
                coordinator.search.awaitSearch(searchRequest, 3_000)
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val batch = coordinator.drain(SeatId(1)).flatten()
            val search = batch.single { it.hasSearchReq() }.searchReq
            search.itemsToSearchCount shouldBe 1
            val objects =
                (listOf(initial) + batch.filter { it.hasGameStateMessage() }.map { it.gameStateMessage })
                    .flatMap { it.gameObjectsList }
                    .associateBy { it.instanceId }
            objects.getValue(search.itemsToSearchList.single()).grpId shouldBeGreaterThan 0
            library.drop(1).forEach { fid ->
                val iid = board.bridge.getOrAllocInstanceId(fid).value
                // Hidden library cards may be omitted entirely, rather than
                // published as identity-zero objects. Neither exposes a title.
                (objects[iid]?.grpId ?: 0) shouldBe 0
            }
            coordinator.acceptSettled(leyline.testkit.searchResp(emptyList()), published.gameStateId) shouldBe true
            finished.await(3, TimeUnit.SECONDS) shouldBe true
        }

        test("initial cut publishes library objects and SearchReq atomically") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.search.awaitSearch(request(board), 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val batches = coordinator.drain(SeatId(1))
            val batch = batches.single()
            val requestIndex = batch.indexOfFirst { it.hasSearchReq() }
            val search = batch[requestIndex]
            val libraryObjects =
                batch
                    .take(requestIndex)
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .map { it.instanceId }
                    .toSet()

            assertSoftly {
                batch.map {
                    when {
                        it.hasGameStateMessage() -> "state"
                        it.hasSearchReq() -> "search"
                        else -> "other"
                    }
                } shouldContainExactly listOf("state", "state", "search")
                search.searchReq.sourceId shouldBe 0
                search.gameStateId shouldBe published.gameStateId
                search.searchReq.itemsToSearchList.shouldHaveSize(2)
                search.searchReq.itemsSoughtList.shouldHaveSize(2)
                search.searchReq.zonesToSearchList shouldContainExactly listOf(ZoneIds.libraryOf(SeatId(1)))
                search.searchReq.maxFind shouldBe 1
                libraryObjects.containsAll(search.searchReq.itemsToSearchList) shouldBe true
                finished.count shouldBe 1
            }

            val selected = search.searchReq.itemsSoughtList.first()
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()
            assertSoftly {
                coordinator.acceptSettled(leyline.testkit.searchResp(listOf(selected)), published.gameStateId + 1) shouldBe false
                coordinator.acceptSettled(leyline.testkit.searchResp(listOf(selected, selected)), published.gameStateId) shouldBe false
                coordinator.acceptSettled(leyline.testkit.searchResp(listOf(Int.MAX_VALUE)), published.gameStateId) shouldBe false
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                coordinator.drain(SeatId(1)) shouldBe emptyList()
                finished.count shouldBe 1
            }
            assertSoftly {
                coordinator.acceptSettled(leyline.testkit.searchResp(listOf(selected)), published.gameStateId) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(0)
                coordinator.search
                    .current()
                    .shouldBeNull()
            }
        }

        test("source-free fail-to-find and typecycling source preserve exact SearchReq shapes") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val sourceCard =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    coordinator.search.awaitSearch(
                        request(
                            board,
                            min = 0,
                            source =
                                SearchSourceValue(
                                    hostCardId = ForgeCardId(sourceCard.id),
                                    forgeAbilityId = 98_765,
                                    abilityOnStack = true,
                                    typeCycling = true,
                                ),
                        ),
                        3_000,
                    ),
                )
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val requestMessage = coordinator.drain(SeatId(1)).flatten().single { it.hasSearchReq() }
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()
            val acceptedResponses = board.bridge.responseAcceptance.responsesAccepted()
            assertSoftly {
                requestMessage.prompt.promptId shouldBe PromptIds.SEARCH_TYPECYCLING
                requestMessage.searchReq.sourceId shouldBeGreaterThan 0
                requestMessage.searchReq.sourceId shouldNotBe
                    requestMessage.prompt.parametersList
                        .first()
                        .numberValue
                coordinator.acceptSettled(
                    ClientToGREMessage.newBuilder().setType(ClientMessageType.SearchResp_097b).build(),
                    published.gameStateId,
                ) shouldBe false
                coordinator.acceptSettled(
                    leyline.testkit
                        .groupedSearchFailResp()
                        .toBuilder()
                        .setType(ClientMessageType.SearchResp_097b)
                        .build(),
                    published.gameStateId,
                ) shouldBe false
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedResponses
                coordinator.search.current() shouldBe published
                finished.await(100, TimeUnit.MILLISECONDS) shouldBe false
                coordinator.acceptSettled(leyline.testkit.searchResp(emptyList()), published.gameStateId) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(2)
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedResponses + 1
                coordinator.search.current().shouldBeNull()
            }
        }

        test("grouped SearchFromGroupsReq preserves partitions and admits only exact rows") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.search.awaitSearch(request(board, grouped = true), 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val search = coordinator.drain(SeatId(1)).flatten().single { it.hasSearchFromGroupsReq() }
            val req = search.searchFromGroupsReq
            val baseline = board.bridge.projectionStateSnapshot()
            val sequence = board.counter.snapshot()
            val requestMsgId = sequence.lastPromptMsgId
            val acceptedResponses = board.bridge.responseAcceptance.responsesAccepted()

            fun groupedRows(vararg rows: Group): ClientToGREMessage =
                ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.SearchFromGroupsResp_097b)
                    .setSearchFromGroupsResp(SearchFromGroupsResp.newBuilder().addAllGroups(rows.asList()))
                    .build()
            assertSoftly {
                search.prompt.promptId shouldBe PromptIds.SEARCH_FROM_GROUPS
                req.sourceId shouldBe
                    search.prompt.parametersList
                        .single()
                        .numberValue
                req.groupingStyle shouldBe wotc.mtgo.gre.external.messaging.Messages.GroupingStyle.SingleGroup
                req.zonesToSearchList shouldContainExactly listOf(ZoneIds.libraryOf(SeatId(1)))
                req.minFind shouldBe 0
                req.maxFind shouldBe 1
                req.additionalZonesList shouldBe emptyList()
                req.allowFailToFind shouldBe wotc.mtgo.gre.external.messaging.Messages.AllowFailToFind.None_a8f7
                search.allowCancel shouldBe wotc.mtgo.gre.external.messaging.Messages.AllowCancel.No_a526
                search.prompt.parametersCount shouldBe 1
                req.groupsList.map { it.groupId } shouldContainExactly listOf(5003, 5004)
                req.groupsList.all { it.maxSelect == 1 } shouldBe true
                req.groupsList.flatMap { it.idsList }.distinct() shouldHaveSize 2
                coordinator.acceptSettled(
                    leyline.testkit.groupedSearchResp(5003, listOf(req.groupsList[0].idsList[0]), 1),
                    published.gameStateId,
                    requestMsgId + 1,
                ) shouldBe false
                coordinator.acceptSettled(
                    leyline.testkit.groupedSearchResp(5003, listOf(req.groupsList[0].idsList[0]), 1),
                    published.gameStateId + 1,
                    requestMsgId,
                ) shouldBe false
                coordinator.acceptSettled(leyline.testkit.searchResp(listOf(req.groupsList[0].idsList[0])), published.gameStateId) shouldBe
                    false
                coordinator.acceptSettled(
                    leyline.testkit.groupedSearchResp(9000, listOf(req.groupsList[0].idsList[0]), 1),
                    published.gameStateId,
                ) shouldBe
                    false
                coordinator.acceptSettled(
                    groupedRows(req.groupsList[0], req.groupsList[0]),
                    published.gameStateId,
                ) shouldBe false
                coordinator.acceptSettled(
                    groupedRows(req.groupsList[0], req.groupsList[1]),
                    published.gameStateId,
                ) shouldBe false
                coordinator.acceptSettled(
                    leyline.testkit.groupedSearchResp(5003, listOf(req.groupsList[0].idsList[0]), 2),
                    published.gameStateId,
                ) shouldBe
                    false
                coordinator.acceptSettled(
                    leyline.testkit.groupedSearchResp(5003, listOf(req.groupsList[1].idsList[0]), 1),
                    published.gameStateId,
                ) shouldBe
                    false
                coordinator.acceptSettled(
                    leyline.testkit.groupedSearchResp(5003, listOf(req.groupsList[0].idsList[0], req.groupsList[0].idsList[0]), 1),
                    published.gameStateId,
                ) shouldBe
                    false
                coordinator.acceptSettled(
                    leyline.testkit.groupedSearchResp(
                        5003,
                        listOf(req.groupsList[0].idsList[0], req.groupsList[1].idsList[0]),
                        1,
                    ),
                    published.gameStateId,
                ) shouldBe false
                board.bridge.projectionStateSnapshot() shouldBe baseline
                board.counter.snapshot() shouldBe sequence
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedResponses
                finished.await(100, TimeUnit.MILLISECONDS) shouldBe false
                coordinator.search.current().shouldNotBeNull()
            }
            assertSoftly {
                coordinator.acceptSettled(
                    leyline.testkit.groupedSearchResp(5003, listOf(req.groupsList[0].idsList[0]), 1),
                    published.gameStateId,
                ) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(0)
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedResponses + 1
                coordinator.search.current().shouldBeNull()
                coordinator.acceptSettled(
                    leyline.testkit.groupedSearchResp(5003, listOf(req.groupsList[0].idsList[0]), 1),
                    published.gameStateId,
                ) shouldBe false
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedResponses + 1
            }
        }

        test("grouped search admits a zero-row fail-to-find when minFind is zero") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.search.awaitSearch(request(board, min = 0, grouped = true), 3_000))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val requestMessage = coordinator.drain(SeatId(1)).flatten().single { it.hasSearchFromGroupsReq() }
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()
            val acceptedResponses = board.bridge.responseAcceptance.responsesAccepted()
            assertSoftly {
                requestMessage.searchFromGroupsReq.allowFailToFind shouldBe
                    wotc.mtgo.gre.external.messaging.Messages.AllowFailToFind.Any
                coordinator.acceptSettled(
                    ClientToGREMessage.newBuilder().setType(ClientMessageType.SearchFromGroupsResp_097b).build(),
                    published.gameStateId,
                ) shouldBe false
                coordinator.acceptSettled(
                    leyline.testkit
                        .searchResp(emptyList())
                        .toBuilder()
                        .setType(ClientMessageType.SearchFromGroupsResp_097b)
                        .build(),
                    published.gameStateId,
                ) shouldBe false
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedResponses
                coordinator.search.current() shouldBe published
                finished.await(100, TimeUnit.MILLISECONDS) shouldBe false
                coordinator.acceptSettled(leyline.testkit.groupedSearchFailResp(), published.gameStateId) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(2)
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedResponses + 1
                coordinator.search.current().shouldBeNull()
            }
        }

        test("response invalidates the reveal baseline before the engine resumes") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val engineFinished = CountDownLatch(1)
            Thread {
                coordinator.search.awaitSearch(request(board), 3_000)
                engineFinished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val selected =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSearchReq() }
                    .searchReq.itemsSoughtList
                    .first()
            val resetObserved = CountDownLatch(1)
            val allowRelease = CountDownLatch(1)
            val baseline = AtomicReference<Any?>()
            coordinator.search.afterBaselineResetBeforeRelease = {
                baseline.set(
                    board.bridge
                        .projectionStateSnapshot()
                        .viewerCursors[SeatId(1)]
                        ?.previousSnapshot,
                )
                resetObserved.countDown()
                check(allowRelease.await(3, TimeUnit.SECONDS))
            }
            val accepted = AtomicReference<Boolean>()
            val submitFinished = CountDownLatch(1)
            Thread {
                try {
                    accepted.set(coordinator.acceptSettled(leyline.testkit.searchResp(listOf(selected)), published.gameStateId))
                } finally {
                    submitFinished.countDown()
                }
            }.start()

            resetObserved.await(3, TimeUnit.SECONDS) shouldBe true
            assertSoftly {
                baseline.get().shouldBeNull()
                engineFinished.count shouldBe 1
            }
            allowRelease.countDown()
            assertSoftly {
                engineFinished.await(3, TimeUnit.SECONDS) shouldBe true
                submitFinished.await(3, TimeUnit.SECONDS) shouldBe true
                accepted.get() shouldBe true
            }
            coordinator.search.afterBaselineResetBeforeRelease = null
        }

        test("stale shared baseline reset terminalizes search and releases its waiter") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val waiterFailure = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            Thread {
                try {
                    coordinator.search.awaitSearch(request(board), null)
                } catch (ex: Throwable) {
                    waiterFailure.set(ex)
                } finally {
                    finished.countDown()
                }
            }.start()
            val published = awaitPublished(coordinator)
            val selected =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasSearchReq() }
                    .searchReq.itemsSoughtList
                    .first()
            val prior = board.bridge.projectionStateSnapshot()
            coordinator.search.beforeBaselineResetInstall = {
                board.bridge.getOrAllocInstanceId(ForgeCardId(9_999_999))
            }

            val terminal =
                shouldThrow<PlaybackTerminalFailure> {
                    coordinator.acceptSettled(leyline.testkit.searchResp(listOf(selected)), published.gameStateId)
                }

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                waiterFailure.get() shouldBe terminal
                coordinator.failure() shouldBe terminal
                coordinator.search.current().shouldBeNull()
                board.bridge.committedSequence() shouldBe prior.sequence
                coordinator.drain(SeatId(1)).shouldBeEmpty()
            }
        }

        test("delivery retains Search ahead of its outer blocking window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val blockingFailure = AtomicReference<Throwable>()
            val blockingFinished = CountDownLatch(1)
            Thread {
                runCatching {
                    coordinator.awaitOptional(
                        BlockingInteraction.Optional(ForgeCardId(source.id), true, null, null),
                        3_000,
                        false,
                    )
                }.onFailure(blockingFailure::set)
                blockingFinished.countDown()
            }.start()
            val blockingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (coordinator.currentBlockingInteraction() == null && System.nanoTime() < blockingDeadline) {
                Thread.onSpinWait()
            }
            checkNotNull(coordinator.currentBlockingInteraction())

            val searchFailure = AtomicReference<Throwable>()
            val searchFinished = CountDownLatch(1)
            Thread {
                runCatching { coordinator.search.awaitSearch(request(board), null) }
                    .onFailure(searchFailure::set)
                searchFinished.countDown()
            }.start()
            awaitPublished(coordinator)

            val terminal = shouldThrow<PlaybackTerminalFailure> { coordinator.failDelivery(IllegalStateException("delivery unavailable")) }

            assertSoftly {
                terminal.pendingPromptCut
                    .shouldNotBeNull()
                    .interaction
                    .shouldBeInstanceOf<SearchWindowValue>()
                blockingFinished.await(3, TimeUnit.SECONDS) shouldBe true
                searchFinished.await(3, TimeUnit.SECONDS) shouldBe true
                blockingFailure.get() shouldBe terminal
                searchFailure.get() shouldBe terminal
            }
        }

        test("bridge timeout returns the configured default and requests later progression") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val signal = PrioritySignal()
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            val bridge =
                InteractivePromptBridge(timeoutMs = 25, prioritySignal = signal).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                }
            Thread {
                result.set(bridge.requestChoice(request(board, min = 0, defaultIndex = 1)))
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val publishedSequence = board.bridge.committedSequence()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(1)
                signal.awaitSignal(3_000) shouldBe true
                board.bridge.committedSequence() shouldBe publishedSequence
                coordinator.drain(SeatId(1)).shouldBeEmpty()
                coordinator.search
                    .current()
                    .shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
        }
    })
