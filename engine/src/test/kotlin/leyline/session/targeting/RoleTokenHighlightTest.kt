package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.after
import wotc.mtgo.gre.external.messaging.Messages.HighlightType

/**
 * `HighlightType.ReplaceRole` — the decompiled client checks
 * `target.Highlight == HighlightType.ReplaceRole` in its targeting-confirmation
 * logic (`SelectTargetsWorkflow.cs`) to render distinct copy
 * ("Select Cold Role Targets" / `DuelScene/SelectColdRoleTargets`) when a
 * player targets a permanent that already has a Role (CR 702.166) — instead
 * of the ordinary target-confirmation flow every other Card candidate gets
 * (`HighlightType.Tepid`).
 *
 * This is a per-candidate property, not a per-window one: different
 * candidates in the same targeting prompt can have different answers, so
 * [leyline.bridge.handoff.TargetingCandidateValue.Card] carries its own
 * `hasExistingRole` flag, computed at capture time in
 * [leyline.bridge.coord.TargetingWindowCapture] from the live Forge `Card`'s
 * attachments — mirroring the predicate `GameAction.stateBasedAction_Role`
 * uses internally (`card.attachedCards.any { it.type.hasSubtype("Role") }`).
 */
class RoleTokenHighlightTest :
    SessionTest({

        session(
            "Monstrous Rage — a target that already has a Role is highlighted ReplaceRole, not Tepid",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Monstrous Rage;Monstrous Rage
                humanbattlefield=Mountain;Mountain;Grizzly Bears
                humanlibrary=Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain
                """,
        ) {
            val bearsIid = human.battlefield.iid("Grizzly Bears")
            val courserIid = ai.battlefield.iid("Centaur Courser")

            // Grant Grizzly Bears its first Role.
            castSpellByName("Monstrous Rage").shouldBeTrue()
            selectTargets(listOf(bearsIid))
            passUntil { human.battlefield.card("Grizzly Bears").isEnchanted }.shouldBeTrue()

            // Cast a second Monstrous Rage — the prompt now covers both Grizzly
            // Bears (already has a Role) and the AI's Courser (does not). Only
            // the candidate that already has a Role gets ReplaceRole; the same
            // window's other candidate stays Tepid.
            val msgs = after { castSpellByName("Monstrous Rage").shouldBeTrue() }.messages
            val req = msgs.first { it.hasSelectTargetsReq() }.selectTargetsReq
            val targets = req.targetsList.single().targetsList

            assertSoftly {
                targets.single { it.targetInstanceId == bearsIid }.highlight shouldBe HighlightType.ReplaceRole
                targets.single { it.targetInstanceId == courserIid }.highlight shouldBe HighlightType.Tepid
            }
        }
    })
