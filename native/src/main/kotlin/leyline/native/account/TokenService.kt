package leyline.native.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Signed, short-lived access JWTs and durable opaque local profile credentials. */
class TokenService(
    private val clientId: String = DEFAULT_CLIENT_ID,
    private val store: AccountStore? = null,
) {
    private val signingKey = store?.tokenSigningKey() ?: randomBytes()
    private val memoryCredentials = ConcurrentHashMap<String, String>()

    data class TokenPair(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long = ACCESS_EXPIRY_SECONDS,
    )

    fun issueTokens(
        account: Account,
        refreshToken: String? = null,
    ): TokenPair {
        val credential =
            refreshToken?.takeIf { validateRefreshToken(it) == account.personaId }
                ?: issueRefreshCredential(account.personaId)
        return TokenPair(buildAccessToken(account), credential)
    }

    fun validateRefreshToken(token: String): String? {
        if (!token.startsWith(REFRESH_PREFIX) || token.length != REFRESH_PREFIX.length + 43) return null
        val digest = digest(token)
        return if (store != null) store.findRefreshPersona(digest) else memoryCredentials[digest]
    }

    fun validateAccessToken(token: String): String? =
        runCatching {
            val parts = token.split('.')
            if (parts.size != 3 || parts[0] != encode(HEADER.toByteArray())) return null
            val expected = sign("${parts[0]}.${parts[1]}")
            if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) return null
            val payload = Json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8)).jsonObject
            if (payload["aud"]?.jsonPrimitive?.content != clientId) return null
            val expiration = payload["exp"]?.jsonPrimitive?.longOrNull ?: return null
            if (expiration <= nowSeconds()) return null
            payload["sub"]?.jsonPrimitive?.content
        }.getOrNull()

    private fun issueRefreshCredential(personaId: String): String {
        val token = REFRESH_PREFIX + encode(randomBytes())
        val digest = digest(token)
        if (store != null) store.saveRefreshCredential(digest, personaId) else memoryCredentials[digest] = personaId
        return token
    }

    private fun buildAccessToken(account: Account): String {
        val now = nowSeconds()
        val payload =
            buildJsonObject {
                put("aud", clientId)
                put("exp", now + ACCESS_EXPIRY_SECONDS)
                put("iat", now)
                put("iss", account.accountId)
                put("sub", account.personaId)
            }.toString()
        val unsigned = encode(HEADER.toByteArray()) + "." + encode(payload.toByteArray())
        return unsigned + "." + encode(sign(unsigned))
    }

    private fun sign(value: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(signingKey, "HmacSHA256"))
            doFinal(value.toByteArray(Charsets.UTF_8))
        }

    private fun digest(value: String): String = encode(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun randomBytes(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    companion object {
        const val DEFAULT_CLIENT_ID = "leyline"
        const val ACCESS_EXPIRY_SECONDS = 960L
        private const val HEADER = """{"alg":"HS256","typ":"JWT"}"""
        private const val REFRESH_PREFIX = "leyline-local-"
    }
}
