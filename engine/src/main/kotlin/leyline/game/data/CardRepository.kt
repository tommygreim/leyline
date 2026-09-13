package leyline.game.data

import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import kotlin.collections.iterator

/**
 * Read-only card metadata used by GRE projection and embedding hosts.
 *
 * [SqliteCardRepository] preserves native-client identities from [ClientCardDatabase].
 * [ForgeCardRepository] provides catalog-scoped identities from Forge definitions.
 * Tests use [leyline.game.InMemoryCardRepository] with fixture data.
 */
interface CardRepository {
    fun findByGrpId(grpId: Int): CardData?

    fun findNameByGrpId(grpId: Int): String?

    fun findGrpIdByName(name: String): Int?

    /** Deck-entry lookup. Repositories may accept exact catalog aliases while returning the deck-legal parent. */
    fun findDeckGrpIdByName(name: String): Int? = findGrpIdByName(name)

    fun findDeckGrpIdByNameAndSet(
        name: String,
        setCode: String,
    ): Int? = findGrpIdByNameAndSet(name, setCode) ?: findDeckGrpIdByName(name)

    /** Names that identify one primary card in catalog search. */
    fun findNamesByGrpId(grpId: Int): List<String> = listOfNotNull(findNameByGrpId(grpId))

    /** Like [findGrpIdByName] but includes secondary faces and derived forms. */
    fun findGrpIdByNameAnyFace(name: String): Int? = findGrpIdByName(name)

    /** Token-only name lookup. Forge often appends " Token" to the DB display name. */
    fun findTokenGrpIdByName(name: String): Int? = null

    /** Exact token-script lookup for repositories whose token identities are definition-backed. */
    fun findTokenGrpIdByScript(script: String): Int? = null

    fun findGrpIdByNameAndSet(
        name: String,
        setCode: String,
    ): Int? = findGrpIdByName(name)

    /** All non-token, primary-card grpIds in the database. */
    fun findAllGrpIds(): List<Int>

    /**
     * Look up modal options for a card. Returns the parent ability grpId
     * and list of child option grpIds from the Abilities table's ModalChildIds column.
     * Returns null if the card has no modal abilities.
     */
    fun lookupModalOptions(cardGrpId: Int): ModalAbilityInfo? = null

    /** Linked face grpIds for multi-face cards and derived forms. */
    fun findLinkedFaces(grpId: Int): List<Int> = findByGrpId(grpId)?.linkedFaceGrpIds ?: emptyList()

    /**
     * Look up one ability's GRE metadata.
     *
     * Returns null if the repository does not carry ability data or the row is absent.
     */
    fun findAbilityInfo(abilityGrpId: Int): AbilityInfo? = null

    /** Raw localized text and owned cost metadata for one ability row. */
    fun findAbilityLocalization(abilityGrpId: Int): AbilityLocalization? = null

    /**
     * Keyword presence lookup. [keywordAbilityId] is one of the well-known
     * ability identifiers from [KeywordAbilityIds]. Returns the per-card
     * ability grpId that represents the keyword, or null when the card
     * doesn't carry it.
     *
     * Two shapes are checked, in order:
     * 1. **Direct match**: the well-known id appears verbatim in
     *    `card.abilityIds`. Used for cost-uniform keywords (Prowess, Haste,
     *    etc.) where every card with the keyword references the same shared
     *    ability id.
     * 2. **BaseId chain**: an ability on the card has `BaseId =
     *    keywordAbilityId`. Used for alt-cost keywords (Warp, Sneak,
     *    Flashback, Madness) where each printing has its own ability row
     *    with a per-printing mana cost, all chained to the keyword's
     *    well-known base id. Checked against both [CardData.abilityIds] and
     *    [CardData.hiddenAbilityIds] — a keyword granted onto other cards by
     *    a static ability (e.g. The Necrobloom's "land cards in your
     *    graveyard have dredge 2") carries its per-amount ability row
     *    (`AddKeyword$ Dredge:2`'s "Dredge 2") on the granting card's own
     *    hidden abilities, not the affected card's own printed data.
     */
    fun findKeywordAbilityGrpId(
        cardGrpId: Int,
        keywordAbilityId: Int,
    ): Int? {
        val data = findByGrpId(cardGrpId) ?: return null
        // Direct match
        for ((abilityGrpId, _) in data.abilityIds) {
            if (abilityGrpId == keywordAbilityId) return abilityGrpId
        }
        // BaseId chain
        for ((abilityGrpId, _) in data.abilityIds + data.hiddenAbilityIds) {
            val info = findAbilityInfo(abilityGrpId) ?: continue
            if (info.baseId == keywordAbilityId) return abilityGrpId
        }
        return null
    }

