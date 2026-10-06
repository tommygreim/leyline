package leyline.native.account

import leyline.native.AccountAuthenticator

/** Shared by account, Front Door, and Match Door listeners. Client claims never choose identity. */
class LocalAccountAuthenticator(
    private val store: AccountStore,
    private val tokens: TokenService,
) : AccountAuthenticator {
    override fun authenticateAccessToken(token: String): Account? = tokens.validateAccessToken(token)?.let(store::findByPersonaId)
}
