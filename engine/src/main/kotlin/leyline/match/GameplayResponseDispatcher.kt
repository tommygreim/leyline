package leyline.match

import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage

/** Dispatch one post-handshake gameplay response to its session handler. */
@Suppress("CyclomaticComplexMethod", "ElseCaseInsteadOfExhaustiveWhen")
internal fun dispatchGameplayResponse(
    receiver: ActionReceiver,
    message: ClientToGREMessage,
): Boolean {
    val handler = gameplayResponseHandler(message.type) ?: return false
    handler(receiver, message)
    return true
}

internal fun isGameplayResponse(type: ClientMessageType): Boolean = gameplayResponseHandler(type) != null

private typealias GameplayResponseHandler = (ActionReceiver, ClientToGREMessage) -> Unit

@Suppress("ElseCaseInsteadOfExhaustiveWhen")
private fun gameplayResponseHandler(type: ClientMessageType): GameplayResponseHandler? =
    when (type) {
        ClientMessageType.PerformActionResp_097b -> ActionReceiver::onPerformAction
        ClientMessageType.DeclareAttackersResp_097b,
        ClientMessageType.SubmitAttackersReq,
        -> ActionReceiver::onDeclareAttackers
        ClientMessageType.DeclareBlockersResp_097b,
        ClientMessageType.SubmitBlockersReq,
        -> ActionReceiver::onDeclareBlockers
        ClientMessageType.SelectTargetsResp_097b -> ActionReceiver::onSelectTargets
        ClientMessageType.SubmitTargetsReq -> ActionReceiver::onSubmitTargets
        ClientMessageType.EffectCostResp_097b -> ActionReceiver::onEffectCost
        ClientMessageType.CancelActionReq_097b -> ActionReceiver::onCancelAction
        ClientMessageType.UndoReq -> ActionReceiver::onUndo
        ClientMessageType.CastingTimeOptionsResp_097b -> ActionReceiver::onCastingTimeOptions
        ClientMessageType.AssignDamageResp_097b -> ActionReceiver::onAssignDamage
        ClientMessageType.OptionalActionResp -> ActionReceiver::onOptionalActionResp
        ClientMessageType.NumericInputResp_097b -> ActionReceiver::onNumericInputResp
        else -> null
    }
