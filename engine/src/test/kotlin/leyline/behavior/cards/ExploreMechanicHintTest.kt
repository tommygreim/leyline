package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.CardMechanicType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Explore's "put this in your graveyard?" confirmation — routed through
 * `PlayerController.confirmAction` like ~1,100 other `Optional$ True`
 * effects, but unlike those, the decompiled client's
 * `OptionalActionTranslation.Translate` dispatches on
 * `CardMechanicType.Explore` specifically to pick its dedicated
 * `ExploreWorkflow` browser instead of the generic fallback. Before this,
 * `optionalActionTypes` was never populated for any `confirmAction`-routed
 * prompt. See ISSUES.md I42.
 */
class ExploreMechanicHintTest :
    SessionTest({
        session(
            "Merfolk Branchwalker's Explore confirmation tags CardMechanicType.Explore",
            puzzle = """
                [metadata]
                Name:Merfolk Branchwalker Explore
                Goal:Win
                Turns:3
                Difficulty:Easy
                Description:Cast Merfolk Branchwalker and answer its Explore confirmation.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Merfolk Branchwalker
                humanbattlefield=Forest;Forest
                humanlibrary=Grizzly Bears;Forest;Forest
                ailibrary=Forest;Forest
                """,
        ) {
            holdNextOptionalAction()
            castSpellByName("Merfolk Branchwalker") shouldBe true

            val oam =
                allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
                    ?: error("Expected an OptionalActionMessage for the Explore confirmation")

            assertSoftly {
                oam.optionalActionMessage.optionalActionTypesList shouldContain CardMechanicType.Explore
            }
        }
    })
