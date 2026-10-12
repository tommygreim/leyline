package leyline.session.combat

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.SeatId
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf
import leyline.testkit.ofType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import kotlin.time.Duration.Companion.seconds

private val HUMAN_COMBAT_PRIORITY_HANDOFF_PUZZLE =
    """
    [metadata]
    Name:Human Combat Priority Handoff
    Goal:Win
    Turns:3
    Difficulty:Easy
    Description:Human attacks with no instants in hand; verifies DECLARE_BLOCKERS hand-off doesn't stall

    [state]
    ActivePlayer=Human
    ActivePhase=COMBAT_DECLARE_ATTACKERS
    HumanLife=20
    AILife=20

    humanhand=
    humanbattlefield=Raging Goblin;Mountain
    humanlibrary=Mountain;Mountain;Mountain
    aibattlefield=
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

/**
 * Combat declarations hand back to rules priority before damage. The attacker
 * can advance with no responses, while a defender can use a blocking mana
 * creature without full control. Declining all blocks preserves smart passing.
 */
// Session-tier: exercises the full MatchSession + runtime continuation.
@Suppress("TierPlacementCheck")
class HumanCombatPriorityHandoffTest :
    SessionTest({

        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        session(
            "human attacks, no blockers — DECLARE_BLOCKERS pass-only priority doesn't hang bridge",
            puzzle = HUMAN_COMBAT_PRIORITY_HANDOFF_PUZZLE,
            timeout = 3.seconds,
        ) {
            passUntil(maxPasses = 5) {
                bridge
                    .actionBridge(SeatId(1))
                    .getPending()
                    ?.state
                    ?.kind == PendingActionKind.DECLARE_ATTACKERS
            }.shouldBeTrue()

            val attackerIid =
                humanBattlefieldCreatures()
                    .single { it.second == "Raging Goblin" }
                    .first
            declareAttackers(listOf(attackerIid))
            // Configured combat stops remain visible even when Pass is the only action.
            passPriority()
            passPriority()

            ai.life shouldBe 19
        }

        session(
            "defender can float mana with a declared blocker before combat damage without full control",
            puzzleFile = "data/puzzles/blocker-mana-priority.pzl",
            aiScript = listOf(ScriptedAction.Attack(listOf("Juggernaut")), ScriptedAction.PassPriority),
        ) {
            passUntil(maxPasses = 12) { allMessages.any { it.hasDeclareBlockersReq() } }.shouldBeTrue()
            val req = allMessages.last { it.hasDeclareBlockersReq() }.declareBlockersReq
            val birdsIid = human.battlefield.iid("Birds of Paradise")
            val attackerIid =
                req.blockersList
                    .single { it.blockerInstanceId == birdsIid }
                    .attackerInstanceIdsList
                    .single()
            declareBlockers(mapOf(birdsIid to attackerIid))

            val pending = checkNotNull(bridge.actionBridge(SeatId(1)).getPending())
            pending.state.phase shouldBe "COMBAT_DECLARE_BLOCKERS"
            pending.state.kind shouldBe PendingActionKind.PRIORITY
            bridge.priorityPolicy.isFullControl() shouldBe false
            allMessages
                .last { it.hasActionsAvailableReq() }
                .actionsAvailableReq
                .ofType(ActionType.ActivateMana)
                .map { it.instanceId } shouldBe listOf(birdsIid)
            val birds = human.battlefield.card("Birds of Paradise")
            game().combat.isBlocking(birds) shouldBe true
            birds.isTapped shouldBe false

            activateMana("Birds of Paradise").shouldBeTrue()
            val colorChoice = allMessages.last { it.hasSelectNReq() }.selectNReq
            colorChoice.staticList shouldBe StaticList.ManaColors
            respondToSelectN(listOf(ManaColor.Green_afc9.number))
            birds.isTapped shouldBe true
            human.manaPool.totalMana() shouldBe 1
            game().combat.isBlocking(birds) shouldBe true
            passThroughCombat()
            human.life shouldBe 20
            "Birds of Paradise" should beInGraveyardOf(human)
        }

        session(
            "declining blocks does not force a defender mana-only stop",
            puzzleFile = "data/puzzles/blocker-mana-priority.pzl",
            aiScript = listOf(ScriptedAction.Attack(listOf("Juggernaut")), ScriptedAction.PassPriority),
        ) {
            passUntil(maxPasses = 12) { allMessages.any { it.hasDeclareBlockersReq() } }.shouldBeTrue()
            declareNoBlockers()

            val pending = checkNotNull(bridge.actionBridge(SeatId(1)).getPending())
            pending.state.phase shouldBe "MAIN1"
            pending.state.kind shouldBe PendingActionKind.PRIORITY
            human.life shouldBe 15
            human.battlefield.card("Birds of Paradise").isTapped shouldBe false
            human.manaPool.totalMana() shouldBe 0
        }
    })
