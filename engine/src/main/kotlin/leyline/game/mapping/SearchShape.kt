package leyline.game.mapping

import forge.game.spellability.SpellAbility

/**
 * Discriminator for library-to-hand searches that benefit from the
 * typecycling picker layout. The prompt text remains generic until the bridge
 * can translate the exact Forge `ChangeType` into an Arena localization.
 */
object SearchShape {
    /**
     * True when the SA is a typecycling/landcycling/basiccycling-shape
     * library search — `AB$ ChangeZone | Origin$ Library | Destination$
     * Hand | ChangeType$ <type>` with the type narrower than `Card`.
     *
     * Picker layout: highlight every valid candidate face-up and click to pick.
     * [PromptIds.SEARCH_TYPECYCLING] deliberately carries generic search text;
     * Arena's Island-specific localization is not valid for every such shape.
     *
     * Generic library tutors (Diabolic Tutor, Sylvan Ranger) — wider
     * `ChangeType` or no type filter — fall through to `PromptIds.SEARCH`.
     */
    fun isTypeCycling(sa: SpellAbility?): Boolean {
        if (sa == null) return false
        if (!sa.hasParam("Origin") || sa.getParam("Origin") != "Library") return false
        if (!sa.hasParam("Destination") || sa.getParam("Destination") != "Hand") return false
        if (!sa.hasParam("ChangeType")) return false
        return sa.getParam("ChangeType") != "Card"
    }
}
