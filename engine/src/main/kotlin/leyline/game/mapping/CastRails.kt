package leyline.game.mapping

import forge.game.spellability.AlternativeCost
import forge.game.spellability.OptionalCost
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.AltCostBinding
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Declarative table of cast-rail descriptors. Each row describes one keyword's
 * cast-pathway [wotc.mtgo.gre.external.messaging.Messages.Action] field
 * configuration — the mapping from `(source zone, BoundCard's alt-cost row,
 * live SA)` to the Action proto fields the client expects.
 *
 * Three rail buckets, keyed on source zone:
 *  - [HandWithAltCost] — cast offer originates from the player's hand. Warp,
 *    Sneak, Plot's hand SA, Foretell's hand SA, Impending.
 *  - [FromExile] — cast offer originates from the exile zone. Plot's cast leg
 *    (universal-149) and Foretell's cast leg (per-card row).
 *  - [FromGraveyard] — cast offer originates from a graveyard zone. Flashback,
 *    Disturb, Escape, Jump-start.
 *
 * `ActionMapper.addZoneCastActionsFromSnap` iterates the zone-cast buckets;
 * `ActionMapper.addHandAltCostCastActions` iterates the hand bucket and binds
 * each projected action to the matching candidate.
 *
 * Adding a new alt-cost keyword:
 *  1. Add an [AltCostKind] entry with the keyword's `KeywordAbilityIds`
 *     constant.
 *  2. Add one or more rail rows (Hand / Exile / Graveyard) below.
 *  3. Confirm with the relevant fixture under `data/puzzles/`.
 *
 * Madness has its own rail (`OptionalActionMessage`, not the Cast offer) and
 * is intentionally **not** in this table.
 */

/**
 * Identity of an alt-cost keyword. Each value carries the keyword's BaseId
 * chain root — the dedup key into [AltCostBinding.keywordBaseId] and the
 * `findAbilityInfo(...).baseId` returned by the card repository for any
 * per-printing alt-cost ability row.
 */
enum class AltCostKind(
    val keywordBaseId: Int,
) {
    PLOT(KeywordAbilityIds.PLOT),
    FORETELL(KeywordAbilityIds.FORETELL),
    FLASHBACK(KeywordAbilityIds.FLASHBACK),
    RETRACE(KeywordAbilityIds.RETRACE),
    DISTURB(KeywordAbilityIds.DISTURB),
    ESCAPE(KeywordAbilityIds.ESCAPE),
    MUTATE(KeywordAbilityIds.MUTATE),
    JUMP_START(KeywordAbilityIds.JUMP_START),
    IMPENDING(KeywordAbilityIds.IMPENDING),
    CLEAVE(KeywordAbilityIds.CLEAVE),
    EVOKE(KeywordAbilityIds.EVOKE),
    BLITZ(KeywordAbilityIds.BLITZ),
    DASH(KeywordAbilityIds.DASH),
    OVERLOAD(KeywordAbilityIds.OVERLOAD),
    EMERGE(KeywordAbilityIds.EMERGE),
    SPECTACLE(KeywordAbilityIds.SPECTACLE),
    SURGE(KeywordAbilityIds.SURGE),
    HARMONIZE(KeywordAbilityIds.HARMONIZE),
    WARP(KeywordAbilityIds.WARP),
    SNEAK(KeywordAbilityIds.SNEAK),
    WEB_SLINGING(KeywordAbilityIds.WEB_SLINGING),
    DISGUISE(KeywordAbilityIds.DISGUISE),
    PARADIGM(KeywordAbilityIds.PARADIGM),
    AIRBEND(KeywordAbilityIds.AIRBEND),
}

/**
 * How to disambiguate among an [AltCostBinding]s list when more than one row
 * could match. Cost-aware compares mana cost (Warp / Sneak share rows across
 * cards). Cost-agnostic returns the first row with the matching keyword —
 * Foretell needs this because the hand SA pays the constant {2} action cost
 * while the bound row carries the cast cost ({R} for Demon Bolt), so cost-
 * aware misses.
 */
