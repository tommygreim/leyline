package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.game.event.FrameEventLog
import leyline.game.state.GameBridge
import leyline.testkit.BoardTest
import leyline.testkit.BundleBuilderTestSupport
import leyline.testkit.haveManaCost
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * `ActionMapper.buildNaiveActionsFromSnapshot` — the action list embedded in
 * GSM frames during the opponent's turn, built from the immutable snapshot
 * without a legality pass.
 *
 * Two things the naive path gets wrong if left unguarded: it can offer actions
 * the turn structure forbids, and it can serialize a card's printed cost rather
 * than its effective one, because the fallback it used to route hand casts
 * through read `CardData.manaCost` directly.
 */
class NaiveActionsTest :
    BoardTest({

        fun naiveCastAction(
            bridge: GameBridge,
            game: forge.game.Game,
            instanceId: Int,
        ): Action {
            val snap =
                leyline.game.snapshot.SnapshotCapture
                    .run(game, bridge, "test", 0)
            val req = ActionMapper.buildNaiveActionsFromSnapshot(1, snap, bridge)
            return (req.actionsList + req.inactiveActionsList)
                .first { it.actionType == ActionType.Cast && it.instanceId == instanceId }
        }

        test("lands are offered as inactive, never as playable actions") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Plains", human, ZoneType.Hand)
                }

            val snap =
                leyline.game.snapshot.SnapshotCapture
                    .run(game, bridge, "test", 0)
            val req = ActionMapper.buildNaiveActionsFromSnapshot(1, snap, bridge)

            assertSoftly {
                req.actionsList.filter { it.actionType == ActionType.Play_add3 }.shouldBeEmpty()
                req.inactiveActionsList.filter { it.actionType == ActionType.Play_add3 }.shouldNotBeEmpty()
            }
        }

        test("naive cast and embedded GSM actions reflect graveyard cost reduction") {
            var crabId = 0
            val (bridge, game, counter) =
                startWithBoard { _, human, _ ->
                    val crab = addCard("Eddymurk Crab", human, ZoneType.Hand)
                    crabId = crab.id
                    repeat(4) { addCard("Lightning Bolt", human, ZoneType.Graveyard) }
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Battlefield)
                }

            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(crabId)).value
            val legalActions = BundleBuilderTestSupport.buildActions(bridge)
            val legalAction =
                (legalActions.actionsList + legalActions.inactiveActionsList)
                    .first { it.actionType == ActionType.Cast && it.instanceId == instanceId }
            val naiveAction = naiveCastAction(bridge, game, instanceId)
            // Playback cuts are the opponent-turn frame vehicle: every frame's
            // content GSM embeds the naive action list.
            val builder = bundleBuilder(bridge)
            val cut = builder.materializePlaybackCut(game, counter, turnStarted = false, events = FrameEventLog.EMPTY)
            val embeddedGsmAction =
                builder
                    .compilePlaybackCut(cut)
                    .batches
                    .first()
                    .first { it.hasGameStateMessage() }
                    .gameStateMessage
                    .actionsList
                    .first { it.action.actionType == ActionType.Cast && it.action.instanceId == instanceId }
                    .action

            assertSoftly {
                // Printed {5}{U}{U}; four graveyard instants reduce it to {1}{U}{U}.
                legalAction should haveManaCost(generic = 1, blue = 2)
                naiveAction should haveManaCost(generic = 1, blue = 2)
                embeddedGsmAction should haveManaCost(generic = 1, blue = 2)
                // The Arena client renders the generic component first. Keep
                // the ordering stable across live, naive, and embedded rails.
                legalAction.manaCostList.map { it.colorList to it.count } shouldBe
                    listOf(
                        listOf(ManaColor.Generic) to 1,
                        listOf(ManaColor.Blue_afc9) to 2,
                    )
                naiveAction.manaCostList.map { it.colorList to it.count } shouldBe
                    listOf(
                        listOf(ManaColor.Generic) to 1,
                        listOf(ManaColor.Blue_afc9) to 2,
                    )
                embeddedGsmAction.manaCostList.map { it.colorList to it.count } shouldBe
                    listOf(
                        listOf(ManaColor.Generic) to 1,
                        listOf(ManaColor.Blue_afc9) to 2,
                    )
            }
        }

        test("naive emission order matches the opponent-turn contract") {
            // ADR 0003: emission order is protocol-significant. Battlefield
            // ActivateMana first, then inactive land Plays, then Cast before
            // CastAdventure per hand spell, then Pass/FloatMana.
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    addCard("Ratcatcher Trainee", human, ZoneType.Hand) // adventure
                }

            val snap =
                leyline.game.snapshot.SnapshotCapture
                    .run(game, bridge, "test", 0)
            val req = ActionMapper.buildNaiveActionsFromSnapshot(1, snap, bridge)

            req.actionsList.map { it.actionType } shouldBe
                listOf(
                    ActionType.ActivateMana,
                    ActionType.Cast,
                    ActionType.Cast,
                    ActionType.CastAdventure,
                    ActionType.Pass,
                    ActionType.FloatMana,
                )
            req.inactiveActionsList.map { it.actionType } shouldBe listOf(ActionType.Play_add3)
        }

        test("naive cast shows printed cost with no graveyard reducer") {
            var crabId = 0
            val (bridge, game, _) =
                startWithBoard { _, human, _ ->
                    val crab = addCard("Eddymurk Crab", human, ZoneType.Hand)
                    crabId = crab.id
                }

            val instanceId = bridge.getOrAllocInstanceId(ForgeCardId(crabId)).value

            naiveCastAction(bridge, game, instanceId) should haveManaCost(generic = 5, blue = 2)
        }
    })
