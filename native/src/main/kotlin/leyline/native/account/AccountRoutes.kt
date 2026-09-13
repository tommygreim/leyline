package leyline.native.account

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("leyline.native.account.routes")

/**
 * Install local account/bootstrap routes into a Ktor [Route].
 * Login/profile/doorbell stay live; nonessential surfaces stay stubbed.
 */
fun Route.accountRoutes(
    store: AccountStore,
    tokens: TokenService,
    fdHost: String,
    cachedManifests: String? = null,
    allowPasswordGrant: Boolean = false,
) {
    loginRoute(store, tokens, allowPasswordGrant)
    localProfileRoutes(store, tokens)
    accountCreationStub()
    profileRoute(store, tokens)
    doorbellRoute(fdHost, cachedManifests)
    ageGateStub()
    moderateStub()
    skusStub()
    localSocialRoutes(store, tokens)
    catchAll()
}

// -- Local handlers -----------------------------------------------------------

private fun Route.loginRoute(
    store: AccountStore,
    tokens: TokenService,
    allowPasswordGrant: Boolean,
) {
    post("/auth/oauth/token") {
        val params = call.receiveParameters()
        val grantType = params["grant_type"]

        when (grantType) {
            "password" -> {
                if (!allowPasswordGrant) return@post call.respondError(AccountError.PASSWORD_LOGIN_DISABLED)
                val email =
                    params["username"]
                        ?: return@post call.respondError(AccountError.MISSING_FIELD)
                val password =
                    params["password"]
                        ?: return@post call.respondError(AccountError.MISSING_PASSWORD)
                val account =
                    store.authenticate(email, password)
                        ?: return@post call.respondError(AccountError.INVALID_CREDENTIALS)
                val pair = tokens.issueTokens(account)
                log.info("Login succeeded")
                call.respondText(
                    loginResponseJson(account, pair),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }

            "refresh_token" -> {
                val refreshToken =
                    params["refresh_token"]
                        ?: return@post call.respondError(AccountError.MISSING_REFRESH_TOKEN)
                val personaId =
                    tokens.validateRefreshToken(refreshToken)
                        ?: return@post call.respondError(AccountError.INVALID_CLIENT)
                val account =
                    store.findByPersonaId(personaId)
                        ?: return@post call.respondError(AccountError.INVALID_CLIENT)
                val pair = tokens.issueTokens(account, refreshToken)
                log.info("Token refresh succeeded")
                call.respondText(
                    loginResponseJson(account, pair),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }

            else -> call.respondError(AccountError.UNSUPPORTED_GRANT_TYPE)
        }
    }
}

private fun Route.localProfileRoutes(
    store: AccountStore,
    tokens: TokenService,
) {
    post("/local/profiles") {
        val name =
            localDisplayName(call.receive<JsonObject>())
                ?: return@post call.respondError(AccountError.INVALID_DISPLAY_NAME)
        val account = store.createLocalProfile(name)
        call.respondText(
            localProfileJson(account, tokens.issueTokens(account).refreshToken),
            ContentType.Application.Json,
            HttpStatusCode.Created,
        )
    }
    post("/local/profile") {
        val credential =
            call.request
                .header("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()
                ?: return@post call.respondError(AccountError.MISSING_AUTH)
        val personaId =
            tokens.validateRefreshToken(credential)
                ?: return@post call.respondError(AccountError.INVALID_TOKEN)
        val name =
            localDisplayName(call.receive<JsonObject>())
                ?: return@post call.respondError(AccountError.INVALID_DISPLAY_NAME)
        val account =
            store.renameLocalProfile(personaId, name)
                ?: return@post call.respondError(AccountError.NOT_FOUND)
        call.respondText(localProfileJson(account, credential), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

private fun localDisplayName(body: JsonObject): String? =
    (body["displayName"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { name ->
        name.length in 1..32 && name.none { it.isISOControl() || it == '#' }
    }

private fun localProfileJson(
    account: Account,
    refreshToken: String,
): String =
    buildJsonObject {
        put("persona_id", account.personaId)
        put("display_name", account.displayName)
        put("refresh_token", refreshToken)
    }.toString()

private fun Route.accountCreationStub() {
    post("/accounts/register") {
        call.receiveText()
        call.respondError(AccountError.REGISTRATION_DISABLED)
    }
}

private fun Route.profileRoute(
    store: AccountStore,
    tokens: TokenService,
) {
    get("/profile") {
        val bearer =
            call.request
                .header("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()
        if (bearer == null) {
            return@get call.respondError(AccountError.MISSING_AUTH)
        }
        val personaId = tokens.validateAccessToken(bearer)
        if (personaId == null) {
            return@get call.respondError(AccountError.INVALID_TOKEN)
        }
        val account =
            store.findByPersonaId(personaId)
                ?: return@get call.respondError(AccountError.NOT_FOUND)
        log.debug("Profile requested")
        call.respondText(
            profileResponseJson(account),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }
}

// -- Doorbell -----------------------------------------------------------------

private fun Route.doorbellRoute(
    fdHost: String,
    cachedManifests: String?,
) {
    post("/api/doorbell/api/v2/ring") {
        call.receiveText() // drain body
        val hasManifests = cachedManifests != null
        log.info("Doorbell: FdURI={} manifests={}", fdHost, if (hasManifests) "cached" else "empty")
        val response =
            buildJsonObject {
                put("FdURI", fdHost)
                if (cachedManifests != null) {
                    put("BundleManifests", Json.parseToJsonElement(cachedManifests))
                } else {
                    putJsonArray("BundleManifests") {}
                }
            }
        call.respondText(response.toString(), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

// -- Stubs --------------------------------------------------------------------

private fun Route.ageGateStub() {
    post("/accounts/requires-age-gate") {
        call.receiveText()
        call.respondText(
            """{"requiresAgeGate":false}""",
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }
}

private fun Route.moderateStub() {
    post("/accounts/moderate") {
        call.receiveText()
        call.respondText("", ContentType.Application.Json, HttpStatusCode.OK)
    }
}

private fun Route.skusStub() {
    get("/xsollaconnector/client/skus") {
        call.respondText(
            """{"items":[]}""",
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }
}

private fun Route.localSocialRoutes(
    store: AccountStore,
    tokens: TokenService,
) {
    get("/friends/friendship/all") {
        // The social client enumerates this list even when no friends exist.
        call.respondText("""{"friends":[]}""", ContentType.Application.Json, HttpStatusCode.OK)
    }
    post("/presence/app-presence") {
        val bearer =
            call.request
                .header("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()
                ?: return@post call.respondError(AccountError.MISSING_AUTH)
        val personaId =
            tokens.validateAccessToken(bearer)
                ?: return@post call.respondError(AccountError.INVALID_TOKEN)
        val account =
            store.findByPersonaId(personaId)
                ?: return@post call.respondError(AccountError.NOT_FOUND)
        val request = call.receive<JsonObject>()
        val now = System.currentTimeMillis() / 1000
        // Acknowledge the local user's presence without publishing it to a social service.
        val response =
            buildJsonObject {
                put("accountId", account.accountId)
                put("personaId", account.personaId)
                put("gameId", "arena")
                put("updatedAt", now)
                put("expiry", request["expiry"]?.takeUnless { it == JsonNull } ?: JsonPrimitive(now + 60))
                put("platformStatus", request["platformStatus"] ?: JsonPrimitive(0))
                put("gameStatus", request["gameStatus"] ?: JsonPrimitive(""))
                put("data", request["data"] ?: JsonNull)
            }
        call.respondText(response.toString(), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

private fun Route.catchAll() {
    route("{...}") {
        handle {
            log.warn("Unhandled: {} {}", call.request.httpMethod.value, call.request.path())
            if (call.request.httpMethod == HttpMethod.Post) call.receiveText()
            call.respondText("{}", ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}

// -- JSON response builders (injection-safe via kotlinx.serialization) --------

private fun loginResponseJson(
    account: Account,
    pair: TokenService.TokenPair,
): String =
    buildJsonObject {
        put("access_token", pair.accessToken)
        put("refresh_token", pair.refreshToken)
        put("expires_in", pair.expiresIn)
        put("token_type", "Bearer")
        put("persona_id", account.personaId)
        put("account_id", account.accountId)
        put("display_name", account.displayName)
    }.toString()

private fun profileResponseJson(account: Account): String =
    buildJsonObject {
        put("accountID", account.accountId)
        put("ccpaProtectData", false)
        put("countryCode", account.country)
        put("createdAt", account.createdAt)
        put("dataOptIn", true)
        put("displayName", account.displayName)
        put("email", account.email)
        put("emailOptIn", false)
        put("emailVerified", false)
        put("externalID", "")
        put("gameID", "arena")
        put("languageCode", "en-US")
        put("parentalConsentState", 3)
        put("personaID", account.personaId)
        putJsonObject("presenceSettings") {
            put("socialMode", "PUBLIC")
        }
        put("targetedAnalyticsOptOut", false)
    }.toString()
