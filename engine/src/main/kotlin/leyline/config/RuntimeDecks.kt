package leyline.config

import kotlinx.serialization.Serializable
import leyline.domain.deck.DeckSource
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RuntimeMatchConfig(
    val matchId: String,
    /** Seat 1 (human) deck override; realized via [leyline.bridge.bootstrap.DeckLoader]. */
    val seat1: DeckSource? = null,
    /** Seat 2 (AI) deck override; realized via [leyline.bridge.bootstrap.DeckLoader]. */
    val seat2: DeckSource? = null,
    /** Forge variant for a runtime-started match; null keeps the configured default. */
    val gameVariant: String? = null,
    /** Configured puzzle identity. This is never a filesystem path. */
    val puzzle: String? = null,
    /** Inline challenge definition, resolved before the match loading seam. */
    val puzzleDefinition: PuzzleDefinition? = null,
    val spectatorMode: Boolean? = null,
    /** Both seats are independently controlled by players. */
    val humanVsHuman: Boolean = false,
) {
    init {
        require(!humanVsHuman || (seat1 != null && seat2 != null && spectatorMode != true && puzzle == null && puzzleDefinition == null)) {
            "Two-human matches require both decks and cannot be puzzles or spectator games"
        }
        require(puzzle == null || puzzleDefinition == null) { "puzzle and puzzleDefinition are mutually exclusive" }
        val identity = puzzle?.trim()?.removeSuffix(".pzl")
        require(
            identity == null ||
                (
                    identity.isNotEmpty() &&
                        identity != "." &&
                        identity != ".." &&
                        '/' !in identity &&
                        '\\' !in identity
                ),
        ) { "puzzle must be a configured-root identity, not a filesystem path: $puzzle" }
    }
}

@Serializable
data class RuntimeMatchLaunchResponse(
    val matchId: String,
    val wireMatchId: String,
    val accepted: Boolean,
    val config: RuntimeMatchConfig,
)

class RuntimeMatchConfigRegistry {
    private val configs = ConcurrentHashMap<String, RuntimeMatchConfig>()

    fun configure(config: RuntimeMatchConfig): RuntimeMatchLaunchResponse {
        val stored = put(config)
        return RuntimeMatchLaunchResponse(
            matchId = stored.matchId,
            wireMatchId = stored.matchId,
            accepted = true,
            config = stored,
        )
    }

    fun put(config: RuntimeMatchConfig): RuntimeMatchConfig {
        val matchId = config.matchId.trim()
        require(matchId.isNotEmpty()) { "matchId is required" }
        val normalized =
            config.copy(
                matchId = matchId,
                seat1 = config.seat1.normalized(),
                seat2 = config.seat2.normalized(),
                gameVariant = config.gameVariant?.trim()?.takeIf { it.isNotEmpty() },
                puzzle = config.puzzle?.trim()?.takeIf { it.isNotEmpty() },
            )
        configs[matchId] = normalized
        return normalized
    }

    /** Trims [DeckSource.ForgeText]; blank-after-trim normalizes to null, same as a blank string field. */
    private fun DeckSource?.normalized(): DeckSource? =
        when (this) {
            is DeckSource.ForgeText -> text.trim().takeIf { it.isNotEmpty() }?.let { DeckSource.ForgeText(it) }
            is DeckSource.Cards -> this
            null -> null
        }

    fun get(matchId: String): RuntimeMatchConfig? = configs[matchId]

    fun remove(matchId: String): RuntimeMatchConfig? = configs.remove(matchId)

    fun clear() = configs.clear()
}