enum class LookupMode {
    CostAware,
    CostAgnostic,
}

/** Source for an action's `alternativeGrpId` field. */
sealed interface AltGrpIdSource {
    /** Fixed universal-149 ("Cast without paying mana cost"). Plot's exile-cast leg. */
    data object Universal149 : AltGrpIdSource

    /** Per-card row from [AltCostBinding] list, keyed by [AltCostKind]. */
    data class FromBoundCard(
        val lookupMode: LookupMode = LookupMode.CostAware,
    ) : AltGrpIdSource

    /** Fixed keyword row for effect-granted zone casts whose source card has no alt-cost row. */
    data class Fixed(
        val abilityGrpId: Int,
    ) : AltGrpIdSource
}

/** Source for an action's `abilityGrpId` field. */
sealed interface AbilityGrpIdMode {
    /** Don't set abilityGrpId. Foretell's exile-cast leaves it 0. */
    data object None : AbilityGrpIdMode

    /** Set to a fixed keyword BaseId. Plot's exile-cast emits abilityGrpId = PLOT (328). */
    data class FixedKeyword(
        val baseId: Int,
    ) : AbilityGrpIdMode

    /** Echo whatever resolved alternativeGrpId is. Disturb, Escape. */
    data object EchoAlternative : AbilityGrpIdMode
}

/**
 * Common interface — every rail can be queried for SA-matching. The predicate
 * tests an SA produced by `getAllCastableAbilities` for "is this the SA the
 * action's `alternativeGrpId` referred to?". Used both for action emission
 * (find-the-SA-for-this-rail) and ability-index resolution
 * (find-the-rail-for-this-SA).
 */
sealed interface CastRail {
    val kind: AltCostKind
    val saPredicate: (SpellAbility) -> Boolean
}

/** Sub-interface for rails whose source zone is non-hand. Carries the shared
 *  zone-cast Action-field configuration. */
sealed interface ZoneCastRail : CastRail {
    val altGrpIdSource: AltGrpIdSource
    val abilityGrpIdMode: AbilityGrpIdMode
    val emitManaCost: Boolean
    val echoAlternativeOnMana: Boolean
    val emitAlternativeSourceZcid: Boolean
    val grpIdMode: ZoneCastGrpIdMode

    /** When true, the action omits `grpId` and `facetId` (Plot, Foretold, Escape pattern). */
    val omitGrpIdAndFacetId: Boolean
}

enum class ZoneCastGrpIdMode {
    Source,
    OtherSide,
}

/**
 * Hand-zone alt-cost rail. Action fields: instanceId + grpId + facetId + alt
 * grpId + mana cost (with alt grpId echoed on each ManaRequirement).
 */
data class HandWithAltCost(
    override val kind: AltCostKind,
    override val saPredicate: (SpellAbility) -> Boolean,
    val lookupMode: LookupMode,
    /**
     * When true, the action's `alternativeGrpId` is set to the keyword's
     * BaseId constant ([AltCostKind.keywordBaseId]) directly, not the
     * per-card row's `abilityGrpId`. Disguise's face-down hand cast emits
     * `alternativeGrpId = 307` (DISGUISE BaseId), not the per-card
     * disguise-up activator id — the per-card row drives the
     * Special_TurnFaceUp action, the BaseId anchors the face-down cast.
     *
     * When false (default), the per-card row's `abilityGrpId` is used —
     * Plot / Foretell / Warp / Sneak hand-cast pattern.
     */
    val useKeywordBaseIdAsAlternative: Boolean = false,
) : CastRail

/**
 * Exile-zone alt-cost rail. grpId/facetId omitted by default (both current
 * rows omit). Universal-149 lands as a fixed alternativeGrpId; per-card rows
 * read from [AltCostBinding].
 */
