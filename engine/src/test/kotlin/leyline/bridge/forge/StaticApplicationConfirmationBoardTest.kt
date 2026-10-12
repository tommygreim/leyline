package leyline.bridge.forge

import forge.game.player.PlayerActionConfirmMode
import forge.game.zone.ZoneType
import io.kotest.matchers.shouldBe
import leyline.bridge.NonInteractiveScope
import leyline.bridge.types.SeatId
import leyline.testkit.BoardTest

/** Callback-only paths that must complete without a client interaction. */
class StaticApplicationConfirmationBoardTest :
    BoardTest({
        test("noninteractive static confirmation declines without publishing") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val source = board.human.battlefield.card("Grizzly Bears")
            val controller = checkNotNull(board.bridge.humanController)
            val history =
                board.bridge
                    .promptBridge(SeatId(1))
                    .history.size

            NonInteractiveScope.quiet {
                controller.confirmStaticApplication(source, null, "Apply optional static ability?", null)
            } shouldBe false
            board.bridge
                .promptBridge(SeatId(1))
                .history.size shouldBe history
        }

        test("alternative damage assignment still declines without publishing") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val source = board.human.battlefield.card("Grizzly Bears")
            val controller = checkNotNull(board.bridge.humanController)
            val history =
                board.bridge
                    .promptBridge(SeatId(1))
                    .history.size

            controller.confirmStaticApplication(
                source,
                PlayerActionConfirmMode.AlternativeDamageAssignment,
                "Assign combat damage as though unblocked?",
                null,
            ) shouldBe false
            board.bridge
                .promptBridge(SeatId(1))
                .history.size shouldBe history
        }
    })
