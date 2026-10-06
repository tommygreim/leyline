package leyline.mechanics.flashback

import forge.game.phase.PhaseType
import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import leyline.bridge.getAllCastableAbilities
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.beAltCostOffer
import leyline.testkit.humanPlayer
import leyline.testkit.offerAltCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class FlashbackActionTest :
    BoardTest({
        test("unpayable flashback card in graveyard is inactive") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Think Twice", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            val thinkTwiceIid = human.graveyard.iid("Think Twice")
            val thinkTwiceGrpId = b.cardRepository.findGrpIdByName("Think Twice")!!
            val flashbackAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(thinkTwiceGrpId, KeywordAbilityIds.FLASHBACK)!!

            val actions =
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = SnapshotCapture.run(game, b, "test", 0),
                    bridge = b,
                )

            val activeCastActions =
                actions.actionsList.filter {
                    it.actionType == ActionType.Cast && it.instanceId == thinkTwiceIid
                }
            activeCastActions.shouldBeEmpty()
            val flashbackOffer =
                actions.inactiveActionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == thinkTwiceIid &&
                        it.alternativeGrpId == flashbackAbilityGrpId
                }
            flashbackOffer should beAltCostOffer(flashbackAbilityGrpId)
        }

        test("flashback card only in hand has no graveyard alt-cost offer") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Think Twice", human, ZoneType.Hand)
                }
            val human = game.humanPlayer
            val thinkTwiceGrpId = b.cardRepository.findGrpIdByName("Think Twice")!!
            val flashbackAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(thinkTwiceGrpId, KeywordAbilityIds.FLASHBACK)!!
            val card = human.getZone(ZoneType.Hand).cards.first { it.name == "Think Twice" }

            val handFlashbackSa =
                getAllCastableAbilities(card, human)
                    .firstOrNull { it.alternativeCost == AlternativeCost.Flashback }
            handFlashbackSa shouldBe null

            val actions =
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = SnapshotCapture.run(game, b, "test", 0),
                    bridge = b,
                )
            actions shouldNot offerAltCost(flashbackAbilityGrpId)
        }

        test("sorcery flashback remains visible as an inactive graveyard offer outside its timing window") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Faithless Looting", human, ZoneType.Graveyard)
                }
            val human = game.humanPlayer
            // Main1 is the default board phase.  Move to combat so the
            // Flashback SA is filtered from the timed candidate set while it
            // remains a valid card-rail affordance.
            game.phaseHandler.devModeSet(PhaseType.COMBAT_BEGIN, human)

            val card = human.getZone(ZoneType.Graveyard).cards.single { it.name == "Faithless Looting" }
            val iid = b.instanceId(card.id)
            val grpId = b.cardRepository.findGrpIdByName("Faithless Looting")!!
            val flashbackAbilityGrpId =
                b.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.FLASHBACK)!!

            val actions =
                ActionMapper.buildFromSnapshot(
                    seatId = 1,
                    snap = SnapshotCapture.run(game, b, "test", 0),
                    bridge = b,
                )

            actions.actionsList.none { it.actionType == ActionType.Cast && it.instanceId == iid } shouldBe true
            actions.inactiveActionsList.any {
                it.actionType == ActionType.Cast &&
                    it.instanceId == iid &&
                    it.alternativeGrpId == flashbackAbilityGrpId
            } shouldBe true
        }
    })
