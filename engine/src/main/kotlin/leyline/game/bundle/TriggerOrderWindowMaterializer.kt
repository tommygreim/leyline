package leyline.game.bundle

import leyline.bridge.handoff.TriggerOrderWindowValue
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType

/**
 * Value-only GRE preparation for one simultaneous-trigger ordering window.
 *
 * The client's `TriggeredAbilityWorkflow` lays [SelectNReq.getIdsList] out left to right under
 * the labels "Last" (left) and "First" (right), and answers with the ids in the arrangement
 * the player left behind. So the wire order is the reverse of [TriggerOrderWindowValue.options],
 * whose first entry resolves first.
 */
internal class TriggerOrderWindowMaterializer {
    fun prepare(
        context: SettledPromptMaterializationContext,
        window: TriggerOrderWindowValue,
    ): SettledPromptMaterialization {
        val ids =
            window.options.reversed().map {
                context.requiredInstanceId(FrameIdResolver.triggerStackAbilityForgeId(it.forgeAbilityId), "Trigger order ability")
            }
        val request =
            SelectNReq
                .newBuilder()
                .setContext(SelectionContext.TriggeredAbility_c799)
                .setOptionContext(OptionContext.Stacking)
                .setListType(SelectionListType.Dynamic)
                .setIdType(IdType.InstanceId_ab2c)
                .setValidationType(SelectionValidationType.NonRepeatable)
                .setMinWeight(Int.MIN_VALUE)
                .setMaxWeight(Int.MAX_VALUE)
                .setMinSel(ids.size)
                .setMaxSel(ids.size)
                .addAllIds(ids)
                .build()
        val state =
            context.gameState
                .toBuilder()
                .setPendingMessageCount(1)
                .build()
        val messages =
            listOf(
                context.message(GREMessageType.GameStateMessage_695e) {
                    it.gameStateMessage = state
                },
                context.message(GREMessageType.SelectNreq) {
                    it.selectNReq = request
                    it.prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build()
                    it.allowCancel = AllowCancel.No_a526
                },
            )
        return context.prepared(messages, awaitedRequest = messages.last())
    }
}
