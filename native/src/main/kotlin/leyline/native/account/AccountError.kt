package leyline.native.account

import io.ktor.http.*
import io.ktor.server.response.*

/** Account server error codes. Keep response shape stable for local client flows. */
enum class AccountError(
    val httpCode: Int,
    val grpcCode: String,
    val message: String,
) {
    MISSING_FIELD(400, "3", "MISSING USERNAME"),
    MISSING_PASSWORD(400, "3", "MISSING PASSWORD"),
    MISSING_REFRESH_TOKEN(400, "3", "MISSING REFRESH TOKEN"),
    INVALID_DISPLAY_NAME(400, "3", "INVALID DISPLAY NAME"),
    PASSWORD_LOGIN_DISABLED(403, "7", "PASSWORD LOGIN DISABLED"),
    UNSUPPORTED_GRANT_TYPE(400, "3", "UNSUPPORTED GRANT TYPE"),
    REGISTRATION_DISABLED(403, "7", "REGISTRATION DISABLED"),
    INVALID_CREDENTIALS(401, "16", "INVALID ACCOUNT CREDENTIALS"),
    INVALID_CLIENT(401, "16", "INVALID CLIENT CREDENTIALS"),
    MISSING_AUTH(401, "16", "MISSING AUTHORIZATION"),
    INVALID_TOKEN(401, "16", "INVALID TOKEN"),
    NOT_FOUND(404, "5", "ACCOUNT NOT FOUND"),
}

/** Send an account error response: `{code, grpcCode, error}`. */
suspend fun io.ktor.server.application.ApplicationCall.respondError(err: AccountError) {
    respondText(
        """{"code":${err.httpCode},"grpcCode":"${err.grpcCode}","error":"${err.message}"}""",
        ContentType.Application.Json,
        HttpStatusCode.fromValue(err.httpCode),
    )
}
