package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Superior Spider-Man's Mind Swap ETB replacement — "you may have it enter as
 * a copy of any creature card in a graveyard".
 *
 * `confirmReplacementEffect` built its own bare PromptRequest with no
 * semantic/route and called `bridge.requestChoice` directly. That resolves an
 * unclassified Generic semantic to `ResolvedPromptRoute.AutoResolve` — a
 * synchronous default answer with no prompt ever reaching the client — and
 * the method's own `defaultIndex` for an "enter as a copy" message was 1
 * ("No"). So the replacement was silently declined every time, live: no
 * prompt shown, and Spider-Man entered as itself rather than a copy. See
 * ISSUES.md and [leyline.bridge.handoff.OptionalActionGate], which documents
 * `confirmReplacementEffect` as one of its intended consumers.
 */
class SuperiorSpiderManInteractionTest :
    SessionTest({
        session(
            "Mind Swap prompts before copying, then exiles the copied card",
            puzzle =
                """
                [metadata]
                Name:Superior Spider-Man Mind Swap
                Goal:Win
                Turns:1
                Difficulty:Easy
                Description:Cast Superior Spider-Man and accept the Mind Swap copy.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Superior Spider-Man
                humangraveyard=Grizzly Bears
                humanbattlefield=Island;Island;Swamp;Swamp
                humanlibrary=Island
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            holdNextOptionalAction()
            castSpellByName("Superior Spider-Man") shouldBe true

            val oam = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            checkNotNull(oam) { "Expected an OptionalActionMessage for the Mind Swap replacement" }

            respondToOptionalAction(true)
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                // Copied: Grizzly Bears left the graveyard (exiled by DBImmediateTrig),
                // not still sitting there because the replacement was declined.
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldBe emptyList()
                human.getZone(ZoneType.Exile).cards.map { it.name } shouldBe listOf("Grizzly Bears")
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .count { it.name == "Superior Spider-Man" } shouldBe 1
            }
        }

        session(
            "declining Mind Swap leaves the graveyard card alone",
            puzzle =
                """
                [metadata]
                Name:Superior Spider-Man Mind Swap Decline
                Goal:Win
                Turns:1
                Difficulty:Easy
                Description:Cast Superior Spider-Man and decline the Mind Swap copy.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Superior Spider-Man
                humangraveyard=Grizzly Bears
                humanbattlefield=Island;Island;Swamp;Swamp
                humanlibrary=Island
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            holdNextOptionalAction()
            castSpellByName("Superior Spider-Man") shouldBe true

            val oam = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            checkNotNull(oam) { "Expected an OptionalActionMessage for the Mind Swap replacement" }

            respondToOptionalAction(false)
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldBe listOf("Grizzly Bears")
                human.getZone(ZoneType.Exile).cards.map { it.name } shouldBe emptyList()
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .count { it.name == "Superior Spider-Man" } shouldBe 1
            }
        }
    })
