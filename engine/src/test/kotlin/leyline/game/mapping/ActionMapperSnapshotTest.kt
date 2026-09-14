package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.coord.hasAmbiguousActionCatalog
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.haveManaCost
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Verifies that [ActionMapper.buildFromSnapshot] produces correct action shapes
 * for representative board states.
 *
 * Uses [Board.startWithBoard] — synchronous board setup, no game loop.
 * Cost-legality routes through the live Forge bridge, so these are BoardTag tests.
 */
class ActionMapperSnapshotTest :
    BoardTest({

        // -----------------------------------------------------------------------
        // Test 1: empty hand + battlefield → Pass + FloatMana only
        // -----------------------------------------------------------------------

        test("empty hand and battlefield yields only Pass and FloatMana") {
            val (b, game, _) = startWithBoard { _, _, _ -> }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            assertSoftly {
                fromSnap.actionsList.any { it.actionType == ActionType.Pass }.shouldBeTrue()
                fromSnap.actionsList.any { it.actionType == ActionType.FloatMana }.shouldBeTrue()
                fromSnap.actionsList
                    .none {
                        it.actionType == ActionType.Cast ||
                            it.actionType == ActionType.Play_add3 ||
                            it.actionType == ActionType.ActivateMana
                    }.shouldBeTrue()
                fromSnap.inactiveActionsCount shouldBe 0
            }
        }

        // -----------------------------------------------------------------------
        // Test 2: land in hand → Play action appears (active or inactive)
        // -----------------------------------------------------------------------

        test("land in hand — Play action present") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Hand)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            (fromSnap.actionsList + fromSnap.inactiveActionsList).count { it.actionType == ActionType.Play_add3 } shouldBe 1
        }

        // -----------------------------------------------------------------------
        // Test 3: non-land spell in hand — Cast action present
        // -----------------------------------------------------------------------

        test("non-land spell in hand — Cast action present (active or inactive)") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Llanowar Elves", human, ZoneType.Hand)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            (fromSnap.actionsList + fromSnap.inactiveActionsList).count { it.actionType == ActionType.Cast } shouldBe 1
        }

        // -----------------------------------------------------------------------
        // Test 4: untapped land on battlefield → ActivateMana present
        // -----------------------------------------------------------------------

        test("untapped land on battlefield — ActivateMana present") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            fromSnap.actionsList.count { it.actionType == ActionType.ActivateMana } shouldBe 1
        }

        test("type-granted duplicate mana ability keeps printed execution") {
            var takenumaForgeId = 0
            val (b, game, _) =
                startWithBoard { game, human, _ ->
                    addCard("Takenuma, Abandoned Mire", human, ZoneType.Battlefield).also { takenumaForgeId = it.id }
                    addCard("Urborg, Tomb of Yawgmoth", human, ZoneType.Battlefield)
                    game.action.checkStateEffects(true)
                }

            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val takenumaInstanceId = b.getOrAllocInstanceId(ForgeCardId(takenumaForgeId)).value
            val offer =
                projection.offers.single {
                    it.action.actionType == ActionType.ActivateMana && it.action.instanceId == takenumaInstanceId
                }
            val command = offer.command.shouldBeInstanceOf<PlayerAction.ActivateMana>()

            assertSoftly {
                hasAmbiguousActionCatalog(projection.offers) shouldBe false
                offer.action.abilityGrpId shouldBe 1003
                command.abilityId shouldBe 0
                command.ability?.getOriginalParam("Secondary") shouldBe null
            }
        }

        test("repeated granted activated abilities retain distinct action identities") {
            var guardForgeId = 0
            val (b, game, _) =
                startWithBoard { game, human, _ ->
                    val guard = addCard("Grizzly Bears", human, ZoneType.Battlefield).also { guardForgeId = it.id }
                    addCard("Presence of Gond", human, ZoneType.Battlefield).attachToEntity(guard, null, true)
                    addCard("Presence of Gond", human, ZoneType.Battlefield).attachToEntity(guard, null, true)
                    game.action.checkStaticAbilities(false)
                }

            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val guardInstanceId = b.getOrAllocInstanceId(ForgeCardId(guardForgeId)).value
            val projectedAbilities =
                handshakeFull(game, b, 1)
                    .gameObjectsList
                    .single { it.instanceId == guardInstanceId }
                    .uniqueAbilitiesList
                    .filter { it.grpId == 97312 }
            val offers =
                projection.offers.filter {
                    it.action.actionType == ActionType.Activate_add3 && it.action.instanceId == guardInstanceId
                }

            assertSoftly {
                offers.size shouldBe 2
                projectedAbilities.map { it.id }.toSet().size shouldBe 2
                offers.map { it.action.abilityGrpId }.toSet() shouldBe setOf(97312)
                offers.map { it.action.uniqueAbilityId }.toSet().size shouldBe 2
                offers.map { it.action.uniqueAbilityId }.toSet() shouldBe projectedAbilities.map { it.id }.toSet()
                offers.map { it.command.shouldBeInstanceOf<PlayerAction.ActivateAbility>().ability }.toSet().size shouldBe 2
                hasAmbiguousActionCatalog(projection.offers) shouldBe false
            }
        }

        test("dual basic land types retain each color identity") {
            var bayouForgeId = 0
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Bayou", human, ZoneType.Battlefield).also { bayouForgeId = it.id }
                }

            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val bayouInstanceId = b.getOrAllocInstanceId(ForgeCardId(bayouForgeId)).value
            val actions =
                projection.offers
                    .filter { it.action.actionType == ActionType.ActivateMana && it.action.instanceId == bayouInstanceId }
                    .map { it.action }

            assertSoftly {
                actions.size shouldBe 2
                actions.map { it.abilityGrpId }.toSet() shouldBe setOf(1003, 1005)
                hasAmbiguousActionCatalog(projection.offers) shouldBe false
            }
        }

        test("type-granted mana preserves abilities with distinct costs and amounts") {
            var towerForgeId = 0
            val (b, game, _) =
                startWithBoard { game, human, _ ->
                    addCard("Phyrexian Tower", human, ZoneType.Battlefield).also { towerForgeId = it.id }
                    addCard("Llanowar Elves", human, ZoneType.Battlefield)
                    addCard("Urborg, Tomb of Yawgmoth", human, ZoneType.Battlefield)
                    game.action.checkStateEffects(true)
                }

            val projection = ActionMapper.buildProjectionFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val towerInstanceId = b.getOrAllocInstanceId(ForgeCardId(towerForgeId)).value
            val offers =
                projection.offers.filter {
                    it.action.actionType == ActionType.ActivateMana && it.action.instanceId == towerInstanceId
                }

            assertSoftly {
                offers.size shouldBe 3
                offers.map { it.action.abilityGrpId }.toSet() shouldBe setOf(1003, 1152, 13714)
                offers
                    .map { it.command.shouldBeInstanceOf<PlayerAction.ActivateMana>().ability!! }
                    .single { it.originalMapParams["Amount"] == "2" }
                    .payCosts.costParts.size shouldBe 2
                hasAmbiguousActionCatalog(projection.offers) shouldBe false
            }
        }

        test("tapped land on battlefield — ActivateMana is inactive with identity only") {
            var islandForgeId = 0
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                        .also { islandForgeId = it.id }
                        .tap(true, true, null, null)
                }

            val fromSnap = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)
            val inactive = fromSnap.inactiveActionsList.single { it.actionType == ActionType.ActivateMana }
            val expectedInstanceId = b.getOrAllocInstanceId(ForgeCardId(islandForgeId)).value
            val expectedGrpId = b.resolveGrpId(b.findCard(ForgeCardId(islandForgeId))!!, expectedInstanceId)

            assertSoftly {
                fromSnap.actionsList.none { it.actionType == ActionType.ActivateMana }.shouldBeTrue()
                inactive.abilityGrpId shouldBe 1002
                inactive.instanceId shouldBe expectedInstanceId
                inactive.grpId shouldBe expectedGrpId
                inactive.facetId shouldBe expectedInstanceId
                inactive.uniqueAbilityId shouldBe 50
                inactive.isBatchable.shouldBeFalse()
                inactive.manaPaymentOptionsCount shouldBe 0
                inactive.manaSelectionsCount shouldBe 0
            }
        }

        test("battlefield activated ability carries matching uniqueAbilityId") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Tavern Swindler", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            val activate = fromSnap.actionsList.first { it.actionType == ActionType.Activate_add3 }

            assertSoftly {
                activate.abilityGrpId shouldBe 19490
                activate.uniqueAbilityId shouldBe 50
            }
        }

        test("snow-costed activated ability carries snow mana cost") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Ascendant Spirit", human, ZoneType.Battlefield)
                    addCard("Snow-Covered Island", human, ZoneType.Battlefield)
                    addCard("Snow-Covered Island", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)
            val activate = fromSnap.actionsList.first { it.actionType == ActionType.Activate_add3 }
            val snowMana =
                fromSnap.actionsList
                    .asSequence()
                    .filter { it.actionType == ActionType.ActivateMana }
                    .flatMap { it.manaPaymentOptionsList.asSequence() }
                    .flatMap { it.manaList.asSequence() }
                    .first { mana -> mana.specsList.any { it.type == ManaSpecType.FromSnow } }
            val autoTapMana =
                activate.autoTapSolution.autoTapActionsList.flatMap { it.manaPaymentOption.manaList }

            assertSoftly {
                activate.abilityGrpId shouldBe 139877
                activate should haveManaCost(snow = 2)
                activate.hasAutoTapSolution().shouldBeTrue()
                autoTapMana.map { it.color } shouldBe listOf(ManaColor.Blue_afc9, ManaColor.Blue_afc9)
                // Snow-Covered Island is both FromSnow and FromBasic (see ISSUES.md I41) —
                // the two specs are independent, not mutually exclusive.
                autoTapMana.map { mana -> mana.specsList.map { it.type } } shouldBe
                    listOf(
                        listOf(ManaSpecType.Predictive, ManaSpecType.FromSnow, ManaSpecType.FromBasic),
                        listOf(ManaSpecType.Predictive, ManaSpecType.FromSnow, ManaSpecType.FromBasic),
                    )
                snowMana.color shouldBe ManaColor.Blue_afc9
                snowMana.specsList.map { it.type } shouldBe
                    listOf(ManaSpecType.Predictive, ManaSpecType.FromSnow, ManaSpecType.FromBasic)
            }
        }

        // -----------------------------------------------------------------------
        // Test 5: affordable spell → Cast in active list
        // -----------------------------------------------------------------------

        test("affordable Llanowar Elves — Cast in active actions") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Llanowar Elves", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Battlefield)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            assertSoftly {
                fromSnap.actionsCount shouldBe 4 // ActivateMana + Cast + Pass + FloatMana
                fromSnap.actionsList.any { it.actionType == ActionType.Cast }.shouldBeTrue()
                fromSnap.actionsList.any { it.actionType == ActionType.ActivateMana }.shouldBeTrue()
                fromSnap.actionsList.any { it.actionType == ActionType.Pass }.shouldBeTrue()
                fromSnap.actionsList.any { it.actionType == ActionType.FloatMana }.shouldBeTrue()
            }
        }

        test("priority projection binds every active family to its originating Forge candidate") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Tavern Swindler", human, ZoneType.Battlefield)
                    addCard("Llanowar Elves", human, ZoneType.Hand)
                    addCard("Island", human, ZoneType.Hand)
                }

            val projection =
                ActionMapper.buildProjectionFromSnapshot(
                    1,
                    SnapshotCapture.run(board.game, board.bridge, "test", 0),
                    board.bridge,
                )

            projection.offers.map { it.action } shouldBe projection.actions.actionsList
            val cast =
                projection.offers
                    .single { it.action.actionType == ActionType.Cast }
                    .command
                    .shouldBeInstanceOf<PlayerAction.CastSpell>()
            val land =
                projection.offers
                    .single { it.action.actionType == ActionType.Play_add3 }
                    .command
                    .shouldBeInstanceOf<PlayerAction.PlayLand>()
            val activate =
                projection.offers
                    .first { it.action.actionType == ActionType.Activate_add3 }
                    .command
                    .shouldBeInstanceOf<PlayerAction.ActivateAbility>()
            val mana =
                projection.offers
                    .first { it.action.actionType == ActionType.ActivateMana }
                    .command
                    .shouldBeInstanceOf<PlayerAction.ActivateMana>()

            assertSoftly {
                cast.ability shouldNotBe null
                cast.ability?.hostCard?.name shouldBe "Llanowar Elves"
                land.cardId shouldBe
                    ForgeCardId(
                        board.human
                            .getZone(ZoneType.Hand)
                            .cards
                            .single { it.name == "Island" }
                            .id,
                    )
                activate.ability shouldNotBe null
                activate.ability?.hostCard?.name shouldBe "Tavern Swindler"
                mana.ability shouldNotBe null
                mana.ability?.hostCard?.name shouldBe "Forest"
                projection.offers
                    .filter { it.action.actionType == ActionType.Pass || it.action.actionType == ActionType.FloatMana }
                    .map { it.command } shouldBe listOf(PlayerAction.PassPriority, PlayerAction.PassPriority)
            }
        }

        // -----------------------------------------------------------------------
        // Test 6: CardSnapshot isLand flag is set correctly
        // -----------------------------------------------------------------------

        test("CardSnapshot.isLand is true for lands, false for non-lands") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Hand)
                    addCard("Llanowar Elves", human, ZoneType.Hand)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val humanCards = game.humanPlayer.getZone(ZoneType.Hand).cards

            val islandFid = ForgeCardId(humanCards.first { it.name == "Island" }.id)
            val elvesFid = ForgeCardId(humanCards.first { it.name == "Llanowar Elves" }.id)

            snap.objects[islandFid]?.isLand shouldBe true
            snap.objects[elvesFid]?.isLand shouldBe false
        }
    })
