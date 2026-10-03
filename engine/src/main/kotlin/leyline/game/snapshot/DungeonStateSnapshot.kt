package leyline.game.snapshot

/**
 * The protocol-side dungeon state for one player. Forge keeps the live dungeon
 * card in the command zone, while Arena keeps this compact state on the player
 * and reconstructs it on each update from a persistent DungeonStatus annotation.
 */
data class DungeonStateSnapshot(
    val currentDungeonGrpId: Int = 0,
    val currentDungeonInstanceId: Int = 0,
    val currentRoomGrpId: Int = 0,
    val completedDungeonGrpIds: List<Int> = emptyList(),
) {
    val isActive: Boolean
        get() = currentDungeonGrpId != 0 || currentDungeonInstanceId != 0 || currentRoomGrpId != 0
}
