package leyline.game.state

import forge.game.Game
import forge.game.card.Card
import forge.game.cost.Cost
import forge.game.spellability.AbilityActivated
import forge.game.spellability.TargetRestrictions
import forge.util.Lang
import forge.util.Localizer
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.InMemoryCardRepository
import leyline.game.mapping.PromptIds
import java.nio.file.Path

class StackTargetSpecRecorderTest :
    FunSpec({
        tags(UnitTag)

        beforeSpec {
            val contentRoot = Path.of(System.getProperty("leyline.content.root"))
            Localizer.getInstance().initialize(
                "en-US",
                contentRoot.resolve("forge/forge-gui/res/languages").toString(),
            )
            Lang.createInstance("en-US")
        }

        test("live stack capture records an engine-selected target exactly once") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val source = Card(101, null as Game?).also { it.name = "Engine Bolt" }
            val target = Card(202, null as Game?).also { it.name = "Target Creature" }
            val restrictions = TargetRestrictions(mapOf("ValidTgts" to "Any"))
            val ability =
                object : AbilityActivated(source, Cost("R", true), restrictions) {
                    override fun resolve() = Unit
                }
            ability.targets.add(target)

            bridge.recordStackTargetSpecs(ability, isSpell = false)
            bridge.recordStackTargetSpecs(ability, isSpell = false)

            val fact = bridge.snapshotPendingTargetSpecs().single().spec
            assertSoftly {
                bridge.snapshotPendingTargetSpecs() shouldHaveSize 1
                fact.spellForgeCardId shouldBe source.id
                fact.index shouldBe 1
                fact.isStackAbility shouldBe true
                fact.forgeAbilityId shouldBe ability.id
                fact.promptId shouldBe PromptIds.CHOOSE_ANY_TARGET
                fact.affectees.single().targetForgeCardId shouldBe target.id
            }
        }

        test("spell capture freezes a stack affector instance id") {
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val source = Card(303, null as Game?).also { it.name = "Targeted Spell" }
            val target = Card(404, null as Game?).also { it.name = "Target Creature" }
            val restrictions = TargetRestrictions(mapOf("ValidTgts" to "Creature"))
            val ability =
                object : AbilityActivated(source, Cost("1", true), restrictions) {
                    override fun resolve() = Unit
                }
            ability.targets.add(target)

            bridge.recordStackTargetSpecs(ability, isSpell = true)

            val spec = bridge.snapshotPendingTargetSpecs().single().spec
            assertSoftly {
                spec.isStackAbility shouldBe false
                spec.affectorInstanceIdAtRecord shouldBe bridge.getOrAllocInstanceId(leyline.bridge.types.ForgeCardId(source.id)).value
                spec.promptId shouldBe PromptIds.TARGET_CREATURE
            }
        }
    })
