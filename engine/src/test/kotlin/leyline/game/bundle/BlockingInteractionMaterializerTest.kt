package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import leyline.game.state.ViewerProjectionCursor
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionInfo
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.CardMechanicType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo

class BlockingInteractionMaterializerTest :
    FunSpec({
        tags(UnitTag)

        test("resolution cast browser binds every choice to the resolving source and offers decline") {
            val source = ForgeCardId(41)
            val candidates = listOf(ForgeCardId(42), ForgeCardId(43))
            val state =
                GameStateMessage
                    .newBuilder()
                    .apply {
                        (41..43).forEach { addGameObjects(GameObjectInfo.newBuilder().setInstanceId(it + 100).setGrpId(it + 1000)) }
                    }.build()
            val prior =
                ProjectionState
                    .initial()
                    .editor()
                    .apply {
                        (41..43).forEach { identities.bind(ForgeCardId(it), InstanceId(it + 100)) }
                        viewerCursors[SeatId(1)] = ViewerProjectionCursor(fullState = state)
                    }.freeze()
            val interaction = BlockingInteraction.ResolutionCast(source, null, candidates, true, true, PromptIds.RESOLUTION_CAST_ANY_FREE)
            val materializer = BlockingInteractionMaterializer(1)
            val prepared =
                materializer.resolutionCast(
                    emptyList(),
                    LogicalSequencePlanner(),
                    interaction,
                    ProjectionTransition(prior.revision, prior),
                )
            val request = prepared.bundle.messages.last()
            assertSoftly {
                request.prompt.promptId shouldBe PromptIds.RESOLUTION_CAST_ANY_FREE
                request.actionsAvailableReq.actionsList.map { it.actionType } shouldBe
                    listOf(ActionType.Cast, ActionType.Cast, ActionType.Pass)
                request.actionsAvailableReq.actionsList
                    .take(2)
                    .map { it.sourceId } shouldBe listOf(141, 141)
                request.actionsAvailableReq.actionsList
                    .take(2)
                    .map { it.instanceId } shouldBe listOf(142, 143)
                prepared.bundle.messages
                    .first()
                    .gameStateMessage.annotationsList
                    .single()
                    .affectorId shouldBe 141
            }
            val next = checkNotNull(prepared.transition).nextState
            val reopened =
                materializer.resolutionCast(
                    emptyList(),
                    LogicalSequencePlanner(),
                    interaction.copy(candidateIds = candidates.takeLast(1)),
                    ProjectionTransition(next.revision, next),
                )
            reopened.bundle.messages
                .first()
                .gameStateMessage.annotationsCount shouldBe 0
            reopened.bundle.messages
                .last()
                .actionsAvailableReq.actionsList
                .filter { it.actionType == ActionType.Cast }
                .map { it.instanceId } shouldBe
                listOf(143)
        }

        test("resolution cast trigger source uses its ability identity and mandatory choice has no decline") {
            val source = ForgeCardId(41)
            val trigger =
                leyline.game.mapping.FrameIdResolver
                    .triggerStackAbilityForgeId(99)
            val state =
                GameStateMessage
                    .newBuilder()
                    .addGameObjects(GameObjectInfo.newBuilder().setInstanceId(141).setGrpId(1041))
                    .addGameObjects(GameObjectInfo.newBuilder().setInstanceId(142).setGrpId(1042))
                    .addGameObjects(GameObjectInfo.newBuilder().setInstanceId(199).setGrpId(1099))
                    .build()
            val prior =
                ProjectionState
                    .initial()
                    .editor()
                    .apply {
                        identities.bind(source, InstanceId(141))
                        identities.bind(ForgeCardId(42), InstanceId(142))
                        identities.bind(trigger, InstanceId(199))
                        viewerCursors[SeatId(1)] = ViewerProjectionCursor(fullState = state, resolvingInstanceId = 199)
                    }.freeze()
            val prepared =
                BlockingInteractionMaterializer(1).resolutionCast(
                    emptyList(),
                    LogicalSequencePlanner(),
                    BlockingInteraction.ResolutionCast(source, 99, listOf(ForgeCardId(42)), false, false, PromptIds.RESOLUTION_CAST_PAID),
                    ProjectionTransition(prior.revision, prior),
                )
            assertSoftly {
                prepared.bundle.messages
                    .first()
                    .gameStateMessage.annotationsCount shouldBe 0
                prepared.bundle.messages
                    .last()
                    .actionsAvailableReq.actionsList
                    .single()
                    .sourceId shouldBe 199
                prepared.bundle.messages
                    .last()
                    .actionsAvailableReq.actionsList
                    .single()
                    .alternativeGrpId shouldBe 0
            }
        }

        test("caps blocker damage at the attacker's available damage") {
            val prepared =
                BlockingInteractionMaterializer(seatId = 1).damage(
                    prior = ProjectionState.initial(),
                    counter = LogicalSequencePlanner(),
                    interaction =
                        BlockingInteraction.Damage.of(
                            attackerId = ForgeCardId(1),
                            blockerIds = listOf(ForgeCardId(2)),
                            damageDealt = 4,
                            hasDeathtouch = false,
                            hasTrample = true,
                            hasDefender = true,
                        ),
                    blockerToughness = mapOf(ForgeCardId(2) to 5),
                )

            val assigner =
                prepared.bundle.messages
                    .single()
                    .assignDamageReq.damageAssignersList
                    .single()
            assertSoftly {
                assigner.totalDamage shouldBe 4
                assigner.assignmentsList.single().minDamage shouldBe 5
                assigner.assignmentsList.single().assignedDamage shouldBe 4
                assigner.canIgnoreBlockers shouldBe false
            }
        }

        test("optional interactions carry the same semantic prompt inside and outside the payload") {
            val prepared =
                BlockingInteractionMaterializer(seatId = 1).generalOptional(
                    prior = ProjectionState.initial(),
                    counter = LogicalSequencePlanner(),
                    interaction =
                        BlockingInteraction.Optional(
                            sourceId = ForgeCardId(42),
                            forceSnapshotBeforePrompt = false,
                            customPromptId = PromptIds.DISCARD_OPTIONAL,
                            commanderReturn = null,
                        ),
                )

            val message = prepared.bundle.messages.single { it.hasOptionalActionMessage() }
            assertSoftly {
                message.prompt.promptId shouldBe PromptIds.DISCARD_OPTIONAL
                message.optionalActionMessage.prompt shouldBe message.prompt
            }
        }

        test("blocking optional and numeric prompts retain the viewer's alternate-zone action rail") {
            val railAction =
                ActionInfo
                    .newBuilder()
                    .setSeatId(1)
                    .setAction(Action.newBuilder().setActionType(ActionType.Cast).setInstanceId(303))
                    .build()
            val prior =
                ProjectionState.initial().copy(
                    viewerCursors =
                        mapOf(
                            SeatId(1) to
                                ViewerProjectionCursor(
                                    fullState = GameStateMessage.newBuilder().addActions(railAction).build(),
                                ),
                        ),
                )
            val materializer = BlockingInteractionMaterializer(seatId = 1)
            val optional =
                materializer.generalOptional(
                    prior,
                    LogicalSequencePlanner(),
                    BlockingInteraction.Optional(
                        sourceId = ForgeCardId(42),
                        forceSnapshotBeforePrompt = false,
                        customPromptId = null,
                        commanderReturn = null,
                    ),
                )
            val numeric =
                materializer.numeric(
                    prior,
                    LogicalSequencePlanner(),
                    BlockingInteraction.Numeric(
                        sourceId = ForgeCardId(42),
                        min = 0,
                        max = 3,
                        defaultValue = 0,
                    ),
                )

            optional.bundle.messages
                .single { it.hasGameStateMessage() }
                .gameStateMessage.actionsList shouldBe listOf(railAction)
            numeric.bundle.messages
                .single { it.hasGameStateMessage() }
                .gameStateMessage.actionsList shouldBe listOf(railAction)
        }

        test("top or bottom interactions identify the affected recipient and workflow") {
            val prepared =
                BlockingInteractionMaterializer(seatId = 1).topOrBottom(
                    prior = ProjectionState.initial(),
                    counter = LogicalSequencePlanner(),
                    interaction =
                        BlockingInteraction.TopOrBottom(
                            sourceId = ForgeCardId(42),
                            recipientId = ForgeCardId(84),
                        ),
                )

            val message = prepared.bundle.messages.single { it.hasOptionalActionMessage() }
            val sourceId = message.optionalActionMessage.sourceId
            val recipientId = message.optionalActionMessage.recipientIdsList.single()
            assertSoftly {
                sourceId shouldNotBe recipientId
                message.optionalActionMessage.optionalActionTypesList shouldBe listOf(CardMechanicType.PutTopOrBottom)
                message.optionalActionMessage.prompt.parametersList
                    .map { it.numberValue } shouldBe listOf(sourceId, 1, 1)
            }
        }

        test("multikicker uses the typed casting-time numeric workflow") {
            val prepared =
                BlockingInteractionMaterializer(seatId = 1).numeric(
                    prior = ProjectionState.initial(),
                    counter = LogicalSequencePlanner(),
                    interaction =
                        BlockingInteraction.Numeric(
                            sourceId = ForgeCardId(42),
                            min = 0,
                            max = 3,
                            defaultValue = 0,
                            presentation = BlockingInteraction.NumericPresentation.Multikicker,
                        ),
                )

            val message = prepared.bundle.messages.single { it.hasCastingTimeOptionsReq() }
            val option = message.castingTimeOptionsReq.castingTimeOptionReqList.single()
            assertSoftly {
                option.ctoId shouldBe 1
                option.castingTimeOptionType shouldBe CastingTimeOptionType.Multikicker
                option.numericInputReq.minValue shouldBe 0
                option.numericInputReq.maxValue shouldBe 3
            }
        }

        test("X prompt rides the post-cast stack snapshot and keeps replacement identity") {
            val source = ForgeCardId(42)
            val oldIid = InstanceId(101)
            val stackIid = InstanceId(202)
            val prior =
                ProjectionState
                    .initial()
                    .editor()
                    .apply { identities.bind(source, stackIid) }
                    .freeze()
            val transfer =
                AnnotationBuilder.zoneTransfer(
                    instanceId = stackIid,
                    srcZoneId = ZoneIds.P1_HAND,
                    destZoneId = ZoneIds.STACK,
                    category = "CastSpell",
                )
            val zone =
                ZoneInfo
                    .newBuilder()
                    .setZoneId(ZoneIds.STACK)
                    .addObjectInstanceIds(stackIid.value)
                    .build()
            val railAction =
                ActionInfo
                    .newBuilder()
                    .setSeatId(1)
                    .setAction(Action.newBuilder().setActionType(ActionType.Cast).setInstanceId(303))
                    .build()
            val state =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(7)
                    .addGameObjects(GameObjectInfo.newBuilder().setInstanceId(stackIid.value).setType(GameObjectType.Card))
                    .addZones(zone)
                    .addAnnotations(AnnotationBuilder.objectIdChanged(oldIid, stackIid))
                    .addAnnotations(transfer)
                    .addActions(railAction)
                    .build()
            val stateMessage = GREToClientMessage.newBuilder().setGameStateMessage(state).build()
            val prepared =
                BlockingInteractionMaterializer(seatId = 1).snapshotNumeric(
                    stateMessages = listOf(stateMessage),
                    counter = LogicalSequencePlanner(initialGsId = 7),
                    interaction =
                        BlockingInteraction.Numeric(
                            sourceId = source,
                            min = 0,
                            max = 4,
                            defaultValue = 0,
                        ),
                    transition = ProjectionTransition(prior.revision, prior),
                )

            val output = prepared.bundle.messages
            val snapshot = output.first { it.hasGameStateMessage() && it.gameStateMessage.zonesCount > 0 }.gameStateMessage
            val numeric = output.single { it.hasNumericInputReq() }.numericInputReq
            val pending = output.last { it.hasGameStateMessage() }.gameStateMessage
            assertSoftly {
                snapshot.zonesList.single { it.zoneId == ZoneIds.STACK }.objectInstanceIdsList shouldBe listOf(stackIid.value)
                snapshot.gameObjectsList.map { it.instanceId } shouldBe listOf(stackIid.value)
                snapshot.annotationsList.map { it.typeList.single() } shouldBe
                    listOf(
                        wotc.mtgo.gre.external.messaging.Messages.AnnotationType.ObjectIdChanged,
                        wotc.mtgo.gre.external.messaging.Messages.AnnotationType.ZoneTransfer_af5a,
                    )
                numeric.sourceId shouldBe stackIid.value
                pending.actionsList shouldBe state.actionsList
                (output.indexOfFirst { it.hasNumericInputReq() } > output.indexOf(stateMessage)) shouldBe true
            }
        }
    })
