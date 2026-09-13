package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.GatherCountersPayment
import leyline.bridge.handoff.GatherCountersResult
import leyline.bridge.handoff.GatherCountersSelection
import leyline.bridge.handoff.GatherCountersWindowInput
import leyline.bridge.handoff.GatherCountersWindowValue
import leyline.bridge.handoff.OneShotPayCostsResult
import leyline.bridge.handoff.OneShotPayCostsRuntime
import leyline.bridge.handoff.OneShotPayCostsTimeoutException
import leyline.bridge.handoff.OneShotPayCostsWindow
import leyline.bridge.handoff.OneShotPayCostsWindowKind
import leyline.bridge.handoff.OneShotPayCostsWindowValue
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.PublishedOneShotPayCostsInteraction
import leyline.bridge.handoff.firstFitGatherCounters
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import java.util.concurrent.CompletableFuture

/** Match-scoped lifecycle for Select and the bounded GatherCounters PayCosts windows. */
internal class MatchOneShotPayCostsRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : OneShotPayCostsRuntime {
    private data class SelectWindow(
        val published: PublishedOneShotPayCostsInteraction,
        val value: OneShotPayCostsWindowValue,
        override val cut: PendingPromptCut<OneShotPayCostsWindow>,
        val handlesByOption: Map<Int, Card>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<OneShotPayCostsResult> = CompletableFuture(),
    ) : SettledPromptOwner.Window<OneShotPayCostsResult> {
        override val interactionId: String get() = published.interactionId
    }

    private data class GatherWindow(
        val published: PublishedOneShotPayCostsInteraction,
        val value: GatherCountersWindowValue,
        override val cut: PendingPromptCut<OneShotPayCostsWindow>,
        val handlesBySourceId: Map<Int, Card>,
        val sourceByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<GatherCountersResult> = CompletableFuture(),
    ) : SettledPromptOwner.Window<GatherCountersResult> {
        override val interactionId: String get() = published.interactionId

        fun firstFit(timedOut: Boolean): GatherCountersResult =
            firstFitGatherCounters(value.sources, value.amountToGather, handlesBySourceId, timedOut)
    }

    private val selectSlot =
        settled.mount<SelectWindow, OneShotPayCostsResult>(
            PromptTerminalPriority.OneShotPayCosts,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
            owns = { _, message ->
                message.type == ClientMessageType.CancelActionReq_097b ||
                    (
                        message.type == ClientMessageType.EffectCostResp_097b &&
                            message.effectCostResp.effectCostType == EffectCostType.Select_a59c
                    )
            },
            admitLocked = ::admitSelectLocked,
            cancelCapable = true,
        )
    private val gatherSlot =
        settled.mount<GatherWindow, GatherCountersResult>(
            PromptTerminalPriority.OneShotPayCosts,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
            owns = { _, message ->
                message.type == ClientMessageType.CancelActionReq_097b ||
                    (
                        message.type == ClientMessageType.EffectCostResp_097b &&
                            message.effectCostResp.effectCostType == EffectCostType.GatherCounters
                    )
            },
            admitLocked = ::admitGatherLocked,
            cancelCapable = true,
        )
    private val capture = OneShotPayCostsWindowCapture(owner)
    private val gatherCapture = GatherCountersWindowCapture(capture)

