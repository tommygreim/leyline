package leyline.native.frontdoor

import io.netty.channel.ChannelHandlerContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import leyline.domain.PlayerId
import leyline.domain.json.productionJson
import leyline.domain.service.MatchCoordinator
import leyline.native.account.Account
import leyline.native.account.LocalAccountAuthenticator
import leyline.native.frontdoor.service.PlayerService
import leyline.native.frontdoor.wire.CmdType
import leyline.native.frontdoor.wire.FdResponse
import leyline.native.frontdoor.wire.FdResponseWriter
import org.slf4j.LoggerFactory
import java.util.UUID

/** One authenticated local persona per Front Door connection. */
internal class FrontDoorIdentity(
    private val authenticator: LocalAccountAuthenticator,
    private val players: PlayerService,
    private val writer: FdResponseWriter,
    private val coordinatorFactory: (PlayerId) -> MatchCoordinator,
) {
    private val json = productionJson { ignoreUnknownKeys = true }
    val connectionId: String = UUID.randomUUID().toString()
    var account: Account? = null
        private set
    var coordinator: MatchCoordinator = MatchCoordinator.NOOP
        private set
    val playerId: PlayerId get() = PlayerId(checkNotNull(account).personaId)

    /** Handles authentication itself; returns true only for an authenticated data command. */
    fun accept(
        ctx: ChannelHandlerContext,
        cmdType: Int?,
        txId: String?,
        body: String?,
    ): Boolean {
        if (cmdType == CmdType.AUTHENTICATE.value || (cmdType == null && body?.contains("\"Token\"") == true)) {
            authenticate(ctx, txId, body)
            return false
        }
        if (account == null) {
            reject(ctx, txId)
            return false
        }
        return true
    }

    private fun authenticate(
        ctx: ChannelHandlerContext,
        txId: String?,
        body: String?,
    ) {
        val token =
            runCatching {
                (json.parseToJsonElement(body ?: "{}").jsonObject["Token"] as? JsonPrimitive)?.content
            }.getOrNull()
        val authenticated = token?.let(authenticator::authenticateAccessToken)
        if (authenticated == null || (account != null && account?.personaId != authenticated.personaId)) {
            reject(ctx, txId)
            return
        }
        if (account == null) coordinator = coordinatorFactory(PlayerId(authenticated.personaId))
        account = authenticated
        val session = players.authenticate(playerId, authenticated.displayName)
        writer.send(ctx, txId, FdResponse.Json("""{"SessionId":"${session.value}","Attached":true}"""))
        LoggerFactory.getLogger(FrontDoorIdentity::class.java).info("Front Door: authenticated local player {}", playerId.value)
    }

    private fun reject(
        ctx: ChannelHandlerContext,
        txId: String?,
    ) {
        writer.send(ctx, txId, FdResponse.Json("""{"Attached":false,"Error":"Unauthorized"}"""))
        ctx.close()
    }
}
