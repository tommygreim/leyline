package leyline.acceptance

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class AcceptanceSuiteLoaderTest :
    FunSpec({
        tags(UnitTag)

        test("parses installed catalog and full-control headless options independently") {
            val suite =
                AcceptanceSuiteLoader.loadFromText(
                    """
                    name: catalog-control
                    scenarios:
                      - id: full-catalog
                        puzzle: sample
                        headless: { forge_catalog: true, full_control: true }
                      - id: explicit-fixture
                        puzzle: sample
                        headless: { forge_catalog: false }
                    """.trimIndent(),
                )
            assertSoftly {
                suite.scenarios[0].forgeCatalog shouldBe true
                suite.scenarios[0].fullControl shouldBe true
                suite.scenarios[1].forgeCatalog shouldBe false
                suite.scenarios[1].fullControl shouldBe false
            }
        }

        test("parses backend-neutral executable steps") {
            val suite =
                AcceptanceSuiteLoader.loadFromText(
                    """
                    |name: sample
                    |scenarios:
                    |  - id: cast-face
                    |    puzzle: sample-cast-face
                    |    steps:
                    |      - wait:
                    |          action: { type: activate, card: Miscalculation }
                    |      - activate: { card: Miscalculation, zone: hand, ability_grp_id: 188841 }
                    |      - choose: { optional_cost: kicker }
                    |      - choose: { cto_id: 1 }
                    |      - mana_type_choices: [green, two_generic, red]
                    |      - modal_choice: { index: 0 }
                    |      - static_choice: { id: 34 }
                    |      - optional_action: { accept: true }
                    |      - cancel_action: {}
                    |      - target: { side: ours, zone: battlefield, card: Lunarch Veteran }
                    |      - target: { side: opponent, zone: stack, card: Counterspell }
                    |      - targets:
                    |          - { side: opponent, zone: battlefield, card: Coral Merfolk }
                    |          - { side: opponent, zone: battlefield, card: Savannah Lions }
                    |      - distribute:
                    |          - { side: opponent, card: Coral Merfolk, amount: 1 }
                    |          - { side: opponent, card: Savannah Lions, amount: 2 }
                    |      - block: { blocker: Centaur Courser, attacker: Juggernaut }
                    |      - cast: { card: Think Twice, zone: graveyard, alt_cost: jump_start }
                    |      - select_cost: { zone: hand, cards: [Coral Merfolk] }
                    |      - select_card: { zone: sideboard, card: Environmental Sciences }
                    |      - select_cards: { zone: library, cards: [Lightning Bolt, Counterspell] }
                    |      - order_cards: [Counterspell, Lightning Bolt]
                    |      - attack: { cards: [Raging Goblin], target: { side: opponent, zone: battlefield, card: Liliana of the Veil } }
                    |      - expect:
                    |          all:
                    |            - prompt: { type: OrderReq, prompt_id: 42 }
                    |            - battlefield_stats_at_least: { side: ours, card: Monastery Swiftspear, power: 2, toughness: 3 }
                    |            - zone_not_contains: { side: ours, zone: hand, card: Miscalculation }
                    |            - zone_count_at_least: { side: ours, zone: hand, count: 2 }
                    |            - annotation_seen:
                    |                type: AbilityWordActive
                    |                details: { AbilityWordName: ExpendedMana, value: 4, threshold: 4, AbilityGrpId: 174034 }
                    """.trimMargin(),
                )

            assertSoftly {
                suite.name shouldBe "sample"
                suite.scenarios shouldHaveSize 1
                val scenario = suite.scenarios.single()
                scenario.id shouldBe "cast-face"
                scenario.fullControl shouldBe false
                scenario.forgeCatalog shouldBe false
                scenario.steps shouldHaveSize 21
                scenario.steps[0] shouldBe WaitStep(listOf(ActionAvailableCondition(AcceptanceActionType.Activate, "Miscalculation")))
                scenario.steps[1] shouldBe ActivateStep("Miscalculation", AcceptanceZone.Hand, 0, 188841)
                scenario.steps[2] shouldBe ChooseStep(AcceptanceCastingTimeOption.Kicker, null)
                scenario.steps[3] shouldBe ChooseStep(null, 1)
                scenario.steps[4] shouldBe
                    ManaTypeChoicesStep(
                        listOf(
                            AcceptanceManaTypeChoice.Green,
                            AcceptanceManaTypeChoice.TwoGeneric,
                            AcceptanceManaTypeChoice.Red,
                        ),
                    )
                scenario.steps[5] shouldBe ModalChoiceStep(0)
                scenario.steps[6] shouldBe StaticChoiceStep(34)
                scenario.steps[7] shouldBe OptionalActionStep(accept = true)
                scenario.steps[8] shouldBe CancelActionStep
                scenario.steps[9] shouldBe TargetStep(CardTargetSpec(AcceptanceSide.Ours, AcceptanceZone.Battlefield, "Lunarch Veteran"))
                scenario.steps[10] shouldBe TargetStep(CardTargetSpec(AcceptanceSide.Opponent, AcceptanceZone.Stack, "Counterspell"))
                scenario.steps[11] shouldBe
                    TargetsStep(
                        listOf(
                            CardTargetSpec(AcceptanceSide.Opponent, AcceptanceZone.Battlefield, "Coral Merfolk"),
                            CardTargetSpec(AcceptanceSide.Opponent, AcceptanceZone.Battlefield, "Savannah Lions"),
                        ),
                    )
                scenario.steps[12] shouldBe
                    DistributeStep(
                        listOf(
                            DistributionAssignment(AcceptanceSide.Opponent, "Coral Merfolk", 1),
                            DistributionAssignment(AcceptanceSide.Opponent, "Savannah Lions", 2),
                        ),
                    )
                scenario.steps[13] shouldBe BlockStep("Centaur Courser", "Juggernaut")
                scenario.steps[14] shouldBe CastStep("Think Twice", AcceptanceZone.Graveyard, AcceptanceAltCost.JumpStart)
                scenario.steps[15] shouldBe SelectCostStep(zone = AcceptanceZone.Hand, cards = listOf("Coral Merfolk"))
                scenario.steps[16] shouldBe SelectCardStep(zone = AcceptanceZone.Sideboard, card = "Environmental Sciences")
                scenario.steps[17] shouldBe SelectCardsStep(zone = AcceptanceZone.Library, cards = listOf("Lightning Bolt", "Counterspell"))
                scenario.steps[18] shouldBe OrderCardsStep(listOf("Counterspell", "Lightning Bolt"))
                scenario.steps[19] shouldBe
                    AttackStep(
                        cards = listOf("Raging Goblin"),
                        target = CardTargetSpec(AcceptanceSide.Opponent, AcceptanceZone.Battlefield, "Liliana of the Veil"),
                    )
                scenario.steps[20] shouldBe
                    ExpectStep(
                        listOf(
                            PromptCondition("OrderReq", 42),
                            BattlefieldStatsAtLeastCondition(AcceptanceSide.Ours, "Monastery Swiftspear", 2, 3),
                            ZoneNotContainsCondition(AcceptanceSide.Ours, AcceptanceZone.Hand, "Miscalculation"),
                            ZoneCountAtLeastCondition(AcceptanceSide.Ours, AcceptanceZone.Hand, 2),
                            AnnotationSeenCondition(
                                "AbilityWordActive",
                                mapOf(
                                    "AbilityWordName" to "ExpendedMana",
                                    "value" to "4",
                                    "threshold" to "4",
                                    "AbilityGrpId" to "174034",
                                ),
                            ),
                        ),
                    )
            }
        }

        test("rejects unknown step keys") {
            shouldThrow<IllegalStateException> {
                AcceptanceSuiteLoader.loadFromText(
                    """
                    name: bad
                    scenarios:
                      - id: bad-step
                        puzzle: any
                        steps:
                          - click_face: {}
                    """.trimIndent(),
                )
            }
        }

        test("reports a missing required key as required, not as a type mismatch") {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    AcceptanceSuiteLoader.loadFromText(
                        """
                        name: bad
                        scenarios:
                          - id: missing-puzzle
                            steps:
                              - resolve_stack: {}
                        """.trimIndent(),
                    )
                }
            exception.message shouldBe "scenario[0] requires exactly one of puzzle or deck"
        }

        test("parses a constructed-deck pregame scenario") {
            val scenario =
                AcceptanceSuiteLoader
                    .loadFromText(
                        """
                        name: opening-hand
                        scenarios:
                          - id: battlefield-put
                            deck: [60 Leyline Axe]
                            opponent_deck: [60 Plains]
                            headless: { full_control: true }
                            steps:
                              - expect: { phase: MAIN1 }
                        """.trimIndent(),
                    ).scenarios
                    .single()

            assertSoftly {
                scenario.puzzle shouldBe null
                scenario.deckList shouldBe "60 Leyline Axe"
                scenario.opponentDeckList shouldBe "60 Plains"
                scenario.fullControl shouldBe true
            }
        }

        test("reports a wrong-typed optional key with its full context path") {
            val exception =
                shouldThrow<IllegalStateException> {
                    AcceptanceSuiteLoader.loadFromText(
                        """
                        name: bad
                        scenarios:
                          - id: bad-zone
                            puzzle: any
                            steps:
                              - cast:
                                  card: Think Twice
                                  zone: [not, a, string]
                        """.trimIndent(),
                    )
                }
            exception.message shouldBe "step[0].cast.zone must be a string"
        }
    })
