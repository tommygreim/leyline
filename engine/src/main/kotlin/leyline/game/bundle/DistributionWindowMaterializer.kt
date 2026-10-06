package leyline.game.bundle

import leyline.bridge.handoff.DistributionRouteKind
import leyline.bridge.handoff.DistributionTargetRef
import leyline.bridge.handoff.DistributionWindowValue
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.DistributionReq
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/** Value-only GRE preparation for coordinator-owned divided allocations. */
internal class DistributionWindowMaterializer {
    fun prepare(
        context: SettledPromptMaterializationContext,
        window: DistributionWindowValue,
    ): SettledPromptMaterialization {
        val sourceId =
            context.requiredInstanceId(
                if (window.sourceIsSpell) {
                    leyline.bridge.types.ForgeCardId(window.sourceForgeCardId)
                } else {
                    FrameIdResolver.triggerStackAbilityForgeId(window.sourceForgeAbilityId)
                },
                "Distribution object",
            )
        val targetIds =
            window.targets.map { target ->
                when (target) {
                    is DistributionTargetRef.Card -> context.requiredInstanceId(target.id, "Distribution object")
                    is DistributionTargetRef.Player -> target.id.value
                }
            }
        require(targetIds.distinct().size == targetIds.size) { "Distribution targets have colliding wire ids" }
        val request =
            DistributionReq
                .newBuilder()
                .setMinAmount(window.amount)
                .setMaxAmount(window.amount)
                .setMinPerTarget(window.minPerTarget)
                .addAllTargetIds(targetIds)
                .addAllValidSelectedTargetIds(targetIds)
                .setSourceId(sourceId)
                .build()
        val state =
            context.gameState
                .toBuilder()
                .setPendingMessageCount(1)
                .build()
        val promptId =
            when (window.kind) {
                DistributionRouteKind.Damage -> PromptIds.DISTRIBUTE_DAMAGE
                DistributionRouteKind.Counters -> PromptIds.DISTRIBUTE_COUNTERS
            }
        val messages =
            listOf(
                context.message(GREMessageType.GameStateMessage_695e) { it.gameStateMessage = state },
                context.message(GREMessageType.DistributionReq_695e) {
                    it.distributionReq = request
                    it.prompt =
                        promptWithCardId(
                            promptId,
                            context.requiredInstanceId(
                                leyline.bridge.types.ForgeCardId(window.sourceForgeCardId),
                                "Distribution object",
                            ),
                        )
                    it.allowCancel = AllowCancel.Abort
                },
            )
        return context.prepared(messages, awaitedRequest = messages.last())
    }
}
