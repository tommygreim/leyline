package leyline.game.codes

object KeywordGrpIds {
    /** GRE AbilityType values used by both static keyword choices and temporary keyword grants. */
    private val table =
        mapOf(
            "Deathtouch" to 1,
            "Double Strike" to 3,
            "First Strike" to 6,
            "Flying" to 8,
            "Haste" to 9,
            "Hexproof" to 10,
            "Indestructible" to 104,
            "Lifelink" to 12,
            "Menace" to 142,
            "Reach" to 13,
            "Trample" to 14,
            "Vigilance" to 15,
        )

    fun forKeyword(keyword: String): Int? = table[keyword]
}
