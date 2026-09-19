package leyline.mechanics.harmonize

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.TapPaymentDescriptor
import leyline.bridge.handoff.TapPaymentKind
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

private val PUZZLE =
    """
    [metadata]
    Name:Harmonize Winternight Stories
    Goal:Cast Winternight Stories from graveyard with Harmonize.
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humangraveyard=Winternight Stories
    humanhand=Island;Island
    humanbattlefield=Island;Island;Island;Island;Island
    humanlibrary=Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

private val REDUCE_COST_PUZZLE =
    """
    [metadata]
    Name:Harmonize Winternight Stories - Reduce Cost
    Goal:Cast Winternight Stories from graveyard with Harmonize, tapping a creature to reduce the cost.
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humangraveyard=Winternight Stories
    humanhand=Island;Island
    humanbattlefield=Island;Island;Island;Island;Island;Grizzly Bears
    humanlibrary=Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

// {4}{U} Harmonize with only three lands: castable only if tapping a creature can cover the rest.
private fun shortOnLandsPuzzle(creature: String?) =
    """
    [metadata]
    Name:Harmonize availability
    Goal:Survive
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humangraveyard=Winternight Stories
    humanbattlefield=Island;Island;Island${creature?.let { ";$it" }.orEmpty()}
    humanlibrary=Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

private fun leyline.tooling.headless.MatchFlowHarness.harmonizeOffered(): Boolean {
    val iid = bridge.instanceId(human.getZone(ZoneType.Graveyard).cards.single { it.name == "Winternight Stories" })
    return allMessages
        .last { it.hasActionsAvailableReq() }
        .actionsAvailableReq.actionsList
        .any { it.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Cast && it.instanceId == iid }
}

class HarmonizeLifecycleTest :
    SessionTest({
        session(
            "Harmonize is offered from the graveyard when a creature's power covers the shortfall",
            puzzle = shortOnLandsPuzzle("Grizzly Bears"),
        ) {
            harmonizeOffered() shouldBe true
        }

        session("Harmonize is not offered when neither lands nor creatures can pay", puzzle = shortOnLandsPuzzle(null)) {
            harmonizeOffered() shouldBe false
        }

        session("Winternight Stories casts from graveyard with Harmonize", puzzle = PUZZLE) {
            val cardGrpId = bridge.cardRepository.findGrpIdByName("Winternight Stories")!!
            val harmonizeAbilityGrpId = bridge.cardRepository.findKeywordAbilityGrpId(cardGrpId, KeywordAbilityIds.HARMONIZE)!!

            val snap = messageSnapshot()
            castSpellByName(
                "Winternight Stories",
                zone = forge.game.zone.ZoneType.Graveyard,
                alternativeGrpId = harmonizeAbilityGrpId,
            ).shouldBeTrue()

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("alternateCostGrpId") == harmonizeAbilityGrpId }

            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                cto.detailInt("castAbilityGrpId") shouldBe harmonizeAbilityGrpId
            }
        }

        // Forge's Harmonize reduce-cost mechanic (GameActionUtil.addExtraKeywordCost):
        // 1. chooseNumber "Choose power of creature to tap" (0..max creature power) — a
        //    NumericInputReq.
        // 2. addKeywordCost adds `tapXType<1/Creature.powerEQ{n}/...>` to payCosts.
        // 3. Paying that cost asks which creature to tap — a PayCostsReq.
        // Step 3 fell back to an unclassified Generic prompt because
        // TapPaymentDescriptor had no grounded promptId for TapExact/1, so the
        // client never saw a way to pick the creature ("Pay X" screen, then
        // stuck). Grounded against Arena's own Prompts table: Id=183 is
        // "Harmonize: Tap a creature to reduce the cost?".
        session("tapping a creature reduces the mana cost and the spell resolves", puzzle = REDUCE_COST_PUZZLE) {
            val cardGrpId = bridge.cardRepository.findGrpIdByName("Winternight Stories")!!
            val harmonizeAbilityGrpId = bridge.cardRepository.findKeywordAbilityGrpId(cardGrpId, KeywordAbilityIds.HARMONIZE)!!
            val bearIid = human.battlefield.iid("Grizzly Bears")

            nextNumericInput(2)
            castSpellByName(
                "Winternight Stories",
                zone = ZoneType.Graveyard,
                alternativeGrpId = harmonizeAbilityGrpId,
            ).shouldBeTrue()

            val payCosts = allMessages.last { it.hasPayCostsReq() }
            assertSoftly {
                payCosts.prompt.promptId shouldBe checkNotNull(TapPaymentDescriptor.grounded(TapPaymentKind.TapExact, 1)).promptId
                payCosts.payCostsReq.effectCostReq.costSelection.idsList shouldContain bearIid
            }

            respondToEffectCost(listOf(bearIid))

            human.battlefield
                .card("Grizzly Bears")
                .isTapped
                .shouldBeTrue()

            // The draw-three resolves into "discard two unless you discard a
            // creature" (SVar$ DBDiscard); the drawn/held hand has no creature,
            // so discard the two drawn Plains explicitly.
            passUntil(maxPasses = 10) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val discardReq = lastSelectNReq()
            val discardIds = discardReq.idsList.filter { cardName(it) == "Plains" }.take(2)
            respondToSelectN(discardIds)

            passUntilResolved(maxPasses = 10)

            assertSoftly {
                // Reduced {4}{U} by Grizzly Bears' power (2) to {2}{U} = 3 mana;
                // only 3 of the 5 Islands should have been tapped to pay it.
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .count { it.name == "Island" && it.isTapped } shouldBe 3
                human
                    .getZone(ZoneType.Exile)
                    .cards
                    .map { it.name } shouldContain "Winternight Stories"
                human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .map { it.name } shouldNotContain "Winternight Stories"
            }
        }
    })