    /**
     * Hidden ability grpId of the first **triggered** ability
     * ([Abilities.Category] == 2) on [cardGrpId]. Robust against cards with
     * multiple hidden entries where the cleanup row isn't first — e.g.
     * Zurgo, Thunder's Decree pairs its Mobilize cleanup (Category=2,
     * id=189933) with a static "can't be sacrificed" (Category=3, id=188976);
     * filtering on category picks the cleanup deterministically regardless
     * of ordering.
     *
     * Returns null if no candidate has [AbilityInfo.category] == 2 — every
     * production source carries Category, and tests must register matching
     * [AbilityInfo] for any hidden cleanup id they exercise.
     */
    fun findHiddenTriggeredAbilityGrpId(cardGrpId: Int): Int? {
        val data = findByGrpId(cardGrpId) ?: return null
        for ((abilityGrpId, _) in data.hiddenAbilityIds) {
            val info = findAbilityInfo(abilityGrpId) ?: continue
            if (info.category == 2) return abilityGrpId
        }
        return null
    }

    /**
     * Token grpId produced by [sourceGrpId].
     * Single token -> returns directly. Multiple -> matches by [tokenName].
     */
    fun tokenGrpIdForCard(
        sourceGrpId: Int,
        tokenName: String? = null,
    ): Int? {
        val data = findByGrpId(sourceGrpId) ?: return null
        val tokens = data.tokenGrpIds
        if (tokens.isEmpty()) return null
        if (tokens.size == 1) return tokens.values.first()
        if (tokenName == null) return null
        // Forge names tokens "Rat Token", client DB uses "Rat" — try both
        val normalized = tokenName.removeSuffix(" Token")
        for ((_, tokenGrpId) in tokens) {
            val name = findNameByGrpId(tokenGrpId) ?: continue
            if (name == tokenName || name == normalized) return tokenGrpId
        }
        return null
    }
}

/**
 * Modal ability info: parent ability grpId and list of child option grpIds.
 * Used for CastingTimeOptionsReq (modal ETB, modal cast, etc.).
 */
data class ModalAbilityInfo(
    val parentGrpId: Int,
    val childGrpIds: List<Int>,
)

/**
 * Single row from the client's Abilities table. Minimal fields needed to
 * disambiguate keyword alt-cost rows (Warp, Sneak, …) within a card and to
 * filter hidden delayed-trigger abilities by kind.
 *
 * @param category from the client card-DB `Abilities.Category` column.
 *   Observed values: 1 = Activated, 2 = Triggered, 3 = Static/Replacement,
 *   4 = SpellEffect, 8 = AlternativeCost. Defaults to 0 (unknown) for
 *   synthetic test data and rows the production repo couldn't load.
 */
data class AbilityInfo(
    val baseId: Int,
    val manaCost: List<Pair<ManaColor, Int>>,
    val category: Int = 0,
    val subCategory: Int = 0,
)

data class AbilityLocalization(
    val text: String,
    val manaCost: List<Pair<ManaColor, Int>> = emptyList(),
)

/**
 * Well-known keyword ability identifiers from the client's `Abilities` table.
 *
 * Two shapes mix here, both consumed by [CardRepository.findKeywordAbilityGrpId]:
 *
 * - **Direct ability ids** (Prowess, Haste, …) — the integer is itself the
 *   ability id and appears verbatim in `Cards.AbilityIds` for every card
 *   carrying the keyword. Cost-uniform keywords land here.
 * - **BaseId chain** (Warp, Sneak, Flashback, Madness) — per-printing
 *   ability rows have varying mana costs but share a `BaseId` pointing at
 *   the keyword's definition row. The integer here is that shared base.
 *
 * Extend when a mapper/resolver layer needs to address another keyword.
 * Identify the integer by inspecting a sample card's fixture YAML or
 * querying the client's `Abilities` / `Localizations_enUS` tables.
 */
