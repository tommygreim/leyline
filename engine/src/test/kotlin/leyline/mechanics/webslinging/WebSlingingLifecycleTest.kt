package leyline.mechanics.webslinging

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

private val WEB_SLINGING_PUZZLE =
    """
    [metadata]
    Name:Web-slinging Spider-Man
    Goal:Cast Spider-Man, Web-Slinger for its web-slinging cost.
    Turns:2
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Spider-Man, Web-Slinger
    humanbattlefield=Plains;Raging Goblin|Tapped
    humanlibrary=Plains;Plains
    aibattlefield=
    ailibrary=Plains;Plains
    """.trimIndent()

class WebSlingingLifecycleTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureCardRegistered("Spider-Man, Web-Slinger")
            TestCardRegistry.ensureCardRegistered("Raging Goblin")
            TestCardRegistry.ensureCardRegistered("Plains")
        }

        session("Web-slinging offers the typed alternate cast rail", puzzle = WEB_SLINGING_PUZZLE) {
            val webSlingingGrpId = webSlingingAbilityGrpId()
            val offer = latestWebSlingingOffer(webSlingingGrpId)
            checkNotNull(offer) { "missing Web-slinging Cast offer" }
            assertSoftly(offer) {
                actionType shouldBe ActionType.Cast
                alternativeGrpId shouldBe webSlingingGrpId
                abilityGrpId shouldBe 0
                manaCostList.map { it.abilityGrpId }.shouldContain(webSlingingGrpId)
            }
        }

        session("Web-slinging routes its tapped-creature cost through PayCostsReq", puzzle = WEB_SLINGING_PUZZLE) {
            val webSlingingGrpId = webSlingingAbilityGrpId()
            val tappedCreatureIid = human.battlefield.iid("Raging Goblin")
            castSpellByName("Spider-Man, Web-Slinger", alternativeGrpId = webSlingingGrpId).shouldBeTrue()
            passUntil(maxPasses = 8) { allMessages.any { it.hasPayCostsReq() } }.shouldBeTrue()

            val payCosts = allMessages.last { it.hasPayCostsReq() }
            assertSoftly {
                payCosts.prompt.promptId shouldBe PromptIds.WEB_SLINGING_RETURN_TAPPED_CREATURE_COST
                payCosts.payCostsReq.effectCostReq.costSelection.idsList shouldContain tappedCreatureIid
            }

            val castStart = messageSnapshot()
            respondToEffectCost(listOf(tappedCreatureIid))
            passUntil(maxPasses = 12) {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Spider-Man, Web-Slinger" }
            }.shouldBeTrue()

            val castMessages = messagesSince(castStart)
            val cto =
                castMessages
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .firstOrNull { it.detailInt("type") == CastingTimeOptionType.CastThroughAbility.number }
            checkNotNull(cto) { "missing CastThroughAbility annotation for Web-slinging" }
            cto.detailInt("alternateCostGrpId") shouldBe webSlingingGrpId
            human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Raging Goblin"
        }
    })

private fun MatchFlowHarness.webSlingingAbilityGrpId(): Int {
    val cardGrpId =
        bridge.cardRepository.findGrpIdByName("Spider-Man, Web-Slinger")
            ?: error("missing Spider-Man card row in ${bridge.cardRepository::class.qualifiedName}")
    val abilityGrpId = bridge.cardRepository.findKeywordAbilityGrpId(cardGrpId, KeywordAbilityIds.WEB_SLINGING)
    return abilityGrpId ?: error("missing Web-slinging ability row for grpId=$cardGrpId")
}

private fun MatchFlowHarness.latestWebSlingingOffer(webSlingingGrpId: Int) =
    allMessages
        .asReversed()
        .firstOrNull { it.hasActionsAvailableReq() }
        ?.actionsAvailableReq
        ?.actionsList
        ?.firstOrNull {
            it.actionType == ActionType.Cast && it.alternativeGrpId == webSlingingGrpId
        }
