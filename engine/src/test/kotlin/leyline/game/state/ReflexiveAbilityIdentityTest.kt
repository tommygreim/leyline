package leyline.game.state

import forge.game.Game
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.trigger.TriggerHandler
import forge.game.trigger.WrappedAbility
import forge.util.Lang
import forge.util.Localizer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.InMemoryCardRepository
import leyline.game.codes.SlotKind
import leyline.game.data.AbilityInfo
import leyline.game.data.CardData
import java.nio.file.Path

class ReflexiveAbilityIdentityTest :
    FunSpec({
        tags(UnitTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            val contentRoot = Path.of(System.getProperty("leyline.content.root"))
            Localizer.getInstance().initialize(
                "en-US",
                contentRoot.resolve("forge/forge-gui/res/languages").toString(),
            )
            Lang.createInstance("en-US")
        }

        test("copied trigger uses its definition host even when recipient keeps its own name") {
            val origin = Card(21, null as Game?).also { it.name = "Original Rules" }
            val recipient = Card(22, null as Game?).also { it.name = "Retained Name" }
            val trigger =
                TriggerHandler.parseTrigger(
                    "Mode$ ChangesZone | Origin$ Any | Destination$ Battlefield | ValidCard$ Card.Self",
                    origin,
                    true,
                )
            origin.addTrigger(trigger)
            val copied = trigger.copy(recipient, false)
            recipient.addTrigger(copied)
            val execute = AbilitySub(ApiType.Draw, recipient, null, emptyMap())
            val wrapped = WrappedAbility(copied, execute, null)
            val repository = InMemoryCardRepository()

            fun metadata(
                id: Int,
                row: Int,
            ) = CardData(
                grpId = id,
                titleId = id,
                power = "",
                toughness = "",
                colors = emptyList(),
                types = emptyList(),
                subtypes = emptyList(),
                supertypes = emptyList(),
                manaCost = emptyList(),
                abilityIds = listOf(row to 1),
                abilityKinds = listOf(SlotKind.Intrinsic),
                abilityCategories = listOf(2),
            )
            repository.registerData(metadata(9001, 9101), origin.name)
            repository.registerData(metadata(9002, 9102), recipient.name)
            GameBridge(cardRepository = repository).resolveAbilityIdentity(recipient, wrapped)?.abilityGrpId shouldBe 9101
        }

        test("replacement-origin reflexive trigger keeps the printed copy paragraph after entry") {
            val source = Card(31, null as Game?).also { it.name = "Replacement Source" }
            val replacement =
                forge.game.replacement.ReplacementHandler.parseReplacement(
                    "Event$ Moved | ValidCard$ Card.Self | Destination$ Battlefield",
                    source,
                    true,
                )
            val clone = AbilitySub(ApiType.Clone, source, null, emptyMap()).also { it.replacementEffect = replacement }
            val immediate = AbilitySub(ApiType.ImmediateTrigger, source, null, emptyMap())
            val execute = AbilitySub(ApiType.ChangeZone, source, null, emptyMap())
            clone.setSubAbility(immediate)
            immediate.setAdditionalAbility("Execute", execute)
            val copiedImmediate = immediate.copy(source, true)
            val detached = (execute.copy(source, true) as AbilitySub).also { it.parent = null }
            val trigger =
                TriggerHandler
                    .parseTrigger("Mode$ Immediate", source, true)
                    .also { it.spawningAbility = copiedImmediate }
            val wrapped = WrappedAbility(trigger, detached, null)
            val repository = InMemoryCardRepository()
            repository.registerData(
                CardData(
                    grpId = 9003,
                    titleId = 9003,
                    power = "",
                    toughness = "",
                    colors = emptyList(),
                    types = emptyList(),
                    subtypes = emptyList(),
                    supertypes = emptyList(),
                    manaCost = emptyList(),
                    abilityIds = listOf(9103 to 1),
                    abilityKinds = listOf(SlotKind.Intrinsic),
                    abilityCategories = listOf(3),
                ),
                source.name,
            )
            repository.registerAbilityInfo(
                9103,
                AbilityInfo(
                    baseId = 0,
                    manaCost = emptyList(),
                    category = 3,
                    hiddenAbilityIds = listOf(9104),
                ),
            )
            GameBridge(cardRepository = repository).resolveAbilityIdentity(source, wrapped)?.abilityGrpId shouldBe 9103
        }

        test("detached immediate-trigger execute ability resolves to its hidden child row") {
            val source = Card(1, null as Game?).also { it.name = "Reflexive Source" }
            val parentTrigger =
                TriggerHandler.parseTrigger(
                    "Mode$ CounterAdded | ValidCard$ Card.Self | CounterType$ PLAN | CounterAmount$ EQ4",
                    source,
                    true,
                )
            source.addTrigger(parentTrigger)

            // Mirrors Forge's `When you do` construction. ImmediateTriggerEffect
            // copies `immediate`, then detaches the copied Execute subability
            // before WrappedAbility assigns the new immediate trigger to it.
            val execute = AbilitySub(ApiType.DealDamage, source, null, emptyMap())
            val immediate = AbilitySub(ApiType.ImmediateTrigger, source, null, emptyMap())
            immediate.setAdditionalAbility("Execute", execute)
            val sacrifice = AbilitySub(ApiType.Sacrifice, source, null, emptyMap())
            sacrifice.setSubAbility(immediate)
            sacrifice.trigger = parentTrigger

            val immediateCopy = immediate.copy(source, true)
            val detachedExecute = checkNotNull(execute.copy(source, true) as? AbilitySub)
            detachedExecute.parent = null
            val reflexiveTrigger =
                TriggerHandler
                    .parseTrigger(
                        "Mode$ Immediate",
                        source,
                        true,
                    ).also { it.spawningAbility = immediateCopy }
            val stackAbility = WrappedAbility(reflexiveTrigger, detachedExecute, null)

            val repository = InMemoryCardRepository()
            repository.registerData(
                CardData(
                    grpId = SOURCE_GRP_ID,
                    titleId = SOURCE_GRP_ID,
                    power = "",
                    toughness = "",
                    colors = emptyList(),
                    types = emptyList(),
                    subtypes = emptyList(),
                    supertypes = emptyList(),
                    abilityIds = listOf(PARENT_ABILITY_GRP_ID to 1),
                    abilityKinds = listOf(SlotKind.Intrinsic),
                    abilityCategories = listOf(TRIGGER_CATEGORY),
                    manaCost = emptyList(),
                ),
                source.name,
            )
            repository.registerAbilityInfo(
                PARENT_ABILITY_GRP_ID,
                AbilityInfo(
                    baseId = 0,
                    manaCost = emptyList(),
                    category = TRIGGER_CATEGORY,
                    hiddenAbilityIds = listOf(REFLEXIVE_CHILD_ABILITY_GRP_ID),
                ),
            )

            GameBridge(cardRepository = repository)
                .resolveAbilityIdentity(source, stackAbility)
                ?.abilityGrpId shouldBe REFLEXIVE_CHILD_ABILITY_GRP_ID
        }
    }) {
    private companion object {
        const val SOURCE_GRP_ID = 105_022
        const val PARENT_ABILITY_GRP_ID = 206_203
        const val REFLEXIVE_CHILD_ABILITY_GRP_ID = 206_398
        const val TRIGGER_CATEGORY = 2
    }
}
