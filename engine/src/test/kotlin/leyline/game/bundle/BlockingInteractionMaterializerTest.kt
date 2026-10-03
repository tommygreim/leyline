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
