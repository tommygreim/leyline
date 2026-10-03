package leyline.game.state

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot

/** Remember identities at known library positions, without granting anyone permission to look anew. */
object LibraryKnowledgeTracker {
    data class State(
        val viewers: Map<ForgeCardId, Set<SeatId>> = emptyMap(),
    )

    fun plan(
        prior: State,
        snap: GsmSnapshot,
        previousSnapshot: GsmSnapshot?,
        events: List<GameEvent>,
        instanceIdLookup: (ForgeCardId) -> InstanceId,
    ): State {
        val libraryOwners =
            snap.zones.values
                .filter { it.id == ZoneIds.P1_LIBRARY || it.id == ZoneIds.P2_LIBRARY }
                .flatMap { zone -> zone.contents.map { it to checkNotNull(zone.owner) } }
                .toMap()
        // Grouping arrangements carry client instance IDs, unlike ZoneChanged's Forge IDs.
        val cardsByInstanceId = libraryOwners.keys.associateBy { instanceIdLookup(it).value }
        val next = prior.viewers.filterKeys { it in libraryOwners }.toMutableMap()

        fun rememberPublicReturn(
            cardId: ForgeCardId,
            from: Zone,
        ) {
            // A visible object placed in a library stays known to both players.
            // Moving a face-down object or an unseen hand card must not reveal it.
            val previousCard = previousSnapshot?.objects?.get(cardId)
            val publicSource = from in setOf(Zone.Battlefield, Zone.Stack, Zone.Graveyard, Zone.Exile, Zone.Command)
            if (cardId in libraryOwners && publicSource && previousCard != null && !previousCard.isFaceDown) {
                next[cardId] = setOf(SeatId(1), SeatId(2))
            }
        }
        for (event in events) {
            when (event) {
                is GameEvent.LibraryShuffled -> next.keys.removeAll { libraryOwners[it] == event.seatId }
                is GameEvent.Scry ->
                    (event.topIds + event.bottomIds).forEach { iid ->
                        cardsByInstanceId[iid]?.let { fid ->
                            next[fid] = next[fid].orEmpty() + event.seatId
                        }
                    }
                is GameEvent.Surveil ->
                    event.libraryIds.forEach { iid ->
                        cardsByInstanceId[iid]?.let { fid -> next[fid] = next[fid].orEmpty() + event.seatId }
                    }
                is GameEvent.ZoneChanged -> {
                    if (event.to == Zone.Library) rememberPublicReturn(event.cardId, event.from)
                    if (event.to != Zone.Library) next.remove(event.cardId)
                }
                // The collector specializes BF -> Hand/Library; final library
                // membership distinguishes the library destination from a hand bounce.
                is GameEvent.CardBounced -> rememberPublicReturn(event.cardId, Zone.Battlefield)
                else -> Unit
            }
        }
        return State(next.toMap())
    }
}
