package leyline.bridge.forge

import forge.StaticData
import forge.card.CardFacePredicates
import forge.game.zone.ZoneType
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest

class RevealTrackingAiControllerTest :
    BoardTest({
        beforeSpec { leyline.testkit.registerUpstreamCatalogCards("Petrified Hamlet") }
        test("AI land naming rejects a known opposing nonland") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Duress", human, ZoneType.Hand)
                    addCard("Petrified Hamlet", ai, ZoneType.Hand)
                    addCard("Mountain", ai, ZoneType.Battlefield)
                }
            val effect =
                board.ai.hand
                    .card("Petrified Hamlet")
                    .triggers
                    .single()
                    .ensureAbility()
            val controller = RevealTrackingAiController(board.game, board.ai, board.bridge.promptBridge(SeatId(1)), SeatId(2))
            val name = controller.chooseCardName(effect, CardFacePredicates.valid("Land"), "Land", "Name a land")
            StaticData
                .instance()
                .commonCards
                .getFaceByName(name)
                .type.isLand shouldBe true
        }

        test("AI naming can choose a legal catalog face absent from the game") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Duress", human, ZoneType.Hand)
                    addCard("Petrified Hamlet", ai, ZoneType.Hand)
                }
            val effect =
                board.ai.hand
                    .card("Petrified Hamlet")
                    .triggers
                    .single()
                    .ensureAbility()
            val controller = RevealTrackingAiController(board.game, board.ai, board.bridge.promptBridge(SeatId(1)), SeatId(2))
            val onlyForest = java.util.function.Predicate<forge.card.ICardFace> { it.name == "Forest" }
            controller.chooseCardName(effect, onlyForest, "Land", "Name a land") shouldBe "Forest"
        }

        test("legal unrestricted strategic choices are preserved") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Duress", human, ZoneType.Hand)
                    addCard("Petrified Hamlet", ai, ZoneType.Hand)
                }
            val effect =
                board.ai.hand
                    .card("Petrified Hamlet")
                    .triggers
                    .single()
                    .ensureAbility()
            val controller = RevealTrackingAiController(board.game, board.ai, board.bridge.promptBridge(SeatId(1)), SeatId(2))
            controller.chooseCardName(effect, CardFacePredicates.valid("Card"), "Card", "Name a card") shouldBe "Duress"
        }
    })
