package leyline.mechanics.impending

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.allAnnotations
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

private val PUZZLE =
    """
    [metadata]
    Name:Impending Overlord of the Mistmoors
    Goal:Cast Overlord of the Mistmoors for its impending cost.
    Turns:8
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Overlord of the Mistmoors
    humanbattlefield=Plains;Plains;Plains;Plains
    humanlibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

class ImpendingLifecycleTest :
    SessionTest({
        session("impending cast enters with time counters and records cast-through option", puzzle = PUZZLE) {
            val overlordGrpId = bridge.cardRepository.findGrpIdByName("Overlord of the Mistmoors")!!
            val impendingAbilityGrpId =
                bridge.cardRepository.findKeywordAbilityGrpId(overlordGrpId, KeywordAbilityIds.IMPENDING)!!

            val snap = messageSnapshot()
            castSpellByName("Overlord of the Mistmoors", alternativeGrpId = impendingAbilityGrpId).shouldBeTrue()
            advanceToFirstImpendingTick()

            val cto =
                messagesSince(snap)
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .first { it.detailInt("alternateCostGrpId") == impendingAbilityGrpId }
            val overlord = human.battlefield.card("Overlord of the Mistmoors")
            val counterRemoved =
                messagesSince(snap)
                    .allAnnotations()
                    .first { AnnotationType.CounterRemoved in it.typeList }

            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                overlord.getCounters(CounterEnumType.TIME) shouldBe 3
                overlord.isCreature.shouldBeFalse()
                counterRemoved.detailInt("counter_type") shouldBe 5 // Time
                counterRemoved.detailInt("transaction_amount") shouldBe 1
            }
        }

        session("impending first end-step trigger removes one time counter", puzzle = PUZZLE) {
            val overlordGrpId = bridge.cardRepository.findGrpIdByName("Overlord of the Mistmoors")!!
            val impendingAbilityGrpId =
                bridge.cardRepository.findKeywordAbilityGrpId(overlordGrpId, KeywordAbilityIds.IMPENDING)!!

            castSpellByName("Overlord of the Mistmoors", alternativeGrpId = impendingAbilityGrpId).shouldBeTrue()
            advanceToFirstImpendingTick()

            val removed =
                allMessages
                    .allAnnotations()
                    .filter { AnnotationType.CounterRemoved in it.typeList }

            val overlord = human.battlefield.card("Overlord of the Mistmoors")
            assertSoftly {
                removed.any { it.detailInt("counter_type") == 5 && it.detailInt("transaction_amount") == 1 }.shouldBeTrue()
                overlord.getCounters(CounterEnumType.TIME) shouldBe 3
                overlord.isCreature.shouldBeFalse()
            }
        }

        session("impending removes all time counters and becomes a creature", puzzle = PUZZLE) {
            val overlordGrpId = bridge.cardRepository.findGrpIdByName("Overlord of the Mistmoors")!!
            val impendingAbilityGrpId =
                bridge.cardRepository.findKeywordAbilityGrpId(overlordGrpId, KeywordAbilityIds.IMPENDING)!!

            castSpellByName("Overlord of the Mistmoors", alternativeGrpId = impendingAbilityGrpId).shouldBeTrue()
            var removedAllCounters = false
            repeat(80) {
                if (removedAllCounters) return@repeat
                val overlord = human.getZone(ZoneType.Battlefield).cards.firstOrNull { it.name == "Overlord of the Mistmoors" }
                if (overlord != null && overlord.getCounters(CounterEnumType.TIME) == 0) {
                    removedAllCounters = true
                    return@repeat
                }
                if (bridge
                        .actionBridge(SeatId(1))
                        .getPending()
                        ?.state
                        ?.kind == PendingActionKind.DECLARE_ATTACKERS
                ) {
                    declareNoAttackers()
                } else {
                    passPriority()
                }
            }
            removedAllCounters.shouldBeTrue()

            val removed =
                allMessages
                    .allAnnotations()
                    .filter { AnnotationType.CounterRemoved in it.typeList && it.detailInt("counter_type") == 5 }
            val overlord = human.battlefield.card("Overlord of the Mistmoors")

            assertSoftly {
                removed.sumOf { it.detailInt("transaction_amount") } shouldBe 4
                overlord.getCounters(CounterEnumType.TIME) shouldBe 0
                overlord.isCreature.shouldBeTrue()
            }
        }
    })

private fun MatchFlowHarness.advanceToFirstImpendingTick() {
    passUntilResolved(maxPasses = 12)
    passUntil(maxPasses = 20) {
        human
            .getZone(ZoneType.Battlefield)
            .cards
            .firstOrNull { it.name == "Overlord of the Mistmoors" }
            ?.getCounters(CounterEnumType.TIME) == 3
    }
}
