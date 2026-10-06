package leyline.game.data

import leyline.game.codes.SlotKind
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Immutable card metadata from a [CardRepository].
 *
 * Numeric enum values map 1:1 to proto values. Display-name fields preserve values
 * that do not have a corresponding protocol enum.
 */
data class CardData(
    val grpId: Int,
    val titleId: Int,
    val power: String,
    val toughness: String,
    val colors: List<Int>, // proto CardColor values
    val types: List<Int>, // proto CardType values
    val subtypes: List<Int>, // proto SubType values
    val supertypes: List<Int>, // proto SuperType values
    val abilityIds: List<Pair<Int, Int>>, // abilityGrpId:textId pairs
    /**
     * Engine-only ability ids not displayed on the card face. Mirrors the
     * client card-DB `Cards.HiddenAbilityIds` field (same `id:textLocId`
     * shape as [abilityIds]). Used for delayed-trigger pairings — every
     * Mobilize source has its cleanup ability ("Sacrifice them at the
     * beginning of the next end step.") here, paired with the keyword on
     * [abilityIds]. Empty for cards that don't carry a hidden
     * delayed-trigger ability.
     */
    val hiddenAbilityIds: List<Pair<Int, Int>> = emptyList(),
    /**
     * Per-slot kind aligned 1:1 with [abilityIds]. Sourced from Arena
     * `Abilities.Category` (1=Activated → [SlotKind.Activated]; everything
     * else → [SlotKind.Intrinsic] for triggers/statics). Empty list means
     * "kinds unknown" — consumers should fall back to the legacy
     * "all-after-keywords-are-activated" positional assumption.
     */
    val abilityKinds: List<SlotKind> = emptyList(),
    /** Raw client ability categories aligned with [abilityIds] (2=trigger, 3+=static/passive). */
    val abilityCategories: List<Int> = emptyList(),
    /** Native keyword roots (Abilities.BaseId), aligned with [abilityIds]. Empty means unknown. */
    val abilityBaseIds: List<Int> = emptyList(),
    val manaCost: List<Pair<ManaColor, Int>>, // (color, count) from OldSchoolManaText
    val tokenGrpIds: Map<Int, Int> = emptyMap(), // abilityGrpId → tokenGrpId
    /** Client card-DB relationship category for [linkedFaceGrpIds]. */
    val linkedFaceType: Int = 0,
    val linkedFaceGrpIds: List<Int> = emptyList(),
    /** Forge names that have no corresponding fixed GRE enum remain available to non-native heads. */
    val typeNames: List<String> = emptyList(),
    val subtypeNames: List<String> = emptyList(),
    val keywordNames: List<String> = emptyList(),
) {
    val isMultiFace: Boolean get() = linkedFaceGrpIds.isNotEmpty()
}
