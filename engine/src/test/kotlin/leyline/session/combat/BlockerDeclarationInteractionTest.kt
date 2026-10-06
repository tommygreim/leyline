package leyline.session.combat

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.afterActionInstall
import leyline.bridge.coord.beforeActionInstall
import leyline.bridge.types.SeatId
import leyline.game.PlaybackTerminalFailure
import leyline.testkit.MatchFlowHarness
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf
import wotc.mtgo.gre.external.messaging.Messages.BlockState
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

private val GOBLIN_ATTACK_AI_SCRIPT =
    listOf(
        ScriptedAction.PlayLand("Mountain"),
        ScriptedAction.CastSpell("Raging Goblin"),
        ScriptedAction.Attack(listOf("Raging Goblin")),
        ScriptedAction.PassPriority,
        ScriptedAction.PlayLand("Mountain"),
        ScriptedAction.DeclareNoAttackers,
        ScriptedAction.PassPriority,
    )

private val MULTI_BLOCKER_AI_SCRIPT =
    listOf(
        ScriptedAction.Attack(listOf("Centaur Courser")),
        ScriptedAction.PassPriority,
    )

/**
 * Returns (humanBlockerIid, aiAttackerIid) at COMBAT_DECLARE_BLOCKERS,
 * ready for the human to respond to DeclareBlockersReq.
 *
 * DO NOT call passPriority() after declareNoAttackers() to "advance" —
 * it submits Pass to the COMBAT_DECLARE_BLOCKERS pending (= no blockers)
 * and skips the entire DeclareBlockersReq flow. Use advanceToPhase plus a
 * client-output wait instead, as below.
 */
private fun MatchFlowHarness.setupAiAttacksHumanCanBlock(): Pair<Int, Int> {
    // Human turn 1: play Mountain, cast Raging Goblin (haste → potential blocker)
    playLand("Mountain").shouldBeTrue()
    castSpellByName("Raging Goblin").shouldBeTrue()
    passUntilResolved()

    // Human combat: decline if prompted. Runtime horizons may process the AI
    // turn (land, cast, attack) and publish DeclareBlockersReq in the same call.
    if (allMessages.any { it.hasDeclareAttackersReq() }) declareNoAttackers()

    // If DeclareBlockersReq isn't in messages yet, the AI turn hasn't
    // completed. Use bridge-level advanceTo to reach COMBAT_DECLARE_BLOCKERS
    // without intercepting the pending (passPriority would submit Pass
    // to the blocker pending = "no blockers"). The next runtime horizon then
    // publishes DeclareBlockersReq before any action is submitted.
    if (allMessages.none { it.hasDeclareBlockersReq() }) {
        advanceToPhase("COMBAT_DECLARE_BLOCKERS")
        drainSink()
    }

    allMessages.any { it.hasDeclareBlockersReq() }.shouldBeTrue()

    val humanCreatures = humanBattlefieldCreatures()
    humanCreatures.shouldNotBeEmpty()
    val blockerIid = humanCreatures.first().first

    // Find the AI attacker instanceId from the DeclareBlockersReq
    val blockReq = allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
    blockReq.blockersCount shouldBeGreaterThan 0

    // The blocker should reference attacker instanceIds
    val blocker = blockReq.blockersList.first { it.blockerInstanceId == blockerIid }
    blocker.attackerInstanceIdsCount shouldBeGreaterThan 0
    val attackerIid = blocker.attackerInstanceIdsList.first()

    return blockerIid to attackerIid
}

private fun MatchFlowHarness.advanceToMultiBlockerPrompt(): Triple<Int, Int, Int> {
    passUntil(maxPasses = 6) { allMessages.any { it.hasDeclareBlockersReq() } }.shouldBeTrue()

    val req = allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
    req.blockersCount shouldBe 2
    val blockerIids = req.blockersList.map { it.blockerInstanceId }
    val attackerIid =
        req.blockersList
            .first()
            .attackerInstanceIdsList
            .first()
    return Triple(blockerIids[0], blockerIids[1], attackerIid)
}

/**
 * Non-validating harness: combat zone transfers can produce transient
 * instanceId gaps (known StateMapper issue tracked separately).
 */