data class FromExile(
    override val kind: AltCostKind,
    override val saPredicate: (SpellAbility) -> Boolean,
    override val altGrpIdSource: AltGrpIdSource,
    override val abilityGrpIdMode: AbilityGrpIdMode,
    override val emitManaCost: Boolean,
    override val echoAlternativeOnMana: Boolean,
    override val emitAlternativeSourceZcid: Boolean = false,
    override val grpIdMode: ZoneCastGrpIdMode = ZoneCastGrpIdMode.Source,
    override val omitGrpIdAndFacetId: Boolean = true,
) : ZoneCastRail

/**
 * Graveyard-zone alt-cost rail. Defaults reflect the dominant Disturb /
 * Escape pattern: cost-agnostic per-card lookup, abilityGrpId echoes
 * alternativeGrpId, mana cost emitted.
 */
data class FromGraveyard(
    override val kind: AltCostKind,
    override val saPredicate: (SpellAbility) -> Boolean,
    override val altGrpIdSource: AltGrpIdSource = AltGrpIdSource.FromBoundCard(LookupMode.CostAgnostic),
    override val abilityGrpIdMode: AbilityGrpIdMode = AbilityGrpIdMode.EchoAlternative,
    override val emitManaCost: Boolean = true,
    override val echoAlternativeOnMana: Boolean,
    override val emitAlternativeSourceZcid: Boolean,
    override val grpIdMode: ZoneCastGrpIdMode = ZoneCastGrpIdMode.Source,
    override val omitGrpIdAndFacetId: Boolean,
) : ZoneCastRail

object CastRails {
    val fromExile: List<FromExile> =
        listOf(
            FromExile(
                kind = AltCostKind.PLOT,
                saPredicate = { it.alternativeCost == AlternativeCost.Plotted },
                altGrpIdSource = AltGrpIdSource.Universal149,
                abilityGrpIdMode = AbilityGrpIdMode.FixedKeyword(KeywordAbilityIds.PLOT),
                emitManaCost = false,
                echoAlternativeOnMana = false,
            ),
            FromExile(
                kind = AltCostKind.FORETELL,
                saPredicate = { it.alternativeCost == AlternativeCost.Foretold },
                altGrpIdSource = AltGrpIdSource.FromBoundCard(LookupMode.CostAgnostic),
                abilityGrpIdMode = AbilityGrpIdMode.None,
                emitManaCost = true,
                echoAlternativeOnMana = true,
            ),
            FromExile(
                kind = AltCostKind.PARADIGM,
                saPredicate = { sa ->
                    val host = sa.hostCard
                    sa.isCastFromPlayEffect &&
                        sa.hasParam("WithoutManaCost") &&
                        host?.isToken == true &&
                        host.copiedPermanent?.hasKeyword("Paradigm") == true
                },
                altGrpIdSource = AltGrpIdSource.Universal149,
                abilityGrpIdMode = AbilityGrpIdMode.FixedKeyword(KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER),
                emitManaCost = false,
                echoAlternativeOnMana = false,
            ),
            FromExile(
                kind = AltCostKind.AIRBEND,
                saPredicate = ::isAirbendMayPlay,
                altGrpIdSource = AltGrpIdSource.Fixed(KeywordAbilityIds.AIRBEND),
                abilityGrpIdMode = AbilityGrpIdMode.FixedKeyword(KeywordAbilityIds.AIRBEND),
                emitManaCost = true,
                echoAlternativeOnMana = true,
            ),
        )

