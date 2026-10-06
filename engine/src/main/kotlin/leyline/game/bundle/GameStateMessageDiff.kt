package leyline.game.bundle

import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate

/** Applies keyed durable fields to a retained full-state baseline. */
internal fun GameStateMessage.applyDiff(diff: GameStateMessage): GameStateMessage {
    val players = playersList.associateBy { it.systemSeatNumber }.toMutableMap()
    diff.playersList.forEach { players[it.systemSeatNumber] = it }
    val zones = zonesList.associateBy { it.zoneId }.toMutableMap()
    diff.zonesList.forEach { zones[it.zoneId] = it }
    val deletedIds = diff.diffDeletedInstanceIdsList.toSet()
    if (deletedIds.isNotEmpty()) {
        zones.replaceAll { _, zone ->
            zone
                .toBuilder()
                .clearObjectInstanceIds()
                .addAllObjectInstanceIds(zone.objectInstanceIdsList.filterNot(deletedIds::contains))
                .build()
        }
    }
    val objects = gameObjectsList.associateBy { it.instanceId }.toMutableMap()
    deletedIds.forEach(objects::remove)
    diff.gameObjectsList.forEach { objects[it.instanceId] = it }
    return toBuilder()
        .apply {
            if (diff.hasGameInfo()) gameInfo = diff.gameInfo
            if (diff.hasTurnInfo()) turnInfo = diff.turnInfo
            if (diff.timersCount > 0) clearTimers().addAllTimers(diff.timersList)
        }.clearPlayers()
        .addAllPlayers(players.values)
        .clearZones()
        .addAllZones(zones.values)
        .clearGameObjects()
        .addAllGameObjects(objects.values)
        .setType(GameStateType.Full)
        .setGameStateId(diff.gameStateId)
        .clearPrevGameStateId()
        .clearAnnotations()
        .clearActions()
        .addAllActions(diff.actionsList)
        .clearDiffDeletedInstanceIds()
        .setPendingMessageCount(0)
        .setUpdate(GameStateUpdate.SendAndRecord)
        .build()
}
