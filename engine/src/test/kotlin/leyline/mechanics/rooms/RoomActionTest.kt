package leyline.mechanics.rooms

import forge.game.ability.AbilityFactory
import forge.game.ability.effects.EffectEffect
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Room (split-room enchantment) cast actions — `CastLeftRoom` (action type 22)
 * and `CastRightRoom` (action type 23).
 *
 * Each emit carries `actionType + instanceId + manaCost` only — door identity
 * is encoded by `actionType` alone. From hand both doors are locked, so both
 * offers fire when payable. From battlefield only the still-locked side(s)
 * appear; once both doors are unlocked nothing surfaces.
 *
 * Test card: Surgical Suite // Hospital Room (grpId 92094). Left door
 * "Surgical Suite" {1}{W}; right door "Hospital Room" {3}{W}.
 */
@Suppress("WeakAssertionOnly")
class RoomActionTest :
    BoardTest({

        fun roomOffersForIid(
            actions: List<Action>,
            instanceId: Int,
        ): List<Action> =
            actions.filter {
                (it.actionType == ActionType.CastLeftRoom || it.actionType == ActionType.CastRightRoom) &&
                    it.instanceId == instanceId
            }

        test("room in hand with both door costs payable → both CastLeft/RightRoom offers") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    // 4 Plains covers right door {3}{W}; left {1}{W} also payable.
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Surgical Suite", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.isRoom }
            val iid = b.instanceId(card)

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snap, b)

            val offers = roomOffersForIid(actions.actionsList, iid)
            offers.map { it.actionType } shouldContainExactlyInAnyOrder
                listOf(ActionType.CastLeftRoom, ActionType.CastRightRoom)
            assertSoftly {
                offers.forEach { offer ->
                    // No grpId / facetId / abilityGrpId / alternativeGrpId — minimal envelope.
                    offer.grpId shouldBe 0
                    offer.facetId shouldBe 0
                    offer.abilityGrpId shouldBe 0
                    offer.alternativeGrpId shouldBe 0
                    offer.manaCostCount shouldNotBe 0
                }
            }
        }

        test("payable Room doors retain left-right order and auto-tap plans for native highlighting") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(4) { addCard("Plains", human, ZoneType.Battlefield) }
                    addCard("Surgical Suite", human, ZoneType.Hand)
                }
            val card = game.humanPlayer.hand.card("Surgical Suite // Hospital Room")
            val iid = b.instanceId(card)
            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val offers = roomOffersForIid(actions.actionsList, iid)
            offers.map { it.actionType } shouldBe listOf(ActionType.CastLeftRoom, ActionType.CastRightRoom)
            offers.forEach { it.autoTapSolution.autoTapActionsCount shouldNotBe 0 }
        }

        test("room in hand with insufficient mana for right door → only left offer") {
            // 2 Plains: left {1}{W} payable, right {3}{W} not.
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Surgical Suite", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.isRoom }
            val iid = b.instanceId(card)

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snap, b)

            val activeLeft =
                actions.actionsList.firstOrNull {
                    it.actionType == ActionType.CastLeftRoom && it.instanceId == iid
                }
            val activeRight =
                actions.actionsList.firstOrNull {
                    it.actionType == ActionType.CastRightRoom && it.instanceId == iid
                }
            val inactiveRight =
                actions.inactiveActionsList.firstOrNull {
                    it.actionType == ActionType.CastRightRoom && it.instanceId == iid
                }
            assertSoftly {
                activeLeft shouldNotBe null
                activeRight shouldBe null
                inactiveRight shouldNotBe null
            }
        }

        test("face-down exiled Room with MayPlay retains both ordered door choices") {
            val (bridge, game, _) =
                startWithBoard { game, human, _ ->
                    val source = addCard("Tablet of Discovery", human, ZoneType.Battlefield)
                    val room = addCard("Surgical Suite", human, ZoneType.Exile)
                    repeat(4) { index ->
                        addCard("Plains", human, ZoneType.Battlefield).setTapped(index >= 2)
                    }
                    source.addRemembered(room)
                    source.setSVar(
                        "TemporaryMayPlay",
                        "Mode\$ Continuous | MayPlay\$ True | EffectZone\$ Command | " +
                            "Affected\$ Card.IsRemembered | AffectedZone\$ Exile",
                    )
                    AbilityFactory
                        .getAbility(
                            "DB\$ Effect | RememberObjects\$ RememberedCard | " +
                                "StaticAbilities\$ TemporaryMayPlay | Duration\$ UntilTheEndOfYourNextTurn",
                            source,
                        ).also { it.activatingPlayer = human }
                        .let { EffectEffect().resolve(it) }
                    source.clearRemembered()
                    game.phaseHandler.devModeSet(PhaseType.MAIN1, human)
                    game.action.checkStaticAbilities(false)
                }
            val player = game.humanPlayer
            val room = player.getZone(ZoneType.Exile).cards.single()
            room.turnFaceDown(true)
            game.action.checkStaticAbilities(false)
            val iid = bridge.instanceId(room)
            val castable = getAllCastableAbilities(room, player)
            val snap = SnapshotCapture.run(game, bridge, "exiled-room", 0)
            val projection = ActionMapper.buildProjectionFromSnapshot(1, snap, bridge)
            val doors = roomOffersForIid(projection.actions.actionsList + projection.actions.inactiveActionsList, iid)

            snap.objects[ForgeCardId(room.id)]?.isRoom shouldBe true
            castable.map { it.cardStateName } shouldContain forge.card.CardStateName.LeftSplit
            castable.map { it.cardStateName } shouldContain forge.card.CardStateName.RightSplit
            doors.map { it.actionType } shouldBe listOf(ActionType.CastLeftRoom, ActionType.CastRightRoom)
            projection.actions.actionsList.any {
                it.instanceId == iid && it.actionType == ActionType.CastLeftRoom
            } shouldBe true
            projection.actions.inactiveActionsList.any {
                it.instanceId == iid && it.actionType == ActionType.CastRightRoom
            } shouldBe true

            player
                .getZone(ZoneType.Battlefield)
                .cards
                .filter { it.name == "Plains" }
                .forEach { it.setTapped(false) }
            val bothPayable =
                ActionMapper.buildProjectionFromSnapshot(
                    1,
                    SnapshotCapture.run(game, bridge, "exiled-room-both-payable", 1),
                    bridge,
                )
            val activeDoors = roomOffersForIid(bothPayable.actions.actionsList, iid)
            activeDoors.map { it.actionType } shouldBe listOf(ActionType.CastLeftRoom, ActionType.CastRightRoom)
            activeDoors.forEach { action ->
                val command =
                    bothPayable.offers
                        .single { it.action == action }
                        .command
                        .shouldBeInstanceOf<PlayerAction.CastSpell>()
                command.ability?.cardStateName shouldBe
                    if (action.actionType == ActionType.CastLeftRoom) {
                        forge.card.CardStateName.LeftSplit
                    } else {
                        forge.card.CardStateName.RightSplit
                    }
            }
        }

        test("room in graveyard → no door cast offers") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Surgical Suite", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Graveyard).cards.first { it.isRoom }
            val iid = b.instanceId(card)

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snap, b)

            roomOffersForIid(actions.actionsList, iid).shouldBeEmpty()
            roomOffersForIid(actions.inactiveActionsList, iid).shouldBeEmpty()
        }

        test("room on battlefield with left already unlocked → only CastRightRoom offered") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Surgical Suite", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Battlefield).cards.first { it.isRoom }
            // Pretend the left door already unlocked. Forge's `unlockRoom` updates state too.
            card.unlockRoom(human, forge.card.CardStateName.LeftSplit)
            val iid = b.instanceId(card)

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snap, b)

            val offers = roomOffersForIid(actions.actionsList, iid)
            offers.map { it.actionType } shouldContainExactlyInAnyOrder listOf(ActionType.CastRightRoom)
        }

        test("room on battlefield with both doors unlocked → no door cast offers") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Surgical Suite", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Battlefield).cards.first { it.isRoom }
            card.unlockRoom(human, forge.card.CardStateName.LeftSplit)
            card.unlockRoom(human, forge.card.CardStateName.RightSplit)
            val iid = b.instanceId(card)

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snap, b)

            roomOffersForIid(actions.actionsList, iid).shouldBeEmpty()
            roomOffersForIid(actions.inactiveActionsList, iid).shouldBeEmpty()
        }

        test("room door offers retain their exact hand abilities") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(5) { addCard("Plains", human, ZoneType.Battlefield) }
                    addCard("Surgical Suite", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.isRoom }
            val iid = b.instanceId(card)
            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val offers = roomOffersForIid(projection.actions.actionsList, iid)

            val castable = leyline.bridge.getAllCastableAbilities(card, human)
            offers.map { it.actionType } shouldContainExactlyInAnyOrder
                listOf(ActionType.CastLeftRoom, ActionType.CastRightRoom)
            assertSoftly {
                offers.forEach { offer ->
                    val command =
                        projection.offers
                            .single { it.action == offer }
                            .command
                            .shouldBeInstanceOf<PlayerAction.CastSpell>()
                    command.ability shouldNotBe null
                    command.abilityId shouldBe castable.indexOfFirst { it === command.ability }
                    command.ability?.cardStateName shouldBe
                        if (offer.actionType == ActionType.CastLeftRoom) {
                            forge.card.CardStateName.LeftSplit
                        } else {
                            forge.card.CardStateName.RightSplit
                        }
                }
            }
        }

        test("StateMapper emits LeftUnlocked Designation pAnn for bf room with left door open") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Surgical Suite", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Battlefield).cards.first { it.isRoom }
            card.unlockRoom(human, forge.card.CardStateName.LeftSplit)
            val iid = b.instanceId(card)

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val result =
                leyline.testkit.StateMapperShell
                    .buildFromSnapshot(
                        snap,
                        0,
                        "test",
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    )

            val designations =
                result.gsm.persistentAnnotationsList.filter { ann ->
                    wotc.mtgo.gre.external.messaging.Messages.AnnotationType.Designation in ann.typeList &&
                        ann.affectedIdsList.contains(iid)
                }
            val designationTypes =
                designations.flatMap { ann ->
                    ann.detailsList
                        .filter { it.key == "DesignationType" && it.valueInt32Count > 0 }
                        .map { it.getValueInt32(0) }
                }
            assertSoftly {
                designationTypes shouldContain 19 // LeftUnlocked
                designationTypes shouldNotContain 20 // Right not unlocked
            }
        }

        test("snapshot exposes door state for battlefield room") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Surgical Suite", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Battlefield).cards.first { it.isRoom }
            card.unlockRoom(human, forge.card.CardStateName.RightSplit)

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects[ForgeCardId(card.id)]
            assertSoftly {
                cardSnap shouldNotBe null
                cardSnap!!.isRoom shouldBe true
                cardSnap.isLeftDoorUnlocked shouldBe false
                cardSnap.isRightDoorUnlocked shouldBe true
            }
        }

        test("off-stack room resolves to the parent grpId even with a door unlocked") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Surgical Suite", human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Battlefield).cards.first { it.isRoom }
            card.unlockRoom(human, forge.card.CardStateName.LeftSplit)

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(ForgeCardId(card.id))

            assertSoftly {
                // Premise: unlocking a door flips the active face to the door —
                // off-stack identity must stay the parent (Original) face.
                card.currentState?.stateName shouldBe forge.card.CardStateName.LeftSplit
                cardSnap.grpId shouldBe 92094 // parent grpId, not the 92095 door
            }
        }
    })
