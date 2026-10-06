package leyline.native.matchdoor

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.ssl.SslContext
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.EventExecutorGroup
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.PlayerId
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleLibrary
import leyline.match.MatchConnection
import leyline.match.MatchDebugSink
import leyline.match.MatchRegistry
import leyline.native.AccountAuthenticator
import leyline.native.matchmaking.LocalPairingService
import leyline.native.protocol.ClientFrameDecoder
import leyline.native.protocol.ClientHeaderPrepender
import leyline.native.protocol.ClientHeaderStripper
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import java.io.File

object NativeMatchDoorBootstrap {
    private const val DEFAULT_MATCH_HANDLER_THREADS = 4

    @Suppress("LongParameterList")
    fun bind(
        bossGroup: EventLoopGroup,
        workerGroup: EventLoopGroup,
        ssl: SslContext,
        bindAddress: String,
        port: Int,
        engineSettings: EngineSettings,
        puzzlesDir: File,
        coordinator: MatchCoordinator,
        cardRepository: CardRepository,
        debugSink: MatchDebugSink,
        puzzleIdentity: () -> String?,
        runtimeMatchConfigs: RuntimeMatchConfigRegistry,
        aiDeckNameOverride: () -> String? = { null },
        accountAuthenticator: AccountAuthenticator? = null,
        pairingService: LocalPairingService? = null,
        coordinatorFactory: ((PlayerId) -> MatchCoordinator)? = null,
        matchHandlerGroup: EventExecutorGroup? = null,
    ): Channel {
        require((accountAuthenticator == null) == (pairingService == null)) {
            "Native authentication and room reservations must be configured together"
        }
        val registry = MatchRegistry { pairingService?.complete(it) }
        debugSink.sessionProvider = { registry.activeHumanSession() }
        val handlers = matchHandlerGroup ?: DefaultEventExecutorGroup(DEFAULT_MATCH_HANDLER_THREADS)
        val ownsHandlers = matchHandlerGroup == null
        return try {
            val channel =
                ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(
                        object : ChannelInitializer<SocketChannel>() {
                            override fun initChannel(ch: SocketChannel) {
                                ch.pipeline().addLast("ssl", ssl.newHandler(ch.alloc()))
                                ch.pipeline().addLast("frameDecoder", ClientFrameDecoder())
                                ch.pipeline().addLast("headerStripper", ClientHeaderStripper())
                                ch.pipeline().addLast("protobufDecoder", ProtobufDecoder(ClientToMatchServiceMessage.getDefaultInstance()))
                                ch.pipeline().addLast("headerPrepender", ClientHeaderPrepender())
                                ch.pipeline().addLast("protobufEncoder", ProtobufEncoder())
                                // MatchConnection.receive can wait on engine interaction. Run the
                                // stateful handler off the transport event loop. Netty assigns one
                                // executor to this context per channel, so reads and lifecycle
                                // callbacks remain ordered without sharing mutable connection state.
                                ch.pipeline().addLast(
                                    handlers,
                                    "handler",
                                    NativeMatchConnectionHandler(
                                        { output, account ->
                                            MatchConnection(
                                                registry = registry,
                                                output = output,
                                                engineSettings = engineSettings,
                                                puzzleLibrary = PuzzleLibrary(puzzlesDir),
                                                coordinator =
                                                    account?.let { coordinatorFactory?.invoke(PlayerId(it.personaId)) }
                                                        ?: coordinator,
                                                cardRepository = cardRepository,
                                                puzzleIdentity = puzzleIdentity,
                                                runtimeMatchConfigs = runtimeMatchConfigs,
                                                aiDeckNameOverride = aiDeckNameOverride,
                                            )
                                        },
                                        accountAuthenticator,
                                        pairingService,
                                    ),
                                )
                            }
                        },
                    ).bind(bindAddress, port)
                    .sync()
                    .channel()
            if (ownsHandlers) {
                channel.closeFuture().addListener { handlers.shutdownGracefully() }
            }
            channel
        } catch (cause: Throwable) {
            if (ownsHandlers) handlers.shutdownGracefully()
            throw cause
        }
    }
}
