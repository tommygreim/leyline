package leyline.native.account

import io.ktor.http.*
import io.ktor.network.tls.certificates.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import leyline.domain.json.productionJson
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyStore

/**
 * Local account bootstrap server.
 *
 * Handles local login/profile/doorbell flows plus a few compatibility stubs.
 * Shares the player.db SQLite database with the rest of Leyline.
 */
class AccountServer(
    private val bindAddress: String,
    private val port: Int,
    private val certFile: File? = null,
    private val keyFile: File? = null,
    private val fdHost: String = "localhost:30010",
    private val database: Database,
    /** BundleManifests JSON array for doorbell response (enables offline mode). */
    private val cachedManifests: String? = null,
    private val accountStore: AccountStore = AccountStore(database).also { it.createTables() },
    private val tokenService: TokenService = TokenService(store = accountStore),
    private val allowPasswordGrant: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(AccountServer::class.java)
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    private val store = accountStore
    private val tokens = tokenService

    fun start() {
        store.createTables()
        seedDevAccount()

        val keyStore = resolveKeyStore(certFile, keyFile)
        val serverPort = port
        val host = fdHost
        val accountStore = store
        val tokenService = tokens
        val serverLog = log

        engine =
            embeddedServer(
                Netty,
                configure = {
                    sslConnector(
                        keyStore = keyStore,
                        keyAlias = KEY_ALIAS,
                        keyStorePassword = { KEY_STORE_PASSWORD.toCharArray() },
                        privateKeyPassword = { KEY_STORE_PASSWORD.toCharArray() },
                    ) {
                        this.host = bindAddress
                        this.port = serverPort
                    }
                },
            ) {
                install(ContentNegotiation) {
                    json(productionJson { ignoreUnknownKeys = true })
                }
                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        serverLog.error(
                            "Unhandled error: {} {}",
                            call.request.local.method.value,
                            call.request.local.uri,
                            cause,
                        )
                        call.respondText(
                            """{"code":500,"grpcCode":"13","error":"INTERNAL"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.InternalServerError,
                        )
                    }
                }
                routing {
                    accountRoutes(accountStore, tokenService, host, cachedManifests, allowPasswordGrant)
                }
            }.also { it.start(wait = false) }

        log.info("AccountServer: https://{}:{}", bindAddress, port)
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    private fun seedDevAccount() {
        if (store.isEmpty()) {
            val seeded =
                store.seed(
                    accountId = DEV_ACCOUNT_ID,
                    personaId = DEV_PERSONA_ID,
                    email = "leyline@local",
                    displayName = "Player#00001",
                    password = "leyline",
                )
            if (seeded) {
                log.info("Dev account seeded")
            }
        }
    }

    companion object {
        /** Matches the hardcoded playerId in LeylineServer for dev seed continuity. */
        const val DEV_PERSONA_ID = "9da3ee9f-0d6a-4b18-a3e0-c9e315d2475b"
        const val DEV_ACCOUNT_ID = "leyline-dev-account-001"

        internal const val KEY_ALIAS = "leyline-account"
        internal const val KEY_STORE_PASSWORD = "leyline"

        /** Build or load a JKS keystore for HTTPS. */
        fun resolveKeyStore(
            certFile: File?,
            keyFile: File?,
        ): KeyStore {
            if (certFile != null && keyFile != null && certFile.exists() && keyFile.exists()) {
                return loadPemKeyStore(certFile, keyFile)
            }
            return buildKeyStore {
                certificate(KEY_ALIAS) {
                    password = KEY_STORE_PASSWORD
                    domains = listOf("localhost", "127.0.0.1")
                }
            }
        }

        private fun loadPemKeyStore(
            certFile: File,
            keyFile: File,
        ): KeyStore {
            val cf =
                java.security.cert.CertificateFactory
                    .getInstance("X.509")
            val cert =
                certFile.inputStream().use {
                    cf.generateCertificate(it) as java.security.cert.X509Certificate
                }
            val keyPem =
                keyFile
                    .readText()
                    .replace(Regex("-----\\w+ PRIVATE KEY-----"), "")
                    .replace("\\s".toRegex(), "")
            val keyBytes =
                java.util.Base64
                    .getDecoder()
                    .decode(keyPem)
            val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
            val privateKey =
                try {
                    java.security.KeyFactory
                        .getInstance("RSA")
                        .generatePrivate(keySpec)
                } catch (_: java.security.spec.InvalidKeySpecException) {
                    java.security.KeyFactory
                        .getInstance("EC")
                        .generatePrivate(keySpec)
                }

            val ks = KeyStore.getInstance("JKS")
            ks.load(null, null)
            ks.setKeyEntry(KEY_ALIAS, privateKey, KEY_STORE_PASSWORD.toCharArray(), arrayOf(cert))
            return ks
        }
    }
}
