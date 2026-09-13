package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.OrderInteractionResult
import leyline.bridge.handoff.OrderInteractionRuntime
import leyline.bridge.handoff.OrderInteractionTimeoutException
import leyline.bridge.handoff.OrderMoveIntent
import leyline.bridge.handoff.OrderWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedOrderInteraction
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import java.util.concurrent.CompletableFuture

/** Exact ordered-card lifecycle beneath [MatchCutCoordinator]. */
internal class MatchOrderInteractionRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : OrderInteractionRuntime {
    private data class Window(
        val published: PublishedOrderInteraction,
        val value: OrderWindowValue,
        override val cut: PendingPromptCut<OrderWindowValue>,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<OrderInteractionResult> = CompletableFuture(),
    ) : SettledPromptOwner.Window<OrderInteractionResult> {
        override val interactionId: String get() = published.interactionId
    }

    private val slot =
        settled.mount<Window, OrderInteractionResult>(
            PromptTerminalPriority.Order,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
            owns = { _, message -> message.type == ClientMessageType.OrderResp_097b },
            admitLocked = ::admitLocked,
        )

    override fun awaitOrder(
        request: PromptRequest,
        candidateHandles: List<Card>,
        move: OrderMoveIntent?,
        timeoutMs: Long?,
    ): OrderInteractionResult {
        val initial =
            try {
                OrderWindowCapture.initial(request, candidateHandles, move)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    fun current(): PublishedOrderInteraction? = slot.current()?.published

    private fun admitLocked(
        pending: Window,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<OrderInteractionResult>? {
        val orderedInstanceIds = message.orderResp.idsList
        if (orderedInstanceIds.size != pending.value.candidates.size) return null
        if (orderedInstanceIds.size != orderedInstanceIds.distinct().size) return null
        val options = orderedInstanceIds.map { pending.optionByInstanceId[it] ?: return null }
        if (options.toSet() != pending.handlesByOption.keys) return null
        return SettledPromptOwner.SlotAdmission(OrderInteractionResult(options, options.map(pending.handlesByOption::getValue)))
    }

    private fun publish(initial: OrderWindowCapture.Initial): Window =
        slot.publish(
            duplicateMessage = "An Order interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val preparedViewers =
                    try {
                        feed.builder.prepareOrderWindow(
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
                    PublishedOrderInteraction(
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
                                ?: owner.failPrompt(IllegalStateException("Order candidate was not projected"), exact)
                        instanceId to candidate.originalOptionIndex
                    }
                val optionsByInstanceId = entries.toMap()
                if (optionsByInstanceId.size != entries.size) {
                    owner.failPrompt(IllegalStateException("Order candidates have ambiguous identities"), exact)
                }
                val created = Window(published, initial.value, exact, initial.handlesByOption, optionsByInstanceId)
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
    ): OrderInteractionResult = slot.await(pending, timeoutMs, ::OrderInteractionTimeoutException)
}
