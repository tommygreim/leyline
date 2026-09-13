package leyline.game.data

import leyline.game.codes.SlotKind
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only [CardRepository] over the client's local SQLite card DB (Exposed).
 *
 * Never creates or modifies the schema — tables (Cards, Localizations_enUS)
 * are owned by the client installation. Entries are cached lazily per-key on
 * first access and never evicted; card data is immutable for a given client
 * build.
 *
 * Internal: construct via [ClientCardDatabase.open] so discovery, validation,
 * and connection all flow through the single resolution policy.
 */
internal class SqliteCardRepository(
    private val database: Database,
) : CardRepository {
    private val log = LoggerFactory.getLogger(SqliteCardRepository::class.java)

    // --- Exposed table objects matching external schema ---

    private object Cards : Table("Cards") {
        val grpId = integer("GrpId")
        val titleId = integer("TitleId")
        val power = text("Power").default("")
        val toughness = text("Toughness").default("")
        val colors = text("Colors").default("")
        val types = text("Types").default("")
        val subtypes = text("Subtypes").default("")
        val supertypes = text("Supertypes").default("")
        val abilityIds = text("AbilityIds").default("")
        val hiddenAbilityIds = text("HiddenAbilityIds").default("")
        val oldSchoolManaText = text("OldSchoolManaText").default("")
        val abilityIdToLinkedTokenGrpId = text("AbilityIdToLinkedTokenGrpId").default("")
        val isToken = integer("IsToken").default(0)
        val isPrimaryCard = integer("IsPrimaryCard").default(1)
        val isDigitalOnly = integer("IsDigitalOnly").default(0)
        val isRebalanced = integer("IsRebalanced").default(0)
        val expansionCode = text("ExpansionCode").default("")
        val linkedFaceType = integer("LinkedFaceType").default(0)
        val linkedFaceGrpIds = text("LinkedFaceGrpIds").default("")
        override val primaryKey = PrimaryKey(grpId)
    }

    private object Localizations : Table("Localizations_enUS") {
        val locId = integer("LocId")
        val formatted = integer("Formatted").default(0)
        val loc = text("Loc").default("")
        override val primaryKey = PrimaryKey(locId)
    }

    private object Abilities : Table("Abilities") {
        val id = integer("Id")
        val baseId = integer("BaseId").default(0)
        val textId = integer("TextId").default(0)
        val oldSchoolManaText = text("OldSchoolManaText").nullable()
        val modalChildIds = text("ModalChildIds").nullable()

        // Arena ability category. Observed: 1 = Activated (player-initiated),
        // 2 = Trigger, 3+ = Static/Passive. Anything we don't recognize is
        // treated as non-activated by the consumer.
        val category = integer("Category").default(0)
        val subCategory = integer("SubCategory").default(0)
        override val primaryKey = PrimaryKey(id)
    }

    /** Strip HTML formatting tags (e.g. `<nobr>`) from localized card names. */
    private fun stripTags(name: String): String = name.replace(tagRegex, "")

    private val tagRegex = Regex("</?[a-zA-Z][^>]*>")

    /** Arena aftermath titles use three slashes; Forge combines split faces with two. */
    private fun forgeName(name: String): String = name.replace(" /// ", " // ")

    // --- In-memory caches ---

    private val dataCache = ConcurrentHashMap<Int, CardData?>()
    private val grpIdToName = ConcurrentHashMap<Int, String>()
    private val nameToGrpId = ConcurrentHashMap<String, Int>()
    private val missingNames = ConcurrentHashMap.newKeySet<String>()
    private val missingAnyFaceNames = ConcurrentHashMap.newKeySet<String>()
    private val tokenNameToGrpId = ConcurrentHashMap<String, Int>()
    private val missingTokenNames = ConcurrentHashMap.newKeySet<String>()
    private val modalCache = ConcurrentHashMap<Int, ModalAbilityInfo?>()
    private val abilityInfoCache = ConcurrentHashMap<Int, java.util.Optional<AbilityInfo>>()
    private val abilityLocalizationCache = ConcurrentHashMap<Int, java.util.Optional<AbilityLocalization>>()

    // --- CardRepository ---

    override fun findByGrpId(grpId: Int): CardData? {
        if (grpId == 0) return null
        dataCache[grpId]?.let { return it }
        val data = queryCardData(grpId) ?: return null
        dataCache[grpId] = data
        queryNameByGrpId(grpId)
        return data
    }

    override fun findNameByGrpId(grpId: Int): String? {
        grpIdToName[grpId]?.let { return it }
        return queryNameByGrpId(grpId)?.also { name ->
            rememberNameResolution(
                name,
                grpId,
                clearPrimaryMiss = true,
                clearAnyFaceMiss = true,
            )
        }
    }

    override fun findGrpIdByName(name: String): Int? {
        nameToGrpId[name]?.let { return it }
        if (name in missingNames) return null
        return queryGrpIdByName(name).also { grpId ->
            if (grpId == null) {
                missingNames.add(name)
            } else {
                rememberNameResolution(name, grpId, clearAnyFaceMiss = true)
            }
        }
    }

    override fun findGrpIdByNameAnyFace(name: String): Int? {
        nameToGrpId[name]?.let { return it }
        if (name in missingAnyFaceNames) return null
        return queryGrpIdByNameAnyFace(name).also { grpId ->
            if (grpId == null) {
                missingAnyFaceNames.add(name)
            } else {
                rememberNameResolution(name, grpId, clearPrimaryMiss = true)
            }
        }
    }

    override fun findTokenGrpIdByName(name: String): Int? {
        tokenNameToGrpId[name]?.let { return it }
        if (name in missingTokenNames) return null
        return queryTokenGrpIdByName(name).also { grpId ->
            if (grpId == null) {
                missingTokenNames.add(name)
            } else {
                tokenNameToGrpId[name] = grpId
                grpIdToName[grpId] = name.removeSuffix(" Token")
            }
        }
    }

    override fun findGrpIdByNameAndSet(
        name: String,
        setCode: String,
    ): Int? =
        queryGrpIdByNameAndSet(name, setCode)?.also { grpId ->
            rememberNameResolution(
                name,
                grpId,
                clearPrimaryMiss = true,
                clearAnyFaceMiss = true,
            )
        }

    private fun rememberNameResolution(
        name: String,
        grpId: Int,
        clearPrimaryMiss: Boolean = false,
        clearAnyFaceMiss: Boolean = false,
    ) {
        val canonicalName = forgeName(name)
        nameToGrpId[name] = grpId
        nameToGrpId[canonicalName] = grpId
        grpIdToName[grpId] = canonicalName
        if (clearPrimaryMiss) missingNames.remove(name)
        if (clearAnyFaceMiss) missingAnyFaceNames.remove(name)
    }

    override fun findAllGrpIds(): List<Int> =
        try {
            transaction(database) {
                Cards
                    .selectAll()
                    .where { (Cards.isToken eq 0) and (Cards.isPrimaryCard eq 1) }
                    .map { it[Cards.grpId] }
            }
        } catch (e: Exception) {
            log.warn("Failed to query all grpIds: {}", e.message)
            emptyList()
        }

    override fun lookupModalOptions(cardGrpId: Int): ModalAbilityInfo? {
        modalCache[cardGrpId]?.let { return it }
        val card = findByGrpId(cardGrpId) ?: return null
        if (card.abilityIds.isEmpty()) return null
        val info = queryModalOptions(card.abilityIds.map { it.first }) ?: return null
        modalCache[cardGrpId] = info
        return info
    }

    override fun findAbilityInfo(abilityGrpId: Int): AbilityInfo? {
        abilityInfoCache[abilityGrpId]?.let { return it.orElse(null) }
        val info = queryAbilityInfo(abilityGrpId)
        abilityInfoCache[abilityGrpId] = java.util.Optional.ofNullable(info)
        return info
    }

    override fun findAbilityLocalization(abilityGrpId: Int): AbilityLocalization? {
        abilityLocalizationCache[abilityGrpId]?.let { return it.orElse(null) }
        val localization = queryAbilityLocalization(abilityGrpId)
        abilityLocalizationCache[abilityGrpId] = java.util.Optional.ofNullable(localization)
        return localization
    }

    private fun queryAbilityLocalization(abilityGrpId: Int): AbilityLocalization? =
        try {
            transaction(database) {
                Abilities
                    .join(Localizations, JoinType.INNER, Abilities.textId, Localizations.locId)
                    .selectAll()
                    .where { (Abilities.id eq abilityGrpId) and (Localizations.formatted eq 1) }
                    .firstOrNull()
                    ?.let { row ->
                        AbilityLocalization(
                            text = row[Localizations.loc],
                            manaCost = parseManaCost(row[Abilities.oldSchoolManaText]),
                        )
                    }
            }
        } catch (e: Exception) {
            log.warn("Failed to query ability localization for id={}: {}", abilityGrpId, e.message)
            null
        }

    private fun queryAbilityInfo(abilityGrpId: Int): AbilityInfo? =
        try {
            transaction(database) {
                Abilities
                    .selectAll()
                    .where { Abilities.id eq abilityGrpId }
                    .firstOrNull()
                    ?.let { row ->
                        AbilityInfo(
                            baseId = row[Abilities.baseId],
                            manaCost = parseManaCost(row[Abilities.oldSchoolManaText]),
                            category = row[Abilities.category],
                            subCategory = row[Abilities.subCategory],
                        )
                    }
            }
        } catch (e: Exception) {
            log.warn("Failed to query Abilities row for id={}: {}", abilityGrpId, e.message)
            null
        }

    private fun queryModalOptions(abilityGrpIds: List<Int>): ModalAbilityInfo? =
        try {
            transaction(database) {
                for (abilityId in abilityGrpIds) {
                    val row = Abilities.selectAll().where { Abilities.id eq abilityId }.firstOrNull() ?: continue
                    val modalChildren = row[Abilities.modalChildIds] ?: continue
                    if (modalChildren.isBlank()) continue
                    val childIds = modalChildren.split(",").mapNotNull { it.trim().toIntOrNull() }
                    if (childIds.isNotEmpty()) {
                        return@transaction ModalAbilityInfo(parentGrpId = abilityId, childGrpIds = childIds)
                    }
                }
                null
            }
        } catch (e: Exception) {
            log.warn("Failed to query modal options for abilities: {}", e.message)
            null
        }

    // --- Queries ---

    private fun queryCardData(grpId: Int): CardData? =
        try {
            transaction(database) {
                Cards.selectAll().where { Cards.grpId eq grpId }.firstOrNull()?.let { row ->
                    val abilityIds = parseAbilityIds(row[Cards.abilityIds])
                    val abilityKinds = lookupAbilityKinds(abilityIds.map { it.first })
                    val abilityCategories = lookupAbilityCategories(abilityIds.map { it.first })
                    CardData(
                        grpId = row[Cards.grpId],
                        titleId = row[Cards.titleId],
                        power = row[Cards.power],
                        toughness = row[Cards.toughness],
                        colors = parseIntList(row[Cards.colors]),
                        types = parseIntList(row[Cards.types]),
                        subtypes = parseIntList(row[Cards.subtypes]),
                        supertypes = parseIntList(row[Cards.supertypes]),
                        abilityIds = abilityIds,
                        abilityKinds = abilityKinds,
                        abilityCategories = abilityCategories,
                        manaCost = parseManaCost(row[Cards.oldSchoolManaText]),
                        tokenGrpIds = parseTokenGrpIds(row[Cards.abilityIdToLinkedTokenGrpId]),
                        hiddenAbilityIds = parseAbilityIds(row[Cards.hiddenAbilityIds]),
                        linkedFaceType = row[Cards.linkedFaceType],
                        linkedFaceGrpIds = parseIntList(row[Cards.linkedFaceGrpIds]),
                    )
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to query card DB for grpId={}: {}", grpId, e.message)
            null
        }

    /**
     * Resolve Arena ability ids to per-slot [SlotKind] using `Abilities`
     * metadata. Category=1/SubCategory=1 is a mana ability; Category=1 is a
     * non-mana activation; everything else is intrinsic. Both a missing row
     * and Category=0 collapse to Activated to preserve the legacy treatment
     * for unknown synthetic rows.
     */
    private fun lookupAbilityKinds(ids: List<Int>): List<SlotKind> {
        if (ids.isEmpty()) return emptyList()
        val distinctIds = ids.distinct()
        val kinds = mutableMapOf<Int, SlotKind>()
        for (id in distinctIds) {
            val row = Abilities.selectAll().where { Abilities.id eq id }.firstOrNull() ?: continue
            kinds[id] = SlotKind.fromAbilityInfo(row[Abilities.category], row[Abilities.subCategory])
        }
        return ids.map { id -> kinds[id] ?: SlotKind.fromCategory(null) }
    }

    private fun lookupAbilityCategories(ids: List<Int>): List<Int> {
        if (ids.isEmpty()) return emptyList()
        val categories = mutableMapOf<Int, Int>()
        for (id in ids.distinct()) {
            val row = Abilities.selectAll().where { Abilities.id eq id }.firstOrNull() ?: continue
            categories[id] = row[Abilities.category]
        }
        return ids.map { categories[it] ?: 0 }
    }

    private fun queryNameByGrpId(grpId: Int): String? =
        try {
            transaction(database) {
                // Join Cards with Localizations on TitleId=LocId, Formatted=1
                Cards
                    .join(Localizations, JoinType.INNER, Cards.titleId, Localizations.locId)
                    .selectAll()
                    .where { (Cards.grpId eq grpId) and (Localizations.formatted eq 1) }
                    .firstOrNull()
                    ?.get(Localizations.loc)
                    ?.let(::stripTags)
                    ?.let(::forgeName)
            }
        } catch (e: Exception) {
            log.warn("Failed to query name for grpId={}: {}", grpId, e.message)
            null
        }

    /**
     * Match card name against Loc, tolerating `<nobr>` tags and the Arena
     * aftermath separator so names returned to Forge also resolve back to IDs.
     */
    private fun locMatches(cardName: String) =
        (Localizations.loc eq cardName) or
            (
                CustomFunction<String>(
                    "REPLACE",
                    TextColumnType(),
                    CustomFunction<String>(
                        "REPLACE",
                        TextColumnType(),
                        CustomFunction<String>(
                            "REPLACE",
                            TextColumnType(),
                            Localizations.loc,
                            stringLiteral("<nobr>"),
                            stringLiteral(""),
                        ),
                        stringLiteral("</nobr>"),
                        stringLiteral(""),
                    ),
                    stringLiteral(" /// "),
                    stringLiteral(" // "),
                ) eq forgeName(cardName)
            )

    private fun queryGrpIdByNameAndSet(
        cardName: String,
        setCode: String,
    ): Int? =
        try {
            transaction(database) {
                Cards
                    .join(Localizations, JoinType.INNER, Cards.titleId, Localizations.locId)
                    .selectAll()
                    .where {
                        (Localizations.formatted eq 1) and
                            locMatches(cardName) and
                            (Cards.expansionCode eq setCode) and
                            (Cards.isToken eq 0)
                    }
                    // An explicit name + set uniquely identifies a printing, so
                    // we don't require IsPrimaryCard here: Universes Within sets
                    // mark every printing non-primary (the linked base printing
                    // in another set carries IsPrimaryCard=1 under a different
                    // name). Still prefer a primary printing when one exists in
                    // the set, then order deterministically.
                    .orderBy(Cards.isPrimaryCard, order = SortOrder.DESC)
                    .orderBy(Cards.isDigitalOnly)
                    .orderBy(Cards.isRebalanced)
                    .orderBy(Cards.grpId, order = SortOrder.DESC)
                    .firstOrNull()
                    ?.get(Cards.grpId)
            }
        } catch (e: Exception) {
            log.warn("Failed to query grpId for name='{}' set='{}': {}", cardName, setCode, e.message)
            null
        }

    private fun queryGrpIdByName(cardName: String): Int? =
        try {
            transaction(database) {
                Cards
                    .join(Localizations, JoinType.INNER, Cards.titleId, Localizations.locId)
                    .selectAll()
                    .where {
                        (Localizations.formatted eq 1) and
                            locMatches(cardName) and
                            (Cards.isToken eq 0) and
                            (Cards.isPrimaryCard eq 1)
                    }.orderBy(Cards.isDigitalOnly)
                    .orderBy(Cards.isRebalanced)
                    .orderBy(Cards.grpId, order = SortOrder.DESC)
                    .firstOrNull()
                    ?.get(Cards.grpId)
            }
        } catch (e: Exception) {
            log.warn("Failed to query grpId for name='{}': {}", cardName, e.message)
            null
        }

    /** Like [queryGrpIdByName] but without isPrimaryCard filter — finds adventure/DFC back faces. */
    private fun queryGrpIdByNameAnyFace(cardName: String): Int? =
        try {
            transaction(database) {
                Cards
                    .join(Localizations, JoinType.INNER, Cards.titleId, Localizations.locId)
                    .selectAll()
                    .where {
                        (Localizations.formatted eq 1) and
                            locMatches(cardName) and
                            (Cards.isToken eq 0)
                    }.orderBy(Cards.isDigitalOnly)
                    .orderBy(Cards.isRebalanced)
                    .orderBy(Cards.grpId, order = SortOrder.DESC)
                    .firstOrNull()
                    ?.get(Cards.grpId)
            }
        } catch (e: Exception) {
            log.warn("Failed to query grpId (any face) for name='{}': {}", cardName, e.message)
            null
        }

    private fun queryTokenGrpIdByName(cardName: String): Int? =
        try {
            val normalizedName = cardName.removeSuffix(" Token")
            transaction(database) {
                Cards
                    .join(Localizations, JoinType.INNER, Cards.titleId, Localizations.locId)
                    .selectAll()
                    .where {
                        (Localizations.formatted eq 1) and
                            (locMatches(cardName) or locMatches(normalizedName)) and
                            (Cards.isToken eq 1)
                    }.orderBy(Cards.isDigitalOnly)
                    .orderBy(Cards.isRebalanced)
                    .orderBy(Cards.grpId, order = SortOrder.DESC)
                    .firstOrNull()
                    ?.get(Cards.grpId)
            }
        } catch (e: Exception) {
            log.warn("Failed to query token grpId for name='{}': {}", cardName, e.message)
            null
        }
}
