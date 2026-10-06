package leyline.bridge

import forge.card.MagicColor
import forge.game.ability.ApiType
import forge.game.card.CardUtil
import forge.game.spellability.SpellAbility

/**
 * Live mana colors a source can produce.
 *
 * Most mana abilities declare their produced colors directly. `ManaReflected`
 * abilities instead derive them from the current battlefield (for example Mox
 * Amber and Reflecting Pool), so their printed mana string is deliberately
 * empty. Keep that Forge query at the boundary used by both action and
 * snapshot projection.
 */
fun manaProductionTokens(ability: SpellAbility): List<String> {
    val mana = ability.manaPart ?: return emptyList()
    if (ability.api == ApiType.ManaReflected) {
        return CardUtil
            .getReflectableManaColors(ability)
            .map(MagicColor::toShortString)
            .distinct()
    }
    val produced = if (mana.isComboMana) mana.getComboColors(ability) else mana.origProduced
    return produced.split(" ").filter { it.isNotBlank() }.distinct()
}
