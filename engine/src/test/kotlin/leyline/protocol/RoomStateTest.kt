package leyline.protocol

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.GameType
import wotc.mtgo.gre.external.messaging.Messages.MatchGameRoomStateType

class RoomStateTest :
    FunSpec({
        tags(UnitTag)
        val roster = listOf(HandshakeMessages.RoomPlayer("alice", "Alice", 1), HandshakeMessages.RoomPlayer("bob", "Bob", 2))

        test("human room uses both real profiles and neither is marked as a bot") {
            val room = HandshakeMessages.roomState("local-match", "Play", roster).matchGameRoomStateChangedEvent.gameRoomInfo
            assertSoftly {
                room.stateType shouldBe MatchGameRoomStateType.Playing
                room.gameRoomConfig.matchId shouldBe "local-match"
                room.gameRoomConfig.eventId shouldBe "Play"
                room.gameRoomConfig.matchConfig.gameType shouldBe GameType.Duel
                room.playersList.map { it.userId } shouldBe listOf("alice", "bob")
                room.playersList.map { it.playerName } shouldBe listOf("Alice", "Bob")
                room.gameRoomConfig.reservedPlayersList.map { it.systemSeatId } shouldBe listOf(1, 2)
                room.gameRoomConfig.reservedPlayersList.map { it.isBotPlayer } shouldBe listOf(false, false)
            }
        }

        test("completed room preserves the shared roster and seat two winner") {
            val room =
                HandshakeMessages
                    .matchCompleted("local-match", 2, "bob", players = roster, eventId = "Play")
                    .matchGameRoomStateChangedEvent.gameRoomInfo
            assertSoftly {
                room.stateType shouldBe MatchGameRoomStateType.MatchCompleted
                room.gameRoomConfig.reservedPlayersList.map { it.userId } shouldBe listOf("alice", "bob")
                room.gameRoomConfig.reservedPlayersList.map { it.playerName } shouldBe listOf("Alice", "Bob")
                room.finalMatchResult.resultListList.map { it.winningTeamId } shouldBe listOf(2, 2)
            }
        }
    })
