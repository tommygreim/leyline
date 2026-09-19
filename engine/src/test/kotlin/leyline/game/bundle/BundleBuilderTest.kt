package leyline.game.bundle

import forge.card.CardType
import forge.card.RemoveType
import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.TargetingCandidateValue
import leyline.bridge.handoff.TargetingWindowValue
import leyline.bridge.handoff.TargetingZone
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.GamePlayback
import leyline.game.InMemoryCardRepository
import leyline.game.PlaybackTerminalFailure
import leyline.game.annotations.AnnotationLossReason
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.CastingTimeOptionsBuilder.ModalOptionSpec
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.bundle.RequestBuilder
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.iid
import leyline.game.mapping.ProjectionSupplement
import leyline.game.mapping.PromptIds
import leyline.game.mapping.StateFrameInput
import leyline.game.mapping.StateProjectionCompiler
import leyline.game.mapping.ViewerProjectionIntent
import leyline.game.mapping.ZoneIds
import leyline.game.seedDiffBaseline
import leyline.game.sid
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.PhaseSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.GameBridge
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.StaleProjectionTransitionException
import leyline.testkit.BoardTest
import leyline.testkit.BundleBuilderTestSupport
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import java.util.EnumSet

/**
 * Tests for [leyline.game.bundle.BundleBuilder] proto assembly.
 *
 * Unit group: pure proto wrappers (no game needed).
 * Conformance group: bundle shape checks via [startWithBoard].
 */
