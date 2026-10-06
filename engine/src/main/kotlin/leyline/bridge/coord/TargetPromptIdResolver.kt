package leyline.bridge.coord

import forge.game.ability.ApiType
import forge.game.spellability.SpellAbility
import leyline.bridge.types.AbilityKeywordFamily
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.game.mapping.PromptIds

/** Shared TargetSpec/request prompt-id classification for interactive and engine-selected targets. */
internal object TargetPromptIdResolver {
    fun resolve(
        sa: SpellAbility,
        abilityIdentity: ResolvedAbilityIdentity?,
    ): Int =
        when {
            abilityIdentity?.keywordFamily == AbilityKeywordFamily.Mentor -> PromptIds.MENTOR_TARGET
            sa.isMutate -> PromptIds.MUTATE_TARGET
            else -> effectPromptId(sa) ?: restrictionPromptId(sa) ?: PromptIds.SELECT_TARGETS
        }

    private fun effectPromptId(sa: SpellAbility): Int? {
        val valid =
            sa.targetRestrictions
                ?.validTgts
                ?.toList()
                ?.map(String::lowercase)
                .orEmpty()
        return when {
            sa.api == ApiType.Draw && valid == listOf("player") && sa.getParam("NumCards") == "X" ->
                PromptIds.TARGET_PLAYER_DRAWS_X
            sa.api == ApiType.DealDamage && valid == listOf("any") && !sa.isDividedAsYouChoose ->
                when (sa.getParam("NumDmg")) {
                    "3" -> PromptIds.DEAL_THREE_DAMAGE_TO_ANY_TARGET
                    "4" -> PromptIds.DEAL_FOUR_DAMAGE_TO_ANY_TARGET
                    "X" -> PromptIds.DEAL_X_DAMAGE_TO_ANY_TARGET
                    else -> null
                }
            sa.api == ApiType.ChangeZone &&
                valid.toSet() == setOf("permanent", "card.inzonestack") &&
                sa.getParam("Destination") == "Hand" -> PromptIds.RETURN_TARGET_SPELL_OR_PERMANENT_TO_HAND
            else -> null
        }
    }

    private fun restrictionPromptId(sa: SpellAbility): Int? {
        val valid =
            sa.targetRestrictions
                ?.validTgts
                ?.toList()
                .orEmpty()
        if (valid.isEmpty()) return null
        val normalized = valid.map { it.lowercase() }
        if (normalized == listOf("any")) return PromptIds.CHOOSE_ANY_TARGET
        if (normalized == listOf("player")) return PromptIds.TARGET_PLAYER
        if (normalized == listOf("opponent")) return PromptIds.TARGET_OPPONENT
        if (normalized.toSet() == setOf("permanent", "card.inzonestack")) return PromptIds.TARGET_SPELL_OR_PERMANENT
        if (normalized.size == 1) {
            when (normalized.single()) {
                "permanent" -> return PromptIds.TARGET_PERMANENT
                "card.inzonestack" -> return PromptIds.TARGET_SPELL
                "artifact" -> return PromptIds.TARGET_ARTIFACT
                "enchantment" -> return PromptIds.TARGET_ENCHANTMENT
                "land" -> return PromptIds.TARGET_LAND
                "planeswalker" -> return PromptIds.TARGET_PLANESWALKER
            }
        }
        val allOpponentControlled = normalized.all { "youdontctrl" in it }
        val targetKinds =
            normalized
                .flatMap { restriction ->
                    buildList {
                        if ("creature" in restriction) add("creature")
                        if ("planeswalker" in restriction) add("planeswalker")
                    }
                }.toSet()
        return when {
            targetKinds == setOf("creature", "planeswalker") && allOpponentControlled ->
                PromptIds.TARGET_CREATURE_OR_PLANESWALKER_YOU_DONT_CONTROL
            targetKinds == setOf("creature") && normalized.all { "youctrl" in it && "youdontctrl" !in it } ->
                PromptIds.TARGET_CREATURE_YOU_CONTROL
            targetKinds == setOf("creature") && allOpponentControlled ->
                PromptIds.TARGET_CREATURE_YOU_DONT_CONTROL
            targetKinds == setOf("creature") && normalized.none { "youctrl" in it || "youdontctrl" in it } ->
                PromptIds.TARGET_CREATURE
            else -> null
        }
    }
}
