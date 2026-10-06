package leyline.game.mapping

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class SearchPromptResolverTest :
    FunSpec({
        tags(UnitTag)

        test("exact search filters select native wording independent of candidates and alternative order") {
            mapOf(
                "Land.Basic" to PromptIds.SEARCH_BASIC_LAND,
                "Creature" to PromptIds.SEARCH_CREATURE,
                "Island" to PromptIds.SEARCH_ISLAND,
                "Instant,Card.hasKeywordFlash" to PromptIds.SEARCH_INSTANT_OR_FLASH,
                "Card.hasKeywordFlash, Instant" to PromptIds.SEARCH_INSTANT_OR_FLASH,
                "Instant,Sorcery" to PromptIds.SEARCH_INSTANT_OR_SORCERY,
                "Mountain,Swamp,Island,Plains" to PromptIds.SEARCH_FARSEEK_TYPES,
            ).forEach { (filter, expected) ->
                SearchPromptResolver.resolve(filter, 1) shouldBe expected
            }
        }

        test("selection count and unknown qualifiers cannot borrow a misleading narrower prompt") {
            SearchPromptResolver.resolve("Land.Basic", 2) shouldBe PromptIds.SEARCH_UP_TO_TWO_BASIC_LANDS
            SearchPromptResolver.resolve("Land", 2) shouldBe PromptIds.SEARCH_UP_TO_TWO_LANDS
            SearchPromptResolver.resolve("Creature", 2) shouldBe PromptIds.SEARCH_UP_TO_TWO_CREATURES
            listOf(null, "Card", "Land.Basic+withDifferentNames", "Creature.cmcLE2", "Forest,Island").forEach { filter ->
                SearchPromptResolver.resolve(filter, 1) shouldBe PromptIds.SEARCH
            }
            SearchPromptResolver.resolve("Instant,Card.hasKeywordFlash", 2) shouldBe PromptIds.SEARCH
            SearchPromptResolver.resolve("Land.Basic", 3) shouldBe PromptIds.SEARCH
        }
    })
