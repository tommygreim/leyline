package leyline.game.snapshot

import forge.card.GamePieceType
import forge.game.card.Card
import forge.game.card.CardFactory
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.ZoneIds
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer

class SnapshotCaptureTest :
    BoardTest({

        test("a live capture projects the phase the engine will actually enter next") {
            // TurnInfo.nextPhase is the client's only source for the two-line priority
            // button. Forge's own skip rules are private, so this pins the mirror in
            // NextPhaseProjector against a real PhaseHandler rather than a fixture.
            val (b, game, _) = startWithBoard { _, human, _ -> addCard("Grizzly Bears", human, ZoneType.Battlefield) }

            val snap = SnapshotCapture.run(game, b, "test", 0)

            assertSoftly {
                // The board starts on turn 1 at Main1, where Arena sends Combat/BeginCombat
                // (48 of its 211 turnInfo objects in the reference capture).
                snap.phase.phase shouldBe PhaseType.MAIN1
                snap.phase.nextPhase shouldBe PhaseType.COMBAT_BEGIN
            }
        }

        test("Puzzle-Goal-style EFFECT in Command zone: snapshot captures with grpId=0, no throw") {
            val (b, game, _) =
                startWithBoard { g, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)

                    // Mirror forge.gamemodes.puzzle.Puzzle — synthetic engine goal card.
                    // forgeId=-1 signals "not a real card"; GamePieceType.EFFECT signals
                    // "engine bookkeeping, no wire identity".
                    val goal = Card(-1, g)
                    goal.owner = human
                    goal.name = "Puzzle Goal"
                    goal.gamePieceType = GamePieceType.EFFECT
                    human.getZone(ZoneType.Command).add(goal)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)

            val goalFid = ForgeCardId(-1)
            assertSoftly {
                snap.objects[goalFid]?.grpId shouldBe 0
                snap.objects[goalFid]?.name shouldBe "Puzzle Goal"
                snap.objects[goalFid]?.isProjectable shouldBe false
            }

            // Sanity: real card still resolves to a real grpId.
            val bearsCard =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Grizzly Bears" }
            val bearsSnap = snap.objects.getValue(ForgeCardId(bearsCard.id))
            bearsSnap.grpId shouldBeGreaterThan 0
        }

        test("Effect helper with source is omitted from snapshot zones and objects") {
            val (b, game, _) =
                startWithBoard { g, human, _ ->
                    val source = addCard("Grizzly Bears", human, ZoneType.Graveyard)

                    val helper = Card(198, g)
                    helper.owner = human
                    helper.name = "Grizzly Bears's Effect"
                    helper.gamePieceType = GamePieceType.EFFECT
                    helper.setEffectSource(source)
                    human.getZone(ZoneType.Battlefield).add(helper)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val helperFid = ForgeCardId(198)

            assertSoftly {
                snap.zones.getValue(ZoneIds.BATTLEFIELD).contents shouldBe emptyList()
                snap.zones.getValue(ZoneIds.BATTLEFIELD).contents shouldNotContain helperFid
                snap.objects.keys shouldNotContain helperFid
            }
        }

        test("snapshot freezes state-zone projection facts") {
            lateinit var forest: Card
            lateinit var source: Card
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    human.setLife(13, null)
                    source = addCard("Grizzly Bears", human, ZoneType.Graveyard)
                    forest = addCard("Forest", human, ZoneType.Battlefield)
                    forest.setEffectSource(source)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val forestSnap = snap.objects.getValue(ForgeCardId(forest.id))

            assertSoftly {
                forestSnap.isProjectable shouldBe true
                forestSnap.basicLandManaAbilityGrpId shouldBe 1005
                forestSnap.effectSourceForgeCardId shouldBe ForgeCardId(source.id)
                forestSnap.owner.value shouldBe 1
                forestSnap.controller.value shouldBe 1
                snap.seats.single { it.seatId.value == 1 }.life shouldBe 13
            }
        }

        test("snapshot freezes live Paradigm membership") {
            lateinit var paradigm: Card
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    paradigm = addCard("Germination Practicum", human, ZoneType.Hand)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)

            snap.objects.getValue(ForgeCardId(paradigm.id)).hasParadigmKeyword shouldBe true
        }

        test("copied spell remains projectable as a distinct stack card") {
            val board =
                startWithBoard { _, player, _ ->
                    addCard("Grizzly Bears", player, ZoneType.Hand)
                }
            val source =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val original = source.firstSpellAbility.also { it.activatingPlayer = board.human }
            board.game.stack.addAndUnfreeze(original)
            val copy = CardFactory.copySpellAbilityAndPossiblyHost(original, original, board.human)
            board.game.stack.addAndUnfreeze(copy)
            val copyId = ForgeCardId(copy.hostCard.id)
            val snap = SnapshotCapture.run(board.game, board.bridge, "test", 0)

            assertSoftly {
                snap.zones.getValue(ZoneIds.STACK).contents shouldContain copyId
                snap.objects.getValue(copyId).isProjectable shouldBe true
                copyId shouldNotBe ForgeCardId(source.id)
            }
        }

        test("discarded snapshot capture does not advance token or instance identity") {
            lateinit var token: Card
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    val creator = addCard("Forest", human, ZoneType.Battlefield)
                    token = addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    token.setGamePieceType(GamePieceType.TOKEN)
                    token.tokenSpawningAbility = creator.manaAbilities.single()
                }
            bridge.replaceProjectionStateForTest(bridge.projectionStateSnapshot().copy(tokenGrpIds = emptyMap()))
            val before = bridge.projectionStateSnapshot()

            val (_, tentative) =
                bridge.editProjection(before) {
                    SnapshotCapture.run(game, bridge, "test", 1)
                }
            val tokenIid =
                tentative.identities.forgeIdToInstanceId
                    .getValue(ForgeCardId(token.id))
                    .value

            assertSoftly {
                tentative.tokenGrpIds.keys shouldBe setOf(tokenIid)
                bridge.projectionStateSnapshot() shouldBe before
            }
        }
    })
