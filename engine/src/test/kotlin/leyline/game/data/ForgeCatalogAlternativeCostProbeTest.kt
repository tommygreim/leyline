package leyline.game.data

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.ForgeCatalogTag
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.detailInt
import leyline.testkit.hand
import leyline.tooling.headless.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class ForgeCatalogAlternativeCostProbeTest :
    FunSpec({
        tags(IntegrationTag, ForgeCatalogTag)
        timeout = 600_000L
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }
        afterEach { TestCardRegistry.repo.registeredCount shouldBe 0 }

        test("conditional alternative cost is offered and paid when its condition holds") {
            forgeCatalogProbe(
                "generic-alternative-cost",
                puzzleText = forceOfNegationPuzzle,
            ) { repo ->
                val forceGrpId = requireNotNull(repo.findGrpIdByName("Force of Negation"))
                val alternativeGrpId = requireNotNull(repo.findGenericAlternativeCostAbilityGrpId(forceGrpId))

                passUntil(maxPasses = 10) {
                    allMessages
                        .lastOrNull { it.hasActionsAvailableReq() }
                        ?.actionsAvailableReq
                        ?.actionsList
                        ?.any { it.actionType == ActionType.Cast && it.alternativeGrpId == alternativeGrpId } == true
                }.shouldBeTrue()
                val offer =
                    allMessages
                        .last { it.hasActionsAvailableReq() }
                        .actionsAvailableReq.actionsList
                        .single { it.actionType == ActionType.Cast && it.alternativeGrpId == alternativeGrpId }
                assertSoftly {
                    offer.grpId shouldBe forceGrpId
                    offer.manaCostList shouldBe emptyList()
                }

                val castStart = messageSnapshot()
                castSpellByName("Force of Negation", alternativeGrpId = alternativeGrpId).shouldBeTrue()
                passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
                val boltIid =
                    allMessages
                        .last { it.hasSelectTargetsReq() }
                        .selectTargetsReq.targetsList
                        .flatMap { it.targetsList }
                        .map { it.targetInstanceId }
                        .single { cardByIid(it)?.name == "Lightning Bolt" }
                selectTargets(listOf(boltIid))
                passUntil(maxPasses = 5) {
                    allMessages
                        .lastOrNull { it.hasSelectTargetsReq() }
                        ?.selectTargetsReq
                        ?.targetsList
                        ?.flatMap { it.targetsList }
                        ?.any { cardByIid(it.targetInstanceId)?.name == "Opt" } == true
                }.shouldBeTrue()
                selectTargets(listOf(human.hand.iid("Opt")))
                passUntilResolved(maxPasses = 20)
                val castMessages = messagesSince(castStart)
                val castAction =
                    castMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.annotationsList }
                        .single {
                            it.typeList.contains(AnnotationType.UserActionTaken) &&
                                it.detailsList.any { detail -> detail.key == "alternativeGrpId" }
                        }
                val castingTimeOption =
                    castMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.persistentAnnotationsList }
                        .single { it.typeList.contains(AnnotationType.CastingTimeOption) }

                assertSoftly {
                    human.life shouldBe 3
                    human.getZone(ZoneType.Exile).cards.count { it.name == "Opt" } shouldBe 1
                    ai.getZone(ZoneType.Exile).cards.count { it.name == "Lightning Bolt" } shouldBe 1
                    human.getZone(ZoneType.Graveyard).cards.count { it.name == "Force of Negation" } shouldBe 1
                    castAction.detailInt("alternativeGrpId") shouldBe alternativeGrpId
                    castingTimeOption.detailInt("alternateCostGrpId") shouldBe alternativeGrpId
                    castingTimeOption.detailInt("castAbilityGrpId") shouldBe alternativeGrpId
                }
            }
        }

        test("conditional alternative cost is absent when its condition does not hold") {
            forgeCatalogProbe(
                "generic-alternative-cost-condition",
                "humanhand=Force of Negation;Opt",
            ) { repo ->
                val forceGrpId = requireNotNull(repo.findGrpIdByName("Force of Negation"))
                val alternativeGrpId = requireNotNull(repo.findGenericAlternativeCostAbilityGrpId(forceGrpId))

                passUntil(maxPasses = 2) { allMessages.any { it.hasActionsAvailableReq() } }.shouldBeTrue()
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .count { it.actionType == ActionType.Cast && it.alternativeGrpId == alternativeGrpId } shouldBe 0
            }
        }
    })

private val forceOfNegationPuzzle =
    """
# Design Narrative
# Mechanic: Cast Force of Negation for its alternative cost during the opponent's turn by exiling another blue card.
# Forcing: The opponent's Lightning Bolt is lethal, and the player has no mana, so exiling Opt is the only way to survive.
# AI behavior: The opponent starts its main phase at three opposing life and has exactly one castable spell, so it casts Lightning Bolt at the player.
# Failure modes:
#   WIN = Force of Negation exiles Opt to counter and exile Lightning Bolt before it resolves.
#   LOSE = Lightning Bolt resolves because the alternative cast or its hand-exile payment is unavailable.
#   TIMEOUT = the response window, target selection, cost payment, or stack resolution stops advancing.
# Card roles:
#   Force of Negation — alternative-cost counterspell under test.
#   Opt — the only blue card available for the alternative payment.
#   Lightning Bolt — deterministic lethal noncreature spell to counter.
#   Mountain — lets the opponent cast Lightning Bolt.
# Protocol path: opponent priority, alternate hand-cast action, stack targeting, hand-exile payment, countered spell exile, and stack resolution.

[metadata]
Name:Force of Negation Alternative Cost
Goal:Survive
Turns:1
Difficulty:Tutorial
Description:Exile Opt to cast Force of Negation without mana and counter a lethal Lightning Bolt.

[state]
ActivePlayer=AI
ActivePhase=Main1
HumanLife=3
AILife=20

humanhand=Force of Negation;Opt
humanlibrary=Island
aibattlefield=Mountain
aihand=Lightning Bolt
ailibrary=Mountain
    """.trimIndent()
