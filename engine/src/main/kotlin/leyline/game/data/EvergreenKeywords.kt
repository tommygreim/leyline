package leyline.game.data

/**
 * Evergreen keywords a card is printed with, by the ability id that carries them.
 * Each is a single shared ability row every printing references, so membership
 * is an integer test rather than a match against ability text.
 *
 * Ids are read off card rows rather than inferred from rules text. Printed
 * keywords come from card data; temporary grants come from game state and use
 * the corresponding GRE AbilityType value.
 */
object EvergreenKeywords {
    private val BY_ABILITY_ID: Map<Int, String> =
        mapOf(
            1 to "Deathtouch",
            2 to "Defender",
            3 to "Double strike",
            6 to "First strike",
            7 to "Flash",
            8 to "Flying",
            9 to "Haste",
            10 to "Hexproof",
            11 to "Intimidate",
            12 to "Lifelink",
            13 to "Reach",
            14 to "Trample",
            15 to "Vigilance",
            22 to "Shroud",
            38 to "Fear",
            81 to "Wither",
            87 to "Annihilator",
            91 to "Infect",
            104 to "Indestructible",
            137 to "Prowess",
            142 to "Menace",
            143 to "Skulk",
            211 to "Ward",
            264 to "Toxic",
        )

    /** In the order this table lists them. */
    fun of(card: CardData): List<String> {
        val ids = card.abilityIds.mapTo(mutableSetOf()) { it.first }
        return BY_ABILITY_ID.entries.filter { it.key in ids }.map { it.value }
    }

    fun fromAbilityId(abilityId: Int): String? = BY_ABILITY_ID[abilityId]
}
