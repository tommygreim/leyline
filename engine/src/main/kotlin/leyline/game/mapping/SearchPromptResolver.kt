package leyline.game.mapping

/** Native search wording from exact Forge validity filters, never from the cards currently eligible. */
internal object SearchPromptResolver {
    private val singleCardPrompts =
        mapOf(
            setOf("Land.Basic") to PromptIds.SEARCH_BASIC_LAND,
            setOf("Creature") to PromptIds.SEARCH_CREATURE,
            setOf("Artifact") to PromptIds.SEARCH_ARTIFACT,
            setOf("Enchantment") to PromptIds.SEARCH_ENCHANTMENT,
            setOf("Planeswalker") to PromptIds.SEARCH_PLANESWALKER,
            setOf("Land") to PromptIds.SEARCH_LAND,
            setOf("Forest") to PromptIds.SEARCH_FOREST,
            setOf("Island") to PromptIds.SEARCH_ISLAND,
            setOf("Plains") to PromptIds.SEARCH_PLAINS,
            setOf("Swamp") to PromptIds.SEARCH_SWAMP,
            setOf("Mountain") to PromptIds.SEARCH_MOUNTAIN,
            setOf("Forest.Basic") to PromptIds.SEARCH_BASIC_FOREST,
            setOf("Mountain.Basic") to PromptIds.SEARCH_BASIC_MOUNTAIN,
            setOf("Creature", "Land") to PromptIds.SEARCH_CREATURE_OR_LAND,
            setOf("Artifact", "Creature") to PromptIds.SEARCH_ARTIFACT_OR_CREATURE,
            setOf("Artifact", "Enchantment") to PromptIds.SEARCH_ARTIFACT_OR_ENCHANTMENT,
            setOf("Instant", "Sorcery") to PromptIds.SEARCH_INSTANT_OR_SORCERY,
            setOf("Instant", "Card.hasKeywordFlash") to PromptIds.SEARCH_INSTANT_OR_FLASH,
            setOf("Plains", "Island", "Swamp", "Mountain") to PromptIds.SEARCH_FARSEEK_TYPES,
        )
    private val twoCardPrompts =
        mapOf(
            setOf("Land") to PromptIds.SEARCH_UP_TO_TWO_LANDS,
            setOf("Land.Basic") to PromptIds.SEARCH_UP_TO_TWO_BASIC_LANDS,
            setOf("Creature") to PromptIds.SEARCH_UP_TO_TWO_CREATURES,
        )

    fun resolve(
        changeType: String?,
        maxFind: Int,
    ): Int {
        val filter = changeType?.split(',')?.map(String::trim)?.toSet() ?: return PromptIds.SEARCH
        val prompts =
            when (maxFind) {
                1 -> singleCardPrompts
                2 -> twoCardPrompts
                else -> return PromptIds.SEARCH
            }
        return prompts[filter] ?: PromptIds.SEARCH
    }
}
