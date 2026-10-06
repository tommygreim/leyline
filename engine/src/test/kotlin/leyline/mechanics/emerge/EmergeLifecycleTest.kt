package leyline.mechanics.emerge

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

class EmergeLifecycleTest :
    SessionTest({
        session(
            "Wretched Gryff pays Emerge through sacrifice cost prompt",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Wretched Gryff
                humanbattlefield=Island;Island;Island;Island;Walking Corpse;Grizzly Bears
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val gryffGrpId = bridge.cardRepository.findGrpIdByName("Wretched Gryff")!!
            val emergeAbilityGrpId = bridge.cardRepository.findKeywordAbilityGrpId(gryffGrpId, KeywordAbilityIds.EMERGE)!!
            val corpseIid = human.battlefield.iid("Walking Corpse")
            val bearIid = human.battlefield.iid("Grizzly Bears")
            val emergeOffer =
                allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                    it.actionType == ActionType.Cast && it.alternativeGrpId == emergeAbilityGrpId
                }
            assertSoftly(emergeOffer) {
                hasAutoTapSolution() shouldBe true
                autoTapSolution.autoTapActionsCount shouldBe 4
                autoTapSolution.autoTapActionsList.none { it.instanceId == corpseIid || it.instanceId == bearIid } shouldBe true
            }

            val snap = messageSnapshot()
            val payCosts =
                after {
                    castSpellByName(
                        "Wretched Gryff",
                        alternativeGrpId = emergeAbilityGrpId,
                    ).shouldBeTrue()
                }.expectOnePayCostsReq()

            assertSoftly {
                allMessages.last { it.hasPrompt() }.prompt.promptId shouldBe PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE
                payCosts.effectCostReq.costSelection.idsList shouldContain corpseIid
                payCosts.effectCostReq.costSelection.idsList shouldContain bearIid
            }

            respondToEffectCost(listOf(corpseIid))
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Walking Corpse"
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Grizzly Bears"
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .any { it.name == "Wretched Gryff" }
                    .shouldBeTrue()
                val cto =
                    messagesSince(snap)
                        .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                        .first { it.detailInt("alternateCostGrpId") == emergeAbilityGrpId }
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                cto.detailInt("castAbilityGrpId") shouldBe emergeAbilityGrpId
            }
        }
    })
