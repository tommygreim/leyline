package leyline.session.mechanics

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.gameStateMessages
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/** A single discard event can queue several Monument triggers; each choice must see prior modes. */
class MonumentModeRestrictionTest :
    SessionTest({
        session(
            "Monument excludes a mode chosen by an earlier trigger this turn",
            forgeCatalog = true,
            puzzleFile = "data/puzzles/monument-mode-restriction.pzl",
        ) {
            castSpellByName("Faithless Looting").shouldBeTrue()
            passUntil(maxPasses = 8) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val discard = lastSelectNReq()
            discard.maxSel shouldBe 2
            respondToSelectN(discard.idsList.take(2))
            passUntil(maxPasses = 8) { allMessages.any { it.hasCastingTimeOptionsReq() } }.shouldBeTrue()
            val first =
                allMessages
                    .last { it.hasCastingTimeOptionsReq() }
                    .castingTimeOptionsReq
                    .getCastingTimeOptionReq(0)
                    .modalReq
            first.modalOptionsCount shouldBe 3
            val drawMode = first.getModalOptions(0).grpId
            respondModalChoice(listOf(drawMode))
            passUntil(maxPasses = 8) { allMessages.count { it.hasCastingTimeOptionsReq() } >= 2 }.shouldBeTrue()
            val second =
                allMessages
                    .last { it.hasCastingTimeOptionsReq() }
                    .castingTimeOptionsReq
                    .getCastingTimeOptionReq(0)
                    .modalReq
            second.modalOptionsList.map { it.grpId }.contains(drawMode) shouldBe false
            second.excludedOptionsList.map { it.grpId } shouldContain drawMode
            val exhausted =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.AbilityExhausted)
                    .last { drawMode in it.detailIntList("AbilityGrpId") }
            val source =
                allMessages
                    .gameStateMessages()
                    .flatMap { it.gameObjectsList }
                    .last { it.instanceId == exhausted.affectorId }
            source.uniqueAbilitiesList.any { it.id == exhausted.detailInt("UniqueAbilityId") }.shouldBeTrue()
        }
    })
