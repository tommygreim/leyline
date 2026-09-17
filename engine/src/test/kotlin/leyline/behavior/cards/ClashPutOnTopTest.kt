package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Clash's "put that card on the bottom of your library?" decision.
 *
 * `willPutCardOnTop` built its own bare PromptRequest with no semantic/route
 * and called `bridge.requestChoice` directly, which resolves an unclassified
 * Generic semantic to `ResolvedPromptRoute.AutoResolve` — a synchronous
 * default answer (`defaultIndex = 0`, "Top") with no prompt ever reaching the
 * client. Every clash silently kept the revealed card on top, with no way to
 * choose the bottom Magic's own rules offer. See ISSUES.md.
 */
class ClashPutOnTopTest :
    SessionTest({
        session(
            "declining to put the clashed card on the bottom leaves it on top",
            puzzle =
                """
                [metadata]
                Name:Clash Keep On Top
                Goal:Win
                Turns:1
                Difficulty:Easy
                Description:Clash and decline to put the card on the bottom.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Adder-Staff Boggart
                humanbattlefield=Mountain;Mountain
                humanlibrary=Grizzly Bears;Island;Island
                ailibrary=Plains;Plains;Plains
                """.trimIndent(),
        ) {
            holdNextOptionalAction()
            castSpellByName("Adder-Staff Boggart") shouldBe true

            val oam = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            checkNotNull(oam) { "Expected an OptionalActionMessage for the clash top/bottom decision" }

            respondToOptionalAction(false)
            passUntilResolved(maxPasses = 8)

            human
                .getZone(ZoneType.Library)
                .cards
                .first()
                .name shouldBe "Grizzly Bears"
        }

        session(
            "accepting puts the clashed card on the bottom",
            puzzle =
                """
                [metadata]
                Name:Clash Put On Bottom
                Goal:Win
                Turns:1
                Difficulty:Easy
                Description:Clash and put the card on the bottom.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Adder-Staff Boggart
                humanbattlefield=Mountain;Mountain
                humanlibrary=Grizzly Bears;Island;Island
                ailibrary=Plains;Plains;Plains
                """.trimIndent(),
        ) {
            holdNextOptionalAction()
            castSpellByName("Adder-Staff Boggart") shouldBe true

            val oam = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            checkNotNull(oam) { "Expected an OptionalActionMessage for the clash top/bottom decision" }

            respondToOptionalAction(true)
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                val library = human.getZone(ZoneType.Library).cards.map { it.name }
                library.last() shouldBe "Grizzly Bears"
                library.first() shouldBe "Island"
            }
        }
    })
