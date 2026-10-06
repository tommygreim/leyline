package leyline.game.mapping

import forge.game.spellability.SpellAbility

/**
 * Discriminator for library-to-hand searches that benefit from the
 * typecycling picker layout. SearchPromptResolver independently chooses native
 * wording from the exact Forge filter, with generic text for unmapped filters.
 */
object SearchShape {
    /**
     * True when the SA is a typecycling/landcycling/basiccycling-shape
     * library search — `AB$ ChangeZone | Origin$ Library | Destination$
     * Hand | ChangeType$ <type>` with the type narrower than `Card`.
     *
     * Picker layout: highlight every valid candidate face-up and click to pick.
     * Layout classification is not a text decision: an Island-specific
     * localization must never be reused for a different type filter.
     */
    fun isTypeCycling(sa: SpellAbility?): Boolean {
        if (sa == null) return false
        if (!sa.hasParam("Origin") || sa.getParam("Origin") != "Library") return false
        if (!sa.hasParam("Destination") || sa.getParam("Destination") != "Hand") return false
        if (!sa.hasParam("ChangeType")) return false
        return sa.getParam("ChangeType") != "Card"
    }
}
