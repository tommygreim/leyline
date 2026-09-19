package leyline.session.stack

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/**
 * Casting an instant while holding another castable one must leave the first on the stack
 * with a priority window, so the second can be cast on top before either resolves. The
 * policy used to auto-resolve a player's own spell whenever nobody else responded.
 */
class OwnStackPriorityTest :
    SessionTest({
        fun puzzle(copies: Int): String =
            """
            [metadata]
            Name:Own stack priority
            Goal:Survive
            Turns:6
            Difficulty:Tutorial
            Description:Stack two instants.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=${List(copies) { "Royal Treatment" }.joinToString(";")}
            humanbattlefield=Forest;Forest;Forest;Grizzly Bears
            humanlibrary=Plains;Plains;Plains;Plains
            ailibrary=Plains;Plains;Plains;Plains
            """.trimIndent()

        fun MatchFlowHarness.castableRoyalTreatments(): Int =
            allMessages
                .last { it.hasActionsAvailableReq() }
                .actionsAvailableReq.actionsList
                .count { it.actionType == ActionType.Cast }

        session("a second castable instant can be cast while the first is still on the stack", puzzle = puzzle(copies = 2)) {
            castSpellByName("Royal Treatment")
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))

            assertSoftly {
                game().stack.size() shouldBe 1
                castableRoyalTreatments() shouldBe 1
            }
        }

        session("a lone instant still resolves without an extra priority window", puzzle = puzzle(copies = 1)) {
            castSpellByName("Royal Treatment")
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))

            game().stack.size() shouldBe 0
        }
    })
