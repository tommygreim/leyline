package leyline.session.stack

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.Phase

class SpreeCounterStackCleanupTest :
    SessionTest({
        session(
            "Counter cleanup fixture waits at a human main phase before any opponent cast",
            forgeCatalog = true,
            puzzleFile = "data/puzzles/three-steps-counter-cleanup.pzl",
        ) {
            val opening = allMessages.gameStateMessages().first()
            assertSoftly {
                opening.turnInfo.activePlayer shouldBe HUMAN_SEAT
                opening.turnInfo.phase shouldBe Phase.Main1_a549
                opening.zonesList.single { it.zoneId == ZoneIds.STACK }.objectInstanceIdsCount shouldBe 0
            }
            val forestId = human.hand.iid("Forest")
            allMessages
                .last { it.hasActionsAvailableReq() }
                .actionsAvailableReq.actionsList
                .any { it.actionType == ActionType.Play_add3 && it.instanceId == forestId }
                .shouldBeTrue()
        }

        session(
            "Three Steps Ahead counter removes both spell objects from the client stack",
            forgeCatalog = true,
            puzzleFile = "data/puzzles/three-steps-counter-cleanup.pzl",
            aiScript = listOf(ScriptedAction.CastSpell("Tablet of Discovery"), ScriptedAction.PassPriority),
        ) {
            passUntil(maxPasses = 20) { game().stackZone.size() > 0 }.shouldBeTrue()
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
            val cleanupFrame =
                allMessages.gameStateMessages().single { frame ->
                    frame.annotationsList.any {
                        AnnotationType.ZoneTransfer_af5a in it.typeList && it.detailString("category") == "Countered"
                    }
                }
            val countered = exits.single { it.detailString("category") == "Countered" }
            val resolvingId =
                cleanupFrame.annotationsList.single { AnnotationType.ResolutionStart in it.typeList }.affectorId
            val movedIds = exits.flatMap { it.affectedIdsList }.toSet()
            val idChanges =
                cleanupFrame.annotationsList.withIndex().filter { (_, annotation) ->
                    AnnotationType.ObjectIdChanged in annotation.typeList && annotation.detailInt("new_id") in movedIds
                }
            assertSoftly {
                countered.affectorId shouldBe resolvingId
                idChanges.size shouldBe 2
                for ((index, idChange) in idChanges) {
                    // Arena combines an identity change only with the immediately following transfer.
                    val next = cleanupFrame.getAnnotations(index + 1)
                    next.typeList shouldBe listOf(AnnotationType.ZoneTransfer_af5a)
                    next.affectedIdsList shouldBe listOf(idChange.detailInt("new_id"))
                }
                cleanupFrame.zonesList.single { it.zoneId == ZoneIds.STACK }.objectInstanceIdsList shouldBe emptyList()
            }
            val finalStack =
                allMessages
                    .gameStateMessages()
                    .flatMap { it.zonesList }
                    .last { it.zoneId == ZoneIds.STACK }
            finalStack.objectInstanceIdsList shouldBe emptyList()
        }
    })
