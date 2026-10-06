package leyline.testkit

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.*

class ClientAccumulatorTest :
    FunSpec({

        tags(UnitTag)

        test("persistent annotations survive unrelated diffs and apply keyed replacements and deletions") {
            val acc = ClientAccumulator()
            val original =
                AnnotationInfo
                    .newBuilder()
                    .setId(1)
                    .setAffectorId(1)
                    .addType(AnnotationType.DungeonStatus)
                    .build()
            val replacement = original.toBuilder().setId(2).build()
            acc.process(
                greMessage(
                    msgId = 1,
                    gsm =
                        GameStateMessage
                            .newBuilder()
                            .setType(GameStateType.Full)
                            .addPersistentAnnotations(original)
                            .build(),
                ),
            )
            acc.process(greMessage(msgId = 2, gsm = GameStateMessage.newBuilder().setType(GameStateType.Diff).build()))
            acc.persistentAnnotations.values.toList() shouldBe listOf(original)

            acc.process(
                greMessage(
                    msgId = 3,
                    gsm =
                        GameStateMessage
                            .newBuilder()
                            .setType(GameStateType.Diff)
                            .addDiffDeletedPersistentAnnotationIds(1)
                            .addPersistentAnnotations(replacement)
                            .build(),
                ),
            )
            acc.persistentAnnotations shouldBe mapOf(2 to replacement)
            val updated = replacement.toBuilder().setAffectorId(2).build()
            acc.process(
                greMessage(
                    msgId = 4,
                    gsm =
                        GameStateMessage
                            .newBuilder()
                            .setType(GameStateType.Diff)
                            .addPersistentAnnotations(updated)
                            .build(),
                ),
            )
            acc.persistentAnnotations shouldBe mapOf(2 to updated)
            acc.process(
                greMessage(
                    msgId = 5,
                    gsm =
                        GameStateMessage
                            .newBuilder()
                            .setType(GameStateType.Diff)
                            .addDiffDeletedPersistentAnnotationIds(2)
                            .build(),
                ),
            )
            acc.persistentAnnotations shouldBe emptyMap()
        }

        test("a full state replaces the persistent annotation baseline") {
            val acc = ClientAccumulator()
            val annotation =
                AnnotationInfo
                    .newBuilder()
                    .setId(1)
                    .addType(AnnotationType.DungeonStatus)
                    .setAffectorId(1)
                    .build()
            acc.process(
                greMessage(
                    msgId = 1,
                    gsm =
                        GameStateMessage
                            .newBuilder()
                            .setType(GameStateType.Full)
                            .addPersistentAnnotations(annotation)
                            .build(),
                ),
            )
            acc.persistentAnnotations shouldBe mapOf(1 to annotation)
            acc.process(greMessage(msgId = 2, gsm = GameStateMessage.newBuilder().setType(GameStateType.Full).build()))
            acc.persistentAnnotations shouldBe emptyMap()
        }

        test("fullStateReplacesAllObjects") {
            val acc = ClientAccumulator()

            val gs =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(1)
                    .addGameObjects(
                        GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
                    ).addGameObjects(
                        GameObjectInfo.newBuilder().setInstanceId(101).setType(GameObjectType.Card),
                    ).build()
            acc.process(greMessage(msgId = 1, gsm = gs))

            assertSoftly {
                acc.objects.size shouldBe 2
                acc.objects.containsKey(100).shouldBeTrue()
                acc.objects.containsKey(101).shouldBeTrue()
                acc.latestGsId shouldBe 1
            }
        }

        test("diffMergesIntoExistingState") {
            val acc = ClientAccumulator()

            val full =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(1)
                    .addGameObjects(
                        GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
                    ).build()
            acc.process(greMessage(msgId = 1, gsm = full))

            val diff =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(2)
                    .addGameObjects(
                        GameObjectInfo.newBuilder().setInstanceId(101).setType(GameObjectType.Card),
                    ).build()
            acc.process(greMessage(msgId = 2, gsm = diff))

            assertSoftly {
                acc.objects.size shouldBe 2
                acc.objects.containsKey(100).shouldBeTrue()
                acc.objects.containsKey(101).shouldBeTrue()
                acc.latestGsId shouldBe 2
            }
        }

        test("fullStateResetsObjects") {
            val acc = ClientAccumulator()

            val full1 =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(1)
                    .addGameObjects(
                        GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
                    ).build()
            acc.process(greMessage(msgId = 1, gsm = full1))

            val full2 =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(2)
                    .addGameObjects(
                        GameObjectInfo.newBuilder().setInstanceId(200).setType(GameObjectType.Card),
                    ).build()
            acc.process(greMessage(msgId = 2, gsm = full2))

            assertSoftly {
                acc.objects.size shouldBe 1
                acc.objects.containsKey(100).shouldBeFalse()
                acc.objects.containsKey(200).shouldBeTrue()
            }
        }

        test("zonesTrackedFromState") {
            val acc = ClientAccumulator()

            val gs =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(1)
                    .addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(10)
                            .setType(ZoneType.Hand)
                            .addObjectInstanceIds(100)
                            .addObjectInstanceIds(101),
                    ).addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(20)
                            .setType(ZoneType.Library)
                            .addObjectInstanceIds(200),
                    ).build()
            acc.process(greMessage(msgId = 1, gsm = gs))

            acc.zones.size shouldBe 2
            acc.zones[10]!!.objectInstanceIdsList.size shouldBe 2
        }

        test("gsIdMonotonicInvariant") {
            val acc = ClientAccumulator()

            val gs1 =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(5)
                    .build()
            acc.process(greMessage(msgId = 1, gsm = gs1))
            acc.latestGsId shouldBe 5

            val gs2 =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(3)
                    .build()
            acc.process(greMessage(msgId = 2, gsm = gs2))

            acc.latestGsId shouldBe 5
        }

        test("actionsAvailableReqTracked") {
            val acc = ClientAccumulator()

            acc.process(
                actionsMessage(msgId = 1, gsId = 1) {
                    addActions(
                        Action
                            .newBuilder()
                            .setActionType(ActionType.Play_add3)
                            .setInstanceId(100),
                    )
                },
            )

            val storedActions = checkNotNull(acc.actions)
            storedActions.actionsCount shouldBe 1
        }

        test("interactive prompt retires the previous action window") {
            val acc = ClientAccumulator()
            acc.process(
                actionsMessage(msgId = 1, gsId = 1) {
                    addActions(Action.newBuilder().setActionType(ActionType.Play_add3).setInstanceId(100))
                },
            )

            acc.process(
                GREToClientMessage
                    .newBuilder()
                    .setMsgId(2)
                    .setGameStateId(2)
                    .setType(GREMessageType.GroupReq_695e)
                    .setPrompt(Prompt.newBuilder().setPromptId(17))
                    .setGroupReq(GroupReq.newBuilder().setContext(GroupingContext.Surveil))
                    .build(),
            )

            acc.actions shouldBe null
        }

        test("actionInstanceIdsMissingFromObjectsDetectsMissing") {
            val acc = ClientAccumulator()

            // Full state with object 100 only
            val gs =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(1)
                    .addGameObjects(
                        GameObjectInfo.newBuilder().setInstanceId(100).setType(GameObjectType.Card),
                    ).build()
            acc.process(greMessage(msgId = 1, gsm = gs))

            // Actions referencing iid 100 (exists) and 200 (missing)
            acc.process(
                actionsMessage(msgId = 2, gsId = 1) {
                    addActions(Action.newBuilder().setActionType(ActionType.Play_add3).setInstanceId(100))
                    addActions(Action.newBuilder().setActionType(ActionType.Cast).setInstanceId(200))
                },
            )

            val missing = acc.actionInstanceIdsMissingFromObjects()
            missing.size shouldBe 1
            missing[0] shouldBe 200
        }
    })
