package leyline.bridge.coord

import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedStaticChoiceInteraction
import leyline.bridge.handoff.StaticChoiceInteractionRuntime
import leyline.bridge.handoff.StaticChoiceInteractionTimeoutException
import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.handoff.StaticChoiceWindowValue
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import java.util.concurrent.CompletableFuture

/** Exact static enum SelectN lifecycle beneath [MatchCutCoordinator]. */
internal class MatchStaticChoiceInteractionRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : StaticChoiceInteractionRuntime {
    private data class Window(
        val published: PublishedStaticChoiceInteraction,
        val value: StaticChoiceWindowValue,
        override val cut: PendingPromptCut<StaticChoiceWindowValue>,
        val optionByValue: Map<Int, Int>,
        override val future: CompletableFuture<List<Int>> = CompletableFuture(),
    ) : SettledPromptOwner.Window<List<Int>> {
        override val interactionId: String get() = published.interactionId
    }

    private val slot =
        settled.mount<Window, List<Int>>(
            PromptTerminalPriority.StaticChoice,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
            owns = { _, message -> message.type == ClientMessageType.SelectNresp },
            admitLocked = ::admitLocked,
        )

    internal var beforeResponseComplete: (() -> Unit)? = null

    override fun awaitSelection(
        request: PromptRequest,
        timeoutMs: Long?,
    ): List<Int> {
        val initial =
            try {
                StaticChoiceWindowCapture.initial(request)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    fun current(): PublishedStaticChoiceInteraction? = slot.current()?.published

    private fun admitLocked(
        pending: Window,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<List<Int>>? {
        val selectedValues = message.selectNResp.idsList
        if (selectedValues.size !in pending.value.min..pending.value.max) return null
        if (selectedValues.size != selectedValues.distinct().size) return null
        val options = selectedValues.map { pending.optionByValue[it] ?: return null }
        return SettledPromptOwner.SlotAdmission(
            options,
            beforeComplete = {
                recordChoiceResults(pending, selectedValues)
                beforeResponseComplete?.invoke()
            },
        )
    }

    private fun publish(initial: StaticChoiceWindowValue): Window =
        slot.publish(
            duplicateMessage = "A StaticChoice interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial)
                val preparedViewers =
                    try {
                        feed.builder.prepareStaticChoiceWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            planner,
                            initial,
                            owner.viewerRoutes(runtimeSeat),
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val prepared = preparedViewers.player
                val published =
                    PublishedStaticChoiceInteraction(
                        interactionId,
                        checkNotNull(prepared.bundle.actionGameStateId),
                        initial.kind,
                    )
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        initial,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                val optionByValue = initial.options.associate { it.protocolValue to it.originalOptionIndex }
                val created = Window(published, initial, exact, optionByValue)
                SettledPromptOwner.Publication(
                    created,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                    preparedViewers.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                    prepared.correlation,
                )
            },
        )

    private fun recordChoiceResults(
        pending: Window,
        selectedValues: List<Int>,
    ) {
        val source = pending.value.sourceForgeCardId ?: return
        selectedValues.forEach { value ->
            owner.bridge
                .seat(runtimeSeat)
                .prompt.journal
                .record(
                    PromptSideEffect.ChoiceResult(
                        sourceForgeCardId = source,
                        chooserSeatId = runtimeSeat,
                        choiceValue = value,
                        choiceDomain = pending.value.kind.choiceDomain(),
                        sentiment = 2,
                    ),
                )
        }
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): List<Int> = slot.await(pending, timeoutMs, ::StaticChoiceInteractionTimeoutException)

    private fun StaticChoiceKind.choiceDomain(): Int =
        when (this) {
            StaticChoiceKind.Color -> StaticList.Colors.number
            StaticChoiceKind.CardColor -> StaticList.CardColors.number
            StaticChoiceKind.Subtype -> StaticList.SubTypes.number
            StaticChoiceKind.Parity -> StaticList.Parities.number
            StaticChoiceKind.Keyword -> StaticList.Keywords.number
            StaticChoiceKind.CardType -> StaticList.CardTypes.number
        }
}
