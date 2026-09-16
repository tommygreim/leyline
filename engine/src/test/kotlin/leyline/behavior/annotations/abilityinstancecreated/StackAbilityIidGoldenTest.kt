package leyline.behavior.annotations.abilityinstancecreated

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.gsm
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Acceptance gate for stack-ability iids unified on SA-id-keyed surrogate.
 *
 * Headline: two back-to-back chapter triggers from the same saga must mint
 * **distinct** stack-ability instance ids and emit a clean
 * `[PhaseOrStepModified, CounterAdded, AbilityInstanceCreated]` triple per
 * tick — no `ObjectIdChanged` rename of the prior tick's iid.
 *
 * Pre-fix leyline emits 5 annotations per tick because the source-card-keyed
 * surrogate hands the previous tick's iid to the fresh trigger; the
 * snapshot-diff then synthesises an `OIC(prev_iid, fresh_iid)` plus the
 * paired `ZoneTransfer`, displacing `CounterAdded` and `PhaseOrStepModified`
 * out of position.
 *
 * Cuts off before chapter III deliberately — that path involves the saga
 * exile-return transform, RS/RC nesting, and AID affector identity, all of
 * which are downstream follow-ups.
 */
class StackAbilityIidGoldenTest :
    FunSpec({
        tags(IntegrationTag)

        test("back-to-back chapter triggers get distinct iids without OIC rename") {
            val puzzleText =
                """
                [metadata]
                Name:Saga Two-Chapter — Tribute to Horobi (no transform)
                Goal:Survive
                Turns:4
                Difficulty:Easy
                Description:Cast Tribute to Horobi, tick through chapters I and II, stop before chapter III.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Tribute to Horobi
                humanbattlefield=Swamp;Swamp;Swamp
                humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Swamp
                aibattlefield=Mountain
                ailibrary=Mountain;Mountain;Mountain;Mountain
                """.trimIndent()

            val harness = MatchFlowHarness()
            try {
                harness.connectAndKeepPuzzleText(puzzleText)

                harness.castSpellByName("Tribute to Horobi")

                // Pass through to turn 3's precombat-Main1 (Ch II tick) and
                // hold short of turn 5's Ch III. Pass budget chosen empirically
                // to clear the saga ETB tick and the second chapter tick;
                // game won't reach turn 5 within Turns:4.
                repeat(20) {
                    if (harness.isGameOver() || harness.turn() >= 4) return@repeat
                    harness.passPriority()
                }

                val chapterTicks =
                    harness.allMessages
                        .asSequence()
                        .filter { it.hasGameStateMessage() }
                        .map { it.gameStateMessage }
                        .filter { gsm ->
                            gsm.annotationsList.any { AnnotationType.AbilityInstanceCreated in it.typeList } &&
                                gsm.persistentAnnotationsList.any { AnnotationType.TriggeringObject in it.typeList }
                        }.toList()

                // `>= 2` not `== 2`: harness GSM batching can surface extra
                // trigger-enter GSMs (priority-pass at the same step, future
                // engine churn). The load-bearing claim is "the first two
                // chapter ticks mint distinct iids without rename", checked below.
                io.kotest.assertions.withClue(
                    "expected at least two chapter-tick GSMs (Ch I + Ch II), got ${chapterTicks.size}",
                ) {
                    chapterTicks.size shouldBeGreaterThanOrEqual 2
                }

                // The client resolves an instanceId only when a zone lists it
                // (MtgGameState.TryGetCard consults ObjectIds, filled from zone
                // contents). A trigger frame that ships the Ability object without
                // the stack zone leaves it unusable: AbilityInstanceCreated is
                // silently skipped and nothing renders on the stack.
                for (tick in chapterTicks) {
                    val abilityIid = tick.aicAffectedIid()
                    val stackZone = tick.zonesList.firstOrNull { it.zoneId == ZoneIds.STACK }
                    io.kotest.assertions.withClue(
                        "gs ${tick.gameStateId} must carry the stack zone listing ability $abilityIid",
                    ) {
                        (stackZone?.objectInstanceIdsList ?: emptyList()) shouldContain abilityIid
                    }
                }

                val ch1 = chapterTicks[0]
                val ch2 = chapterTicks[1]

                val ch1AbilityIid = ch1.aicAffectedIid()
                val ch2AbilityIid = ch2.aicAffectedIid()

                io.kotest.assertions.withClue(
                    "chapter II must mint a distinct iid from chapter I; got ch1=$ch1AbilityIid ch2=$ch2AbilityIid",
                ) {
                    ch1AbilityIid shouldNotBe ch2AbilityIid
                }

                io.kotest.assertions.withClue(
                    "chapter II must not emit ObjectIdChanged renaming the chapter I ability iid $ch1AbilityIid",
                ) {
                    ch2.annotationsList
                        .filter { AnnotationType.ObjectIdChanged in it.typeList }
                        .none { it.affectedIdsList.contains(ch1AbilityIid) } shouldBe true
                }
            } finally {
                harness.shutdown()
            }
        }
    })

private fun GameStateMessage.aicAffectedIid(): Int {
    val aic =
        annotationsList.firstOrNull { AnnotationType.AbilityInstanceCreated in it.typeList }
            ?: error("GSM missing AbilityInstanceCreated annotation")
    return aic.affectedIdsList[0]
}
