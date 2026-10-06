package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.DistributionInteractionResult
import leyline.bridge.handoff.DistributionRouteKind
import leyline.bridge.handoff.DistributionTargetRef
import leyline.bridge.handoff.DistributionWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.FailureReason
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchDistributionInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:distribution runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Mountain;Forest
            humanbattlefield=Island
            ailibrary=Forest
            """.trimIndent()

        fun cards(board: Board): List<Card> =
            board.human
                .getZone(ZoneType.Hand)
                .cards
                .toList()

        fun cardTargets(cards: List<Card>): List<DistributionTargetRef.Card> = cards.map { DistributionTargetRef.Card(ForgeCardId(it.id)) }

        test("fixed damage distribution publishes the prompt envelope and validates response") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val window =
                DistributionWindowValue(
                    kind = DistributionRouteKind.Damage,
                    targets = cardTargets(options),
                    amount = 5,
                    minPerTarget = 1,
                    sourceForgeCardId = source.id,
                    sourceForgeAbilityId = 7,
                    sourceIsSpell = true,
                )
            val result = AtomicReference<DistributionInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.distribution.awaitDistribution(window, 3_000))
                finished.countDown()
            }.start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.distribution.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.distribution.current()
            }
            val interaction = checkNotNull(published)
            val batch = coordinator.drain(SeatId(1)).flatten()
            val req = batch.single { it.type == GREMessageType.DistributionReq_695e }
            assertSoftly {
                req.gameStateId shouldBe interaction.gameStateId
                req.allowCancel shouldBe AllowCancel.Abort
                req.allowUndo shouldBe false
                req.prompt.promptId shouldBe PromptIds.DISTRIBUTE_DAMAGE
                req.distributionReq.minAmount shouldBe 5
                req.distributionReq.maxAmount shouldBe 5
                req.distributionReq.minPerTarget shouldBe 1
                req.distributionReq.targetIdsList.size shouldBe 2
                req.distributionReq.validSelectedTargetIdsList shouldContainExactly req.distributionReq.targetIdsList
                coordinator.acceptSettled(
                    leyline.testkit.distributionResp(req.distributionReq.targetIdsList.zip(listOf(2, 3))),
                    interaction.gameStateId,
                ) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().amounts shouldBe mapOf(cardTargets(options)[0] to 2, cardTargets(options)[1] to 3)
                coordinator.distribution.current() shouldBe null
            }
        }

        test("invalid distribution rows do not consume the published window") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val window =
                DistributionWindowValue(
                    kind = DistributionRouteKind.Counters,
                    targets = cardTargets(options),
                    amount = 5,
                    minPerTarget = 1,
                    sourceForgeCardId = source.id,
                    sourceForgeAbilityId = 7,
                    sourceIsSpell = true,
                )
            Thread { coordinator.distribution.awaitDistribution(window, 3_000) }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.distribution.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.distribution.current()
            }
            val interaction = checkNotNull(published)
            val targetIds =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasDistributionReq() }
                    .distributionReq.targetIdsList
            val acceptedBefore = board.bridge.responseAcceptance.responsesAccepted()
            assertSoftly {
                coordinator.acceptSettled(
                    leyline.testkit.distributionResp(listOf(targetIds[0] to 1)),
                    interaction.gameStateId,
                ) shouldBe
                    false
                coordinator.acceptSettled(
                    leyline.testkit.distributionResp(targetIds.map { it to 1 }),
                    interaction.gameStateId,
                ) shouldBe false
                coordinator.distribution.current() shouldBe interaction
                coordinator.admitSettled(
                    leyline.testkit.cancelActionReq(),
                    interaction.gameStateId + 1,
                    Int.MAX_VALUE,
                ) shouldBe SettledPromptAdmission.NotOwned
                coordinator
                    .admitSettled(
                        leyline.testkit.cancelActionReq(),
                        interaction.gameStateId,
                        Int.MAX_VALUE,
                    ).shouldBeInstanceOf<SettledPromptAdmission.Accepted>()
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore
                coordinator.admitSettled(
                    leyline.testkit.cancelActionReq(),
                    interaction.gameStateId,
                    Int.MIN_VALUE,
                ) shouldBe SettledPromptAdmission.Rejected(FailureReason.ReqRespMismatch)
                coordinator.prompts.settled.reset()
                coordinator.admitSettled(
                    leyline.testkit.cancelActionReq(),
                    interaction.gameStateId,
                    Int.MIN_VALUE,
                ) shouldBe SettledPromptAdmission.NotOwned
            }
        }

        test("wire distribution rejects an unmapped target instance id") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val options = cards(board)
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val window =
                DistributionWindowValue(
                    kind = DistributionRouteKind.Damage,
                    targets = cardTargets(options),
                    amount = 5,
                    minPerTarget = 1,
                    sourceForgeCardId = source.id,
                    sourceForgeAbilityId = 7,
                    sourceIsSpell = true,
                )
            Thread { coordinator.distribution.awaitDistribution(window, 3_000) }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.distribution.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.distribution.current()
            }
            val interaction = checkNotNull(published)
            val targetIds =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasDistributionReq() }
                    .distributionReq
                    .targetIdsList
            assertSoftly {
                coordinator.acceptSettled(
                    leyline.testkit.distributionResp(listOf(999_999 to 2, targetIds[1] to 3)),
                    interaction.gameStateId,
                ) shouldBe false
                coordinator.distribution.current() shouldBe interaction
                coordinator.acceptSettled(leyline.testkit.cancelActionReq(), interaction.gameStateId) shouldBe true
            }
        }

        test("card and player targets remain distinct when their numeric ids match") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val option = cards(board).first()
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val cardTarget = DistributionTargetRef.Card(ForgeCardId(option.id))
            val playerTarget = DistributionTargetRef.Player(SeatId(option.id))
            cardTarget.id.value shouldBe playerTarget.id.value
            val window =
                DistributionWindowValue(
                    kind = DistributionRouteKind.Damage,
                    targets = listOf(cardTarget, playerTarget),
                    amount = 5,
                    minPerTarget = 1,
                    sourceForgeCardId = source.id,
                    sourceForgeAbilityId = 7,
                    sourceIsSpell = true,
                )
            val result = AtomicReference<DistributionInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    coordinator.distribution.awaitDistribution(
                        window,
                        3_000,
                    ),
                )
                finished.countDown()
            }.start()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.distribution.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.distribution.current()
            }
            val interaction = checkNotNull(published)
            val targetIds =
                coordinator
                    .drain(SeatId(1))
                    .flatten()
                    .single { it.hasDistributionReq() }
                    .distributionReq
                    .targetIdsList
            assertSoftly {
                targetIds.distinct().size shouldBe 2
                coordinator.acceptSettled(
                    leyline.testkit.distributionResp(listOf(targetIds[0] to 2, targetIds[1] to 3)),
                    interaction.gameStateId,
                ) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().amounts shouldBe mapOf(cardTarget to 2, playerTarget to 3)
            }
        }

        test("distribution window rejects fewer than two targets or a fully constrained total") {
            assertSoftly {
                shouldThrow<IllegalArgumentException> {
                    DistributionWindowValue(
                        kind = DistributionRouteKind.Damage,
                        targets = listOf(DistributionTargetRef.Card(ForgeCardId(10))),
                        amount = 3,
                        minPerTarget = 1,
                        sourceForgeCardId = 20,
                        sourceForgeAbilityId = 30,
                        sourceIsSpell = true,
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    DistributionWindowValue(
                        kind = DistributionRouteKind.Counters,
                        targets =
                            listOf(
                                DistributionTargetRef.Card(ForgeCardId(10)),
                                DistributionTargetRef.Card(ForgeCardId(11)),
                            ),
                        amount = 2,
                        minPerTarget = 1,
                        sourceForgeCardId = 20,
                        sourceForgeAbilityId = 30,
                        sourceIsSpell = true,
                    )
                }
                shouldThrow<IllegalArgumentException> {
                    DistributionWindowValue(
                        kind = DistributionRouteKind.Damage,
                        targets =
                            listOf(
                                DistributionTargetRef.Card(ForgeCardId(10)),
                                DistributionTargetRef.Card(ForgeCardId(11)),
                            ),
                        amount = 4,
                        minPerTarget = 2,
                        sourceForgeCardId = 20,
                        sourceForgeAbilityId = 30,
                        sourceIsSpell = true,
                    )
                }
            }
        }
    })
