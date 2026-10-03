package leyline.game.bundle

import leyline.bridge.handoff.SearchWindowValue
import leyline.bridge.types.SeatId
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AllowFailToFind
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Group
import wotc.mtgo.gre.external.messaging.Messages.GroupingStyle
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.SearchFromGroupsReq

/** Value-only GRE preparation for coordinator-owned library-search windows. */
internal class SearchWindowMaterializer(
    private val seatId: SeatId,
) {
    fun initial(
        stateMessages: List<GREToClientMessage>,
        context: SettledPromptMaterializationContext,
        window: SearchWindowValue,
    ): SettledPromptMaterialization {
        val libraryIds = window.libraryCardIds.map { context.requiredInstanceId(it, "Search card") }
        val validIds = window.candidateCardIdsByOption.values.map { context.requiredInstanceId(it, "Search card") }
        val hostId = window.source?.hostCardId?.let { context.requiredInstanceId(it, "Search card") } ?: 0
        val sourceId =
            window.source
                ?.takeIf { it.abilityOnStack && it.forgeAbilityId != 0 }
                ?.let { context.requiredInstanceId(FrameIdResolver.triggerStackAbilityForgeId(it.forgeAbilityId), "Search card") }
                ?: hostId
        val promptId =
            if (window.source?.let { it.abilityOnStack && it.typeCycling } == true) {
                PromptIds.SEARCH_TYPECYCLING
            } else {
                PromptIds.SEARCH
            }
        val request =
            if (window.groups.isNotEmpty()) {
                context.message(GREMessageType.SearchFromGroupsReq_695e) {
                    val groupedRequest =
                        SearchFromGroupsReq
                            .newBuilder()
                            .setMaxFind(window.maxFind)
                            .addZonesToSearch(ZoneIds.libraryOf(window.libraryOwnerSeatId ?: SeatId(context.seatId)))
                            .addAllGroups(
                                window.groups.map { group ->
                                    Group
                                        .newBuilder()
                                        .setGroupId(group.groupId)
                                        .setMaxSelect(group.maxSelect)
                                        .addAllIds(
                                            group.candidateCardIdsByOption.values.map {
                                                context.requiredInstanceId(it, "Search candidate")
                                            },
                                        ).build()
                                },
                            ).setGroupingStyle(GroupingStyle.SingleGroup)
                            .setSourceId(hostId)
                    if (window.minFind == 0) groupedRequest.setAllowFailToFind(AllowFailToFind.Any)
                    it.searchFromGroupsReq = groupedRequest.build()
                    it.allowCancel = AllowCancel.No_a526
                    it.prompt =
                        Prompt
                            .newBuilder()
                            .setPromptId(PromptIds.SEARCH_FROM_GROUPS)
                            .addParameters(cardIdPromptParameter(hostId))
                            .build()
                }
            } else {
                context.message(GREMessageType.SearchReq_695e) {
                    it.searchReq =
                        RequestBuilder.buildSearchRequest(
                            sourceInstanceId = sourceId,
                            libraryZoneId = ZoneIds.libraryOf(window.libraryOwnerSeatId ?: SeatId(context.seatId)),
                            allLibraryIds = libraryIds,
                            validTargetIds = validIds,
                            maxFind = window.maxFind,
                            allowFailToFind = window.minFind == 0,
                        )
                    it.allowCancel = AllowCancel.No_a526
                    it.prompt =
                        Prompt
                            .newBuilder()
                            .setPromptId(promptId)
                            .addParameters(cardIdPromptParameter(hostId))
                            .addParameters(cardIdPromptParameter(context.seatId))
                            .build()
                }
            }
        return context.prepared(stateMessages + request, awaitedRequest = request)
    }

    fun resetBaseline(prior: ProjectionState): ProjectionTransition {
        val editor = prior.editor()
        val cursor = editor.viewerCursors[seatId] ?: leyline.game.state.ViewerProjectionCursor()
        editor.viewerCursors[seatId] = cursor.copy(previousSnapshot = null)
        return ProjectionTransition(prior.revision, editor.freeze())
    }
}
