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
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.PlayerId
import leyline.domain.service.MatchCoordinator
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleLibrary
import leyline.match.MatchConnection
import leyline.match.MatchDebugSink
import leyline.match.MatchRegistry
import leyline.native.account.LocalAccountAuthenticator
import leyline.native.matchmaking.LocalPairingService
import leyline.native.protocol.ClientFrameDecoder
import leyline.native.protocol.ClientHeaderPrepender
import leyline.native.protocol.ClientHeaderStripper
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessage
import java.io.File

object NativeMatchDoorBootstrap {
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
        accountAuthenticator: LocalAccountAuthenticator? = null,
        pairingService: LocalPairingService? = null,
        coordinatorFactory: ((PlayerId) -> MatchCoordinator)? = null,
    ): Channel {
        require((accountAuthenticator == null) == (pairingService == null)) {
            "Native authentication and room reservations must be configured together"
        }
        val registry = MatchRegistry { pairingService?.complete(it) }
        debugSink.sessionProvider = { registry.activeHumanSession() }
        return ServerBootstrap()
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
                        ch.pipeline().addLast(
                            "handler",
                            NativeMatchConnectionHandler(
                                { output, account ->
                                    MatchConnection(
                                        registry = registry,
                                        output = output,
                                        engineSettings = engineSettings,
                                        puzzleLibrary = PuzzleLibrary(puzzlesDir),
                                        coordinator = account?.let { coordinatorFactory?.invoke(PlayerId(it.personaId)) } ?: coordinator,
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
    }
}
