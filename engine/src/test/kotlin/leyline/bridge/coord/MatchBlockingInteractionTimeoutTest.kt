package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchBlockingInteractionTimeoutTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:blocking interaction timeout
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Forest
            humanbattlefield=Forest
            humanlibrary=Forest
            ailibrary=Forest
            """.trimIndent()

        fun numeric(board: leyline.testkit.Board) =
            BlockingInteraction.Numeric(
                ForgeCardId(
                    board.human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .first()
                        .id,
                ),
                0,
                2,
                1,
            )

        test("answered blocking window is hidden before engine cleanup") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val returned = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            Thread {
                runCatching { board.bridge.cutCoordinator.awaitNumeric(numeric(board), 3_000) }
                    .onSuccess { returned.countDown() }
                    .onFailure(failure::set)
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (board.bridge.cutCoordinator.currentBlockingInteraction() == null &&
                failure.get() == null &&
                System.nanoTime() < deadline
            ) {
                Thread.onSpinWait()
            }
            check(failure.get() == null) { "numeric interaction failed: ${failure.get()}" }
            val pending = checkNotNull(board.bridge.cutCoordinator.currentBlockingInteraction())

            assertSoftly {
                board.bridge.cutCoordinator.submitNumericAnswer(pending.interactionId, pending.gameStateId, 2) shouldBe true
                board.bridge.cutCoordinator.currentBlockingInteraction() shouldBe null
                board.bridge.cutCoordinator.submitNumericAnswer(pending.interactionId, pending.gameStateId, 1) shouldBe false
            }
            check(returned.await(3, TimeUnit.SECONDS))
        }

        test("unbounded library-placement window accepts a choice and releases on shutdown") {
            val board = startPuzzleAtMain1(puzzle)
            val source = checkNotNull(numeric(board).sourceId)
            val interaction = BlockingInteraction.TopOrBottom(source, source)
            val returned = AtomicReference<Boolean>()
            val failed = AtomicReference<Throwable>()
            val engine =
                Thread {
                    runCatching { board.bridge.cutCoordinator.awaitTopOrBottom(interaction, null, false) }
                        .onSuccess(returned::set)
                        .onFailure(failed::set)
                }.also { it.start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (board.bridge.cutCoordinator.currentBlockingInteraction() == null &&
                failed.get() == null &&
                System.nanoTime() < deadline
            ) {
                Thread.onSpinWait()
            }
            val pending = checkNotNull(board.bridge.cutCoordinator.currentBlockingInteraction())
            board.bridge.cutCoordinator.interactions
                .submitTopOrBottom(pending.interactionId, pending.gameStateId, true) shouldBe true
            engine.join(3_000)
            returned.get() shouldBe true
            failed.get() shouldBe null

            val stopped = CountDownLatch(1)
            val secondEngine =
                Thread {
                    runCatching { board.bridge.cutCoordinator.awaitTopOrBottom(interaction, null, false) }
                        .onFailure { stopped.countDown() }
                }.also { it.start() }
            val secondDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (board.bridge.cutCoordinator.currentBlockingInteraction() == null &&
                System.nanoTime() < secondDeadline
            ) {
                Thread.onSpinWait()
            }
            checkNotNull(board.bridge.cutCoordinator.currentBlockingInteraction())
            board.bridge.cutCoordinator.shutdown()
            check(stopped.await(3, TimeUnit.SECONDS))
            secondEngine.join(3_000)
            secondEngine.isAlive shouldBe false
        }

        test("timed-out blocking window rejects a late answer without output or state change") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val returned = AtomicReference<Int>()
            val engine = Thread { returned.set(board.bridge.cutCoordinator.awaitNumeric(numeric(board), 200)) }.also { it.start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var published = board.bridge.cutCoordinator.currentBlockingInteraction()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = board.bridge.cutCoordinator.currentBlockingInteraction()
            }
            val exact = checkNotNull(published)
            engine.join(3_000)
            val projection = board.bridge.projectionStateSnapshot()
            val counter = board.counter.snapshot()
            val queued = board.bridge.cutCoordinator.drain(SeatId(1))

            assertSoftly {
                returned.get() shouldBe 1
                board.bridge.cutCoordinator.submitNumericAnswer(exact.interactionId, exact.gameStateId, 2) shouldBe false
                board.bridge.projectionStateSnapshot() shouldBe projection
                board.counter.snapshot() shouldBe counter
                board.bridge.cutCoordinator.drain(SeatId(1)) shouldBe emptyList()
                queued.flatten().any { it.hasNumericInputReq() } shouldBe true
            }
        }

        test("answer completed before timeout retirement wins the interaction") {
            val board = startPuzzleAtMain1(puzzle)
            board.bridge.cutCoordinator.drain(SeatId(1))
            val timedOut = CountDownLatch(1)
            val release = CountDownLatch(1)
            board.bridge.cutCoordinator.beforeBlockingTimeoutClaim = {
                timedOut.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            val returned = AtomicReference<Int>()
            val engine = Thread { returned.set(board.bridge.cutCoordinator.awaitNumeric(numeric(board), 20)) }.also { it.start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var pending = board.bridge.cutCoordinator.currentBlockingInteraction()
            while (pending == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                pending = board.bridge.cutCoordinator.currentBlockingInteraction()
            }
            val exact = checkNotNull(pending)
            check(timedOut.await(3, TimeUnit.SECONDS))

            board.bridge.cutCoordinator.submitNumericAnswer(exact.interactionId, exact.gameStateId, 2) shouldBe true
            release.countDown()
            engine.join(3_000)
            board.bridge.cutCoordinator.beforeBlockingTimeoutClaim = null

            returned.get() shouldBe 2
            board.bridge.cutCoordinator.currentBlockingInteraction() shouldBe null
        }
    })