    val fromGraveyard: List<FromGraveyard> =
        listOf(
            FromGraveyard(
                kind = AltCostKind.FLASHBACK,
                saPredicate = { it.alternativeCost == AlternativeCost.Flashback },
                abilityGrpIdMode = AbilityGrpIdMode.None,
                echoAlternativeOnMana = true,
                emitAlternativeSourceZcid = true,
                omitGrpIdAndFacetId = false,
            ),
            FromGraveyard(
                kind = AltCostKind.DISTURB,
                saPredicate = { it.alternativeCost == AlternativeCost.Disturb },
                echoAlternativeOnMana = true,
                emitAlternativeSourceZcid = true,
                grpIdMode = ZoneCastGrpIdMode.OtherSide,
                omitGrpIdAndFacetId = false,
            ),
            FromGraveyard(
                kind = AltCostKind.ESCAPE,
                saPredicate = { it.alternativeCost == AlternativeCost.Escape },
                echoAlternativeOnMana = true,
                emitAlternativeSourceZcid = false,
                omitGrpIdAndFacetId = true,
            ),
            FromGraveyard(
                kind = AltCostKind.JUMP_START,
                saPredicate = { it.isJumpstart },
                echoAlternativeOnMana = true,
                emitAlternativeSourceZcid = true,
                omitGrpIdAndFacetId = false,
            ),
            FromGraveyard(
                kind = AltCostKind.RETRACE,
                saPredicate = { it.isOptionalCostPaid(OptionalCost.Retrace) },
                abilityGrpIdMode = AbilityGrpIdMode.None,
                echoAlternativeOnMana = true,
                emitAlternativeSourceZcid = true,
                omitGrpIdAndFacetId = false,
            ),
            FromGraveyard(
                kind = AltCostKind.HARMONIZE,
                saPredicate = { it.alternativeCost?.name == "Harmonize" },
                abilityGrpIdMode = AbilityGrpIdMode.None,
                echoAlternativeOnMana = true,
                emitAlternativeSourceZcid = true,
                omitGrpIdAndFacetId = false,
            ),
        )

    val handWithAltCost: List<HandWithAltCost> =
        listOf(
            HandWithAltCost(
                kind = AltCostKind.WARP,
                saPredicate = { it.alternativeCost == AlternativeCost.Warp },
                lookupMode = LookupMode.CostAware,
            ),
            HandWithAltCost(
                kind = AltCostKind.SNEAK,
                saPredicate = { it.alternativeCost == AlternativeCost.Sneak },
                lookupMode = LookupMode.CostAware,
            ),
            HandWithAltCost(
                kind = AltCostKind.WEB_SLINGING,
                saPredicate = { it.alternativeCost == AlternativeCost.WebSlinging },
                lookupMode = LookupMode.CostAware,
            ),
            HandWithAltCost(
                kind = AltCostKind.MUTATE,
                saPredicate = { it.alternativeCost == AlternativeCost.Mutate },
                lookupMode = LookupMode.CostAgnostic,
            ),
            HandWithAltCost(
                kind = AltCostKind.PLOT,
                saPredicate = { it.isPlotting },
                lookupMode = LookupMode.CostAware,
            ),
            HandWithAltCost(
                kind = AltCostKind.FORETELL,
                saPredicate = { it.isForetelling },
                lookupMode = LookupMode.CostAgnostic,
            ),
            // Disguise's face-down hand cast is a Forge `Spell` with
            // `setCastFaceDown(true)`; there is no `AlternativeCost.Disguise`
            // enum entry. The SA carries the fixed {3} morph-down cost
            // returned by `abilityCastFaceDown(...)`. Cost-aware lookup
            // matches the bound row whose printed mana cost is the {3}
            // morph-down stencil.
            HandWithAltCost(
                kind = AltCostKind.DISGUISE,
                saPredicate = { it.isCastFaceDown },
                lookupMode = LookupMode.CostAgnostic,
                useKeywordBaseIdAsAlternative = true,
            ),
            HandWithAltCost(
                kind = AltCostKind.CLEAVE,
                saPredicate = { it.hasParam("PrecostDesc") && it.getParam("PrecostDesc") == "Cleave" },
                lookupMode = LookupMode.CostAware,
            ),
            HandWithAltCost(
                kind = AltCostKind.OVERLOAD,
                saPredicate = { it.alternativeCost == AlternativeCost.Overload },
                lookupMode = LookupMode.CostAgnostic,
            ),
            HandWithAltCost(
                kind = AltCostKind.EVOKE,
                saPredicate = { it.alternativeCost == AlternativeCost.Evoke },
                lookupMode = LookupMode.CostAgnostic,
            ),
            HandWithAltCost(
                kind = AltCostKind.BLITZ,
                saPredicate = { it.alternativeCost == AlternativeCost.Blitz },
                lookupMode = LookupMode.CostAgnostic,
            ),
            HandWithAltCost(
                kind = AltCostKind.DASH,
                saPredicate = { it.alternativeCost == AlternativeCost.Dash },
                lookupMode = LookupMode.CostAgnostic,
            ),
            HandWithAltCost(
                kind = AltCostKind.EMERGE,
                saPredicate = { it.alternativeCost == AlternativeCost.Emerge },
                lookupMode = LookupMode.CostAgnostic,
            ),
            HandWithAltCost(
                kind = AltCostKind.SPECTACLE,
                saPredicate = { it.alternativeCost == AlternativeCost.Spectacle },
                lookupMode = LookupMode.CostAgnostic,
            ),
            HandWithAltCost(
                kind = AltCostKind.SURGE,
                saPredicate = { it.alternativeCost == AlternativeCost.Surge },
                lookupMode = LookupMode.CostAgnostic,
            ),
            HandWithAltCost(
                kind = AltCostKind.IMPENDING,
                saPredicate = { it.isImpending },
                lookupMode = LookupMode.CostAgnostic,
            ),
        )

