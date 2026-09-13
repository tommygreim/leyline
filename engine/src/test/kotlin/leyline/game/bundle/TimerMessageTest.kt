package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.InMemoryCardRepository
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.TimerType

class TimerMessageTest :
    FunSpec({

        tags(UnitTag)

        fun bb() = BundleBuilder(GameBridge(cardRepository = InMemoryCardRepository()), "test-match", 1)

        // LowTimeWarning.SHOW_TIMER_THRESHOLD in the installed client.
        val ropeShowThresholdSec = 30

        test("timerStart builds TimerStateMessage with Decision timer running") {
            val counter = LogicalSequencePlanner()
            val result = bb().timerStart(counter = counter, durationSec = 30)

            result.messages.size shouldBe 1
            val msg = result.messages[0]
            assertSoftly {
                msg.type shouldBe GREMessageType.TimerStateMessage_695e
                msg.timerStateMessage.seatId shouldBe 1
                msg.timerStateMessage.timersCount shouldBe 1
            }

            val timer = msg.timerStateMessage.timersList[0]
            assertSoftly {
                timer.type shouldBe TimerType.Decision
                timer.durationSec shouldBe 30
                timer.running shouldBe true
                timer.elapsedSec shouldBe 0
            }
        }

        test("the default decision timer leaves quiet thinking time before the rope") {
            val timer =
                bb()
                    .timerStart(counter = LogicalSequencePlanner())
                    .messages[0]
                    .timerStateMessage.timersList[0]

            // The client reveals the rope at LowTimeWarning.SHOW_TIMER_THRESHOLD seconds
            // remaining, so the duration has to clear that threshold to buy any grace.
            assertSoftly {
                timer.durationSec shouldBe BundleBuilder.DECISION_TIMER_SEC
                timer.durationSec - ropeShowThresholdSec shouldBe 30
                timer.elapsedSec shouldBe 0
            }
        }

        test("timerStop builds TimerStateMessage with running=false") {
            val counter = LogicalSequencePlanner()
            val result = bb().timerStop(counter = counter)

            result.messages.size shouldBe 1
            val msg = result.messages[0]
            msg.type shouldBe GREMessageType.TimerStateMessage_695e

            val timer = msg.timerStateMessage.timersList[0]
            timer.type shouldBe TimerType.Decision
            timer.running shouldBe false
        }

        test("timerStart uses counter for msgId") {
            val counter = LogicalSequencePlanner()
            val startMsgId = counter.currentMsgId()

            bb().timerStart(counter = counter)

            // Counter should have advanced by 1 msgId
            counter.currentMsgId() shouldBe startMsgId + 1
        }
    })
