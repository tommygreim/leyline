package leyline.mechanics.altcost

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.after
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

/**
 * `K:AlternateAdditionalCost` where neither branch is Sacrifice, Exile, or
 * Blight — the only shapes [leyline.bridge.coord.DeferredCastCostPlanMaterializer]
 * could label until this test's card, Bitter Triumph
 * (`AlternateAdditionalCost:PayLife<3>:Discard<1/Card>`), was reported hanging
 * live: both branches came back unlabeled, and the resulting `CastingTimeOptionsReq`
 * carried two `SelectNReq` ids pointing at zero `Prompt.Parameters`. The client's
 * `CastingTimeOption_ChooseOrCostRequest` constructor indexes straight into that
 * list with no bounds check, throws, and drops the whole message — the game
 * never recovers from that CastingTimeOptionsReq. See ISSUES.md.
 */
class AlternateAdditionalCostChoiceTest :
    SessionTest({
        session(
            "an AlternateAdditionalCost branch outside Sacrifice/Exile/Blight still gets a labeled option",
            puzzleFile = "data/puzzles/bitter-triumph-alternate-cost.pzl",
        ) {
            val option =
                after { castSpellByName("Bitter Triumph") }
                    .expectOneCastingTimeOptionsReq()
                    .castingTimeOptionReqList
                    .single()

            assertSoftly {
                option.castingTimeOptionType shouldBe CastingTimeOptionType.ChooseOrCost
                option.isRequired shouldBe true
                // Every id in SelectNReq must resolve to a real Prompt.Parameters
                // entry — the client indexes into this list unconditionally, and a
                // short list is what hung the game live.
                option.selectNReq.idsList shouldBe listOf(1, 2)
                option.selectNReq.prompt.parametersList shouldHaveSize 2
                option.selectNReq.prompt.parametersList
                    .map { it.promptId } shouldContainExactlyInAnyOrder
                    listOf(PromptIds.CHOOSE_OR_COST_PAY_LIFE.getValue(3), PromptIds.CHOOSE_OR_COST_DISCARD)
                option.selectNReq.prompt.promptId shouldBe PromptIds.CHOOSE_OR_COST
            }

            // Completing the flow proves the fix end to end: before it, the client
            // never responded to this CastingTimeOptionsReq at all.
            after { respondToAlternateCost(option.ctoId, option.selectNReq.idsList.first()) }
                .expectOneSelectTargetsReq()
            selectTargets(listOf(ai.battlefield.iid("Centaur Courser")))
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                ai.getZone(ZoneType.Battlefield).cards.map { it.name } shouldNotContain "Centaur Courser"
                ai.getZone(ZoneType.Graveyard).cards.map { it.name } shouldBe listOf("Centaur Courser")
            }
        }
    })
