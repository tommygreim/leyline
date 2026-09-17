package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

/**
 * Eager Construct: "When ~ enters, each player may scry 1." — an
 * `Optional$ True` ability confirmed via `ScryEffect.resolve()`'s
 * `PlayerController.confirmAction`, not a triggered ability's own "you may"
 * (that's `confirmTrigger`, already correctly wired).
 *
 * `confirmAction`'s fallback (everything except Endure and the Commander
 * alt-destination case) built a bare PromptRequest with no semantic/route
 * and called `bridge.requestChoice` directly — Generic resolves to
 * `ResolvedPromptRoute.AutoResolve`, a synchronous default answer
 * (`defaultIndex = 0`, "Yes") with no prompt ever reaching the client. That is
 * the fallback for every "you may" confirmation modeled as a spell/ability
 * *effect* rather than a trigger — ScryEffect, SurveilEffect, DrawEffect,
 * FightEffect, and 40+ more Forge effect classes, across 1,100+ cards
 * scripted with `Optional$ True`. The failure mode is silent, not a crash:
 * the effect just always happens, with no way to decline. See ISSUES.md.
 */
class OptionalScryConfirmTest :
    SessionTest({
        val puzzle =
            """
            [metadata]
            Name:Eager Construct Optional Scry
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Cast Eager Construct and answer the optional scry.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Eager Construct
            humanbattlefield=Island;Island
            humanlibrary=Grizzly Bears;Island;Island
            ailibrary=Mountain;Mountain
            """.trimIndent()

        session("declining leaves the library untouched and does not scry", puzzle = puzzle) {
            holdNextOptionalAction()
            castSpellByName("Eager Construct") shouldBe true

            val oam = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            checkNotNull(oam) { "Expected an OptionalActionMessage for the optional scry" }

            respondToOptionalAction(false)
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                allMessages.none { it.hasGroupReq() } shouldBe true
                human
                    .getZone(ZoneType.Library)
                    .cards
                    .first()
                    .name shouldBe "Grizzly Bears"
            }
        }

        session("accepting leads into the real scry ordering prompt", puzzle = puzzle) {
            holdNextOptionalAction()
            castSpellByName("Eager Construct") shouldBe true

            val oam = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            checkNotNull(oam) { "Expected an OptionalActionMessage for the optional scry" }

            respondToOptionalAction(true)

            val groupReq = allMessages.last { it.hasGroupReq() }.groupReq
            respondToScry(bottomInstanceIds = groupReq.instanceIdsList, allInstanceIds = groupReq.instanceIdsList)
            passUntilResolved(maxPasses = 8)

            human
                .getZone(ZoneType.Library)
                .cards
                .last()
                .name shouldBe "Grizzly Bears"
        }
    })
