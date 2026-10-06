package leyline.game.bundle

import leyline.bridge.handoff.RevealChoiceWindowValue
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

/** Value-only GRE preparation for one reveal-backed SelectN window. */
internal class RevealChoiceWindowMaterializer {
    fun prepare(
        context: SettledPromptMaterializationContext,
        window: RevealChoiceWindowValue,
    ): SettledPromptMaterialization {
        val request = buildRequest(window, context)
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
                    it.prompt = Prompt.newBuilder().setPromptId(window.promptId ?: PromptIds.SELECT_N).build()
                    it.allowCancel = AllowCancel.No_a526
                },
            )
        return context.prepared(messages, awaitedRequest = messages.last())
    }

    private fun buildRequest(
        window: RevealChoiceWindowValue,
        context: SettledPromptMaterializationContext,
    ): SelectNReq =
        SelectNReq
            .newBuilder()
            .setContext(SelectionContext.Resolution_a163)
            .setListType(SelectionListType.Dynamic)
            .setValidationType(SelectionValidationType.NonRepeatable)
            .setOptionContext(OptionContext.Resolution_a9d7)
            .setMinWeight(Int.MIN_VALUE)
            .setMaxWeight(Int.MAX_VALUE)
            .setIdType(IdType.InstanceId_ab2c)
            .addAllIds(window.candidates.map { context.requiredInstanceId(it.forgeCardId, "RevealChoice card") })
            .addAllUnfilteredIds(window.fullRevealCardIds.map { context.requiredInstanceId(it, "RevealChoice card") })
            .setPrompt(Prompt.newBuilder().setPromptId(window.promptId ?: PromptIds.SELECT_N))
            .apply {
                if (window.candidates.isNotEmpty()) {
                    minSel = window.min
                    maxSel = window.max
                }
                window.sourceForgeCardId?.let { sourceId = context.requiredInstanceId(it, "RevealChoice card") }
            }.build()
}
