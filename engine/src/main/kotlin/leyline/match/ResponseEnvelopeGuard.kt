package leyline.match

import leyline.game.bundle.LogicalSequenceState
import leyline.game.state.ResponseAcceptanceTracker
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.FailureReason

/** Client messages whose `respId` identifies the prompt they answer. */
internal val CORRELATED_CLIENT_MESSAGE_TYPES: Set<ClientMessageType> =
    setOf(
        ClientMessageType.PerformActionResp_097b,
        ClientMessageType.DeclareAttackersResp_097b,
        ClientMessageType.SubmitAttackersReq,
        ClientMessageType.DeclareBlockersResp_097b,
        ClientMessageType.SubmitBlockersReq,
        ClientMessageType.SelectTargetsResp_097b,
        ClientMessageType.SubmitTargetsReq,
        ClientMessageType.EffectCostResp_097b,
        ClientMessageType.PerformAutoTapActionsResp_097b,
        ClientMessageType.CastingTimeOptionsResp_097b,
        ClientMessageType.GroupResp_097b,
        ClientMessageType.AssignDamageResp_097b,
        ClientMessageType.OptionalActionResp,
        ClientMessageType.NumericInputResp_097b,
        ClientMessageType.MulliganResp_097b,
        ClientMessageType.ChooseStartingPlayerResp_097b,
    )

/** Rejects a prompt response that does not identify the most recent prompt. */
internal object ResponseEnvelopeGuard {
    private val log = LoggerFactory.getLogger(ResponseEnvelopeGuard::class.java)

    fun mismatchReason(
        message: ClientToGREMessage,
        sequence: LogicalSequenceState,
        responses: ResponseAcceptanceTracker,
    ): FailureReason? {
        if (message.type !in CORRELATED_CLIENT_MESSAGE_TYPES) return null
        val expectedRespId = sequence.lastPromptMsgId
        if (expectedRespId != 0 && message.respId == expectedRespId) {
            responses.markResponseAccepted(message.respId)
            return null
        }

        log.debug(
            "{} respId={} expected={}",
            message.type,
            message.respId,
            expectedRespId,
        )
        return FailureReason.ReqRespMismatch
    }
}
