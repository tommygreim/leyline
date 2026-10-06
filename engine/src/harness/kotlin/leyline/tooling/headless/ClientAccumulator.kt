package leyline.tooling.headless

import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Simulates a reference client's state accumulator.
 *
 * Processes GREToClientMessage stream. Full replaces; Diff merges.
 * Tracks objects, zones, persistent annotations, actions, and turnInfo -- enough to assert session-level
 * invariants like "every action instanceId exists in known objects".
 *
 * Test-only utility -- not part of production code.
 */
class ClientAccumulator {
    /** instanceId -> GameObjectInfo (latest version). Full replaces all; Diff adds/updates. */
    val objects = mutableMapOf<Int, GameObjectInfo>()

    /** zoneId -> ZoneInfo (latest version). */
    val zones = mutableMapOf<Int, ZoneInfo>()

    /** systemSeatNumber -> PlayerInfo (latest version). Full replaces all; Diff merges per seat. */
    val players = mutableMapOf<Int, PlayerInfo>()

    /** Persistent annotation id -> latest row. Unchanged Diff frames retain these rows. */
    val persistentAnnotations = mutableMapOf<Int, AnnotationInfo>()

    /** Latest turnInfo from most recent GameStateMessage. */
    var turnInfo: TurnInfo? = null
        private set

    /** Latest ActionsAvailableReq received. */
    var actions: ActionsAvailableReq? = null
        private set

    /** High-water mark of gsId seen. */
    var latestGsId: Int = 0
        private set

    /** Total messages processed. */
    var messageCount: Int = 0
        private set

    /** All gsIds seen in order (for diagnostics). */
    val gsIdHistory = mutableListOf<Int>()

    /** Process a single GRE message. */
    fun process(gre: GREToClientMessage) {
        messageCount++

        if (gre.hasPrompt()) {
            actions = null
        }
        when {
            gre.hasGameStateMessage() -> processGameState(gre.gameStateMessage)
            gre.hasActionsAvailableReq() -> actions = gre.actionsAvailableReq
        }
    }

    /** Process a list of GRE messages (one bundle). */
    fun processAll(messages: List<GREToClientMessage>) {
        messages.forEach { process(it) }
    }

    /** Seed the accumulator with a Full GSM (simulates handshake baseline). */
    fun seedFull(gsm: GameStateMessage) {
        require(gsm.type == GameStateType.Full) { "seedFull requires Full GSM, got ${gsm.type}" }
        processGameState(gsm)
    }

    // --- Invariant checks ---

    /**
     * Every instanceId in current ActionsAvailableReq must exist in [objects].
     * Returns list of missing instanceIds (empty = invariant holds).
     */
    fun actionInstanceIdsMissingFromObjects(): List<Int> {
        val req = actions ?: return emptyList()
        val missing = mutableListOf<Int>()
        for (action in req.actionsList) {
            if (action.instanceId != 0 && !isKnownEntity(action.instanceId)) {
                missing.add(action.instanceId)
            }
        }
        return missing
    }

    private fun isKnownEntity(id: Int): Boolean =
        id in 1..2 ||
            objects.containsKey(id) ||
            zones.containsKey(id) ||
            zones.values.any { zone -> id in zone.objectInstanceIdsList }

    /**
     * Every instanceId referenced by a **visible** zone must exist in [objects].
     * Returns list of (zoneId, instanceId) pairs where object is missing.
     *
     * Hidden zones (Library) and Private zones (opponent Hand, Sideboard)
     * intentionally carry objectInstanceIds without matching GameObjectInfo —
     * the client does the same. The client uses zone counts for UI
     * (e.g. "52 cards in library") but never renders hidden card details.
     */

    /**
     * Throw with a clear message if either invariant is violated. Use in
     * tests at checkpoints to catch accumulator drift early: an orphan
     * action reference or a zone pointing at a missing object usually
     * means an emission path leaked or retired the wrong iid. Context
     * string goes into the failure message.
     */
    fun assertConsistent(context: String = "") {
        val orphanActions = actionInstanceIdsMissingFromObjects()
        val orphanZones = zoneObjectsMissingFromObjects()
        if (orphanActions.isEmpty() && orphanZones.isEmpty()) return
        val ctx = if (context.isBlank()) "" else " ($context)"
        error(
            buildString {
                append("ClientAccumulator inconsistent$ctx:")
                if (orphanActions.isNotEmpty()) {
                    append(" action references missing from objects: $orphanActions;")
                }
                if (orphanZones.isNotEmpty()) {
                    append(" zone→object references missing: $orphanZones;")
                }
            },
        )
    }

    fun zoneObjectsMissingFromObjects(): List<Pair<Int, Int>> {
        val missing = mutableListOf<Pair<Int, Int>>()
        for ((zoneId, zone) in zones) {
            // Skip hidden/private zones — client sends objectInstanceIds
            // without GameObjectInfo for these (library, opponent hand, sideboard).
            // Also skip Limbo — it's a protocol bookkeeping zone, not rendered.
            if (zone.visibility == Visibility.Hidden || zone.visibility == Visibility.Private) continue
            if (zone.type == ZoneType.Limbo) continue
            for (iid in zone.objectInstanceIdsList) {
                if (!objects.containsKey(iid)) {
                    missing.add(zoneId to iid)
                }
            }
        }
        return missing
    }

    // --- Internal ---

    // GameStateType has variants (None_*, etc.) we intentionally skip.
    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun processGameState(gs: GameStateMessage) {
        val gsId = gs.gameStateId
        gsIdHistory.add(gsId)
        if (gsId > latestGsId) latestGsId = gsId

        if (gs.hasTurnInfo()) turnInfo = gs.turnInfo

        when (gs.type) {
            GameStateType.Full -> {
                objects.clear()
                zones.clear()
                players.clear()
                persistentAnnotations.clear()
                gs.gameObjectsList.forEach { objects[it.instanceId] = it }
                gs.zonesList.forEach { zones[it.zoneId] = it }
                gs.playersList.forEach { players[it.systemSeatNumber] = it }
                gs.persistentAnnotationsList.forEach { persistentAnnotations[it.id] = it }
            }
            GameStateType.Diff -> {
                // Remove deleted instances first (client sends these for retired IDs)
                gs.diffDeletedInstanceIdsList.forEach { objects.remove(it) }
                gs.gameObjectsList.forEach { objects[it.instanceId] = it }
                gs.zonesList.forEach { zones[it.zoneId] = it }
                gs.playersList.forEach { players[it.systemSeatNumber] = it }
                gs.diffDeletedPersistentAnnotationIdsList.forEach(persistentAnnotations::remove)
                gs.persistentAnnotationsList.forEach { persistentAnnotations[it.id] = it }
            }
            else -> {} // ignore
        }
    }
}
