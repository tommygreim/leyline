package leyline.testkit

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.resolveActionOffer
import leyline.bridge.handoff.ActionResponseKey
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot

class GrantedManaIdentityTest :
    SessionTest({
        for (grantedChoice in 0..1) {
            session(
                "each repeated Treasure grant executes its selected ability $grantedChoice",
                puzzle = grantedManaPuzzle,
                forgeCatalog = true,
                fullControl = true,
            ) {
                castSpellByName("Strike It Rich").shouldBeTrue()
                passUntil { human.getZone(ZoneType.Battlefield).cards.any { it.name == "Treasure Token" } }.shouldBeTrue()
                val treasure = human.battlefield.card("Treasure Token")
                val projection = ActionMapper.buildProjectionFromSnapshot(1, GsmSnapshot.capture(game(), bridge, "test", 0), bridge)
                val grants =
                    projection.offers.filter {
                        val command = it.command as? leyline.bridge.handoff.PlayerAction.ActivateMana
                        command?.ability?.grantorStatic != null
                    }
                val projected =
                    allMessages
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .last { it.instanceId == human.battlefield.iid(treasure) }
                assertSoftly {
                    grants.size shouldBe 2
                    grants.map { it.action.abilityGrpId }.distinct().size shouldBe 1
                    grants.first().action.abilityGrpId shouldBeGreaterThan 0
                    grants.map { it.action.uniqueAbilityId }.distinct().size shouldBe 2
                    grants.forEach { offer ->
                        projected.uniqueAbilitiesList
                            .any { it.id == offer.action.uniqueAbilityId && it.grpId == offer.action.abilityGrpId }
                            .shouldBeTrue()
                    }
                    leyline.bridge.coord.hasAmbiguousActionCatalog(projection.offers) shouldBe false
                }
                val selected = grants[grantedChoice].action
                val response = selected.toBuilder()
                response.getManaPaymentOptionsBuilder(0).getManaBuilder(0).color =
                    wotc.mtgo.gre.external.messaging.Messages.ManaColor.Red_afc9
                val catalog =
                    grants
                        .mapIndexed { index, offer -> index.toLong() to offer }
                        .groupBy { ActionResponseKey.from(it.second.action) }
                resolveActionOffer(catalog, response.build())?.second shouldBe grants[grantedChoice]
                resolveActionOffer(catalog, response.clone().clearUniqueAbilityId().build()) shouldBe null
                submitAction(response.build())
                assertSoftly {
                    human.manaPool.totalMana() shouldBe 2
                    human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .none { it.name == "Treasure Token" }
                        .shouldBeTrue()
                }
                castSpellByName("Lightning Strike").shouldBeTrue()
                selectTargets(listOf(OPPONENT_SEAT))
                passUntil { isGameOver() }.shouldBeTrue()
                human.hasWon().shouldBeTrue()
            }
        }
    })

private val grantedManaPuzzle =
    """
# Mechanic: Distinct mana abilities granted to one Treasure retain selectable identities.
# Forcing: The Mountain pays Strike It Rich, leaving Treasure as the only spell payment.
# AI behavior: No opponent hand or battlefield choices.
# Failure modes: Catalog failure blocks priority; printed mana alone cannot pay Lightning Strike.
# Card roles: Two Goldspans grant mana; Strike It Rich creates Treasure; Lightning Strike consumes it.
# Protocol path: Cast, token creation, granted mana offers, sacrifice, spell payment and damage.
[metadata]
Name:Repeated granted Treasure mana
Goal:Win
Turns:3
Difficulty:Easy
Description:Create Treasure under two Goldspan Dragons and use its granted mana to cast Lightning Strike.
[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=3
humanbattlefield=Goldspan Dragon;Goldspan Dragon;Mountain
humanhand=Strike It Rich;Lightning Strike
humanlibrary=Mountain;Mountain;Mountain
ailibrary=Forest;Forest;Forest
    """.trimIndent()
