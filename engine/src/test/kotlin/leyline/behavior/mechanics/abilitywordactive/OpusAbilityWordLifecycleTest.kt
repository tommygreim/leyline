package leyline.behavior.mechanics.abilitywordactive

import forge.game.card.CounterEnumType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.testkit.*
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.allAnnotations
import leyline.testkit.annotationsOfType
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.detailUint
import leyline.testkit.firstGameObjectByIid
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.OrderingType

class OpusAbilityWordLifecycleTest :
    SessionTest({
        fun MatchFlowHarness.castTargetedSpell(
            spellName: String,
            targetIid: Int,
            orderTriggers: Boolean = false,
        ): List<GREToClientMessage> =
            after {
                castSpellByName(spellName).shouldBeTrue()
                selectTargetsIterative(listOf(targetIid))
                submitTargets()
                // Two Opus triggers flagged OrderDuplicates ask for a stack order.
                if (orderTriggers) respondToSelectN(lastSelectNReq().idsList, OrderingType.OrderAsIndicated)
                passUntilResolved(maxPasses = 20)
            }.messages

        session(
            "below-five Opus trigger has no active marker",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Tackle Artist;Mountain
                humanhand=Shock
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Forest;Forest;Forest
                """,
        ) {
            val source = human.getZone(forge.game.zone.ZoneType.Battlefield).cards.first { it.name == "Tackle Artist" }
            val target = instanceIdOf("Grizzly Bears", ai)

            val messages = castTargetedSpell("Shock", target)

            messages.opusMarkers().shouldBeEmpty()
            source.getCounters(CounterEnumType.P1P1) shouldBe 1
        }

        session(
            "five-plus Opus marker binds player to the exact trigger ability and is deleted on resolution",
            puzzle = opusPuzzle(sourceCount = 1),
        ) {
            val source = human.getZone(forge.game.zone.ZoneType.Battlefield).cards.first { it.name == "Tackle Artist" }
            val sourceIid = instanceIdOf("Tackle Artist")
            val target = instanceIdOf("Grizzly Bears", ai)

            val messages = castTargetedSpell("Unfriendly Fire", target)
            val marker = messages.opusMarkers().single()
            val abilityIid = marker.affectedIdsList.single()
            val triggeringObject =
                messages
                    .persistentAnnotationsOfType(AnnotationType.TriggeringObject)
                    .single { it.affectorId == abilityIid }
            val createdAbilityIids =
                messages
                    .allAnnotations()
                    .filter { AnnotationType.AbilityInstanceCreated in it.typeList }
                    .flatMap { it.affectedIdsList }
            val triggeringSpellIid = triggeringObject.affectedIdsList.single()
            val triggeringSpell = messages.firstGameObjectByIid(triggeringSpellIid)!!
            val counterAdded = messages.annotationsOfType(AnnotationType.CounterAdded).single()
            val resolutionStart =
                messages.annotationsOfType(AnnotationType.ResolutionStart).single { it.affectorId == abilityIid }
            val resolutionComplete =
                messages.annotationsOfType(AnnotationType.ResolutionComplete).single { it.affectorId == abilityIid }
            val abilityDeleted =
                messages.annotationsOfType(AnnotationType.AbilityInstanceDeleted).single {
                    abilityIid in it.affectedIdsList
                }

            assertSoftly {
                marker.affectorId shouldBe 1
                marker.detailsList.map { it.key }.toSet() shouldBe setOf("AbilityWordName")
                createdAbilityIids shouldContainAll listOf(abilityIid)
                triggeringObject.detailInt("source_zone") shouldBe 27
                triggeringSpell.grpId shouldBe 66309
                triggeringSpell.zoneId shouldBe 27
                counterAdded.affectorId shouldBe abilityIid
                counterAdded.affectedIdsList shouldBe listOf(sourceIid)
                counterAdded.detailInt("transaction_amount") shouldBe 2
                resolutionStart.affectorId shouldBe abilityIid
                resolutionStart.detailUint("grpid") shouldBe 204413
                resolutionComplete.affectorId shouldBe abilityIid
                resolutionComplete.detailUint("grpid") shouldBe 204413
                abilityDeleted.affectedIdsList shouldBe listOf(abilityIid)
                messages.deletedPersistentAnnotationIds() shouldContainAll setOf(marker.id, triggeringObject.id)
                source.getCounters(CounterEnumType.P1P1) shouldBe 2
            }
        }

        session(
            "two Opus sources aggregate under one player marker and shrink as abilities resolve",
            puzzle = opusPuzzle(sourceCount = 2),
        ) {
            val target = instanceIdOf("Grizzly Bears", ai)

            val messages = castTargetedSpell("Unfriendly Fire", target, orderTriggers = true)
            val markers = messages.opusMarkers()
            val markerIds = markers.map { it.id }.toSet()
            val fullMarker = markers.maxBy { it.affectedIdsCount }
            val abilityIids = fullMarker.affectedIdsList.toSet()
            val triggerObjects =
                messages
                    .persistentAnnotationsOfType(AnnotationType.TriggeringObject)
                    .filter { it.affectorId in abilityIids }
            val triggeringSpellIids = triggerObjects.map { it.affectedIdsList.single() }.toSet()

            assertSoftly {
                markerIds shouldHaveSize 1
                fullMarker.affectedIdsList shouldHaveSize 2
                markers.any { it.affectedIdsCount == 1 }.shouldBeTrue()
                abilityIids shouldHaveSize 2
                fullMarker.affectorId shouldBe 1
                triggerObjects.map { it.affectorId }.toSet() shouldBe abilityIids
                triggeringSpellIids shouldHaveSize 1
                messages.firstGameObjectByIid(triggeringSpellIids.single())!!.grpId shouldBe 66309
                messages.deletedPersistentAnnotationIds() shouldContainAll markerIds
            }
        }

        session("five-mana non-Opus spell without an Opus source emits no marker", puzzle = opusPuzzle(sourceCount = 0)) {
            val target = instanceIdOf("Grizzly Bears", ai)

            castTargetedSpell("Unfriendly Fire", target).opusMarkers().shouldBeEmpty()
        }
    })

private fun opusPuzzle(sourceCount: Int): String {
    val sources = List(sourceCount) { "Tackle Artist" }.joinToString(";")
    val battlefield =
        listOf(
            sources,
            "Mountain",
            "Mountain",
            "Mountain",
            "Mountain",
            "Mountain",
        ).filter { it.isNotEmpty() }.joinToString(";")
    return """
        ActivePlayer=Human
        ActivePhase=Main1
        HumanLife=20
        AILife=20

        humanbattlefield=$battlefield
        humanhand=Unfriendly Fire
        humanlibrary=Mountain;Mountain;Mountain
        aibattlefield=Grizzly Bears
        ailibrary=Forest;Forest;Forest
    """
}

private fun List<GREToClientMessage>.opusMarkers() =
    persistentAnnotationsOfType(AnnotationType.AbilityWordActive)
        .filter { it.detailString("AbilityWordName") == "Opus" }