object KeywordAbilityIds {
    // Direct ability ids — well-known shared row used verbatim per card.
    const val CONVOKE = 52
    const val HASTE = 9
    const val WARD_TWO = 141939
    const val PROWESS = 137
    const val IMPROVISE = 157
    const val TRAINING = 220
    const val ENLIST = 261
    const val STATION = 373
    const val FIREBENDING = 379
    const val TEAMWORK = 412
    const val WATERBEND = 8100003
    const val RECONFIGURE_UNATTACH = 244

    // Payment-action row used by Convoke's mana-substitution MakePayment actions.
    const val CONVOKE_PAYMENT = 172

    // BaseId roots — each printing has its own ability row chaining to this.
    const val KICKER = 34
    const val FLASHBACK = 35
    const val MADNESS = 36

    /**
     * No Arena card prints Dredge directly (Golgari Grave-Troll, Stinkweed
     * Imp, etc. aren't in Arena's card pool). The Necrobloom (MH3) is the
     * only source: its own static ability grants `Dredge:2` to land cards
     * in the graveyard, and that granted keyword's per-amount ability row
     * ("Dredge 2", id 1328) lives in The Necrobloom's own hidden abilities,
     * chained via BaseId to this root (id 53, TextId "Dredge") — verified
     * against Arena's card database, same BaseId-root shape as [MADNESS].
     */
    const val DREDGE = 53
    const val RETRACE = 82
    const val EVOKE = 75
    const val OVERLOAD = 97
    const val EMERGE = 147
    const val JUMP_START = 170
    const val SPECTACLE = 174
    const val SURGE = 356
    const val MENTOR = 171
    const val ESCAPE = 199
    const val MUTATE = 203
    const val FORETELL = 208
    const val DECAYED = 214
    const val DISTURB = 215
    const val CLEAVE = 221
    const val BLITZ = 240
    const val DASH = 274
    const val DISGUISE = 307
    const val CLOAK = 349
    const val MANIFEST_DREAD = 351
    const val IMPENDING = 352
    const val HARMONIZE = 362
    const val PLOT = 328
    const val BACKUP = 287
    const val MOBILIZE = 363
    const val WARP = 371
    const val SNEAK = 394
    const val PARADIGM = 405
    const val RECONFIGURE = 237
    const val AIRBEND = 8100006

    // Triggered ability rows used by delayed keyword effects.
    const val WARP_DELAYED_TRIGGER = 372
    const val PARADIGM_DELAYED_TRIGGER = 205572

    // Single-row shared keyword ability ids (BaseId=0, every card references the same Id).
    const val CASCADE = 86

    /**
     * Resolve a Forge `AlternativeCost.name` (uppercase enum name like
     * `"WARP"`, `"FORETOLD"`, `"PLOTTED"`) to the keyword's ability id.
     * Also accepts designation names (`"PLOTTED"`) and the keyword's bare
     * form (`"PLOT"`) so cast-rail callers and designation-tag callers
     * resolve to the same integer. Returns null when the keyword isn't
     * mapped yet.
     */
    private val FORGE_ALT_COST_KEYWORD_IDS =
        mapOf(
            "WARP" to WARP,
            "SNEAK" to SNEAK,
            "OVERLOAD" to OVERLOAD,
            "EVOKE" to EVOKE,
            "BLITZ" to BLITZ,
            "DASH" to DASH,
            "EMERGE" to EMERGE,
            "SPECTACLE" to SPECTACLE,
            "SURGE" to SURGE,
            "HARMONIZE" to HARMONIZE,
            "JUMPSTART" to JUMP_START,
            "JUMP_START" to JUMP_START,
            "JUMP-START" to JUMP_START,
            "FLASHBACK" to FLASHBACK,
            "MADNESS" to MADNESS,
            "PLOT" to PLOT,
            "PLOTTED" to PLOT,
            "FORETELL" to FORETELL,
            "FORETOLD" to FORETELL,
            "DISTURB" to DISTURB,
            "ESCAPE" to ESCAPE,
            "MUTATE" to MUTATE,
            "CLEAVE" to CLEAVE,
            "IMPENDING" to IMPENDING,
            "MOBILIZE" to MOBILIZE,
            "DISGUISE" to DISGUISE,
            "PARADIGM" to PARADIGM,
        )

    fun fromForgeAltCostName(name: String): Int? = FORGE_ALT_COST_KEYWORD_IDS[name.uppercase()]
}
