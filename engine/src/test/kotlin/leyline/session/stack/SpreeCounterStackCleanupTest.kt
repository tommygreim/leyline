package leyline.session.stack

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class SpreeCounterStackCleanupTest :
    SessionTest({
        session(
            "Three Steps Ahead counter removes both spell objects from the client stack",
            forgeCatalog = true,
            puzzleFile = "data/puzzles/three-steps-counter-cleanup.pzl",
            aiScript = listOf(ScriptedAction.CastSpell("Tablet of Discovery"), ScriptedAction.PassPriority),
        ) {
            val cto = castSpellUntilCastingTimeOptionsReq("Three Steps Ahead")
            val counterMode =
                cto
                    .getCastingTimeOptionReq(0)
                    .modalReq
                    .getModalOptions(0)
                    .grpId
            respondModalChoice(listOf(counterMode))
            val tabletStackId =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq
                    .targetsList
                    .flatMap { it.targetsList }
                    .first { it.targetInstanceId > OPPONENT_SEAT }
                    .targetInstanceId
            selectTargets(listOf(tabletStackId))
            passUntilResolved(maxPasses = 10)

            val exits =
                allMessages
                    .gameStateMessages()
                    .flatMap { it.annotationsList }
                    .filter { AnnotationType.ZoneTransfer_af5a in it.typeList && it.detailInt("zone_src") == ZoneIds.STACK }
            exits.map { it.detailString("category") }.takeLast(2) shouldBe listOf("Countered", "Resolve")
            val finalStack =
                allMessages
                    .gameStateMessages()
                    .last()
                    .zonesList
                    .firstOrNull { it.zoneId == ZoneIds.STACK }
            (finalStack == null || finalStack.objectInstanceIdsList.isEmpty()).shouldBeTrue()
        }
    })
