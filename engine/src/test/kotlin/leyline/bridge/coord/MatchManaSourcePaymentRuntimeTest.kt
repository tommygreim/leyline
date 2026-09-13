package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.game.state.ProjectionViewerRole
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchManaSourcePaymentRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:mana source payment runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Savannah Lions;Coral Merfolk;Sol Ring;Forest
            humanlibrary=Forest
            ailibrary=Mountain
            """.trimIndent()

        val whiteTiePuzzle =
            """
            [metadata]
            Name:mana source payment white tie
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Savannah Lions;Savannah Lions;Savannah Lions;Forest
            humanlibrary=Forest
            ailibrary=Mountain
            """.trimIndent()

        fun candidates(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .filter { it.name != "Forest" }

        fun request(
            board: Board,
            semantic: PromptSemantic = PromptSemantic.ConvokeCost,
            defaultIndex: Int = 0,
        ): PromptRequest {
            val cards = candidates(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Forest" }
            return PromptRequest(
                promptType = "choose_cards",
                message = "Choose mana sources",
                options = cards.map { it.name },
                min = 0,
                max = cards.size,
                defaultIndex = defaultIndex,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Battlefield.name)
                    },
                route = PromptRouteResolver.resolve(semantic),
                sourceEntityId = source.id,
                sourceCardName = source.name,
                waterbendManaCost = listOf(ManaColor.Generic to 2, ManaColor.White_afc9 to 1),
                waterbendCostString = "{2}{W}",
            )
        }

        fun awaitPublished(coordinator: MatchCutCoordinator): leyline.bridge.handoff.PublishedManaSourcePaymentInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.manaSourcePayments.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.manaSourcePayments.current()
            }
            return checkNotNull(published)
        }

        test("initial payment keeps Player bytes and gives observers projected state only") {
            data class Published(
                val player: List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>,
                val observer: List<wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage>,
            )

            fun publish(withObserver: Boolean): Published {
                val board = startPuzzleAtMain1(puzzle.replace("humanlibrary=Forest", "humanhand=Mountain\nhumanlibrary=Forest"))
                val coordinator = board.bridge.cutCoordinator
                coordinator.drain(SeatId(1))
                if (withObserver) coordinator.registerViewer(SeatId(2), ProjectionViewerRole.Observer)
                val finished = CountDownLatch(1)
                Thread {
                    coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), 3_000)
                    finished.countDown()
                }.start()

                val interaction = awaitPublished(coordinator)
                val player = coordinator.drain(SeatId(1)).single()
                val observer = if (withObserver) coordinator.drain(SeatId(2)).single() else emptyList()
                coordinator.manaSourcePayments.cancel(interaction.interactionId, interaction.gameStateId).shouldNotBeNull()
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

        test("initial payment and re-prompt commit before the engine is released") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), 3_000))
                finished.countDown()
            }.start()

            val initial = awaitPublished(coordinator)
            val initialBatch = coordinator.drain(SeatId(1)).single()
            val initialReq = initialBatch.single { it.hasPayCostsReq() }.payCostsReq
            val merfolkId = board.instanceId(candidates(board).single { it.name == "Coral Merfolk" }.id)
            assertSoftly {
                initialBatch.map { it.type } shouldContainExactly
                    listOf(
                        wotc.mtgo.gre.external.messaging.Messages.GREMessageType.GameStateMessage_695e,
                        wotc.mtgo.gre.external.messaging.Messages.GREMessageType.PayCostsReq_695e,
                    )
                initialReq.paymentActions.actionsList.shouldHaveSize(3)
                finished.count shouldBe 1
            }

            val receipt = coordinator.manaSourcePayments.select(initial.interactionId, initial.gameStateId, listOf(merfolkId))
            receipt.shouldNotBeNull()
            val rePromptBatch = coordinator.drain(SeatId(1)).single()
            val rePrompt = rePromptBatch.single { it.hasPayCostsReq() }.payCostsReq
            assertSoftly {
                receipt.completed shouldBe false
                rePrompt.manaCostList.single { it.colorList == listOf(ManaColor.Generic) }.count shouldBe 1
                rePrompt.paymentActions.actionsList.none { it.instanceId == merfolkId } shouldBe true
                finished.count shouldBe 1
            }
            coordinator.manaSourcePayments.acknowledgeDelivery(
                initial.interactionId,
                receipt.deliveryToken.shouldNotBeNull(),
            ) shouldBe
                true
            val rePublished = awaitPublished(coordinator)
            coordinator.manaSourcePayments
                .complete(
                    rePublished.interactionId,
                    rePublished.gameStateId,
                    emptyList(),
                ).shouldNotBeNull()
            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(1)
                coordinator.manaSourcePayments
                    .current()
                    .shouldBeNull()
            }
        }

        test("published payment remains an awaitPriority horizon after its signal is consumed") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            coordinator.hidePublishedActionWindow(
                board.bridge
                    .actionBridge(SeatId(1))
                    .getPending()
                    .shouldNotBeNull()
                    .actionId,
            )
            val finished = CountDownLatch(1)
            Thread {
                coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), 3_000)
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            board.bridge.prioritySignal.awaitSignal(3_000) shouldBe true
            assertSoftly {
                board.bridge
                    .actionBridge(SeatId(1))
                    .getPending()
                    .shouldBeNull()
                coordinator.currentBlockingInteraction().shouldBeNull()
                coordinator.targeting
                    .current()
                    .shouldBeNull()
                coordinator.search
                    .current()
                    .shouldBeNull()
                board.bridge.awaitPriorityWithTimeout(25) shouldBe true
            }
            coordinator.drain(SeatId(1))
            coordinator.manaSourcePayments
                .cancel(published.interactionId, published.gameStateId)
                .shouldNotBeNull()
            finished.await(3, TimeUnit.SECONDS) shouldBe true
        }

        test("bulk completion preserves selected instance order and rejects stale or duplicate input") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), 3_000))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val ids = candidates(board).map { board.instanceId(it.id) }
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()
            assertSoftly {
                coordinator.manaSourcePayments
                    .complete(published.interactionId, published.gameStateId + 1, listOf(ids[2]))
                    .shouldBeNull()
                coordinator.manaSourcePayments
                    .complete(published.interactionId, published.gameStateId, listOf(ids[0], ids[0]))
                    .shouldBeNull()
                coordinator.manaSourcePayments
                    .complete(published.interactionId, published.gameStateId, listOf(Int.MAX_VALUE))
                    .shouldBeNull()
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
            }
            val accepted =
                coordinator.manaSourcePayments
                    .complete(published.interactionId, published.gameStateId, listOf(ids[2], ids[0]))
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Forest" }
            assertSoftly {
                accepted.shouldNotBeNull()
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(2, 0)
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeConvokePayments(ForgeCardId(source.id)) shouldHaveSize 2
            }
        }

        test("colored-only Convoke exposes and accepts only sources that can pay the pip") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<leyline.bridge.handoff.ManaSourcePaymentResult>()
            val finished = CountDownLatch(1)
            val coloredRequest =
                request(board).copy(
                    waterbendManaCost = listOf(ManaColor.White_afc9 to 1),
                    waterbendCostString = "{W}",
                )
            Thread {
                result.set(coordinator.manaSourcePayments.awaitPayment(coloredRequest, candidates(board), 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val actions =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasPayCostsReq() }
                    .payCostsReq.paymentActions.actionsList
            val lion = candidates(board).single { it.name == "Savannah Lions" }
            val merfolk = candidates(board).single { it.name == "Coral Merfolk" }
            val lionId = board.instanceId(lion.id)
            val merfolkId = board.instanceId(merfolk.id)

            assertSoftly {
                actions.map { it.instanceId } shouldContainExactly listOf(lionId)
                coordinator.manaSourcePayments
                    .complete(published.interactionId, published.gameStateId, listOf(merfolkId))
                    .shouldBeNull()
                coordinator.manaSourcePayments
                    .complete(published.interactionId, published.gameStateId, listOf(lionId))
                    .shouldNotBeNull()
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().optionIndices shouldContainExactly listOf(0)
                result
                    .get()
                    .shards
                    .single()
                    .costColor shouldBe ManaColor.White_afc9
            }
        }

        test("bulk selection cannot spend one frozen colored shard twice") {
            val board = startPuzzleAtMain1(whiteTiePuzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<leyline.bridge.handoff.ManaSourcePaymentResult>()
            val finished = CountDownLatch(1)
            val coloredRequest =
                request(board).copy(
                    waterbendManaCost = listOf(ManaColor.White_afc9 to 1),
                    waterbendCostString = "{W}",
                    max = 3,
                )
            Thread {
                result.set(coordinator.manaSourcePayments.awaitPayment(coloredRequest, candidates(board), 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val actions =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasPayCostsReq() }
                    .payCostsReq.paymentActions.actionsList
            val ids = actions.map { it.instanceId }
            assertSoftly {
                actions shouldHaveSize 3
                coordinator.manaSourcePayments
                    .complete(published.interactionId, published.gameStateId, ids)
                    .shouldBeNull()
                finished.count shouldBe 1
                coordinator.manaSourcePayments
                    .complete(published.interactionId, published.gameStateId, ids.take(1))
                    .shouldNotBeNull()
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result
                    .get()
                    .shards
                    .single()
                    .costColor shouldBe ManaColor.White_afc9
            }
        }

        test("bulk selection cannot exceed the remaining generic payment capacity") {
            val board = startPuzzleAtMain1(whiteTiePuzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<leyline.bridge.handoff.ManaSourcePaymentResult>()
            val finished = CountDownLatch(1)
            val genericRequest =
                request(board, PromptSemantic.ImproviseCost).copy(
                    waterbendManaCost = listOf(ManaColor.Generic to 1),
                    waterbendCostString = "{1}",
                    max = 3,
                )
            Thread {
                result.set(coordinator.manaSourcePayments.awaitPayment(genericRequest, candidates(board), 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val ids =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasPayCostsReq() }
                    .payCostsReq.paymentActions.actionsList
                    .map { it.instanceId }
            assertSoftly {
                ids shouldHaveSize 3
                coordinator.manaSourcePayments
                    .complete(published.interactionId, published.gameStateId, ids)
                    .shouldBeNull()
                finished.count shouldBe 1
                coordinator.manaSourcePayments
                    .complete(published.interactionId, published.gameStateId, ids.take(1))
                    .shouldNotBeNull()
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result
                    .get()
                    .shards
                    .single()
                    .costColor shouldBe ManaColor.Generic
            }
        }

        test("partial cancel returns accumulated original options") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), 3_000))
                finished.countDown()
            }.start()
            val initial = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val firstId = board.instanceId(candidates(board).first().id)
            val receipt =
                coordinator.manaSourcePayments
                    .select(
                        initial.interactionId,
                        initial.gameStateId,
                        listOf(firstId),
                    ).shouldNotBeNull()
            coordinator.drain(SeatId(1))
            coordinator.manaSourcePayments.acknowledgeDelivery(
                initial.interactionId,
                receipt.deliveryToken.shouldNotBeNull(),
            ) shouldBe
                true
            val rePublished = awaitPublished(coordinator)
            coordinator.manaSourcePayments
                .cancel(rePublished.interactionId, rePublished.gameStateId)
                .shouldNotBeNull()
            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(0)
            }
        }

        test("undo releases the last tapped source and leaves the window open") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.manaSourcePayments.awaitPayment(request(board), candidates(board), 3_000))
                finished.countDown()
            }.start()
            val initial = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val firstId = board.instanceId(candidates(board).first().id)
            val selected =
                coordinator.manaSourcePayments
                    .select(initial.interactionId, initial.gameStateId, listOf(firstId))
                    .shouldNotBeNull()
            coordinator.drain(SeatId(1))
            coordinator.manaSourcePayments.acknowledgeDelivery(
                initial.interactionId,
                selected.deliveryToken.shouldNotBeNull(),
            ) shouldBe true
            val afterSelect = awaitPublished(coordinator)

            val undone =
                coordinator.manaSourcePayments
                    .undo(afterSelect.interactionId, afterSelect.gameStateId)
                    .shouldNotBeNull()
            coordinator.drain(SeatId(1))
            coordinator.manaSourcePayments.acknowledgeDelivery(
                afterSelect.interactionId,
                undone.deliveryToken.shouldNotBeNull(),
            ) shouldBe true
            val afterUndo = awaitPublished(coordinator)

            assertSoftly {
                // The window stays open — undo retires a selection, not the payment.
                undone.completed shouldBe false
                coordinator.manaSourcePayments
                    .cancel(afterUndo.interactionId, afterUndo.gameStateId)
                    .shouldNotBeNull()
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().shouldBeEmpty()
            }
        }

        test("selection cardinality is validated before the engine command is claimed") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val result = AtomicReference<List<Int>>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    coordinator
                        .manaSourcePayments
                        .awaitPayment(request(board).copy(max = 1), candidates(board), 3_000),
                )
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            coordinator.drain(SeatId(1))
            val ids = candidates(board).map { board.instanceId(it.id) }
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()
            val rejected = coordinator.manaSourcePayments.complete(published.interactionId, published.gameStateId, ids.take(2))
            val accepted = coordinator.manaSourcePayments.complete(published.interactionId, published.gameStateId, ids.take(1))

            assertSoftly {
                rejected.shouldBeNull()
                accepted.shouldNotBeNull()
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get() shouldContainExactly listOf(0)
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
            }
        }

        test("Convoke and Improvise retain distinct payment ability shapes") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))

            fun initialAbility(semantic: PromptSemantic): Int {
                val finished = CountDownLatch(1)
                Thread {
                    coordinator.manaSourcePayments.awaitPayment(request(board, semantic), candidates(board), 3_000)
                    finished.countDown()
                }.start()
                val published = awaitPublished(coordinator)
                val action =
                    coordinator
                        .drain(
                            SeatId(1),
                        ).flatten()
                        .single { it.hasPayCostsReq() }
                        .payCostsReq.paymentActions.actionsList
                        .first()
                coordinator.manaSourcePayments
                    .cancel(published.interactionId, published.gameStateId)
                    .shouldNotBeNull()
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                return action.abilityGrpId
            }

            assertSoftly {
                initialAbility(PromptSemantic.ConvokeCost) shouldBe KeywordAbilityIds.CONVOKE_PAYMENT
                initialAbility(PromptSemantic.ImproviseCost) shouldBe KeywordAbilityIds.IMPROVISE
            }
        }
    })
