package leyline.mechanics.dungeon

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.game.codes.DetailKeys
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.detailIntList
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType

class DungeonLifecycleTest :
    SessionTest({
        session(
            "Mad Mage third venture offers distinct rooms after gain life and scry",
            puzzleFile = "data/puzzles/dungeon-venture-controls.pzl",
            fullControl = true,
        ) {
            castVenture()
            val picker = lastSelectNReq()
            picker.idType shouldBe IdType.CardGrpId
            picker.listType shouldBe SelectionListType.Dynamic
            picker.idsList.toSet() shouldBe setOf(78768, 78769, 78770)
            respondToSelectN(listOf(78768))
            passUntil { human.life == 21 }.shouldBeTrue()
            human.life shouldBe 21
            assertRoom(146073)

            castVenture()
            passUntil { allMessages.any { it.hasGroupReq() } }.shouldBeTrue()
            val scry = lastGroupReq()
            respondToScry(emptyList(), scry.instanceIdsList)
            finishRoom()
            assertRoom(146074)

            castVenture()
            val rooms = lastSelectNReq()
            // The branch browser needs the retained dungeon and previous room,
            // even though this prompt frame did not itself advance the dungeon.
            assertClientDungeon(78768, 146074)
            assertSoftly {
                rooms.idType shouldBe IdType.AbilityGrpId
                rooms.listType shouldBe SelectionListType.Dynamic
                rooms.idsList shouldBe listOf(146075, 146076)
            }
            chooseAndFinishRoom(146075)
            human.battlefield.card("Treasure Token").isToken shouldBe true
            assertRoom(146075)

            val stackRooms = stackRoomIds()
            stackRooms shouldContain 146073
            stackRooms shouldContain 146074
            stackRooms shouldContain 146075
            isGameOver() shouldBe false
        }

        session(
            "Lost Mine branches complete and Dungeon Map starts a new dungeon",
            puzzleFile = "data/puzzles/dungeon-venture-controls.pzl",
            fullControl = true,
        ) {
            castVenture()
            respondToSelectN(listOf(78769))
            passUntil { allMessages.any { it.hasGroupReq() } }.shouldBeTrue()
            respondToScry(emptyList(), lastGroupReq().instanceIdsList)
            finishRoom()
            assertRoom(146082)

            castVenture()
            lastSelectNReq().idsList shouldBe listOf(146083, 146084)
            chooseAndFinishRoom(146084)
            assertRoom(146084)

            castVenture()
            lastSelectNReq().idsList shouldBe listOf(146086, 146087)
            respondToSelectN(listOf(146086))
            passUntil { human.life == 21 && ai.life == 19 }.shouldBeTrue()
            assertRoom(146086)
            assertSoftly {
                human.life shouldBe 21
                ai.life shouldBe 19
            }

            castVenture()
            finishRoom()
            human.hand.card("Mountain").name shouldBe "Mountain"
            val completed = SnapshotCapture.run(game(), bridge, "dungeon-test", 1).dungeonStates.getValue(SeatId(1))
            completed.currentDungeonGrpId shouldBe 0
            completed.completedDungeonGrpIds shouldContain 78769
            assertClientDungeon(0, 0)
            clientDungeonStatus().detailIntList(DetailKeys.ALL_DUNGEONS_COMPLETED) shouldBe listOf(78769)
            stackRoomIds() shouldContain 146088

            activateAbility("Dungeon Map").shouldBeTrue()
            passUntilResolved()
            lastSelectNReq().idType shouldBe IdType.CardGrpId
            lastSelectNReq().listType shouldBe SelectionListType.Dynamic
            val freshEntry = messageSnapshot()
            respondToSelectN(listOf(78769))
            passUntil { messagesSince(freshEntry).any { it.hasGroupReq() } }.shouldBeTrue()
            respondToScry(emptyList(), lastGroupReq().instanceIdsList)
            finishRoom()
            assertRoom(146082)
        }

        session(
            "Lost Mine Goblin and targeted Storeroom effects preserve their distinct stack rows",
            puzzleFile = "data/puzzles/dungeon-venture-controls.pzl",
            fullControl = true,
        ) {
            castVenture()
            respondToSelectN(listOf(78769))
            passUntil { allMessages.any { it.hasGroupReq() } }.shouldBeTrue()
            respondToScry(emptyList(), lastGroupReq().instanceIdsList)
            finishRoom()

            castVenture()
            chooseAndFinishRoom(146083)
            human.battlefield.card("Goblin Token").netPower shouldBe 1
            assertRoom(146083)

            castVenture()
            lastSelectNReq().idsList shouldBe listOf(146085, 146086)
            val selected = messageSnapshot()
            respondToSelectN(listOf(146085))
            passUntil { messagesSince(selected).any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
            finishRoom(selected)
            human.battlefield.card("Grizzly Bears").netPower shouldBe 6
            assertRoom(146085)

            castVenture()
            finishRoom()
            human.battlefield.card("Grizzly Bears").netPower shouldBe 7
            human.hand.card("Mountain").name shouldBe "Mountain"
            stackRoomIds() shouldContain 146083
            stackRoomIds() shouldContain 146085
            stackRoomIds() shouldContain 146088
        }
    })

private fun MatchFlowHarness.castVenture() {
    castSpellByName("Kick in the Door").shouldBeTrue()
    selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
    passUntilResolved()
}

private fun MatchFlowHarness.chooseAndFinishRoom(id: Int) {
    val start = messageSnapshot()
    respondToSelectN(listOf(id))
    finishRoom(start)
}

private fun MatchFlowHarness.finishRoom(messageStart: Int = 0) {
    passUntil {
        val pending = bridge.actionBridge(SeatId(1)).getPending()
        pending?.state?.kind == PendingActionKind.PRIORITY &&
            pendingActionHorizonPublished(pending, messageStart) &&
            game().stack.isEmpty &&
            !bridge.hasPendingNonActionInteraction()
    }.shouldBeTrue()
}

private fun MatchFlowHarness.assertRoom(expected: Int) {
    val dungeon = human.getCardsIn(ZoneType.Command).single { it.type.isDungeon }
    val room = dungeon.triggers.single { it.overridingAbility.getParam("RoomName") == dungeon.currentRoom }
    bridge.resolveAbilityIdentity(dungeon, room.overridingAbility)?.abilityGrpId shouldBe expected
    SnapshotCapture
        .run(game(), bridge, "dungeon-test", 1)
        .dungeonStates
        .getValue(SeatId(1))
        .currentRoomGrpId shouldBe expected
    assertClientDungeon(bridge.resolveGrpId(dungeon), expected)
}

private fun MatchFlowHarness.clientDungeonStatus() =
    accumulator.persistentAnnotations.values.single { AnnotationType.DungeonStatus in it.typeList && it.affectorId == 1 }

private fun MatchFlowHarness.assertClientDungeon(
    dungeonGrpId: Int,
    roomGrpId: Int,
) {
    val status = clientDungeonStatus()
    assertSoftly {
        status.detailIntList(DetailKeys.CURRENT_DUNGEON) shouldBe listOf(dungeonGrpId)
        status.detailIntList(DetailKeys.CURRENT_ROOM) shouldBe listOf(roomGrpId)
        if (dungeonGrpId != 0) {
            val dungeonIid = status.detailIntList(DetailKeys.CURRENT_DUNGEON_ZCID).single()
            accumulator.objects.getValue(dungeonIid).grpId shouldBe dungeonGrpId
        }
    }
}

private fun MatchFlowHarness.stackRoomIds(): List<Int> =
    allMessages
        .filter { it.hasGameStateMessage() }
        .flatMap { it.gameStateMessage.gameObjectsList }
        .filter { it.type == GameObjectType.Ability }
        .map { it.grpId }
