package leyline.game.data

import org.yaml.snakeyaml.Yaml
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Per-card YAML fixture parser and index. Reads each YAML file under the
 * test resources directory `test-cards` into a [Fixture] (client identity
 * plus optional rules data) and exposes name- and grpId-keyed lookups.
 *
 * Two shapes:
 * - **Slim** ([Fixture.rules] is null): Forge cardsfolder owns rules data
 *   (P/T, types, colors, mana cost). Used for cards in Forge cardsfolder.
 * - **Full** ([Fixture.rules] is non-null): self-contained. Used for tokens
 *   and Alchemy / digital-only cards Forge has no entry for.
 *
 * Field IDs (colors, types, subtypes, supertypes, ability category) are
 * client enum integers as stored in `Cards.*` columns.
 *
 * Closure invariant: every grpId referenced in `tokens` or `linkedFaces`
 * has its own YAML in the same directory. Closure walking lives in
 * [leyline.tooling.headless.FixtureCardLoader], not here.
 */
object TestCardFixtures {
    private const val DEFAULT_RESOURCE_DIR = "test-cards"

    /**
     * Client identity for a card — the integers Forge cardsfolder doesn't
     * carry. Stamped onto a Forge-derived [CardData] for slim fixtures.
     */
    data class Identity(
        val name: String,
        val grpId: Int,
        val titleId: Int,
        val expansionCode: String,
        val abilities: List<Ability>,
        val hiddenAbilities: List<Ability> = emptyList(),
        val tokens: Map<Int, Int>,
        val linkedFaceType: Int,
        val linkedFaces: List<Int>,
        val isToken: Boolean,
        val isPrimaryCard: Boolean,
    ) {
        data class Ability(
            val id: Int,
            val textId: Int,
            val category: Int,
            val subCategory: Int,
            val baseId: Int,
            val activationMana: List<Pair<ManaColor, Int>>,
            val modalChildren: List<Int>,
        )
    }

    /** Forge-redundant rules data; present on Full fixtures, null on Slim. */
    data class Rules(
        val power: String,
        val toughness: String,
        val colors: List<Int>,
        val types: List<Int>,
        val subtypes: List<Int>,
        val supertypes: List<Int>,
        val manaCost: List<Pair<ManaColor, Int>>,
    )

    /** A loaded fixture. Slim ⇔ [rules] is null; Full ⇔ [rules] is non-null. */
    data class Fixture(
        val identity: Identity,
        val rules: Rules?,
    ) {
        val isFull: Boolean get() = rules != null
    }

    private val byName: Map<String, Fixture> by lazy {
        val all = loadAllFixtures()
        val map = mutableMapOf<String, Fixture>()
        for (f in all) {
            map[f.identity.name] = f
            // Forge names tokens "Soldier Token"; client DB stores them as "Soldier".
            // Index both forms so callers passing either resolve.
            if (f.identity.isToken) map["${f.identity.name} Token"] = f
        }
        map
    }
    private val byGrpId: Map<Int, Fixture> by lazy { loadAllFixtures().associateBy { it.identity.grpId } }

    /** Lookup fixture by display name (or "<Name> Token" alias for token fixtures). */
    fun findFixture(name: String): Fixture? = byName[name]

    /** Lookup fixture by grpId. */
    fun findFixtureByGrpId(grpId: Int): Fixture? = byGrpId[grpId]

    /** All loaded fixtures, indexed by display name. */
    val all: Map<String, Fixture> get() = byName

    // --- Loading ---

