package leyline.mechanics.sneak

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import leyline.testkit.detail
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AttackState
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

private val SNEAK_PUZZLE =
    """
    [metadata]
    Name:Sneak Splinter
    Goal:Attack with Splinter after returning Raging Goblin.
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=COMBAT_DECLARE_ATTACKERS
    HumanLife=20
    AILife=20

    humanhand=Splinter, Hamato Yoshi
    humanbattlefield=Swamp;Raging Goblin
    humanlibrary=Swamp;Swamp;Swamp
    aibattlefield=
    ailibrary=Swamp;Swamp;Swamp
    """.trimIndent()

class SneakLifecycleTest :
    SessionTest({
        session(
            "Sneak returns an unblocked attacker and the permanent enters tapped and attacking",
            puzzle = SNEAK_PUZZLE,
        ) {
            val sneakAbilityGrpId = sneakAbilityGrpId()

            passUntil(maxPasses = 5) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            latestSneakOffer(sneakAbilityGrpId) shouldBe null

            val attackerIid = human.battlefield.iid("Raging Goblin")
            declareAttackers(listOf(attackerIid))
            passUntil(maxPasses = 5) { latestSneakOffer(sneakAbilityGrpId) != null }.shouldBeTrue()

            val offer = checkNotNull(latestSneakOffer(sneakAbilityGrpId))
            val available = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq
            assertSoftly(offer) {
                it.actionType shouldBe ActionType.Cast
                it.alternativeGrpId shouldBe sneakAbilityGrpId
                it.abilityGrpId shouldBe 0
                it.manaCostList.map { cost -> cost.abilityGrpId }.shouldContain(sneakAbilityGrpId)
                available.actionsList
                    .count { action ->
                        action.actionType == ActionType.Cast && action.instanceId == it.instanceId
                    } shouldBe
                    1
                available.inactiveActionsList.count { action ->
                    action.actionType == ActionType.Cast && action.instanceId == it.instanceId
                } shouldBe
                    0
            }

            val castStart = messageSnapshot()
            castSpellByName("Splinter, Hamato Yoshi", alternativeGrpId = sneakAbilityGrpId).shouldBeTrue()
            passUntil(maxPasses = 5) { allMessages.any { it.hasPayCostsReq() } }.shouldBeTrue()

            val payCostsMessage = allMessages.last { it.hasPayCostsReq() }
            assertSoftly {
                payCostsMessage.prompt.promptId shouldBe PromptIds.NINJUTSU_RETURN_UNBLOCKED_ATTACKER_COST
                payCostsMessage.payCostsReq.effectCostReq.costSelection.idsList
                    .shouldContain(attackerIid)
            }

            respondToEffectCost(listOf(attackerIid))
            passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Splinter, Hamato Yoshi" }
            }.shouldBeTrue()

            val castMessages = messagesSince(castStart)
            val castingTimeOption =
                castMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .firstOrNull { it.typeList.contains(AnnotationType.CastingTimeOption) }
            val castUserAction =
                castMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .firstOrNull {
                        it.typeList.contains(AnnotationType.UserActionTaken) &&
                            it.detail("alternativeGrpId")?.getValueInt32(0) == sneakAbilityGrpId
                    }
            val splinter = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Splinter, Hamato Yoshi" }
            val splinterIid = human.battlefield.iid("Splinter, Hamato Yoshi")
            val attackingObject =
                castMessages
                    .allGameObjects()
                    .lastOrNull { it.instanceId == splinterIid && it.attackState == AttackState.Attacking }
            val checkedAttackingObject = attackingObject ?: error("missing attacking Splinter projection")
            val checkedCastUserAction = castUserAction ?: error("missing Sneak user action")
            val checkedCastingTimeOption = castingTimeOption ?: error("missing Sneak casting-time option")

            assertSoftly {
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .map { it.name }
                    .shouldContain("Raging Goblin")
                splinter.isTapped shouldBe true
                checkedAttackingObject.isTapped shouldBe true
                checkedCastUserAction.detailInt("alternativeGrpId") shouldBe sneakAbilityGrpId
                checkedCastingTimeOption.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                checkedCastingTimeOption.detailInt("alternateCostGrpId") shouldBe sneakAbilityGrpId
                checkedCastingTimeOption.detailInt("castAbilityGrpId") shouldBe sneakAbilityGrpId
            }

            val damageReached = passUntil(maxPasses = 10) { ai.life < 20 }
            assertSoftly {
                damageReached.shouldBeTrue()
                ai.life shouldBe 19
            }
        }
    })

private fun MatchFlowHarness.sneakAbilityGrpId(): Int {
    val repo = bridge.cardRepository
    val cardGrpId = repo.findGrpIdByName("Splinter, Hamato Yoshi")!!
    return repo.findKeywordAbilityGrpId(cardGrpId, KeywordAbilityIds.SNEAK)!!
}

private fun MatchFlowHarness.latestSneakOffer(sneakAbilityGrpId: Int) =
    allMessages
        .asReversed()
        .firstOrNull { it.hasActionsAvailableReq() }
        ?.actionsAvailableReq
        ?.actionsList
        ?.firstOrNull {
            it.actionType == ActionType.Cast && it.alternativeGrpId == sneakAbilityGrpId
        }
