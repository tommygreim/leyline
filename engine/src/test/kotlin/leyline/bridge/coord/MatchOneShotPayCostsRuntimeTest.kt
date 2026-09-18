package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PayCostsPromptRoute
import leyline.bridge.handoff.PayCostsPromptSourceInput
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.TapPaymentDescriptor
import leyline.bridge.handoff.TapPaymentKind
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PrioritySignal
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionViewerRole
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchOneShotPayCostsRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:one shot PayCosts runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Savannah Lions;Coral Merfolk;Grizzly Bears;Forest
            humanlibrary=Mountain
            ailibrary=Forest
            """.trimIndent()

        fun candidates(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .filter { it.name != "Forest" }

        fun request(
            cards: List<Card>,
            kind: PayCostsRouteKind,
            sourceId: Int,
            tapPayment: TapPaymentDescriptor? = null,
            weights: List<Int> = listOf(2, 1, 1),
        ): PromptRequest {
            val weighted = kind == PayCostsRouteKind.CollectEvidence || tapPayment?.kind == TapPaymentKind.TotalPower
            val semantic = if (tapPayment != null) PromptSemantic.TapPaymentCost else PromptSemantic.SelectNCostSacrifice
            return PromptRequest(
                promptType = "choose_cards",
                message = "Pay cost",
                options = cards.map { it.name },
                min = if (weighted) 0 else 1,
                max = if (weighted) cards.size else 1,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Battlefield.name)
                    },
                route = ResolvedPromptRoute.PayCosts(PayCostsPromptRoute(semantic, kind, kind.name, tapPayment = tapPayment)),
                sourceEntityId = sourceId,
                payCostsPromptSource = PayCostsPromptSourceInput.StackCard(ForgeCardId(sourceId)),
                costSelectionWeights = if (weighted) weights else emptyList(),
                minSelectionWeight = if (weighted) tapPayment?.required ?: 2 else null,
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedOneShotPayCostsInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.oneShotPayCosts.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.oneShotPayCosts.current()
            }
            return checkNotNull(published)
        }

        test("initial PayCosts keeps Player bytes and gives observers projected state only") {
            data class Published(
                val player: List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>,
                val observer: List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>,
            )

            fun publish(withObserver: Boolean): Published {
                val board = startPuzzleAtMain1(puzzle.replace("humanlibrary=Mountain", "humanhand=Mountain\nhumanlibrary=Mountain"))
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                if (withObserver) coordinator.registerViewer(SeatId(2), ProjectionViewerRole.Observer)
                val cards = candidates(board)
                val source =
                    board.human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .single { it.name == "Forest" }
                val finished = CountDownLatch(1)
                Thread {
                    coordinator.oneShotPayCosts.awaitPayment(
                        request(cards, PayCostsRouteKind.Sacrifice, source.id),
                        cards,
                        3_000,
                    )
                    finished.countDown()
                }.start()

                val interaction = awaitPublished(coordinator)
                val player = coordinator.drain(SeatId(1)).single()
                val observer = if (withObserver) coordinator.drain(SeatId(2)).single() else emptyList()
                coordinator.acceptSettled(leyline.testkit.cancelActionReq(), interaction.gameStateId) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                return Published(player, observer)
            }

            val playerOnly = publish(withObserver = false)
            val withObserver = publish(withObserver = true)

            assertSoftly {
                withObserver.player.map { it.toByteArray().toList() } shouldBe
                    playerOnly.player.map { it.toByteArray().toList() }
                withObserver.player.any { it.hasPayCostsReq() } shouldBe true
                withObserver.observer.size shouldBe 1
                withObserver.observer.single().hasGameStateMessage() shouldBe true
                withObserver.observer.none { it.hasPayCostsReq() } shouldBe true
                withObserver.observer
                    .single()
                    .gameStateMessage.zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldBe emptyList()
                withObserver.observer
                    .single()
                    .gameStateMessage.gameObjectsList
                    .none { it.visibility == Visibility.Private } shouldBe true
            }
        }

        test("all seven routes publish one atomic state and PayCosts cut and resolve exact option") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val cards = candidates(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Forest" }
            val tapPayment = checkNotNull(TapPaymentDescriptor.grounded(TapPaymentKind.TotalPower, 2))
            val routes =
                listOf(
                    Triple(PayCostsRouteKind.Sacrifice, PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE, null),
                    Triple(PayCostsRouteKind.SelectCostExileFromGrave, PromptIds.CHOOSE_OR_COST_PAY_EXILE_FROM_GRAVE, null),
                    Triple(PayCostsRouteKind.SelectCostReturnAttacker, PromptIds.NINJUTSU_RETURN_UNBLOCKED_ATTACKER_COST, null),
                    Triple(PayCostsRouteKind.CollectEvidence, PromptIds.COLLECT_EVIDENCE_COST, null),
                    Triple(PayCostsRouteKind.StationTapCost, PromptIds.STATION_TAP_COST, null),
                    Triple(PayCostsRouteKind.EnlistCost, PromptIds.ENLIST_TAP_COST, null),
                    Triple(PayCostsRouteKind.TapPayment, tapPayment.promptId, tapPayment),
                )

            routes.forEach { (kind, promptId, tapDescriptor) ->
                val result = AtomicReference<leyline.bridge.handoff.OneShotPayCostsResult>()
                val finished = CountDownLatch(1)
                Thread {
                    result.set(
                        coordinator
                            .oneShotPayCosts
                            .awaitPayment(request(cards, kind, source.id, tapDescriptor), cards, 3_000),
                    )
                    finished.countDown()
                }.start()

                val published = awaitPublished(coordinator)
                val batch = coordinator.drain(SeatId(1)).single()
                val message = batch.single { it.hasPayCostsReq() }
                val payCosts = message.payCostsReq
                val selection = payCosts.effectCostReq.costSelection
                val selected =
                    selection.idsList
                        .first()
                val sourceInstanceId =
                    board.bridge
                        .projectionStateSnapshot()
                        .identities.forgeIdToInstanceId
                        .getValue(ForgeCardId(source.id))
                        .value
                assertSoftly {
                    batch.map { it.type } shouldContainExactly
                        listOf(GREMessageType.GameStateMessage_695e, GREMessageType.PayCostsReq_695e)
                    message.prompt.promptId shouldBe promptId
                    message.gameStateId shouldBe published.gameStateId
                    message.allowCancel shouldBe AllowCancel.Abort
                    message.allowUndo shouldBe true
                    message.prompt.parametersList
                        .single { it.parameterName == "CardId" }
                        .numberValue shouldBe sourceInstanceId
                    payCosts.hasPaymentActions() shouldBe true
                    payCosts.effectCostReq.effectCostType shouldBe EffectCostType.Select_a59c
                    selection.context shouldBe SelectionContext.NonManaPayment
                    selection.optionContext shouldBe OptionContext.Payment
                    selection.listType shouldBe SelectionListType.Dynamic
                    selection.idType shouldBe IdType.InstanceId_ab2c
                    selection.validationType shouldBe SelectionValidationType.NonRepeatable
                    selection.idsCount shouldBe cards.size
                    selection.maxWeight shouldBe Int.MAX_VALUE
                    finished.count shouldBe 1
                }
                when (kind) {
                    PayCostsRouteKind.CollectEvidence ->
                        assertSoftly {
                            selection.minSel shouldBe 0
                            selection.maxSel shouldBe cards.size
                            selection.minWeight shouldBe 2
                            selection.weightsList shouldContainExactly listOf(2, 1, 1)
                        }
                    PayCostsRouteKind.TapPayment ->
                        assertSoftly {
                            selection.minSel shouldBe 2
                            selection.maxSel shouldBe Int.MAX_VALUE
                            selection.minWeight shouldBe Int.MIN_VALUE
                            selection.weightsList shouldContainExactly listOf(2, 1, 1)
                        }
                    PayCostsRouteKind.SelectCostExileFromGrave ->
                        assertSoftly {
                            message.prompt.promptId shouldBe 5500
                            selection.minSel shouldBe 1
                            selection.maxSel shouldBe 1
                            selection.minWeight shouldBe Int.MIN_VALUE
                            selection.weightsList shouldContainExactly listOf(1, 1, 1)
                        }
                    PayCostsRouteKind.Sacrifice,
                    PayCostsRouteKind.SelectCostReturnAttacker,
                    PayCostsRouteKind.StationTapCost,
                    PayCostsRouteKind.EnlistCost,
                    ->
                        assertSoftly {
                            selection.minSel shouldBe 1
                            selection.maxSel shouldBe 1
                            selection.minWeight shouldBe Int.MIN_VALUE
                            selection.weightsList shouldContainExactly listOf(1, 1, 1)
                        }
                    PayCostsRouteKind.ConvokeCost,
                    PayCostsRouteKind.ImproviseCost,
                    PayCostsRouteKind.WaterbendCost,
                    -> error("Iterative route entered one-shot materializer proof")
                }
                val accepted = coordinator.acceptSettled(leyline.testkit.effectCostResp(listOf(selected)), published.gameStateId)
                assertSoftly {
                    accepted shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    result.get().optionIndices shouldContainExactly listOf(0)
                    (result.get().handles.single() === cards.first()) shouldBe true
                }
            }
        }

        test("grounded tap-payment table preserves every prompt and selection envelope") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val cards = candidates(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Forest" }
            val rows =
                listOf(
                    Triple(TapPaymentKind.TotalPower, 1, 8929),
                    Triple(TapPaymentKind.TotalPower, 2, 8924),
                    Triple(TapPaymentKind.TotalPower, 3, 8925),
                    Triple(TapPaymentKind.TotalPower, 4, 8922),
                    Triple(TapPaymentKind.TapExact, 1, 183),
                    Triple(TapPaymentKind.TapExact, 2, 2595),
                    Triple(TapPaymentKind.TapExact, 3, 3579),
                    Triple(TapPaymentKind.UntapExact, 2, 8840),
                )

            rows.forEach { (kind, required, promptId) ->
                val descriptor = checkNotNull(TapPaymentDescriptor.grounded(kind, required))
                val weights =
                    when {
                        kind == TapPaymentKind.TotalPower && required == 4 -> listOf(-2, 1, 3)
                        kind == TapPaymentKind.TotalPower -> listOf(2, 1, 1)
                        else -> listOf(1, 1, 1)
                    }
                val emittedWeights = weights.map { it.coerceAtLeast(0) }
                val result = AtomicReference<leyline.bridge.handoff.OneShotPayCostsResult>()
                val finished = CountDownLatch(1)
                Thread {
                    result.set(
                        coordinator
                            .oneShotPayCosts
                            .awaitPayment(
                                request(cards, PayCostsRouteKind.TapPayment, source.id, descriptor, weights),
                                cards,
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
                        .single { it.hasPayCostsReq() }
                val selection = req.payCostsReq.effectCostReq.costSelection
                val selectedIds =
                    when {
                        kind != TapPaymentKind.TotalPower -> selection.idsList.take(required)
                        required <= 2 -> selection.idsList.take(1)
                        else -> selection.idsList.take(required - 1)
                    }
                assertSoftly {
                    descriptor.promptId shouldBe promptId
                    req.prompt.promptId shouldBe promptId
                    selection.minSel shouldBe required
                    selection.maxSel shouldBe if (kind == TapPaymentKind.TotalPower) Int.MAX_VALUE else required
                    selection.minWeight shouldBe Int.MIN_VALUE
                    selection.maxWeight shouldBe Int.MAX_VALUE
                    selection.weightsList shouldContainExactly emittedWeights
                    coordinator.acceptSettled(leyline.testkit.effectCostResp(selectedIds), published.gameStateId) shouldBe true
                    finished.await(3, TimeUnit.SECONDS) shouldBe true
                    result.get().handles shouldHaveSize selectedIds.size
                }
            }

            assertSoftly {
                TapPaymentDescriptor.grounded(TapPaymentKind.TotalPower, 5).shouldBeNull()
                TapPaymentDescriptor.grounded(TapPaymentKind.UntapExact, 1).shouldBeNull()
            }
        }

        test("stale duplicate cardinality and weight-invalid responses leave the exact window pending") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val cards = candidates(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Forest" }

            fun launch(request: PromptRequest): Pair<AtomicReference<leyline.bridge.handoff.OneShotPayCostsResult>, CountDownLatch> {
                val result = AtomicReference<leyline.bridge.handoff.OneShotPayCostsResult>()
                val finished = CountDownLatch(1)
                Thread {
                    result.set(coordinator.oneShotPayCosts.awaitPayment(request, cards, 3_000))
                    finished.countDown()
                }.start()
                return result to finished
            }

            val (exactResult, exactFinished) = launch(request(cards, PayCostsRouteKind.Sacrifice, source.id))
            val exact = awaitPublished(coordinator)
            val exactIds =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasPayCostsReq() }
                    .payCostsReq.effectCostReq.costSelection.idsList
            assertSoftly {
                coordinator.acceptSettled(leyline.testkit.effectCostResp(listOf(exactIds.first())), exact.gameStateId + 1) shouldBe
                    false
                coordinator.acceptSettled(leyline.testkit.effectCostResp(listOf(Int.MAX_VALUE)), exact.gameStateId) shouldBe false
                coordinator.acceptSettled(
                    leyline.testkit.effectCostResp(listOf(exactIds.first(), exactIds.first())),
                    exact.gameStateId,
                ) shouldBe
                    false
                coordinator.acceptSettled(leyline.testkit.effectCostResp(emptyList()), exact.gameStateId) shouldBe false
                coordinator.acceptSettled(leyline.testkit.effectCostResp(exactIds), exact.gameStateId) shouldBe false
                coordinator.oneShotPayCosts.current() shouldBe exact
                coordinator.acceptSettled(leyline.testkit.effectCostResp(listOf(exactIds.first())), exact.gameStateId) shouldBe true
                exactFinished.await(3, TimeUnit.SECONDS) shouldBe true
                exactResult.get().optionIndices shouldContainExactly listOf(0)
                coordinator.acceptSettled(leyline.testkit.effectCostResp(listOf(exactIds.first())), exact.gameStateId) shouldBe false
            }

            val (weightedResult, weightedFinished) = launch(request(cards, PayCostsRouteKind.CollectEvidence, source.id))
            val weighted = awaitPublished(coordinator)
            val weightedIds =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasPayCostsReq() }
                    .payCostsReq.effectCostReq.costSelection.idsList
            assertSoftly {
                coordinator.acceptSettled(leyline.testkit.effectCostResp(listOf(weightedIds.last())), weighted.gameStateId) shouldBe
                    false
                coordinator.oneShotPayCosts.current() shouldBe weighted
                coordinator.acceptSettled(leyline.testkit.effectCostResp(listOf(weightedIds.first())), weighted.gameStateId) shouldBe
                    true
                weightedFinished.await(3, TimeUnit.SECONDS) shouldBe true
                weightedResult.get().optionIndices shouldContainExactly listOf(0)
            }

            val (cancelResult, cancelFinished) = launch(request(cards, PayCostsRouteKind.EnlistCost, source.id))
            val cancellable = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            coordinator.acceptSettled(leyline.testkit.cancelActionReq(), cancellable.gameStateId) shouldBe true
            assertSoftly {
                cancelFinished.await(3, TimeUnit.SECONDS) shouldBe true
                cancelResult.get().optionIndices shouldBe emptyList()
                cancelResult.get().handles shouldBe emptyList()
            }
        }

        test("bridge timeout returns the configured default handle and requests later progression") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val cards = candidates(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Forest" }
            val signal = PrioritySignal()
            val result = AtomicReference<leyline.bridge.handoff.OneShotPayCostsResult>()
            val finished = CountDownLatch(1)
            val bridge =
                InteractivePromptBridge(timeoutMs = 25, prioritySignal = signal).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                }
            Thread {
                result.set(
                    bridge.requestOneShotPayCosts(
                        request(cards, PayCostsRouteKind.Sacrifice, source.id).copy(defaultIndex = 1),
                        cards,
                    ),
                )
                finished.countDown()
            }.start()
            awaitPublished(coordinator)
            coordinator.drain(SeatId(1))

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().optionIndices shouldContainExactly listOf(1)
                (result.get().handles.single() === cards[1]) shouldBe true
                signal.awaitSignal(3_000) shouldBe true
                coordinator.oneShotPayCosts
                    .current()
                    .shouldBeNull()
                coordinator.failure().shouldBeNull()
            }
        }
    })
