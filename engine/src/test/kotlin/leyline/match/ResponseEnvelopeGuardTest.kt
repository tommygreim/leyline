package leyline.match

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.bundle.LogicalSequenceState
import leyline.game.state.ResponseAcceptanceTracker
import wotc.mtgo.gre.external.messaging.Messages.*

class ResponseEnvelopeGuardTest :
    FunSpec({
        tags(UnitTag)

        test("accepts a response correlated to the latest prompt") {
            val sequence = LogicalSequenceState(lastPromptMsgId = 17)
            val responses = ResponseAcceptanceTracker()

            ResponseEnvelopeGuard.mismatchReason(response(respId = 17), sequence, responses) shouldBe null
            responses.responsesAccepted() shouldBe 1
        }

        test("classifies a response correlated to another prompt without advancing response state") {
            val sequence = LogicalSequenceState(currentMsgId = 20, lastPromptMsgId = 17)
            val responses = ResponseAcceptanceTracker()

            ResponseEnvelopeGuard.mismatchReason(response(respId = 16), sequence, responses) shouldBe FailureReason.ReqRespMismatch

            responses.responsesAccepted() shouldBe 0
        }

        test("rejects a response when no prompt has been emitted") {
            ResponseEnvelopeGuard.mismatchReason(response(respId = 0), LogicalSequenceState(), ResponseAcceptanceTracker()) shouldBe
                FailureReason.ReqRespMismatch
        }

        test("does not correlate control messages") {
            val sequence = LogicalSequenceState(lastPromptMsgId = 17)
            val responses = ResponseAcceptanceTracker()
            val control =
                ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.CancelActionReq_097b)
                    .build()

            ResponseEnvelopeGuard.mismatchReason(control, sequence, responses) shouldBe null
            responses.responsesAccepted() shouldBe 0
        }

        test("correlates numeric input responses") {
            val sequence = LogicalSequenceState(lastPromptMsgId = 17)
            ResponseEnvelopeGuard.mismatchReason(
                response(respId = 16, type = ClientMessageType.NumericInputResp_097b),
                sequence,
                ResponseAcceptanceTracker(),
            ) shouldBe FailureReason.ReqRespMismatch
        }

        test("correlates Auto Pay to its payment prompt") {
            val sequence = LogicalSequenceState(lastPromptMsgId = 17)
            val responses = ResponseAcceptanceTracker()
            assertSoftly {
                ResponseEnvelopeGuard.mismatchReason(
                    response(respId = 16, type = ClientMessageType.PerformAutoTapActionsResp_097b),
                    sequence,
                    responses,
                ) shouldBe FailureReason.ReqRespMismatch
                responses.responsesAccepted() shouldBe 0
                ResponseEnvelopeGuard.mismatchReason(
                    response(respId = 17, type = ClientMessageType.PerformAutoTapActionsResp_097b),
                    sequence,
                    responses,
                ) shouldBe null
                responses.responsesAccepted() shouldBe 1
            }
        }

        test("correlates London tuck group responses") {
            val sequence = LogicalSequenceState(lastPromptMsgId = 17)
            val responses = ResponseAcceptanceTracker()

            ResponseEnvelopeGuard.mismatchReason(
                response(respId = 16, type = ClientMessageType.GroupResp_097b),
                sequence,
                responses,
            ) shouldBe FailureReason.ReqRespMismatch
            responses.responsesAccepted() shouldBe 0
        }

        test("leaves settled responses to the settled prompt owner") {
            val sequence = LogicalSequenceState(lastPromptMsgId = 17)
            val responses = ResponseAcceptanceTracker()
            val settled = response(respId = 16, type = ClientMessageType.SelectNresp)

            ResponseEnvelopeGuard.mismatchReason(settled, sequence, responses) shouldBe null
            responses.responsesAccepted() shouldBe 0
        }
    })

private fun response(
    respId: Int,
    type: ClientMessageType = ClientMessageType.PerformActionResp_097b,
): ClientToGREMessage =
    ClientToGREMessage
        .newBuilder()
        .setType(type)
        .setSystemSeatId(1)
        .setRespId(respId)
        .build()
