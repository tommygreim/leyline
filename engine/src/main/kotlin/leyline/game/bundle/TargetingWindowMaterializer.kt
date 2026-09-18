package leyline.game.bundle

import leyline.bridge.handoff.TargetingCandidateValue
import leyline.bridge.handoff.TargetingWindowValue
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.state.PendingSubmittedTargets
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.HighlightType
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.PromptParameter
import wotc.mtgo.gre.external.messaging.Messages.ResultCode
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsReq
import wotc.mtgo.gre.external.messaging.Messages.SubmitTargetsResp
import wotc.mtgo.gre.external.messaging.Messages.Target
import wotc.mtgo.gre.external.messaging.Messages.TargetSelection

/** Value-only GRE preparation for coordinator-owned targeting windows. */
internal class TargetingWindowMaterializer(
    private val seatId: Int,
) {
    data class Prepared(
        val bundle: BundleBuilder.BundleResult,
        val transition: ProjectionTransition?,
        val closesPlaybackFrame: Boolean = false,
    )

    fun initial(
        gameState: GameStateMessage,
        gameStateId: Int,
        counter: LogicalSequencePlanner,
        projection: ProjectionState,
        transition: ProjectionTransition,
        window: TargetingWindowValue,
    ): Prepared {
        val req = selectTargetsReq(window, projection, emptySet(), window.candidates.map { it.optionIndex }.toSet())
        return Prepared(
            bundle =
                BundleBuilder.BundleResult(
                    listOf(
                        makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) {
                            it.gameStateMessage = gameState
                        },
                        promptMessage(gameStateId, counter.nextMsgId(), req),
                    ),
                    actionGameStateId = gameStateId,
                ),
            transition = transition,
            closesPlaybackFrame = true,
        )
    }

    fun rePrompt(
        counter: LogicalSequencePlanner,
        projection: ProjectionState,
        window: TargetingWindowValue,
        selectedOptionIndices: Set<Int>,
        legalOptionIndices: Set<Int>,
    ): Prepared {
        val link = counter.nextGameStateLink()
        val echo =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(link.gsId)
                .setPrevGameStateId(link.prevGsId)
                .setUpdate(GameStateUpdate.Send)
                .build()
        val req = selectTargetsReq(window, projection, selectedOptionIndices, legalOptionIndices)
        return Prepared(
            BundleBuilder.BundleResult(
                listOf(
                    makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) {
                        it.gameStateMessage = echo
                    },
                    promptMessage(link.gsId, counter.nextMsgId(), req),
                ),
                actionGameStateId = link.gsId,
            ),
            transition = null,
        )
    }

    fun submit(
        counter: LogicalSequencePlanner,
        prior: ProjectionState,
        sourceInstanceId: InstanceId?,
        casterSeatId: leyline.bridge.types.SeatId,
    ): Prepared {
        val transition =
            sourceInstanceId?.let { sourceId ->
                val editor = prior.editor()
                val viewerSeatId = SeatId(seatId)
                val cursor = editor.viewerCursors[viewerSeatId] ?: leyline.game.state.ViewerProjectionCursor()
                editor.viewerCursors[viewerSeatId] =
                    cursor.copy(
                        pendingSubmittedTargets =
                            PendingSubmittedTargets(
                                sourceId,
                                casterSeatId,
                                version = (cursor.pendingSubmittedTargets?.version ?: 0) + 1,
                            ),
                    )
                ProjectionTransition(prior.revision, editor.freeze())
            }
        val message =
            makeGRE(GREMessageType.SubmitTargetsResp_695e, counter.currentGsId(), counter.nextMsgId()) {
                it.submitTargetsResp = SubmitTargetsResp.newBuilder().setResult(ResultCode.Success_a500).build()
            }
        return Prepared(BundleBuilder.BundleResult(listOf(message)), transition)
    }

    private fun selectTargetsReq(
        window: TargetingWindowValue,
        projection: ProjectionState,
        selectedOptionIndices: Set<Int>,
        legalOptionIndices: Set<Int>,
    ): SelectTargetsReq {
        val sourceInstanceId =
            window.forgeAbilityId
                .takeIf { (window.isTriggeredAbility || window.isActivatedAbility) && it != 0 }
                ?.let(FrameIdResolver::triggerStackAbilityForgeId)
                ?.let(projection.identities.forgeIdToInstanceId::get)
                ?.value
                ?: window.sourceForgeCardId
                    ?.let(projection.identities.forgeIdToInstanceId::get)
                    ?.value
                ?: 0
        val selection =
            TargetSelection
                .newBuilder()
                .setTargetIdx(window.targetIndex)
                .setTargetingPlayer(window.chooserSeatId.value)
                .setMinTargets(window.minTargets)
                .setMaxTargets(window.maxTargets)
                .setSelectedTargets(selectedOptionIndices.size)
        if (window.targetingAbilityGrpId != 0) selection.targetingAbilityGrpId = window.targetingAbilityGrpId
        if (window.targetSourceZoneId != 0) selection.targetSourceZoneId = window.targetSourceZoneId
        if (sourceInstanceId != 0) {
            selection.prompt = targetPrompt(window.targetPromptId ?: PromptIds.SELECT_TARGETS, sourceInstanceId)
        }
        window.candidates.forEach { candidate ->
            val instanceId =
                candidate.instanceId(projection)
                    ?: if (candidate is TargetingCandidateValue.StackObject) {
                        error("Missing projection instance id for stack target option ${candidate.optionIndex}")
                    } else {
                        return@forEach
                    }
            when {
                candidate.optionIndex in selectedOptionIndices ->
                    selection.addTargets(
                        Target.newBuilder().setTargetInstanceId(instanceId).setLegalAction(SelectAction.Unselect),
                    )
                candidate.optionIndex in legalOptionIndices ->
                    selection.addTargets(
                        Target
                            .newBuilder()
                            .setTargetInstanceId(instanceId)
                            .setLegalAction(SelectAction.Select_a1ad)
                            .setHighlight(candidate.highlight(window.chooserSeatId, window.isCounterspell)),
                    )
            }
        }
        val req = SelectTargetsReq.newBuilder().addTargets(selection)
        if (sourceInstanceId != 0) req.sourceId = sourceInstanceId
        if (window.outerAbilityGrpId != 0) req.abilityGrpId = window.outerAbilityGrpId
        return req.build()
    }

    private fun promptMessage(
        gameStateId: Int,
        msgId: Int,
        request: SelectTargetsReq,
    ): GREToClientMessage =
        makeGRE(GREMessageType.SelectTargetsReq_695e, gameStateId, msgId) {
            it.selectTargetsReq = request
            it.prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_TARGETS).build()
            it.allowCancel = AllowCancel.Abort
            it.allowUndo = true
        }

    private fun TargetingCandidateValue.instanceId(projection: ProjectionState): Int? =
        when (this) {
            is TargetingCandidateValue.Card -> projection.identities.forgeIdToInstanceId[forgeCardId]?.value
            is TargetingCandidateValue.Player -> seatId.value
            is TargetingCandidateValue.StackObject ->
                projection.identities.forgeIdToInstanceId[
                    if (isSpell) sourceForgeCardId else FrameIdResolver.triggerStackAbilityForgeId(forgeAbilityId),
                ]?.value
        }

    private fun TargetingCandidateValue.highlight(
        chooserSeatId: leyline.bridge.types.SeatId,
        isCounterspell: Boolean,
    ): HighlightType =
        when (this) {
            // A candidate that already has a Role attached gets its own highlight
            // color — targeting it will replace the existing Role (CR 702.166),
            // and the client renders distinct copy ("Select Cold Role Targets")
            // for that case instead of the ordinary target-confirmation flow.
            is TargetingCandidateValue.Card -> if (hasExistingRole) HighlightType.ReplaceRole else HighlightType.Tepid
            is TargetingCandidateValue.Player -> if (seatId == chooserSeatId) HighlightType.Cold else HighlightType.Hot
            // A stack target for a "counter target spell/ability" effect gets
            // its own highlight color, distinct from an ordinary spell target.
            is TargetingCandidateValue.StackObject -> if (isCounterspell) HighlightType.Counterspell else HighlightType.Tepid
        }

    private fun targetPrompt(
        promptId: Int,
        sourceInstanceId: Int,
    ): Prompt =
        Prompt
            .newBuilder()
            .setPromptId(promptId)
            .addParameters(
                PromptParameter
                    .newBuilder()
                    .setParameterName("CardId")
                    .setType(ParameterType.Number)
                    .setNumberValue(sourceInstanceId),
            ).build()

    private fun makeGRE(
        type: GREMessageType,
        gameStateId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(type)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .addSystemSeatIds(seatId)
            .also(configure)
            .build()
}
