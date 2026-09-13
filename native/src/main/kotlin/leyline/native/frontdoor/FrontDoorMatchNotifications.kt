package leyline.native.frontdoor

import io.netty.channel.ChannelHandlerContext
import leyline.domain.MatchInfo
import leyline.native.frontdoor.wire.FdEnvelope
import leyline.native.frontdoor.wire.FdResponse
import leyline.native.frontdoor.wire.FdResponseWriter
import leyline.native.matchmaking.PairedSeat
import org.slf4j.LoggerFactory
import java.util.UUID

/** Per-recipient MatchCreated projection for direct bot games and two-human rooms. */
internal object FrontDoorMatchNotifications {
    fun sendPaired(
        ctx: ChannelHandlerContext,
        writer: FdResponseWriter,
        seat: PairedSeat,
    ) {
        val players =
            seat.players.map { player ->
                FdEnvelope.PlayerInfo(
                    seatId = player.seatId,
                    teamId = player.seatId,
                    name = player.displayName,
                    commanderGrpIds = player.commanderGrpIds,
                )
            }
        val body =
            FdEnvelope.buildMatchCreatedJson(
                seat.match.matchId,
                seat.match.host,
                seat.match.port,
                matchType = "Queue",
                yourSeat = seat.yourSeat,
                eventId = seat.match.eventName,
                playerInfos = players,
            )
        writer.send(ctx, UUID.randomUUID().toString(), FdResponse.Json(body))
    }

    fun sendBot(
        ctx: ChannelHandlerContext,
        writer: FdResponseWriter,
        match: MatchInfo,
        displayName: String,
        commanderGrpIds: List<Int>,
        yourSeat: Int,
    ) {
        val players =
            listOf(
                FdEnvelope.PlayerInfo(seatId = 1, teamId = 1, name = displayName, commanderGrpIds = commanderGrpIds),
                FdEnvelope.PlayerInfo(
                    seatId = 2,
                    teamId = 2,
                    name = "AI Opponent",
                    avatarId = "Avatar_Basic_Sparky",
                    commanderGrpIds = commanderGrpIds,
                ),
            )
        val body =
            FdEnvelope.buildMatchCreatedJson(
                match.matchId,
                match.host,
                match.port,
                matchType = if (yourSeat > 1) "Queue" else "Familiar",
                yourSeat = yourSeat,
                eventId = match.eventName,
                playerInfos = players,
            )
        LoggerFactory
            .getLogger(FrontDoorMatchNotifications::class.java)
            .info("Front Door: pushing MatchCreated matchId={} event={} seat={}", match.matchId, match.eventName, yourSeat)
        writer.send(ctx, UUID.randomUUID().toString(), FdResponse.Json(body))
    }
}
