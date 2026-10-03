package leyline.game.snapshot

import leyline.bridge.types.ForgeCardId
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.data.KeywordAbilityIds
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Per-frame bound view of one card — pairs the live [CardSnapshot] with the
 * static [CardData] resolved at snapshot time, plus pre-resolved fields that
 * downstream mappers would otherwise reach into `bridge.cardRepository` for.
 *
 *  - [snapshot] holds the live state (zone-affecting fields, P/T, designation
 *    flags).
 *  - [data] holds the static metadata. Null when no DB row exists for the
 *    card's grpId — `EFFECT` engine pieces (grpId=0) and unbound tokens.
 *  - [altCosts] enumerates the alt-cost ability rows the card carries
 *    (Plot, Foretell, Disturb, Escape, Jump-start, Mutate, Impending,
 *    Cleave, Overload, Spectacle, Harmonize, Warp, Sneak, Madness, Flashback, Mobilize). Drives
 *    [leyline.game.mapping.ActionMapper]'s
 *    cast-from-non-hand-zone and hand-alt-cost rails.
 *  - [mobilizeCleanup] is the per-card hidden triggered-ability grpId — the
 *    "Sacrifice them at the next end step" row paired with every Mobilize
 *    keyword. Null for non-Mobilize cards. Drives StateMapper's
 *    TemporaryPermanent + DelayedTriggerAffectees pAnn emission.
 *  - [decayedCleanup] is the hidden EndCombat sacrifice row paired with Decayed.
 *    Null for non-Decayed cards. Drives StateMapper's TemporaryPermanent-only
 *    delayed cleanup marker.
 *  - [parentLinkage] drives `parentId` emission on
 *    [leyline.game.mapping.ObjectMapper]'s GameObject build path. Auras /
 *    Equipment surface as [ParentLinkage.AttachedTo]; prepared-spell exile
 *    copies as [ParentLinkage.PreparedCopy].
 *  - [designations] gathers card-state designations (Prepared, Plotted,
 *    Foretold) so StateMapper's per-designation transient inserters address
 *    one field rather than three independent snapshot reads.
 */
