package leyline.bridge.coord

import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedTriggerOrderInteraction
import leyline.bridge.handoff.TriggerOrderInteractionResult
import leyline.bridge.handoff.TriggerOrderInteractionRuntime
import leyline.bridge.handoff.TriggerOrderWindowValue
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.OrderingType
import java.util.concurrent.CompletableFuture

/** Exact simultaneous-trigger ordering lifecycle beneath [MatchCutCoordinator]. */
internal class MatchTriggerOrderInteractionRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : TriggerOrderInteractionRuntime {
    private data class Window(
        val published: PublishedTriggerOrderInteraction,
        val value: TriggerOrderWindowValue,
        override val cut: PendingPromptCut<TriggerOrderWindowValue>,
        val handlesByOption: Map<Int, SpellAbility>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<TriggerOrderInteractionResult> = CompletableFuture(),
    ) : SettledPromptOwner.Window<TriggerOrderInteractionResult> {
        override val interactionId: String get() = published.interactionId

        fun result(
            optionIndices: List<Int>,
            timedOut: Boolean = false,
        ) = TriggerOrderInteractionResult(optionIndices, optionIndices.map(handlesByOption::getValue), timedOut)
    }

    private val capture = TriggerOrderWindowCapture(owner, runtimeSeat)
    private val slot =
        settled.mount<Window, TriggerOrderInteractionResult>(
            PromptTerminalPriority.TriggerOrder,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
            owns = { _, message -> message.type == ClientMessageType.SelectNresp },
            admitLocked = ::admitLocked,
        )

    override fun awaitTriggerOrder(
        request: PromptRequest,
        abilities: List<SpellAbility>,
        timeoutMs: Long?,
    ): TriggerOrderInteractionResult? {
        val initial = capture.initial(request, abilities) ?: return null
        return await(publish(initial), timeoutMs)
    }

    internal fun current(): PublishedTriggerOrderInteraction? = slot.current()?.published

    private fun admitLocked(
        pending: Window,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<TriggerOrderInteractionResult>? {
        val response = message.selectNResp
        val forgeOrder = pending.value.options.map { it.originalOptionIndex }
        val optionIndices =
            when (response.useArbitrary) {
                // The client's "auto order triggers" setting answers with a placeholder id and asks
                // the server to choose; Forge's own order is that choice.
                OrderingType.OrderArbitraryOnce, OrderingType.OrderArbitraryAlways -> forgeOrder
                else ->
                    // The client answers left to right, "Last" first; Forge wants the first
                    // trigger to resolve first.
                    response.idsList.reversed().map { pending.optionByInstanceId[it] ?: return null }
            }
        if (optionIndices.size != forgeOrder.size || optionIndices.toSet() != forgeOrder.toSet()) return null
        return SettledPromptOwner.SlotAdmission(pending.result(optionIndices))
    }

    private fun publish(initial: TriggerOrderWindowCapture.Initial): Window =
        slot.publish(
            duplicateMessage = "A trigger-order interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val preparedViewers =
                    try {
                        feed.builder.prepareTriggerOrderWindow(
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
                    PublishedTriggerOrderInteraction(interactionId, checkNotNull(prepared.bundle.actionGameStateId))
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        initial.value,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                // The published ids run "Last" to "First", the reverse of Forge's option order.
                val publishedIds =
                    prepared.bundle.messages
                        .single { it.hasSelectNReq() }
                        .selectNReq.idsList
                val optionByInstanceId =
                    publishedIds
                        .zip(
                            initial.value.options
                                .reversed()
                                .map { it.originalOptionIndex },
                        ).toMap()
                if (optionByInstanceId.size != initial.value.options.size) {
                    owner.failPrompt(IllegalStateException("Trigger order options have ambiguous identities"), exact)
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

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): TriggerOrderInteractionResult =
        slot.await(
            pending = pending,
            timeoutMs = timeoutMs,
            timeoutException = { error("Trigger order timeout should complete with a default") },
            beforeTimeoutCompleteLocked = {
                check(
                    slot.completeLocked(
                        pending,
                        pending.result(pending.value.options.map { it.originalOptionIndex }, timedOut = true),
                    ),
                )
            },
        )
}
