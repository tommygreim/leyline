package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.iid
import leyline.game.sid
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

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

        test("empty frame leaves the counter unchanged") {
            AnnotationFrameFinalizer.finalize(emptyList(), firstId = 91) shouldBe
                FinalizedAnnotationFrame(emptyList(), nextId = 91)
        }
    })