data class BoundCard(
    val forgeCardId: ForgeCardId,
    val snapshot: CardSnapshot,
    val data: CardData?,
    val altCosts: List<AltCostBinding> = emptyList(),
    val mobilizeCleanup: Int? = null,
    val decayedCleanup: Int? = null,
    val parentLinkage: ParentLinkage? = null,
    val designations: DesignationSet = DesignationSet(),
    val linkedFaces: List<LinkedFaceDescriptor> = emptyList(),
) {
    /**
     * Find the alt-cost row whose [AltCostBinding.keywordBaseId] matches
     * [keywordBaseId]. Cost-agnostic — useful when a card has at most one
     * row for the keyword (Foretell, Disturb, Escape, Plot's exile-cast leg).
     */
    fun altCost(keywordBaseId: Int): AltCostBinding? = altCosts.firstOrNull { it.keywordBaseId == keywordBaseId }

    /**
     * Find the alt-cost row whose [AltCostBinding.keywordBaseId] matches
     * [keywordBaseId] AND whose [AltCostBinding.manaCost] equals [manaCost]
     * (multiset-equal). Cost-aware — needed when multiple rows share a base
     * (Warp / Sneak shared ability rows across cards) and the consumer's
     * SA cost picks one.
     */
    fun altCostFor(
        keywordBaseId: Int,
        manaCost: List<Pair<ManaColor, Int>>,
    ): AltCostBinding? =
        altCosts.firstOrNull {
            it.keywordBaseId == keywordBaseId && it.manaCost.toMap() == manaCost.toMap()
        }

    companion object {
        /**
         * BaseId chain roots that identify alt-cost ability rows on a card.
         * Each appears as the `BaseId` of at most one ability row per printing
         * (Warp/Sneak/Plot/Foretell/Disturb/Escape/Jump-start/Mutate/
         * Impending/Cleave/Evoke/Blitz/Dash/Overload/Emerge/Spectacle/Surge/Harmonize/Madness/Flashback/Retrace/Mobilize).
         */
        private val ALT_COST_BASE_IDS: Set<Int> =
            setOf(
                KeywordAbilityIds.FLASHBACK,
                KeywordAbilityIds.RETRACE,
                KeywordAbilityIds.MADNESS,
                KeywordAbilityIds.ESCAPE,
                KeywordAbilityIds.MUTATE,
                KeywordAbilityIds.FORETELL,
                KeywordAbilityIds.DISTURB,
                KeywordAbilityIds.JUMP_START,
                KeywordAbilityIds.IMPENDING,
                KeywordAbilityIds.CLEAVE,
                KeywordAbilityIds.EVOKE,
                KeywordAbilityIds.BLITZ,
                KeywordAbilityIds.DASH,
                KeywordAbilityIds.OVERLOAD,
                KeywordAbilityIds.EMERGE,
                KeywordAbilityIds.SPECTACLE,
                KeywordAbilityIds.SURGE,
                KeywordAbilityIds.HARMONIZE,
                KeywordAbilityIds.PLOT,
                KeywordAbilityIds.MOBILIZE,
                KeywordAbilityIds.WARP,
                KeywordAbilityIds.SNEAK,
                KeywordAbilityIds.WEB_SLINGING,
                KeywordAbilityIds.DISGUISE,
                KeywordAbilityIds.PARADIGM,
            )

        /**
         * Walk [data]'s `abilityIds` once and return the alt-cost rows the
         * card carries. Mirrors [CardRepository.findKeywordAbilityGrpId]'s
         * two-shape resolution so consumers reading from [BoundCard.altCosts]
         * agree with the live lookup:
         *
         *  1. **Direct match** — the well-known root id appears verbatim in
         *     `Cards.AbilityIds`. Rare for alt-cost keywords (the canonical
         *     shape is the BaseId chain), but surfaces in fixtures that
         *     register only the keyword's bare id without a per-printing
         *     `AbilityInfo` row.
         *  2. **BaseId chain** — an ability on the card has
         *     [leyline.game.data.AbilityInfo.baseId] equal to a well-known
         *     root.
         */
        fun bindAltCosts(
            data: CardData?,
            repo: CardRepository,
        ): List<AltCostBinding> {
            if (data == null) return emptyList()
            val out = mutableListOf<AltCostBinding>()
            for ((abilityGrpId, _) in data.abilityIds) {
                if (abilityGrpId in ALT_COST_BASE_IDS) {
                    // Direct-match shape: the well-known root id stands in
                    // for the per-printing row. No AbilityInfo registration
                    // needed; mana cost defaults to empty.
                    out +=
                        AltCostBinding(
                            keywordBaseId = abilityGrpId,
                            abilityGrpId = abilityGrpId,
                            manaCost = emptyList(),
                        )
                    continue
                }
                val info = repo.findAbilityInfo(abilityGrpId) ?: continue
                if (info.baseId !in ALT_COST_BASE_IDS) continue
                out +=
                    AltCostBinding(
                        keywordBaseId = info.baseId,
                        abilityGrpId = abilityGrpId,
                        manaCost = info.manaCost,
                    )
            }
            return out
        }

        /**
         * Resolve the Mobilize cleanup ability grpId — the hidden triggered
         * "Sacrifice them at the beginning of the next end step." row paired
         * with every Mobilize keyword. Returns null when [altCosts] doesn't
         * carry a Mobilize row (gating the lookup so non-Mobilize cards with
         * unrelated hidden abilities don't get misclassified) OR when [data]
         * has no matching `hiddenAbilityIds` row with `category == 2`.
         */
        fun bindMobilizeCleanup(
            data: CardData?,
            altCosts: List<AltCostBinding>,
            repo: CardRepository,
        ): Int? {
            if (data == null) return null
            val hasMobilize = altCosts.any { it.keywordBaseId == KeywordAbilityIds.MOBILIZE }
            if (!hasMobilize) return null
            return repo.findHiddenTriggeredAbilityGrpId(data.grpId)
        }

        /** Resolve Decayed's hidden "sacrifice it at end of combat" row. */
        fun bindDecayedCleanup(
            data: CardData?,
            repo: CardRepository,
        ): Int? {
            if (data == null) return null
            val hasDecayed = repo.findKeywordAbilityGrpId(data.grpId, KeywordAbilityIds.DECAYED) != null
            if (!hasDecayed) return null
            return repo.findHiddenTriggeredAbilityGrpId(data.grpId)
        }

        /** Bind the linked faces whose companion-object contract is supported. */
        fun bindLinkedFaces(
            data: CardData?,
            repo: CardRepository,
        ): List<LinkedFaceDescriptor> {
            if (data == null) return emptyList()
            return LinkedFaceRole.entries.mapNotNull { role ->
                val faceGrpId =
                    when (data.linkedFaceType) {
                        role.faceLinkedType -> data.grpId
                        role.parentLinkedType ->
                            data.linkedFaceGrpIds.firstOrNull { linkedGrpId ->
                                repo.findByGrpId(linkedGrpId)?.linkedFaceType == role.faceLinkedType
                            }
                        else -> null
                    }
                faceGrpId?.let { LinkedFaceDescriptor(it, role) }
            }
        }
    }
}

