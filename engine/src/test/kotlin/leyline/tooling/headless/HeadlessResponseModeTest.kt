package leyline.tooling.headless

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Pins who owns engine prompts under each [HeadlessResponseMode].
 *
 * Wildborn Preserver's ETB trigger is a two-step chain: an optional
 * "you may pay {X}" offer, then a numeric input for X. The optional offer
 * gates the numeric prompt — decline it, or leave it unanswered, and the
 * numeric prompt never fires.
 *
 * [HeadlessResponseMode.AutoForTests] answers the offer inside `drainSink`.
 * [HeadlessResponseMode.PolicyVisible] must leave it pending so a caller
 * (simclient's prompt policy) chooses the response and records it.
 */
class HeadlessResponseModeTest :
    FunSpec({

        tags(IntegrationTag)

        val puzzleText =
            """
            [metadata]
            Name:Headless Response Mode — Wildborn Preserver
            Goal:Survive
            Turns:2
            Difficulty:Easy
            Description:Resolve a non-Human creature so Wildborn Preserver offers its optional pay-X trigger.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Centaur Courser
            humanbattlefield=Wildborn Preserver;Forest;Forest;Forest;Forest;Forest
            humanlibrary=Forest
            ailibrary=Mountain
            """.trimIndent()

        /** Cast the non-Human creature and let it resolve so the ETB offer fires. */
        fun MatchFlowHarness.castCourserAndResolve() {
            castSpellByName("Centaur Courser").shouldBeTrue()
            repeat(4) {
                if (allMessages.any { it.type == GREMessageType.OptionalActionMessage_695e }) return
                passPriority()
            }
        }

        fun MatchFlowHarness.countOf(type: GREMessageType) = allMessages.count { it.type == type }

        test("AutoForTests answers the optional offer during drain") {
            val h = MatchFlowHarness(responseMode = HeadlessResponseMode.AutoForTests)
            h.connectAndKeepPuzzleText(puzzleText)
            h.castCourserAndResolve()

            withClue("harness auto-accepts, so the offer is consumed and the chained numeric prompt fires") {
                h.bridge.cutCoordinator
                    .currentBlockingInteraction()
                    .shouldBeNull()
                h.countOf(GREMessageType.NumericInputReq_695e) shouldBe 1
            }
        }

        test("PolicyVisible leaves the optional offer for the caller") {
            val h = MatchFlowHarness(responseMode = HeadlessResponseMode.PolicyVisible)
            h.connectAndKeepPuzzleText(puzzleText)
            h.castCourserAndResolve()

            withClue("prompt must survive drainSink so policy owns the response") {
                h.countOf(GREMessageType.OptionalActionMessage_695e) shouldBe 1
                val optional = h.allMessages.single { it.hasOptionalActionMessage() }
                assertSoftly {
                    optional.prompt.promptId shouldBe PromptIds.OPTIONAL_PAY_X
                    optional.optionalActionMessage.prompt shouldBe optional.prompt
                    h.bridge.cutCoordinator
                        .currentBlockingInteraction()
                        .shouldNotBeNull()
                }
            }

            withClue("the numeric prompt is gated behind the unanswered offer") {
                h.countOf(GREMessageType.NumericInputReq_695e) shouldBe 0
            }

            withClue("answering as policy would releases the chained numeric prompt") {
                h.respondToOptionalAction(accept = true)
                h.countOf(GREMessageType.NumericInputReq_695e) shouldBe 1
            }
        }
    })
