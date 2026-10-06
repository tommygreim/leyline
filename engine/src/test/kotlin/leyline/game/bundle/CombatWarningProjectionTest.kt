package leyline.game.bundle

import forge.game.combat.Combat
import forge.game.combat.CombatUtil
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.BlockWarningType

class CombatWarningProjectionTest :
    BoardTest({
        for (assignedCount in 0..1) {
            test("a shared must-be-blocked requirement does not force every blocker with $assignedCount assigned") {
                val board =
                    startWithBoard { _, human, ai ->
                        addCard("Gaea's Protector", ai, ZoneType.Battlefield)
                        addCard("Grizzly Bears", human, ZoneType.Battlefield)
                        addCard("Coral Merfolk", human, ZoneType.Battlefield)
                    }
                val attacker = board.ai.battlefield.card("Gaea's Protector")
                val blocker = board.human.battlefield.card("Grizzly Bears")
                val combat = Combat(board.ai)
                combat.addAttacker(attacker, board.human)
                board.game.phaseHandler.setCombat(combat)
                val attackerId = board.bridge.instanceId(attacker)
                val assignments = if (assignedCount == 0) emptyMap() else mapOf(board.bridge.instanceId(blocker) to attackerId)

                val request = RequestBuilder.buildDeclareBlockersReq(board.game, SeatId(1), board.bridge, assignments)

                assertSoftly {
                    request.blockersList.map { it.mustBlock } shouldBe listOf(false, false)
                    request.blockWarningsList.map { it.type } shouldBe
                        if (assignedCount == 0) listOf(BlockWarningType.MustBeBlocked) else emptyList()
                    request.blockWarningsList.map { it.instanceId } shouldBe
                        if (assignedCount == 0) listOf(attackerId) else emptyList()
                    combat.getBlockers(attacker).size shouldBe 0
                }
            }
        }

        test("an individual forced-block requirement retains the blocker warning") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                    addCard("Coral Merfolk", human, ZoneType.Battlefield)
                }
            val attacker = board.ai.battlefield.card("Grizzly Bears")
            val blocker = board.human.battlefield.card("Coral Merfolk")
            val combat = Combat(board.ai)
            combat.addAttacker(attacker, board.human)
            blocker.addMustBlockCard(1L, attacker)
            board.game.phaseHandler.setCombat(combat)

            val request = RequestBuilder.buildDeclareBlockersReq(board.game, SeatId(1), board.bridge)

            request.blockersList.single().mustBlock shouldBe true
            request.blockWarningsList.single().type shouldBe BlockWarningType.MustBlock
        }

        test("a lure requirement still forces every eligible blocker") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                    addCard("Coral Merfolk", human, ZoneType.Battlefield)
                    addCard("Runeclaw Bear", human, ZoneType.Battlefield)
                }
            val attacker = board.ai.battlefield.card("Grizzly Bears")
            attacker.addIntrinsicKeyword("All creatures able to block CARDNAME do so.")
            val combat = Combat(board.ai)
            combat.addAttacker(attacker, board.human)
            board.game.phaseHandler.setCombat(combat)

            val request = RequestBuilder.buildDeclareBlockersReq(board.game, SeatId(1), board.bridge)

            request.blockersList.map { it.mustBlock } shouldBe listOf(true, true)
            request.blockWarningsList.map { it.type } shouldBe
                listOf(BlockWarningType.MustBlock, BlockWarningType.MustBlock, BlockWarningType.MustBeBlockedByAll)
        }

        for (blockerCount in 0..2) {
            test("menace warns only for an incomplete block with $blockerCount blockers assigned") {
                val board =
                    startWithBoard { _, human, ai ->
                        addCard("Noxious Gearhulk", ai, ZoneType.Battlefield)
                        addCard("Grizzly Bears", human, ZoneType.Battlefield)
                        addCard("Coral Merfolk", human, ZoneType.Battlefield)
                    }
                val attacker = board.ai.battlefield.card("Noxious Gearhulk")
                val blockers =
                    listOf(
                        board.human.battlefield.card("Grizzly Bears"),
                        board.human.battlefield.card("Coral Merfolk"),
                    ).take(blockerCount)
                val combat = Combat(board.ai)
                combat.addAttacker(attacker, board.human)
                blockers.forEach { combat.addBlocker(attacker, it) }
                board.game.phaseHandler.setCombat(combat)
                val attackerId = board.bridge.instanceId(attacker)
                val assignments = blockers.associate { board.bridge.instanceId(it) to attackerId }

                val request = RequestBuilder.buildDeclareBlockersReq(board.game, SeatId(1), board.bridge, assignments)

                assertSoftly {
                    request.hasRestrictions shouldBe true
                    request.hasRequirements shouldBe false
                    request.blockWarningsCount shouldBe if (blockerCount == 1) 1 else 0
                    (CombatUtil.validateBlocks(combat, board.human) == null) shouldBe (blockerCount != 1)
                }
                if (blockerCount == 1) {
                    val warning = request.blockWarningsList.single()
                    warning.type shouldBe BlockWarningType.InsufficientBlockers
                    warning.instanceId shouldBe attackerId
                    warning.warningPromptId shouldBe PromptIds.WARNING_INSUFFICIENT_BLOCKERS
                }
            }
        }
    })
