package leyline.game

import com.google.common.collect.ImmutableMultimap
import forge.game.card.CardView
import forge.game.card.CounterType
import forge.game.player.PlayerView
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.coord.CombatPlaybackFramePlanner
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.event.DamageSourceKind
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.event.ZoneMove
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import java.util.concurrent.ConcurrentLinkedQueue
import forge.game.event.GameEventAttackersDeclared as ForgeAttackersDeclared
import forge.game.event.GameEventCardCounters as ForgeCardCounters
import forge.game.event.GameEventCombatEnded as ForgeCombatEnded
import forge.game.event.GameEventPlayerPoisoned as ForgePlayerPoisoned

class GamePlaybackTest :
    FunSpec({

        tags(UnitTag)

        fun createMinimalPlayback(counter: LogicalSequencePlanner = LogicalSequencePlanner()): GamePlayback {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository(), initialSequence = counter.snapshot())
            bridge.cutCoordinator.registerViewer(SeatId(1))
            return GamePlayback(bridge, 1)
        }

        fun gameStateMessage(
            msgId: Int,
            gsId: Int,
        ): GREToClientMessage =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(msgId)
                .setGameStateId(gsId)
                .addSystemSeatIds(1)
                .setGameStateMessage(
                    GameStateMessage
                        .newBuilder()
                        .setType(GameStateType.Diff)
                        .setGameStateId(gsId)
                        .setPrevGameStateId((gsId - 1).coerceAtLeast(0)),
                ).build()

        fun playbackBridge(playback: GamePlayback): GameBridge {
            val bridgeField = GamePlayback::class.java.getDeclaredField("bridge")
            bridgeField.isAccessible = true
            return bridgeField.get(playback) as GameBridge
        }

        fun requestedCut(playback: GamePlayback): PlaybackCutRequest? =
            playbackBridge(playback).cutCoordinator.requestedPlaybackCut(SeatId(1))

        test("ordinary callbacks aggregate first reason max delay and turn start without output") {
            val playback = createMinimalPlayback()

            playback.visit(ForgePlayerPoisoned(null as PlayerView?, null as PlayerView?, 0, 1))
            playback.visit(ForgeCardCounters(null as CardView?, null as CounterType?, 0, 1))

            assertSoftly {
                requestedCut(playback) shouldBe
                    PlaybackCutRequest(
                        reason = PlaybackCutReason.PoisonChanged,
                        delayMs = GamePlayback.COUNTER_DELAY,
                        turnStarted = false,
                    )
                playback.hasPendingMessages() shouldBe false
            }
        }

        test("request aggregation keeps chronology while folding values") {
            PlaybackCutRequest(PlaybackCutReason.TurnBegan, 200, true)
                .aggregate(PlaybackCutRequest(PlaybackCutReason.StackObjectCast, 400, false)) shouldBe
                PlaybackCutRequest(PlaybackCutReason.TurnBegan, 400, true)
        }

        test("combat callbacks only promote the typed safe-point request") {
            val playback = createMinimalPlayback()

            playback.visit(ForgePlayerPoisoned(null as PlayerView?, null as PlayerView?, 0, 1))
            playback.visit(ForgeAttackersDeclared(null as PlayerView?, ImmutableMultimap.of()))

            assertSoftly {
                requestedCut(playback) shouldBe
                    PlaybackCutRequest(
                        reason = PlaybackCutReason.PoisonChanged,
                        delayMs = GamePlayback.COUNTER_DELAY,
                        turnStarted = false,
                        boundary = PlaybackCutBoundary.AttackersDeclared,
                    )
                playback.hasPendingMessages() shouldBe false
            }
        }

        test("combat end callback is silent until its completion hook") {
            val playback = createMinimalPlayback()

            playback.visit(ForgeCombatEnded(emptyList(), emptyList()))

            assertSoftly {
                requestedCut(playback)?.boundary shouldBe PlaybackCutBoundary.CombatEnded
                playback.hasPendingMessages() shouldBe false
            }
        }

        test("shell frame close subsumes the ordinary request without later output") {
            val playback = createMinimalPlayback()

            playback.visit(ForgePlayerPoisoned(null as PlayerView?, null as PlayerView?, 0, 1))
            playback.onFrameCommitted()

            assertSoftly {
                requestedCut(playback) shouldBe null
                playback.hasPendingMessages() shouldBe false
            }
        }

        test("Playback queues messages and reports queue size") {
            val queue = ConcurrentLinkedQueue<List<GREToClientMessage>>()
            queue.shouldBeEmpty()
            queue.add(emptyList())
            queue.size shouldBe 1
            val drained = queue.poll()
            drained.shouldNotBeNull()
            queue.shouldBeEmpty()
        }

        test("No duplicate msgIds when two threads use the same counter") {
            val counter = LogicalSequencePlanner(initialGsId = 10, initialMsgId = 10)

            val sessionMsgIds = (1..3).map { counter.nextMsgId() }
            val engineMsgIds = (1..2).map { counter.nextMsgId() }

            val allMsgIds = sessionMsgIds + engineMsgIds
            allMsgIds.toSet().size shouldBe allMsgIds.size
            allMsgIds.last() shouldBeGreaterThan allMsgIds.first()
        }

        test("drain before outbound respects older queued gameStateId") {
            val playback = createMinimalPlayback()
            val olderGsLaterMsg = listOf(gameStateMessage(msgId = 757, gsId = 576))
            val newerGsLaterMsg = listOf(gameStateMessage(msgId = 760, gsId = 579))
            playbackBridge(playback).cutCoordinator.enqueueCommittedBatchForTest(SeatId(1), olderGsLaterMsg)
            playbackBridge(playback).cutCoordinator.enqueueCommittedBatchForTest(SeatId(1), newerGsLaterMsg)

            playback.drainQueueBeforeMsgId(msgId = 755, maxGsId = 578) shouldBe listOf(olderGsLaterMsg)
            playback.drainQueue() shouldBe listOf(newerGsLaterMsg)
        }

        test("drain before outbound leaves future gameStateId queued") {
            val playback = createMinimalPlayback()
            val futureGsEarlierMsg = listOf(gameStateMessage(msgId = 747, gsId = 569))
            playbackBridge(playback).cutCoordinator.enqueueCommittedBatchForTest(SeatId(1), futureGsEarlierMsg)

            playback.drainQueueBeforeMsgId(msgId = 749, maxGsId = 568).shouldBeEmpty()
            playback.drainQueue() shouldBe listOf(futureGsEarlierMsg)
        }

        test("noncombat spell damage does not activate combat splitting") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe false
        }

        test("fight damage does not activate combat splitting") {
            val events =
                listOf(
                    GameEvent.DamageDealtToCard(
                        sourceCardId = ForgeCardId(10),
                        targetCardId = ForgeCardId(20),
                        amount = 2,
                        sourceKind = DamageSourceKind.Fight,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe false
        }

        test("genuine combat damage activates combat splitting") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe true
        }

        test("mixed damage window stays on the unsplit causal path") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 2,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(30),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                )

            val frame = FrameEventLog(events)
            val plan =
                CombatPlaybackFramePlanner.plan(
                    PlaybackCutRequest(PlaybackCutReason.CombatEnded, 0, turnStarted = true),
                    frame,
                    SeatId(1),
                    currentTurnSeat = 1,
                    matchSeats = setOf(1, 2),
                    sourceControllerSeats = mapOf(ForgeCardId(10) to 1, ForgeCardId(30) to 1),
                )

            assertSoftly {
                events.shouldSplitCombatDamageWindow() shouldBe false
                plan.size shouldBe 1
                plan.single().events shouldBe frame
                plan.single().turnStarted shouldBe true
            }
        }

        test("resolution completion is represented by one closed frame") {
            val damage =
                GameEvent.DamageDealtToPlayer(
                    sourceCardId = ForgeCardId(10),
                    targetSeatId = SeatId(2),
                    amount = 3,
                    sourceKind = DamageSourceKind.SpellOrAbility,
                    changesLife = true,
                )

            val resolving = GameEvent.SpellResolved(cardId = ForgeCardId(10), hasFizzled = false)
            FrameEventLog(
                events = listOf(damage, resolving),
                zoneMoves =
                    listOf(
                        ZoneMove(
                            order = 1,
                            cardId = ForgeCardId(10),
                            from = Zone.Stack,
                            to = Zone.Graveyard,
                            cause = null,
                        ),
                    ),
            ).zoneMoves.single().from shouldBe Zone.Stack
        }

        test("compulsory draw playback does not advertise actions before the card reaches hand") {
            val plan =
                CombatPlaybackFramePlanner.plan(
                    PlaybackCutRequest(PlaybackCutReason.DrawStep, 0, turnStarted = false),
                    FrameEventLog.EMPTY,
                    SeatId(1),
                    currentTurnSeat = 1,
                    matchSeats = setOf(1, 2),
                    sourceControllerSeats = emptyMap(),
                )

            plan.single().embedActions shouldBe false
        }

        test("ambiguous mixed damage emits without waiting for an unrelated resolution") {
            val events =
                listOf(
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(10),
                        targetSeatId = SeatId(2),
                        amount = 2,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(30),
                        targetSeatId = SeatId(2),
                        amount = 3,
                        sourceKind = DamageSourceKind.SpellOrAbility,
                        changesLife = true,
                    ),
                )

            events.shouldSplitCombatDamageWindow() shouldBe false
        }
    })
