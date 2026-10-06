package leyline.bridge

import forge.card.mana.ManaCost
import forge.game.cost.Cost
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.StrictPromptRefusalException
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaCostSpecType

class ActionManaCostsTest :
    FunSpec({

        tags(UnitTag)

        test("strict prompt refusal bypasses the defensive affordability fallback") {
            var fallbackCalled = false

            shouldThrow<StrictPromptRefusalException> {
                ActionManaCosts.affordabilityProbe(
                    probe = { throw StrictPromptRefusalException("unexpected prompt") },
                    fallback = {
                        fallbackCalled = true
                        false
                    },
                )
            }

            fallbackCalled shouldBe false
        }

        test("ordinary Forge failure uses the defensive affordability fallback") {
            ActionManaCosts.affordabilityProbe(
                probe = { error("unsupported cost") },
                fallback = { true },
            ) shouldBe true
        }

        test("action mana requirements put generic before colored pips") {
            ActionManaCosts
                .forgeManaCostToRequirements(ManaCost("2 U"))
                .map { it.colorList to it.count } shouldBe
                listOf(
                    listOf(ManaColor.Generic) to 2,
                    listOf(ManaColor.Blue_afc9) to 1,
                )
        }

        test("colored mana requirement order is independent of Forge shard order") {
            val expected =
                listOf(
                    listOf(ManaColor.Generic) to 1,
                    listOf(ManaColor.White_afc9) to 1,
                    listOf(ManaColor.Black_afc9) to 1,
                    listOf(ManaColor.Red_afc9) to 1,
                )
            for (cost in listOf(ManaCost("1 W B R"), ManaCost("1 R W B"), ManaCost("1 B R W"))) {
                ActionManaCosts.forgeManaCostToRequirements(cost).map { it.colorList to it.count } shouldBe expected
            }
        }

        test("action mana requirements put generic before hybrid pips") {
            ActionManaCosts
                .forgeManaCostToRequirements(ManaCost("1 2/U U"))
                .map { it.colorList to it.count } shouldBe
                listOf(
                    listOf(ManaColor.Generic) to 1,
                    listOf(ManaColor.TwoGeneric, ManaColor.Blue_afc9) to 1,
                    listOf(ManaColor.Blue_afc9) to 1,
                )
        }

        test("action mana requirements retain ordinary hybrid pips") {
            ActionManaCosts
                .forgeManaCostToRequirements(ManaCost("4 U/B U/B"))
                .map { it.colorList to it.count } shouldBe
                listOf(
                    listOf(ManaColor.Generic) to 4,
                    listOf(ManaColor.Blue_afc9, ManaColor.Black_afc9) to 1,
                    listOf(ManaColor.Blue_afc9, ManaColor.Black_afc9) to 1,
                )
        }

        test("action mana requirements retain mono and two-colour Phyrexian pips") {
            ActionManaCosts
                .forgeManaCostToRequirements(ManaCost("1 B/P B/G/P"))
                .map { it.colorList to it.count } shouldBe
                listOf(
                    listOf(ManaColor.Generic) to 1,
                    listOf(ManaColor.Black_afc9, ManaColor.Phyrexian_afc9) to 1,
                    listOf(ManaColor.Black_afc9, ManaColor.Green_afc9, ManaColor.Phyrexian_afc9) to 1,
                )
        }

        test("Waterbend is copied from Forge cost metadata onto every mana requirement") {
            val cost = Cost("Waterbend<3>", false)
            val specs = ActionManaCosts.manaCostSpecs(cost)

            ActionManaCosts
                .forgeManaCostToRequirements(ManaCost("3"), specs = specs)
                .map { it.specsList }
                .shouldBe(listOf(listOf(ManaCostSpecType.Waterbend)))
        }

        test("ordinary mana costs do not acquire cost-side specs") {
            ActionManaCosts
                .manaCostSpecs(Cost("3", false)) shouldBe emptyList()
        }
    })
