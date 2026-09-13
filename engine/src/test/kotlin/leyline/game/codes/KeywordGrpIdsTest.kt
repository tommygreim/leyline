package leyline.game.codes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.codes.KeywordGrpIds

class KeywordGrpIdsTest :
    FunSpec({
        tags(UnitTag)
        test("temporary keyword ids use the Arena AbilityType values") {
            mapOf(
                "Deathtouch" to 1,
                "Double Strike" to 3,
                "Haste" to 9,
                "Hexproof" to 10,
                "Indestructible" to 104,
            ).forEach { (keyword, abilityType) -> KeywordGrpIds.forKeyword(keyword) shouldBe abilityType }
        }
        test("trample resolves to 14") { KeywordGrpIds.forKeyword("Trample") shouldBe 14 }
        test("flying resolves to 8") { KeywordGrpIds.forKeyword("Flying") shouldBe 8 }
        test("hexproof resolves to 10") { KeywordGrpIds.forKeyword("Hexproof") shouldBe 10 }
        test("unknown keyword returns null") { KeywordGrpIds.forKeyword("Flanking").shouldBeNull() }
    })
