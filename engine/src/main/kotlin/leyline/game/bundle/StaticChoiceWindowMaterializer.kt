package leyline.game.bundle

import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.handoff.StaticChoiceWindowValue
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

/** Value-only GRE preparation for coordinator-owned static enum SelectN windows. */
internal class StaticChoiceWindowMaterializer {
    fun prepare(
        context: SettledPromptMaterializationContext,
        window: StaticChoiceWindowValue,
    ): SettledPromptMaterialization {
        val request = buildRequest(window, context)
        val predecessor = context.sequence.lastGameStateGsId()
        val state =
            context.gameState
                .toBuilder()
                .apply {
                    if (predecessor in 1 until context.gameStateId) prevGameStateId = predecessor
                }.setPendingMessageCount(1)
                .build()
        val messages =
            listOf(
                context.message(GREMessageType.GameStateMessage_695e) {
                    it.gameStateMessage = state
                },
                context.message(GREMessageType.SelectNreq) {
                    it.selectNReq = request
                    it.prompt =
                        Prompt
                            .newBuilder()
                            .setPromptId(outerPromptId(window.kind))
                            .addParameters(cardIdPromptParameter(request.sourceId))
                            .build()
                    it.allowCancel = AllowCancel.No_a526
                },
            )
        return context.prepared(messages, awaitedRequest = messages.last())
    }

    private fun buildRequest(
        window: StaticChoiceWindowValue,
        context: SettledPromptMaterializationContext,
    ): SelectNReq =
        SelectNReq
            .newBuilder()
            .setContext(SelectionContext.Resolution_a163)
            .setListType(
                if (isExplicitSubset(window.kind)) {
                    SelectionListType.StaticSubset
                } else {
                    SelectionListType.Static
                },
            ).setValidationType(SelectionValidationType.NonRepeatable)
            .setOptionContext(OptionContext.Resolution_a9d7)
            .setMinWeight(Int.MIN_VALUE)
            .setMaxWeight(Int.MAX_VALUE)
            .setMinSel(window.min)
            .setMaxSel(window.max)
            .setStaticList(staticList(window.kind))
            .setPrompt(Prompt.newBuilder())
            .apply {
                window.sourceForgeCardId?.let { sourceId = context.requiredInstanceId(it, "StaticChoice source") }
                if (isExplicitSubset(window.kind)) {
                    addAllIds(window.options.map { it.protocolValue })
                }
            }.build()

    /** Open-ended domains where `validTypes` is a narrowed subset, not the whole enum. */
    private fun isExplicitSubset(kind: StaticChoiceKind): Boolean =
        kind == StaticChoiceKind.Subtype || kind == StaticChoiceKind.Keyword || kind == StaticChoiceKind.CardType

    private fun staticList(kind: StaticChoiceKind): StaticList =
        when (kind) {
            StaticChoiceKind.Color -> StaticList.Colors
            StaticChoiceKind.Subtype -> StaticList.SubTypes
            StaticChoiceKind.Parity -> StaticList.Parities
            StaticChoiceKind.Keyword -> StaticList.Keywords
            StaticChoiceKind.CardType -> StaticList.CardTypes
        }

    private fun outerPromptId(kind: StaticChoiceKind): Int =
        when (kind) {
            StaticChoiceKind.Color -> PromptIds.CHOOSE_COLOR
            StaticChoiceKind.Subtype,
            StaticChoiceKind.Parity,
            StaticChoiceKind.Keyword,
            StaticChoiceKind.CardType,
            -> PromptIds.CHOOSE_TYPE
        }
}