    /** All cast rails across supported source zones. */
    val all: List<CastRail> = fromExile + fromGraveyard + handWithAltCost
}

/**
 * Resolve the per-card alt-cost ability grpId for a given rail. Returns 0
 * when no row matches; callers either skip the offer (zone path) or the
 * action (hand path).
 *
 * For [HandWithAltCost], [payCostPairs] is the SA's effective mana cost (used
 * by cost-aware lookups). For [ZoneCastRail], the rail's
 * [ZoneCastRail.altGrpIdSource] decides whether [payCostPairs] gets consulted
 * — Universal149 ignores it, FromBoundCard with CostAware uses it, with
 * CostAgnostic ignores it.
 */
internal fun resolveAltGrpId(
    rail: CastRail,
    altCosts: List<AltCostBinding>,
    payCostPairs: List<Pair<ManaColor, Int>>,
): Int =
    when (rail) {
        is HandWithAltCost ->
            if (rail.useKeywordBaseIdAsAlternative) {
                rail.kind.keywordBaseId
            } else {
                resolveByMode(altCosts, rail.kind, rail.lookupMode, payCostPairs)
            }
        is ZoneCastRail ->
            when (val src = rail.altGrpIdSource) {
                AltGrpIdSource.Universal149 -> 149
                is AltGrpIdSource.FromBoundCard -> resolveByMode(altCosts, rail.kind, src.lookupMode, payCostPairs)
                is AltGrpIdSource.Fixed -> src.abilityGrpId
            }
    }

private fun resolveByMode(
    altCosts: List<AltCostBinding>,
    kind: AltCostKind,
    mode: LookupMode,
    payCostPairs: List<Pair<ManaColor, Int>>,
): Int =
    when (mode) {
        LookupMode.CostAgnostic ->
            altCosts.firstOrNull { it.keywordBaseId == kind.keywordBaseId }?.abilityGrpId ?: 0
        LookupMode.CostAware ->
            altCosts
                .firstOrNull {
                    it.keywordBaseId == kind.keywordBaseId && it.manaCost.toMap() == payCostPairs.toMap()
                }?.abilityGrpId ?: 0
    }

private fun isAirbendMayPlay(sa: SpellAbility): Boolean {
    val mayPlay = sa.mayPlay ?: return false
    return sa.hostCard?.isInZone(ZoneType.Exile) == true &&
        mayPlay.hostCard?.name?.startsWith("Airbend ") == true &&
        mayPlay.hasParam("MayPlayAltManaCost") &&
        mayPlay.getParam("MayPlayAltManaCost") == "2"
}
