package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.CardMechanicType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Riot is an ETB replacement in Forge: its counter payment is the positive
 * branch of an `UnlessCost`, while declining it gives the creature haste.
 * Arena's OptionalActionTranslation dispatches this prompt to
 * OptionalActionWorkflow_Riot only when `CardMechanicType.Riot` is present;
 * an untagged OptionalActionMessage renders the generic yes/no workflow.
 */
class RiotMechanicHintTest :
    SessionTest({
        session(
            "Zhur-Taa Goblin ETB tags CardMechanicType.Riot",
            puzzle =
                """
                [metadata]
                Name:Zhur-Taa Goblin Riot
                Goal:Win
                Turns:3
                Difficulty:Easy
                Description:Cast a Riot creature and inspect its ETB choice.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Zhur-Taa Goblin
                humanbattlefield=Mountain;Forest
                humanlibrary=Mountain;Mountain
                ailibrary=Mountain;Mountain
                """.trimIndent(),
        ) {
            holdNextOptionalAction()
            castSpellByName("Zhur-Taa Goblin") shouldBe true

            val oam =
                allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
                    ?: error("Expected an OptionalActionMessage for the Riot choice")

            assertSoftly {
                oam.optionalActionMessage.optionalActionTypesList shouldContain CardMechanicType.Riot
            }
        }
    })
