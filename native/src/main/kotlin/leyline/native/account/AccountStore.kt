package leyline.native.account

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * SQLite-backed account storage. Lives in the same DB as the player/deck tables
 * but owns only the `accounts` table.
 */
class AccountStore(
    private val database: Database,
) {
    internal object Accounts : Table("accounts") {
        val accountId = text("account_id")
        val personaId = text("persona_id").uniqueIndex()
        val email = text("email").uniqueIndex()
        val displayName = text("display_name")
        val passwordHash = text("password_hash")
        val country = text("country").default("US")
        val dob = text("dob")
        val createdAt = text("created_at")
        override val primaryKey = PrimaryKey(accountId)
    }

    internal object RefreshCredentials : Table("local_refresh_credentials") {
        val digest = text("digest")
        val personaId = text("persona_id")
        override val primaryKey = PrimaryKey(digest)
    }

    internal object Secrets : Table("local_account_secrets") {
        val name = text("name")
        val value = text("value")
        override val primaryKey = PrimaryKey(name)
    }

    fun createTables() {
        transaction(database) { SchemaUtils.create(Accounts, RefreshCredentials, Secrets) }
    }

    /** Persistent server key; access tokens must be verifiable across listeners and restarts. */
    fun tokenSigningKey(): ByteArray =
        transaction(database) {
            val existing = Secrets.selectAll().where { Secrets.name eq "access-token-signing" }.firstOrNull()
            if (existing != null) {
                Base64.getDecoder().decode(existing[Secrets.value])
            } else {
                val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
                Secrets.insert {
                    it[name] = "access-token-signing"
                    it[value] = Base64.getEncoder().encodeToString(key)
                }
                key
            }
        }

    internal fun saveRefreshCredential(
        digest: String,
        personaId: String,
    ) {
        transaction(database) {
            RefreshCredentials.insert {
                it[RefreshCredentials.digest] = digest
                it[RefreshCredentials.personaId] = personaId
            }
        }
    }

    internal fun findRefreshPersona(digest: String): String? =
        transaction(database) {
            RefreshCredentials
                .selectAll()
                .where { RefreshCredentials.digest eq digest }
                .firstOrNull()
                ?.get(RefreshCredentials.personaId)
        }

    fun createLocalProfile(displayName: String): Account {
        val account =
            Account(
                accountId = UUID.randomUUID().toString(),
                personaId = UUID.randomUUID().toString(),
                email = "${UUID.randomUUID()}@local",
                displayName = generateUniqueDisplayName(displayName),
                country = "US",
                dob = "1990-01-01",
                createdAt = Instant.now().toString(),
            )
        // Local profiles use a saved refresh credential; no user-facing password exists.
        insert(account, UUID.randomUUID().toString())
        return account
    }

    fun renameLocalProfile(
        personaId: String,
        displayName: String,
    ): Account? {
        val account = findByPersonaId(personaId) ?: return null
        if (account.displayName.substringBeforeLast('#') == displayName) return account
        val name = generateUniqueDisplayName(displayName)
        transaction(database) {
            Accounts.update({ Accounts.personaId eq personaId }) { it[Accounts.displayName] = name }
        }
        return account.copy(displayName = name)
    }

    /** Create a local account row. Used by tests and local bootstrap helpers. */
    internal fun create(
        email: String,
        password: String,
        displayName: String,
        country: String = "US",
        dob: String = "1990-01-01",
    ): Account {
        val account =
            Account(
                accountId = generateId(),
                personaId = generateId(),
                email = email.lowercase(),
                displayName = generateUniqueDisplayName(displayName),
                country = country,
                dob = dob,
                createdAt = Instant.now().toString(),
            )
        insert(account, password)
        return account
    }

    /**
     * Insert an account with pre-determined IDs (used for dev seed).
     * Skips if an account with this email already exists.
     * Returns true if inserted, false if skipped.
     */
    fun seed(
        accountId: String,
        personaId: String,
        email: String,
        displayName: String,
        password: String,
        country: String = "US",
        dob: String = "1990-01-01",
    ): Boolean {
        val existing = findByEmail(email)
        if (existing != null) return false
        insert(
            Account(accountId, personaId, email.lowercase(), displayName, country, dob, Instant.now().toString()),
            password,
        )
        return true
    }

    private fun insert(
        account: Account,
        password: String,
    ) {
        val hash = BCrypt.hashpw(password, BCrypt.gensalt())
        transaction(database) {
            Accounts.insert {
                it[Accounts.accountId] = account.accountId
                it[Accounts.personaId] = account.personaId
                it[Accounts.email] = account.email
                it[Accounts.displayName] = account.displayName
                it[Accounts.passwordHash] = hash
                it[Accounts.country] = account.country
                it[Accounts.dob] = account.dob
                it[Accounts.createdAt] = account.createdAt
            }
        }
    }

    /** Authenticate by email + password. Returns [Account] on success, null on failure. */
    fun authenticate(
        email: String,
        password: String,
    ): Account? {
        val row =
            transaction(database) {
                Accounts
                    .selectAll()
                    .where { Accounts.email eq email.lowercase() }
                    .firstOrNull()
            } ?: return null
        val hash = row[Accounts.passwordHash]
        if (!BCrypt.checkpw(password, hash)) return null
        return row.toAccount()
    }

    /** Look up account by email. */
    fun findByEmail(email: String): Account? = findOneBy(Accounts.email, email.lowercase())

    /** Look up account by persona ID. */
    fun findByPersonaId(personaId: String): Account? = findOneBy(Accounts.personaId, personaId)

    private fun <T : Comparable<T>> findOneBy(
        column: org.jetbrains.exposed.v1.core.Column<T>,
        value: T,
    ): Account? =
        transaction(database) {
            Accounts
                .selectAll()
                .where { column eq value }
                .firstOrNull()
                ?.toAccount()
        }

    /** Check if any accounts exist (for dev seed logic). */
    fun isEmpty(): Boolean =
        transaction(database) {
            Accounts.selectAll().count() == 0L
        }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toAccount() =
        Account(
            accountId = this[Accounts.accountId],
            personaId = this[Accounts.personaId],
            email = this[Accounts.email],
            displayName = this[Accounts.displayName],
            country = this[Accounts.country],
            dob = this[Accounts.dob],
            createdAt = this[Accounts.createdAt],
        )

    private fun generateUniqueDisplayName(baseName: String): String {
        repeat(10) {
            val disc = (1..99999).random()
            val candidate = "$baseName#${disc.toString().padStart(5, '0')}"
            val exists =
                transaction(database) {
                    Accounts
                        .selectAll()
                        .where { Accounts.displayName eq candidate }
                        .empty()
                        .not()
                }
            if (!exists) return candidate
        }
        error("Failed to generate unique display name for '$baseName' after 10 attempts")
    }

    private fun generateId(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .uppercase()
            .take(26)
}

/** Immutable account snapshot returned from store queries. */
data class Account(
    val accountId: String,
    val personaId: String,
    val email: String,
    val displayName: String,
    val country: String,
    val dob: String,
    val createdAt: String,
)
