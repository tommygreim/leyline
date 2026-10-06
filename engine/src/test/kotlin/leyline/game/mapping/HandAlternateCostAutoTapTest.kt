package leyline.game.mapping

import forge.game.spellability.AlternativeCost
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PlayerAction
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.beAltCostOffer
import leyline.testkit.haveManaCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/** Client-visible mana plans for payable alternative casts from hand. */
class HandAlternateCostAutoTapTest :
    BoardTest({
        test("Web-slinging has a payable highlighted cast while its printed cast is unaffordable") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Raging Goblin", human, ZoneType.Battlefield).setTapped(true)
                    addCard("Grizzly Bears", human, ZoneType.Battlefield).setTapped(true)
                    addCard("Spider-Man, Web-Slinger", human, ZoneType.Hand)
                }
            val human = board.human
            val spider = human.hand.card("Spider-Man, Web-Slinger")
            val plains = human.battlefield.card("Plains")
            val goblin = human.battlefield.card("Raging Goblin")
            val bears = human.battlefield.card("Grizzly Bears")
            val spiderIid = board.instanceId(spider.id)
            val spiderGrpId = checkNotNull(board.bridge.cardRepository.findGrpIdByName(spider.name))
            val webGrpId =
                checkNotNull(board.bridge.cardRepository.findKeywordAbilityGrpId(spiderGrpId, KeywordAbilityIds.WEB_SLINGING))

            val actions = board.actions()
            val activeCasts = actions.actionsList.filter { it.actionType == ActionType.Cast && it.instanceId == spiderIid }
            val inactiveCasts = actions.inactiveActionsList.filter { it.actionType == ActionType.Cast && it.instanceId == spiderIid }
            activeCasts shouldHaveSize 1
            inactiveCasts shouldHaveSize 1
            val webCast = activeCasts.single()
            assertSoftly {
                webCast should beAltCostOffer(webGrpId)
                webCast.alternativeGrpId shouldBe webGrpId
                webCast.abilityGrpId shouldBe 0
                webCast.hasAutoTapSolution() shouldBe true
                webCast.autoTapSolution.autoTapActionsCount shouldBe 1
                webCast.autoTapSolution.autoTapActionsList.map { it.instanceId } shouldBe listOf(board.instanceId(plains.id))
                webCast should haveManaCost(white = 1)
                inactiveCasts.single().alternativeGrpId shouldBe 0
                spider.zone.zoneType shouldBe ZoneType.Hand
                plains.isTapped shouldBe false
                goblin.isTapped shouldBe true
                bears.isTapped shouldBe true
                human.getZone(ZoneType.Battlefield).cards.toSet() shouldBe setOf(plains, goblin, bears)
            }
        }

        test("Web-slinging is unavailable without a tapped return creature") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Raging Goblin", human, ZoneType.Battlefield)
                    addCard("Spider-Man, Web-Slinger", human, ZoneType.Hand)
                }
            val spider = board.human.hand.card("Spider-Man, Web-Slinger")
            val spiderIid = board.instanceId(spider.id)
            val grpId = checkNotNull(board.bridge.cardRepository.findGrpIdByName(spider.name))
            val webGrpId = checkNotNull(board.bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.WEB_SLINGING))

            val actions = board.actions()
            assertSoftly {
                actions.actionsList.none {
                    it.actionType == ActionType.Cast && it.instanceId == spiderIid && it.alternativeGrpId == webGrpId
                } shouldBe
                    true
                actions.inactiveActionsList
                    .single { it.actionType == ActionType.Cast && it.instanceId == spiderIid }
                    .alternativeGrpId shouldBe 0
                board.human.battlefield
                    .card("Raging Goblin")
                    .isTapped shouldBe false
            }
        }

        test("Emerge has a reduced mana plan without changing its selected sacrifice state or battlefield") {
            val board =
                startWithBoard { _, human, _ ->
                    repeat(4) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Walking Corpse", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Wretched Gryff", human, ZoneType.Hand)
                }
            val human = board.human
            val gryff = human.hand.card("Wretched Gryff")
            val corpse = human.battlefield.card("Walking Corpse")
            val bears = human.battlefield.card("Grizzly Bears")
            val battlefieldBefore = human.getZone(ZoneType.Battlefield).cards.toSet()
            val gryffIid = board.instanceId(gryff.id)
            val gryffGrpId = checkNotNull(board.bridge.cardRepository.findGrpIdByName(gryff.name))
            val emergeGrpId = checkNotNull(board.bridge.cardRepository.findKeywordAbilityGrpId(gryffGrpId, KeywordAbilityIds.EMERGE))

            val projection =
                ActionMapper.buildProjectionFromSnapshot(
                    BoardTest.SEAT_ID,
                    SnapshotCapture.run(board.game, board.bridge, "emerge-highlight", 0),
                    board.bridge,
                )
            val actions = projection.actions
            val activeCasts = actions.actionsList.filter { it.actionType == ActionType.Cast && it.instanceId == gryffIid }
            val inactiveCasts = actions.inactiveActionsList.filter { it.actionType == ActionType.Cast && it.instanceId == gryffIid }
            activeCasts shouldHaveSize 1
            inactiveCasts shouldHaveSize 1
            val emergeCast = activeCasts.single()
            val castCommand = projection.offers.single { it.action == emergeCast }.command as PlayerAction.CastSpell
            assertSoftly {
                emergeCast should beAltCostOffer(emergeGrpId)
                emergeCast should haveManaCost(generic = 5, blue = 1)
                emergeCast.alternativeGrpId shouldBe emergeGrpId
                emergeCast.abilityGrpId shouldBe 0
                emergeCast.hasAutoTapSolution() shouldBe true
                emergeCast.autoTapSolution.autoTapActionsCount shouldBe 4
                emergeCast.autoTapSolution.autoTapActionsList.none {
                    it.instanceId == board.instanceId(corpse.id) || it.instanceId == board.instanceId(bears.id)
                } shouldBe true
                inactiveCasts.single().alternativeGrpId shouldBe 0
                castCommand.ability?.alternativeCost shouldBe AlternativeCost.Emerge
                castCommand.ability?.sacrificedAsEmerge shouldBe null
                corpse.isUsedToPay shouldBe false
                bears.isUsedToPay shouldBe false
                battlefieldBefore.all { !it.isTapped } shouldBe true
                human.getZone(ZoneType.Battlefield).cards.toSet() shouldBe battlefieldBefore
                gryff.zone.zoneType shouldBe ZoneType.Hand
            }
        }

        test("Web-slinging is unavailable with a tapped return creature but no white mana") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Raging Goblin", human, ZoneType.Battlefield).setTapped(true)
                    addCard("Spider-Man, Web-Slinger", human, ZoneType.Hand)
                }
            val spiderIid = board.human.hand.iid("Spider-Man, Web-Slinger")
            val actions = board.actions()
            assertSoftly {
                actions.actionsList.none { it.actionType == ActionType.Cast && it.instanceId == spiderIid } shouldBe true
                actions.inactiveActionsList
                    .single { it.actionType == ActionType.Cast && it.instanceId == spiderIid }
                    .hasAutoTapSolution() shouldBe false
                board.human.battlefield
                    .card("Island")
                    .isTapped shouldBe false
                board.human.battlefield
                    .card("Raging Goblin")
                    .isTapped shouldBe true
            }
        }

        test("Emerge plans flexible sources against the residual cost rather than the displayed cost") {
            val board =
                startWithBoard { _, human, _ ->
                    repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Steam Vents", human, ZoneType.Battlefield)
                    addCard("Walking Corpse", human, ZoneType.Battlefield)
                    addCard("Wretched Gryff", human, ZoneType.Hand)
                }
            val gryffIid = board.human.hand.iid("Wretched Gryff")
            val corpse = board.human.battlefield.card("Walking Corpse")
            val ventsIid = board.human.battlefield.iid("Steam Vents")
            val cast = board.actions().actionsList.single { it.actionType == ActionType.Cast && it.instanceId == gryffIid }
            val payments = cast.autoTapSolution.autoTapActionsList
            assertSoftly {
                cast should haveManaCost(generic = 5, blue = 1)
                payments shouldHaveSize 4
                payments.any { it.instanceId == ventsIid } shouldBe true
                payments.flatMap { it.manaPaymentOption.manaList }.any { it.color == ManaColor.Blue_afc9 } shouldBe true
                corpse.isUsedToPay shouldBe false
                corpse.zone.zoneType shouldBe ZoneType.Battlefield
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .all { !it.isTapped } shouldBe true
            }
        }

        test("Emerge is unavailable with insufficient mana despite sacrifice creatures") {
            val board =
                startWithBoard { _, human, _ ->
                    repeat(2) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Walking Corpse", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Wretched Gryff", human, ZoneType.Hand)
                }
            val gryff = board.human.hand.card("Wretched Gryff")
            val gryffIid = board.instanceId(gryff.id)
            val grpId = checkNotNull(board.bridge.cardRepository.findGrpIdByName(gryff.name))
            val emergeGrpId = checkNotNull(board.bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.EMERGE))

            val actions = board.actions()
            assertSoftly {
                actions.actionsList.none {
                    it.actionType == ActionType.Cast && it.instanceId == gryffIid && it.alternativeGrpId == emergeGrpId
                } shouldBe true
                actions.inactiveActionsList
                    .single { it.actionType == ActionType.Cast && it.instanceId == gryffIid }
                    .alternativeGrpId shouldBe 0
                gryff.zone.zoneType shouldBe ZoneType.Hand
            }
        }

        test("Emerge is unavailable without a sacrifice creature") {
            val board =
                startWithBoard { _, human, _ ->
                    repeat(4) { addCard("Island", human, ZoneType.Battlefield) }
                    addCard("Wretched Gryff", human, ZoneType.Hand)
                }
            val gryff = board.human.hand.card("Wretched Gryff")
            val gryffIid = board.instanceId(gryff.id)
            val grpId = checkNotNull(board.bridge.cardRepository.findGrpIdByName(gryff.name))
            val emergeGrpId = checkNotNull(board.bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.EMERGE))

            val actions = board.actions()
            assertSoftly {
                actions.actionsList.none {
                    it.actionType == ActionType.Cast && it.instanceId == gryffIid && it.alternativeGrpId == emergeGrpId
                } shouldBe true
                actions.inactiveActionsList
                    .single { it.actionType == ActionType.Cast && it.instanceId == gryffIid }
                    .alternativeGrpId shouldBe 0
                gryff.zone.zoneType shouldBe ZoneType.Hand
            }
        }

        test("Warp also includes a mana plan on its active hand alternate cast") {
            val board =
                startWithBoard { _, human, _ ->
                    repeat(2) { addCard("Forest", human, ZoneType.Battlefield) }
                    addCard("Germinating Wurm", human, ZoneType.Hand)
                }
            val wurm = board.human.hand.card("Germinating Wurm")
            val wurmIid = board.instanceId(wurm.id)
            val grpId = checkNotNull(board.bridge.cardRepository.findGrpIdByName(wurm.name))
            val warpGrpId = checkNotNull(board.bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.WARP))

            val actions = board.actions()
            val warpCast =
                actions.actionsList.single {
                    it.actionType == ActionType.Cast && it.instanceId == wurmIid && it.alternativeGrpId == warpGrpId
                }
            assertSoftly {
                warpCast should beAltCostOffer(warpGrpId)
                warpCast.hasAutoTapSolution() shouldBe true
                warpCast.autoTapSolution.autoTapActionsCount shouldBe 2
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .all { !it.isTapped } shouldBe true
                wurm.zone.zoneType shouldBe ZoneType.Hand
            }
        }
    })
