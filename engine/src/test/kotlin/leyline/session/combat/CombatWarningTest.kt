package leyline.session.combat

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import leyline.testkit.SessionTest

/**
 * `DeclareAttackersReq.hasRequirements` / `Attacker.mustAttack` — previously
 * never written (engine/src/main had zero write sites; see ISSUES.md I38).
 * Juggernaut's `Mode$ MustAttack` static ability is Forge's own reference
 * case for this (the same check `InputAttack.java` uses to nag the human
 * player), so it is the most direct real-card regression test available.
 */
class CombatWarningTest :
    SessionTest({
        session(
            "Juggernaut's must-attack requirement reaches DeclareAttackersReq",
            puzzle = """
                [metadata]
                Name:Juggernaut Must Attack
                Goal:Survive
                Turns:5
                Difficulty:Easy
                Description:Juggernaut is forced to attack each combat if able.

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Juggernaut
                humanlibrary=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest
                ailibrary=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest
                """,
        ) {
            advanceToCombat()

            val req = allMessages.last { it.hasDeclareAttackersReq() }.declareAttackersReq
            assertSoftly {
                req.hasRequirements.shouldBeTrue()
                req.attackersList shouldHaveSize 1
                req.attackersList
                    .first()
                    .mustAttack
                    .shouldBeTrue()
            }
        }
    })
