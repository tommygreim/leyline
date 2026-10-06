package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.GrpId
import leyline.game.iid
import leyline.game.sid
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class AnnotationFrameFinalizerTest :
    FunSpec({

        tags(UnitTag)

        test("a target annotation follows an id change riding the same frame") {
            // Reproduces ExileTypeCountCostLifecycleTest gs 5: the frame retires 113 and
            // PlayerSubmittedTargets still named it. The client looks that id up in the new
            // state only, gets null, and throws — dropping the whole update.
            val retire = AnnotationBuilder.objectIdChanged(origId = 113.iid, newId = 411.iid)
            val submitted = AnnotationBuilder.playerSubmittedTargets(instanceId = 113.iid, casterSeatId = 1.sid)

            val result = AnnotationFrameFinalizer.finalize(listOf(retire, submitted), firstId = 1)

            assertSoftly {
                result.annotations
                    .first { AnnotationType.PlayerSubmittedTargets in it.typeList }
                    .affectedIdsList shouldBe listOf(411)
                // The id change itself still reports the retired id it replaced.
                result.annotations
                    .first { AnnotationType.ObjectIdChanged in it.typeList }
                    .affectedIdsList shouldBe listOf(113)
            }
        }

        test("stale target annotations and TargetSpecs are omitted from the new state") {
            val staleSubmitted = AnnotationBuilder.playerSubmittedTargets(113.iid, 1.sid)
            val validSubmitted = AnnotationBuilder.playerSubmittedTargets(411.iid, 1.sid)
            val staleSpec =
                AnnotationBuilder.targetSpec(
                    instanceId = 113.iid,
                    affectorId = 113.iid,
                    abilityGrpId = GrpId(7),
                    index = 1,
                    promptId = 1,
                    promptParameters = 113,
                )
            val validSpec =
                AnnotationBuilder
                    .targetSpec(
                        instanceId = 411.iid,
                        affectorId = 411.iid,
                        abilityGrpId = GrpId(7),
                        index = 1,
                        promptId = 1,
                        promptParameters = 411,
                    ).toBuilder()
                    .setId(2)
                    .build()
            val gsm =
                GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .addGameObjects(
                        GameObjectInfo.newBuilder().setInstanceId(411).setType(GameObjectType.Card),
                    ).addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(10)
                            .setType(ZoneType.Battlefield)
                            .addObjectInstanceIds(411),
                    ).addAnnotations(staleSubmitted)
                    .addAnnotations(validSubmitted)
                    .addPersistentAnnotations(staleSpec.toBuilder().setId(1).build())
                    .addPersistentAnnotations(validSpec)
                    .build()

            val result = AnnotationReferenceSanitizer.sanitize(gsm)

            assertSoftly {
                result.transient.map { it.typeList.single() } shouldBe listOf(AnnotationType.PlayerSubmittedTargets)
                result.transient.single().affectedIdsList shouldBe listOf(411)
                result.persistent.map { it.id } shouldBe listOf(2)
                result.removedPersistentIds shouldBe listOf(1)
            }
        }

        test("orders a complete frame before assigning contiguous ids") {
            val manaPaid = AnnotationBuilder.manaPaid(spellInstanceId = 344.iid, landInstanceId = 281.iid)
            val cast = AnnotationBuilder.userActionTaken(instanceId = 344.iid, seatId = 1.sid, actionType = ActionType.Cast)
            val submitted = AnnotationBuilder.playerSubmittedTargets(instanceId = 344.iid, casterSeatId = 1.sid)

            val result = AnnotationFrameFinalizer.finalize(listOf(manaPaid, cast, submitted), firstId = 73)

            assertSoftly {
                result.annotations.map { it.typeList.first() } shouldBe
                    listOf(
                        AnnotationType.PlayerSubmittedTargets,
                        AnnotationType.ManaPaid,
                        AnnotationType.UserActionTaken,
                    )
                result.annotations.map { it.id } shouldBe listOf(73, 74, 75)
                result.nextId shouldBe 76
            }
        }

        test("preserves already ordered values while replacing only ids") {
            val first =
                AnnotationBuilder
                    .playerSelectingTargets(90.iid, 1.sid)
                    .toBuilder()
                    .setId(400)
                    .build()
            val second =
                AnnotationBuilder
                    .newTurnStarted(2.sid)
                    .toBuilder()
                    .setId(401)
                    .build()
            val input = listOf(first, second)

            val result = AnnotationFrameFinalizer.finalize(input, firstId = 50)

            assertSoftly {
                result.annotations.map { it.toBuilder().clearId().build() } shouldBe
                    input.map { it.toBuilder().clearId().build() }
                result.annotations.map { it.id } shouldBe listOf(50, 51)
                input.map { it.id } shouldBe listOf(400, 401)
            }
        }

        test("phase deduplication preserves the same phase for different active players") {
            val playerOne = AnnotationBuilder.phaseOrStepModified(1.sid, phase = 1, step = 1)
            val duplicatePlayerOne = AnnotationBuilder.phaseOrStepModified(1.sid, phase = 1, step = 1)
            val playerTwo = AnnotationBuilder.phaseOrStepModified(2.sid, phase = 1, step = 1)

            val result =
                AnnotationFrameFinalizer.finalize(
                    listOf(playerOne, duplicatePlayerOne, playerTwo),
                    firstId = 20,
                )

            assertSoftly {
                result.annotations.map { it.affectedIdsList.single() } shouldBe listOf(1, 2)
                result.annotations.map { it.id } shouldBe listOf(20, 21)
                result.nextId shouldBe 22
            }
        }

        test("empty frame leaves the counter unchanged") {
            AnnotationFrameFinalizer.finalize(emptyList(), firstId = 91) shouldBe
                FinalizedAnnotationFrame(emptyList(), nextId = 91)
        }
    })
