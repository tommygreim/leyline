package leyline.mechanics.mdfc

import forge.card.CardStateName
import forge.game.ability.AbilityFactory
import forge.game.ability.effects.EffectEffect
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.buildMdfcBackLandAbility
import leyline.bridge.coord.SpellExecutor
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

@Suppress("WeakAssertionOnly")
class MdfcActionTest :
    BoardTest({

        fun actionsFor(
            actions: List<Action>,
            iid: Int,
            actionType: ActionType,
        ): List<Action> = actions.filter { it.actionType == actionType && it.instanceId == iid }

        test("land-front land-back MDFC offers one play action per face and no cast") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Blightstep Pathway", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.hand.card("Blightstep Pathway")
            val iid = b.instanceId(card)
            val snap = SnapshotCapture.run(game, b, "test", 0)

            val actions = ActionMapper.buildFromSnapshot(1, snap, b)
            val faceActions = (actions.actionsList + actions.inactiveActionsList).filter { it.instanceId == iid }
            val naiveActions = ActionMapper.buildNaiveActionsFromSnapshot(1, snap, b)
            val naiveFaceActions = (naiveActions.actionsList + naiveActions.inactiveActionsList).filter { it.instanceId == iid }

            assertSoftly {
                getAllCastableAbilities(card, human).shouldBeEmpty()
                faceActions.map { it.actionType } shouldBe listOf(ActionType.Play_add3, ActionType.PlayMdfc)
                naiveFaceActions.map { it.actionType } shouldBe listOf(ActionType.Play_add3)
            }
        }

        test("PlayMdfc offer keeps the selected back-face land ability through execution") {
            val (b, game, _) =
                startWithBoard { _, human, _ -> addCard("Blightstep Pathway", human, ZoneType.Hand) }
            val human = game.humanPlayer
            val card = human.hand.card("Blightstep Pathway")
            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val backOffer =
                projection.offers.single {
                    it.action.actionType == ActionType.PlayMdfc &&
                        it.action.instanceId == b.instanceId(card)
                }
            val command = backOffer.command as PlayerAction.PlayLand
            command.ability?.cardStateName shouldBe CardStateName.Backside

            val resolved = SpellExecutor(game, human, InteractivePromptBridge()).playLand(ForgeCardId(card.id), command.ability)
            resolved?.single() shouldBe command.ability
            resolved?.single()?.resolve()
            card.currentStateName shouldBe CardStateName.Backside
            card.isInZone(ZoneType.Battlefield) shouldBe true
        }

        test("MayPlay-granted Pathway in exile offers and resolves both land faces") {
            val (b, game, _) =
                startWithBoard { game, human, _ ->
                    val source = addCard("Tablet of Discovery", human, ZoneType.Battlefield)
                    val pathway = addCard("Blightstep Pathway", human, ZoneType.Exile)
                    source.addRemembered(pathway)
                    source.setSVar(
                        "GrantLandPlay",
                        "Mode\$ Continuous | MayPlay\$ True | EffectZone\$ Command | " +
                            "Affected\$ Card.IsRemembered | AffectedZone\$ Exile",
                    )
                    AbilityFactory
                        .getAbility("DB\$ Effect | RememberObjects\$ RememberedCard | StaticAbilities\$ GrantLandPlay", source)
                        .also { it.activatingPlayer = human }
                        .let { EffectEffect().resolve(it) }
                    source.clearRemembered()
                    game.phaseHandler.devModeSet(PhaseType.MAIN1, human)
                    game.action.checkStaticAbilities(false)
                }
            val human = game.humanPlayer
            val card = human.exile.card("Blightstep Pathway")
            val iid = b.instanceId(card)
            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val plays = projection.offers.filter { it.action.instanceId == iid && it.command is PlayerAction.PlayLand }
            plays.map { it.action.actionType } shouldBe listOf(ActionType.Play_add3, ActionType.PlayMdfc)
            val backAbility = (plays.last().command as PlayerAction.PlayLand).ability
            backAbility?.cardStateName shouldBe CardStateName.Backside
            backAbility?.mayPlayOption shouldNotBe null
            SpellExecutor(game, human, InteractivePromptBridge()).playLand(ForgeCardId(card.id), backAbility)?.single()?.resolve()
            card.currentStateName shouldBe CardStateName.Backside
            card.isInZone(ZoneType.Battlefield) shouldBe true
        }

        test("MayPlay-granted exiled MDFC retains its spell and land faces") {
            val (bridge, game, _) =
                startWithBoard { game, human, _ ->
                    val source = addCard("Tablet of Discovery", human, ZoneType.Battlefield)
                    val vision = addCard("Silundi Vision", human, ZoneType.Exile)
                    repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                    source.addRemembered(vision)
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
            val human = game.humanPlayer
            val card = human.exile.card("Silundi Vision")
            val iid = bridge.instanceId(card)
            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, bridge, "exiled-mdfc", 0), bridge)
            val offers = projection.offers.filter { it.action.instanceId == iid }
            offers.map { it.action.actionType } shouldBe listOf(ActionType.Cast, ActionType.PlayMdfc)
            (offers.last().command as PlayerAction.PlayLand).ability?.cardStateName shouldBe CardStateName.Backside
        }

        test("MayPlay-granted exiled MDFC retains its back spell face") {
            val (bridge, game, _) =
                startWithBoard { game, human, _ ->
                    val source = addCard("Tablet of Discovery", human, ZoneType.Battlefield)
                    val esika = addCard("Esika, God of the Tree", human, ZoneType.Exile)
                    listOf("Plains", "Island", "Swamp", "Mountain", "Forest")
                        .forEach { addCard(it, human, ZoneType.Battlefield) }
                    addCard("Forest", human, ZoneType.Battlefield)
                    source.addRemembered(esika)
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
            val card = game.humanPlayer.exile.card("Esika, God of the Tree")
            card.turnFaceDown(true)
            game.action.checkStaticAbilities(false)
            val iid = bridge.instanceId(card)
            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, bridge, "exiled-spell-mdfc", 0), bridge)
            val offers = projection.offers.filter { it.action.instanceId == iid }
            offers.map { it.action.actionType } shouldBe listOf(ActionType.Cast, ActionType.CastMdfc)
            (offers.last().command as PlayerAction.CastSpell).ability?.cardStateName shouldBe CardStateName.Backside
            offers
                .last()
                .action.autoTapSolution.autoTapActionsCount shouldBe 5
        }

        test("spell-front land-back MDFC offers normal Cast and PlayMdfc") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Silundi Vision", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val iid = human.hand.iid("Silundi Vision")

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)

            val mainCast = actions.actionsList.firstOrNull { it.actionType == ActionType.Cast && it.instanceId == iid }
            val landFace = actionsFor(actions.actionsList, iid, ActionType.PlayMdfc).firstOrNull()
            assertSoftly {
                mainCast shouldNotBe null
                landFace shouldNotBe null
                landFace!!.grpId shouldBe 0
                landFace.facetId shouldBe 0
                landFace.abilityGrpId shouldBe 0
                landFace.sourceId shouldBe 0
                landFace.manaCostCount shouldBe 0
            }
        }

        test("spell-back MDFC offers CastMdfc with spell-face cost") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Esika, God of the Tree", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val iid = human.hand.iid("Esika, God of the Tree")

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)

            val backSpell = actionsFor(actions.actionsList, iid, ActionType.CastMdfc).firstOrNull()
            assertSoftly {
                backSpell shouldNotBe null
                backSpell!!.grpId shouldBe 0
                backSpell.facetId shouldBe 0
                backSpell.alternativeGrpId shouldBe 0
                backSpell.manaCostCount shouldNotBe 0
                backSpell.autoTapSolution.autoTapActionsCount shouldBe 5
            }
        }

        test("MDFC actions are hand-only") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Silundi Vision", human, ZoneType.Graveyard)
                }
            val iid = game.humanPlayer.graveyard.iid("Silundi Vision")

            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)

            actionsFor(actions.actionsList, iid, ActionType.PlayMdfc).shouldBeEmpty()
            actionsFor(actions.inactiveActionsList, iid, ActionType.PlayMdfc).shouldBeEmpty()
        }

        test("MDFC accept helpers resolve backside spell and land abilities") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Esika, God of the Tree", human, ZoneType.Hand)
                    addCard("Silundi Vision", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val esika = human.getZone(ZoneType.Hand).cards.first { it.name == "Esika, God of the Tree" }
            val silundi = human.getZone(ZoneType.Hand).cards.first { it.name == "Silundi Vision" }

            val castable = getAllCastableAbilities(esika, human)
            val backSpell = castable.firstOrNull { it.cardStateName == CardStateName.Backside && it.isSpell && !it.isLandAbility }
            val landAbility = buildMdfcBackLandAbility(silundi)
            landAbility?.activatingPlayer = human

            assertSoftly {
                backSpell shouldNotBe null
                castable.indexOfFirst { it === backSpell } shouldNotBe -1
                landAbility shouldNotBe null
                landAbility!!.cardStateName shouldBe CardStateName.Backside
                landAbility.canPlay() shouldBe true
            }

            // Keep bridge allocated for both hand cards; catches accidental iid assumptions in setup.
            b.instanceId(esika) shouldNotBe b.instanceId(silundi)
        }
    })
