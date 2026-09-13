package leyline.native.matchdoor

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import leyline.domain.PlayerId
import leyline.infra.MatchOutput
import leyline.match.MatchConnection
import leyline.native.account.Account
import leyline.native.account.LocalAccountAuthenticator
import leyline.native.matchmaking.LocalPairingService
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import java.util.UUID

/** Netty lifecycle adapter; channel event-loop serialization stays at this edge. */
internal class NativeMatchConnectionHandler(
    private val connectionFactory: (MatchOutput, Account?) -> MatchConnection,
    private val accountAuthenticator: LocalAccountAuthenticator? = null,
    private val pairingService: LocalPairingService? = null,
) : SimpleChannelInboundHandler<ClientToMatchServiceMessage>() {
    private val log = LoggerFactory.getLogger(NativeMatchConnectionHandler::class.java)
    private lateinit var output: MatchOutput
    private var connection: MatchConnection? = null
    private var account: Account? = null
    private var familiar = false
    private var roomBound = false
    private val connectionId = UUID.randomUUID().toString()

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        output = NettyMatchOutput(ctx)
        if (accountAuthenticator == null) connection = connectionFactory(output, null)
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        connection?.opened()
    }

    override fun channelRead0(
        ctx: ChannelHandlerContext,
        msg: ClientToMatchServiceMessage,
    ) {
        runCatching {
            if (accountAuthenticator == null) {
                checkNotNull(connection).receive(msg)
            } else {
                receiveAuthenticated(msg)
            }
        }.onFailure { cause ->
            log.warn("Match Door admission rejected for {}: {}", msg.clientToMatchServiceMessageType, cause.message)
            connection?.failed(cause) ?: ctx.close()
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        connection?.disconnected()
    }

    override fun exceptionCaught(
        ctx: ChannelHandlerContext,
        cause: Throwable,
    ) {
        connection?.failed(cause) ?: ctx.close()
    }

    private fun receiveAuthenticated(msg: ClientToMatchServiceMessage) {
        when (msg.clientToMatchServiceMessageType) {
            ClientToMatchServiceMessageType.AuthenticateRequest_f487 -> {
                require(account == null) { "Match Door is already authenticated" }
                val request = AuthenticateRequest.parseFrom(msg.payload)
                // Native GRE clients carry the account access JWT in the ticket field.
                val credential = request.playFabSessionTicket.ifEmpty { request.clientAuthToken.toStringUtf8() }
                val authenticated =
                    requireNotNull(accountAuthenticator?.authenticateAccessToken(credential)) {
                        "Invalid Match Door access token"
                    }
                familiar = request.clientId.endsWith("_Familiar")
                val suppliedId = request.clientId.removeSuffix("_Familiar")
                require(suppliedId.isEmpty() || suppliedId == authenticated.personaId || suppliedId == authenticated.accountId) {
                    "Match Door identity does not match the access token"
                }
                account = authenticated
                val canonicalId = authenticated.personaId + if (familiar) "_Familiar" else ""
                val sanitized =
                    request
                        .toBuilder()
                        .setClientId(canonicalId)
                        .setPlayerName(authenticated.displayName)
                        .build()
                connection = connectionFactory(output, authenticated).also { it.opened() }
                checkNotNull(connection).receive(msg.toBuilder().setPayload(sanitized.toByteString()).build())
            }

            ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487 -> {
                val authenticated = requireNotNull(account) { "Match Door authentication is required" }
                require(!roomBound) { "Match Door is already connected" }
                val request = ClientToMatchDoorConnectRequest.parseFrom(msg.payload)
                val gre = ClientToGREMessage.parseFrom(request.clientToGreMessageBytes)
                require(gre.type == ClientMessageType.ConnectReq_097b) { "Initial GRE request must connect" }
                val assignment =
                    checkNotNull(pairingService).claim(
                        request.matchId,
                        PlayerId(authenticated.personaId),
                        gre.systemSeatId,
                        familiar,
                        connectionId,
                    )
                checkNotNull(connection).assignSeat(assignment)
                roomBound = true
                checkNotNull(connection).receive(msg)
            }

            else -> {
                require(roomBound) { "Match Door room admission is required" }
                checkNotNull(connection).receive(msg)
            }
        }
    }
}
