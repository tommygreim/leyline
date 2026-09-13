package leyline.game.mapping

import forge.card.MagicColor
import forge.game.mana.Mana
import forge.game.phase.PhaseType
import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.ActionManaCosts
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.SeatId
import leyline.game.snapshot.SnapshotCapture
import leyline.game.state.GameBridge
import leyline.testkit.BoardTest
import leyline.testkit.SessionTest
import leyline.testkit.haveManaCost
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Displayed-cost rule over fixture boards: the cost on an action offer is the
 * printed cost after state-derived modifications only, never reduced by a
 * payment-time choice (Delve, Convoke, Waterbend) — and computing it never
 * raises a prompt.
 *
 * See docs/decisions/0007-displayed-cost-and-controller-contexts.md.
 */
class CastDisplayCostBoardTest :
    BoardTest({

        test("Delve card in hand displays printed cost with a full graveyard, no prompt raised") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Treasure Cruise", human, ZoneType.Hand)
                    repeat(4) { addCard("Forest", human, ZoneType.Graveyard) }
                    // Enough Islands to pay the full displayed cost outright — the
                    // point is displayed cost ignores delve, not whether delve
                    // could also afford it.
                    repeat(8) { addCard("Island", human) }
                }
            val (active, inactive) = castOffers(b, game, "Treasure Cruise")
            assertSoftly {
                active.single() should haveManaCost(generic = 7, blue = 1)
                inactive shouldHaveSize 0
                b
                    .seat(SeatId(1))
                    .prompt.history
                    .shouldBeEmpty()
            }
        }

        test("Convoke card displays printed cost despite untapped creatures") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Conclave Tribunal", human, ZoneType.Hand)
                    repeat(4) { addCard("Grizzly Bears", human) }
                    // Pay outright — the point is displayed cost ignores convoke,
                    // not whether convoke could also afford it.
                    repeat(4) { addCard("Plains", human) }
                }
            val (active, inactive) = castOffers(b, game, "Conclave Tribunal")
            assertSoftly {
                active.single() should haveManaCost(generic = 3, white = 1)
                inactive shouldHaveSize 0
                b
                    .seat(SeatId(1))
                    .prompt.history
                    .shouldBeEmpty()
            }
        }

        test("Convoke card displays a state-derived static reduction") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Conclave Tribunal", human, ZoneType.Hand)
                    // "Enchantment spells you cast cost {1} less to cast."
                    addCard("Starfield Mystic", human)
                    addCard("Grizzly Bears", human)
                    repeat(3) { addCard("Plains", human) }
                }
            val (active, inactive) = castOffers(b, game, "Conclave Tribunal")
            active.single() should haveManaCost(generic = 2, white = 1)
            inactive shouldHaveSize 0
        }

        test("Waterbend activation cost displays printed value despite untapped creatures") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    // Giant Koi: "{Waterbend 3}: this creature can't be blocked."
                    addCard("Giant Koi", human)
                    repeat(2) { addCard("Grizzly Bears", human) }
                    // Pay outright — the point is displayed cost ignores
                    // waterbend, not whether waterbend could also afford it.
                    repeat(3) { addCard("Island", human) }
                }
            val (active, inactive) = castOffers(b, game, "Giant Koi", ActionType.Activate_add3)
            assertSoftly {
                active.single() should haveManaCost(generic = 3)
                inactive shouldHaveSize 0
                b
                    .seat(SeatId(1))
                    .prompt.history
                    .shouldBeEmpty()
            }
        }

        test("X-cost spell offered as castable with only its colored pips payable") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    // Traumatic Critique {X}{U}{R} — X can be 0; only U and R are required.
                    addCard("Traumatic Critique", human, ZoneType.Hand)
                    addCard("Island", human)
                    addCard("Mountain", human)
                }
            val (active, inactive) = castOffers(b, game, "Traumatic Critique")
            active shouldHaveSize 1
            inactive shouldHaveSize 0
        }

        test("spell is castable from floating mana alone with no untapped sources left") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    // Grizzly Bears {1}{G} — pay entirely from an already-floating
                    // pool; both lands that produced it are tapped and no other
                    // mana source exists.
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    val forest1 = addCard("Forest", human)
                    val forest2 = addCard("Forest", human)
                    forest1.setTapped(true)
                    forest2.setTapped(true)
                    human.manaPool.addMana(Mana(MagicColor.GREEN, forest1, null, human))
                    human.manaPool.addMana(Mana(MagicColor.GREEN, forest2, null, human))
                }
            val (active, inactive) = castOffers(b, game, "Grizzly Bears")
            active shouldHaveSize 1
            inactive shouldHaveSize 0
        }

        test("Affinity displays cost reduced by artifacts controlled") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    // Thoughtcast {4}{U}, Affinity for Artifacts — reduced to {1}{U}
                    // by 3 artifacts; 2 Islands cover the reduced cost so the offer
                    // is active, not just correctly priced.
                    addCard("Thoughtcast", human, ZoneType.Hand)
                    repeat(3) { addCard("Ornithopter", human) }
                    repeat(2) { addCard("Island", human) }
                }
            val (active, inactive) = castOffers(b, game, "Thoughtcast")
            active.single() should haveManaCost(generic = 1, blue = 1)
            inactive shouldHaveSize 0
        }

        test("static reducer shows on a plain spell") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Fall of the Thran", human, ZoneType.Hand)
                    addCard("Starfield Mystic", human)
                    repeat(5) { addCard("Plains", human) }
                }
            val (active, inactive) = castOffers(b, game, "Fall of the Thran")
            active.single() should haveManaCost(generic = 4, white = 1)
            inactive shouldHaveSize 0
        }

        test("AlternateAdditionalCost card yields one Cast offer at base cost") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    // Thunderherd Migration {1}{G}: additional cost — reveal a
                    // Dinosaur ({1}{G} variant) or pay {1} more ({2}{G} variant).
                    addCard("Thunderherd Migration", human, ZoneType.Hand)
                    repeat(2) { addCard("Forest", human) }
                }

            val actions =
                ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(game, b, "test", 0), b)

            val casts =
                actions.actionsList.filter { it.actionType == ActionType.Cast } +
                    actions.inactiveActionsList.filter { it.actionType == ActionType.Cast }
            casts.size shouldBe 1
            casts.single() should haveManaCost(generic = 1, green = 1)
        }

        test("best-effort affordability includes Emerge and restores payment state") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Wretched Gryff", human, ZoneType.Hand)
                    addCard("Walking Corpse", human)
                    repeat(4) { addCard("Island", human) }
                }
            val gryff = game.humanPlayerCard("Wretched Gryff")
            val corpse = game.humanPlayerCard("Walking Corpse")
            val human = gryff.controller
            val emerge =
                getAllCastableAbilities(gryff, human)
                    .single { it.alternativeCost == AlternativeCost.Emerge }

            assertSoftly {
                ActionManaCosts.canPayManaCost(emerge, human) shouldBe true
                emerge.sacrificedAsEmerge shouldBe null
                corpse.isUsedToPay shouldBe false
            }
        }

        test("restricted any-color mana only enables matching creature spells") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Voice of Victory", human, ZoneType.Hand)
                    addCard("Island", human)
                    addCard("Cavern of Souls", human).setChosenType("Elf")
                }
            val cavern = game.humanPlayerCard("Cavern of Souls")

            castOffers(b, game, "Voice of Victory").let { (active, inactive) ->
                active.shouldBeEmpty()
                inactive shouldHaveSize 1
            }

            cavern.setChosenType("Human")
            castOffers(b, game, "Voice of Victory").let { (active, inactive) ->
                active shouldHaveSize 1
                inactive.shouldBeEmpty()
            }
        }

        test("a hand card with no timing-legal cast still reports its reduced cost") {
            val (b, game, _) =
                startWithBoard { g, human, ai ->
                    addCard("Banishing Light", human, ZoneType.Hand)
                    addCard("Starfield Mystic", human)
                    repeat(3) { addCard("Plains", human) }
                    // Sorcery speed on the opponent's turn — Forge offers no castable
                    // ability, but the card still shows a cost in hand.
                    g.phaseHandler.devModeSet(PhaseType.MAIN1, ai)
                }

            val (active, inactive) = castOffers(b, game, "Banishing Light")
            // {2}{W} less Starfield Mystic's reduction. The client reads a card's
            // displayed cost from its cheapest offer and falls back to the printed
            // cost when it has none, so an offer has to survive the timing filter.
            assertSoftly {
                active.shouldBeEmpty()
                inactive.single { it.alternativeGrpId == 0 } should haveManaCost(generic = 1, white = 1)
            }
        }

        test("naive and snapshot builders agree on displayed cost for every hand card") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Treasure Cruise", human, ZoneType.Hand)
                    addCard("Conclave Tribunal", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    addCard("Fall of the Thran", human, ZoneType.Hand)
                    addCard("Starfield Mystic", human)
                    addCard("Grizzly Bears", human)
                    repeat(3) { addCard("Forest", human, ZoneType.Graveyard) }
                    // AlternateAdditionalCost: variant-dependent payCosts — the
                    // printed {1}{G} must appear on both the naive and the
                    // projection side of the same snapshot.
                    addCard("Thunderherd Migration", human, ZoneType.Hand)
                }

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val snapshotReq = ActionMapper.buildFromSnapshot(SessionTest.HUMAN_SEAT, snap, b)
            val naiveReq = ActionMapper.buildNaiveActionsFromSnapshot(SessionTest.HUMAN_SEAT, snap, b)

            val naiveCasts =
                naiveReq.actionsList
                    .filter { it.actionType == ActionType.Cast }
                    .associateBy { it.instanceId }
            naiveCasts.shouldNotBeEmpty()

            for ((instanceId, naive) in naiveCasts) {
                val snapshotCast =
                    (snapshotReq.actionsList + snapshotReq.inactiveActionsList)
                        .filter { it.actionType == ActionType.Cast && it.instanceId == instanceId }
                        // Alt-cost offers carry alternativeGrpId; compare the base offer.
                        .first { it.alternativeGrpId == 0 }
                snapshotCast.manaCostList shouldBe naive.manaCostList
            }
        }
    })

private fun forge.game.Game.humanPlayerCard(name: String): forge.game.card.Card =
    players
        .flatMap { p -> listOf(ZoneType.Hand, ZoneType.Battlefield).flatMap { p.getZone(it).cards } }
        .first { it.name == name }

/**
 * Cast-family action offers for [cardName], split active vs inactive.
 * [actionType] defaults to Cast; pass e.g. [ActionType.Activate_add3] for an
 * activated-ability offer (Waterbend, etc).
 */
private fun castOffers(
    b: GameBridge,
    game: forge.game.Game,
    cardName: String,
    actionType: ActionType = ActionType.Cast,
): Pair<List<Action>, List<Action>> {
    val instanceId = b.instanceId(game.humanPlayerCard(cardName))
    val req = ActionMapper.buildFromSnapshot(SessionTest.HUMAN_SEAT, SnapshotCapture.run(game, b, "test", 0), b)
    return req.actionsList.filter { it.actionType == actionType && it.instanceId == instanceId } to
        req.inactiveActionsList.filter { it.actionType == actionType && it.instanceId == instanceId }
}
