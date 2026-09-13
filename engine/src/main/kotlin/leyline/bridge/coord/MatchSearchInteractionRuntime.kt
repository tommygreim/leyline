package leyline.bridge.coord

import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PublishedSearchInteraction
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SearchInteractionRuntime
import leyline.bridge.handoff.SearchInteractionTimeoutException
import leyline.bridge.handoff.SearchWindowValue
import leyline.game.PendingPromptCut
import leyline.game.PromptMaterializationDiagnostic
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import java.util.concurrent.CompletableFuture

/** Exact library-search lifecycle beneath [MatchCutCoordinator]. */
internal class MatchSearchInteractionRuntime(
    private val owner: MatchCutCoordinator,
    settled: SettledPromptOwner,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) : SearchInteractionRuntime {
    private data class Window(
        val published: PublishedSearchInteraction,
        val value: SearchWindowValue,
        override val cut: PendingPromptCut<SearchWindowValue>,
        val optionByInstanceId: Map<Int, Int>,
        override val future: CompletableFuture<List<Int>> = CompletableFuture(),
    ) : SettledPromptOwner.Window<List<Int>> {
        override val interactionId: String get() = published.interactionId
    }

    private val capture = SearchWindowCapture(owner, runtimeSeat)
    private val slot =
        settled.mount<Window, List<Int>>(
            PromptTerminalPriority.Search,
            publicationFailure = { cause, failed -> owner.failPrompt(cause, failed.cut) },
            owns = { pending, message ->
                message.type ==
                    if (pending.value.groups.isEmpty()) {
                        ClientMessageType.SearchResp_097b
                    } else {
                        ClientMessageType.SearchFromGroupsResp_097b
                    }
            },
            admitLocked = ::admitLocked,
        )

    internal var beforeBaselineResetInstall: (() -> Unit)? = null
    internal var afterBaselineResetBeforeRelease: (() -> Unit)? = null

    override fun awaitSearch(
        request: PromptRequest,
        timeoutMs: Long?,
    ): List<Int> {
        check(request.route is ResolvedPromptRoute.Search)
        val value =
            try {
                capture.capture(request)
            } catch (ex: Exception) {
                owner.fail(ex)
            }
        val pending = publish(value)
        return await(pending, timeoutMs)
    }

    fun current(): PublishedSearchInteraction? = slot.current()?.published

    @Suppress("ReturnCount")
    private fun admitLocked(
        pending: Window,
        message: ClientToGREMessage,
    ): SettledPromptOwner.SlotAdmission<List<Int>>? {
        val selectedOptions =
            if (pending.value.groups.isEmpty()) {
                if (!message.hasSearchResp()) return null
                val selectedInstanceIds = message.searchResp.itemsFoundList
                if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return null
                if (selectedInstanceIds.isEmpty()) {
                    if (pending.value.minFind != 0) return null
                    listOf(pending.value.optionCount)
                } else {
                    if (selectedInstanceIds.size !in pending.value.minFind..pending.value.maxFind) return null
                    selectedInstanceIds.map { pending.optionByInstanceId[it] ?: return null }
                }
            } else {
                if (!message.hasSearchFromGroupsResp()) return null
                val responseGroups = message.searchFromGroupsResp.groupsList
                if (responseGroups.isEmpty()) {
                    if (pending.value.minFind != 0) return null
                    listOf(pending.value.optionCount)
                } else {
                    if (responseGroups.size != 1) return null
                    val response = responseGroups.single()
                    val group = pending.value.groups.singleOrNull { it.groupId == response.groupId } ?: return null
                    if (response.maxSelect != group.maxSelect) return null
                    val selectedInstanceIds = response.idsList
                    if (selectedInstanceIds.isEmpty()) return null
                    if (selectedInstanceIds.size != selectedInstanceIds.distinct().size) return null
                    if (selectedInstanceIds.size !in pending.value.minFind..minOf(pending.value.maxFind, group.maxSelect)) return null
                    val allowedOptions = group.candidateCardIdsByOption.keys
                    selectedInstanceIds.map { instanceId ->
                        pending.optionByInstanceId[instanceId]?.takeIf(allowedOptions::contains) ?: return null
                    }
                }
            }
        return SettledPromptOwner.SlotAdmission(
            selectedOptions,
            beforeComplete = {
                resetBaseline()
                afterBaselineResetBeforeRelease?.invoke()
            },
        )
    }

    private fun publish(value: SearchWindowValue): Window =
        slot.publish(
            duplicateMessage = "A search interaction is already pending",
            prepare = { interactionId, feed, game, planner ->
                val diagnostic = PromptMaterializationDiagnostic(interactionId, value)
                val preparedViewers =
                    try {
                        feed.builder.prepareSearchWindow(
                            game ?: owner.fail(IllegalStateException("Game unavailable")),
                            planner,
                            value,
                            owner.viewerRoutes(runtimeSeat),
                        )
                    } catch (ex: Exception) {
                        owner.failPrompt(ex, diagnostic = diagnostic)
                    }
                val prepared = preparedViewers.player
                val published = PublishedSearchInteraction(interactionId, checkNotNull(prepared.bundle.actionGameStateId))
                val exact =
                    PendingPromptCut(
                        interactionId,
                        published.gameStateId,
                        value,
                        prepared.bundle.messages,
                        prepared.transition,
                    )
                val projection = prepared.transition.nextState
                val optionEntries =
                    value.candidateCardIdsByOption.map { (option, cardId) ->
                        val instanceId =
                            projection.identities.forgeIdToInstanceId[cardId]?.value
                                ?: owner.failPrompt(IllegalStateException("Search candidate ${cardId.value} was not projected"), exact)
                        instanceId to option
                    }
                val optionByInstanceId = optionEntries.toMap()
                if (optionByInstanceId.size != optionEntries.size) {
                    owner.failPrompt(IllegalStateException("Search candidates have ambiguous client identities"), exact)
                }
                val created = Window(published, value, exact, optionByInstanceId)
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
    ): List<Int> =
        slot.await(
            pending = pending,
            timeoutMs = timeoutMs,
            timeoutException = ::SearchInteractionTimeoutException,
            beforeTimeoutCompleteLocked = {
                resetBaseline()
                afterBaselineResetBeforeRelease?.invoke()
            },
        )

    private fun resetBaseline() {
        val transition = owner.feed(runtimeSeat).builder.prepareSearchBaselineReset(owner.bridge.projectionStateSnapshot())
        beforeBaselineResetInstall?.invoke()
        owner.cutInstaller.installProjectionOnly(transition, owner::fail)
    }
}
