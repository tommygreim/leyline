package leyline.bridge.coord

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.tooling.headless.HeadlessResponseMode

/**
 * Automatic mana activation must preserve the client's confirmation boundary.
 *
 * These cases deliberately use the session harness rather than constructing a
 * SpellAbility by hand. Forge's automatic payer needs fully initialized game
 * state (controllers, zones, and card abilities) to produce a real payment
 * plan; a standalone puzzle game can return a false "no payment" result.
 */
class ManaActivationConfirmationTest :
    SessionTest({
        fun towerPuzzle() =
            """
            [metadata]
            Name:Mana activation confirmation
            Goal:Win
            Turns:2
            Difficulty:Easy
            Description:Confirm an irreversible mana-source activation.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Bad Moon
            humanbattlefield=Phyrexian Tower;Grizzly Bears
            aibattlefield=Grizzly Bears
            humanlibrary=Mountain;Mountain;Mountain
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        fun ordinaryPuzzle() =
            """
            [metadata]
            Name:Ordinary mana activation
            Goal:Win
            Turns:2
            Difficulty:Easy
            Description:Automatic payment from ordinary lands.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Bad Moon
            humanbattlefield=Swamp;Swamp
            aibattlefield=Grizzly Bears
            humanlibrary=Mountain;Mountain;Mountain
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        fun MatchFlowHarness.optionalCount() = allMessages.count { it.hasOptionalActionMessage() }

        session(
            "classifies the selected Phyrexian Tower activation as irreversible",
            puzzle = towerPuzzle(),
            responseMode = HeadlessResponseMode.PolicyVisible,
            fullControl = true,
        ) {
            val towerIid = human.battlefield.iid("Phyrexian Tower")
            val before = optionalCount()

            holdNextOptionalAction()
            castSpellByName("Bad Moon").shouldBeTrue()
            optionalCount() shouldBe before + 1

            val prompt = allMessages.last { it.hasOptionalActionMessage() }.optionalActionMessage
            assertSoftly {
                // Automatic payment from an irreversible source uses the same
                // cost-labelled workflow as other mana-payment confirmations;
                // the source id still identifies the exact permanent to consume.
                prompt.prompt.promptId shouldBe PromptIds.PAY_COSTS
                prompt.prompt.parametersList.map { it.parameterName to it.stringValue } shouldBe
                    listOf("Cost" to "o1oB")
                prompt.sourceId shouldBe towerIid
            }
            respondToOptionalAction(accept = true)
        }

        session(
            "declining an irreversible automatic source leaves payment cards unchanged",
            puzzle = towerPuzzle(),
            responseMode = HeadlessResponseMode.PolicyVisible,
            fullControl = true,
        ) {
            holdNextOptionalAction()
            castSpellByName("Bad Moon").shouldBeTrue()
            respondToOptionalAction(accept = false)

            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Phyrexian Tower"
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Grizzly Bears"
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Phyrexian Tower" }
                    .isTapped shouldBe false
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldNotContain "Grizzly Bears"
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Bad Moon"
            }
        }

        session(
            "accepting an irreversible automatic source allows normal Forge payment",
            puzzle = towerPuzzle(),
            responseMode = HeadlessResponseMode.PolicyVisible,
            fullControl = true,
        ) {
            holdNextOptionalAction()
            castSpellByName("Bad Moon").shouldBeTrue()
            respondToOptionalAction(accept = true)
            passUntilResolved()

            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Bad Moon"
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Phyrexian Tower" }
                    .isTapped shouldBe true
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Grizzly Bears"
            }
        }

        session(
            "ordinary tap-only mana sources retain silent automatic payment",
            puzzle = ordinaryPuzzle(),
            responseMode = HeadlessResponseMode.PolicyVisible,
            fullControl = true,
        ) {
            val before = optionalCount()
            castSpellByName("Bad Moon").shouldBeTrue()
            passUntilResolved()

            assertSoftly {
                optionalCount() shouldBe before
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Bad Moon"
                human.getZone(ZoneType.Battlefield).cards.count { it.name == "Swamp" && it.isTapped } shouldBe 2
            }
        }
    })
