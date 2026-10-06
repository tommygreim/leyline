package leyline.behavior.annotations.addability

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.detailUint
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Integration test for keyword grant via Overrun (grpId 93943).
 *
 * Overrun: all creatures you control get +3/+3 and trample until end of turn.
 * Tests the full keyword grant pipeline:
 *   Forge event → GameEventCollector → EffectTracker → AddAbility pAnn + uniqueAbilities on gameObject.
 */
class KeywordGrantOverrunTest :
    SessionTest({

        session(
            "Overrun: creatures get AddAbility pAnn with Trample grpId",
            puzzleFile = "data/puzzles/keyword-grant-overrun.pzl",
        ) {
            castSpellByName("Overrun").shouldBeTrue()
            passUntilResolved()

            // Find AddAbility persistent annotation
            val addAbilities =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .filter { AnnotationType.AddAbility_af5a in it.typeList && it.detailUint("grpid") == 14 }
                    .distinctBy { it.id }
            assertSoftly {
                // Each recipient owns its effect lifecycle; neither row can
                // survive when that recipient's keyword grant is destroyed.
                addAbilities.size shouldBe 2
                addAbilities.forEach { it.affectedIdsList.size shouldBe 1 }
                addAbilities.flatMap { it.affectedIdsList }.distinct().size shouldBe 2
            }
        }

        session(
            "Overrun: creature gameObjects have Trample in uniqueAbilities",
            puzzleFile = "data/puzzles/keyword-grant-overrun.pzl",
        ) {
            val bears =
                human
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Grizzly Bears" }
            bears.size shouldBe 2

            castSpellByName("Overrun").shouldBeTrue()
            passUntilResolved()

            val bearIids = bears.map { human.battlefield.iid(it) }.toSet()
            val bearObjects = bearIids.mapNotNull { accumulator.objects[it] }
            bearObjects.shouldNotBeEmpty()

            for (obj in bearObjects) {
                val trampleAbility = obj.uniqueAbilitiesList.firstOrNull { it.grpId == 14 }
                trampleAbility.shouldNotBeNull()
            }
        }
    })
