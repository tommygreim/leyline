package leyline.game.bundle

import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

data class SelectNEnvelope(
    val req: SelectNReq,
    val prompt: Prompt,
    val allowCancel: AllowCancel = AllowCancel.None_a526,
    val gameStateAugmentation: GameStateAugmentation = GameStateAugmentation.None,
) {
    sealed interface GameStateAugmentation {
        data object None : GameStateAugmentation

        data object LookAndPick : GameStateAugmentation
    }

    companion object {
        fun default(req: SelectNReq): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build(),
            )

        fun discard(
            req: SelectNReq,
            promptId: Int,
            optional: Boolean,
        ): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = Prompt.newBuilder().setPromptId(promptId).build(),
                allowCancel = if (optional) AllowCancel.Continue else AllowCancel.No_a526,
            )

        fun legendRule(req: SelectNReq): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt =
                    Prompt
                        .newBuilder()
                        .setPromptId(PromptIds.SELECT_N_LEGEND_RULE)
                        .addParameters(cardIdPromptParameter())
                        .build(),
                allowCancel = AllowCancel.No_a526,
            )

        fun resolution(
            req: SelectNReq,
            stockUp: Boolean = false,
        ): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = if (stockUp) stockUpPrompt(req) else Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build(),
                allowCancel = AllowCancel.No_a526,
                gameStateAugmentation = GameStateAugmentation.LookAndPick,
            )

        fun manifestDread(req: SelectNReq): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = promptWithSourceAndCount(PromptIds.MANIFEST_DREAD, req),
                allowCancel = AllowCancel.No_a526,
            )

        fun libraryPutback(req: SelectNReq): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = promptWithSourceAndCount(PromptIds.SELECT_N_LIBRARY_PUTBACK, req),
                allowCancel = AllowCancel.No_a526,
            )

        fun suspectChoice(req: SelectNReq): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = promptWithSourceAndCount(PromptIds.SUSPECT_ONE_OF_THOSE_CREATURES, req),
                allowCancel = AllowCancel.Continue,
            )

        fun mutateTopBottom(req: SelectNReq): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_N).build(),
                allowCancel = AllowCancel.No_a526,
            )

        fun learnLesson(
            req: SelectNReq,
            promptId: Int,
        ): SelectNEnvelope =
            SelectNEnvelope(
                req = req,
                prompt = promptWithSourceAndCount(promptId, req),
                allowCancel = AllowCancel.Continue,
            )

        private fun stockUpPrompt(req: SelectNReq): Prompt = promptWithSourceAndCount(PromptIds.SELECT_N_STOCK_UP, req)

        private fun promptWithSourceAndCount(
            promptId: Int,
            req: SelectNReq,
        ): Prompt =
            Prompt
                .newBuilder()
                .setPromptId(promptId)
                .addParameters(cardIdPromptParameter(req.sourceId))
                .addParameters(cardIdPromptParameter(req.maxSel))
                .build()
    }
}
