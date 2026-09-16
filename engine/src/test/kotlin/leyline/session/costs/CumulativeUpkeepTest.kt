package leyline.session.costs

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

class CumulativeUpkeepTest :
    SessionTest({
        fun puzzle(islands: Int = 3): String =
            """
            [metadata]
            Name:Cumulative Upkeep
            Goal:Survive
            Turns:6
            Difficulty:Tutorial
            Description:Choose whether to pay Mystic Remora's cumulative upkeep.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanbattlefield=Mystic Remora${";Island".repeat(islands)}
            humanhand=Mountain
            humanlibrary=Plains;Plains;Plains;Plains
            ailibrary=Plains;Plains;Plains;Plains
            """.trimIndent()

        fun leyline.tooling.headless.MatchFlowHarness.reachUpkeepPayment() {
            val priorPrompts = allMessages.count { it.hasOptionalActionMessage() }
            holdNextOptionalAction()
            passUntil(maxPasses = 40) {
                allMessages.count { it.hasOptionalActionMessage() } > priorPrompts
            }.shouldBeTrue()
        }

        session("decline sacrifices Mystic Remora without spending mana", puzzle = puzzle()) {
            reachUpkeepPayment()
            respondToOptionalAction(accept = false)

            val battlefield = human.getZone(ZoneType.Battlefield).cards
            val graveyard = human.getZone(ZoneType.Graveyard).cards.map { it.name }
            assertSoftly {
                battlefield.map { it.name } shouldNotContain "Mystic Remora"
                battlefield.filter { it.name == "Island" }.none { it.isTapped }.shouldBeTrue()
                battlefield.count { it.name == "Island" } shouldBe 3
                graveyard shouldContain "Mystic Remora"
            }
        }

        session("accept pays each increasing upkeep cost once", puzzle = puzzle()) {
            reachUpkeepPayment()
            respondToOptionalAction(accept = true)

            val remora = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Mystic Remora" }
            assertSoftly {
                remora.getCounters(CounterEnumType.AGE) shouldBe 1
                human.getZone(ZoneType.Battlefield).cards.count { it.name == "Island" && it.isTapped } shouldBe 1
            }

            reachUpkeepPayment()
            respondToOptionalAction(accept = true)
            passUntil(maxPasses = 4) {
                remora.getCounters(CounterEnumType.AGE) == 2
            }.shouldBeTrue()

            assertSoftly {
                remora.getCounters(CounterEnumType.AGE) shouldBe 2
                human.getZone(ZoneType.Battlefield).cards.count { it.name == "Island" && it.isTapped } shouldBe 2
            }
        }

        session("accept cannot preserve Mystic Remora when the cost is unpayable", puzzle = puzzle(islands = 0)) {
            reachUpkeepPayment()
            respondToOptionalAction(accept = true)

            val battlefield = human.getZone(ZoneType.Battlefield).cards.map { it.name }
            val graveyard = human.getZone(ZoneType.Graveyard).cards.map { it.name }
            assertSoftly {
                battlefield shouldNotContain "Mystic Remora"
                graveyard shouldContain "Mystic Remora"
                graveyard.count { it == "Mystic Remora" } shouldBe 1
            }
        }
    })
