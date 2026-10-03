package leyline.board.annotations

import forge.game.card.CounterEnumType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CounterType

class PlayerCounterAnnotationTest :
    BoardTest({
        test("energy gain and payment publish player totals and exact deltas") {
            val board = startWithBoard { _, _, _ -> }
            val gain = board.snapshotDiff { board.human.setCounters(CounterEnumType.ENERGY, 3, board.human, true) }
            val added = gain.annotationsList.single { AnnotationType.CounterAdded in it.typeList }
            val state = gain.persistentAnnotationsList.single { AnnotationType.Counter_803b in it.typeList }
            assertSoftly {
                added.affectedIdsList shouldBe listOf(1)
                added.detailInt("counter_type") shouldBe CounterType.Energy.number
                added.detailInt("transaction_amount") shouldBe 3
                state.detailInt("count") shouldBe 3
            }
            val payment = board.snapshotDiff { board.human.subtractCounter(CounterEnumType.ENERGY, 2, board.human) }
            val removed = payment.annotationsList.single { AnnotationType.CounterRemoved in it.typeList }
            assertSoftly {
                removed.detailInt("transaction_amount") shouldBe 2
                payment.persistentAnnotationsList.single { AnnotationType.Counter_803b in it.typeList }.detailInt("count") shouldBe 1
            }
            val exhausted = board.snapshotDiff { board.human.subtractCounter(CounterEnumType.ENERGY, 1, board.human) }
            exhausted.annotationsList.single { AnnotationType.CounterRemoved in it.typeList }.detailInt("transaction_amount") shouldBe 1
            exhausted.persistentAnnotationsList.filter { AnnotationType.Counter_803b in it.typeList }.all {
                it.detailInt(
                    "count",
                ) == 0
            } shouldBe
                true
        }

        test("bulk clearing opponent counters removes their energy without affecting the local player") {
            val board = startWithBoard { _, _, _ -> }
            board.snapshotDiff {
                board.ai.setCounters(CounterEnumType.ENERGY, 4, board.ai, true)
                board.human.setCounters(CounterEnumType.ENERGY, 2, board.human, true)
            }
            val clear = board.snapshotDiff { board.ai.clearCounters() }
            val removed = clear.annotationsList.single { AnnotationType.CounterRemoved in it.typeList }
            assertSoftly {
                removed.affectedIdsList shouldBe listOf(2)
                removed.detailInt("transaction_amount") shouldBe 4
                board.human.getCounters(CounterEnumType.ENERGY) shouldBe 2
            }
        }

        test("puzzle startup seeds energy and poison on their owning players") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:Player counters
                    Goal:Survive
                    Turns:3
                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humancounters=ENERGY=3,POISON=1
                    aicounters=ENERGY=2
                    humanhand=Forest
                    humanlibrary=Forest;Forest
                    ailibrary=Mountain;Mountain
                    """.trimIndent(),
                )
            val counters =
                board.bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations.activeAnnotations.values
                    .filter { AnnotationType.Counter_803b in it.typeList }
                    .associate { (it.affectedIdsList.single() to it.detailInt("counter_type")) to it.detailInt("count") }
            counters shouldBe
                mapOf((1 to CounterType.Energy.number) to 3, (1 to CounterType.Poison.number) to 1, (2 to CounterType.Energy.number) to 2)
        }
    })
