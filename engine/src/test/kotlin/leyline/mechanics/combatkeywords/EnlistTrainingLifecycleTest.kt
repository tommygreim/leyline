package leyline.mechanics.combatkeywords

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.annotation
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.detailUint
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class EnlistTrainingLifecycleTest :
    SessionTest({
        session("Training attack trigger resolves as keyword counter ability", puzzle = trainingPuzzle) {
            val hopefulIid = human.battlefield.iid("Hopeful Initiate")
            val courserIid = human.battlefield.iid("Centaur Courser")
            advanceToAttackersReq()

            val snap = messageSnapshot()
            toggleAttackers(listOf(hopefulIid, courserIid))
            submitAttackers()
            passUntil(maxPasses = 20) {
                messagesSince(snap).any { msg ->
                    msg.hasGameStateMessage() &&
                        msg.gameStateMessage.annotationsList.any { AnnotationType.ResolutionStart in it.typeList }
                }
            }.shouldBeTrue()

            val resolutionGsm =
                messagesSince(snap).gameStateMessages().last { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.ResolutionStart in it.typeList && it.detailUint("grpid") == 220
                    }
                }
            val trainingActive =
                messagesSince(snap).gameStateMessages().asReversed().flatMap { it.persistentAnnotationsList }.first {
                    AnnotationType.AbilityWordActive in it.typeList &&
                        it.detailString("AbilityWordName") == "Training"
                }
            val counterAdded =
                messagesSince(snap).gameStateMessages().flatMap { it.annotationsList }.first {
                    AnnotationType.CounterAdded in it.typeList
                }
            val counterPersistent =
                messagesSince(snap).gameStateMessages().asReversed().flatMap { it.persistentAnnotationsList }.first {
                    AnnotationType.Counter_803b in it.typeList
                }

            assertSoftly {
                resolutionGsm.annotation(AnnotationType.ResolutionStart).detailUint("grpid") shouldBe 220
                resolutionGsm.annotation(AnnotationType.ResolutionComplete).detailUint("grpid") shouldBe 220
                counterAdded.affectorId shouldBe resolutionGsm.annotation(AnnotationType.ResolutionStart).affectorId
                counterAdded.affectedIdsList shouldContain hopefulIid
                counterPersistent.detailInt("count") shouldBe 1
                trainingActive.affectorId shouldBe hopefulIid
                trainingActive.affectedIdsList shouldContain courserIid
            }
        }

        session("Enlist attack cost prompts with PayCostsReq and links tapped creature", puzzle = enlistPuzzle) {
            val faithbonderIid = human.battlefield.iid("Benalish Faithbonder")
            val courserIid = human.battlefield.iid("Centaur Courser")
            advanceToAttackersReq()

            val promptSnap = messageSnapshot()
            val echoMsgs = toggleAttackers(listOf(faithbonderIid), mapOf(faithbonderIid to 261))
            val echoReq = echoMsgs.first { it.hasDeclareAttackersReq() }.declareAttackersReq
            val selectedEnlistAttacker =
                echoReq.attackersList.first {
                    it.attackerInstanceId == faithbonderIid && it.alternativeGrpId == 261
                }
            val normalAttacker =
                echoReq.attackersList.first {
                    it.attackerInstanceId == faithbonderIid && it.alternativeGrpId == 0
                }
            submitAttackers()
            passUntil(maxPasses = 10) { messagesSince(promptSnap).any { it.hasPayCostsReq() } }
                .shouldBeTrue()
            val enlistPrompt = messagesSince(promptSnap).last { it.hasPayCostsReq() }.payCostsReq

            assertSoftly {
                selectedEnlistAttacker.hasSelectedDamageRecipient().shouldBeTrue()
                normalAttacker.hasSelectedDamageRecipient().shouldBeFalse()
                messagesSince(promptSnap).any { it.hasSelectTargetsReq() }.shouldBeFalse()
                enlistPrompt.effectCostReq.costSelection.idsList shouldContain courserIid
                messagesSince(promptSnap).last { it.hasPrompt() }.prompt.promptId shouldBe PromptIds.ENLIST_TAP_COST
            }

            val resolveSnap = messageSnapshot()
            respondToEffectCost(listOf(courserIid))
            passUntil(maxPasses = 20) {
                messagesSince(resolveSnap).any { msg ->
                    msg.hasGameStateMessage() &&
                        msg.gameStateMessage.annotationsList.any { AnnotationType.ResolutionStart in it.typeList }
                }
            }.shouldBeTrue()

            val resolutionGsm =
                messagesSince(resolveSnap).gameStateMessages().last { gsm ->
                    gsm.annotationsList.any {
                        AnnotationType.ResolutionStart in it.typeList && it.detailUint("grpid") == 261
                    }
                }
            val tap =
                messagesSince(resolveSnap)
                    .gameStateMessages()
                    .asSequence()
                    .flatMap { it.annotationsList.asSequence() }
                    .first { AnnotationType.TappedUntappedPermanent in it.typeList && courserIid in it.affectedIdsList }
            val triggeringObject =
                messagesSince(promptSnap).gameStateMessages().flatMap { it.persistentAnnotationsList }.firstOrNull {
                    AnnotationType.TriggeringObject in it.typeList && courserIid in it.affectedIdsList
                } ?: error(
                    "No Enlist TriggeringObject for enlisted creature faithbonder=$faithbonderIid courser=$courserIid; persistent=" +
                        messagesSince(promptSnap)
                            .gameStateMessages()
                            .flatMap { it.persistentAnnotationsList }
                            .map { it.typeList to it.affectorId to it.affectedIdsList },
                )
            val powerToughnessMod =
                resolutionGsm.annotationsList.firstOrNull {
                    AnnotationType.PowerToughnessModCreated in it.typeList &&
                        it.affectorId == resolutionGsm.annotation(AnnotationType.ResolutionStart).affectorId
                }
                    ?: error(
                        "No Enlist PowerToughnessModCreated; annotations=${resolutionGsm.annotationsList.map {
                            it.typeList to it.affectorId to it.affectedIdsList
                        }}",
                    )

            assertSoftly {
                resolutionGsm.annotation(AnnotationType.ResolutionStart).detailUint("grpid") shouldBe 261
                resolutionGsm.annotation(AnnotationType.ResolutionComplete).detailUint("grpid") shouldBe 261
                tap.affectorId shouldBe faithbonderIid
                tap.affectedIdsList shouldContain courserIid
                triggeringObject.affectorId shouldBe resolutionGsm.annotation(AnnotationType.ResolutionStart).affectorId
                triggeringObject.affectedIdsList shouldContain courserIid
                powerToughnessMod.affectedIdsList shouldContain faithbonderIid
            }
        }

        session("Exert attack alternative drives Forge's exert callback", puzzle = exertPuzzle) {
            val glorybringerIid = human.battlefield.iid("Glorybringer")
            val courserIid = ai.battlefield.iid("Centaur Courser")
            advanceToAttackersReq()

            val echo = toggleAttackers(listOf(glorybringerIid), mapOf(glorybringerIid to KeywordAbilityIds.EXERT))
            val exertOption =
                echo
                    .first { it.hasDeclareAttackersReq() }
                    .declareAttackersReq
                    .attackersList
                    .single {
                        it.attackerInstanceId == glorybringerIid && it.alternativeGrpId == KeywordAbilityIds.EXERT
                    }
            exertOption.hasSelectedDamageRecipient().shouldBeTrue()

            val triggerSnap = messageSnapshot()
            submitAttackers()
            passUntil(maxPasses = 10) { messagesSince(triggerSnap).any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            selectTargets(listOf(courserIid))
            passUntilResolved(maxPasses = 10)

            ai.graveyard.card("Centaur Courser").name shouldBe "Centaur Courser"
        }

        session("Normal attack does not exert Glorybringer", puzzle = exertPuzzle) {
            val glorybringerIid = human.battlefield.iid("Glorybringer")
            advanceToAttackersReq()

            val attackSnap = messageSnapshot()
            toggleAttackers(listOf(glorybringerIid))
            submitAttackers()
            passUntilResolved(maxPasses = 10)

            messagesSince(attackSnap).filter { it.hasSelectTargetsReq() } shouldBe emptyList()
        }
    }) {
    companion object {
        private fun MatchFlowHarness.advanceToAttackersReq() {
            fun isDeclareAttackersPending(): Boolean =
                bridge
                    .actionBridge(SeatId(HUMAN_SEAT))
                    .getPending()
                    ?.state
                    ?.kind == PendingActionKind.DECLARE_ATTACKERS

            if (phase() == "COMBAT_DECLARE_ATTACKERS" && isDeclareAttackersPending()) return
            val snap = messageSnapshot()
            passUntil(maxPasses = 10) {
                messagesSince(snap).any { it.hasDeclareAttackersReq() } && isDeclareAttackersPending()
            }.shouldBeTrue()
        }

        private val trainingPuzzle =
            """
            [metadata]
            Name:Training Hopeful Initiate
            Goal:Win
            Turns:5

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            removesummoningsickness=true

            humanbattlefield=Hopeful Initiate;Centaur Courser
            humanlibrary=Plains;Plains;Plains
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        private val enlistPuzzle =
            """
            [metadata]
            Name:Enlist Benalish Faithbonder
            Goal:Win
            Turns:5

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            removesummoningsickness=true

            humanbattlefield=Benalish Faithbonder;Centaur Courser
            humanlibrary=Plains;Plains;Plains
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        private val exertPuzzle =
            """
            [metadata]
            Name:Exert Glorybringer
            Goal:Win
            Turns:5

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            removesummoningsickness=true

            humanbattlefield=Glorybringer
            humanlibrary=Mountain;Mountain;Mountain
            aibattlefield=Centaur Courser
            ailibrary=Forest;Forest;Forest
            """.trimIndent()
    }
}
