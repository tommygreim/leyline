package leyline.bridge.coord

import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.ModalChoiceAiContext
import leyline.bridge.handoff.ModalChoiceInteractionResult
import leyline.bridge.handoff.ModalChoiceInteractionRuntime
import leyline.bridge.handoff.ModalChoiceWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedModalChoiceInteraction
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.bundle.ModalChoiceWindowMaterializer
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import java.util.concurrent.CompletableFuture

/** Exact Forge-handle modal lifecycle beneath [MatchCutCoordinator]. */
internal class MatchModalChoiceRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : ModalChoiceInteractionRuntime {
    private data class Window(
        val published: PublishedModalChoiceInteraction,
        val value: ModalChoiceWindowValue,
        override val cut: PendingPromptCut<ModalChoiceWindowValue>,
        val handlesByOptionIndex: Map<Int, AbilitySub>,
        val optionIndexByGrpId: Map<Int, Int>,
        val aiContext: ModalChoiceAiContext,
        override val future: CompletableFuture<ModalChoiceInteractionResult> = CompletableFuture(),
    ) : SettledPromptOwner.Window<ModalChoiceInteractionResult> {
        override val interactionId: String get() = published.interactionId
    }

    private data class CleanupReceipt(
        val sourceInstanceId: Int,
        val triggered: Boolean,
        val cut: PendingPromptCut<ModalChoiceWindowValue>,
    )

    private val slot =
        settled.mount<Window, ModalChoiceInteractionResult>(
            PromptTerminalPriority.ModalChoice,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
            owns = { _, message ->
                (
                    message.type == ClientMessageType.CastingTimeOptionsResp_097b &&
                        message.castingTimeOptionsResp.castingTimeOptionResp.castingTimeOptionType ==
                        CastingTimeOptionType.Modal_a7b4
                ) ||
                    message.type == ClientMessageType.CancelActionReq_097b
            },
            admitLocked = ::admitLocked,
            cancelCapable = true,
        )

    override fun awaitSelection(
        request: PromptRequest,
        possible: List<AbilitySub>,
        sourceCard: Card,
        sourceAbility: SpellAbility,
        timeoutMs: Long?,
    ): ModalChoiceInteractionResult {
        val initial =
            try {
                ModalChoiceWindowCapture(owner).capture(request, possible, sourceCard, sourceAbility)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return await(publish(initial), timeoutMs)
    }

    fun current(): PublishedModalChoiceInteraction? = slot.current()?.published

    /** Read-only Forge context for the harness policy; no prompt/session lookup. */
    internal fun aiContext(): ModalChoiceAiContext? =
        synchronized(owner.feedLock) {
            slot.current()?.aiContext
        }

    private fun admitLocked(
        pending: Window,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<ModalChoiceInteractionResult>? {
        val selectedGrpIds =
            if (message.type == ClientMessageType.CancelActionReq_097b) {
                emptyList()
            } else {
                if (!message.castingTimeOptionsResp.castingTimeOptionResp.hasChooseModalResp()) return null
                message.castingTimeOptionsResp.castingTimeOptionResp.chooseModalResp.grpIdsList
            }
        if (message.type != ClientMessageType.CancelActionReq_097b) {
            if (selectedGrpIds.size !in pending.value.min..pending.value.max) return null
            if (!pending.value.allowRepeat && selectedGrpIds.size != selectedGrpIds.distinct().size) return null
        }
        val optionIndices = selectedGrpIds.map { pending.optionIndexByGrpId[it] ?: return null }
        val receipt = cleanupReceipt(pending)
        return SettledPromptOwner.SlotAdmission(
            ModalChoiceInteractionResult(
                optionIndices = optionIndices,
                handles = optionIndices.map(pending.handlesByOptionIndex::getValue),
                timedOut = false,
            ),
            beforeComplete = { recordSelection(pending, selectedGrpIds) },
            afterEngineResume = {
                synchronized(owner.feedLock) { queueCleanupLocked(receipt) }
            },
        )
    }

    private fun publish(initial: ModalChoiceWindowCapture.Initial): Window =
        slot.publish(
            duplicateMessage = "A ModalChoice interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, initial.value)
                val preparedViewers =
                    try {
                        feed.builder.prepareModalChoiceWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            planner,
                            initial.value,
                            owner.viewerRoutes(runtimeSeat),
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val prepared = preparedViewers.player
                val materialization = prepared.materialization
                val published =
                    PublishedModalChoiceInteraction(
                        interactionId,
                        checkNotNull(materialization.bundle.actionGameStateId),
                        prepared.sourceInstanceId,
                    )
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        initial.value,
                        materialization.bundle.messages,
                        materialization.transition,
                    )
                val created =
                    Window(
                        published,
                        initial.value,
                        exact,
                        initial.handlesByOptionIndex,
                        initial.value.possible
                            .mapIndexed { index, option -> option.grpId to index }
                            .toMap(),
                        initial.aiContext,
                    )
                SettledPromptOwner.Publication(
                    created,
                    materialization.transition,
                    materialization.closesPlaybackFrame,
                    preparedViewers.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                    materialization.correlation,
                )
            },
        )

    private fun await(
        pending: Window,
        timeoutMs: Long?,
    ): ModalChoiceInteractionResult =
        slot.await(
            pending = pending,
            timeoutMs = timeoutMs,
            timeoutException = { error("ModalChoice timeout should complete with a default") },
            beforeTimeoutCompleteLocked = {
                val fallback = listOf(pending.value.defaultOptionIndex)
                val grpIds = fallback.map { pending.value.possible[it].grpId }
                recordSelection(pending, grpIds)
                val receipt = cleanupReceipt(pending)
                slot.completeLocked(
                    pending,
                    ModalChoiceInteractionResult(
                        optionIndices = fallback,
                        handles = fallback.map(pending.handlesByOptionIndex::getValue),
                        timedOut = true,
                    ),
                )
                queueCleanupLocked(receipt)
            },
        )

    private fun recordSelection(
        pending: Window,
        selectedGrpIds: List<Int>,
    ) {
        if (selectedGrpIds.singleOrNull() != null) {
            owner.bridge.recordSelectedModalAbilityGrpId(
                pending.value.sourceForgeCardId,
                selectedGrpIds.single(),
            )
        }
    }

    private fun cleanupReceipt(pending: Window): CleanupReceipt =
        CleanupReceipt(
            pending.published.sourceInstanceId,
            pending.value.triggered,
            pending.cut,
        )

    private fun queueCleanupLocked(receipt: CleanupReceipt) {
        if (!receipt.triggered) return
        val feed = owner.feed(runtimeSeat)
        val prior = owner.bridge.projectionStateSnapshot()
        val planner = LogicalSequencePlanner(prior.sequence)
        val cleanup = ModalChoiceWindowMaterializer(runtimeSeat.value).cleanup(planner, receipt.sourceInstanceId)
        try {
            owner.cutInstaller.install(
                feed,
                PreparedCut.prepare(prior, planner, listOf(cleanup), projection = null, closesPlaybackFrame = false),
                onFailure = { ex -> owner.failPrompt(ex, pending = receipt.cut) },
            )
        } catch (ex: Exception) {
            owner.failPrompt(ex, pending = receipt.cut)
        }
    }
}