/** Static identity for a supported secondary-face companion. */
data class LinkedFaceDescriptor(
    val grpId: Int,
    val role: LinkedFaceRole,
)

/** Companion semantics and identity namespaces supported by the projection layer. */
enum class LinkedFaceRole(
    val faceLinkedType: Int,
    val parentLinkedType: Int,
    val objectType: GameObjectType,
    val companionIdOffset: Int,
) {
    Adventure(7, 8, GameObjectType.Adventure_a4aa, 500_000),
    Omen(17, 18, GameObjectType.Omen_a4aa, 600_000),
}

/**
 * One alt-cost ability row on a card — the (keyword-base, per-printing
 * abilityGrpId, mana cost) triple that ActionMapper's cast rails consume.
 *
 * Pre-resolved at snapshot time so consumers don't re-walk
 * [CardData.abilityIds] / re-call `findKeywordAbilityGrpId` /
 * `findAlternativeCostAbilityGrpId` at action-emission time.
 */
data class AltCostBinding(
    /** Keyword's BaseId (one of [KeywordAbilityIds]'s alt-cost constants). */
    val keywordBaseId: Int,
    /** Per-card ability grpId for this keyword's row in `Cards.AbilityIds`. */
    val abilityGrpId: Int,
    /** Cast cost on this row (multiset-keyed for cost-aware disambiguation). */
    val manaCost: List<Pair<ManaColor, Int>>,
)

/**
 * Card-state designations gathered into one struct: Prepared role, Plotted
 * role, simple battlefield flags, and the Foretold flag. StateMapper's per-designation transient
 * inserters address this one field rather than scanning three independent
 * snapshot fields.
 *
 * [PreparedRole.Source] carries `copyForgeCardId` and [PreparedRole.Copy]
 * carries `sourceForgeCardId?` — the structural data each role needs to
 * recover its partner without a second lookup.
 */
data class DesignationSet(
    val prepared: PreparedRole = PreparedRole.None,
    val plotted: PlottedRole = PlottedRole.None,
    val isSaddled: Boolean = false,
    val isSuspected: Boolean = false,
    val isSolved: Boolean = false,
    val foretold: Boolean = false,
    val isCommander: Boolean = false,
    val commanderTax: Int = 0,
    val commanderColorIdentity: List<Int> = emptyList(),
    val isLeftDoorUnlocked: Boolean = false,
    val isRightDoorUnlocked: Boolean = false,
) {
    val isPreparedSource: Boolean get() = prepared is PreparedRole.Source
    val isPreparedCopy: Boolean get() = prepared is PreparedRole.Copy
    val isPlotted: Boolean get() = plotted is PlottedRole.Plotted
    val isForetold: Boolean get() = foretold
}

/**
 * Per-card parent linkage that drives `parentId` emission on the GameObject
 * proto. Sealed because the two cases are structurally distinct:
 *
 *  - [AttachedTo] — Aura/Equipment attached to a permanent. The attachment is
 *    a Forge `Card.attachedTo` reference; the parent is the carrier
 *    permanent.
 *  - [PreparedCopy] — prepared-spell exile copy linked to its battlefield
 *    Source creature. The parent is the live Source whose
 *    `prepared.firstRemembered` points at the copy.
 *
 * Resolved by `SnapshotCapture.bindParentLinkage`; consumers (e.g.
 * `ObjectMapper`) pattern-match here instead of AND-ing two independent
 * nulls.
 */
sealed interface ParentLinkage {
    /** The instanceId of the parent object — what gets stamped as `parentId`. */
    val parentInstanceId: Int

    /** Aura/Equipment attached to the carrier permanent at [parentInstanceId]. */
    data class AttachedTo(
        override val parentInstanceId: Int,
    ) : ParentLinkage

    /** Prepared-spell exile copy whose Source creature lives at [parentInstanceId]. */
    data class PreparedCopy(
        override val parentInstanceId: Int,
    ) : ParentLinkage
}
