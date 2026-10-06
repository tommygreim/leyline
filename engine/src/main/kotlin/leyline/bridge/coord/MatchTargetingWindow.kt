package leyline.bridge.coord

import forge.game.GameEntity
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PublishedTargetingInteraction
import leyline.bridge.handoff.TargetToggleValue
import leyline.bridge.handoff.TargetingCommandReceipt
import leyline.bridge.handoff.TargetingWindowValue
import leyline.bridge.types.InstanceId
import java.util.concurrent.CompletableFuture

internal sealed interface TargetingCommand {
    val reply: CompletableFuture<TargetingCommandReceipt>

    data class Toggle(
        val interactionId: String,
        val gameStateId: Int,
        val targetIndex: Int,
        val toggles: List<TargetToggleValue>,
        override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
    ) : TargetingCommand

    data class Submit(
        val interactionId: String,
        val gameStateId: Int,
        override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
    ) : TargetingCommand

    data class Cancel(
        val interactionId: String,
        val gameStateId: Int,
        override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
    ) : TargetingCommand

    data class Undo(
        val interactionId: String,
        val gameStateId: Int,
        override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
    ) : TargetingCommand

    data class Terminal(
        val cause: Throwable,
        override val reply: CompletableFuture<TargetingCommandReceipt> = CompletableFuture(),
    ) : TargetingCommand
}

internal data class TargetingWindow(
    val interactionId: String,
    val value: TargetingWindowValue,
    val targetingAbility: SpellAbility?,
    val entitiesByOptionIndex: Map<Int, GameEntity>,
    val stackAbilitiesByOptionIndex: Map<Int, SpellAbility>,
    val instanceIdByOptionIndex: Map<Int, Int>,
    val sourceInstanceId: InstanceId?,
    val exchange: InteractiveCommandExchange<TargetingCommand, TargetingCommandReceipt>,
    val selectedOptionIndices: MutableList<Int> = mutableListOf(),
    var published: PublishedTargetingInteraction,
)