    override fun awaitPayment(
        request: PromptRequest,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): OneShotPayCostsResult {
        val initial =
            try {
                capture.initial(request, candidateHandles)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return selectSlot.await(publishSelect(initial), timeoutMs, ::OneShotPayCostsTimeoutException)
    }

    override fun awaitGatherCounters(
        window: GatherCountersWindowInput,
        candidateHandles: List<Card>,
        timeoutMs: Long?,
    ): GatherCountersResult {
        val initial =
            try {
                gatherCapture.initial(window, candidateHandles)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        return awaitGather(publishGather(initial), timeoutMs)
    }

    fun current(): PublishedOneShotPayCostsInteraction? =
        synchronized(owner.feedLock) {
            selectSlot.current()?.published ?: gatherSlot.current()?.published
        }

    private fun admitSelectLocked(
        pending: SelectWindow,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<OneShotPayCostsResult>? {
        if (message.type == ClientMessageType.CancelActionReq_097b) {
            return SettledPromptOwner.SlotAdmission(OneShotPayCostsResult(emptyList(), emptyList()))
        }
        val selectedInstanceIds = message.effectCostResp.costSelection.idsList
        if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return null
        val options = selectedInstanceIds.map { pending.optionByInstanceId[it] ?: return null }
        if (options.size !in pending.value.minSelections..pending.value.maxSelections) return null
        val selected = pending.value.candidates.filter { it.originalOptionIndex in options }
        if (pending.value.minimumWeight?.let { minimum -> selected.sumOf { it.weight } < minimum } == true) return null
        return SettledPromptOwner.SlotAdmission(
            OneShotPayCostsResult(options, options.map(pending.handlesByOption::getValue)),
        )
    }

    private fun admitGatherLocked(
        pending: GatherWindow,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<GatherCountersResult>? {
        if (message.type == ClientMessageType.CancelActionReq_097b) {
            return SettledPromptOwner.SlotAdmission(GatherCountersResult.EMPTY)
        }
        val selections =
            message.effectCostResp.gatherResp.gatheringsList.map {
                GatherCountersSelection(it.instanceId, it.amount)
            }
        if (selections.size != selections.distinctBy { it.instanceId }.size) return null
        if (selections.any { it.amount <= 0 }) return null
        val amountsBySource = pending.sourceByInstanceId
        if (selections.any { it.instanceId !in amountsBySource }) return null
        val capacities =
            pending.value.sources.associate { source ->
                pending.sourceByInstanceId.entries
                    .first { it.value == source.forgeCardId.value }
                    .key to source.maxAmount
            }
        if (selections.any { it.amount > capacities.getValue(it.instanceId) }) return null
        if (selections.sumOf { it.amount } != pending.value.amountToGather) return null
        val payments =
            selections.map { selection ->
                GatherCountersPayment(
                    pending.handlesBySourceId.getValue(amountsBySource.getValue(selection.instanceId)),
                    selection.amount,
                )
            }
        return SettledPromptOwner.SlotAdmission(GatherCountersResult(payments))
    }

    private fun publishSelect(initial: OneShotPayCostsWindowCapture.Initial): SelectWindow =
        selectSlot.publish(
            duplicateMessage = DUPLICATE_MESSAGE,
            prepare = { interactionId, _, game, planner ->
                val resolved = game ?: owner.fail(IllegalStateException("Game unavailable"))
                val routes = owner.viewerRoutes(runtimeSeat)
                val playerRoute = routes.single { it.viewer.role == leyline.game.state.ProjectionViewerRole.Player }
                val window = OneShotPayCostsWindow.Select(initial.value)
                val diagnostic = PromptMaterializationDiagnostic(interactionId, window)
                if (initial.value.kind == PayCostsRouteKind.CollectEvidence) {
                    owner.bridge.promptBridge(runtimeSeat).journal.record(
                        PromptSideEffect.CollectEvidenceCost(
                            checkNotNull(initial.value.sourceForgeCardId),
                            checkNotNull(initial.value.minimumWeight),
                        ),
                    )
                }
                val prepared =
                    try {
                        playerRoute.builder.prepareOneShotPayCosts(resolved, planner, initial.value, routes)
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val player = prepared.player
                val published =
                    PublishedOneShotPayCostsInteraction(
                        interactionId,
                        checkNotNull(player.bundle.actionGameStateId),
                        selectKind = initial.value.kind,
                    )
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        window,
                        player.bundle.messages,
                        player.transition,
                    )
                val projection = player.transition.nextState
                val optionEntries =
                    initial.value.candidates.map { candidate ->
                        val instanceId =
                            projection.identities.forgeIdToInstanceId[candidate.forgeCardId]?.value
                                ?: owner.failPrompt(IllegalStateException("PayCosts candidate was not projected"), exact)
                        instanceId to candidate.originalOptionIndex
                    }
                val optionByInstanceId = optionEntries.toMap()
                if (optionByInstanceId.size != optionEntries.size) {
                    owner.failPrompt(IllegalStateException("PayCosts candidates have ambiguous identities"), exact)
                }
                SettledPromptOwner.Publication(
                    SelectWindow(published, initial.value, exact, initial.handlesByOption, optionByInstanceId),
                    player.transition,
                    player.closesPlaybackFrame,
                    prepared.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                    player.correlation,
                )
            },
            ensureEmptyLocked = { gatherSlot.ensureEmptyLocked(DUPLICATE_MESSAGE) },
        )

    private fun publishGather(initial: GatherCountersWindowCapture.Initial): GatherWindow =
        gatherSlot.publish(
            duplicateMessage = DUPLICATE_MESSAGE,
            prepare = { interactionId, _, game, planner ->
                val resolved = game ?: owner.fail(IllegalStateException("Game unavailable"))
                val routes = owner.viewerRoutes(runtimeSeat)
                val playerRoute = routes.single { it.viewer.role == leyline.game.state.ProjectionViewerRole.Player }
                val value = initial.value
                val window = OneShotPayCostsWindow.GatherCounters(value)
                val diagnostic = PromptMaterializationDiagnostic(interactionId, window)
                val prepared =
                    try {
                        playerRoute.builder.prepareGatherCounters(resolved, planner, value, routes)
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val player = prepared.player
                val published =
                    PublishedOneShotPayCostsInteraction(
                        interactionId,
                        checkNotNull(player.bundle.actionGameStateId),
                        windowKind = OneShotPayCostsWindowKind.GatherCounters,
                    )
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        window,
                        player.bundle.messages,
                        player.transition,
                    )
                val projection = player.transition.nextState
                val sourceEntries =
                    value.sources.map { source ->
                        val instanceId =
                            projection.identities.forgeIdToInstanceId[source.forgeCardId]?.value
                                ?: owner.failPrompt(
                                    IllegalStateException("GatherCounters source was not projected"),
                                    exact,
                                )
                        instanceId to source.forgeCardId.value
                    }
                val sourceByInstanceId = sourceEntries.toMap()
                if (sourceByInstanceId.size != sourceEntries.size) {
                    owner.failPrompt(IllegalStateException("GatherCounters sources have ambiguous identities"), exact)
                }
                SettledPromptOwner.Publication(
                    GatherWindow(published, value, exact, initial.handlesBySourceId, sourceByInstanceId),
                    player.transition,
                    player.closesPlaybackFrame,
                    prepared.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                    player.correlation,
                )
            },
            ensureEmptyLocked = { selectSlot.ensureEmptyLocked(DUPLICATE_MESSAGE) },
        )

    private fun awaitGather(
        pending: GatherWindow,
        timeoutMs: Long?,
    ): GatherCountersResult =
        gatherSlot.await(
            pending = pending,
            timeoutMs = timeoutMs,
            timeoutException = { error("GatherCounters timeout should complete with a default") },
            beforeTimeoutCompleteLocked = {
                gatherSlot.completeLocked(pending, pending.firstFit(timedOut = true))
            },
        )

    private companion object {
        const val DUPLICATE_MESSAGE = "A one-shot PayCosts interaction is already pending"
    }
}
