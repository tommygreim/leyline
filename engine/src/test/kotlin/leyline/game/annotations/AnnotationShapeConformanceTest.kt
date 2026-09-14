package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationLossReason
import leyline.game.eid
import leyline.game.event.DamageSourceKind
import leyline.game.grp
import leyline.game.iid
import leyline.game.sid
import leyline.game.wid
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Annotation detail-key shape conformance tests.
 *
 * Two concerns:
 * 1. **Per-builder shape tests** — assert each builder produces the exact
 *    set of detail keys the client expects.
 * 2. **Reference conformance** — cross-check all builders against the
 *    baseline set of always-present detail keys per annotation type.
 *
 * See also: `AnnotationBuilderTest` for per-field value/type assertions.
 */
class AnnotationShapeConformanceTest :
    FunSpec({

        tags(UnitTag)

        fun detailKeys(ann: AnnotationInfo): Set<String> = ann.detailsList.map { it.key }.toSet()

        // =======================================================================
        // Per-builder detail-key shape tests
        //
        // Verify each builder method produces the exact set of detail keys
        // the client expects.
        // =======================================================================

        test("DamageDealt shape: {damage, type, markDamage}") {
            val ann =
                AnnotationBuilder.damageDealt(
                    sourceInstanceId = 1.iid,
                    targetId = 2.wid,
                    amount = 3,
                    sourceKind = DamageSourceKind.Combat,
                )
            detailKeys(ann) shouldBe setOf("damage", "type", "markDamage")
        }

        test("ManaPaid shape: {id, color}") {
            val ann = AnnotationBuilder.manaPaid(spellInstanceId = 1.iid, landInstanceId = 2.iid, manaId = 1, color = 4)
            detailKeys(ann) shouldBe setOf("id", "color")
        }

        test("AbilityInstanceCreated shape: {source_zone}") {
            val ann = AnnotationBuilder.abilityInstanceCreated(abilityInstanceId = 1.iid, sourceZoneId = 31)
            detailKeys(ann) shouldBe setOf("source_zone")
        }

        test("ZoneTransfer shape: {zone_src, zone_dest, category}") {
            val ann = AnnotationBuilder.zoneTransfer(1.iid, 31, 28, "PlayLand")
            detailKeys(ann) shouldBe setOf("zone_src", "zone_dest", "category")
        }

        test("ResolutionStart shape: {grpid}") {
            val ann = AnnotationBuilder.resolutionStart(1.iid, 12345.grp)
            detailKeys(ann) shouldBe setOf("grpid")
        }

        test("ResolutionComplete shape: {grpid}") {
            val ann = AnnotationBuilder.resolutionComplete(1.iid, 12345.grp)
            detailKeys(ann) shouldBe setOf("grpid")
        }

        test("UserActionTaken shape: {actionType, abilityGrpId}") {
            val ann = AnnotationBuilder.userActionTaken(1.iid, 1.sid, ActionType.Cast, 0.grp)
            detailKeys(ann) shouldBe setOf("actionType", "abilityGrpId")
        }

        test("TappedUntappedPermanent shape: {tapped}") {
            val ann = AnnotationBuilder.tappedUntappedPermanent(1.iid, 2.iid)
            detailKeys(ann) shouldBe setOf("tapped")
        }

        test("ObjectIdChanged shape: {orig_id, new_id}") {
            val ann = AnnotationBuilder.objectIdChanged(1.iid, 2.iid)
            detailKeys(ann) shouldBe setOf("orig_id", "new_id")
        }

        test("PhaseOrStepModified shape: {phase, step}") {
            val ann = AnnotationBuilder.phaseOrStepModified(1.sid, 1, 2)
            detailKeys(ann) shouldBe setOf("phase", "step")
        }

        test("ModifiedLife shape: {delta}") {
            val ann = AnnotationBuilder.modifiedLife(1.sid, -3)
            detailKeys(ann) shouldBe setOf("life")
        }

        test("ModifiedPower shape: no required keys") {
            val ann = AnnotationBuilder.modifiedPower(1.iid)
            detailKeys(ann) shouldBe emptySet()
        }

        test("ModifiedToughness shape: no required keys") {
            val ann = AnnotationBuilder.modifiedToughness(1.iid)
            detailKeys(ann) shouldBe emptySet()
        }

        test("LossOfGame shape: {reason}") {
            val ann = AnnotationBuilder.lossOfGame(1.sid, AnnotationLossReason.LifeTotal)
            detailKeys(ann) shouldBe setOf("reason")
        }

        test("CounterAdded shape: {counter_type, transaction_amount}") {
            val ann = AnnotationBuilder.counterAdded(1.iid, "P1P1", 2)
            detailKeys(ann) shouldBe setOf("counter_type", "transaction_amount")
        }

        test("CounterRemoved shape: {counter_type, transaction_amount}") {
            val ann = AnnotationBuilder.counterRemoved(1.iid, "LOYALTY", 1)
            detailKeys(ann) shouldBe setOf("counter_type", "transaction_amount")
        }

        test("Scry shape: {topIds, bottomIds}") {
            val ann = AnnotationBuilder.scry(1.sid, listOf(2, 3), listOf(4))
            detailKeys(ann) shouldBe setOf("topIds", "bottomIds")
        }

        test("SyntheticEvent shape: {type}") {
            val ann = AnnotationBuilder.syntheticEvent(1.iid, 1.sid)
            detailKeys(ann) shouldBe setOf("type")
        }

        test("Counter shape: {count, counter_type}") {
            val ann = AnnotationBuilder.counter(1.iid, 1, 1)
            detailKeys(ann) shouldBe setOf("count", "counter_type")
        }

        test("AddAbility shape: {grpid, effect_id, UniqueAbilityId, originalAbilityObjectZcid}") {
            detailKeys(AnnotationBuilder.addAbility(1.iid, 1.grp, 1.eid, 1, 1)) shouldBe
                setOf("grpid", "effect_id", "UniqueAbilityId", "originalAbilityObjectZcid")
        }

        test("RemoveAbility shape: {effect_id}") {
            detailKeys(AnnotationBuilder.removeAbility(1.iid, 1.eid)) shouldBe setOf("effect_id")
        }

        test("AbilityExhausted shape: {AbilityGrpId, UsesRemaining, UniqueAbilityId}") {
            detailKeys(AnnotationBuilder.abilityExhausted(1.iid, 1.grp, 0, 1)) shouldBe
                setOf("AbilityGrpId", "UsesRemaining", "UniqueAbilityId")
        }

        test("GainDesignation shape: {DesignationType}") {
            detailKeys(AnnotationBuilder.gainDesignation(1.sid, 19)) shouldBe setOf("DesignationType")
        }

        test("Designation shape: {DesignationType}") {
            detailKeys(AnnotationBuilder.designation(1.sid, 19)) shouldBe setOf("DesignationType")
        }

        test("LayeredEffect shape: {effect_id}") {
            detailKeys(AnnotationBuilder.layeredEffect(1.iid, 7004.eid)) shouldBe setOf("effect_id")
        }

        test("ColorProduction shape: {colors}") {
            detailKeys(AnnotationBuilder.colorProduction(1.iid, listOf(1))) shouldBe setOf("colors")
        }

        test("ClassLevel shape: {Level}") {
            detailKeys(AnnotationBuilder.classLevel(1.iid, 2)) shouldBe setOf("Level")
        }

        test("TriggeringObject shape: {source_zone}") {
            detailKeys(AnnotationBuilder.triggeringObject(1.iid, 2.iid, 27)) shouldBe setOf("source_zone")
        }

        test("TargetSpec shape: {abilityGrpId, index, promptId, promptParameters}") {
            detailKeys(AnnotationBuilder.targetSpec(1.iid, 1.iid, 1.grp, 1, 1, 1)) shouldBe
                setOf("abilityGrpId", "index", "promptId", "promptParameters")
        }

        test("TargetSpec distributions are optional") {
            detailKeys(
                AnnotationBuilder.targetSpec(
                    instanceIds = listOf(1.iid, 2.iid),
                    affectorId = 3.iid,
                    abilityGrpId = 4.grp,
                    index = 1,
                    promptId = 11869,
                    promptParameters = 3,
                    distributions = listOf(1, 1),
                ),
            ) shouldBe setOf("abilityGrpId", "index", "promptId", "promptParameters", "distributions")
        }

        test("PowerToughnessModCreated shape: {power, toughness}") {
            detailKeys(AnnotationBuilder.powerToughnessModCreated(1.iid, 1, 1)) shouldBe setOf("power", "toughness")
        }

        test("DisplayCardUnderCard shape: {Disable, TemporaryZoneTransfer}") {
            detailKeys(AnnotationBuilder.displayCardUnderCard(affectorId = 0.iid, instanceId = 1.iid)) shouldBe
                setOf("Disable", "TemporaryZoneTransfer")
        }

        test("PredictedDirectDamage shape: {value}") {
            detailKeys(AnnotationBuilder.predictedDirectDamage(1.iid, 1)) shouldBe setOf("value")
        }

        test("Shuffle shape: {OldIds, NewIds}") {
            detailKeys(AnnotationBuilder.shuffle(1.sid, listOf(1), listOf(2), 3.iid)) shouldBe setOf("OldIds", "NewIds")
        }

        test("No-detail annotations: NewTurnStarted, EnteredZoneThisTurn, etc.") {
            assertSoftly {
                detailKeys(AnnotationBuilder.newTurnStarted(1.sid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.enteredZoneThisTurn(28, 1.iid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.abilityInstanceDeleted(1.iid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.tokenCreated(1.iid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.tokenDeleted(1.iid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.attachmentCreated(1.iid, 2.iid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.attachment(1.iid, 2.iid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.removeAttachment(1.iid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.revealedCardCreated(1.iid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.revealedCardDeleted(1.iid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.layeredEffectDestroyed(1.eid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.playerSelectingTargets(1.iid, 1.sid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.playerSubmittedTargets(1.iid, 1.sid)) shouldBe emptySet()
                detailKeys(AnnotationBuilder.damagedThisTurn(listOf(1.iid))) shouldBe emptySet()
                detailKeys(AnnotationBuilder.instanceRevealedToOpponent(1.iid)) shouldBe emptySet()
            }
        }

        // =======================================================================
        // Reference conformance
        //
        // Cross-check builders against the baseline detail-key set per type.
        // The baseline is what the client always expects to see; any drift
        // between our builder output and this reference fails the test and
        // forces triage.
        //
        // Workflow after fixing a builder:
        //   1. Fix the builder method in AnnotationBuilder.kt
        //   2. Run `just test-gate` — this test fails
        //   3. Remove the type from expectedMismatch (or update referenceAlwaysKeys)
        // =======================================================================

        /** Baseline: always-present detail keys per annotation type. */
        val referenceAlwaysKeys: Map<String, Set<String>> =
            mapOf(
                // --- Most-common types ---
                "PhaseOrStepModified" to setOf("phase", "step"),
                "ZoneTransfer" to setOf("category", "zone_dest", "zone_src"),
                "EnteredZoneThisTurn" to emptySet(), // persistent, no details
                "UserActionTaken" to setOf("abilityGrpId", "actionType"),
                "ObjectIdChanged" to setOf("new_id", "orig_id"),
                "TappedUntappedPermanent" to setOf("tapped"),
                "AbilityInstanceCreated" to setOf("source_zone"),
                "AbilityInstanceDeleted" to emptySet(),
                "ManaPaid" to setOf("color", "id"),
                "ManaDetails" to setOf("ManaSpecType_DoesNotEmpty"),
                "ResolutionComplete" to setOf("grpid"),
                "ResolutionStart" to setOf("grpid"),
                // --- Medium frequency ---
                "NewTurnStarted" to emptySet(),
                "DamageDealt" to setOf("damage", "markDamage", "type"),
                "ModifiedToughness" to emptySet(), // all detail keys are optional
                "ModifiedPower" to emptySet(), // all detail keys are optional
                "ModifiedLife" to setOf("life"),
                "SyntheticEvent" to setOf("type"),
                // --- Rare types ---
                "TokenCreated" to emptySet(),
                "AttachmentCreated" to emptySet(),
                "Attachment" to emptySet(),
                "CounterAdded" to setOf("counter_type", "transaction_amount"),
                "TokenDeleted" to emptySet(),
                "Counter" to setOf("count", "counter_type"),
                "AddAbility" to setOf("grpid", "effect_id", "UniqueAbilityId", "originalAbilityObjectZcid"),
                "RemoveAbility" to setOf("effect_id"),
                "AbilityExhausted" to setOf("AbilityGrpId", "UsesRemaining", "UniqueAbilityId"),
                "GainDesignation" to setOf("DesignationType"),
                "Designation" to setOf("DesignationType"),
                "LayeredEffect" to setOf("effect_id"),
                "LayeredEffectDestroyed" to emptySet(),
                "PlayerSelectingTargets" to emptySet(),
                "PlayerSubmittedTargets" to emptySet(),
                "DamagedThisTurn" to emptySet(),
                "InstanceRevealedToOpponent" to emptySet(),
                "ColorProduction" to setOf("colors"),
                "ClassLevel" to setOf("Level"),
                "TriggeringObject" to setOf("source_zone"),
                "TargetSpec" to setOf("abilityGrpId", "index", "promptId", "promptParameters"),
                "PowerToughnessModCreated" to setOf("power", "toughness"),
                "DisplayCardUnderCard" to setOf("Disable", "TemporaryZoneTransfer"),
                "PredictedDirectDamage" to setOf("value"),
            )

        /**
         * Our builder output per type — calls each builder with dummy args,
         * extracts detail keys.
         */
        val ourBuilderKeys: Map<String, Set<String>> =
            mapOf(
                "PhaseOrStepModified" to detailKeys(AnnotationBuilder.phaseOrStepModified(1.sid, 1, 2)),
                "ZoneTransfer" to detailKeys(AnnotationBuilder.zoneTransfer(1.iid, 31, 28, "PlayLand")),
                "EnteredZoneThisTurn" to detailKeys(AnnotationBuilder.enteredZoneThisTurn(28, 1.iid)),
                "UserActionTaken" to detailKeys(AnnotationBuilder.userActionTaken(1.iid, 1.sid, ActionType.Cast, 0.grp)),
                "ObjectIdChanged" to detailKeys(AnnotationBuilder.objectIdChanged(1.iid, 2.iid)),
                "TappedUntappedPermanent" to detailKeys(AnnotationBuilder.tappedUntappedPermanent(1.iid, 2.iid)),
                "AbilityInstanceCreated" to detailKeys(AnnotationBuilder.abilityInstanceCreated(1.iid, sourceZoneId = 31)),
                "AbilityInstanceDeleted" to detailKeys(AnnotationBuilder.abilityInstanceDeleted(1.iid)),
                "ManaPaid" to
                    detailKeys(
                        AnnotationBuilder.manaPaid(spellInstanceId = 1.iid, landInstanceId = 2.iid, manaId = 1, color = 4),
                    ),
                "ManaDetails" to detailKeys(AnnotationBuilder.manaDetails(sourceInstanceId = 2.iid, manaId = 1)),
                "ResolutionComplete" to detailKeys(AnnotationBuilder.resolutionComplete(1.iid, 1.grp)),
                "ResolutionStart" to detailKeys(AnnotationBuilder.resolutionStart(1.iid, 1.grp)),
                "NewTurnStarted" to detailKeys(AnnotationBuilder.newTurnStarted(1.sid)),
                "DamageDealt" to detailKeys(AnnotationBuilder.damageDealt(1.iid, 2.wid, 3, DamageSourceKind.Combat)),
                "ModifiedToughness" to detailKeys(AnnotationBuilder.modifiedToughness(1.iid)),
                "ModifiedPower" to detailKeys(AnnotationBuilder.modifiedPower(1.iid)),
                "ModifiedLife" to detailKeys(AnnotationBuilder.modifiedLife(1.sid, -3)),
                "SyntheticEvent" to detailKeys(AnnotationBuilder.syntheticEvent(1.iid, 1.sid)),
                "TokenCreated" to detailKeys(AnnotationBuilder.tokenCreated(1.iid)),
                "AttachmentCreated" to detailKeys(AnnotationBuilder.attachmentCreated(1.iid, 2.iid)),
                "Attachment" to detailKeys(AnnotationBuilder.attachment(1.iid, 2.iid)),
                "CounterAdded" to detailKeys(AnnotationBuilder.counterAdded(1.iid, "P1P1", 2)),
                "TokenDeleted" to detailKeys(AnnotationBuilder.tokenDeleted(1.iid)),
                "Counter" to detailKeys(AnnotationBuilder.counter(1.iid, 1, 1)),
                "AddAbility" to detailKeys(AnnotationBuilder.addAbility(1.iid, 1.grp, 1.eid, 1, 1)),
                "RemoveAbility" to detailKeys(AnnotationBuilder.removeAbility(1.iid, 1.eid)),
                "AbilityExhausted" to detailKeys(AnnotationBuilder.abilityExhausted(1.iid, 1.grp, 0, 1)),
                "GainDesignation" to detailKeys(AnnotationBuilder.gainDesignation(1.sid, 19)),
                "Designation" to detailKeys(AnnotationBuilder.designation(1.sid, 19)),
                "LayeredEffect" to detailKeys(AnnotationBuilder.layeredEffect(1.iid, 7004.eid)),
                "LayeredEffectDestroyed" to detailKeys(AnnotationBuilder.layeredEffectDestroyed(1.eid)),
                "PlayerSelectingTargets" to detailKeys(AnnotationBuilder.playerSelectingTargets(1.iid, 1.sid)),
                "PlayerSubmittedTargets" to detailKeys(AnnotationBuilder.playerSubmittedTargets(1.iid, 1.sid)),
                "DamagedThisTurn" to detailKeys(AnnotationBuilder.damagedThisTurn(listOf(1.iid))),
                "InstanceRevealedToOpponent" to detailKeys(AnnotationBuilder.instanceRevealedToOpponent(1.iid)),
                "ColorProduction" to detailKeys(AnnotationBuilder.colorProduction(1.iid, listOf(1))),
                "ClassLevel" to detailKeys(AnnotationBuilder.classLevel(1.iid, 2)),
                "TriggeringObject" to detailKeys(AnnotationBuilder.triggeringObject(1.iid, 2.iid, 27)),
                "TargetSpec" to detailKeys(AnnotationBuilder.targetSpec(1.iid, 1.iid, 1.grp, 1, 1, 1)),
                "PowerToughnessModCreated" to detailKeys(AnnotationBuilder.powerToughnessModCreated(1.iid, 1, 1)),
                "DisplayCardUnderCard" to detailKeys(AnnotationBuilder.displayCardUnderCard(affectorId = 0.iid, instanceId = 1.iid)),
                "PredictedDirectDamage" to detailKeys(AnnotationBuilder.predictedDirectDamage(1.iid, 1)),
            )

        /**
         * Known mismatches: types where our builder intentionally differs from
         * the reference set. Each entry documents WHY and what the fix looks like.
         *
         * When you fix a builder, REMOVE the entry here — the test will confirm
         * the fix by passing without it.
         */
        val expectedMismatch: Map<String, String> = emptyMap()

        test("Reference set: our builder detail keys match always-present keys") {
            val failures = mutableListOf<String>()

            for ((typeName, referenceKeys) in referenceAlwaysKeys) {
                val ourKeys = ourBuilderKeys[typeName]
                if (ourKeys == null) {
                    failures += "$typeName: no builder registered in ourBuilderKeys"
                    continue
                }

                val missing = referenceKeys - ourKeys // reference has, we don't
                val extra = ourKeys - referenceKeys // we have, reference doesn't

                if (missing.isEmpty() && extra.isEmpty()) {
                    // OK — check it's NOT in expectedMismatch (stale entry)
                    if (typeName in expectedMismatch) {
                        failures += "$typeName: marked as expectedMismatch but now matches! " +
                            "Remove from expectedMismatch."
                    }
                    continue
                }

                // Mismatch — must be in expectedMismatch
                if (typeName !in expectedMismatch) {
                    failures +=
                        buildString {
                            append("$typeName: MISMATCH not in expectedMismatch.")
                            if (missing.isNotEmpty()) append(" missing=$missing")
                            if (extra.isNotEmpty()) append(" extra=$extra")
                            append(" Either fix the builder or add to expectedMismatch with a comment.")
                        }
                }
            }

            if (failures.isNotEmpty()) {
                val msg =
                    buildString {
                        appendLine("Golden reference conformance failures:")
                        appendLine()
                        for (f in failures) appendLine("  - $f")
                    }
                fail(msg)
            }
        }
    })
