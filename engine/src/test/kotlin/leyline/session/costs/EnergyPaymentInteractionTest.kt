package leyline.session.costs

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class EnergyPaymentInteractionTest :
    SessionTest({
        val puzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=3
            AILife=20
            humancounters=ENERGY=2
            humanbattlefield=Glint-Sleeve Siphoner
            humanlibrary=Forest;Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

        for (accept in listOf(true, false)) {
            session(
                "energy upkeep $accept asks once and preserves the chosen costs and effect",
                puzzle = puzzle,
                turns = 4,
                fullControl = true,
            ) {
                holdNextOptionalAction()
                passUntil(maxPasses = 60) { allMessages.any { it.hasOptionalActionMessage() } } shouldBe true
                val request = allMessages.single { it.hasOptionalActionMessage() }
                request.optionalActionMessage.prompt.promptId shouldBe PromptIds.OPTIONAL_PAY_ENERGY.getValue(2)
                val stack =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .filter { it.type == GameObjectType.Ability }
                stack.map { it.grpId }.toSet() shouldBe setOf(900106)
                val handBefore = human.getCardsIn(ZoneType.Hand).size
                respondToOptionalAction(accept)
                passUntilResolved()
                assertSoftly {
                    allMessages.count { it.hasOptionalActionMessage() } shouldBe 1
                    human.getCounters(CounterEnumType.ENERGY) shouldBe if (accept) 0 else 2
                    human.life shouldBe if (accept) 2 else 3
                    human.getCardsIn(ZoneType.Hand).size shouldBe handBefore + if (accept) 1 else 0
                }
            }
        }

        session(
            "entry and attack each grant visible energy with the energy trigger identity",
            puzzle = """
        ActivePlayer=Human
        ActivePhase=Main1
        HumanLife=20
        AILife=20
        removesummoningsickness=true
        humanbattlefield=Glint-Sleeve Siphoner;Swamp;Swamp
        humanhand=Glint-Sleeve Siphoner
        humanlibrary=Forest;Forest;Forest;Forest
        ailibrary=Mountain;Mountain;Mountain;Mountain
    """,
            turns = 4,
            fullControl = true,
        ) {
            val attacker = human.battlefield.iid("Glint-Sleeve Siphoner")
            val entering = human.hand.card("Glint-Sleeve Siphoner")
            castSpellByName("Glint-Sleeve Siphoner") shouldBe true
            passUntil { human.getCounters(CounterEnumType.ENERGY) == 1 } shouldBe true
            human.getCounters(CounterEnumType.ENERGY) shouldBe 1
            advanceToCombat()
            declareAttackers(listOf(attacker))
            passUntil { human.getCounters(CounterEnumType.ENERGY) == 2 } shouldBe true
            human.getCounters(CounterEnumType.ENERGY) shouldBe 2
            val states = allMessages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            states
                .flatMap { it.gameObjectsList }
                .filter { it.type == GameObjectType.Ability }
                .map { it.grpId }
                .toSet() shouldBe setOf(900105)
            val energyGains =
                states.flatMap { it.annotationsList }.filter {
                    wotc.mtgo.gre.external.messaging.Messages.AnnotationType.CounterAdded in it.typeList &&
                        it.affectedIdsList == listOf(1)
                }
            energyGains.size shouldBe 2
            cardByIid(energyGains[0].affectorId)?.id shouldBe entering.id
            energyGains[1].affectorId shouldBe attacker
        }

        session(
            "unaffordable energy upkeep neither prompts nor draws or loses life",
            puzzle = puzzle.replace("ENERGY=2", "ENERGY=0"),
            turns = 4,
            fullControl = true,
        ) {
            holdNextOptionalAction()
            passUntil(maxPasses = 60) { turn() >= 3 && phase() == "MAIN1" } shouldBe true
            assertSoftly {
                allMessages.count { it.hasOptionalActionMessage() } shouldBe 0
                human.life shouldBe 3
                human.getCounters(CounterEnumType.ENERGY) shouldBe 0
                human.getCardsIn(ZoneType.Hand).size shouldBe 1 // Only the ordinary draw step.
            }
        }
    })