// `should beInGraveyardOf(player, count = N)` carries equality semantics — the
// detekt heuristic just doesn't recognize custom matchers as equality-shape.
@Suppress("WeakAssertionOnly")
class BlockerDeclarationInteractionTest :
    SessionTest({

        // ─── Single-blocker block / decline / trade ──────────────────────────

        session(
            "human blocks AI attacker",
            deckList = COMBAT_DECK,
            aiScript = GOBLIN_ATTACK_AI_SCRIPT,
        ) {
            val (blockerIid, attackerIid) = setupAiAttacksHumanCanBlock()

            // Human life before blocking
            val lifeBefore = human.life

            // Declare block: human's Raging Goblin blocks AI's Raging Goblin
            declareBlockers(mapOf(blockerIid to attackerIid))

            passThroughCombat()

            assertSoftly {
                // Human life should NOT decrease (blocked damage)
                human.life shouldBe lifeBefore

                // Both 1/1s should have traded — human's creature should be in graveyard
                "Raging Goblin" should beInGraveyardOf(human, count = 1)

                isGameOver().shouldBeFalse()
            }
        }

        session(
            "human declines blocking takes damage",
            deckList = COMBAT_DECK,
            aiScript = GOBLIN_ATTACK_AI_SCRIPT,
        ) {
            setupAiAttacksHumanCanBlock() // advances to DeclareBlockersReq

            val lifeBefore = human.life

            // Human declines to block
            declareNoBlockers()

            passThroughCombat()

            assertSoftly {
                // Human should have taken exactly 1 damage (Raging Goblin is 1/1)
                human.life shouldBe lifeBefore - 1

                // Human's creature should still be alive
                humanBattlefieldCreatures() shouldHaveSize 1

                isGameOver().shouldBeFalse()
            }
        }

        session(
            "trade produces creature deaths",
            deckList = COMBAT_DECK,
            aiScript = GOBLIN_ATTACK_AI_SCRIPT,
        ) {
            val (blockerIid, attackerIid) = setupAiAttacksHumanCanBlock()

            // Declare block
            declareBlockers(mapOf(blockerIid to attackerIid))

            passThroughCombat()

            assertSoftly {
                // Both 1/1s should have traded — exactly one Raging Goblin in each graveyard
                "Raging Goblin" should beInGraveyardOf(human, count = 1)
                "Raging Goblin" should beInGraveyardOf(ai, count = 1)

                val objects = allMessages.filter { it.hasGameStateMessage() }.flatMap { it.gameStateMessage.gameObjectsList }
                objects
                    .any {
                        it.instanceId == blockerIid &&
                            it.blockState == BlockState.Blocking &&
                            it.blockInfo.attackerIdsList == listOf(attackerIid)
                    }.shouldBeTrue()
                objects
                    .any {
                        it.instanceId == attackerIid &&
                            it.attackInfo.orderedBlockersList.map { blocker -> blocker.instanceId } == listOf(blockerIid)
                    }.shouldBeTrue()

                isGameOver().shouldBeFalse()
            }
        }

        session(
            "blocker confirmation installs before the engine token is submitted",
            deckList = COMBAT_DECK,
            aiScript = GOBLIN_ATTACK_AI_SCRIPT,
        ) {
            setupAiAttacksHumanCanBlock()
            val pending = bridge.actionBridge(SeatId(1)).getPending().shouldNotBeNull()
            bridge.cutCoordinator.drain(SeatId(1))
            var installed = emptyList<GREToClientMessage>()
            var engineAlreadyResumed = true
            bridge.cutCoordinator.afterActionInstall = {
                installed = bridge.cutCoordinator.drain(SeatId(1)).flatten()
                engineAlreadyResumed = pending.future.isDone
            }

            val completed =
                bridge.cutCoordinator.submitDeclaredAction(
                    pending.actionId,
                    pending.promptGameStateId.shouldNotBeNull(),
                )
            bridge.cutCoordinator.afterActionInstall = null

            assertSoftly {
                completed.shouldBeTrue()
                installed.single().type shouldBe GREMessageType.SubmitBlockersResp_695e
                engineAlreadyResumed.shouldBeFalse()
            }
        }

        session(
            "blocker confirmation install failure publishes nothing and does not resume the engine",
            deckList = COMBAT_DECK,
            aiScript = GOBLIN_ATTACK_AI_SCRIPT,
        ) {
            setupAiAttacksHumanCanBlock()
            val pending = bridge.actionBridge(SeatId(1)).getPending().shouldNotBeNull()
            bridge.cutCoordinator.drain(SeatId(1))
            bridge.cutCoordinator.beforeActionInstall = { error("blocker confirmation install unavailable") }

            val failure =
                shouldThrow<PlaybackTerminalFailure> {
                    bridge.cutCoordinator.submitDeclaredAction(
                        pending.actionId,
                        pending.promptGameStateId.shouldNotBeNull(),
                    )
                }

            assertSoftly {
                failure.cause?.message shouldBe "blocker confirmation install unavailable"
                bridge.cutCoordinator
                    .drain(SeatId(1))
                    .shouldBeEmpty()
                pending.future.isCompletedExceptionally.shouldBeTrue()
            }
        }

        // ─── Iterative multi-blocker toggle ──────────────────────────────────

        session(
            "second iterative blocker toggle does not wipe first assignment",
            puzzleFile = "test-puzzles/multi-blocker.pzl",
            aiScript = MULTI_BLOCKER_AI_SCRIPT,
        ) {
            val (b1, b2, attackerIid) = advanceToMultiBlockerPrompt()

            val echo1 = toggleBlockers(mapOf(b1 to attackerIid))
            val req1 = echo1.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            val objects1 =
                echo1
                    .first { it.hasGameStateMessage() }
                    .gameStateMessage.gameObjectsList
                    .associateBy { it.instanceId }
            assertSoftly {
                objects1.getValue(b1).blockState shouldBe BlockState.Declared_aa2d
                objects1.getValue(b1).blockInfo.attackerIdsList shouldBe listOf(attackerIid)
                objects1
                    .getValue(attackerIid)
                    .attackInfo.orderedBlockersList
                    .map { it.instanceId } shouldBe listOf(b1)
                req1.blockersList
                    .first { it.blockerInstanceId == b1 }
                    .selectedAttackerInstanceIdsCount shouldBe 1
                req1.blockersList
                    .first { it.blockerInstanceId == b2 }
                    .selectedAttackerInstanceIdsCount shouldBe 0
            }

            val echo2 = toggleBlockers(mapOf(b2 to attackerIid))
            val req2 = echo2.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            val objects2 =
                echo2
                    .first { it.hasGameStateMessage() }
                    .gameStateMessage.gameObjectsList
                    .associateBy { it.instanceId }
            assertSoftly {
                objects2.getValue(b1).blockInfo.attackerIdsList shouldBe listOf(attackerIid)
                objects2.getValue(b2).blockInfo.attackerIdsList shouldBe listOf(attackerIid)
                objects2
                    .getValue(attackerIid)
                    .attackInfo.orderedBlockersList
                    .map { it.instanceId }
                    .toSet() shouldBe setOf(b1, b2)
                req2.blockersList
                    .first { it.blockerInstanceId == b1 }
                    .selectedAttackerInstanceIdsCount shouldBe 1
                req2.blockersList
                    .first { it.blockerInstanceId == b2 }
                    .selectedAttackerInstanceIdsCount shouldBe 1
            }
        }

        session(
            "deselect blocker removes only that assignment",
            puzzleFile = "test-puzzles/multi-blocker.pzl",
            aiScript = MULTI_BLOCKER_AI_SCRIPT,
        ) {
            val (b1, b2, attackerIid) = advanceToMultiBlockerPrompt()

            toggleBlockers(mapOf(b1 to attackerIid))
            toggleBlockers(mapOf(b2 to attackerIid))

            val echo = deselectBlocker(b1)
            val req = echo.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            val objects =
                echo
                    .first { it.hasGameStateMessage() }
                    .gameStateMessage.gameObjectsList
                    .associateBy { it.instanceId }

            assertSoftly {
                objects.getValue(b1).blockState shouldBe BlockState.None_aa2d
                objects.getValue(b1).hasBlockInfo().shouldBeFalse()
                objects.getValue(b2).blockInfo.attackerIdsList shouldBe listOf(attackerIid)
                objects
                    .getValue(attackerIid)
                    .attackInfo.orderedBlockersList
                    .map { it.instanceId } shouldBe listOf(b2)
                req.blockersList
                    .first { it.blockerInstanceId == b1 }
                    .selectedAttackerInstanceIdsCount shouldBe 0
                req.blockersList
                    .first { it.blockerInstanceId == b1 }
                    .attackerInstanceIdsCount shouldBeGreaterThanOrEqual 1
                req.blockersList
                    .first { it.blockerInstanceId == b2 }
                    .selectedAttackerInstanceIdsCount shouldBe 1
            }
        }

        session(
            "declare-blockers step sends exactly one DeclareBlockersReq",
            puzzle = """
            ActivePlayer=AI
            ActivePhase=COMBAT_DECLARE_ATTACKERS
            HumanLife=20
            AILife=20

            humanbattlefield=Mountain;Raging Goblin
            humanlibrary=Mountain;Mountain;Mountain
            aibattlefield=Mountain;Raging Goblin|Attacking|Tapped
            ailibrary=Mountain;Mountain;Mountain
            """,
            turns = 1,
        ) {
            advanceToPhase("COMBAT_DECLARE_BLOCKERS")
            declareNoBlockers()

            allMessages.count { it.hasDeclareBlockersReq() } shouldBe 1
        }
    })
