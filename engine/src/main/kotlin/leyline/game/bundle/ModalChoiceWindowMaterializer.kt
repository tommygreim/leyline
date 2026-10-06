package leyline.game.bundle

import leyline.bridge.handoff.ModalChoiceWindowValue
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.PromptParameter
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

/** Value-only GRE preparation for coordinator-owned modal CastingTimeOptionsReq windows. */
internal class ModalChoiceWindowMaterializer(
    private val seatId: Int,
) {
    data class Prepared(
        val materialization: SettledPromptMaterialization,
        val sourceInstanceId: Int,
    )

    fun prepare(
        context: SettledPromptMaterializationContext,
        window: ModalChoiceWindowValue,
    ): Prepared {
        val sourceCardInstanceId = context.requiredInstanceId(window.sourceForgeCardId, "ModalChoice source")
        val sourceInstanceId =
            if (window.triggered) {
                context.requiredInstanceId(
                    FrameIdResolver.triggerStackAbilityForgeId(window.sourceForgeAbilityId),
                    "ModalChoice source",
                )
            } else {
                sourceCardInstanceId
            }
        val req =
            CastingTimeOptionsBuilder.buildModalCastingTimeOptionsReq(
                parentGrpId = window.parentGrpId,
                modalOptions = window.possible.map { option -> CastingTimeOptionsBuilder.ModalOptionSpec(option.grpId, option.cost) },
                excludedOptions = window.excluded.map { option -> CastingTimeOptionsBuilder.ModalOptionSpec(option.grpId, option.cost) },
                minSel = window.min,
                maxSel = window.max,
                allowRepeat = window.allowRepeat,
                sourceInstanceId = sourceInstanceId,
                grpId = window.ctoGrpId,
                ctoId = window.ctoId,
                playerIdToPrompt = context.seatId,
            )
        val state =
            context.gameState
                .toBuilder()
                .setPendingMessageCount(1)
                .let { builder ->
                    if (!window.triggered || builder.gameObjectsList.any { it.instanceId == sourceInstanceId }) {
                        builder
                    } else {
                        val ability =
                            GameObjectInfo
                                .newBuilder()
                                .setInstanceId(sourceInstanceId)
                                .setGrpId(window.ctoGrpId)
                                .setType(GameObjectType.Ability)
                                .setZoneId(ZoneIds.STACK)
                                .setVisibility(Visibility.Public)
                                .setOwnerSeatId(context.seatId)
                                .setControllerSeatId(context.seatId)
                                .setObjectSourceGrpId(window.sourceCardGrpId)
                                .setParentId(sourceCardInstanceId)
                                .build()
                        builder.addGameObjects(ability)
                        addToStack(builder, sourceInstanceId)
                    }
                }.build()
        val messages =
            listOf(
                context.message(GREMessageType.GameStateMessage_695e) { it.gameStateMessage = state },
                context.message(GREMessageType.CastingTimeOptionsReq_695e) {
                    it.castingTimeOptionsReq = req
                    it.prompt =
                        Prompt
                            .newBuilder()
                            .setPromptId(PromptIds.CASTING_TIME_OPTIONS)
                            .apply {
                                if (window.isVote) {
                                    addParameters(
                                        PromptParameter
                                            .newBuilder()
                                            .setParameterName("choiceKind")
                                            .setType(ParameterType.NonLocalizedString)
                                            .setStringValue("vote"),
                                    )
                                }
                            }.build()
                    it.allowCancel = if (window.allowCancel) AllowCancel.Abort else AllowCancel.No_a526
                    it.allowUndo = window.allowCancel
                },
            )
        return Prepared(context.prepared(messages, awaitedRequest = messages.last()), sourceInstanceId)
    }

    fun cleanup(
        counter: LogicalSequencePlanner,
        abilityInstanceId: Int,
    ): GREToClientMessage {
        val link = counter.nextGameStateLink()
        return GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateId(link.gsId)
            .setMsgId(counter.nextMsgId())
            .addSystemSeatIds(seatId)
            .setGameStateMessage(
                GameStateMessage
                    .newBuilder()
                    .setType(wotc.mtgo.gre.external.messaging.Messages.GameStateType.Diff)
                    .setGameStateId(link.gsId)
                    .setPrevGameStateId(link.prevGsId)
                    .setUpdate(GameStateUpdate.Send)
                    .addDiffDeletedInstanceIds(abilityInstanceId)
                    .addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(ZoneIds.STACK)
                            .setType(ZoneType.Stack)
                            .setVisibility(Visibility.Public)
                            .build(),
                    ),
            ).build()
    }

    private fun addToStack(
        builder: GameStateMessage.Builder,
        instanceId: Int,
    ): GameStateMessage.Builder {
        val stackIndex = builder.zonesList.indexOfFirst { it.type == ZoneType.Stack }
        if (stackIndex >= 0) {
            builder.setZones(
                stackIndex,
                builder
                    .getZones(stackIndex)
                    .toBuilder()
                    .addObjectInstanceIds(instanceId)
                    .build(),
            )
        } else {
            builder.addZones(
                ZoneInfo
                    .newBuilder()
                    .setZoneId(ZoneIds.STACK)
                    .setType(ZoneType.Stack)
                    .setVisibility(Visibility.Public)
                    .addObjectInstanceIds(instanceId)
                    .build(),
            )
        }
        return builder
    }
}
