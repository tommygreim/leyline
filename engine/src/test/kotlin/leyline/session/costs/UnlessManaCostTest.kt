package leyline.session.costs

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

/**
 * "Unless [player] pays {N}" with a plain mana cost. Forge routes these through
 * `payCostToPreventEffect`; before this fix only Ward and cumulative upkeep asked
 * the human, and every other single-mana-part cost fell through to Forge's
 * desktop path, which auto-tapped lands without a yes/no.
 *
 * - Rupture Spire: the human controls the trigger and is the payer.
 * - Rhystic Study on the AI side: the trigger's controller is the AI but the human
 *   pays, so the payer must be the controller Forge dispatched on, not
 *   `sa.activatingPlayer`.
 */
class UnlessManaCostTest :
    SessionTest({
        fun spirePuzzle(islands: Int): String =
            """
            [metadata]
            Name:Unless Mana Cost
            Goal:Survive
            Turns:6
            Difficulty:Tutorial
            Description:Choose whether to pay Rupture Spire's sacrifice cost.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Rupture Spire
            ${if (islands > 0) "humanbattlefield=" + List(islands) { "Island" }.joinToString(";") else ""}
            humanlibrary=Plains;Plains;Plains;Plains
            ailibrary=Plains;Plains;Plains;Plains
            """.trimIndent()

        val rhysticPuzzle =
            """
            [metadata]
            Name:Rhystic Study
            Goal:Survive
            Turns:6
            Difficulty:Tutorial
            Description:Choose whether to pay for Rhystic Study.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Grizzly Bears
            humanbattlefield=Forest;Forest;Forest
            aibattlefield=Rhystic Study
            humanlibrary=Plains;Plains;Plains;Plains
            ailibrary=Plains;Plains;Plains;Plains
            """.trimIndent()

        fun leyline.tooling.headless.MatchFlowHarness.optionalPrompts() = allMessages.count { it.hasOptionalActionMessage() }

        // Responding resumes the game up to the human's next decision, which is a turn
        // later, so lands have untapped by then: assert on what persists (zones, cards
        // drawn), not on tapped state.
        session("Rupture Spire — accept pays {1} and keeps the land", puzzle = spirePuzzle(islands = 2)) {
            val prior = optionalPrompts()
            holdNextOptionalAction()
            playLand("Rupture Spire").shouldBeTrue()
            optionalPrompts() shouldBe prior + 1
            // "Pay {1}." (PayCosts prompt with a Cost parameter), not the generic "Choose options."
            val prompt = allMessages.last { it.hasOptionalActionMessage() }.optionalActionMessage.prompt
            prompt.promptId shouldBe leyline.game.mapping.PromptIds.PAY_COSTS
            prompt.parametersList.map { it.parameterName to it.stringValue } shouldBe listOf("Cost" to "o1")
            respondToOptionalAction(accept = true)

            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Rupture Spire"
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldNotContain "Rupture Spire"
            }
        }

        session("Rupture Spire — decline sacrifices it", puzzle = spirePuzzle(islands = 2)) {
            val prior = optionalPrompts()
            holdNextOptionalAction()
            playLand("Rupture Spire").shouldBeTrue()
            optionalPrompts() shouldBe prior + 1
            respondToOptionalAction(accept = false)

            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldNotContain "Rupture Spire"
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Rupture Spire"
            }
        }

        session("Rupture Spire — no mana available means no prompt", puzzle = spirePuzzle(islands = 0)) {
            val prior = optionalPrompts()
            playLand("Rupture Spire").shouldBeTrue()

            assertSoftly {
                optionalPrompts() shouldBe prior
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldNotContain "Rupture Spire"
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Rupture Spire"
            }
        }

        // The AI controls the trigger but the human pays, so the payer is the controller
        // Forge dispatched on, not `sa.activatingPlayer`. The opponent draws once for its
        // own draw step either way; declining gives it one extra card from Rhystic Study.
        session("Rhystic Study — accepting the {1} denies the opponent's draw", puzzle = rhysticPuzzle) {
            val prior = optionalPrompts()
            val library = ai.getZone(ZoneType.Library).cards.size
            holdNextOptionalAction()
            castSpellByName("Grizzly Bears").shouldBeTrue()
            passUntil(maxPasses = 6) { optionalPrompts() > prior }.shouldBeTrue()
            respondToOptionalAction(accept = true)

            ai.getZone(ZoneType.Library).cards.size shouldBe library - 1
        }

        session("Rhystic Study — declining the {1} lets the opponent draw", puzzle = rhysticPuzzle) {
            val prior = optionalPrompts()
            val library = ai.getZone(ZoneType.Library).cards.size
            holdNextOptionalAction()
            castSpellByName("Grizzly Bears").shouldBeTrue()
            passUntil(maxPasses = 6) { optionalPrompts() > prior }.shouldBeTrue()
            respondToOptionalAction(accept = false)

            ai.getZone(ZoneType.Library).cards.size shouldBe library - 2
        }
    })