@Suppress("LargeClass") // Builder fixture lives in one class so the proto-shape assertions stay co-located.
class BundleBuilderTest :
    BoardTest({

        /** Create a BundleBuilder for pure proto tests (no game state needed). */
        fun pureBB(
            seatId: Int = 1,
            matchId: String = "test-match",
        ) = BundleBuilder(GameBridge(cardRepository = InMemoryCardRepository()), matchId, seatId)

        fun targetingWindow(source: ForgeCardId?) =
            TargetingWindowValue(
                sourceForgeCardId = source,
                sourceGrpId = 0,
                outerAbilityGrpId = 0,
                targetingAbilityGrpId = 0,
                targetSourceZoneId = ZoneIds.BATTLEFIELD,
                targetPromptId = PromptIds.SELECT_TARGETS,
                targetIndex = 1,
                minTargets = 1,
                maxTargets = 1,
                chooserSeatId = SeatId(1),
                candidates = listOf(TargetingCandidateValue.Card(0, ForgeCardId(999), TargetingZone.Battlefield)),
                isTriggeredAbility = false,
                forgeAbilityId = 0,
            )

        // --- Unit tests (pure proto, no game) ---

        test("transition game info retains timer lookup identity") {
            val gameInfo = GsmBuilder.buildTransitionGameInfo("test-match")

            assertSoftly {
                gameInfo.gameNumber shouldBe 1
                gameInfo.type shouldBe Messages.GameType.Duel
                gameInfo.variant shouldBe Messages.GameVariant.Normal
            }
        }

        test("queuedGameState wraps GSM with type 51") {
            val gs =
                Messages.GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(42)
                    .build()

            val msg =
                BundleBuilder(GameBridge(cardRepository = InMemoryCardRepository()), "test-match", 2)
                    .queuedGameState(gs, LogicalSequencePlanner(initialGsId = 42, initialMsgId = 9))

            assertSoftly {
                msg.type shouldBe GREMessageType.QueuedGameStateMessage
                msg.hasGameStateMessage().shouldBeTrue()
                msg.gameStateMessage.gameStateId shouldBe 42
            }
        }

        test("coinFlipPromptMessages emits promptId 46 notification") {
            val counter = LogicalSequencePlanner(initialGsId = 10, initialMsgId = 20)
            val messages =
                pureBB().coinFlipPromptMessages(
                    events =
                        listOf(
                            GameEvent.CoinFlipped(
                                flipperSeatId = 1.sid,
                                sourceCardId = ForgeCardId(100),
                                abilityForgeId = 200,
                                abilityGrpId = 19490,
                                result = 1,
                            ),
                            GameEvent.CoinFlipped(
                                flipperSeatId = 1.sid,
                                sourceCardId = ForgeCardId(100),
                                abilityForgeId = 200,
                                abilityGrpId = 19490,
                                result = 0,
                            ),
                        ),
                    gsId = 10,
                    counter = counter,
                )

            val winPrompt = messages[0].prompt
            val lossPrompt = messages[1].prompt
            assertSoftly {
                messages.map { it.type } shouldBe listOf(GREMessageType.PromptReq, GREMessageType.PromptReq)
                winPrompt.promptId shouldBe PromptIds.COIN_FLIP
                winPrompt.getParameters(0).parameterName shouldBe "PlayerId"
                winPrompt.getParameters(0).reference.type shouldBe Messages.ReferenceType.PlayerSeatId
                winPrompt.getParameters(0).reference.id shouldBe 1
                winPrompt.getParameters(1).parameterName shouldBe "CoinFlipResult"
                winPrompt.getParameters(1).reference.type shouldBe Messages.ReferenceType.LocalizationId
                winPrompt.getParameters(1).reference.id shouldBe 47
                lossPrompt.promptId shouldBe PromptIds.COIN_FLIP
                lossPrompt.getParameters(1).reference.id shouldBe 48
            }
        }

        test("buildModalCastingTimeOptionsReq — Charm shape (no costs, no excluded)") {
            val req =
                CastingTimeOptionsBuilder.buildModalCastingTimeOptionsReq(
                    parentGrpId = 200001,
                    modalOptions = listOf(101, 102, 103).map(::ModalOptionSpec),
                    minSel = 1,
                    maxSel = 1,
                    sourceInstanceId = 555,
                    grpId = 90000,
                )

            req.castingTimeOptionReqCount shouldBe 1
            val opt = req.getCastingTimeOptionReq(0)
            opt.castingTimeOptionType shouldBe Messages.CastingTimeOptionType.Modal_a7b4
            val mr = opt.modalReq
            assertSoftly {
                mr.modalOptionsCount shouldBe 3
                mr.getModalOptions(0).grpId shouldBe 101
                mr.getModalOptions(0).modeCostCount shouldBe 0
                mr.excludedOptionsCount shouldBe 0
                mr.minSel shouldBe 1
                mr.maxSel shouldBe 1
            }
        }

        test("buildModalCastingTimeOptionsReq — Spree shape (modeCost + excludedOptions)") {
            val req =
                CastingTimeOptionsBuilder.buildModalCastingTimeOptionsReq(
                    parentGrpId = 173717,
                    modalOptions =
                        listOf(
                            ModalOptionSpec(171803, listOf(Messages.ManaColor.Generic to 3)),
                            ModalOptionSpec(171804, listOf(Messages.ManaColor.Generic to 2)),
                        ),
                    excludedOptions =
                        listOf(
                            ModalOptionSpec(
                                171802,
                                listOf(
                                    Messages.ManaColor.Generic to 1,
                                    Messages.ManaColor.Blue_afc9 to 1,
                                ),
                            ),
                        ),
                    minSel = 1,
                    maxSel = 2,
                    sourceInstanceId = 240,
                    grpId = 90421,
                )

            val opt = req.getCastingTimeOptionReq(0)
            val mr = opt.modalReq
            assertSoftly {
                mr.modalOptionsCount shouldBe 2
                mr.getModalOptions(0).grpId shouldBe 171803
                mr.getModalOptions(0).modeCostCount shouldBe 1
                mr.getModalOptions(0).getModeCost(0).id shouldBe 1
                mr
                    .getModalOptions(0)
                    .getModeCost(0)
                    .manaCost
                    .getColor(0) shouldBe Messages.ManaColor.Generic
                mr
                    .getModalOptions(0)
                    .getModeCost(0)
                    .manaCost.count shouldBe 3

                mr.getModalOptions(1).grpId shouldBe 171804
                mr.getModalOptions(1).getModeCost(0).id shouldBe 2
                mr
                    .getModalOptions(1)
                    .getModeCost(0)
                    .manaCost.count shouldBe 2

                mr.excludedOptionsCount shouldBe 1
                mr.getExcludedOptions(0).grpId shouldBe 171802
                mr.getExcludedOptions(0).modeCostCount shouldBe 2
                mr.getExcludedOptions(0).getModeCost(0).id shouldBe 3
                mr.getExcludedOptions(0).getModeCost(1).id shouldBe 4
                mr
                    .getExcludedOptions(0)
                    .getModeCost(0)
                    .manaCost
                    .getColor(0) shouldBe Messages.ManaColor.Generic
                mr
                    .getExcludedOptions(0)
                    .getModeCost(0)
                    .manaCost.count shouldBe 1
                mr
                    .getExcludedOptions(0)
                    .getModeCost(1)
                    .manaCost
                    .getColor(0) shouldBe Messages.ManaColor.Blue_afc9
                mr
                    .getExcludedOptions(0)
                    .getModeCost(1)
                    .manaCost.count shouldBe 1
            }
        }

        test("buildModalCastingTimeOptionsReq — Tiered shape (one of three costed tiers)") {
            val req =
                CastingTimeOptionsBuilder.buildModalCastingTimeOptionsReq(
                    parentGrpId = 189137,
                    modalOptions =
                        listOf(
                            ModalOptionSpec(189134, listOf(Messages.ManaColor.Generic to 0)),
                            ModalOptionSpec(189135, listOf(Messages.ManaColor.Generic to 2)),
                            ModalOptionSpec(
                                189136,
                                listOf(
                                    Messages.ManaColor.Generic to 5,
                                    Messages.ManaColor.Blue_afc9 to 1,
                                ),
                            ),
                        ),
                    minSel = 1,
                    maxSel = 1,
                    sourceInstanceId = 283,
                    grpId = 95912,
                    ctoId = 2,
                    playerIdToPrompt = 1,
                )

            val opt = req.getCastingTimeOptionReq(0)
            val mr = opt.modalReq
            assertSoftly {
                opt.ctoId shouldBe 2
                opt.grpId shouldBe 95912
                opt.playerIdToPrompt shouldBe 1
                mr.abilityGrpId shouldBe 189137
                mr.minSel shouldBe 1
                mr.maxSel shouldBe 1
                mr.modalOptionsList.map { it.grpId } shouldBe listOf(189134, 189135, 189136)
                mr.excludedOptionsCount shouldBe 0
                mr.getModalOptions(0).getModeCost(0).id shouldBe 1
                mr.getModalOptions(1).getModeCost(0).id shouldBe 2
                mr.getModalOptions(2).getModeCost(0).id shouldBe 3
                mr.getModalOptions(2).getModeCost(1).id shouldBe 4
                mr
                    .getModalOptions(0)
                    .getModeCost(0)
                    .manaCost.count shouldBe 0
                mr
                    .getModalOptions(1)
                    .getModeCost(0)
                    .manaCost.count shouldBe 2
                mr
                    .getModalOptions(2)
                    .getModeCost(0)
                    .manaCost.count shouldBe 5
                mr
                    .getModalOptions(2)
                    .getModeCost(1)
                    .manaCost
                    .getColor(0) shouldBe Messages.ManaColor.Blue_afc9
            }
        }

        test("buildOptionalCostCastingTimeOptionsReq — Gift shape (single AdditionalCost + Done terminator)") {
            val (req, ids) =
                CastingTimeOptionsBuilder.buildOptionalCostCastingTimeOptionsReq(
                    instanceId = 240,
                    optionalCosts = listOf(Messages.CastingTimeOptionType.AdditionalCost to 173850),
                    playerIdToPrompt = 1,
                    baseManaCost = listOf(Messages.ManaColor.Blue_afc9 to 1),
                )

            ids shouldBe listOf(1)
            assertSoftly {
                req.castingTimeOptionReqCount shouldBe 2
                val opt = req.getCastingTimeOptionReq(0)
                opt.ctoId shouldBe 1
                opt.castingTimeOptionType shouldBe Messages.CastingTimeOptionType.AdditionalCost
                opt.grpId shouldBe 173850
                opt.playerIdToPrompt shouldBe 1
                opt.affectedId shouldBe 240
                opt.affectorId shouldBe 240
                req.getCastingTimeOptionReq(1).castingTimeOptionType shouldBe Messages.CastingTimeOptionType.Done
                req.getCastingTimeOptionReq(1).isRequired.shouldBeTrue()
            }
        }

        test("buildManaTypeCastingTimeOptionsReq emits one required option per hybrid pip") {
            val (req, ids) =
                CastingTimeOptionsBuilder.buildManaTypeCastingTimeOptionsReq(
                    instanceId = 236,
                    grpId = 95755,
                    playerIdToPrompt = 2,
                    hybridColors =
                        listOf(
                            Messages.ManaColor.Green_afc9,
                            Messages.ManaColor.Blue_afc9,
                            Messages.ManaColor.Red_afc9,
                        ),
                    manaCost =
                        listOf(
                            leyline.bridge.handoff.ManaRequirementSpec.frozen(
                                listOf(Messages.ManaColor.TwoGeneric, Messages.ManaColor.Green_afc9),
                            ),
                            leyline.bridge.handoff.ManaRequirementSpec.frozen(
                                listOf(Messages.ManaColor.TwoGeneric, Messages.ManaColor.Blue_afc9),
                            ),
                            leyline.bridge.handoff.ManaRequirementSpec.frozen(
                                listOf(Messages.ManaColor.TwoGeneric, Messages.ManaColor.Red_afc9),
                            ),
                        ),
                )

            ids shouldBe listOf(2, 3, 4)
            assertSoftly {
                req.castingTimeOptionReqCount shouldBe 3
                val green = req.getCastingTimeOptionReq(0)
                green.ctoId shouldBe 2
                green.castingTimeOptionType shouldBe Messages.CastingTimeOptionType.ManaType
                green.isRequired.shouldBeTrue()
                green.affectedId shouldBe 236
                green.affectorId shouldBe 236
                green.grpId shouldBe 95755
                green.playerIdToPrompt shouldBe 2
                green.selectManaTypeReq.sourceId shouldBe 236
                green.selectManaTypeReq.manaColorsList shouldBe
                    listOf(Messages.ManaColor.TwoGeneric, Messages.ManaColor.Green_afc9)
                green.manaCostCount shouldBe 3
                green.getManaCost(0).colorList shouldBe listOf(Messages.ManaColor.TwoGeneric, Messages.ManaColor.Green_afc9)
                req.getCastingTimeOptionReq(1).selectManaTypeReq.manaColorsList shouldBe
                    listOf(Messages.ManaColor.TwoGeneric, Messages.ManaColor.Blue_afc9)
                req.getCastingTimeOptionReq(2).selectManaTypeReq.manaColorsList shouldBe
                    listOf(Messages.ManaColor.TwoGeneric, Messages.ManaColor.Red_afc9)
            }
        }

        test("buildOptionalCostCastingTimeOptionsReq — combined Bargain + Offspring shape (mixed ctoTypes)") {
            // Unified emit: an OptionalCost-enum cost (Bargain) and a
            // KeywordWithCost cost (Offspring) on the same cast surface as
            // one combined modal. ctoIds are 1-based sequential; trailing
            // entry is the Done terminator.
            val (req, ids) =
                CastingTimeOptionsBuilder.buildOptionalCostCastingTimeOptionsReq(
                    instanceId = 555,
                    optionalCosts =
                        listOf(
                            Messages.CastingTimeOptionType.Bargain to 303,
                            Messages.CastingTimeOptionType.AdditionalCost to 173931,
                        ),
                    playerIdToPrompt = 1,
                    baseManaCost = listOf(Messages.ManaColor.Generic to 1, Messages.ManaColor.Blue_afc9 to 1),
                )

            assertSoftly {
                ids shouldBe listOf(1, 2)
                req.castingTimeOptionReqCount shouldBe 3 // 2 costs + Done
                req.getCastingTimeOptionReq(0).ctoId shouldBe 1
                req.getCastingTimeOptionReq(0).castingTimeOptionType shouldBe Messages.CastingTimeOptionType.Bargain
                req.getCastingTimeOptionReq(0).grpId shouldBe 303
                req.getCastingTimeOptionReq(1).ctoId shouldBe 2
                req.getCastingTimeOptionReq(1).castingTimeOptionType shouldBe Messages.CastingTimeOptionType.AdditionalCost
                req.getCastingTimeOptionReq(1).grpId shouldBe 173931
                req.getCastingTimeOptionReq(2).castingTimeOptionType shouldBe Messages.CastingTimeOptionType.Done
            }
        }

        test("buildOptionalCostCastingTimeOptionsReq — empty optionalCosts still emits Done terminator") {
            val (req, ids) =
                CastingTimeOptionsBuilder.buildOptionalCostCastingTimeOptionsReq(
                    instanceId = 100,
                    optionalCosts = emptyList(),
                    playerIdToPrompt = 1,
                    baseManaCost = emptyList(),
                )
            assertSoftly {
                ids shouldBe emptyList<Int>()
                req.castingTimeOptionReqCount shouldBe 1
                req.getCastingTimeOptionReq(0).castingTimeOptionType shouldBe Messages.CastingTimeOptionType.Done
            }
        }

        test("buildOptionalCostCastingTimeOptionsReq populates playerIdToPrompt + manaCost on every entry including Done") {
            val (req, costCtoIds) =
                CastingTimeOptionsBuilder.buildOptionalCostCastingTimeOptionsReq(
                    instanceId = 100,
                    optionalCosts =
                        listOf(
                            Messages.CastingTimeOptionType.AdditionalCost to 303,
                            Messages.CastingTimeOptionType.Kicker to 94999,
                        ),
                    playerIdToPrompt = 1,
                    baseManaCost =
                        listOf(
                            Messages.ManaColor.Generic to 2,
                            Messages.ManaColor.Black_afc9 to 1,
                        ),
                )

            costCtoIds shouldBe listOf(1, 2)
            req.castingTimeOptionReqCount shouldBe 3 // 2 cost entries + Done

            val cost0 = req.getCastingTimeOptionReq(0)
            val cost1 = req.getCastingTimeOptionReq(1)
            val done = req.getCastingTimeOptionReq(2)

            assertSoftly {
                cost0.castingTimeOptionType shouldBe Messages.CastingTimeOptionType.AdditionalCost
                cost0.grpId shouldBe 303
                cost0.affectedId shouldBe 100
                cost0.affectorId shouldBe 100
                cost0.playerIdToPrompt shouldBe 1
                cost0.manaCostCount shouldBe 2
                cost0.getManaCost(0).getColor(0) shouldBe Messages.ManaColor.Generic
                cost0.getManaCost(0).count shouldBe 2
                cost0.getManaCost(0).objectId shouldBe 100
                cost0.getManaCost(1).getColor(0) shouldBe Messages.ManaColor.Black_afc9

                cost1.castingTimeOptionType shouldBe Messages.CastingTimeOptionType.Kicker
                cost1.grpId shouldBe 94999
                cost1.playerIdToPrompt shouldBe 1
                cost1.manaCostCount shouldBe 2

                done.castingTimeOptionType shouldBe Messages.CastingTimeOptionType.Done
                done.isRequired.shouldBeTrue()
                done.playerIdToPrompt shouldBe 1
                done.manaCostCount shouldBe 2
                done.getManaCost(0).getColor(0) shouldBe Messages.ManaColor.Generic
                done.getManaCost(0).count shouldBe 2
                done.getManaCost(0).objectId shouldBe 100
            }
        }

        test("buildOptionalCostCastingTimeOptionsReq with empty baseManaCost leaves manaCost unset") {
            val (req, _) =
                CastingTimeOptionsBuilder.buildOptionalCostCastingTimeOptionsReq(
                    instanceId = 200,
                    optionalCosts = listOf(Messages.CastingTimeOptionType.AdditionalCost to 303),
                    playerIdToPrompt = 2,
                    baseManaCost = emptyList(),
                )

            assertSoftly {
                req.getCastingTimeOptionReq(0).manaCostCount shouldBe 0
                req.getCastingTimeOptionReq(0).playerIdToPrompt shouldBe 2
                req.getCastingTimeOptionReq(1).manaCostCount shouldBe 0
                req.getCastingTimeOptionReq(1).playerIdToPrompt shouldBe 2
            }
        }

        @Suppress("WeakAssertionOnly")
        test("edictalPass sends server-forced Pass action") {
            val counter = LogicalSequencePlanner(initialGsId = 10, initialMsgId = 0)
            val result = pureBB().edictalPass(counter = counter)

            result.messages.size shouldBe 1
            val msg = result.messages[0]
            msg.type shouldBe GREMessageType.EdictalMessage_695e
            msg.hasEdictalMessage().shouldBeTrue()

            val inner = msg.edictalMessage.edictMessage
            inner.type shouldBe Messages.ClientMessageType.PerformActionResp_097b
            inner.systemSeatId shouldBe 1
            val action = inner.performActionResp.actionsList.first()
            action.actionType shouldBe Messages.ActionType.Pass
        }

        test("echo diff prevGsId uses last emitted GSM instead of gsId adjacency") {
            val counter =
                LogicalSequencePlanner(LogicalSequenceState(currentGsId = 7, currentMsgId = 0, lastGameStateGsId = 7))
            counter.nextGsId() // prompt-only interleave

            val echo = pureBB().buildEchoDiffGsm(counter)

            assertSoftly {
                echo.gameStateMessage.gameStateId shouldBe 9
                echo.gameStateMessage.prevGameStateId shouldBe 7
            }
        }

        test("gameOverBundle produces 3 GSM diffs + IntermissionReq") {
            val counter = LogicalSequencePlanner(initialGsId = 10, initialMsgId = 0)
            val result =
                pureBB().gameOverBundle(
                    winningTeam = 1,
                    counter = counter,
                    losingPlayerSeatId = 2,
                    lossReason = AnnotationLossReason.LifeTotal,
                )

            result.messages.size shouldBe 4

            for (i in 0..2) {
                result.messages[i].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[i].gameStateMessage.type shouldBe GameStateType.Diff
            }

            val gs1 = result.messages[0].gameStateMessage
            assertSoftly {
                gs1.hasGameInfo().shouldBeTrue()
                gs1.gameInfo.matchState shouldBe Messages.MatchState.GameComplete
                gs1.gameInfo.stage shouldBe Messages.GameStage.GameOver
                gs1.gameInfo.resultsCount shouldBe 1
                gs1.teamsCount shouldBeGreaterThan 0
                gs1.annotationsCount shouldBeGreaterThan 0
            }

            val gs2 = result.messages[1].gameStateMessage
            gs2.gameInfo.matchState shouldBe Messages.MatchState.MatchComplete
            gs2.gameInfo.resultsCount shouldBe 2

            val gs3 = result.messages[2].gameStateMessage
            gs3.pendingMessageCount shouldBe 1
            gs3.hasGameInfo().shouldBeFalse()

            val intermission = result.messages[3]
            assertSoftly {
                intermission.type shouldBe GREMessageType.IntermissionReq_695e
                intermission.hasIntermissionReq().shouldBeTrue()
                intermission.intermissionReq.optionsCount shouldBe 2
                intermission.intermissionReq.intermissionPrompt.promptId shouldBe PromptIds.MATCH_RESULT_WIN_LOSS
            }
        }

        test("gameOverBundle gsIds are strictly ascending") {
            val counter = LogicalSequencePlanner(initialGsId = 10, initialMsgId = 0)
            val result =
                pureBB().gameOverBundle(
                    winningTeam = 1,
                    counter = counter,
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

        test("gameOverBundle prevGameStateId chains correctly") {
            val counter = LogicalSequencePlanner(initialGsId = 10, initialMsgId = 0)
            val result =
                pureBB().gameOverBundle(
                    winningTeam = 2,
                    counter = counter,
                )

            val gsms = result.messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            assertSoftly {
                gsms.size shouldBe 3
                gsms[0].prevGameStateId shouldBe 10
                gsms[1].prevGameStateId shouldBe gsms[0].gameStateId
                gsms[2].prevGameStateId shouldBe gsms[1].gameStateId
            }
        }

        test("gameOverBundle with Concede reason") {
            val counter = LogicalSequencePlanner(initialGsId = 10, initialMsgId = 0)
            val result =
                pureBB().gameOverBundle(
                    winningTeam = 1,
                    counter = counter,
                    reason = Messages.ResultReason.Concede,
                    losingPlayerSeatId = 2,
                    lossReason = AnnotationLossReason.Concede,
                )

            val gs1 = result.messages[0].gameStateMessage
            val gameResult = gs1.gameInfo.resultsList.first()
            gameResult.reason shouldBe Messages.ResultReason.Concede
        }

        // --- Conformance tests (board-based) ---

        test("declareAttackersBundle shape") {
            val (b, game, counter) = startWithBoard { _, _, _ -> }

            val result = BundleBuilderTestSupport.declareAttackers(bundleBuilder(b), b, game, counter)

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.DeclareAttackersReq_695e
                result.messages[1].prompt.promptId shouldBe 6
            }
        }

        test("declareBlockersBundle shape") {
            val (b, game, counter) = startWithBoard { _, _, _ -> }

            val result = BundleBuilderTestSupport.declareBlockers(bundleBuilder(b), b, game, counter)

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.DeclareBlockersReq_695e
                result.messages[1].prompt.promptId shouldBe 7
            }
        }

        test("prepared targeting window shape") {
            val (b, game, counter) = startWithBoard { _, _, _ -> }
            val builder = bundleBuilder(b)
            val result =
                builder
                    .prepareTargetingWindow(
                        game,
                        counter,
                        targetingWindow(source = null),
                        routes = listOf(BundleBuilder.ViewerRoute(ProjectionViewer(SeatId(1), ProjectionViewerRole.Player), builder)),
                    ).player
                    .bundle

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.SelectTargetsReq_695e
                result.messages[1].prompt.promptId shouldBe PromptIds.SELECT_TARGETS
                result.messages[1].allowCancel shouldBe Messages.AllowCancel.Abort
                result.messages[1].allowUndo.shouldBeTrue()
            }
        }

        test("select-target rider is finalized with the paired state frame") {
            val (b, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val source =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val builder = bundleBuilder(b)
            val preparedCut =
                builder.prepareTargetingWindow(
                    game,
                    counter,
                    targetingWindow(source = ForgeCardId(source.id)),
                    routes = listOf(BundleBuilder.ViewerRoute(ProjectionViewer(SeatId(1), ProjectionViewerRole.Player), builder)),
                )
            b.commitProjection(preparedCut.transition)
            val prepared = preparedCut.player
            val result = prepared.bundle
            val gsm = result.messages.first().gameStateMessage
            val rider = gsm.annotationsList.single { AnnotationType.PlayerSelectingTargets in it.typeList }

            assertSoftly {
                rider.affectedIdsList shouldBe listOf(b.instanceId(source))
                gsm.annotationsList.map { it.id } shouldBe gsm.annotationsList.indices.map { gsm.annotationsList.first().id + it }
                b.projectionStateSnapshot().persistentAnnotations.nextAnnotationId shouldBe gsm.annotationsList.last().id + 1
            }
        }

        test("submitted-target rider leads the finalized frame with ascending ids") {
            val (b, game, counter) = startWithBoard { _, _, _ -> }
            val builder = bundleBuilder(b)
            val staged = builder.prepareTargetingSubmit(counter, b.projectionStateSnapshot(), 777.iid, SeatId(1))
            b.commitProjection(checkNotNull(staged.transition))

            val result = BundleBuilderTestSupport.stateOnly(builder, b, game, counter)
            val gsm = result.messages.first().gameStateMessage

            assertSoftly {
                gsm.annotationsList.first().typeList shouldBe listOf(AnnotationType.PlayerSubmittedTargets)
                gsm.annotationsList.first().affectedIdsList shouldBe listOf(777)
                gsm.annotationsList.map { it.id } shouldBe gsm.annotationsList.indices.map { gsm.annotationsList.first().id + it }
                builder.pendingSubmittedTargets() shouldBe null
                b.projectionStateSnapshot().persistentAnnotations.nextAnnotationId shouldBe gsm.annotationsList.last().id + 1
            }
        }

        test("new-turn rider is numbered with the playback frame") {
            val (b, game, counter) = startWithBoard { _, _, _ -> }
            val builder = bundleBuilder(b)

            val cut = builder.materializePlaybackCut(game, counter, turnStarted = true, events = FrameEventLog.EMPTY)
            val prepared = builder.compilePlaybackCut(cut)
            b.commitProjection(prepared.transition)
            val gsm =
                prepared.batches
                    .first()
                    .first { it.hasGameStateMessage() }
                    .gameStateMessage
            val rider = gsm.annotationsList.single { AnnotationType.NewTurnStarted in it.typeList }

            assertSoftly {
                rider.affectedIdsList shouldBe listOf(1)
                rider.affectorId shouldBeGreaterThan 0
                gsm.annotationsList.map { it.id } shouldBe gsm.annotationsList.indices.map { gsm.annotationsList.first().id + it }
                b.projectionStateSnapshot().persistentAnnotations.nextAnnotationId shouldBe gsm.annotationsList.last().id + 1
            }
        }

        test("playback display actions do not advertise a pending decision") {
            val (b, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                }
            val builder = bundleBuilder(b)
            val cut = builder.materializePlaybackCut(game, counter, turnStarted = false, events = FrameEventLog.EMPTY)
            val playback = builder.compilePlaybackCut(cut)
            b.commitProjection(playback.transition)

            val playbackBatch = playback.batches.single()
            val playbackGsm = playbackBatch.first().gameStateMessage
            assertSoftly {
                playbackGsm.update shouldBe GameStateUpdate.SendHiFi
                playbackGsm.actionsCount shouldBeGreaterThan 0
                playbackGsm.pendingMessageCount shouldBe 0
                playbackBatch.count { it.hasActionsAvailableReq() } shouldBe playbackGsm.pendingMessageCount
            }

            val priority = BundleBuilderTestSupport.postAction(builder, b, game, counter)
            val priorityGsm = priority.messages.first().gameStateMessage
            val request = priority.messages.single { it.hasActionsAvailableReq() }
            assertSoftly {
                priorityGsm.update shouldBe GameStateUpdate.SendAndRecord
                priorityGsm.pendingMessageCount shouldBe 1
                priority.messages.count { it.hasActionsAvailableReq() } shouldBe priorityGsm.pendingMessageCount
                request.gameStateId shouldBe priorityGsm.gameStateId
            }
        }

        test("playback cut is exact across retry and discard") {
            val (b, game, counter) = startWithBoard { _, _, _ -> }
            val builder = bundleBuilder(b)
            val prior = b.projectionStateSnapshot()
            val events =
                FrameEventLog(
                    listOf(
                        GameEvent.CoinFlipped(1.sid, ForgeCardId(100), 200, 19490, 1),
                        GameEvent.CoinFlipped(1.sid, ForgeCardId(100), 200, 19490, 0),
                    ),
                )
            val cut = builder.materializePlaybackCut(game, counter, turnStarted = true, events)
            val afterMaterialize = b.projectionStateSnapshot()
            val counterAfterMaterialize = counter.snapshot()

            val first = builder.compilePlaybackCut(cut)
            val retry = builder.compilePlaybackCut(cut)
            val firstMessages = first.batches.single()
            val retryMessages = retry.batches.single()

            assertSoftly {
                retryMessages.map { it.toByteArray().toList() } shouldBe firstMessages.map { it.toByteArray().toList() }
                retry.transition shouldBe first.transition
                firstMessages.map { it.type } shouldBe
                    listOf(
                        GREMessageType.GameStateMessage_695e,
                        GREMessageType.PromptReq,
                        GREMessageType.PromptReq,
                        GREMessageType.GameStateMessage_695e,
                    )
                firstMessages.map { it.msgId } shouldBe firstMessages.map { it.msgId }.sorted()
                firstMessages
                    .map { it.msgId }
                    .toSet()
                    .size shouldBe firstMessages.size
                cut.priorProjection.revision shouldBe prior.revision
                b.projectionStateSnapshot() shouldBe afterMaterialize
                counter.snapshot() shouldBe counterAfterMaterialize
            }
        }

        test("multi-frame playback cut folds effective baselines and installs once") {
            val (b, game, counter) = startWithBoard { _, _, _ -> }
            val builder = bundleBuilder(b)
            val prior = b.projectionStateSnapshot()
            val specs =
                listOf(
                    BundleBuilder.PlaybackFrameSpec(
                        FrameEventLog(
                            listOf(
                                GameEvent.PhaseChanged(
                                    1.sid,
                                    Messages.Phase.Combat_a549.number,
                                    Messages.Step.FirstStrikeDamage_a2cb.number,
                                ),
                            ),
                        ),
                    ),
                    BundleBuilder.PlaybackFrameSpec(
                        FrameEventLog(
                            listOf(
                                GameEvent.PhaseChanged(
                                    1.sid,
                                    Messages.Phase.Combat_a549.number,
                                    Messages.Step.CombatDamage_a2cb.number,
                                ),
                            ),
                        ),
                    ),
                )
            val cut = builder.materializePlaybackCut(game, counter, specs)
            val afterMaterialize = b.projectionStateSnapshot()

            val first = builder.compilePlaybackCut(cut)
            val retry = builder.compilePlaybackCut(cut)
            val gsms = first.batches.map { it.first().gameStateMessage }

            assertSoftly {
                first.batches.map { batch -> batch.map { it.toByteArray().toList() } } shouldBe
                    retry.batches.map { batch -> batch.map { it.toByteArray().toList() } }
                first.transition shouldBe retry.transition
                first.batches shouldHaveSize 2
                gsms[1].prevGameStateId shouldBe gsms[0].gameStateId
                first.transition.expectedRevision shouldBe prior.revision
                first.transition.nextState.revision shouldBe prior.revision + 1
                b.projectionStateSnapshot() shouldBe afterMaterialize
            }
        }

        test("failure in later playback frame retains the exact whole cut") {
            val (b, game, counter) = startWithBoard { _, _, _ -> }
            val builder = bundleBuilder(b)
            val cut =
                builder.materializePlaybackCut(
                    game,
                    counter,
                    listOf(
                        BundleBuilder.PlaybackFrameSpec(FrameEventLog.EMPTY),
                        BundleBuilder.PlaybackFrameSpec(FrameEventLog.EMPTY),
                    ),
                )
            val committed = b.projectionStateSnapshot()
            var compileCount = 0
            b.diffListener = { _, _ ->
                compileCount++
                if (compileCount == 2) error("second playback frame failed")
            }

            val failure = shouldThrow<IllegalStateException> { builder.compilePlaybackCut(cut) }
            b.diffListener = null
            val retry = builder.compilePlaybackCut(cut)

            assertSoftly {
                failure.message shouldBe "second playback frame failed"
                compileCount shouldBe 2
                cut.frames shouldHaveSize 2
                retry.batches shouldHaveSize 2
                b.projectionStateSnapshot() shouldBe committed
            }
        }

        test("stale exact playback cut becomes terminal and emits nothing") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            b.cutCoordinator.registerViewer(SeatId(1))
            val playback = GamePlayback(b, 1)
            var writerRan = false
            b.diffListener = { _, _ ->
                if (!writerRan) {
                    writerRan = true
                    b.getOrAllocInstanceId(ForgeCardId(9_999_999))
                }
            }
            playback.visit(
                forge.game.event.GameEventPlayerPoisoned(
                    null as forge.game.player.PlayerView?,
                    null as forge.game.player.PlayerView?,
                    0,
                    1,
                ),
            )

            val thrown = shouldThrow<PlaybackTerminalFailure> { playback.onMainLoopStepCompleted() }

            assertSoftly {
                writerRan shouldBe true
                thrown.pendingCut shouldBe playback.failure()?.pendingCut
                b.projectionStateSnapshot().revision shouldBeGreaterThan
                    checkNotNull(thrown.pendingCut).projection.priorProjection.revision
                playback.drainQueue() shouldBe emptyList()
                shouldThrow<PlaybackTerminalFailure> {
                    playback.onMainLoopStepCompleted()
                } shouldBe thrown
            }
            b.diffListener = null
        }

        test("combat safe point subsumes ordinary request after reversed subscriber order") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            b.cutCoordinator.registerViewer(SeatId(1))
            val playback = GamePlayback(b, 1)
            val collector = checkNotNull(b.eventCollector)
            game.unsubscribeFromEvents(collector)
            game.subscribeToEvents(playback)
            game.subscribeToEvents(collector)
            playback.visit(
                forge.game.event.GameEventPlayerPoisoned(
                    null as forge.game.player.PlayerView?,
                    null as forge.game.player.PlayerView?,
                    0,
                    1,
                ),
            )
            game.fireEvent(forge.game.event.GameEventCardTapped(card, true))

            game.fireEvent(forge.game.event.GameEventCombatEnded(emptyList(), emptyList()))
            playback.hasPendingMessages() shouldBe false
            playback.onCombatEndedCompleted()
            val first = playback.drainQueue()
            playback.onMainLoopStepCompleted()

            assertSoftly {
                first.size shouldBe 1
                first
                    .flatten()
                    .flatMap { it.gameStateMessage.annotationsList }
                    .count { AnnotationType.TappedUntappedPermanent in it.typeList } shouldBe 1
                playback.drainQueue() shouldBe emptyList()
            }
        }

        test("startup boundary discards setup facts and retains first gameplay facts") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            b.cutCoordinator.registerViewer(SeatId(1))
            val playback = GamePlayback(b, 1)
            game.fireEvent(forge.game.event.GameEventCardTapped(card, true))

            playback.onMainGameLoopStarted()
            game.fireEvent(forge.game.event.GameEventCardTapped(card, false))
            playback.visit(
                forge.game.event.GameEventPlayerPoisoned(
                    null as forge.game.player.PlayerView?,
                    null as forge.game.player.PlayerView?,
                    0,
                    1,
                ),
            )
            playback.onMainLoopStepCompleted()

            val tapAnnotations =
                playback
                    .drainQueue()
                    .flatten()
                    .flatMap { it.gameStateMessage.annotationsList }
                    .filter { AnnotationType.TappedUntappedPermanent in it.typeList }
            tapAnnotations.size shouldBe 1
            tapAnnotations
                .single()
                .detailsList
                .single { it.key == "tapped" }
                .valueInt32List shouldBe listOf(0)
        }

        test("playback cut runs a SpellCast frame through the annotation pipeline") {
            // Frame-path coverage for the SpellCast annotation fact: the
            // PlaybackFrameSpec event log rides the playback cut through the
            // annotation pipeline. The transition is committed so the
            // journal-side consumption lands, matching production's
            // flushPlaybackCut.
            val (b, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            card.addPTBoost(1, 1, 123L, 456L)
            b.promptBridge(SeatId(1)).journal.record(
                PromptSideEffect.ChoiceResult(
                    sourceForgeCardId = ForgeCardId(card.id),
                    chooserSeatId = SeatId(1),
                    choiceValue = 1,
                ),
            )
            val events =
                FrameEventLog(
                    listOf(
                        GameEvent.SpellCast(
                            cardId = ForgeCardId(card.id),
                            seatId = SeatId(1),
                            spellGrpId = 100,
                        ),
                    ),
                )
            val builder = bundleBuilder(b)
            val cut = builder.materializePlaybackCut(game, counter, turnStarted = false, events)
            val prepared = builder.compilePlaybackCut(cut)
            b.commitProjection(prepared.transition)

            val gsm =
                prepared.batches
                    .first()
                    .first { it.hasGameStateMessage() }
                    .gameStateMessage
            assertSoftly {
                gsm.annotationsList.any { AnnotationType.LayeredEffectCreated in it.typeList } shouldBe true
                b.promptBridge(SeatId(1)).journal.snapshotChoiceResults() shouldBe emptyList()
                b
                    .annotationProjectionStateSnapshot()
                    .pendingSpellCasts
                    .find(ForgeCardId(card.id), 100)
                    ?.spellGrpId shouldBe 100
            }
        }

        test("Earthbend commits its enriched snapshot and does not re-emit an unchanged target") {
            val (b, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val target =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            target.addNewPT(0, 0, 123L, 0L)
            target.addChangedCardTypes(
                CardType(listOf("Creature"), true),
                null,
                false,
                EnumSet.noneOf(RemoveType::class.java),
                123L,
                0L,
                true,
                false,
            )
            target.addChangedCardKeywords(listOf("Haste"), null, false, 123L, null)
            b.recordEarthbendResolution(
                sourceCardId = ForgeCardId(target.id),
                sourceAbilityGrpId = 42,
                abilityForgeId = 0,
                targetCardIds = listOf(ForgeCardId(target.id)),
            )
            val builder = bundleBuilder(b)

            val first =
                BundleBuilderTestSupport
                    .stateOnly(builder, b, game, counter)
                    .messages
                    .first()
                    .gameStateMessage
            val second =
                BundleBuilderTestSupport
                    .stateOnly(builder, b, game, counter)
                    .messages
                    .first()
                    .gameStateMessage

            assertSoftly {
                first.gameObjectsList.map { it.instanceId } shouldBe listOf(b.instanceId(target.id))
                first.persistentAnnotationsList.count {
                    AnnotationType.AddAbility_af5a in it.typeList && AnnotationType.LayeredEffect in it.typeList
                } shouldBe 1
                second.gameObjectsList shouldBe emptyList()
            }
        }

        test("same-value replacement submitted-target rider aborts before bridge mutations commit") {
            val (b, game, counter) = startWithBoard { _, _, _ -> }
            val builder = bundleBuilder(b)
            val initial = builder.prepareTargetingSubmit(counter, b.projectionStateSnapshot(), 777.iid, SeatId(1))
            b.commitProjection(checkNotNull(initial.transition))
            val expected = checkNotNull(builder.pendingSubmittedTargets())
            b.seedDiffBaseline(game, counter.currentGsId())
            val snap = checkNotNull(builder.previousProjectionSnapshot())
            val prior = b.projectionStateSnapshot()
            val startId = b.projectionStateSnapshot().persistentAnnotations.nextAnnotationId
            val replacementCut = builder.prepareTargetingSubmit(counter, b.projectionStateSnapshot(), 777.iid, SeatId(1))
            b.commitProjection(checkNotNull(replacementCut.transition))
            val replacement = checkNotNull(builder.pendingSubmittedTargets())
            val compiled =
                StateProjectionCompiler.compileOneViewer(
                    b.stateProjectionEnvironment,
                    StateFrameInput(
                        gameStateId = counter.nextGsId(),
                        snapshot = snap,
                        previousSnapshot = snap,
                        events = FrameEventLog.EMPTY,
                        promptFacts = PromptProjectionFacts(),
                        updateType = GameStateUpdate.SendAndRecord,
                        viewingSeatId = 1,
                        revealForSeat = null,
                        effectFacts = b.materializeEffectProjectionFacts(),
                        mechanicSourceFacts = MechanicSourceFacts(),
                        abilityExhaustionFacts = AbilityExhaustionFacts(),
                        persistentFeedFacts = PersistentFeedFacts(),
                    ),
                    prior,
                    ViewerProjectionIntent.of(
                        listOf(
                            ProjectionSupplement.SubmitPendingTargets(
                                expected.spellInstanceId,
                                expected.casterSeatId,
                                expected.version,
                            ),
                        ),
                    ),
                )
            shouldThrow<StaleProjectionTransitionException> {
                b.commitProjection(compiled.transition)
            }

            assertSoftly {
                builder.pendingSubmittedTargets() shouldBe replacement
                b.projectionStateSnapshot().persistentAnnotations.nextAnnotationId shouldBe startId
            }
        }

        test("echoAttackersBundle conformance — SendAndRecord, no combat state, actions present") {
            val (b, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Llanowar Elves", human, ZoneType.Battlefield)
                    addCard("Elvish Mystic", human, ZoneType.Battlefield)
                }

            val creatures =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isCreature }
            val allIds = creatures.map(b::instanceId)
            val selectedIds = listOf(allIds.first())

            val prepared =
                bundleBuilder(b).prepareEchoAttackers(
                    game,
                    counter,
                    selectedIds,
                    allIds,
                    presentationActions = Messages.ActionsAvailableReq.getDefaultInstance(),
                )
            b.commitProjection(checkNotNull(prepared.transition))
            val result = prepared.bundle

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.DeclareAttackersReq_695e
            }

            val gsm = result.messages[0].gameStateMessage
            assertSoftly {
                gsm.type shouldBe GameStateType.Diff
                gsm.gameObjectsCount shouldBeGreaterThan 0
                gsm.update shouldBe Messages.GameStateUpdate.SendAndRecord
                gsm.pendingMessageCount shouldBe 0
            }

            val selected = gsm.gameObjectsList.first { it.instanceId == selectedIds.first() }
            selected.attackState shouldBe Messages.AttackState.None_a3a9
            selected.blockState shouldBe Messages.BlockState.None_aa2d

            for (obj in gsm.gameObjectsList.filter { it.instanceId != selectedIds.first() }) {
                obj.attackState shouldBe Messages.AttackState.None_a3a9
                obj.blockState shouldBe Messages.BlockState.None_aa2d
            }

            // Conformance note: actions array is a cumulative turn log. In the naive
            // board-only setup here it's expected to be empty, so no assertion is needed.
        }

        test("echoBlockersBundle conformance — SendAndRecord, no combat state, actions present") {
            val (b, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Llanowar Elves", human, ZoneType.Battlefield)
                }

            val blocker =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val blockerId = b.instanceId(blocker)
            val blockAssignments = mapOf(blockerId to 999)

            val prepared =
                bundleBuilder(b).prepareEchoBlockers(
                    game,
                    counter,
                    blockAssignments,
                    presentationActions = Messages.ActionsAvailableReq.getDefaultInstance(),
                )
            b.commitProjection(checkNotNull(prepared.transition))
            val result = prepared.bundle

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.DeclareBlockersReq_695e
            }

            val gsm = result.messages[0].gameStateMessage
            assertSoftly {
                gsm.type shouldBe GameStateType.Diff
                gsm.gameObjectsCount shouldBeGreaterThan 0
                gsm.update shouldBe Messages.GameStateUpdate.SendAndRecord
                gsm.pendingMessageCount shouldBe 0
            }

            // Conformance: no blockState on echo objects
            for (obj in gsm.gameObjectsList) {
                obj.blockState shouldBe Messages.BlockState.None_aa2d
                obj.attackState shouldBe Messages.AttackState.None_a3a9
            }
        }

        // --- isTurnOrTriggerDraw unit tests ---
        //
        // postAction overrides the default `SendAndRecord` to `SendHiFi` when the
        // drained event stream describes a turn-boundary or trigger-driven draw
        // for the active seat. These cases pin the helper contract directly so
        // regressions surface without needing a full game state.

        fun drawSnap(
            drawnCard: ForgeCardId,
            activeSeat: SeatId,
            phase: PhaseType? = PhaseType.MAIN1,
        ): GsmSnapshot =
            GsmSnapshot.forTest(
                objects =
                    mapOf(
                        drawnCard to
                            CardSnapshot(
                                forgeCardId = drawnCard,
                                name = "Grizzly Bears",
                                grpId = 1,
                                owner = activeSeat,
                                controller = activeSeat,
                            ),
                    ),
                phase =
                    PhaseSnapshot(
                        turn = 2,
                        activePlayer = activeSeat,
                        priorityPlayer = activeSeat,
                        phase = phase,
                    ),
            )

        test("isTurnOrTriggerDraw: Case A — turn-boundary draw returns true") {
            val card = ForgeCardId(42)
            val seat = SeatId(1)
            val events = listOf(GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand))

            BundleBuilder.isTurnOrTriggerDraw(events, drawSnap(card, seat), seat) shouldBe true
        }

        test("isTurnOrTriggerDraw: Case B — spell-driven draw returns false") {
            val card = ForgeCardId(42)
            val seat = SeatId(1)
            val events =
                listOf(
                    GameEvent.SpellCast(ForgeCardId(99), seat),
                    GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand),
                    GameEvent.SpellResolved(ForgeCardId(99), hasFizzled = false),
                )

            BundleBuilder.isTurnOrTriggerDraw(events, drawSnap(card, seat), seat) shouldBe false
        }

        test("isTurnOrTriggerDraw: Case C — no draw event returns false") {
            val seat = SeatId(1)
            BundleBuilder.isTurnOrTriggerDraw(emptyList(), drawSnap(ForgeCardId(0), seat), seat) shouldBe false
        }

        test("isTurnOrTriggerDraw: UPKEEP phase (trigger-driven draw) returns true") {
            val card = ForgeCardId(42)
            val seat = SeatId(1)
            val events = listOf(GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand))

            BundleBuilder.isTurnOrTriggerDraw(events, drawSnap(card, seat, PhaseType.UPKEEP), seat) shouldBe true
        }

        test("isTurnOrTriggerDraw: phase=null falls back to default (false)") {
            val card = ForgeCardId(42)
            val seat = SeatId(1)
            val events = listOf(GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand))

            BundleBuilder.isTurnOrTriggerDraw(events, drawSnap(card, seat, phase = null), seat) shouldBe false
        }

        test("isTurnOrTriggerDraw: draw by non-active seat returns false") {
            val card = ForgeCardId(42)
            val activeSeat = SeatId(1)
            val opponent = SeatId(2)
            // Card owned by opponent, not active seat
            val snap =
                GsmSnapshot.forTest(
                    objects =
                        mapOf(
                            card to
                                CardSnapshot(
                                    forgeCardId = card,
                                    name = "Grizzly Bears",
                                    grpId = 1,
                                    owner = opponent,
                                    controller = opponent,
                                ),
                        ),
                    phase =
                        PhaseSnapshot(
                            turn = 2,
                            activePlayer = activeSeat,
                            priorityPlayer = activeSeat,
                            phase = PhaseType.MAIN1,
                        ),
                )
            val events = listOf(GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand))

            BundleBuilder.isTurnOrTriggerDraw(events, snap, activeSeat) shouldBe false
        }

        test("isTurnOrTriggerDraw: COMBAT phase (out of window) returns false") {
            val card = ForgeCardId(42)
            val seat = SeatId(1)
            val events = listOf(GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand))

            BundleBuilder.isTurnOrTriggerDraw(events, drawSnap(card, seat, PhaseType.COMBAT_BEGIN), seat) shouldBe false
        }

        test("postAction emits SendAndRecord when there are no draw events (baseline preserved)") {
            val (b, game, counter) =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                }

            val result = BundleBuilderTestSupport.postAction(bundleBuilder(b), b, game, counter)
            val gsm = result.messages.first { it.hasGameStateMessage() }.gameStateMessage

            // startWithBoard parks us at MAIN1 with activePlayer=humanPlayer=seat 1.
            // No Library→Hand events, so the override must not fire and the default
            // SendAndRecord (acting == viewing) stands. Guards against the override
            // swallowing non-draw postAction bundles.
            gsm.update shouldBe Messages.GameStateUpdate.SendAndRecord
        }
    })
