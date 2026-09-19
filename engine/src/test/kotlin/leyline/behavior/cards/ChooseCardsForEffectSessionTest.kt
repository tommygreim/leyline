package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

/**
 * `chooseCardsForEffect` outside ChangeZone used to plan `PromptSemantic.Generic`, which
 * resolves to AutoResolve: exactly one card, the first, no message to the client. These
 * pin that the player is now asked and the answer they give is the one that takes effect.
 *
 * - Duneblast (`ChooseCard`): the survivor of the wrath is the creature the player picks.
 * - Armored Kincaller (`RevealEffect`, optional): "you may reveal" is a real choice, so
 *   declining must not gain the 3 life that revealing would.
 */
class ChooseCardsForEffectSessionTest :
    SessionTest({
        val duneblastPuzzle =
            """
            [metadata]
            Name:Duneblast
            Goal:Survive
            Turns:6
            Difficulty:Tutorial
            Description:Choose which creature survives Duneblast.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Duneblast
            humanbattlefield=Plains;Plains;Swamp;Swamp;Forest;Forest;Forest;Grizzly Bears;Serra Angel
            aibattlefield=Walking Corpse
            humanlibrary=Plains;Plains;Plains;Plains
            ailibrary=Plains;Plains;Plains;Plains
            """.trimIndent()

        val kincallerPuzzle =
            """
            [metadata]
            Name:Armored Kincaller
            Goal:Survive
            Turns:6
            Difficulty:Tutorial
            Description:Choose whether to reveal a Dinosaur.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Armored Kincaller;Armored Kincaller
            humanbattlefield=Forest;Forest;Forest
            humanlibrary=Plains;Plains;Plains;Plains
            ailibrary=Plains;Plains;Plains;Plains
            """.trimIndent()

        session("Duneblast asks which creature to keep instead of sparing the first", puzzle = duneblastPuzzle) {
            val req = castSpellUntilSelectNReq("Duneblast")
            val offered = req.idsList.map { iid -> cardByIid(iid)?.name ?: error("iid $iid did not resolve") }

            offered.sorted() shouldContainExactly listOf("Grizzly Bears", "Serra Angel", "Walking Corpse")

            respondToSelectN(listOf(human.battlefield.iid("Serra Angel")))
            passUntilResolved()

            val survivors =
                (human.getZone(ZoneType.Battlefield).cards + ai.getZone(ZoneType.Battlefield).cards)
                    .filter { it.isCreature }
                    .map { it.name }
            survivors shouldContainExactly listOf("Serra Angel")
        }

        session("Armored Kincaller lets the player decline to reveal, so no life is gained", puzzle = kincallerPuzzle) {
            val req = castSpellUntilSelectNReq("Armored Kincaller")
            req.idsCount shouldBe 1

            respondToSelectN(emptyList())
            passUntilResolved()

            human.life shouldBe 20
        }

        session("Armored Kincaller reveals the Dinosaur the player picks and gains 3 life", puzzle = kincallerPuzzle) {
            val req = castSpellUntilSelectNReq("Armored Kincaller")

            respondToSelectN(listOf(req.idsList.single()))
            passUntilResolved()

            assertSoftly {
                human.life shouldBe 23
                human.getZone(ZoneType.Hand).cards.count { it.name == "Armored Kincaller" } shouldBe 1
            }
        }
    })
