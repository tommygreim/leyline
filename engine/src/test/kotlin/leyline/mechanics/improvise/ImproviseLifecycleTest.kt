package leyline.mechanics.improvise

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.clientMessage
import leyline.testkit.detailInt
import leyline.testkit.hasDetail
import leyline.testkit.haveManaCost
import leyline.testkit.performAction
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import wotc.mtgo.gre.external.messaging.Messages.PerformAutoTapActionsResp

class ImproviseLifecycleTest :
    SessionTest({
        session(
            "Improvise Cancel abandons a partial artifact selection without paying ordinary mana",
            puzzleFile = "data/puzzles/payment-improvise-ironheart.pzl",
            fullControl = true,
        ) {
            after { castSpellByName("Ironheart, Clever Champion").shouldBeTrue() }.expectOnePayCostsReq()
            after { respondToMakePayment(human.battlefield.iid("Ornithopter")) }.expectOnePayCostsReq()
            cancelAction()
            assertSoftly {
                human.hand
                    .card("Ironheart, Clever Champion")
                    .zone.zoneType shouldBe forge.game.zone.ZoneType.Hand
                human.getZone(forge.game.zone.ZoneType.Battlefield).cards.all { !it.isTapped } shouldBe true
                game().stack.isEmpty shouldBe true
                game().stackZone.shouldBeEmpty()
            }
            after { castSpellByName("Ironheart, Clever Champion").shouldBeTrue() }.expectOnePayCostsReq()
            respondToMakePayment(human.battlefield.iid("Ornithopter"))
            respondToPaymentDone()
            passUntilResolved(maxPasses = 8)
            assertSoftly {
                human.battlefield.card("Ironheart, Clever Champion").name shouldBe "Ironheart, Clever Champion"
                human.battlefield.card("Ornithopter").isTapped shouldBe true
                human
                    .getZone(forge.game.zone.ZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Island" }
                    .count { it.isTapped } shouldBe 4
                game().stack.isEmpty shouldBe true
            }
        }

        session(
            "Ironheart pays Improvise through PayCostsReq",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Ironheart, Clever Champion
                humanbattlefield=Island;Ornithopter;Short Sword;Witching Well;Gleaming Barrier
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val artifactIids =
                listOf(
                    human.battlefield.iid("Ornithopter"),
                    human.battlefield.iid("Short Sword"),
                    human.battlefield.iid("Witching Well"),
                    human.battlefield.iid("Gleaming Barrier"),
                )
            val ironheartIid = human.hand.iid("Ironheart, Clever Champion")
            val castAction =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq
                    .actionsList
                    .single { it.actionType == ActionType.Cast && it.instanceId == ironheartIid }

            assertSoftly {
                castAction should haveManaCost(generic = 4, blue = 1)
                castAction.hasAutoTapSolution() shouldBe true
                castAction.autoTapSolution.autoTapActionsList.count { it.abilityGrpId == KeywordAbilityIds.IMPROVISE } shouldBe 4
            }

            val initialPayCosts = after { castSpellByName("Ironheart, Clever Champion").shouldBeTrue() }.expectOnePayCostsReq()
            initialPayCosts.paymentActions.actionsList
                .filter { it.actionType == ActionType.MakePayment }
                .map { it.instanceId }
                .toSet() shouldBe artifactIids.toSet()
            initialPayCosts.paymentActions.actionsList.filter { it.actionType == ActionType.MakePayment }.forEach { action ->
                val mana =
                    action.manaPaymentOptionsList
                        .single()
                        .manaList
                        .single()
                assertSoftly {
                    mana.color shouldBe ManaColor.Colorless_afc9
                    mana.abilityGrpId shouldBe KeywordAbilityIds.IMPROVISE
                    mana.specsList.map { it.type } shouldContainAll listOf(ManaSpecType.ManaSubstitution)
                }
            }

            val paymentSnap = messageSnapshot()
            artifactIids.forEach { respondToMakePayment(it) }
            respondToPaymentDone()

            val annotations = annotationsSince(paymentSnap)
            val tappedIds = annotations.filter { AnnotationType.TappedUntappedPermanent in it.typeList }.flatMap { it.affectedIdsList }
            val paidByImprovise =
                annotations.filter {
                    AnnotationType.ManaPaid in it.typeList &&
                        it.hasDetail(DetailKeys.SUBSTITUTION_GRPID) &&
                        it.detailInt(DetailKeys.SUBSTITUTION_GRPID) == KeywordAbilityIds.IMPROVISE
                }

            assertSoftly {
                tappedIds shouldContainAll artifactIids
                paidByImprovise.map { it.affectorId }.toSet() shouldBe artifactIids.toSet()
                messagesSince(paymentSnap).persistentAnnotationsOfType(AnnotationType.CastingTimeOption).shouldBeEmpty()
            }
        }
    })

private fun MatchFlowHarness.respondToMakePayment(instanceId: Int) {
    send(
        submitWithGsId(
            performAction {
                actionType = ActionType.MakePayment
                this.instanceId = instanceId
            }.toBuilder().setGameStateId(latestPromptGsId()).build(),
        ),
    )
    drainSink()
}

private fun MatchFlowHarness.respondToPaymentDone() {
    send(
        submitWithGsId(
            clientMessage(ClientMessageType.PerformAutoTapActionsResp_097b) {
                gameStateId = latestPromptGsId()
                setPerformAutoTapActionsResp(PerformAutoTapActionsResp.newBuilder().setIndex(0))
            },
        ),
    )
    drainSink()
}
