package leyline.bridge.coord

import leyline.bridge.handoff.DistributionInteractionResult
import leyline.bridge.handoff.DistributionInteractionRuntime
import leyline.bridge.handoff.DistributionInteractionTimeoutException
import leyline.bridge.handoff.DistributionTargetRef
import leyline.bridge.handoff.DistributionWindowValue
import leyline.bridge.handoff.PublishedDistributionInteraction
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import java.util.concurrent.CompletableFuture

/** Exact fixed-total allocation lifecycle beneath [MatchCutCoordinator]. */
internal class MatchDistributionInteractionRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : DistributionInteractionRuntime {
    private data class Window(
        val published: PublishedDistributionInteraction,
        val value: DistributionWindowValue,
        override val cut: PendingPromptCut<DistributionWindowValue>,
        val targetByWireId: Map<Int, DistributionTargetRef>,
        override val future: CompletableFuture<DistributionInteractionResult> = CompletableFuture(),
    ) : SettledPromptOwner.Window<DistributionInteractionResult> {
        override val interactionId: String get() = published.interactionId
    }

    private val slot =
        settled.mount<Window, DistributionInteractionResult>(
            PromptTerminalPriority.Distribution,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
            owns = { _, message ->
                message.type == ClientMessageType.DistributionResp_097b ||
                    message.type == ClientMessageType.CancelActionReq_097b
            },
            admitLocked = ::admitLocked,
            cancelCapable = true,
        )

    override fun awaitDistribution(
        window: DistributionWindowValue,
        timeoutMs: Long?,
    ): DistributionInteractionResult = await(publish(window), timeoutMs)

    fun current(): PublishedDistributionInteraction? = slot.current()?.published

    private fun admitLocked(
        pending: Window,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<DistributionInteractionResult>? {
        if (message.type == ClientMessageType.CancelActionReq_097b) {
            return SettledPromptOwner.SlotAdmission(
                DistributionInteractionResult(
                    pending.value.targets.associateWith { 0 },
                    cancelled = true,
                ),
            )
        }
        val rows =
            message.distributionResp.distributionsList.map { row ->
                val target = pending.targetByWireId[row.instanceId] ?: return null
                target to row.amount
            }
        return validate(pending, rows)?.let(SettledPromptOwner::SlotAdmission)
    }

    private fun publish(initial: DistributionWindowValue): Window =
        slot.publish(
            duplicateMessage = "A Distribution interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial)
                val preparedViewers =
                    try {
                        feed.builder.prepareDistributionWindow(
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
                    PublishedDistributionInteraction(interactionId, checkNotNull(prepared.bundle.actionGameStateId), initial.kind)
                val exact =
                    PendingPromptCut(interactionId, published.gameStateId, initial, prepared.bundle.messages, prepared.transition)
                val targetByWireId =
                    initial.targets.associateBy { target ->
                        when (target) {
                            is DistributionTargetRef.Card ->
                                prepared.transition.nextState.identities.forgeIdToInstanceId[target.id]
                                    ?.value
                                    ?: owner.fail(IllegalStateException("Distribution target has no projected instance id"))
                            is DistributionTargetRef.Player -> target.id.value
                        }
                    }
                if (targetByWireId.size != initial.targets.size) {
                    owner.fail(IllegalStateException("Distribution targets have colliding wire ids"))
                }
                SettledPromptOwner.Publication(
                    Window(published, initial, exact, targetByWireId),
                    prepared.transition,
                    prepared.closesPlaybackFrame,
                    preparedViewers.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                    prepared.correlation,
                )
            },
        )

    private fun validate(
        pending: Window,
        rows: List<Pair<DistributionTargetRef, Int>>,
    ): DistributionInteractionResult? {
        val expected = pending.value.targets.toSet()
        if (rows.size != expected.size || rows.map { it.first }.toSet() != expected) return null
        if (rows.any { it.second < pending.value.minPerTarget }) return null
        if (rows.sumOf { it.second } != pending.value.amount) return null
        return DistributionInteractionResult(rows.toMap())
    }

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): DistributionInteractionResult =
        try {
            slot.await(pending, timeoutMs, ::DistributionInteractionTimeoutException)
        } catch (_: DistributionInteractionTimeoutException) {
            pending.value.fallback()
        }
}
