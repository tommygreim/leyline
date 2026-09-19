package leyline.game.state

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.BoardTest

class EffectProjectionFactsTest :
    BoardTest({

        test("materialization binds static and staticId-zero boost ability identities") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Pacifism", human, ZoneType.Battlefield)
                    addCard("Monastery Swiftspear", human, ZoneType.Battlefield)
                }
            val cards =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .associateBy { it.name }
            val pacifism = cards.getValue("Pacifism")
            val swiftspear = cards.getValue("Monastery Swiftspear")
            val restriction = pacifism.staticAbilities.last()
            val pacifismData =
                board.bridge.cardRepository
                    .findByGrpId(93650)
                    .shouldNotBeNull()
            val expectedStaticGrpId =
                board.bridge
                    .abilityRegistryFor(pacifism, pacifismData)
                    .shouldNotBeNull()
                    .forStaticAbility(restriction.definitionId)
                    .shouldNotBeNull()
            val expectedProwessGrpId =
                board.bridge.cardRepository
                    .findKeywordAbilityGrpId(94381, KeywordAbilityIds.PROWESS)
                    .shouldNotBeNull()

            pacifism.addPTBoost(1, 2, 101L, restriction.id.toLong())
            swiftspear.addPTBoost(1, 1, 102L, 0L)
            val byCard =
                board.bridge
                    .materializeEffectProjectionFacts()
                    .boostEntries
                    .associateBy { it.forgeCardId }

            assertSoftly {
                byCard.getValue(ForgeCardId(pacifism.id)).sourceAbilityGrpId shouldBe expectedStaticGrpId
                byCard.getValue(ForgeCardId(swiftspear.id)).sourceAbilityGrpId shouldBe expectedProwessGrpId
            }
        }

        test("keyword attribution freezes the source permanent at materialization") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Pacifism", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val cards =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .associateBy { it.name }
            val source = cards.getValue("Pacifism")
            val target = cards.getValue("Grizzly Bears")
            val sourceStatic = source.staticAbilities.last()
            val sourceId = ForgeCardId(source.id)

            target.addChangedCardKeywords(listOf("Flying"), null, false, 201L, sourceStatic)
            val frozen =
                board.bridge
                    .materializeEffectProjectionFacts()
                    .keywordEntries
                    .single()

            exile(source, board.game)
            target.addChangedCardKeywords(listOf("Vigilance"), null, false, 202L, sourceStatic)
            val advanced =
                board.bridge
                    .materializeEffectProjectionFacts()
                    .keywordEntries
                    .single { it.timestamp == 202L }

            assertSoftly {
                frozen.affectorForgeCardId shouldBe sourceId
                frozen.keyword shouldBe "Flying"
                advanced.affectorForgeCardId shouldBe null
            }
        }

        test("static boost facts retain the source permanent") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Case of the Gateway Express", human, ZoneType.Battlefield)
                    repeat(3) { addCard("Savannah Lions", human, ZoneType.Battlefield) }
                }
            val case =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Case of the Gateway Express" }
            val sourceStatic = case.staticAbilities.last()
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .filter { it.name == "Savannah Lions" }
                .forEach { it.addPTBoost(1, 0, 1L, sourceStatic.id.toLong()) }

            val facts = board.bridge.materializeEffectProjectionFacts()

            facts.boostEntries
                .filter { it.sourceForgeCardId != null }
                .map { it.sourceForgeCardId }
                .distinct() shouldBe
                listOf(ForgeCardId(case.id))
        }

        test("staticId-zero keyword leaves attribution for immutable event fallback") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val target =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            target.addChangedCardKeywords(listOf("Haste"), null, false, 301L, null)

            val keyword =
                board.bridge
                    .materializeEffectProjectionFacts()
                    .keywordEntries
                    .single()

            assertSoftly {
                keyword.staticId shouldBe 0L
                keyword.affectorForgeCardId shouldBe null
            }
        }

        test("parameterized keyword grants retain the names that identify their GRE abilities") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val target =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            target.addChangedCardKeywords(
                listOf(
                    "Landwalk:Forest",
                    "Landwalk:Desert",
                    "Protection:White",
                    "Hexproof:White",
                    "Affinity:Artifact",
                    "TypeCycling:Island:1U:Island",
                ),
                null,
                false,
                401L,
                null,
            )

            board.bridge
                .materializeEffectProjectionFacts()
                .keywordEntries
                .map { it.keyword }
                .shouldContainExactlyInAnyOrder(
                    "Forestwalk",
                    "Landwalk",
                    "Protection from white",
                    "Hexproof from white",
                    "Affinity for artifacts",
                    "Islandcycling",
                )
        }
    })
