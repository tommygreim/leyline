package leyline.protocol

import wotc.mtgo.gre.external.messaging.Messages.*

/** Head-owned room, completion, and settings transport messages. */
object HandshakeMessages {
    data class RoomPlayer(
        val playerId: String,
        val displayName: String,
        val seatId: Int,
    )

    /** Both connections receive the same authenticated human roster. */
    fun roomState(
        matchId: String,
        eventId: String,
        players: List<RoomPlayer>,
    ): MatchServiceToClientMessage {
        require(players.map { it.seatId }.toSet() == setOf(1, 2) && players.size == 2)
        val config = humanRoomConfig(matchId, eventId, players)
        val roster = config.reservedPlayersList
        return wrapRoomState(
            MatchGameRoomInfo
                .newBuilder()
                .setGameRoomConfig(config)
                .setStateType(MatchGameRoomStateType.Playing)
                .addAllPlayers(roster),
        )
    }

    fun roomState(
        matchId: String,
        playerId: String,
        opponentName: String = "AI Opponent",
        eventId: String = "AIBotMatch",
        isBot: Boolean = true,
    ): MatchServiceToClientMessage {
        val roomInfo =
            MatchGameRoomInfo
                .newBuilder()
                .setGameRoomConfig(buildRoomConfig(matchId, playerId, opponentName, eventId, isBot))
                .setStateType(MatchGameRoomStateType.Playing)
                .addPlayers(playerInfo(playerId, "Player", 1, 1))
                .addPlayers(playerInfo("${playerId}_Familiar", opponentName, 2, 2))
        return wrapRoomState(roomInfo)
    }

    fun matchCompleted(
        matchId: String,
        winningTeam: Int,
        playerId: String,
        resultType: ResultType = ResultType.WinLoss,
        reason: ResultReason = ResultReason.Concede,
        players: List<RoomPlayer> = emptyList(),
        eventId: String = "AIBotMatch",
    ): MatchServiceToClientMessage {
        val result =
            FinalMatchResult
                .newBuilder()
                .setMatchId(matchId)
                .setMatchCompletedReason(MatchCompletedReasonType.Success_a26d)
                .addResultList(resultSpec(MatchScope.Game_a146, resultType, winningTeam, reason))
                .addResultList(resultSpec(MatchScope.Match, resultType, winningTeam, reason))
        val roomInfo =
            MatchGameRoomInfo
                .newBuilder()
                .setGameRoomConfig(
                    if (players.isEmpty()) buildRoomConfig(matchId, playerId) else humanRoomConfig(matchId, eventId, players),
                ).setStateType(MatchGameRoomStateType.MatchCompleted)
                .setFinalMatchResult(result)
        return wrapRoomState(roomInfo)
    }

    private fun humanRoomConfig(
        matchId: String,
        eventId: String,
        players: List<RoomPlayer>,
    ): MatchGameRoomConfig.Builder {
        val roster = players.map { playerInfo(it.playerId, it.displayName, it.seatId, it.seatId).setEventId(eventId).build() }
        return MatchGameRoomConfig
            .newBuilder()
            .setMatchId(matchId)
            .setEventId(eventId)
            .addAllReservedPlayers(roster)
    }

    private fun resultSpec(
        scope: MatchScope,
        resultType: ResultType,
        winningTeam: Int,
        reason: ResultReason,
    ) = ResultSpec
        .newBuilder()
        .setScope(scope)
        .setResult(resultType)
        .setWinningTeamId(winningTeam)
        .setReason(reason)

    private fun playerInfo(
        userId: String,
        name: String,
        seat: Int,
        team: Int,
    ) = MatchGameRoomPlayerInfo
        .newBuilder()
        .setUserId(userId)
        .setPlayerName(name)
        .setSystemSeatId(seat)
        .setTeamId(team)

    private fun buildRoomConfig(
        matchId: String,
        playerId: String,
        opponentName: String = "AI Opponent",
        eventId: String = "AIBotMatch",
        isBot: Boolean = true,
    ): MatchGameRoomConfig.Builder {
        val builder = MatchGameRoomConfig.newBuilder().setMatchId(matchId)
        if (isBot) builder.setEventId(eventId)
        val player =
            playerInfo(playerId, "Player", 1, 1)
                .setCourseId("Avatar_Basic_Adventurer")
                .setPlatformId("Mac")
                .setEventId(eventId)
        val opponent =
            playerInfo("${playerId}_Familiar", opponentName, 2, 2)
                .setCourseId("Avatar_Basic_Sparky")
                .setEventId(eventId)
        if (isBot) opponent.setIsBotPlayer(true)
        return builder.addReservedPlayers(player).addReservedPlayers(opponent)
    }

    private fun wrapRoomState(roomInfo: MatchGameRoomInfo.Builder): MatchServiceToClientMessage =
        MatchServiceToClientMessage
            .newBuilder()
            .setMatchGameRoomStateChangedEvent(MatchGameRoomStateChangedEvent.newBuilder().setGameRoomInfo(roomInfo))
            .build()
}
