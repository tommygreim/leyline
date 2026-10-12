package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag
import leyline.game.codes.DetailKeys
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo

/**
 * Unit tests for focused [InvariantChecker] diagnostics and hard checks.
 *
 * Phase and resolution ordering checks are diagnostics, so these tests select
 * them explicitly instead of relying on the default hard-check set.
 */
class InvariantCheckerTest :
    FunSpec({

        tags(UnitTag)

        // --- Helpers (copied locally; small and self-contained) ---

        fun annotation(
            id: Int,
            type: AnnotationType,
        ): AnnotationInfo =
            AnnotationInfo
                .newBuilder()
                .setId(id)
                .addType(type)
                .build()

        fun zoneTransferAnnotation(
            id: Int,
            category: String,
            affectedId: Int = 100,
            srcZoneId: Int = ZoneIds.STACK,
        ): AnnotationInfo =
            AnnotationInfo
                .newBuilder()
                .setId(id)
                .addType(AnnotationType.ZoneTransfer_af5a)
                .addAffectedIds(affectedId)
                .addDetails(
                    KeyValuePairInfo
                        .newBuilder()
                        .setKey(DetailKeys.CATEGORY)
                        .setType(KeyValuePairValueType.String)
                        .addValueString(category)
                        .build(),
                ).addDetails(
                    KeyValuePairInfo
                        .newBuilder()
                        .setKey(DetailKeys.ZONE_SRC)
                        .setType(KeyValuePairValueType.Int32)
                        .addValueInt32(srcZoneId)
                        .build(),
                ).build()

        fun aicAnnotation(
            id: Int,
            abilityIid: Int,
            affectorId: Int,
        ): AnnotationInfo =
            AnnotationInfo
                .newBuilder()
                .setId(id)
                .addType(AnnotationType.AbilityInstanceCreated)
                .setAffectorId(affectorId)
                .addAffectedIds(abilityIid)
                .build()

        fun aidAnnotation(
            id: Int,
            abilityIid: Int,
            affectorId: Int,
        ): AnnotationInfo =
            AnnotationInfo
                .newBuilder()
                .setId(id)
                .addType(AnnotationType.AbilityInstanceDeleted)
                .setAffectorId(affectorId)
                .addAffectedIds(abilityIid)
                .build()

        fun resolutionStartAnnotation(
            id: Int,
            abilityIid: Int,
        ): AnnotationInfo =
            AnnotationInfo
                .newBuilder()
                .setId(id)
                .addType(AnnotationType.ResolutionStart)
                .setAffectorId(abilityIid)
                .build()

        fun gsm(
            gsId: Int,
            annotations: List<AnnotationInfo>,
        ): GameStateMessage =
            GameStateMessage
                .newBuilder()
                .setGameStateId(gsId)
                .setType(GameStateType.Full)
                .addAllAnnotations(annotations)
                .build()

        fun greMessage(
            msgId: Int,
            gsm: GameStateMessage,
        ): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(msgId)
                .setGameStateMessage(gsm)
                .build()

        fun checkerFor(
            reason: String,
            vararg checks: InvariantCheck,
        ) = InvariantChecker(InvariantSelection.only(reason, *checks))

        // --- Tests ---

        test("phase_first violation when PhaseOrStepModified is not at index 0") {
            val checker = checkerFor("phase diagnostic", InvariantCheck.PhaseFirst)
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.AbilityInstanceCreated),
                            annotation(2, AnnotationType.CounterAdded),
                            annotation(3, AnnotationType.PhaseOrStepModified),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            val phaseFirst = checker.violations.filter { it.check == "phase_first" }
            phaseFirst.shouldNotBeEmpty()
            phaseFirst.size shouldBe 1
        }

        test("no phase_first violation when PhaseOrStepModified is at index 0") {
            val checker = checkerFor("phase diagnostic", InvariantCheck.PhaseFirst)
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.PhaseOrStepModified),
                            annotation(2, AnnotationType.AbilityInstanceCreated),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "phase_first" }.shouldBeEmpty()
        }

        test("no phase_first violation when PhaseOrStepModified is absent") {
            val checker = checkerFor("phase diagnostic", InvariantCheck.PhaseFirst)
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.AbilityInstanceCreated),
                            annotation(2, AnnotationType.CounterAdded),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "phase_first" }.shouldBeEmpty()
        }

        test("no phase_first violation when multiple PhaseOrStepModified and first is at index 0") {
            val checker = checkerFor("phase diagnostic", InvariantCheck.PhaseFirst)
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.PhaseOrStepModified),
                            annotation(2, AnnotationType.PhaseOrStepModified),
                            annotation(3, AnnotationType.AbilityInstanceCreated),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "phase_first" }.shouldBeEmpty()
        }

        // --- resolution_transfer_ordering tests ---

        fun resolutionChecker() = checkerFor("resolution diagnostic", InvariantCheck.ResolutionTransferOrdering)

        test("resolution_transfer_ordering violation when stack Resolve ZT lands before ResolutionStart") {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.ObjectIdChanged),
                            zoneTransferAnnotation(2, "Resolve"),
                            annotation(3, AnnotationType.ResolutionStart),
                            annotation(4, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            val ordering = checker.violations.filter { it.check == "resolution_transfer_ordering" }
            ordering.shouldNotBeEmpty()
            ordering.size shouldBe 1
        }

        test("resolution_transfer_ordering violation when stack Resolve ZT lands between ResolutionStart and ResolutionComplete") {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.ResolutionStart),
                            zoneTransferAnnotation(2, "Resolve"),
                            annotation(3, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            val ordering = checker.violations.filter { it.check == "resolution_transfer_ordering" }
            ordering.shouldNotBeEmpty()
            ordering.size shouldBe 1
        }

        test("resolution_transfer_ordering records two violations when stack Resolve ZTs precede ResolutionComplete") {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            zoneTransferAnnotation(1, "Resolve", affectedId = 100),
                            annotation(2, AnnotationType.ResolutionStart),
                            zoneTransferAnnotation(3, "Resolve", affectedId = 200),
                            annotation(4, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.size shouldBe 2
        }

        test("no resolution_transfer_ordering violation when stack Resolve ZT follows ResolutionComplete") {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.ResolutionStart),
                            annotation(2, AnnotationType.ResolutionComplete),
                            zoneTransferAnnotation(3, "Resolve"),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.shouldBeEmpty()
        }

        test("no resolution_transfer_ordering violation when RS and RC are absent") {
            val checker = resolutionChecker()
            val g = gsm(gsId = 1, annotations = listOf(zoneTransferAnnotation(1, "Resolve")))

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.shouldBeEmpty()
        }

        test("no resolution_transfer_ordering violation when non-Resolve ZT sits before ResolutionComplete") {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            zoneTransferAnnotation(1, "CastSpell"),
                            annotation(2, AnnotationType.ResolutionStart),
                            annotation(3, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.shouldBeEmpty()
        }

        test("no resolution_transfer_ordering violation when multiple stack Resolve ZTs follow ResolutionComplete") {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.ResolutionStart),
                            annotation(2, AnnotationType.ResolutionComplete),
                            zoneTransferAnnotation(3, "Resolve", affectedId = 100),
                            zoneTransferAnnotation(4, "Resolve", affectedId = 200),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.shouldBeEmpty()
        }

        test(
            "no resolution_transfer_ordering violation when non-stack Resolve ZT sits inside ResolutionStart and ResolutionComplete",
        ) {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            annotation(1, AnnotationType.ResolutionStart),
                            zoneTransferAnnotation(2, "Resolve", srcZoneId = ZoneIds.P1_LIBRARY),
                            annotation(3, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "resolution_transfer_ordering" }.shouldBeEmpty()
        }

        test("resolution_transfer_ordering violation when non-stack Resolve ZT lands before ResolutionStart") {
            val checker = resolutionChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            zoneTransferAnnotation(1, "Resolve", srcZoneId = ZoneIds.P1_LIBRARY),
                            annotation(2, AnnotationType.ResolutionStart),
                            annotation(3, AnnotationType.ResolutionComplete),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))
            checker.violations.filter { it.check == "resolution_transfer_ordering" }.size shouldBe 1
        }

        // --- aid_affector tests ---

        test("aid_affector violation when AID affectorId differs from prior AIC affectorId across GSMs") {
            val checker = InvariantChecker()
            val g1 = gsm(gsId = 1, annotations = listOf(aicAnnotation(id = 1, abilityIid = 416, affectorId = 372)))
            val g2 = gsm(gsId = 2, annotations = listOf(aidAnnotation(id = 1, abilityIid = 416, affectorId = 418)))

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))

            val mismatches = checker.violations.filter { it.check == "aid_affector" }
            assertSoftly {
                mismatches.size shouldBe 1
                mismatches[0].gsId shouldBe 2
                mismatches[0].message shouldContain "372"
                mismatches[0].message shouldContain "418"
            }
        }

        test("no aid_affector violation when AID affectorId matches prior AIC across GSMs") {
            val checker = InvariantChecker()
            val g1 = gsm(gsId = 1, annotations = listOf(aicAnnotation(id = 1, abilityIid = 416, affectorId = 372)))
            val g2 = gsm(gsId = 2, annotations = listOf(aidAnnotation(id = 1, abilityIid = 416, affectorId = 372)))

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))

            checker.violations.filter { it.check == "aid_affector" }.shouldBeEmpty()
        }

        test("no aid_affector violation when AID has no prior AIC for this ability iid") {
            val checker = InvariantChecker()
            val g = gsm(gsId = 1, annotations = listOf(aidAnnotation(id = 1, abilityIid = 416, affectorId = 999)))

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "aid_affector" }.shouldBeEmpty()
        }

        test("no aid_affector violation for same-GSM AIC+AID pair (mana bracket)") {
            val checker = InvariantChecker()
            val g =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            aicAnnotation(id = 1, abilityIid = 100, affectorId = 200),
                            aidAnnotation(id = 2, abilityIid = 100, affectorId = 200),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.filter { it.check == "aid_affector" }.shouldBeEmpty()
        }

        test("aid_affector tracks multiple ability iids independently") {
            val checker = InvariantChecker()
            val g1 =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            aicAnnotation(id = 1, abilityIid = 10, affectorId = 20),
                            aicAnnotation(id = 2, abilityIid = 30, affectorId = 40),
                        ),
                )
            val g2 =
                gsm(
                    gsId = 2,
                    annotations =
                        listOf(
                            aidAnnotation(id = 1, abilityIid = 10, affectorId = 999),
                            aidAnnotation(id = 2, abilityIid = 30, affectorId = 40),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))

            val mismatches = checker.violations.filter { it.check == "aid_affector" }
            assertSoftly {
                mismatches.size shouldBe 1
                mismatches[0].message shouldContain "ability=10"
            }
        }

        test("annotation_ref treats open ability lifecycles as known ids") {
            val checker = InvariantChecker()
            val g1 = gsm(gsId = 1, annotations = listOf(aicAnnotation(id = 1, abilityIid = 116, affectorId = 1)))
            val g2 =
                gsm(
                    gsId = 2,
                    annotations =
                        listOf(
                            resolutionStartAnnotation(id = 1, abilityIid = 116),
                            aidAnnotation(id = 2, abilityIid = 116, affectorId = 1),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))

            checker.violations.filter { it.check == "annotation_ref" }.shouldBeEmpty()
        }

        test("default validation rejects a submitted target object absent from every zone") {
            val checker = InvariantChecker()
            val submitted =
                annotation(1, AnnotationType.PlayerSubmittedTargets)
                    .toBuilder()
                    .setAffectorId(1)
                    .addAffectedIds(123)
                    .build()
            val g =
                gsm(gsId = 1, annotations = listOf(submitted))
                    .toBuilder()
                    .addGameObjects(GameObjectInfo.newBuilder().setInstanceId(123).setType(GameObjectType.Card))
                    .build()

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.map { it.check } shouldBe listOf("client_annotation_ref")
            checker.violations.single().message shouldContain "affectedId 123"
        }

        test("submitted targets cannot use a retired card from the previous state") {
            val checker = InvariantChecker()
            val initial =
                gsm(gsId = 1, annotations = emptyList())
                    .toBuilder()
                    .addGameObjects(GameObjectInfo.newBuilder().setInstanceId(123).setType(GameObjectType.Card))
                    .addZones(ZoneInfo.newBuilder().setZoneId(ZoneIds.STACK).addObjectInstanceIds(123))
                    .build()
            checker.seedFull(initial)
            val submitted =
                annotation(1, AnnotationType.PlayerSubmittedTargets)
                    .toBuilder()
                    .setAffectorId(1)
                    .addAffectedIds(123)
                    .build()
            val next =
                gsm(gsId = 2, annotations = listOf(submitted))
                    .toBuilder()
                    .setType(GameStateType.Diff)
                    .addDiffDeletedInstanceIds(123)
                    .addZones(ZoneInfo.newBuilder().setZoneId(ZoneIds.STACK))
                    .build()

            checker.process(greMessage(msgId = 1, gsm = next))

            checker.violations.map { it.check } shouldBe listOf("client_annotation_ref")
        }

        test("damage cannot use an unknown source just because an ability annotation names it") {
            val checker = InvariantChecker()
            val damage =
                annotation(2, AnnotationType.DamageDealt_af5a)
                    .toBuilder()
                    .setAffectorId(123)
                    .addAffectedIds(2)
                    .build()
            val g = gsm(gsId = 1, annotations = listOf(aicAnnotation(1, 123, 1), damage))

            checker.process(greMessage(msgId = 1, gsm = g))

            checker.violations.map { it.check } shouldBe listOf("client_annotation_ref")
            checker.violations.single().message shouldContain "affectorId 123"
        }

        test("damage retains an old source and victim through their same-frame deletion") {
            val checker = InvariantChecker()
            val initial =
                gsm(gsId = 1, annotations = emptyList())
                    .toBuilder()
                    .addGameObjects(GameObjectInfo.newBuilder().setInstanceId(123).setType(GameObjectType.Card))
                    .addGameObjects(GameObjectInfo.newBuilder().setInstanceId(124).setType(GameObjectType.Token))
                    .addZones(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(ZoneIds.BATTLEFIELD)
                            .addObjectInstanceIds(123)
                            .addObjectInstanceIds(124),
                    ).build()
            checker.seedFull(initial)
            val damage =
                annotation(1, AnnotationType.DamageDealt_af5a)
                    .toBuilder()
                    .setAffectorId(123)
                    .addAffectedIds(124)
                    .build()
            val next =
                gsm(gsId = 2, annotations = listOf(damage))
                    .toBuilder()
                    .setType(GameStateType.Diff)
                    .addAllDiffDeletedInstanceIds(listOf(123, 124))
                    .addZones(ZoneInfo.newBuilder().setZoneId(ZoneIds.BATTLEFIELD))
                    .build()

            checker.process(greMessage(msgId = 1, gsm = next))

            checker.violations.shouldBeEmpty()
        }

        test("guarded historical animation and persistent-target references are not fatal") {
            val checker = InvariantChecker()
            val annotations =
                listOf(
                    AnnotationType.ManaPaid,
                    AnnotationType.UserActionTaken,
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.TargetSpec,
                ).mapIndexed {
                    index,
                    type,
                    ->
                    annotation(index + 1, type)
                        .toBuilder()
                        .setAffectorId(123)
                        .addAffectedIds(124)
                        .build()
                }

            checker.process(greMessage(msgId = 1, gsm = gsm(gsId = 1, annotations = annotations)))

            checker.violations.shouldBeEmpty()
        }

        test("aid_affector entry is pruned after AID fires") {
            val checker = InvariantChecker()
            val g1 = gsm(gsId = 1, annotations = listOf(aicAnnotation(id = 1, abilityIid = 50, affectorId = 60)))
            val g2 = gsm(gsId = 2, annotations = listOf(aidAnnotation(id = 1, abilityIid = 50, affectorId = 60)))
            val g3 = gsm(gsId = 3, annotations = listOf(aidAnnotation(id = 1, abilityIid = 50, affectorId = 999)))

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))
            checker.process(greMessage(msgId = 3, gsm = g3))

            checker.violations.filter { it.check == "aid_affector" }.shouldBeEmpty()
        }

        test("aid_affector — same-GSM AIC+AID does not leak entry into history") {
            val checker = InvariantChecker()
            // GSM 1: AIC and AID for ability 100 in the same GSM (mana bracket).
            val g1 =
                gsm(
                    gsId = 1,
                    annotations =
                        listOf(
                            aicAnnotation(id = 1, abilityIid = 100, affectorId = 200),
                            aidAnnotation(id = 2, abilityIid = 100, affectorId = 200),
                        ),
                )
            // GSM 2: standalone AID with wrong affector for ability 100 — should NOT
            // trip a violation, because the same-GSM AIC was pruned, not stored.
            val g2 =
                gsm(
                    gsId = 2,
                    annotations =
                        listOf(
                            aidAnnotation(id = 1, abilityIid = 100, affectorId = 999),
                        ),
                )

            checker.process(greMessage(msgId = 1, gsm = g1))
            checker.process(greMessage(msgId = 2, gsm = g2))

            checker.violations.filter { it.check == "aid_affector" }.shouldBeEmpty()
        }
    })
