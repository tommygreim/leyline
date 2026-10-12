package leyline.mechanics.plot

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import leyline.bridge.getAllCastableAbilities
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.beAltCostOffer
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Plot hand-activation path (Hand → Exile face-up plotted).
 *
 * Plot is a hand-keyword whose SA is registered by Forge as a KeywordInstance
 * (`isPlotting=true`), NOT an `AlternativeCost`. Bridge surfaces it via the
 * same hand-cast rail as Warp/Sneak; the offer carries `alternativeGrpId =
 * PLOTTED keyword ability grpId` and the SA's mana cost.
 *
 * Cast-from-exile leg uses `AlternativeCost.Plotted`. Projection tests below
 * establish current-turn display versus executable actions; the authored
 * `data/puzzles/plot-railway-brawler.pzl` drives the real zone/turn history.
 *
 * Card: Railway Brawler (Sorcery 4G, Plot {3}{G}).
 */
@Suppress(
    "UnnecessaryNotNullOperator",
    // Zone-guard tests assert offer absence via `shouldNot offerAltCost(...)`. The
    // detekt heuristic doesn't recognize custom matchers as equality-shape.
    "WeakAssertionOnly",
)
class PlotActionTest :
    BoardTest({

        test("Forge surfaces the Plot hand SA on a hand card (isPlotting=true)") {
            val (_, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Railway Brawler", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val card = human.getZone(ZoneType.Hand).cards.first { it.name == "Railway Brawler" }

            val plotSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.isPlotting }
            plotSa shouldNotBe null
        }

        test("ActionMapper offers Cast for plot card in hand when mana available (alternativeGrpId=PLOTTED row)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Railway Brawler", human, ZoneType.Hand)
                }
            val b = board.bridge

            val brawlerGrpId = b.cardRepository.findGrpIdByName("Railway Brawler")!!
            val plottedAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(brawlerGrpId, KeywordAbilityIds.PLOT)!!

            val actions = board.actions()

            val castOffers =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.grpId == brawlerGrpId
                }
            castOffers.shouldNotBeEmpty()
            val plotOffer = castOffers.firstOrNull { it.alternativeGrpId == plottedAbilityGrpId }
            assertSoftly {
                plotOffer should beAltCostOffer(plottedAbilityGrpId)
                plotOffer!!.abilityGrpId shouldBe 0 // alternative rail
            }
        }

        test("ActionMapper.buildFromSnapshot offers Cast for plot card in hand (snapshot path parity)") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Railway Brawler", human, ZoneType.Hand)
                }

            val brawlerGrpId = b.cardRepository.findGrpIdByName("Railway Brawler")!!
            val plottedAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(brawlerGrpId, KeywordAbilityIds.PLOT)!!
            val brawlerIid = game.humanPlayer.hand.iid("Railway Brawler")

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val fromSnap = ActionMapper.buildFromSnapshot(1, snap, b)

            val plotOffer =
                fromSnap.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == brawlerIid &&
                        it.alternativeGrpId == plottedAbilityGrpId
                }
            assertSoftly {
                plotOffer should beAltCostOffer(plottedAbilityGrpId)
                plotOffer!!.abilityGrpId shouldBe 0 // alternative rail
            }
        }

        test("plot card in hand but insufficient mana → no Cast offer with alternativeGrpId=PLOTTED row") {
            // Plot {3}{G} unpayable with 2 Forests. The non-alt-cost base Cast at 4G
            // is also unpayable. Both must be absent from active offers.
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Railway Brawler", human, ZoneType.Hand)
                }
            val b = board.bridge

            val brawlerGrpId = b.cardRepository.findGrpIdByName("Railway Brawler")!!
            val plottedAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(brawlerGrpId, KeywordAbilityIds.PLOT)!!

            val actions = board.actions()

            actions shouldNot offerAltCost(plottedAbilityGrpId)
        }

        test("plot card only in graveyard → no Cast offer with alternativeGrpId=PLOTTED row") {
            // Plot is hand-only. A plot card in graveyard must not surface a plot offer.
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Railway Brawler", human, ZoneType.Graveyard)
                }
            val b = board.bridge

            val brawlerGrpId = b.cardRepository.findGrpIdByName("Railway Brawler")!!
            val plottedAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(brawlerGrpId, KeywordAbilityIds.PLOT)!!

            val actions = board.actions()

            actions shouldNot offerAltCost(plottedAbilityGrpId)
        }

        test("a card plotted this turn is displayed beside hand without an executable cast") {
            val board =
                startWithBoard { _, human, _ ->
                    repeat(5) { addCard("Forest", human, ZoneType.Battlefield) }
                    addCard("Railway Brawler", human, ZoneType.Exile).setPlotted(true)
                }
            val card =
                board.game.humanPlayer.exile
                    .card("Railway Brawler")
            card.setTurnInZone(board.game.phaseHandler.turn)
            val iid = board.instanceId(card.id)
            val actions = board.actions()
            actions.actionsList.any { it.instanceId == iid && it.actionType == ActionType.Cast } shouldBe false
            actions.inactiveActionsList.any { it.instanceId == iid && it.actionType == ActionType.Cast } shouldBe true
        }

        test("plot display permissions do not expose the other player's card as our cast") {
            val board =
                startWithBoard { _, _, opponent ->
                    addCard("Railway Brawler", opponent, ZoneType.Exile).setPlotted(true)
                }
            val card =
                board.game.players[1]
                    .getCardsIn(ZoneType.Exile)
                    .single()
            card.setTurnInZone(board.game.phaseHandler.turn)
            val iid = board.instanceId(card.id)
            val actions = board.actions()
            (actions.actionsList + actions.inactiveActionsList).any {
                it.instanceId == iid && it.actionType == ActionType.Cast
            } shouldBe false
        }
    })
