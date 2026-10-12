package leyline.mechanics.waterbend

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.BoardTest
import leyline.testkit.haveManaCost
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType

class WaterbendAffordanceTest :
    BoardTest({
        test("Waterbend activation has an exact reducer plan when ordinary mana alone is insufficient") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Giant Koi", human, ZoneType.Battlefield)
                    addCard("Coral Merfolk", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Short Sword", human, ZoneType.Battlefield)
                    repeat(2) { addCard("Island", human, ZoneType.Battlefield) }
                }
            val koi = board.human.battlefield.card("Giant Koi")
            val ability = koi.spellAbilities.single { it.payCosts?.maxWaterbend != null }
            val originalWaterbend = ability.maxWaterbend
            val action =
                board.actions().actionsList.single {
                    it.actionType == ActionType.Activate_add3 &&
                        it.instanceId == board.instanceId(koi.id)
                }
            val payments = action.autoTapSolution.autoTapActionsList
            assertSoftly {
                action should haveManaCost(generic = 3)
                action.hasAutoTapSolution() shouldBe true
                payments shouldHaveSize 3
                payments.map { it.instanceId }.distinct() shouldHaveSize 3
                payments.all { it.abilityGrpId == KeywordAbilityIds.WATERBEND_PAYMENT } shouldBe true
                payments.flatMap { it.manaPaymentOption.manaList }.all { mana ->
                    mana.specsList.any { it.type == ManaSpecType.ManaSubstitution }
                } shouldBe true
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .all { !it.isTapped && !it.isUsedToPay } shouldBe true
                ability.maxWaterbend shouldBe originalWaterbend
            }
            // Repeated publication must not depend on context left by the first preview.
            board
                .actions()
                .actionsList
                .single { it.actionType == ActionType.Activate_add3 && it.instanceId == board.instanceId(koi.id) }
                .autoTapSolution shouldBe action.autoTapSolution
        }

        test("Waterbend activation is inactive when tapped reducers leave insufficient mana") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Giant Koi", human, ZoneType.Battlefield).setTapped(true)
                    addCard("Coral Merfolk", human, ZoneType.Battlefield).setTapped(true)
                    addCard("Short Sword", human, ZoneType.Battlefield).setTapped(true)
                    repeat(2) { addCard("Island", human, ZoneType.Battlefield) }
                }
            val koiIid = board.human.battlefield.iid("Giant Koi")
            val actions = board.actions()
            assertSoftly {
                actions.actionsList.none { it.actionType == ActionType.Activate_add3 && it.instanceId == koiIid } shouldBe true
                actions.inactiveActionsList
                    .single { it.actionType == ActionType.Activate_add3 && it.instanceId == koiIid }
                    .hasAutoTapSolution() shouldBe false
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Island" }
                    .all { !it.isTapped } shouldBe true
            }
        }
    })
