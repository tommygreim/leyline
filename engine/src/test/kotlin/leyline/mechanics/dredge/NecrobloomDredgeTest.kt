package leyline.mechanics.dredge

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.SelectReplacementsType

/**
 * No Arena card prints Dredge directly (Golgari Grave-Troll, Stinkweed Imp,
 * etc. aren't in Arena's card pool) — The Necrobloom (MH3) is the only
 * source, granting `Dredge:2` to land cards in the graveyard. Two dredge-
 * eligible lands in the graveyard at once means Forge's `ReplacementHandler`
 * hands `chooseSingleReplacementEffect` two simultaneous Dredge replacements
 * for one Draw event — the same "competing self-replacements" shape Madness
 * already handled, but [leyline.bridge.coord.ReplacementWindowCapture] only
 * recognized Madness, so this fell through to Forge's own inherited
 * desktop-GUI auto-pick with no prompt ever reaching the client.
 */
class NecrobloomDredgeTest :
    SessionTest({
        session(
            "two dredge-eligible lands under The Necrobloom prompt a real choice",
            puzzle =
                """
                [metadata]
                Name:The Necrobloom grants land dredge on a draw
                Goal:Draw with two dredge-eligible lands in the graveyard, choose one.
                Turns:1
                Difficulty:Easy

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Think Twice
                humanbattlefield=The Necrobloom;Island;Island
                humangraveyard=Forest;Forest
                humanlibrary=Plains;Plains;Plains;Plains
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            val forestsBefore = human.getZone(ZoneType.Graveyard).cards.filter { it.name == "Forest" }
            forestsBefore shouldHaveSize 2
            val libraryCountBefore = human.getZone(ZoneType.Library).cards.size

            castSpellByName("Think Twice") shouldBe true

            // The real prompt: a SelectReplacementReq with both Dredge options,
            // not a silent auto-pick. This is the headline regression check.
            val req = allMessages.lastOrNull { it.type == GREMessageType.SelectReplacementReq_695e }?.selectReplacementReq
            req.shouldNotBeNull()
            assertSoftly {
                req.replacementsList shouldHaveSize 2
                req.isOptional shouldBe false
                req.replacementsType shouldBe SelectReplacementsType.AllDredge
            }

            // Identify which live Forest corresponds to option 0 on the wire, so
            // the final zone check proves the CHOSEN card moved, not just "a" card.
            val chosenRow = req.replacementsList[0]
            val chosenForestId =
                forestsBefore.first { bridge.instanceId(it) == chosenRow.objectInstance }.id
            val untouchedForestId = forestsBefore.first { it.id != chosenForestId }.id

            respondToSelectReplacement(chosenRow)
            passUntilResolved(maxPasses = 8)

            assertSoftly {
                // Chosen Forest dredged back to hand; the other one untouched in the graveyard.
                human.getZone(ZoneType.Hand).cards.any { it.id == chosenForestId } shouldBe true
                human.getZone(ZoneType.Graveyard).cards.any { it.id == untouchedForestId } shouldBe true
                // Dredge 2 milled exactly two library cards into the graveyard instead of drawing.
                human.getZone(ZoneType.Graveyard).cards.count { it.name == "Plains" } shouldBe 2
                (libraryCountBefore - human.getZone(ZoneType.Library).cards.size) shouldBe 2
            }
        }
    })
