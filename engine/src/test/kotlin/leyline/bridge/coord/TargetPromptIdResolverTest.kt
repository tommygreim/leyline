package leyline.bridge.coord

import forge.game.Game
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.TargetRestrictions
import forge.util.Lang
import forge.util.Localizer
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapping.PromptIds
import java.nio.file.Path

class TargetPromptIdResolverTest :
    FunSpec({
        tags(UnitTag)

        beforeSpec {
            val contentRoot = Path.of(System.getProperty("leyline.content.root"))
            Localizer.getInstance().initialize("en-US", contentRoot.resolve("forge/forge-gui/res/languages").toString())
            Lang.createInstance("en-US")
        }

        test("chained draw and damage targets carry distinct effect prompts") {
            val source = Card(101, null as Game?).also { it.name = "Convergent Spell" }
            val draw = ability(source, ApiType.Draw, "Player", mapOf("NumCards" to "X"))
            val damage = ability(source, ApiType.DealDamage, "Any", mapOf("NumDmg" to "X"))
            draw.setSubAbility(damage)

            assertSoftly {
                TargetPromptIdResolver.resolve(draw, null) shouldBe PromptIds.TARGET_PLAYER_DRAWS_X
                TargetPromptIdResolver.resolve(damage, null) shouldBe PromptIds.DEAL_X_DAMAGE_TO_ANY_TARGET
            }
        }

        test("chained bounce and fixed damage targets carry distinct effect prompts") {
            val source = Card(102, null as Game?).also { it.name = "Return and Burn" }
            val bounce =
                ability(
                    source,
                    ApiType.ChangeZone,
                    "Permanent,Card.inZoneStack",
                    mapOf("Destination" to "Hand"),
                )
            val damage = ability(source, ApiType.DealDamage, "Any", mapOf("NumDmg" to "4"))
            bounce.setSubAbility(damage)

            assertSoftly {
                TargetPromptIdResolver.resolve(bounce, null) shouldBe PromptIds.RETURN_TARGET_SPELL_OR_PERMANENT_TO_HAND
                TargetPromptIdResolver.resolve(damage, null) shouldBe PromptIds.DEAL_FOUR_DAMAGE_TO_ANY_TARGET
            }
        }

        test("effect prompts require a matching action and target restriction") {
            val source = Card(103, null as Game?).also { it.name = "Generic Effects" }
            assertSoftly {
                TargetPromptIdResolver.resolve(ability(source, ApiType.Mill, "Player", mapOf("NumCards" to "X")), null) shouldBe
                    PromptIds.TARGET_PLAYER
                TargetPromptIdResolver.resolve(ability(source, ApiType.Draw, "Player", mapOf("NumCards" to "2")), null) shouldBe
                    PromptIds.TARGET_PLAYER
                TargetPromptIdResolver.resolve(ability(source, ApiType.DealDamage, "Any", mapOf("NumDmg" to "2")), null) shouldBe
                    PromptIds.CHOOSE_ANY_TARGET
                TargetPromptIdResolver.resolve(
                    ability(source, ApiType.ChangeZone, "Creature", mapOf("Destination" to "Hand")),
                    null,
                ) shouldBe PromptIds.TARGET_CREATURE
            }
        }

        test("exact single-kind restrictions use kind prompts") {
            val source = Card(104, null as Game?).also { it.name = "Typed Target" }
            val expected =
                mapOf(
                    "Permanent" to PromptIds.TARGET_PERMANENT,
                    "Card.inZoneStack" to PromptIds.TARGET_SPELL,
                    "Artifact" to PromptIds.TARGET_ARTIFACT,
                    "Enchantment" to PromptIds.TARGET_ENCHANTMENT,
                    "Land" to PromptIds.TARGET_LAND,
                    "Planeswalker" to PromptIds.TARGET_PLANESWALKER,
                    "Opponent" to PromptIds.TARGET_OPPONENT,
                    "Permanent,Card.inZoneStack" to PromptIds.TARGET_SPELL_OR_PERMANENT,
                )
            expected.forEach { (valid, promptId) ->
                TargetPromptIdResolver.resolve(ability(source, ApiType.Pump, valid, emptyMap()), null) shouldBe promptId
            }
        }
    })

private fun ability(
    source: Card,
    api: ApiType,
    validTargets: String,
    params: Map<String, String>,
): AbilitySub = AbilitySub(api, source, TargetRestrictions(mapOf("ValidTgts" to validTargets)), params)
