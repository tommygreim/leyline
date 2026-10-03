package leyline.board.handshake

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.bundle.GsmBuilder
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.gsm
import wotc.mtgo.gre.external.messaging.Messages.*
import leyline.game.bundle.LifecycleMessageMaterializer as HandshakeMessages

/**
 * Structural tests for pre-mulligan handshake messages produced by [HandshakeMessages].
 *
 * Verifies message counts, ordering, GRE types, gsId/msgId advancement,
 * pendingMessageCount, and seat-specific bundle composition.
 *
 * Focuses on bundle shape and value correctness.
 */
class DealHandConformanceTest :
    BoardTest({

        /** Helper: extract GRE messages from a MatchServiceToClientMessage. */
        fun greMessages(msg: MatchServiceToClientMessage): List<GREToClientMessage> = msg.greToClientEvent.greToClientMessagesList

        // --- dealHand ---

        test("dealHand seat 1: 1 GRE msg, Diff GSM with zones and objects") {
            val (b, _, _) =
                startWithBoard { _, human, _ ->
                    repeat(7) { addCard("Plains", human, ZoneType.Hand) }
                    repeat(53) { addCard("Plains", human, ZoneType.Library) }
                }
            val (messages, nextMsgId) = HandshakeMessages.dealHand(6, 2, b, seatId = SeatId(1))

            messages.size shouldBe 1
            nextMsgId shouldBe 7

            val gre = messages[0]
            gre.type shouldBe GREMessageType.GameStateMessage_695e
            gre.msgId shouldBe 6

            val gsm = gre.gameStateMessage
            assertSoftly {
                gre.type shouldBe GREMessageType.GameStateMessage_695e
                gre.msgId shouldBe 6
                gsm.type shouldBe GameStateType.Diff
                gsm.update shouldBe GameStateUpdate.SendAndRecord
                gsm.gameStateId shouldBe 2
                gsm.prevGameStateId shouldBe 1
                gsm.zonesCount shouldBe 4
                gsm.gameObjectsCount shouldBeGreaterThan 0
            }

            gsm.playersCount shouldBe 2
            for (player in gsm.playersList) {
                player.pendingMessageType shouldBe ClientMessageType.MulliganResp_097b
            }
        }

        // --- dealHandMulliganSeat2 ---

        test("dealHandMulliganSeat2: 2 msgs (GSM + MulliganReq)") {
            val (b, _, _) =
                startWithBoard { _, human, ai ->
                    repeat(7) { addCard("Plains", human, ZoneType.Hand) }
                    repeat(53) { addCard("Plains", human, ZoneType.Library) }
                    repeat(7) { addCard("Plains", ai, ZoneType.Hand) }
                    repeat(53) { addCard("Plains", ai, ZoneType.Library) }
                }
            val (messages, nextMsgId) = HandshakeMessages.dealHandMulliganSeat2(6, 2, b)

            messages.size shouldBe 2
            nextMsgId shouldBe 8

            val gsm = messages[0]
            assertSoftly {
                gsm.type shouldBe GREMessageType.GameStateMessage_695e
                gsm.gameStateMessage.type shouldBe GameStateType.Diff
                gsm.gameStateMessage.update shouldBe GameStateUpdate.SendAndRecord
                gsm.gameStateMessage.zonesCount shouldBe 4
                gsm.gameStateMessage.gameObjectsCount shouldBeGreaterThan 0
                gsm.gameStateMessage.pendingMessageCount shouldBe 1
            }

            val mull = messages[1]
            assertSoftly {
                mull.type shouldBe GREMessageType.MulliganReq_aa0d
                mull.hasPrompt().shouldBeTrue()
                mull.prompt.promptId shouldBe PromptIds.MULLIGAN
            }
        }

        // --- mulliganReqSeat1 ---

        test("mulliganReqSeat1: 3 msgs (thin Diff + PromptReq + MulliganReq)") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val (messages, nextMsgId) = HandshakeMessages.mulliganReqSeat1(10, 3, b)

            messages.size shouldBe 3
            nextMsgId shouldBe 13

            val gsm = messages[0].gameStateMessage
            assertSoftly {
                messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                gsm.type shouldBe GameStateType.Diff
                gsm.update shouldBe GameStateUpdate.SendAndRecord
                gsm.zonesCount shouldBe 0
                gsm.gameObjectsCount shouldBe 0
                gsm.turnInfo.decisionPlayer shouldBe 1
                gsm.pendingMessageCount shouldBe 2
                gsm.prevGameStateId shouldBe 2
            }

            val prompt = messages[1]
            assertSoftly {
                prompt.type shouldBe GREMessageType.PromptReq
                prompt.hasPrompt().shouldBeTrue()
                prompt.prompt.promptId shouldBe PromptIds.STARTING_PLAYER
            }

            val mull = messages[2]
            assertSoftly {
                mull.type shouldBe GREMessageType.MulliganReq_aa0d
                mull.hasPrompt().shouldBeTrue()
                mull.prompt.promptId shouldBe PromptIds.MULLIGAN
            }
        }

        // --- initialBundle ---

        test("initialBundle seat 1: ConnectResp + DieRoll + Full GSM (3 msgs)") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(SeatId(1)))
            val (messages, nextMsgId) = HandshakeMessages.initialBundle(SeatId(1), Board.TEST_MATCH_ID, 2, 1, deck, b)

            assertSoftly {
                messages.size shouldBe 3
                nextMsgId shouldBe 5
                messages[0].type shouldBe GREMessageType.ConnectResp_695e
                messages[1].type shouldBe GREMessageType.DieRollResultsResp_695e
                messages[2].type shouldBe GREMessageType.GameStateMessage_695e
            }

            val gsm = messages[2].gameStateMessage
            assertSoftly {
                gsm.type shouldBe GameStateType.Full
                gsm.zonesCount shouldBe 17
                gsm.zonesList.any { it.zoneId == 12 } shouldBe false
                gsm.teamsCount shouldBe 2
                gsm.playersCount shouldBe 2
                gsm.gameInfo.stage shouldBe GameStage.Start_a920
            }
        }

        test("initialBundle seat 2: DieRoll + Full GSM + ChooseStartingPlayerReq") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val deck = GsmBuilder.buildDeckMessage(b.getDeckGrpIds(SeatId(2)))
            val (messages, nextMsgId) = HandshakeMessages.initialBundle(SeatId(2), Board.TEST_MATCH_ID, 3, 1, deck, b)

            assertSoftly {
                messages.size shouldBe 3
                nextMsgId shouldBe 6
                messages[0].type shouldBe GREMessageType.DieRollResultsResp_695e
                messages[1].type shouldBe GREMessageType.GameStateMessage_695e
                messages[2].type shouldBe GREMessageType.ChooseStartingPlayerReq_695e
            }

            val gsm = messages[1].gameStateMessage
            assertSoftly {
                gsm.type shouldBe GameStateType.Full
                gsm.zonesCount shouldBe 17
                gsm.zonesList.any { it.zoneId == 12 } shouldBe false
                gsm.pendingMessageCount shouldBe 1
            }

            val req = messages[2].chooseStartingPlayerReq
            req.systemSeatIdsCount shouldBe 2
        }
    })
