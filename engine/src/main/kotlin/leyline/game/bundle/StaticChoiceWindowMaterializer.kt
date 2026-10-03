package leyline.game.bundle

import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.handoff.StaticChoiceWindowValue
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.PromptParameter
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
                            .setPromptId(window.protocolPromptId ?: outerPromptId(window.kind))
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
            // The client copies every listed id into UnfilteredIds, and a Resolution-context
            // CardTypes subset then opens its "choice with context" browser, which shows the
            // opponent's hand (built for look-then-choose effects). A card-type choice made
            // as a permanent enters (Cloud Key) is a replacement, which gets plain buttons.
            .setContext(
                if (window.kind ==
                    StaticChoiceKind.CardType
                ) {
                    SelectionContext.Replacement_a163
                } else {
                    SelectionContext.Resolution_a163
                },
            ).setListType(
                if (window.kind == StaticChoiceKind.Binary ||
                    window.kind == StaticChoiceKind.Dungeon ||
                    window.kind == StaticChoiceKind.DungeonRoom
                ) {
                    // Dungeon workflows consume explicit GRP ids, not a subset of a
                    // static enum. StaticSubset is intercepted by Arena's generic
                    // choice workflow before its dungeon/room predicates are checked.
                    SelectionListType.Dynamic
                } else if (isExplicitSubset(window.kind)) {
                    SelectionListType.StaticSubset
                } else {
                    SelectionListType.Static
                },
            ).setValidationType(SelectionValidationType.NonRepeatable)
            .setOptionContext(OptionContext.Resolution_a9d7)
            .apply {
                when (window.kind) {
                    StaticChoiceKind.Binary -> setIdType(IdType.PromptParameterIndex)
                    StaticChoiceKind.Dungeon -> setIdType(IdType.CardGrpId)
                    StaticChoiceKind.DungeonRoom -> setIdType(IdType.AbilityGrpId)
                    else -> Unit
                }
            }.setMinWeight(Int.MIN_VALUE)
            .setMaxWeight(Int.MAX_VALUE)
            .setMinSel(window.min)
            .setMaxSel(window.max)
            .apply { staticList(window.kind)?.let(::setStaticList) }
            .setPrompt(
                Prompt.newBuilder().apply {
                    if (window.kind == StaticChoiceKind.Binary) {
                        window.options.forEach { option ->
                            addParameters(
                                PromptParameter
                                    .newBuilder()
                                    .apply {
                                        option.promptParameterId?.let {
                                            setType(wotc.mtgo.gre.external.messaging.Messages.ParameterType.PromptId)
                                            setPromptId(it)
                                        } ?: run {
                                            setType(wotc.mtgo.gre.external.messaging.Messages.ParameterType.NonLocalizedString)
                                            setStringValue(option.label)
                                        }
                                    }.build(),
                            )
                        }
                    }
                },
            ).apply {
                window.sourceForgeCardId?.let { sourceId = context.requiredInstanceId(it, "StaticChoice source") }
                if (listType == SelectionListType.Dynamic || listType == SelectionListType.StaticSubset) {
                    addAllIds(window.options.map { it.protocolValue })
                }
            }.build()

    /** Open-ended domains where `validTypes` is a narrowed subset, not the whole enum. */
    private fun isExplicitSubset(kind: StaticChoiceKind): Boolean =
        kind == StaticChoiceKind.CardColor ||
            kind == StaticChoiceKind.Subtype ||
            kind == StaticChoiceKind.Keyword ||
            kind == StaticChoiceKind.CardType ||
            kind == StaticChoiceKind.CardName ||
            kind == StaticChoiceKind.CounterType

    private fun staticList(kind: StaticChoiceKind): StaticList? =
        when (kind) {
            StaticChoiceKind.Color -> StaticList.Colors
            StaticChoiceKind.ManaColor -> StaticList.ManaColors
            StaticChoiceKind.BasicLandType -> StaticList.BasicLandTypes
            StaticChoiceKind.CardColor -> StaticList.CardColors
            StaticChoiceKind.Subtype -> StaticList.SubTypes
            StaticChoiceKind.CounterType -> StaticList.CounterTypes
            StaticChoiceKind.Parity -> StaticList.Parities
            StaticChoiceKind.Binary -> null
            StaticChoiceKind.Keyword -> StaticList.Keywords
            StaticChoiceKind.CardType -> StaticList.CardTypes
            StaticChoiceKind.CardName -> StaticList.CardNames
            StaticChoiceKind.Dungeon -> StaticList.None_a56d
            StaticChoiceKind.DungeonRoom -> StaticList.None_a56d
        }

    private fun outerPromptId(kind: StaticChoiceKind): Int =
        when (kind) {
            StaticChoiceKind.Color -> PromptIds.CHOOSE_COLOR
            StaticChoiceKind.ManaColor -> PromptIds.CHOOSE_COLOR
            StaticChoiceKind.BasicLandType -> PromptIds.CHOOSE_COLOR
            StaticChoiceKind.CardColor -> PromptIds.CHOOSE_COLOR
            StaticChoiceKind.Subtype,
            StaticChoiceKind.Parity,
            StaticChoiceKind.Binary,
            StaticChoiceKind.Keyword,
            StaticChoiceKind.CardType,
            StaticChoiceKind.CardName,
            StaticChoiceKind.CounterType,
            -> PromptIds.CHOOSE_TYPE
            StaticChoiceKind.Dungeon -> PromptIds.SELECT_N
            StaticChoiceKind.DungeonRoom -> PromptIds.SELECT_N
        }
}
