package leyline.session.combat

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.beInHandOf
import wotc.mtgo.gre.external.messaging.Messages.AttackWarningType
import wotc.mtgo.gre.external.messaging.Messages.BlockWarningType

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
            "block-warning fixture reaches human declaration through a real forced attack",
            puzzleFile = "data/puzzles/block1-block-warnings.pzl",
        ) {
            "Kardur, Doomscourge" should beInHandOf(human)
            castSpellByName("Kardur, Doomscourge").shouldBeTrue()
            passUntilResolved()
            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareBlockersReq() } }.shouldBeTrue()

            val req = allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            val attackers = req.blockersList.flatMap { it.attackerInstanceIdsList }.toSet()
            assertSoftly {
                humanBattlefieldCreatures() shouldHaveSize 4
                attackers shouldHaveSize 2
                attackers.map { cardName(it) }.toSet() shouldBe setOf("Noxious Gearhulk", "Gaea's Protector")
                req.blockWarningsList.map { it.type } shouldContainAll listOf(BlockWarningType.MustBeBlocked)
                req.blockWarningsList.any { it.type == BlockWarningType.InsufficientBlockers } shouldBe false
                req.blockersList.any { it.mustBlock } shouldBe false
            }

            val protector = req.blockersList.flatMap { it.attackerInstanceIdsList }.first { cardName(it) == "Gaea's Protector" }
            val gearhulk = attackers.first { cardName(it) == "Noxious Gearhulk" }
            val blockers = humanBattlefieldCreatures().associate { (iid, name) -> name to iid }
            toggleBlockers(
                mapOf(
                    blockers.getValue("Mogg Flunkies") to protector,
                    blockers.getValue("Grizzly Bears") to gearhulk,
                    blockers.getValue("Runeclaw Bear") to gearhulk,
                ),
            )
            val legal = allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            legal.blockWarningsCount shouldBe 0
            legal.blockersList.any { it.mustBlock } shouldBe false

            val reassigned = toggleBlockers(mapOf(blockers.getValue("Grizzly Bears") to protector))
            val objects =
                reassigned
                    .first { it.hasGameStateMessage() }
                    .gameStateMessage.gameObjectsList
                    .associateBy { it.instanceId }
            objects.getValue(blockers.getValue("Grizzly Bears")).blockInfo.attackerIdsList shouldBe listOf(protector)
            objects
                .getValue(gearhulk)
                .attackInfo.orderedBlockersList
                .map { it.instanceId } shouldBe
                listOf(blockers.getValue("Runeclaw Bear"))
            objects
                .getValue(protector)
                .attackInfo.orderedBlockersList
                .map { it.instanceId }
                .toSet() shouldBe
                setOf(blockers.getValue("Mogg Flunkies"), blockers.getValue("Grizzly Bears"))
        }

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
                req.attackWarningsList.map { it.type } shouldBe listOf(AttackWarningType.MustAttack)
                req.attackWarningsList.first().warningPromptId shouldBe 124
            }
        }

        session(
            "declare blockers warns for unmet must-block requirements but not unblocked menace",
            puzzle = """
                [metadata]
                Name:Combat warning mappings
                Goal:Survive
                Turns:5
                Difficulty:Easy
                Description:Forge combat requirements map to Arena warning prompts.

                [state]
                ActivePlayer=AI
                ActivePhase=COMBAT_DECLARE_BLOCKERS
                HumanLife=20
                AILife=20

                humanbattlefield=Forest;Grizzly Bears;Grizzly Bears
                humanlibrary=Forest;Forest;Forest;Forest
                aibattlefield=Mountain;Noxious Gearhulk|Attacking|Tapped;Gaea's Protector|Attacking|Tapped
                ailibrary=Mountain;Mountain;Mountain;Mountain
                """,
        ) {
            advanceToPhase("COMBAT_DECLARE_BLOCKERS")
            val req = allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            val warningTypes = req.blockWarningsList.map { it.type }
            warningTypes shouldContainAll listOf(BlockWarningType.MustBeBlocked)
            warningTypes.contains(BlockWarningType.InsufficientBlockers) shouldBe false
            req.blockWarningsList.map { it.warningPromptId } shouldContainAll listOf(121)
        }
    })
