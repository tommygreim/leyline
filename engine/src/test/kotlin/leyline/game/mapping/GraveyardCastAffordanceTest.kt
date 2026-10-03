package leyline.game.mapping

import forge.game.ability.AbilityFactory
import forge.game.ability.effects.EffectEffect
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.bridge.ActionAvailability
import leyline.bridge.ActionManaCosts
import leyline.bridge.coord.SpellExecutor
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.ForgeCardId
import leyline.game.bundle.GsmBuilder
import leyline.game.bundle.GsmFrame
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * A graveyard cast rail is a card affordance, not a mana reservation.  Arena
 * keeps Flashback cards beside the hand when their cast is currently
 * unaffordable, so tapping/untapping mana must only change active vs inactive
 * action state and must not make the card disappear from the hand rail.
 */
class GraveyardCastAffordanceTest :
    BoardTest({
        test("unaffordable Flashback remains represented as an inactive cast") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Think Twice", human, ZoneType.Graveyard)
                }
            val card =
                game.players
                    .first()
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .single()
            val iid = bridge.instanceId(card)

            val actions =
                ActionMapper.buildFromSnapshot(
                    1,
                    SnapshotCapture.run(game, bridge, "graveyard-affordance", 0),
                    bridge,
                )

            actions.actionsList.none { it.actionType == ActionType.Cast && it.instanceId == iid } shouldBe true
            actions.inactiveActionsList
                .filter { it.actionType == ActionType.Cast && it.instanceId == iid }
                .shouldHaveSize(1)
        }

        test("Flashback representation survives mana-source state changes") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Think Twice", human, ZoneType.Graveyard)
                    addCard("Island", human, ZoneType.Battlefield)
                }
            val card =
                game.players
                    .first()
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .single()
            val iid = bridge.instanceId(card)

            fun castActions() =
                ActionMapper
                    .buildFromSnapshot(
                        1,
                        SnapshotCapture.run(game, bridge, "graveyard-affordance", 0),
                        bridge,
                    ).let { req ->
                        (req.actionsList + req.inactiveActionsList).filter {
                            it.actionType == ActionType.Cast && it.instanceId == iid
                        }
                    }

            castActions().shouldHaveSize(1)
            game.players
                .first()
                .getZone(ZoneType.Battlefield)
                .cards
                .single()
                .setTapped(true)
            assertSoftly {
                castActions().shouldHaveSize(1)
                castActions().single().actionType shouldBe ActionType.Cast
            }
        }

        test("transition action list keeps a graveyard Flashback card beside the hand") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Think Twice", human, ZoneType.Graveyard)
                }
            val card =
                game.players
                    .first()
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .single()
            val iid = bridge.instanceId(card)
            val actions =
                ActionMapper.buildNaiveActionsFromSnapshot(
                    1,
                    SnapshotCapture.run(game, bridge, "graveyard-affordance", 0),
                    bridge,
                )

            actions.actionsList
                .filter { it.actionType == ActionType.Cast && it.instanceId == iid }
                .shouldHaveSize(1)
        }

        test("opponent graveyard permissions never leak onto the local hand rail") {
            val (bridge, game, _) =
                startWithBoard { _, _, ai ->
                    addCard("Winternight Stories", ai, ZoneType.Graveyard)
                }
            val opponentCard =
                game.players[1]
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .single()
            val opponentIid = bridge.instanceId(opponentCard)

            val actions =
                ActionMapper.buildNaiveActionsFromSnapshot(
                    1,
                    SnapshotCapture.run(game, bridge, "opponent-graveyard-affordance", 0),
                    bridge,
                )

            (actions.actionsList + actions.inactiveActionsList).none {
                it.instanceId == opponentIid && (it.actionType == ActionType.Cast || it.actionType == ActionType.Play_add3)
            } shouldBe true
        }

        test("static MayPlay permission keeps an unaffordable graveyard spell represented") {
            val (bridge, game, _) =
                startWithBoard { game, human, _ ->
                    val tablet = addCard("Tablet of Discovery", human, ZoneType.Battlefield)
                    // Deliberately has no Flashback or other alternate-zone
                    // keyword: the action must come from MayPlay itself.
                    addCard("Grizzly Bears", human, ZoneType.Graveyard)
                    // This is the same static permission shape Tablet's
                    // remembered-card effect produces after its ETB trigger.
                    tablet.addStaticAbility(
                        "Mode\$ Continuous | Affected\$ Card.YouCtrl | " +
                            "AffectedZone\$ Graveyard | MayPlay\$ True",
                    )
                    game.action.checkStaticAbilities(false)
                }
            val card =
                game.players
                    .first()
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .single()
            val iid = bridge.instanceId(card)
            val req =
                ActionMapper.buildFromSnapshot(
                    1,
                    SnapshotCapture.run(game, bridge, "graveyard-mayplay", 0),
                    bridge,
                )

            req.actionsList.none { it.actionType == ActionType.Cast && it.instanceId == iid } shouldBe true
            req.inactiveActionsList
                .single { it.actionType == ActionType.Cast && it.instanceId == iid }
                .manaCostList shouldHaveSize 2
        }

        test("temporary remembered-card MayPlay is active from exile when mana is available") {
            val (bridge, game, _) =
                startWithBoard { game, human, _ ->
                    val source = addCard("Tablet of Discovery", human, ZoneType.Battlefield)
                    val exiled = addCard("Grizzly Bears", human, ZoneType.Exile)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
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
            val card =
                game.players
                    .first()
                    .getZone(ZoneType.Exile)
                    .cards
                    .single()
            val player = game.players.first()
            val iid = bridge.instanceId(card.id)
            val castable = getAllCastableAbilities(card, player)
            val req =
                ActionMapper.buildFromSnapshot(
                    1,
                    SnapshotCapture.run(game, bridge, "temporary-exile-mayplay", 0),
                    bridge,
                )

            assertSoftly {
                card
                    .mayPlay(player)
                    .single()
                    .grantsZonePermissions()
                    .shouldBeTrue()
                castable.shouldHaveSize(1)
                castable.single().canPlay().shouldBeTrue()
                (castable.single().payCosts?.canPay(castable.single(), player, false) != false).shouldBeTrue()
                ActionAvailability.hasLegalTargetsAndModes(castable.single()).shouldBeTrue()
                ActionManaCosts.canPayManaCost(castable.single(), player).shouldBeTrue()
                ActionAvailability.canExecute(castable.single(), player).shouldBeTrue()
                req.actionsList
                    .filter { it.actionType == ActionType.Cast && it.instanceId == iid }
                    .shouldHaveSize(1)
                req.inactiveActionsList.none { it.actionType == ActionType.Cast && it.instanceId == iid } shouldBe true
            }
        }

        test("static MayPlay permission keeps a graveyard land represented outside land-play timing") {
            val (bridge, game, _) =
                startWithBoard { game, human, ai ->
                    val tablet = addCard("Tablet of Discovery", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Graveyard)
                    tablet.addStaticAbility(
                        "Mode\$ Continuous | Affected\$ Card.YouCtrl | " +
                            "AffectedZone\$ Graveyard | MayPlay\$ True",
                    )
                    game.phaseHandler.devModeSet(PhaseType.MAIN1, ai)
                    game.action.checkStaticAbilities(false)
                }
            val card =
                game.players
                    .first()
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .single()
            val iid = bridge.instanceId(card)
            val req =
                ActionMapper.buildFromSnapshot(
                    1,
                    SnapshotCapture.run(game, bridge, "graveyard-mayplay", 0),
                    bridge,
                )

            req.actionsList.none { it.actionType == ActionType.Play_add3 && it.instanceId == iid } shouldBe true
            req.inactiveActionsList
                .filter { it.actionType == ActionType.Play_add3 && it.instanceId == iid }
                .shouldHaveSize(1)
        }

        test("Tablet MayPlay land stays represented inactive after this turn's land drop") {
            val (bridge, game, _) =
                startWithBoard { game, human, _ ->
                    val tablet = addCard("Tablet of Discovery", human, ZoneType.Battlefield)
                    val milledLand = addCard("Mountain", human, ZoneType.Graveyard)
                    tablet.addRemembered(milledLand)
                    tablet.setSVar(
                        "TabletMayPlay",
                        "Mode\$ Continuous | MayPlay\$ True | EffectZone\$ Command | " +
                            "Affected\$ Card.IsRemembered | AffectedZone\$ Graveyard,Exile",
                    )
                    AbilityFactory
                        .getAbility(
                            "DB\$ Effect | RememberObjects\$ RememberedCard | StaticAbilities\$ TabletMayPlay",
                            tablet,
                        ).also { it.activatingPlayer = human }
                        .let { EffectEffect().resolve(it) }
                    tablet.clearRemembered()
                    game.phaseHandler.devModeSet(PhaseType.MAIN1, human)
                    // Match the live sequence: the land drop has already been spent,
                    // so Forge must expose the Tablet permission as inactive only.
                    human.setLandsPlayedThisTurn(1)
                    game.action.checkStaticAbilities(false)
                }
            val card =
                game.players
                    .first()
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .single()
            val iid = bridge.instanceId(card.id)
            val snap = SnapshotCapture.run(game, bridge, "tablet-mayplay-land-drop-spent", 0)
            val req = ActionMapper.buildFromSnapshot(1, snap, bridge)

            req.actionsList.none { it.actionType == ActionType.Play_add3 && it.instanceId == iid } shouldBe true
            req.inactiveActionsList
                .filter { it.actionType == ActionType.Play_add3 && it.instanceId == iid }
                .shouldHaveSize(1)

            val gsm =
                GsmBuilder.embedActions(
                    GameStateMessage.newBuilder().setGameStateId(1).build(),
                    req,
                    GsmFrame.from(snap),
                    recipientSeatId = 1,
                )
            gsm.actionsList
                .filter { it.action.actionType == ActionType.Play_add3 && it.action.instanceId == iid }
                .shouldHaveSize(1)
        }

        listOf("Demolition Field", "Mountain").forEach { landName ->
            test("Tablet's remembered $landName stays actionable through later priority frames") {
                val board =
                    startWithBoard { game, human, _ ->
                        val tablet = addCard("Tablet of Discovery", human, ZoneType.Battlefield)
                        val milledLand = addCard(landName, human, ZoneType.Graveyard)
                        addCard(landName, human, ZoneType.Hand)
                        // Tablet's ETB creates a command-zone effect that remembers
                        // the milled card. Resolve that same Effect shape rather than
                        // attaching a convenience static ability to Tablet itself.
                        // The two same-name lands remain in distinct zones so an
                        // identity mix-up cannot pass unnoticed.
                        tablet.addRemembered(milledLand)
                        tablet.setSVar(
                            "TabletMayPlay",
                            "Mode\$ Continuous | MayPlay\$ True | EffectZone\$ Command | " +
                                "Affected\$ Card.IsRemembered | AffectedZone\$ Graveyard,Exile",
                        )
                        AbilityFactory
                            .getAbility(
                                "DB\$ Effect | RememberObjects\$ RememberedCard | StaticAbilities\$ TabletMayPlay",
                                tablet,
                            ).also { it.activatingPlayer = human }
                            .let { EffectEffect().resolve(it) }
                        tablet.clearRemembered()
                        game.phaseHandler.devModeSet(PhaseType.MAIN1, human)
                        game.action.checkStaticAbilities(false)
                    }
                val milledLand = board.human.graveyard.card(landName)
                val handLand = board.human.hand.card(landName)
                val milledIid = board.bridge.instanceId(milledLand.id)

                val mayPlayOption = milledLand.mayPlay(board.human).single()
                mayPlayOption.grantsZonePermissions().shouldBeTrue()

                repeat(2) { frame ->
                    val actions =
                        ActionMapper.buildFromSnapshot(
                            1,
                            SnapshotCapture.run(board.game, board.bridge, "tablet-mayplay-$frame", frame),
                            board.bridge,
                        )
                    actions.actionsList
                        .filter { it.actionType == ActionType.Play_add3 && it.instanceId == milledIid }
                        .shouldHaveSize(1)
                    actions.inactiveActionsList
                        .filter { it.actionType == ActionType.Play_add3 && it.instanceId == milledIid }
                        .shouldHaveSize(0)
                }

                val landAbility =
                    SpellExecutor(board.game, board.human, InteractivePromptBridge())
                        .playLand(ForgeCardId(milledLand.id))
                        ?.single()
                        ?: error("Expected a MayPlay-aware land ability")
                landAbility.mayPlayOption shouldBe mayPlayOption
                landAbility.canPlay().shouldBeTrue()
                landAbility.resolve()

                milledLand.isInZone(ZoneType.Battlefield) shouldBe true
                handLand.isInZone(ZoneType.Hand) shouldBe true
            }
        }
    })
