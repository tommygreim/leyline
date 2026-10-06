package leyline.session.combat

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest

/** Keep the native double-block fixture bootable and able to reach combat. */
class DoubleBlockFreeAssignmentFixtureTest :
    SessionTest({
        session(
            "Boar fixture forces two blockers and reaches damage assignment",
            forgeCatalog = true,
            puzzleFile = "data/puzzles/double-block-free-assignment.pzl",
        ) {
            val boarId = human.battlefield.iid("Nessian Boar")
            allMessages.any { it.hasActionsAvailableReq() } shouldBe true
            allMessages.any { it.hasDeclareAttackersReq() } shouldBe false
            passUntil(maxPasses = 8) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            declareAttackers(listOf(boarId))
            passUntil(maxPasses = 12) { allMessages.any { it.hasAssignDamageReq() } }.shouldBeTrue()
            val assignment =
                allMessages
                    .last { it.hasAssignDamageReq() }
                    .assignDamageReq
                    .damageAssignersList
                    .single()
            assignment.assignmentsCount shouldBe 2
            val split = assignment.assignmentsList.mapIndexed { index, slot -> slot.instanceId to (if (index == 0) 1 else 9) }
            assignDamage(listOf(assignment.instanceId to split))
            allMessages.any { it.hasAssignDamageConfirmation() }.shouldBeTrue()
        }
    })
