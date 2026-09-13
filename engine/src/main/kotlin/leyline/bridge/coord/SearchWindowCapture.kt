package leyline.bridge.coord

import forge.game.zone.ZoneType
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.SearchGroupValue
import leyline.bridge.handoff.SearchWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind

/** Engine-thread capture for one immutable library-search window. */
internal class SearchWindowCapture(
    private val owner: MatchCutCoordinator,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) {
    private companion object {
        const val FIRST_GROUP_ID = 5003
    }

    fun capture(request: PromptRequest): SearchWindowValue {
        val player = owner.bridge.getPlayer(runtimeSeat) ?: error("Search player unavailable")
        val candidateIds =
            request.candidateRefs
                .filter { it.kind == PromptCandidateKind.Card }
                .associate { it.index to ForgeCardId(it.entityId) }
        return SearchWindowValue(
            libraryCardIds = player.getZone(ZoneType.Library).cards.map { ForgeCardId(it.id) },
            candidateCardIdsByOption = candidateIds,
            optionCount = request.options.size,
            minFind = request.min,
            maxFind = request.max,
            defaultIndex = request.defaultIndex,
            source = request.searchSource,
            groups =
                request.searchGroupOptionIndices.mapIndexed { index, options ->
                    SearchGroupValue(
                        groupId = FIRST_GROUP_ID + index,
                        candidateCardIdsByOption =
                            options.associateWith {
                                candidateIds[it]
                                    ?: error("Search group references unknown option $it")
                            },
                    )
                },
        )
    }
}
