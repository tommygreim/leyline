package leyline.game.state

import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import forge.util.MyRandom
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.game.InMemoryCardRepository
import leyline.game.advanceToMain1
import leyline.game.awaitFreshPending
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.event.FrameEventLog
import leyline.game.mapping.ActionMapper
import leyline.game.seedDiffBaseline
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.testkit.BundleBuilderTestSupport
import leyline.testkit.TestCardRegistry
import leyline.testkit.detailString
import leyline.testkit.submitTestAction
import leyline.tooling.headless.ClientAccumulator
import wotc.mtgo.gre.external.messaging.Messages
import java.util.Random
import leyline.testkit.StateMapperShell as StateMapper

/**
 * Integration tests for [leyline.game.state.GameBridge] — verifies the real Forge engine
 * deals hands, resolves grpIds, and handles mulligan keep/mull.
 *
 * Requires card DB init (~2-3s first run, cached after).
 */
class GameBridgeTest :
    FunSpec({

        tags(IntegrationTag)

        var bridge: GameBridge? = null

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        // --- Helpers ---

        fun playLandAndCastCreature(b: GameBridge) {
            val player = b.getPlayer(SeatId(1))!!
            var lastId: String? = null

            // Play a land
            val land = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
            if (land != null) {
                val pending = awaitFreshPending(b, lastId) ?: error("No pending action available")
                b.submitTestAction(pending.actionId, PlayerAction.PlayLand(ForgeCardId(land.id)))
                lastId = pending.actionId
                awaitFreshPending(b, lastId)
            }

            // Try to cast a creature
            val creature = player.getZone(ZoneType.Hand).cards.firstOrNull { it.isCreature }
            if (creature != null) {
                val pending = awaitFreshPending(b, lastId) ?: error("No pending action available")
                b.submitTestAction(pending.actionId, PlayerAction.CastSpell(ForgeCardId(creature.id)))
                awaitFreshPending(b, pending.actionId)
            }
        }

        fun advanceToPhase(
            b: GameBridge,
            target: String,
            maxPasses: Int = 50,
        ) {
            val game = b.getGame()!!
            var lastId: String? = null
            var passes = 0
            while (passes < maxPasses) {
                val pending = awaitFreshPending(b, lastId, timeoutMs = 5_000) ?: break
                if (pending.state.phase == target) return
                b.submitTestAction(pending.actionId, PlayerAction.PassPriority)
                lastId = pending.actionId
                passes++
                if (game.isGameOver) break
            }
        }

        // --- Tests ---

        test("bridge starts and deals hand") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start()

            val seat1Hand = b.getHandGrpIds(SeatId(1))
            val seat2Hand = b.getHandGrpIds(SeatId(2))

            seat1Hand.size shouldBe 7
            seat2Hand.shouldNotBeEmpty()
        }

        test("getHandGrpIds resolves") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start()

            val hand = b.getHandGrpIds(SeatId(1))
            hand.size shouldBe 7
        }

        test("getDeckGrpIds returns full deck") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start()

            val deck = b.getDeckGrpIds(SeatId(1))
            deck.size shouldBe 60
        }

        test("keep advances to priority") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)

            b.getHandGrpIds(SeatId(1)).size shouldBe 7
            b.submitKeep(SeatId(1))
            b.awaitActionPriority(SeatId(1)).shouldBeTrue()

            val pending = b.actionBridge(SeatId(1)).getPending()
            assertSoftly {
                pending.shouldNotBeNull()
                pending.state.kind shouldBe PendingActionKind.PRIORITY
                pending.state.phase shouldBe PhaseType.MAIN1.name
                b.getGame()!!.phaseHandler.phase shouldBe PhaseType.MAIN1
            }
        }

        test("submit mull redraws seven and defers tuck until keep") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start()

            val handBefore = b.getHandGrpIds(SeatId(1))
            handBefore.size shouldBe 7

            b.submitMull(SeatId(1))

            val handAfter = b.getHandGrpIds(SeatId(1))
            handAfter.size shouldBe 7
            b.submitKeep(SeatId(1)).shouldBeTrue()
            b.awaitTuckReady()
            b.getHandGrpIds(SeatId(1)).size shouldBe 7
            b.submitTuck(SeatId(1), b.getHandCards(SeatId(1)).take(1))
            b.awaitActionPriority(SeatId(1)).shouldBeTrue()
            b.getHandGrpIds(SeatId(1)).size shouldBe 6
        }

        test("submit mull twice keeps seven until two cards are tucked after keep") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start()

            b.submitMull(SeatId(1))
            b.getHandGrpIds(SeatId(1)).size shouldBe 7

            b.submitMull(SeatId(1))
            b.getHandGrpIds(SeatId(1)).size shouldBe 7

            b.submitKeep(SeatId(1)).shouldBeTrue()
            b.awaitTuckReady()
            b.submitTuck(SeatId(1), b.getHandCards(SeatId(1)).take(2))
            b.awaitActionPriority(SeatId(1)).shouldBeTrue()
            b.getHandGrpIds(SeatId(1)).size shouldBe 5
        }

        test("mull then keep reaches priority") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start()

            b.submitMull(SeatId(1))
            b.getHandGrpIds(SeatId(1)).size shouldBe 7

            b.submitKeep(SeatId(1)).shouldBeTrue()
            b.awaitTuckReady()
            b.submitTuck(SeatId(1), b.getHandCards(SeatId(1)).take(1))
            b.awaitActionPriority(SeatId(1)).shouldBeTrue()

            b.actionBridge(SeatId(1)).getPending().shouldNotBeNull()
        }

        test("build actions includes lands") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(SeatId(1))
            advanceToMain1(b)

            val game = b.getGame()!!
            listOf(PhaseType.MAIN1, PhaseType.UPKEEP, PhaseType.DRAW) shouldContain game.phaseHandler.phase

            val actions = ActionMapper.buildFromSnapshot(1, GsmSnapshot.capture(game, b, "test", 0), b)

            assertSoftly {
                actions.actionsList.count { it.actionType == Messages.ActionType.Pass } shouldBe 1
                actions.actionsList.count { it.actionType == Messages.ActionType.Play_add3 } shouldBeGreaterThan 0
            }
        }

        test("play land moves card to battlefield") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(SeatId(1))
            advanceToMain1(b)

            val game = b.getGame()!!
            val player = b.getPlayer(SeatId(1))!!

            val handBefore = player.getZone(ZoneType.Hand).size()
            val bfBefore = player.getZone(ZoneType.Battlefield).size()

            val landInHand =
                player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
                    ?: error("No land in hand at seed 42")
            val pending =
                awaitFreshPending(b, null)
                    ?: error("No pending action available")

            b.submitTestAction(pending.actionId, PlayerAction.PlayLand(ForgeCardId(landInHand.id)))
            awaitFreshPending(b, pending.actionId)

            val handAfter = player.getZone(ZoneType.Hand).size()
            val bfAfter = player.getZone(ZoneType.Battlefield).size()

            handAfter shouldBe handBefore - 1
            bfAfter shouldBe bfBefore + 1
        }

        test("game start bundle has correct shape") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(SeatId(1))
            advanceToMain1(b)

            val game = b.getGame()!!
            val result =
                BundleBuilderTestSupport.phaseTransition(
                    BundleBuilder(b, "test-match", 1),
                    b,
                    game,
                    LogicalSequencePlanner(initialGsId = 10, initialMsgId = 0),
                )
            val messages = result.messages

            // Bundle has exactly 5 GRE messages
            messages.size shouldBe 5

            // GRE 1: SendHiFi with 2x PhaseOrStepModified + gameInfo
            val gre1 = messages[0]
            gre1.gameStateMessage.update shouldBe Messages.GameStateUpdate.SendHiFi
            gre1.gameStateMessage.hasGameInfo().shouldBeTrue()
            val phaseAnnotations1 =
                gre1.gameStateMessage.annotationsList
                    .flatMap { it.typeList }
                    .count { it == Messages.AnnotationType.PhaseOrStepModified }
            phaseAnnotations1 shouldBeGreaterThanOrEqualTo 2

            // GRE 2: SendHiFi echo
            val gre2 = messages[1]
            assertSoftly {
                gre2.gameStateMessage.type shouldBe Messages.GameStateType.Diff
                gre2.gameStateMessage.update shouldBe Messages.GameStateUpdate.SendHiFi
                gre2.gameStateMessage.gameStateId shouldBeGreaterThan gre1.gameStateMessage.gameStateId
            }

            // GRE 3: SendAndRecord with 1x PhaseOrStepModified
            val gre3 = messages[2]
            gre3.gameStateMessage.update shouldBe Messages.GameStateUpdate.SendAndRecord
            val phaseAnnotations3 =
                gre3.gameStateMessage.annotationsList
                    .flatMap { it.typeList }
                    .count { it == Messages.AnnotationType.PhaseOrStepModified }
            phaseAnnotations3 shouldBe 1

            // GRE 4: PromptReq
            val gre4 = messages[3]
            gre4.type shouldBe Messages.GREMessageType.PromptReq

            // GRE 5: ActionsAvailableReq
            val gre5 = messages[4]
            gre5.type shouldBe Messages.GREMessageType.ActionsAvailableReq_695e
            gre5.actionsAvailableReq.actionsCount shouldBeGreaterThan 0
        }

        test("post action state has consistent instanceIds") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(SeatId(1))
            advanceToMain1(b)

            val accumulator = ClientAccumulator()
            accumulator.processAll(b.cutCoordinator.drain(SeatId(1)).flatten())
            val player = b.getPlayer(SeatId(1))!!
            val landInHand = player.getZone(ZoneType.Hand).cards.first { it.isLand }
            val pending = awaitFreshPending(b, null)!!
            b.submitTestAction(pending.actionId, PlayerAction.PlayLand(ForgeCardId(landInHand.id)))
            awaitFreshPending(b, pending.actionId)
            accumulator.processAll(b.cutCoordinator.drain(SeatId(1)).flatten())

            accumulator.assertConsistent("after land action window")
        }

        // --- Die roll winner randomization ---

        test("dieRollWinner uses RNG when config unset") {
            MyRandom.setRandom(Random(42))
            val b1 = GameBridge(cardRepository = InMemoryCardRepository())
            val r1 = b1.dieRollWinner
            listOf(1, 2) shouldContain r1

            // Same seed produces same result (deterministic)
            MyRandom.setRandom(Random(42))
            val b2 = GameBridge(cardRepository = InMemoryCardRepository())
            b2.dieRollWinner shouldBe r1

            // Lazy val is stable across accesses
            b1.dieRollWinner shouldBe r1
        }

        test("dieRollWinner respects config override") {
            val config1 = EngineSettings(dieRollWinner = 1)
            val b1 = GameBridge(cardRepository = InMemoryCardRepository(), engineSettings = config1)
            b1.dieRollWinner shouldBe 1

            val config2 = EngineSettings(dieRollWinner = 2)
            val b2 = GameBridge(cardRepository = InMemoryCardRepository(), engineSettings = config2)
            b2.dieRollWinner shouldBe 2
        }

        // --- Deterministic seed tests ---

        test("deterministic seed produces same hand") {
            val b1 = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b1
            b1.start(seed = 42L)
            val hand1 = b1.getHandGrpIds(SeatId(1))
            b1.shutdown()

            val b2 = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b2
            b2.start(seed = 42L)
            val hand2 = b2.getHandGrpIds(SeatId(1))

            hand1 shouldBe hand2
        }

        test("teardown tolerates an already absent event collector") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)
            b.getGame()!!.unsubscribeFromEvents(b.eventCollector.shouldNotBeNull())

            shouldNotThrowAny { b.teardownResources() }
            shouldNotThrowAny { b.teardownResources() }
        }

        // --- Double-diff tests ---

        test("phase transition emits five message pattern") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(SeatId(1))
            advanceToMain1(b)

            val game = b.getGame()!!
            val result =
                BundleBuilderTestSupport.phaseTransition(
                    BundleBuilder(b, "test-match", 1),
                    b,
                    game,
                    LogicalSequencePlanner(initialGsId = 10, initialMsgId = 0),
                )

            assertSoftly {
                result.messages.size shouldBe 5

                // Message 1: SendHiFi with PhaseOrStepModified annotations
                val gs1 = result.messages[0].gameStateMessage
                gs1.update shouldBe Messages.GameStateUpdate.SendHiFi
                gs1.type shouldBe Messages.GameStateType.Diff

                // Message 2: SendHiFi echo
                val gs2 = result.messages[1].gameStateMessage
                gs2.update shouldBe Messages.GameStateUpdate.SendHiFi
                gs2.type shouldBe Messages.GameStateType.Diff

                // Message 3: SendAndRecord with PhaseOrStepModified
                val gs3 = result.messages[2].gameStateMessage
                gs3.update shouldBe Messages.GameStateUpdate.SendAndRecord
                gs3.type shouldBe Messages.GameStateType.Diff

                // Message 4: PromptReq (promptId=37)
                result.messages[3].type shouldBe Messages.GREMessageType.PromptReq
                result.messages[3].prompt.promptId shouldBe 37

                // Message 5: ActionsAvailableReq (promptId=2)
                result.messages[4].type shouldBe Messages.GREMessageType.ActionsAvailableReq_695e
                result.messages[4].prompt.promptId shouldBe 2
            }

            // gsIds should be ascending across GSM messages
            val gsIds =
                result.messages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage.gameStateId }
            for (i in 1 until gsIds.size) {
                gsIds[i] shouldBeGreaterThan gsIds[i - 1]
            }
        }

        // --- Game loop contract tests ---

        test("embedded actions have stripped format") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(SeatId(1))
            advanceToMain1(b)

            val game = b.getGame()!!
            val actions = ActionMapper.buildFromSnapshot(1, GsmSnapshot.capture(game, b, "test", 0), b)
            val snapGb1 = GsmSnapshot.capture(game, b, "test-match", 1)
            val gs =
                StateMapper
                    .buildFromSnapshot(
                        snapGb1,
                        1,
                        "test-match",
                        b,
                        actions = actions,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            gs.actionsCount shouldBeGreaterThan 0
            assertSoftly {
                gs.actionsList.map { it.actionId }.toSet() shouldBe setOf(0)
                gs.actionsList.map { it.seatId }.toSet() shouldBe setOf(1)
                gs.actionsList.count { it.hasAction() } shouldBe gs.actionsCount
                gs.actionsList.map { it.action.grpId }.toSet() shouldBe setOf(0)
                gs.actionsList.map { it.action.facetId }.toSet() shouldBe setOf(0)
                gs.actionsList.count { it.action.shouldStop } shouldBe 0
            }
        }

        test("game start bundle gsIds ascending") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(SeatId(1))
            advanceToMain1(b)

            val game = b.getGame()!!
            val result =
                BundleBuilderTestSupport.phaseTransition(
                    BundleBuilder(b, "test-match", 1),
                    b,
                    game,
                    LogicalSequencePlanner(initialGsId = 10, initialMsgId = 0),
                )

            var prevGsId = 0
            for (msg in result.messages) {
                if (msg.hasGameStateMessage()) {
                    val gsId = msg.gameStateMessage.gameStateId
                    gsId shouldBeGreaterThan prevGsId
                    prevGsId = gsId
                }
            }
        }

        // --- ZoneTransfer annotation tests ---

        test("land play produces ZoneTransfer annotation") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(SeatId(1))
            advanceToMain1(b)

            val player = b.getPlayer(SeatId(1))!!
            val land =
                player.getZone(ZoneType.Hand).cards.firstOrNull { it.isLand }
                    ?: error("No land in hand at seed 42")
            val pending =
                awaitFreshPending(b, null)
                    ?: error("No pending action available")
            b.cutCoordinator.drain(SeatId(1))
            b.submitTestAction(pending.actionId, PlayerAction.PlayLand(ForgeCardId(land.id)))
            awaitFreshPending(b, pending.actionId)

            val zoneTransfers =
                b.cutCoordinator
                    .drain(SeatId(1))
                    .flatten()
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .filter { it.typeList.contains(Messages.AnnotationType.ZoneTransfer_af5a) }
            assertSoftly {
                zoneTransfers.size shouldBe 1
                zoneTransfers.first().detailString("category") shouldBe "PlayLand"
            }
        }

        // --- Diff state tests ---

        test("post action sends Diff not Full") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(SeatId(1))
            advanceToMain1(b)

            val game = b.getGame()!!

            // Seed snapshot — subsequent buildDiff should produce Diff
            b.seedDiffBaseline(game)

            val result =
                BundleBuilderTestSupport.postAction(
                    BundleBuilder(b, "test-match", 1),
                    b,
                    game,
                    LogicalSequencePlanner(initialGsId = 10, initialMsgId = 0),
                )
            val gs = result.messages.first().gameStateMessage

            assertSoftly {
                gs.type shouldBe Messages.GameStateType.Diff
                // Unchanged metadata is trimmed from Diff frames.
                gs.playersCount shouldBe 0
                gs.hasTurnInfo().shouldBeFalse()
            }
        }

        test("diff falls back to Full without snapshot") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            bridge = b
            b.start(seed = 42L)
            b.submitKeep(SeatId(1))
            advanceToMain1(b)

            val game = b.getGame()!!

            // No diff baseline — buildDiff with null prev falls back to Full
            val snapFull = GsmSnapshot.capture(game, b, "test-match", 1)
            val gs =
                StateMapper
                    .buildDiff(
                        null,
                        snapFull,
                        FrameEventLog.EMPTY,
                        1,
                        "test-match",
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            gs.type shouldBe Messages.GameStateType.Full
            gs.zonesCount shouldBeGreaterThan 0
        }

        // --- skipMulligan tests ---

        test("skip mulligan advances to priority without keep") {
            val config = EngineSettings(skipMulligan = true)
            val b = GameBridge(cardRepository = InMemoryCardRepository(), engineSettings = config)
            bridge = b
            b.start(seed = 42L)

            // No submitKeep — engine should auto-keep via MulliganBridge(autoKeep=true)
            b.awaitPriority()

            val pending = b.actionBridge(SeatId(1)).getPending()
            pending.shouldNotBeNull()

            val game = b.getGame()!!
            game.phaseHandler.phase shouldBe PhaseType.MAIN1

            // Hand should still have 7 cards (auto-kept, no mull)
            val hand = b.getHandGrpIds(SeatId(1))
            hand.size shouldBe 7
        }

        test("skip mulligan produces valid game state") {
            val config = EngineSettings(skipMulligan = true)
            val b = GameBridge(cardRepository = InMemoryCardRepository(), engineSettings = config)
            bridge = b
            b.start(seed = 42L)
            b.awaitPriority()

            val game = b.getGame()!!
            val snapGb5 = GsmSnapshot.capture(game, b, "test-match", 1)
            val gs =
                StateMapper
                    .buildFromSnapshot(
                        snapGb5,
                        1,
                        "test-match",
                        b,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            assertSoftly {
                gs.zonesCount shouldBeGreaterThan 0
                gs.gameObjectsCount shouldBeGreaterThan 0
                gs.hasTurnInfo().shouldBeTrue()
                gs.turnInfo.turnNumber shouldBeGreaterThanOrEqualTo 1
            }
        }

        test("modal identity is consumed once and retained by trigger") {
            val b = GameBridge(cardRepository = InMemoryCardRepository())
            val source = ForgeCardId(42)

            b.recordSelectedModalAbilityGrpId(source, 1001)
            b.resolvePendingTriggerAbilityIdentity(51, source) { 2001 } shouldBe 1001
            b.resolvePendingTriggerAbilityIdentity(51, source) { 2001 } shouldBe 1001

            b.recordSelectedModalAbilityGrpId(source, 1002)
            b.resolvePendingTriggerAbilityIdentity(52, source) { 2002 } shouldBe 1002
        }
    })
