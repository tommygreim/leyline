package leyline.session.actions

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.CostType

/**
 * `Action.costs` — previously never built for non-mana activation costs; the
 * only `Cost` oneof variant ever constructed anywhere in the engine was
 * `manaCost` (see ISSUES.md I37). Goblin Cratermaker's "{1}, Sacrifice
 * Goblin Cratermaker: ..." ability is a real, simple case: the mana part was
 * already previewed, but the sacrifice-self part never was.
 */
class ActivatedAbilityCostPreviewTest :
    SessionTest({
        session(
            "Goblin Cratermaker's sacrifice-self cost is previewed on the offered action",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Goblin Cratermaker;Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain
                """,
        ) {
            val actionsReq = allMessages.first { it.hasActionsAvailableReq() }.actionsAvailableReq
            val activateActions = actionsReq.actionsList.filter { it.actionType == ActionType.Activate_add3 }

            assertSoftly {
                activateActions shouldHaveSize 1
                val costs = activateActions.single().costsList
                costs.map { it.type } shouldBe listOf(CostType.SacSelf)
                costs.single().effectCost.count shouldBe 1
            }
        }
    })
