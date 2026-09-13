package leyline.bridge.coord

import forge.game.replacement.ReplacementEffect
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedReplacementInteraction
import leyline.bridge.handoff.ReplacementInteractionResult
import leyline.bridge.handoff.ReplacementInteractionRuntime
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import java.util.concurrent.CompletableFuture
import wotc.mtgo.gre.external.messaging.Messages.ReplacementEffect as ReplacementEffectRow

/** Exact competing-replacement lifecycle beneath [MatchCutCoordinator]. */
internal class MatchReplacementInteractionRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : ReplacementInteractionRuntime {
    private data class Window(
        val published: PublishedReplacementInteraction,
        val value: leyline.bridge.handoff.ReplacementWindowValue,
        override val cut: PendingPromptCut<leyline.bridge.handoff.ReplacementWindowValue>,
        val handlesByOption: Map<Int, ReplacementEffect>,
        val publishedRows: List<ReplacementEffectRow>,
        override val future: CompletableFuture<ReplacementInteractionResult> = CompletableFuture(),
    ) : SettledPromptOwner.Window<ReplacementInteractionResult> {
        override val interactionId: String get() = published.interactionId
    }

    private val capture = ReplacementWindowCapture(owner)
    private val slot =
        settled.mount<Window, ReplacementInteractionResult>(
            PromptTerminalPriority.Replacement,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
            owns = { _, message -> message.type == ClientMessageType.SelectReplacementResp_097b },
            admitLocked = ::admitLocked,
        )

    override fun awaitReplacement(
        request: PromptRequest,
        possibleReplacers: List<ReplacementEffect>,
        timeoutMs: Long?,
    ): ReplacementInteractionResult? {
        val initial = capture.initial(request, possibleReplacers) ?: return null
        return await(publish(initial), timeoutMs)
    }

    internal fun current(): PublishedReplacementInteraction? = slot.current()?.published

    @Suppress("ReturnCount")
    private fun admitLocked(
        pending: Window,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<ReplacementInteractionResult>? {
        if (!message.hasSelectReplacementResp()) return null
        if (!message.selectReplacementResp.hasReplacement()) return null
        val echoed = message.selectReplacementResp.replacement
        val optionIndex =
            pending.publishedRows.indexOfFirst { row ->
                row.toByteArray().contentEquals(echoed.toByteArray())
            }
        if (optionIndex < 0) return null
        val option = pending.value.options.getOrNull(optionIndex) ?: return null
        val handle = pending.handlesByOption[option.originalOptionIndex] ?: return null
        return SettledPromptOwner.SlotAdmission(
            ReplacementInteractionResult(option.originalOptionIndex, handle),
        )
    }

    private fun publish(initial: ReplacementWindowCapture.Initial): Window =
        slot.publish(
            duplicateMessage = "A replacement interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val preparedViewers =
                    try {
                        feed.builder.prepareReplacementWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            planner,
                            initial.value,
                            owner.viewerRoutes(runtimeSeat),
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val prepared = preparedViewers.player
                val published =
                    PublishedReplacementInteraction(interactionId, checkNotNull(prepared.bundle.actionGameStateId))
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        initial.value,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                val request =
                    prepared.bundle.messages
                        .single { it.hasSelectReplacementReq() }
                        .selectReplacementReq
                val created =
                    Window(
                        published,
                        initial.value,
                        exact,
                        initial.handlesByOption,
                        request.replacementsList,
                    )
                SettledPromptOwner.Publication(
                    created,
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                    preparedViewers.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                    prepared.correlation,
                )
            },
        )

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): ReplacementInteractionResult =
        slot.await(
            pending = pending,
            timeoutMs = timeoutMs,
            timeoutException = { error("Replacement timeout should complete with a default") },
            beforeTimeoutCompleteLocked = {
                val option = pending.value.options[pending.value.defaultOptionIndex]
                val handle = pending.handlesByOption.getValue(option.originalOptionIndex)
                check(slot.completeLocked(pending, ReplacementInteractionResult(option.originalOptionIndex, handle, timedOut = true)))
            },
        )
}
