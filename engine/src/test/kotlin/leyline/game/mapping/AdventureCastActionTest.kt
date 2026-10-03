package leyline.game.mapping

import forge.game.ability.AbilityFactory
import forge.game.ability.effects.EffectEffect
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.ActionManaCosts
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.getPlayableManaAbilities
import leyline.bridge.handoff.PlayerAction
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.haveManaCost
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class AdventureCastActionTest :
    BoardTest({

        test("adventure card in hand produces both Cast and CastAdventure actions") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                    repeat(3) { addCard("Mountain", human, ZoneType.Battlefield) }
                }

            val creatureGrpId =
                b.cardRepository.findGrpIdByName("Ratcatcher Trainee")
                    ?: error("Ratcatcher Trainee not in card registry")
            val trainee =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Ratcatcher Trainee" }
            val traineeIid = b.instanceId(trainee)

            val actions =
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = GsmSnapshot.capture(game, b, "test", 0),
                    bridge = b,
                )

            val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
            val adventureActions = actions.actionsList.filter { it.actionType == ActionType.CastAdventure }

            castActions shouldHaveSize 1

            adventureActions shouldHaveSize 1
            val adv = adventureActions[0]
            assertSoftly {
                adv.instanceId shouldBe traineeIid
                // grpId = creature face (client can't resolve IsPrimaryCard=0 adventure faces)
                adv.grpId shouldBe creatureGrpId
                adv should haveManaCost(generic = 2, red = 1)
            }
        }

        test("non-adventure card produces no CastAdventure") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    repeat(2) { addCard("Forest", human, ZoneType.Battlefield) }
                }

            val actions =
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = GsmSnapshot.capture(game, b, "test", 0),
                    bridge = b,
                )

            actions.actionsList.filter { it.actionType == ActionType.CastAdventure } shouldHaveSize 0
            actions.inactiveActionsList.filter { it.actionType == ActionType.CastAdventure } shouldHaveSize 0
        }

        test("land with an adventure offers both the land play and the instant face") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Lindblum, Industrial Regency", human, ZoneType.Hand)
                    repeat(3) { addCard("Mountain", human, ZoneType.Battlefield) }
                }

            val actions = ActionMapper.buildFromSnapshot(1, GsmSnapshot.capture(game, b, "test", 0), b)
            actions.actionsList.filter { it.actionType == ActionType.Play_add3 } shouldHaveSize 1
            actions.actionsList.filter { it.actionType == ActionType.CastAdventure } shouldHaveSize 1
        }

        test("adventure remains an active offer when the creature face is unaffordable") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Gandalf, Goblins' Bane", human, ZoneType.Hand)
                    repeat(2) { addCard("Mountain", human, ZoneType.Battlefield) }
                }

            val actions = ActionMapper.buildFromSnapshot(1, GsmSnapshot.capture(game, b, "test", 0), b)
            val adventure = actions.actionsList.filter { it.actionType == ActionType.CastAdventure }
            adventure shouldHaveSize 1
            adventure.single().autoTapSolution.autoTapActionsCount shouldBe 2
            actions.actionsList.filter { it.actionType == ActionType.Cast } shouldHaveSize 0
            actions.inactiveActionsList.filter { it.actionType == ActionType.Cast } shouldHaveSize 1
        }

        test("sorcery adventure can use mana restricted to instant and sorcery spells") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Gandalf, Goblins' Bane", human, ZoneType.Hand)
                    addCard("Godless Shrine", human, ZoneType.Battlefield)
                    addCard("Great Hall of the Biblioplex", human, ZoneType.Battlefield)
                }

            val actions = ActionMapper.buildFromSnapshot(1, GsmSnapshot.capture(game, b, "test", 0), b)
            val human = game.humanPlayer
            val gandalf = human.getZone(ZoneType.Hand).cards.single()
            val adventureAbility = getAllCastableAbilities(gandalf, human).single { it.isAdventure }
            val hall = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Great Hall of the Biblioplex" }
            val hallRestrictedMana = getPlayableManaAbilities(hall, human).single { it.manaPart?.manaRestrictions?.isNotBlank() == true }
            hallRestrictedMana.manaPart.meetsManaRestrictions(adventureAbility) shouldBe true
            hallRestrictedMana.manaPart.meetsManaRestrictions(
                getAllCastableAbilities(gandalf, human).single { !it.isAdventure },
            ) shouldBe false
            ActionManaCosts.canPayManaCost(adventureAbility, human) shouldBe true
            val adventure = actions.actionsList.filter { it.actionType == ActionType.CastAdventure }
            adventure shouldHaveSize 1
            adventure.single().autoTapSolution.autoTapActionsCount shouldBe 2
            adventure
                .single()
                .autoTapSolution.autoTapActionsList
                .flatMap { it.manaPaymentOption.manaList }
                .single { it.srcInstanceId == b.instanceId(hall) }
                .color shouldBe ManaColor.Red_afc9
            actions.actionsList.filter { it.actionType == ActionType.Cast } shouldHaveSize 0
        }

        test("restricted instant and sorcery mana cannot pay for the adventure card's creature face") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Gandalf, Goblins' Bane", human, ZoneType.Hand)
                    repeat(2) { addCard("Plains", human, ZoneType.Battlefield) }
                    addCard("Great Hall of the Biblioplex", human, ZoneType.Battlefield)
                }

            val actions = ActionMapper.buildFromSnapshot(1, GsmSnapshot.capture(game, b, "test", 0), b)
            actions.actionsList.filter { it.actionType == ActionType.CastAdventure } shouldHaveSize 1
            actions.actionsList.filter { it.actionType == ActionType.Cast } shouldHaveSize 0
            actions.inactiveActionsList.filter { it.actionType == ActionType.Cast } shouldHaveSize 1
        }

        test("MayPlay-granted face-down exiled Adventure offers its affordable spell face") {
            val (bridge, game, _) =
                startWithBoard { game, human, _ ->
                    val source = addCard("Gandalf, Goblins' Bane", human, ZoneType.Battlefield)
                    val exiled = addCard("Gandalf, Goblins' Bane", human, ZoneType.Exile)
                    repeat(2) { addCard("Mountain", human, ZoneType.Battlefield) }
                    source.addRemembered(exiled)
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
            val human = game.humanPlayer
            val exiled = human.getZone(ZoneType.Exile).cards.single()
            exiled.turnFaceDown(true)
            game.action.checkStaticAbilities(false)
            val iid = bridge.instanceId(exiled)
            val castable = getAllCastableAbilities(exiled, human)
            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, bridge, "exiled-adventure", 0), bridge)

            castable.any { it.isAdventure } shouldBe true
            projection.actions.actionsList.any { it.instanceId == iid && it.actionType == ActionType.CastAdventure } shouldBe true
            projection.actions.inactiveActionsList.any { it.instanceId == iid && it.actionType == ActionType.Cast } shouldBe true
            val adventure = projection.actions.actionsList.single { it.instanceId == iid && it.actionType == ActionType.CastAdventure }
            projection.offers
                .single { it.action == adventure }
                .command
                .shouldBeInstanceOf<PlayerAction.CastSpell>()
                .ability
                ?.isAdventure shouldBe true
        }

        test("MayPlay-granted exiled land Adventure offers both land and spell") {
            val (bridge, game, _) =
                startWithBoard { game, human, _ ->
                    val source = addCard("Tablet of Discovery", human, ZoneType.Battlefield)
                    val exiled = addCard("Lindblum, Industrial Regency", human, ZoneType.Exile)
                    repeat(3) { addCard("Mountain", human, ZoneType.Battlefield) }
                    source.addRemembered(exiled)
                    source.setSVar(
                        "GrantBothFaces",
                        "Mode\$ Continuous | MayPlay\$ True | EffectZone\$ Command | " +
                            "Affected\$ Card.IsRemembered | AffectedZone\$ Exile",
                    )
                    AbilityFactory
                        .getAbility("DB\$ Effect | RememberObjects\$ RememberedCard | StaticAbilities\$ GrantBothFaces", source)
                        .also { it.activatingPlayer = human }
                        .let { EffectEffect().resolve(it) }
                    source.clearRemembered()
                    game.phaseHandler.devModeSet(PhaseType.MAIN1, human)
                    game.action.checkStaticAbilities(false)
                }
            val card = game.humanPlayer.exile.card("Lindblum, Industrial Regency")
            val iid = bridge.instanceId(card)
            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, bridge, "land-adventure", 0), bridge)
            val offers = projection.offers.filter { it.action.instanceId == iid }
            offers.any { it.action.actionType == ActionType.Play_add3 } shouldBe true
            offers.any { it.action.actionType == ActionType.CastAdventure } shouldBe true
        }

        test("unaffordable adventure action cost does not require pre-seeded activator") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                }

            val actions =
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = GsmSnapshot.capture(game, b, "test", 0),
                    bridge = b,
                )

            actions.inactiveActionsList
                .filter { it.actionType == ActionType.CastAdventure }
                .shouldHaveSize(1)
        }
    })
