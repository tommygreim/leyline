package leyline.native

/** Immutable identity details shared by the native protocol listeners. */
data class Account(
    val accountId: String,
    val personaId: String,
    val email: String,
    val displayName: String,
    val country: String,
    val dob: String,
    val createdAt: String,
)

/** Authentication boundary shared by the account implementation and native listeners. */
fun interface AccountAuthenticator {
    fun authenticateAccessToken(token: String): Account?
}
