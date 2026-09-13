package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.CardSelectInteractionResult
import leyline.bridge.handoff.CardSelectInteractionRuntime
import leyline.bridge.handoff.CardSelectInteractionTimeoutException
import leyline.bridge.handoff.CardSelectKind
import leyline.bridge.handoff.CardSelectWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedCardSelectInteraction
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import java.util.concurrent.CompletableFuture

/** Exact card-backed SelectN lifecycle beneath [MatchCutCoordinator]. */
internal class MatchCardSelectInteractionRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : CardSelectInteractionRuntime {
    private data class Window(
        val published: PublishedCardSelectInteraction,
        val value: CardSelectWindowValue,
        override val cut: PendingPromptCut<CardSelectWindowValue>,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<CardSelectInteractionResult> = CompletableFuture(),
    ) : SettledPromptOwner.Window<CardSelectInteractionResult> {
        override val interactionId: String get() = published.interactionId
    }

    private val slot =
        settled.mount<Window, CardSelectInteractionResult>(
            PromptTerminalPriority.CardSelect,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
            owns = ::owns,
            admitLocked = ::admitLocked,
        )

    override fun awaitSelection(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): CardSelectInteractionResult {
        val initial =
            try {
                CardSelectWindowCapture.initial(request, candidateHandles)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    fun current(): PublishedCardSelectInteraction? = slot.current()?.published

    private fun owns(
        pending: Window,
        message: ClientToGREMessage,
    ): Boolean =
        message.type == ClientMessageType.SelectNresp ||
            (
                message.type == ClientMessageType.EffectCostResp_097b &&
                    message.effectCostResp.effectCostType == EffectCostType.Select_a59c &&
                    pending.value.kind in effectCostKinds
            )

    private fun admitLocked(
        pending: Window,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<CardSelectInteractionResult>? {
        val selectedInstanceIds =
            if (message.type == ClientMessageType.SelectNresp) {
                message.selectNResp.idsList
            } else {
                if (!message.effectCostResp.hasCostSelection()) return null
                message.effectCostResp.costSelection.idsList
            }
        if (selectedInstanceIds.size !in pending.value.min..pending.value.max) return null
        if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return null
        val options = selectedInstanceIds.map { pending.optionByInstanceId[it] ?: return null }
        val result = CardSelectInteractionResult(options, options.map(pending.handlesByOption::getValue))
        return SettledPromptOwner.SlotAdmission(
            result,
            beforeComplete = { recordChoiceResults(pending, selectedInstanceIds) },
        )
    }

    private fun publish(initial: CardSelectWindowCapture.Initial): Window =
        slot.publish(
            duplicateMessage = "A CardSelect interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val preparedViewers =
                    try {
                        feed.builder.prepareCardSelectWindow(
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
                    PublishedCardSelectInteraction(
                        interactionId,
                        checkNotNull(prepared.bundle.actionGameStateId),
                        initial.value.kind,
                    )
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
                                ?: owner.failPrompt(IllegalStateException("CardSelect candidate was not projected"), exact)
                        instanceId to candidate.originalOptionIndex
                    }
                val optionByInstanceId = entries.toMap()
                if (optionByInstanceId.size != entries.size) {
                    owner.failPrompt(IllegalStateException("CardSelect candidates have ambiguous identities"), exact)
                }
                val created = Window(published, initial.value, exact, initial.handlesByOption, optionByInstanceId)
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
        selectedInstanceIds: List<Int>,
    ) {
        val source = pending.value.sourceForgeCardId ?: return
        val sentiment = pending.value.choiceResultSentiment ?: return
        selectedInstanceIds.forEach { instanceId ->
            owner.bridge
                .seat(runtimeSeat)
                .prompt.journal
                .record(PromptSideEffect.ChoiceResult(source, runtimeSeat, instanceId, sentiment = sentiment))
        }
    }

    private companion object {
        val effectCostKinds =
            setOf(
                CardSelectKind.Discard,
                CardSelectKind.SacrificeEffect,
                CardSelectKind.Suspect,
                CardSelectKind.MutateTopBottom,
            )
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): CardSelectInteractionResult = slot.await(pending, timeoutMs, ::CardSelectInteractionTimeoutException)
}
