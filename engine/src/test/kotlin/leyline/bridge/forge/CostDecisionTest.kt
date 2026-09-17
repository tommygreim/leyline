package leyline.bridge.forge

import forge.ai.AiCostDecision
import forge.ai.PlayerControllerAi
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.card.CardCollectionView
import forge.game.card.CounterType
import forge.game.cost.Cost
import forge.game.cost.CostAdjustment
import forge.game.cost.CostDiscard
import forge.game.cost.CostExert
import forge.game.cost.CostExile
import forge.game.cost.CostExiledMoveToGrave
import forge.game.cost.CostMill
import forge.game.cost.CostPayLife
import forge.game.cost.CostPayment
import forge.game.cost.CostPutCardToLib
import forge.game.cost.CostRemoveCounter
import forge.game.cost.CostReveal
import forge.game.cost.CostSacrifice
import forge.game.cost.CostTapType
import forge.game.cost.CostTeamwork
import forge.game.cost.CostUnattach
import forge.game.cost.PaymentDecision
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import forge.player.HumanCostDecision
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.forge.CostDecision
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.SeatId
import leyline.game.generator.PuzzleSource
import leyline.game.state.GameBridge
import leyline.testkit.TestCardRegistry

class CostDecisionTest :
    FunSpec({

        tags(UnitTag)

        var bridge: GameBridge? = null

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        afterEach {
            bridge?.shutdown()
            bridge = null
        }

        data class Fixture(
            val bridge: GameBridge,
            val player: Player,
            val source: Card,
            val ability: SpellAbility,
            val controller: PlayerController,
            val decision: CostDecision,
        )

        fun fixture(
            sourceName: String = "Lightning Bolt",
            sourceZone: ZoneType = ZoneType.Hand,
            humanHand: String = "Lightning Bolt",
            humanBattlefield: String = "Mountain",
        ): Fixture {
            val localBridge = GameBridge(bridgeTimeoutMs = 0, cardRepository = TestCardRegistry.repo)
            bridge = localBridge
            localBridge.startPuzzle(
                PuzzleSource.loadFromText(
                    """
                    [metadata]
                    Name:Cost Decision Fixture
                    Goal:Win
                    Turns:1
                    Difficulty:Easy
                    Description:Web cost decision fixture.

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20

                    humanhand=$humanHand
                    humanbattlefield=$humanBattlefield
                    humanlibrary=Mountain
                    ailibrary=Mountain
                    """.trimIndent(),
                ),
            )
            leyline.testkit.TestCardRegistry.registerPuzzleCards(localBridge.getGame()!!)
            val player = localBridge.getPlayer(SeatId(1))!!
            val source = player.getCardsIn(sourceZone).first { it.name == sourceName }
            val ability = source.spellAbilities.firstOrNull { it.isActivatedAbility } ?: source.spellAbilities.first()
            ability.activatingPlayer = player
            val controller = localBridge.humanController ?: error("No human controller")
            return Fixture(
                bridge = localBridge,
                player = player,
                source = source,
                ability = ability,
                controller = controller,
                decision =
                    CostDecision(
                        controller,
                        player,
                        ability,
                        false,
                        localBridge.promptBridge(SeatId(1)),
                    ),
            )
        }

        fun isLegalCardSelection(
            decision: CostDecision,
            options: CardCollectionView,
            selected: CardCollectionView,
            amount: Int,
        ): Boolean {
            val method =
                HumanCostDecision::class.java.getDeclaredMethod(
                    "isLegalCardSelection",
                    CardCollectionView::class.java,
                    CardCollectionView::class.java,
                    Int::class.javaPrimitiveType,
                )
            method.isAccessible = true
            return method.invoke(decision, options, selected, amount) as Boolean
        }

        test("visit pay life returns numeric payment when confirm defaults yes") {
            val fx = fixture()
            val result = fx.decision.visit(CostPayLife("3", null))

            result!!.c shouldBe 3
        }

        test("sacrificing the source as an activation cost does not ask for confirmation") {
            val fx =
                fixture(
                    sourceName = "Evolving Wilds",
                    sourceZone = ZoneType.Battlefield,
                    humanHand = "Forest",
                    humanBattlefield = "Evolving Wilds;Mountain",
                )
            val actionCost = Cost("Sac<1/CARDNAME>", true)
            fx.ability.payCosts = actionCost
            val payment = CostPayment(actionCost, fx.ability)
            val adjustedCost = CostAdjustment.adjust(actionCost, fx.ability, false)
            val sacrifice = adjustedCost.costParts.single() as CostSacrifice
            (sacrifice === actionCost.costParts.single()) shouldBe false

            fx.player.game
                .costPaymentStack
                .push(sacrifice, payment)
            try {
                fx.controller.confirmPayment(sacrifice, "sacrifice source?", fx.ability) shouldBe true
            } finally {
                fx.player.game
                    .costPaymentStack
                    .pop()
            }
            fx.bridge.promptBridge(SeatId(1)).history shouldBe emptyList()
        }

        test("sacrificing the source for an unless cost still asks for confirmation") {
            val fx = fixture()
            val effectCost = Cost("Sac<1/CARDNAME>", true)
            val cost = effectCost.costParts.single() as CostSacrifice
            fx.ability.putParam("UnlessCost", "Sac<1/CARDNAME>")

            // The UnlessCost context means activeCost?.cost !== costPart, so the
            // early-return shortcut above is not taken and confirmPayment reaches
            // its real confirm path (OptionalActionGate — this bare fixture's
            // setupBlockingInteractionRuntime doesn't record a promptBridge
            // history entry for it the way the old bridge.requestChoice path did,
            // so there's nothing left here to assert beyond the outcome).
            fx.controller.confirmPayment(cost, "pay unless cost?", fx.ability) shouldBe true
        }

        test("inherited mill visitor returns numeric payment when confirm defaults yes") {
            val fx = fixture()

            fx.decision.visit(CostMill("2"))!!.c shouldBe 2
        }

        test("inherited exert visitor refuses impossible payment") {
            val fx = fixture()

            fx.decision.visit(CostExert("1", "Creature", null)).shouldBeNull()
        }

        test("inherited exiled-to-grave visitor refuses impossible payment") {
            val fx = fixture()

            fx.decision.visit(CostExiledMoveToGrave("1", "Card", null)).shouldBeNull()
        }

        test("visit discard from source returns source card") {
            val fx = fixture()
            val result: PaymentDecision? = fx.decision.visit(CostDiscard("1", "CARDNAME", null))

            result!!.cards.map { it.name } shouldContainExactly listOf("Lightning Bolt")
        }

        test("visit reveal from source returns source card") {
            val fx = fixture()
            val result: PaymentDecision? = fx.decision.visit(CostReveal("1", "CARDNAME", null))

            result!!.cards.map { it.name } shouldContainExactly listOf("Lightning Bolt")
        }

        test("inherited sacrifice visitor refuses impossible payment") {
            val fx = fixture()

            fx.decision.visit(CostSacrifice("1", "Creature", null)).shouldBeNull()
        }

        test("inherited sacrifice visitor handles zero without a choice") {
            val fx = fixture()
            val result: PaymentDecision? = fx.decision.visit(CostSacrifice("0", "Land", null))

            result!!.c shouldBe 0
        }

        test("inherited unattach visitor refuses when nothing is attached") {
            val fx = fixture()

            fx.decision.visit(CostUnattach("OriginalHost", "equipment")).shouldBeNull()
        }

        test("tap cost hook projects teamwork weights and semantic") {
            val fx = fixture()
            val mountain = fx.player.getCardsIn(ZoneType.Battlefield).first()
            val cards =
                CardCollection().apply {
                    add(fx.source)
                    add(mountain)
                }

            fx.controller.chooseCardsForTapCost(cards, fx.ability, CostTeamwork("4"), 0, cards.size, 4, "tap for teamwork")

            with(
                fx.bridge
                    .promptBridge(SeatId(1))
                    .history
                    .single(),
            ) {
                assertSoftly {
                    min shouldBe 1
                    max shouldBe 2
                    semantic shouldBe PromptSemantic.TapPaymentCost
                    costSelectionWeights shouldContainExactly listOf(0, 0)
                    minSelectionWeight shouldBe 4
                }
            }
        }

        test("total-power tap costs preserve the forced-list shortcut for supported and unsupported thresholds") {
            val fx = fixture()
            val cards = CardCollection(fx.source)

            assertSoftly {
                fx.controller
                    .chooseCardsForTapCost(cards, fx.ability, CostTeamwork("1"), 0, 1, 1, "tap for one power")
                    .map { it } shouldContainExactly listOf(fx.source)
                fx.bridge
                    .promptBridge(SeatId(1))
                    .history
                    .single()
                    .semantic shouldBe PromptSemantic.TapPaymentCost
            }

            val unsupported = fixture()
            val unsupportedCards = CardCollection(unsupported.source)
            assertSoftly {
                unsupported.controller
                    .chooseCardsForTapCost(unsupportedCards, unsupported.ability, CostTeamwork("5"), 0, 1, 5, "tap for five power")
                    .map { it } shouldContainExactly listOf(unsupported.source)
                unsupported.bridge.promptBridge(SeatId(1)).history shouldBe emptyList()
            }
        }

        test("unsupported total-power threshold remains a Generic residual without weights") {
            val fx = fixture()
            val mountain = fx.player.getCardsIn(ZoneType.Battlefield).first()
            val cards =
                CardCollection().apply {
                    add(fx.source)
                    add(mountain)
                }

            fx.controller.chooseCardsForTapCost(cards, fx.ability, CostTeamwork("5"), 0, cards.size, 5, "tap for five power")

            with(
                fx.bridge
                    .promptBridge(SeatId(1))
                    .history
                    .single(),
            ) {
                assertSoftly {
                    semantic shouldBe PromptSemantic.Generic
                    costSelectionWeights shouldBe emptyList<Int>()
                    minSelectionWeight.shouldBeNull()
                }
            }
        }

        test("tap cost hook keeps generic any-number projection without weights") {
            val fx = fixture()
            val mountain = fx.player.getCardsIn(ZoneType.Battlefield).first()
            val cards =
                CardCollection().apply {
                    add(fx.source)
                    add(mountain)
                }

            fx.controller.chooseCardsForTapCost(
                cards,
                fx.ability,
                CostTapType("Any", "Creature", "creatures", false),
                1,
                cards.size,
                null,
                "tap any number",
            )

            with(
                fx.bridge
                    .promptBridge(SeatId(1))
                    .history
                    .single(),
            ) {
                assertSoftly {
                    min shouldBe 1
                    max shouldBe 2
                    semantic shouldBe PromptSemantic.Generic
                    costSelectionWeights shouldBe emptyList<Int>()
                    minSelectionWeight.shouldBeNull()
                }
            }
        }

        test("fixed-count station tap cost projects StationTapCost through the exact seam") {
            val fx = fixture()
            val mountain = fx.player.getCardsIn(ZoneType.Battlefield).first()
            val cards =
                CardCollection().apply {
                    add(fx.source)
                    add(mountain)
                }
            fx.ability.setKeyword(
                forge.game.keyword.Keyword
                    .getInstance("Station:8"),
            )

            fx.controller.chooseCardsForCost(
                cards,
                fx.ability,
                CostTapType("1", "Creature.Other", "another creature", false),
                1,
                true,
                "tap another creature",
            )

            with(
                fx.bridge
                    .promptBridge(SeatId(1))
                    .history
                    .single(),
            ) {
                assertSoftly {
                    min shouldBe 1
                    max shouldBe 1
                    semantic shouldBe PromptSemantic.StationTapCost
                }
            }
        }

        test("Forge cost policy selects a payable tap cost and refuses it once unavailable") {
            val fx = fixture()
            val mountain = fx.player.getCardsIn(ZoneType.Battlefield).first()
            val cost = CostTapType("1", "Land", "a land", false)
            val ai = PlayerControllerAi(fx.player.game, fx.player, fx.player.lobbyPlayer)
            fx.player.addController(Long.MAX_VALUE, fx.player, ai, false)
            try {
                cost.accept(AiCostDecision(fx.player, fx.ability, false))!!.cards.map { it.id } shouldContainExactly
                    listOf(mountain.id)
                mountain.tap(true, fx.ability, fx.player)
                cost.accept(AiCostDecision(fx.player, fx.ability, false)).shouldBeNull()
            } finally {
                fx.player.removeController(Long.MAX_VALUE, false)
            }
        }

        test("Forge cost policy selects enough creatures for crew") {
            val localBridge = GameBridge(bridgeTimeoutMs = 0, cardRepository = TestCardRegistry.repo)
            bridge = localBridge
            localBridge.startPuzzle(
                PuzzleSource.loadFromText(
                    """
                    [metadata]
                    Name:Crew Cost Decision Fixture
                    Goal:Win
                    Turns:1
                    Difficulty:Easy
                    Description:Crew cost decision fixture.

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20

                    humanhand=Lightning Bolt
                    humanbattlefield=Llanowar Elves;Frenzied Baloth;Forest
                    humanlibrary=Forest
                    ailibrary=Mountain
                    """.trimIndent(),
                ),
            )
            TestCardRegistry.registerPuzzleCards(localBridge.getGame()!!)
            val player = localBridge.getPlayer(SeatId(1))!!
            val source = player.getCardsIn(ZoneType.Hand).first { it.name == "Lightning Bolt" }
            val ability = source.spellAbilities.first()
            ability.setKeyword(
                forge.game.keyword.Keyword
                    .getInstance("Crew:4"),
            )
            val cost = CostTapType("Any", "Creature.YouCtrl+withTotalPowerGE4", "creatures", false)
            player
                .getCardsIn(ZoneType.Battlefield)
                .filter { it.isCreature }
                .map { it.name to it.netPower } shouldContainExactlyInAnyOrder
                listOf("Llanowar Elves" to 1, "Frenzied Baloth" to 3)
            val ai = PlayerControllerAi(player.game, player, player.lobbyPlayer)
            player.addController(Long.MAX_VALUE, player, ai, false)
            try {
                cost
                    .accept(AiCostDecision(player, ability, false))!!
                    .cards
                    .map { it.name } shouldContainExactlyInAnyOrder listOf("Llanowar Elves", "Frenzied Baloth")
            } finally {
                player.removeController(Long.MAX_VALUE, false)
            }
        }

        test("cost hook accepts non-list cost parts after seam widening") {
            val fx = fixture()
            val cards = CardCollection(fx.source)

            fx.controller
                .chooseCardsForCost(
                    cards,
                    fx.ability,
                    CostRemoveCounter(
                        "1",
                        CounterType.getType("P1P1"),
                        "Creature",
                        "creature",
                        listOf(ZoneType.Battlefield),
                        false,
                    ),
                    1,
                    true,
                    "remove counters from one",
                ).map { it } shouldContainExactly listOf(fx.source)

            with(
                fx.bridge
                    .promptBridge(SeatId(1))
                    .history
                    .single(),
            ) {
                assertSoftly {
                    min shouldBe 1
                    max shouldBe 1
                    semantic shouldBe PromptSemantic.Generic
                }
            }
        }

        test("existing exile cost hook keeps exact-cardinality projection") {
            val fx = fixture()
            val cards = CardCollection(fx.source)

            fx.controller
                .chooseCardsForCost(
                    cards,
                    fx.ability,
                    CostExile("1", "Card", null, forge.game.zone.ZoneType.Hand),
                    1,
                    true,
                    "exile one",
                ).map { it } shouldContainExactly listOf(fx.source)

            with(
                fx.bridge
                    .promptBridge(SeatId(1))
                    .history
                    .single(),
            ) {
                assertSoftly {
                    min shouldBe 1
                    max shouldBe 1
                    semantic shouldBe PromptSemantic.Generic
                }
            }
        }

        test("reveal cost hook keeps exact-cardinality projection") {
            val fx = fixture()
            val cards = CardCollection(fx.source)

            fx.controller
                .chooseCardsForRevealCost(
                    cards,
                    fx.ability,
                    CostReveal("1", "Card", null),
                    1,
                    true,
                    false,
                    "reveal one",
                ).map { it } shouldContainExactly listOf(fx.source)

            with(
                fx.bridge
                    .promptBridge(SeatId(1))
                    .history
                    .single(),
            ) {
                assertSoftly {
                    min shouldBe 1
                    max shouldBe 1
                    semantic shouldBe PromptSemantic.Generic
                }
            }
        }

        test("exile cost hook projects plain min-max selection without weights") {
            val fx = fixture()
            val mountain = fx.player.getCardsIn(ZoneType.Battlefield).first()
            val cards =
                CardCollection().apply {
                    add(fx.source)
                    add(mountain)
                }

            fx.controller.chooseCardsForExileCost(
                cards,
                fx.ability,
                CostExile("2", "Card+withTotalCMCGE4", null, ZoneType.Hand),
                1,
                cards.size,
                "CMC",
                4,
                false,
                true,
                "exile cards with total mana value",
            )

            with(
                fx.bridge
                    .promptBridge(SeatId(1))
                    .history
                    .single(),
            ) {
                assertSoftly {
                    min shouldBe 1
                    max shouldBe 2
                    semantic shouldBe PromptSemantic.Generic
                    costSelectionWeights shouldBe emptyList<Int>()
                    minSelectionWeight.shouldBeNull()
                }
            }
        }

        test("existing put-to-library cost hook keeps exact-cardinality projection") {
            val fx = fixture()
            val cards = CardCollection(fx.source)

            fx.controller
                .chooseCardsForCost(
                    cards,
                    fx.ability,
                    CostPutCardToLib("1", "0", "Card", null, forge.game.zone.ZoneType.Hand),
                    1,
                    true,
                    "put one",
                ).map { it } shouldContainExactly listOf(fx.source)

            with(
                fx.bridge
                    .promptBridge(SeatId(1))
                    .history
                    .single(),
            ) {
                assertSoftly {
                    min shouldBe 1
                    max shouldBe 1
                    semantic shouldBe PromptSemantic.Generic
                }
            }
        }

        test("Forge rejects duplicate and out-of-candidate controller selections") {
            val fx = fixture()
            val options = CardCollection(fx.source)
            val duplicate =
                CardCollection().apply {
                    add(fx.source)
                    add(fx.source)
                }
            val outsider = fx.player.getCardsIn(ZoneType.Battlefield).first()

            assertSoftly {
                isLegalCardSelection(fx.decision, options, CardCollection(fx.source), 1) shouldBe true
                isLegalCardSelection(fx.decision, options, CardCollection(), 1) shouldBe false
                isLegalCardSelection(fx.decision, options, duplicate, 2) shouldBe false
                isLegalCardSelection(fx.decision, options, CardCollection(outsider), 1) shouldBe false
            }
        }
    })
