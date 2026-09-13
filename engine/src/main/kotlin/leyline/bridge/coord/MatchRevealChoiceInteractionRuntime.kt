package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.PromptJournal
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedRevealChoiceInteraction
import leyline.bridge.handoff.RevealChoiceInteractionResult
import leyline.bridge.handoff.RevealChoiceInteractionRuntime
import leyline.bridge.handoff.RevealChoiceWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import java.util.concurrent.CompletableFuture

/** Exact reveal-backed SelectN lifecycle beneath [MatchCutCoordinator]. */
internal class MatchRevealChoiceInteractionRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : RevealChoiceInteractionRuntime {
    private data class Window(
        val published: PublishedRevealChoiceInteraction,
        val value: RevealChoiceWindowValue,
        val revealEntry: PromptJournal.RevealEntry,
        override val cut: PendingPromptCut<RevealChoiceWindowValue>,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<RevealChoiceInteractionResult> = CompletableFuture(),
    ) : SettledPromptOwner.Window<RevealChoiceInteractionResult> {
        override val interactionId: String get() = published.interactionId
    }

    private val slot =
        settled.mount<Window, RevealChoiceInteractionResult>(
            PromptTerminalPriority.RevealChoice,
            publicationFailure = { cause, failed ->
                clearReveal(failed.revealEntry, failed.value.journalSeatId)
                owner.failPrompt(cause, failed.cut)
            },
            owns = { _, message -> message.type == ClientMessageType.SelectNresp },
            admitLocked = ::admitLocked,
            onTerminateLocked = { pending, _ ->
                pending?.let { clearReveal(it.revealEntry, it.value.journalSeatId) }
            },
            onResetLocked = { pending ->
                pending?.let { clearReveal(it.revealEntry, it.value.journalSeatId) }
            },
        )

    override fun awaitSelection(
        request: PromptRequest,
        candidateHandles: List<Card>,
        revealEntry: PromptJournal.RevealEntry,
        recordExiledUnderSource: Boolean,
        timeoutMs: Long?,
    ): RevealChoiceInteractionResult {
        val initial =
            try {
                RevealChoiceWindowCapture.initial(
                    request,
                    candidateHandles,
                    revealEntry,
                    runtimeSeat,
                    recordExiledUnderSource,
                )
            } catch (ex: Exception) {
                clearReveal(revealEntry, runtimeSeat)
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    fun current(): PublishedRevealChoiceInteraction? = slot.current()?.published

    private fun admitLocked(
        pending: Window,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<RevealChoiceInteractionResult>? {
        val selectedInstanceIds = message.selectNResp.idsList
        if (selectedInstanceIds.size !in pending.value.min..pending.value.max) return null
        if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return null
        val options = selectedInstanceIds.map { pending.optionByInstanceId[it] ?: return null }
        val handles = options.map(pending.handlesByOption::getValue)
        return SettledPromptOwner.SlotAdmission(
            RevealChoiceInteractionResult(options, handles, timedOut = false),
            beforeComplete = { completeFamilyState(pending, handles) },
        )
    }

    private fun publish(initial: RevealChoiceWindowCapture.Initial): Window =
        slot.publish(
            duplicateMessage = "A RevealChoice interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val preparedViewers =
                    try {
                        feed.builder.prepareRevealChoiceWindow(
                            game ?: failInitial(IllegalStateException("Game unavailable"), initial),
                            planner,
                            initial.value,
                            owner.viewerRoutes(runtimeSeat),
                        )
                    } catch (ex: Exception) {
                        failInitial(ex, initial, diagnostic = diagnostic)
                    }
                val prepared = preparedViewers.player
                val published = PublishedRevealChoiceInteraction(interactionId, checkNotNull(prepared.bundle.actionGameStateId))
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        initial.value,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                val projection = prepared.transition.nextState
                val entries =
                    initial.value.candidates.map { candidate ->
                        val instanceId =
                            projection.identities.forgeIdToInstanceId[candidate.forgeCardId]?.value
                                ?: failInitial(IllegalStateException("RevealChoice candidate was not projected"), initial, exact)
                        instanceId to candidate.originalOptionIndex
                    }
                val optionByInstanceId = entries.toMap()
                if (optionByInstanceId.size != entries.size) {
                    failInitial(IllegalStateException("RevealChoice candidates have ambiguous identities"), initial, exact)
                }
                val created =
                    Window(
                        published,
                        initial.value,
                        initial.revealEntry,
                        exact,
                        initial.handlesByOption,
                        optionByInstanceId,
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
    ): RevealChoiceInteractionResult =
        slot.await(
            pending = pending,
            timeoutMs = timeoutMs,
            timeoutException = { error("RevealChoice timeout should complete with a default") },
            beforeTimeoutCompleteLocked = {
                val fallback = listOf(pending.value.defaultOptionIndex).filter(pending.handlesByOption::containsKey)
                completeLocked(pending, fallback, timedOut = true)
            },
        )

    private fun completeLocked(
        pending: Window,
        options: List<Int>,
        timedOut: Boolean,
    ): Boolean {
        val handles = options.map(pending.handlesByOption::getValue)
        completeFamilyState(pending, handles)
        return slot.completeLocked(pending, RevealChoiceInteractionResult(options, handles, timedOut))
    }

    private fun completeFamilyState(
        pending: Window,
        handles: List<Card>,
    ) {
        pending.value.exileUnderSourceForgeCardId?.let { source ->
            handles.forEach { card ->
                journal(pending.value.journalSeatId).record(
                    PromptSideEffect.ExiledUnderSource(ForgeCardId(card.id), source),
                )
            }
        }
        clearReveal(pending.revealEntry, pending.value.journalSeatId)
    }

    private fun failInitial(
        cause: Throwable,
        initial: RevealChoiceWindowCapture.Initial,
        pending: PendingPromptCut<RevealChoiceWindowValue>? = null,
        diagnostic: PromptMaterializationDiagnostic<RevealChoiceWindowValue>? = null,
    ): Nothing {
        clearReveal(initial.revealEntry, initial.value.journalSeatId)
        owner.failPrompt(cause, pending, diagnostic)
    }

    private fun clearReveal(
        entry: PromptJournal.RevealEntry,
        seatId: leyline.bridge.types.SeatId,
    ) {
        journal(seatId).clearActiveReveal(entry)
    }

    private fun journal(seatId: leyline.bridge.types.SeatId): PromptJournal =
        owner.bridge
            .seat(seatId)
            .prompt.journal
}
