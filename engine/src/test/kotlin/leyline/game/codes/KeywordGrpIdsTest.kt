package leyline.game.codes

import forge.game.keyword.Keyword
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.codes.KeywordGrpIds

class KeywordGrpIdsTest :
    FunSpec({
        tags(UnitTag)
        test("keyword ids use the GRE AbilityType values") {
            mapOf(
                "Absorb" to 65,
                "Deathtouch" to 1,
                "Double Strike" to 3,
                "Flanking" to 26,
                "Haste" to 9,
                "Hexproof" to 10,
                "Indestructible" to 104,
                "Ward" to 211,
                "Backup" to 287,
                "Web-slinging" to 382,
                "Sneak" to 394,
                "Paradigm" to 405,
            ).forEach { (keyword, abilityType) -> KeywordGrpIds.forKeyword(keyword) shouldBe abilityType }
        }

        test("all unambiguous Forge keyword display names resolve") {
            val (mapped, unmapped) =
                Keyword.getAllKeywords().partition { keyword ->
                    KeywordGrpIds.forKeyword(keyword.toString()) != null
                }

            mapped.size shouldBe 178
            unmapped.map(Keyword::toString).toSet() shouldBe
                setOf(
                    "Affinity",
                    "Assist",
                    "Bands with other",
                    "Beam me up",
                    "Craft",
                    "Demonstrate",
                    "Dethrone",
                    "Doctor's companion",
                    "Double agenda",
                    "Encore",
                    "Freerunning",
                    "Hidden agenda",
                    "Living metal",
                    "More Than Meets the Eye",
                    "Protection",
                    "Ravenous",
                    "Reflect",
                    "Space sculptor",
                    "Squad",
                    "Strive",
                    "TypeCycling",
                    "Undaunted",
                    "MayFlashCost",
                    "MayFlashSac",
                )
        }

        test("lookup accepts capitalization spacing and hyphen variants") {
            assertSoftly {
                KeywordGrpIds.forKeyword("double-strike") shouldBe 3
                KeywordGrpIds.forKeyword("JUMPSTART") shouldBe 170
                KeywordGrpIds.forKeyword("web slinging") shouldBe 382
            }
        }

        test("parameterized GRE ability names resolve") {
            mapOf(
                "Forestwalk" to 236,
                "Protection from white" to 185,
                "Protection from instant" to 339,
                "Protection from chosen color" to 184,
                "Hexproof from white" to 191,
                "Affinity for artifacts" to 177,
                "Islandcycling" to 125,
            ).forEach { (keyword, abilityType) -> KeywordGrpIds.forKeyword(keyword) shouldBe abilityType }
        }

        test("parameter-dependent and unknown names remain unmapped") {
            listOf("Affinity", "Protection", "TypeCycling", "Ward:{2}", "Not a keyword").forEach {
                KeywordGrpIds.forKeyword(it).shouldBeNull()
            }
        }
    })