    private fun loadAllFixtures(): List<Fixture> {
        val dir = resolveResourceDir()
        return Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension == "yaml" }
                .map { parseFile(it) }
                .toList()
        }
    }

    private fun resolveResourceDir(): Path {
        val classpathUrl =
            Thread
                .currentThread()
                .contextClassLoader
                .getResource(DEFAULT_RESOURCE_DIR)
        if (classpathUrl != null) return Paths.get(classpathUrl.toURI())

        val candidates =
            listOf(
                Paths.get("engine/src/test/resources/$DEFAULT_RESOURCE_DIR"),
                Paths.get("engine/src/test/resources/$DEFAULT_RESOURCE_DIR"),
                Paths.get("src/test/resources/$DEFAULT_RESOURCE_DIR"),
            )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("Resource directory '$DEFAULT_RESOURCE_DIR' not on classpath or in $candidates")
    }

    private fun parseFile(path: Path): Fixture =
        try {
            val raw =
                Files.newBufferedReader(path).use { Yaml().load<Map<String, Any?>>(it) }
                    ?: error("file is empty")
            Fixture(parseIdentity(raw), parseRules(raw))
        } catch (e: Exception) {
            throw IllegalStateException("Failed to parse fixture $path: ${e.message}", e)
        }

    @Suppress("UNCHECKED_CAST")
    private fun parseIdentity(raw: Map<String, Any?>): Identity =
        Identity(
            name = raw.requireField<String>("name"),
            grpId = raw.requireField<Number>("grpId").toInt(),
            titleId = raw.requireField<Number>("titleId").toInt(),
            expansionCode = (raw["expansionCode"] as? String).orEmpty(),
            abilities =
                (raw["abilities"] as? List<*>).orEmpty().mapIndexed { i, entry ->
                    parseAbility(i, entry as Map<String, Any?>)
                },
            hiddenAbilities =
                (raw["hiddenAbilities"] as? List<*>).orEmpty().mapIndexed { i, entry ->
                    parseHiddenAbility(i, entry as Map<String, Any?>)
                },
            tokens = parseTokens(raw["tokens"] as? Map<*, *>),
            linkedFaceType = (raw["linkedFaceType"] as? Number)?.toInt() ?: 0,
            linkedFaces = (raw["linkedFaces"] as? List<*>).orEmpty().map { (it as Number).toInt() },
            isToken = (raw["isToken"] as? Boolean) ?: false,
            isPrimaryCard = (raw["isPrimaryCard"] as? Boolean) ?: true,
        )

    @Suppress("UNCHECKED_CAST")
    private fun parseAbility(
        i: Int,
        m: Map<String, Any?>,
    ): Identity.Ability =
        Identity.Ability(
            id = (m["id"] as? Number)?.toInt() ?: error("abilities[$i].id missing"),
            textId = (m["textId"] as? Number)?.toInt() ?: error("abilities[$i].textId missing"),
            category = (m["category"] as? Number)?.toInt() ?: error("abilities[$i].category missing"),
            subCategory = (m["subCategory"] as? Number)?.toInt() ?: 0,
            baseId = (m["baseId"] as? Number)?.toInt() ?: 0,
            activationMana = parseManaCost((m["mana"] as? String).orEmpty()),
            modalChildren = (m["modalChildren"] as? List<*>).orEmpty().map { (it as Number).toInt() },
        )

    private fun parseTokens(raw: Map<*, *>?): Map<Int, Int> =
        raw.orEmpty().entries.associate { (k, v) ->
            val key =
                when (k) {
                    is Number -> k.toInt()
                    is String -> k.toInt()
                    else -> error("token key '$k' is neither Number nor String")
                }
            key to (v as Number).toInt()
        }

    /**
     * Hidden ability entry — same shape as [parseAbility], but `category` is
     * optional (defaults to 0/"unknown"). Most hidden abilities are simple
     * id:textId presence pairs; a few (e.g. a keyword granted onto other
     * cards, like The Necrobloom's Dredge grant) need `baseId`/`category` too
     * so [CardRepository.findKeywordAbilityGrpId]'s BaseId-chain check can
     * resolve them.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseHiddenAbility(
        i: Int,
        m: Map<String, Any?>,
    ): Identity.Ability =
        Identity.Ability(
            id = (m["id"] as? Number)?.toInt() ?: error("hiddenAbilities[$i].id missing"),
            textId = (m["textId"] as? Number)?.toInt() ?: error("hiddenAbilities[$i].textId missing"),
            category = (m["category"] as? Number)?.toInt() ?: 0,
            subCategory = (m["subCategory"] as? Number)?.toInt() ?: 0,
            baseId = (m["baseId"] as? Number)?.toInt() ?: 0,
            activationMana = parseManaCost((m["mana"] as? String).orEmpty()),
            modalChildren = (m["modalChildren"] as? List<*>).orEmpty().map { (it as Number).toInt() },
        )

    @Suppress("UNCHECKED_CAST")
    private fun parseRules(raw: Map<String, Any?>): Rules? {
        val hasRulesFields =
            listOf("power", "toughness", "colors", "types", "subtypes", "supertypes", "manaCost")
                .any { raw.containsKey(it) }
        if (!hasRulesFields) return null
        return Rules(
            power = (raw["power"] as? String).orEmpty(),
            toughness = (raw["toughness"] as? String).orEmpty(),
            colors = (raw["colors"] as? List<*>).orEmpty().map { (it as Number).toInt() },
            types = (raw["types"] as? List<*>).orEmpty().map { (it as Number).toInt() },
            subtypes = (raw["subtypes"] as? List<*>).orEmpty().map { (it as Number).toInt() },
            supertypes = (raw["supertypes"] as? List<*>).orEmpty().map { (it as Number).toInt() },
            manaCost = parseManaCost((raw["manaCost"] as? String).orEmpty()),
        )
    }

    private inline fun <reified T> Map<String, Any?>.requireField(name: String): T {
        val v = this[name] ?: error("required field '$name' missing")
        return v as? T ?: error("field '$name' has wrong type: expected ${T::class.simpleName}, got ${v::class.simpleName}")
    }
}
