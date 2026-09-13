package leyline.game.bundle

import leyline.bridge.handoff.CardSelectKind
import leyline.bridge.handoff.CardSelectOriginZone
import leyline.bridge.handoff.CardSelectWindowValue
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
import wotc.mtgo.gre.external.messaging.Messages.Visibility

/** Value-only GRE preparation for coordinator-owned card-backed SelectN windows. */
internal class CardSelectWindowMaterializer {
    fun prepare(
        context: SettledPromptMaterializationContext,
        window: CardSelectWindowValue,
    ): SettledPromptMaterialization {
        val request = buildRequest(window, context)
        if (window.kind in privateCandidateKinds) requirePrivateCandidates(context, request.idsList)
        val envelope = envelope(window, request)
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
                    it.selectNReq = envelope.req
                    it.prompt = envelope.prompt
                    if (envelope.allowCancel != AllowCancel.None_a526) it.allowCancel = envelope.allowCancel
                },
            )
        return context.prepared(messages, awaitedRequest = messages.last())
    }

    private fun buildRequest(
        window: CardSelectWindowValue,
        context: SettledPromptMaterializationContext,
    ): SelectNReq {
        val discard = window.kind == CardSelectKind.Discard || window.kind == CardSelectKind.DiscardEffect
        val discardEffect = window.kind == CardSelectKind.DiscardEffect
        return SelectNReq
            .newBuilder()
            .setContext(if (discard) SelectionContext.Discard_a163 else SelectionContext.Resolution_a163)
            .setListType(if (discard) SelectionListType.Static else SelectionListType.Dynamic)
            .setValidationType(SelectionValidationType.NonRepeatable)
            .setOptionContext(if (discard && !discardEffect) OptionContext.Payment else OptionContext.Resolution_a9d7)
            .setMinWeight(Int.MIN_VALUE)
            .setMaxWeight(Int.MAX_VALUE)
            .setIdType(IdType.InstanceId_ab2c)
            .setMinSel(if (window.kind == CardSelectKind.Learn && window.candidates.isNotEmpty()) 1 else window.min)
            .setMaxSel(window.max)
            .addAllIds(window.candidates.map { context.requiredInstanceId(it.forgeCardId, "CardSelect card") })
            .apply {
                window.sourceForgeCardId?.let { sourceId = context.requiredInstanceId(it, "CardSelect card") }
                when (window.kind) {
                    CardSelectKind.LegendRule -> {
                        prompt = Prompt.getDefaultInstance()
                        sourceId = PromptIds.SELECT_N_LEGEND_RULE_SOURCE
                    }
                    CardSelectKind.LibraryPutback -> setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
                    CardSelectKind.ManifestDread -> {
                        addAllUnfilteredIds(idsList)
                        setSelectNInnerPrompt(PromptIds.MANIFEST_DREAD_INNER_PARAMETER)
                    }
                    CardSelectKind.Resolution,
                    CardSelectKind.ResolutionMapped,
                    -> {
                        addAllUnfilteredIds(idsList)
                        setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
                    }
                    CardSelectKind.Learn -> setSelectNInnerPrompt(PromptIds.SELECT_N_LEARN_INNER_PARAMETER)
                    CardSelectKind.Discard -> prompt = Prompt.newBuilder().setPromptId(discardPromptId(window)).build()
                    CardSelectKind.DiscardEffect -> prompt = Prompt.newBuilder().setPromptId(discardPromptId(window)).build()
                    CardSelectKind.Suspect -> setSelectNInnerPrompt(PromptIds.SELECT_N_INNER_PARAMETER)
                    CardSelectKind.SacrificeEffect,
                    CardSelectKind.MutateTopBottom,
                    -> prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build()
                }
            }.build()
    }

    private fun envelope(
        window: CardSelectWindowValue,
        request: SelectNReq,
    ): SelectNEnvelope =
        when (window.kind) {
            CardSelectKind.LegendRule -> SelectNEnvelope.legendRule(request)
            CardSelectKind.LibraryPutback -> SelectNEnvelope.libraryPutback(request)
            CardSelectKind.ManifestDread -> SelectNEnvelope.manifestDread(request)
            CardSelectKind.Resolution ->
                SelectNEnvelope.resolution(request, stockUp = window.min == 2 && window.max == 2)
            CardSelectKind.ResolutionMapped -> SelectNEnvelope.resolution(request)
            CardSelectKind.Learn ->
                SelectNEnvelope.learnLesson(
                    request,
                    if (window.candidates.any { it.originZone == CardSelectOriginZone.Hand }) {
                        PromptIds.LEARN_LESSON_OR_DISCARD
                    } else {
                        PromptIds.LEARN_LESSON_ONLY
                    },
                )
            CardSelectKind.Discard -> SelectNEnvelope.discard(request, discardPromptId(window), optional = window.min == 0)
            CardSelectKind.DiscardEffect -> SelectNEnvelope.discard(request, discardPromptId(window), optional = window.min == 0)
            CardSelectKind.SacrificeEffect,
            -> SelectNEnvelope.default(request)
            CardSelectKind.Suspect -> SelectNEnvelope.suspectChoice(request)
            CardSelectKind.MutateTopBottom -> SelectNEnvelope.mutateTopBottom(request)
        }

    private fun requirePrivateCandidates(
        context: SettledPromptMaterializationContext,
        candidateIds: List<Int>,
    ) {
        val objectsById = context.gameState.gameObjectsList.associateBy { it.instanceId }
        candidateIds.forEach { candidateId ->
            val candidate = checkNotNull(objectsById[candidateId]) { "Private CardSelect candidate $candidateId was not projected" }
            check(candidate.visibility == Visibility.Private && candidate.viewersList == listOf(context.seatId)) {
                "Private CardSelect candidate $candidateId must be private to its chooser"
            }
        }
    }

    private companion object {
        fun discardPromptId(window: CardSelectWindowValue): Int =
            when {
                window.min == 0 && window.max == 2 -> PromptIds.DISCARD_UP_TO_TWO
                window.min == 0 && window.max == 3 -> PromptIds.DISCARD_UP_TO_THREE
                window.min == 0 && window.max == 1 -> PromptIds.DISCARD_OPTIONAL
                window.min == 1 && window.max == 1 -> PromptIds.DISCARD_COST
                window.min == 2 && window.max == 2 -> PromptIds.DISCARD_TWO
                window.min == 3 && window.max == 3 -> PromptIds.DISCARD_THREE
                else -> PromptIds.SELECT_N
            }

        val privateCandidateKinds = setOf(CardSelectKind.ManifestDread, CardSelectKind.Resolution, CardSelectKind.Learn)
    }
}
