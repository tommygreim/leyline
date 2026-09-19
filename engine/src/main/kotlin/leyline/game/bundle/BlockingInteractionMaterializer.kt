package leyline.game.bundle

import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.CommanderReturnPromptContext
import leyline.bridge.handoff.CommanderZone
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.annotations.AnnotationBuilder
import leyline.game.codes.DetailKeys
import leyline.game.data.CardData
import leyline.game.data.CardProtoBuilder
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ObjectMapper
import leyline.game.mapping.PlayerMapper
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.*

/** Value-oriented message/state preparation for coordinator-owned blocking interactions. */
@Suppress("LargeClass") // One protocol materializer intentionally owns all blocking prompt message shapes.
internal class BlockingInteractionMaterializer(
    private val seatId: Int,
) {
    data class Prepared(
        val bundle: BundleBuilder.BundleResult,
        val transition: ProjectionTransition?,
        val closesPlaybackFrame: Boolean = false,
    )

    fun generalOptional(
        prior: ProjectionState,
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.Optional,
    ): Prepared {
        require(interaction.freeCast == null) { "Free-cast interactions require a state snapshot" }
        return edit(prior) { editor ->
            val sourceId =
                interaction.sourceId?.let { editor.identities.getOrAlloc(it).value }
                    ?: error("Optional interaction requires a source")
            val prompt =
                Prompt
                    .newBuilder()
                    .setPromptId(
                        interaction.customPromptId ?: interaction.costText?.let { PromptIds.PAY_COSTS } ?: PromptIds.OPTIONAL_ACTION,
                    ).apply {
                        val cost = interaction.costText
                        if (cost != null) {
                            addParameters(costPromptParameter(cost))
                        } else {
                            addParameters(cardIdPromptParameter(sourceId))
                        }
                    }.build()
            val optional =
                OptionalActionMessage
                    .newBuilder()
                    .setSourceId(sourceId)
                    .setPrompt(prompt)
                    .apply {
                        interaction.mechanicType?.let { addOptionalActionTypes(it) }
                    }.build()
            val link = counter.nextGameStateLink()
            val pending = pendingMessage(link)
            BundleBuilder.BundleResult(
                listOf(
                    makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) { it.gameStateMessage = pending },
                    makeGRE(GREMessageType.OptionalActionMessage_695e, link.gsId, counter.nextMsgId()) {
                        it.optionalActionMessage = optional
                        it.prompt = prompt
                        it.allowCancel = AllowCancel.No_a526
                    },
                ),
                actionGameStateId = link.gsId,
            )
        }
    }

    fun etbPayLifeOptional(
        prior: ProjectionState,
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.Optional,
        sourceForgeId: ForgeCardId,
        cardData: CardData,
        abilityGrpId: Int,
        cardProto: CardProtoBuilder,
    ): Prepared =
        edit(prior) { editor ->
            val oldId = editor.identities.getOrAlloc(sourceForgeId)
            val newId = editor.identities.reserve()
            val replacementId = leyline.bridge.types.EffectId(editor.effects.effects.nextEffectId())
            val persistentId = editor.persistentAnnotations.nextPersistentId
            val replacement =
                AnnotationBuilder
                    .replacementEffect(replacementId, newId, GrpId(abilityGrpId), oldId)
                    .toBuilder()
                    .setId(persistentId)
                    .build()
            editor.persistentAnnotations =
                editor.persistentAnnotations.copy(
                    activeAnnotations = editor.persistentAnnotations.activeAnnotations + (persistentId to replacement),
                    nextPersistentId = persistentId + 1,
                )
            val link = counter.nextGameStateLink()
            val pending =
                pendingMessage(link)
                    .toBuilder()
                    .setUpdate(GameStateUpdate.Send)
                    .addGameObjects(
                        cardProto
                            .buildObjectInfo(cardData.grpId)
                            .setInstanceId(newId.value)
                            .setType(GameObjectType.Card)
                            .setVisibility(Visibility.Public)
                            .setOwnerSeatId(seatId)
                            .setControllerSeatId(seatId),
                    ).addPersistentAnnotations(replacement)
                    .build()
            val prompt =
                Prompt
                    .newBuilder()
                    .setPromptId(checkNotNull(interaction.customPromptId))
                    .addParameters(cardIdPromptParameter(newId.value))
                    .build()
            val optional =
                OptionalActionMessage
                    .newBuilder()
                    .setSourceId(replacementId.value)
                    .setPrompt(prompt)
                    .build()
            BundleBuilder.BundleResult(
                listOf(
                    makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) {
                        it.gameStateMessage = pending
                    },
                    makeGRE(GREMessageType.OptionalActionMessage_695e, link.gsId, counter.nextMsgId()) {
                        it.optionalActionMessage = optional
                        it.prompt = prompt
                        it.allowCancel = AllowCancel.No_a526
                    },
                ),
                actionGameStateId = link.gsId,
            )
        }

    fun snapshotOptional(
        stateMessages: List<GREToClientMessage>,
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.Optional,
        transition: ProjectionTransition,
    ): Prepared {
        val sourceId =
            interaction.sourceId?.let {
                transition.nextState.identities.forgeIdToInstanceId[it]
                    ?.value
            }
                ?: error("Optional interaction requires a source")
        val link = counter.nextGameStateLink()
        interaction.freeCast?.let { freeCast ->
            val abilityInstanceId =
                transition.nextState.identities.forgeIdToInstanceId[
                    FrameIdResolver.triggerStackAbilityForgeId(freeCast.sourceAbilityForgeId),
                ]?.value ?: error("Free-cast source ability has no projected identity")
            val alternativeSourceZcid =
                transition.nextState.identities.forgeIdToInstanceId[freeCast.alternativeSourceForgeCardId]
                    ?.value
                    ?: error("Free-cast alternative source has no projected identity")
            val cast =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Cast)
                    .setGrpId(freeCast.cardGrpId)
                    .setInstanceId(sourceId)
                    .setAbilityGrpId(freeCast.abilityGrpId)
                    .setSourceId(abilityInstanceId)
                    .setAlternativeGrpId(149)
                    .setAlternativeSourceZcid(alternativeSourceZcid)
                    .build()
            val actions = ActionsAvailableReq.newBuilder().addActions(cast).addActions(Action.newBuilder().setActionType(ActionType.Pass))
            if (freeCast.abilityGrpId != KeywordAbilityIds.CASCADE) {
                addDiscoverInactiveActions(
                    actions,
                    stateMessages,
                    abilityInstanceId,
                    alternativeSourceZcid,
                    freeCast,
                    sourceId,
                )
            }
            val prompt =
                Prompt
                    .newBuilder()
                    .setPromptId(PromptIds.FREE_CAST_FROM_REVEAL)
                    .addParameters(cardIdPromptParameter(alternativeSourceZcid))
            return Prepared(
                BundleBuilder.BundleResult(
                    stateMessages +
                        listOf(
                            makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) {
                                it.gameStateMessage = pendingMessage(link)
                            },
                            makeGRE(GREMessageType.ActionsAvailableReq_695e, link.gsId, counter.nextMsgId()) {
                                it.actionsAvailableReq = actions.build()
                                it.prompt = prompt.build()
                                it.allowCancel = AllowCancel.No_a526
                            },
                        ),
                    actionGameStateId = link.gsId,
                ),
                transition,
                closesPlaybackFrame = true,
            )
        }
        val prompt =
            Prompt
                .newBuilder()
                .setPromptId(interaction.customPromptId ?: PromptIds.OPTIONAL_ACTION)
                .addParameters(cardIdPromptParameter(sourceId))
                .build()
        val optional =
            OptionalActionMessage
                .newBuilder()
                .setSourceId(sourceId)
                .setPrompt(prompt)
                .build()
        return Prepared(
            BundleBuilder.BundleResult(
                stateMessages +
                    listOf(
                        makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) {
                            it.gameStateMessage = pendingMessage(link)
                        },
                        makeGRE(GREMessageType.OptionalActionMessage_695e, link.gsId, counter.nextMsgId()) {
                            it.optionalActionMessage = optional
                            it.prompt = prompt
                            it.allowCancel = AllowCancel.No_a526
                        },
                    ),
                actionGameStateId = link.gsId,
            ),
            transition,
            closesPlaybackFrame = true,
        )
    }

    private fun addDiscoverInactiveActions(
        actions: ActionsAvailableReq.Builder,
        stateMessages: List<GREToClientMessage>,
        abilityInstanceId: Int,
        alternativeSourceZcid: Int,
        freeCast: BlockingInteraction.FreeCast,
        castableCardId: Int,
    ) {
        val objects =
            stateMessages
                .asSequence()
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.gameObjectsList.asSequence() }
                .associateBy { it.instanceId }
        stateMessages
            .asSequence()
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.annotationsList.asSequence() }
            .filter { annotation ->
                AnnotationType.ZoneTransfer_af5a in annotation.typeList &&
                    (
                        annotation.affectorId == abilityInstanceId ||
                            annotation.affectorId == alternativeSourceZcid
                    ) &&
                    annotation.detailsList.any { detail ->
                        detail.key == DetailKeys.CATEGORY && detail.valueStringList == listOf("Exile")
                    }
            }.flatMap { it.affectedIdsList.asSequence() }
            .filter { it != castableCardId }
            .mapNotNull(objects::get)
            .forEach { card ->
                actions.addInactiveActions(
                    Action
                        .newBuilder()
                        .setActionType(if (CardType.Land_a80b in card.cardTypesList) ActionType.Play_add3 else ActionType.Cast)
                        .setGrpId(card.grpId)
                        .setInstanceId(card.instanceId)
                        .setAbilityGrpId(freeCast.abilityGrpId)
                        .setSourceId(abilityInstanceId),
                )
            }
    }

    fun commanderOptional(
        stateMessages: List<GREToClientMessage>,
        snapshot: GsmSnapshot,
        actions: ActionsAvailableReq,
        link: LogicalSequencePlanner.GameStateLink,
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.Optional,
        context: CommanderReturnPromptContext,
        editor: ProjectionState.Editor,
        cardProto: CardProtoBuilder,
    ): BundleBuilder.BundleResult {
        val pending =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(link.gsId)
                .setPrevGameStateId(link.prevGsId)
                .setPendingMessageCount(1)
                .setTurnInfo(GsmFrame.from(snapshot).turnInfo())
                .addAllTimers(PlayerMapper.buildTimers())
                .setUpdate(GameStateUpdate.Send)
        addCommanderContext(pending, snapshot, interaction.sourceId, context, editor, cardProto)
        actions.actionsList.forEach { action ->
            pending.addActions(
                ActionInfo.newBuilder().setSeatId(seatId).setAction(ActionMapper.stripActionForGsm(action)),
            )
        }
        val prompt =
            Prompt
                .newBuilder()
                .setPromptId(interaction.customPromptId ?: PromptIds.OPTIONAL_ACTION)
                .addParameters(cardIdPromptParameter(0))
                .addParameters(cardIdPromptParameter(context.promptInstanceId))
                .build()
        val optional =
            OptionalActionMessage
                .newBuilder()
                .setSourceId(context.promptInstanceId)
                .addOptionalActionTypes(CardMechanicType.ZoneTransfer_a57f)
                .addRecipientIds(context.promptInstanceId)
                .setPrompt(prompt)
                .build()
        editor.limboInstanceIds += context.promptInstanceId
        return BundleBuilder.BundleResult(
            stateMessages +
                listOf(
                    makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) {
                        it.gameStateMessage = pending.build()
                    },
                    makeGRE(GREMessageType.OptionalActionMessage_695e, link.gsId, counter.nextMsgId()) {
                        it.optionalActionMessage = optional
                        it.prompt = prompt
                        it.allowCancel = AllowCancel.No_a526
                    },
                ),
            actionGameStateId = link.gsId,
        )
    }

    fun commanderCleanup(
        prior: ProjectionState,
        snapshot: GsmSnapshot,
        link: LogicalSequencePlanner.GameStateLink,
        counter: LogicalSequencePlanner,
        context: CommanderReturnPromptContext,
    ): Prepared =
        edit(prior) { editor ->
            editor.limboInstanceIds -= context.promptInstanceId
            val destinationZoneId = protocolZoneId(context.destinationZone, context.ownerSeatId)
            val destinationZone = snapshot.zones[destinationZoneId]
            val zoneInfo =
                ZoneInfo
                    .newBuilder()
                    .setZoneId(destinationZoneId)
                    .setType(destinationZone?.type ?: protocolZoneType(destinationZoneId))
                    .setVisibility(destinationZone?.visibility ?: Visibility.Public)
                    .apply { destinationZone?.owner?.let { setOwnerSeatId(it.value) } }
                    .addAllObjectInstanceIds(destinationZone?.contents?.map { editor.identities.getOrAlloc(it).value }.orEmpty())
                    .build()
            val cleanup =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(link.gsId)
                    .setPrevGameStateId(link.prevGsId)
                    .setUpdate(GameStateUpdate.Send)
                    .addDiffDeletedInstanceIds(context.promptInstanceId)
                    .addZones(zoneInfo)
                    .build()
            BundleBuilder.BundleResult(
                listOf(makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) { it.gameStateMessage = cleanup }),
                actionGameStateId = link.gsId,
            )
        }

    fun numeric(
        prior: ProjectionState,
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.Numeric,
    ): Prepared =
        edit(prior) { editor ->
            val sourceId =
                interaction.sourceId?.let { editor.identities.getOrAlloc(it).value }
                    ?: error("Numeric interaction requires a source")
            val numericReq =
                NumericInputReq
                    .newBuilder()
                    .setMaxValue(interaction.max)
                    .setStepSize(1)
                    .setSourceId(sourceId)
                    .setNumericInputType(NumericInputType.ChooseX_ad80)
                    .also { if (interaction.min > 0) it.minValue = interaction.min }
                    .build()
            val prompt =
                Prompt
                    .newBuilder()
                    .setPromptId(PromptIds.NUMERIC_INPUT)
                    .addParameters(cardIdPromptParameter(sourceId))
                    .build()
            val link = counter.nextGameStateLink()
            val requestMessage =
                if (interaction.presentation == BlockingInteraction.NumericPresentation.Replicate) {
                    val replicate =
                        CastingTimeOptionReq
                            .newBuilder()
                            .setCtoId(REPLICATE_CTO_ID)
                            .setCastingTimeOptionType(CastingTimeOptionType.Replicate)
                            .setAffectedId(sourceId)
                            .setAffectorId(sourceId)
                            .setPlayerIdToPrompt(seatId)
                            .setIsRequired(true)
                            .setNumericInputReq(numericReq)
                            .build()
                    makeGRE(GREMessageType.CastingTimeOptionsReq_695e, link.gsId, counter.nextMsgId()) {
                        it.castingTimeOptionsReq = CastingTimeOptionsReq.newBuilder().addCastingTimeOptionReq(replicate).build()
                        it.prompt = Prompt.newBuilder().setPromptId(PromptIds.CASTING_TIME_OPTIONS).build()
                        it.allowCancel = AllowCancel.Abort
                        it.allowUndo = true
                    }
                } else {
                    makeGRE(GREMessageType.NumericInputReq_695e, link.gsId, counter.nextMsgId()) {
                        it.numericInputReq = numericReq
                        it.prompt = prompt
                        it.allowCancel = AllowCancel.No_a526
                    }
                }
            BundleBuilder.BundleResult(
                listOf(
                    makeGRE(GREMessageType.GameStateMessage_695e, link.gsId, counter.nextMsgId()) {
                        it.gameStateMessage = pendingMessage(link)
                    },
                    requestMessage,
                ),
                actionGameStateId = link.gsId,
            )
        }

    private companion object {
        const val REPLICATE_CTO_ID = 1
    }

    fun damage(
        prior: ProjectionState,
        counter: LogicalSequencePlanner,
        interaction: BlockingInteraction.Damage,
        blockerToughness: Map<ForgeCardId, Int>,
    ): Prepared =
        edit(prior) { editor ->
            val attackerId = editor.identities.getOrAlloc(interaction.attackerId).value
            val assignments = mutableListOf<DamageAssignment>()
            var remaining = interaction.damageDealt.coerceAtLeast(0)
            for (blockerId in interaction.blockerIds) {
                val lethal = if (interaction.hasDeathtouch) 1 else maxOf(0, blockerToughness[blockerId] ?: 0)
                val assigned = lethal.coerceAtMost(remaining)
                remaining -= assigned
                assignments +=
                    DamageAssignment
                        .newBuilder()
                        .setInstanceId(editor.identities.getOrAlloc(blockerId).value)
                        .setMinDamage(lethal)
                        .setAssignedDamage(assigned)
                        .build()
            }
            if (interaction.hasTrample && interaction.hasDefender && remaining > 0) {
                assignments +=
                    DamageAssignment
                        .newBuilder()
                        .setInstanceId(SeatId(seatId).opponent.value)
                        .setMaxDamage(remaining)
                        .setAssignedDamage(remaining)
                        .build()
            }
            val assigner =
                DamageAssigner
                    .newBuilder()
                    .setInstanceId(attackerId)
                    .setTotalDamage(interaction.damageDealt)
                    .addAllAssignments(assignments)
                    .setCanIgnoreBlockers(interaction.hasTrample)
                    .setDecisionPrompt(
                        Prompt.newBuilder().setPromptId(PromptIds.ASSIGN_DAMAGE).addParameters(cardIdPromptParameter(attackerId)),
                    ).build()
            BundleBuilder.BundleResult(
                listOf(
                    makeGRE(GREMessageType.AssignDamageReq_695e, counter.currentGsId(), counter.nextMsgId()) {
                        it.assignDamageReq = AssignDamageReq.newBuilder().addDamageAssigners(assigner).build()
                    },
                ),
                actionGameStateId = counter.currentGsId(),
            )
        }

    fun damageConfirmation(counter: LogicalSequencePlanner): BundleBuilder.BundleResult =
        BundleBuilder.BundleResult(
            listOf(
                makeGRE(GREMessageType.AssignDamageConfirmation_695e, counter.currentGsId(), counter.nextMsgId()) {
                    it.assignDamageConfirmation = AssignDamageConfirmation.newBuilder().setResult(ResultCode.Success_a500).build()
                },
            ),
        )

    private fun edit(
        prior: ProjectionState,
        block: (ProjectionState.Editor) -> BundleBuilder.BundleResult,
    ): Prepared {
        val editor = prior.editor()
        val bundle = block(editor)
        val next = editor.freeze()
        val changed = next.copy(revision = prior.revision) != prior
        return Prepared(bundle, if (changed) ProjectionTransition(prior.revision, next) else null)
    }

    private fun addCommanderContext(
        builder: GameStateMessage.Builder,
        snapshot: GsmSnapshot,
        sourceId: ForgeCardId?,
        context: CommanderReturnPromptContext,
        editor: ProjectionState.Editor,
        cardProto: CardProtoBuilder,
    ) {
        val cardId = sourceId ?: error("Commander optional interaction requires a source")
        val bound = snapshot.boundCards[cardId] ?: error("Commander source is absent from the interaction snapshot")
        val originZoneId = protocolZoneId(context.originZone, context.ownerSeatId)
        val destinationZoneId = protocolZoneId(context.destinationZone, context.ownerSeatId)

        fun zoneWithContents(
            zoneId: Int,
            extraIds: List<Int> = emptyList(),
            dropId: Int? = null,
        ): ZoneInfo {
            val zone = snapshot.zones[zoneId]
            val contents =
                zone
                    ?.contents
                    ?.map { editor.identities.getOrAlloc(it).value }
                    ?.filter { it != dropId }
                    .orEmpty() + extraIds
            return ZoneInfo
                .newBuilder()
                .setZoneId(zoneId)
                .setType(zone?.type ?: protocolZoneType(zoneId))
                .setVisibility(zone?.visibility ?: Visibility.Public)
                .apply { zone?.owner?.let { setOwnerSeatId(it.value) } }
                .addAllObjectInstanceIds(contents.distinct())
                .build()
        }
        builder
            .addZones(zoneWithContents(originZoneId, dropId = context.oldInstanceId))
            .addZones(zoneWithContents(destinationZoneId, extraIds = listOf(context.promptInstanceId)))
            .addGameObjects(
                ObjectMapper.buildFromSnapshot(
                    bound.snapshot,
                    context.promptInstanceId,
                    destinationZoneId,
                    bound.snapshot.owner.value,
                    cardProto,
                    Visibility.Public,
                    parentLinkage = bound.parentLinkage,
                ),
            ).addAnnotations(
                AnnotationBuilder
                    .objectIdChanged(InstanceId(context.oldInstanceId), InstanceId(context.promptInstanceId))
                    .toBuilder()
                    .setId(nextAnnotationId(editor))
                    .build(),
            ).addAnnotations(
                AnnotationBuilder
                    .zoneTransfer(
                        InstanceId(context.promptInstanceId),
                        originZoneId,
                        destinationZoneId,
                        context.transferCategory,
                    ).toBuilder()
                    .setId(nextAnnotationId(editor))
                    .build(),
            )
    }

    private fun nextAnnotationId(editor: ProjectionState.Editor): Int {
        val id = editor.persistentAnnotations.nextAnnotationId
        editor.persistentAnnotations = editor.persistentAnnotations.copy(nextAnnotationId = id + 1)
        return id
    }

    private fun pendingMessage(link: LogicalSequencePlanner.GameStateLink): GameStateMessage =
        GameStateMessage
            .newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(link.gsId)
            .setPrevGameStateId(link.prevGsId)
            .setPendingMessageCount(1)
            .setUpdate(GameStateUpdate.SendAndRecord)
            .build()

    private fun costPromptParameter(costText: String): PromptParameter =
        PromptParameter
            .newBuilder()
            .setParameterName("Cost")
            .setType(ParameterType.NonLocalizedString)
            .setStringValue(costText)
            .build()

    private fun cardIdPromptParameter(value: Int): PromptParameter =
        PromptParameter
            .newBuilder()
            .setParameterName("CardId")
            .setType(ParameterType.Number)
            .setNumberValue(value)
            .build()

    private fun protocolZoneType(zoneId: Int): ZoneType =
        when (zoneId) {
            ZoneIds.BATTLEFIELD -> ZoneType.Battlefield
            ZoneIds.EXILE -> ZoneType.Exile
            ZoneIds.COMMAND -> ZoneType.Command
            ZoneIds.P1_HAND, ZoneIds.P2_HAND -> ZoneType.Hand
            ZoneIds.P1_LIBRARY, ZoneIds.P2_LIBRARY -> ZoneType.Library
            ZoneIds.P1_GRAVEYARD, ZoneIds.P2_GRAVEYARD -> ZoneType.Graveyard
            else -> ZoneType.Limbo
        }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun protocolZoneId(
        zone: CommanderZone,
        ownerSeatId: Int,
    ): Int =
        when (zone) {
            CommanderZone.Battlefield -> ZoneIds.BATTLEFIELD
            CommanderZone.Graveyard -> ZoneIds.graveyardOf(ownerSeatId)
            CommanderZone.Exile -> ZoneIds.EXILE
            CommanderZone.Hand -> ZoneIds.handOf(ownerSeatId)
            CommanderZone.Library -> ZoneIds.libraryOf(ownerSeatId)
            CommanderZone.Command -> ZoneIds.COMMAND
            CommanderZone.Limbo -> ZoneIds.LIMBO
        }

    private fun makeGRE(
        type: GREMessageType,
        gsId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(type)
            .setMsgId(msgId)
            .setGameStateId(gsId)
            .addSystemSeatIds(seatId)
            .also(configure)
            .build()
}
