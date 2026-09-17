package leyline.game.bundle

import forge.game.phase.PhaseType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.SeatId
import leyline.game.bundle.GsmFrame
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.PhaseSnapshot
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.Phase
import wotc.mtgo.gre.external.messaging.Messages.Step

class GsmFrameTest :
    FunSpec({

        tags(UnitTag)

        test("turnInfo builds correct proto from frame fields") {
            val frame =
                GsmFrame(
                    activeSeat = 1,
                    prioritySeat = 2,
                    turnNumber = 3,
                    phase = Phase.Main1_a549,
                    step = Step.None_a2cb,
                )

            val ti = frame.turnInfo()
            assertSoftly {
                ti.activePlayer shouldBe 1
                ti.priorityPlayer shouldBe 2
                ti.decisionPlayer shouldBe 2
                ti.turnNumber shouldBe 3
                ti.phase shouldBe Phase.Main1_a549
                ti.step shouldBe Step.None_a2cb
            }
        }

        test("turnInfo carries the next phase, which drives the client's priority button") {
            // Arena sends Main1 -> Combat/BeginCombat; the client's asset lookup turns
            // that pair into the two-line "Next" / "To Combat" label. Without these two
            // fields it reads Phase.None and falls back to a bare "Pass"/"My Turn".
            val frame =
                GsmFrame.from(
                    GsmSnapshot.forTest(
                        phase =
                            PhaseSnapshot(
                                turn = 3,
                                activePlayer = SeatId(1),
                                priorityPlayer = SeatId(1),
                                phase = PhaseType.MAIN1,
                                nextPhase = PhaseType.COMBAT_BEGIN,
                            ),
                    ),
                )

            val ti = frame.turnInfo()
            assertSoftly {
                ti.phase shouldBe Phase.Main1_a549
                ti.step shouldBe Step.None_a2cb
                ti.nextPhase shouldBe Phase.Combat_a549
                ti.nextStep shouldBe Step.BeginCombat_a2cb
            }
        }

        test("phaseAnnotation produces PhaseOrStepModified with supplied ID") {
            val frame =
                GsmFrame(
                    activeSeat = 2,
                    prioritySeat = 1,
                    turnNumber = 1,
                    phase = Phase.Combat_a549,
                    step = Step.DeclareAttack_a2cb,
                )

            var nextId = 100
            val ann = frame.phaseAnnotation { nextId++ }

            assertSoftly {
                ann.id shouldBe 100
                ann.affectedIdsList shouldBe listOf(2)
                ann.typeList.any { it == AnnotationType.PhaseOrStepModified } shouldBe true
            }
        }
    })
