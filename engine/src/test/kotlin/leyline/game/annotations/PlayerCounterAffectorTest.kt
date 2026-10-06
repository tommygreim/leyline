package leyline.game.annotations

import forge.game.zone.ZoneType
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.GameEvent
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer

class PlayerCounterAffectorTest :
    BoardTest({
        test("player counter does not borrow an unrelated later resolution") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ -> addCard("Forest", human, ZoneType.Battlefield) }
            val unrelated = game.humanPlayer.battlefield.card("Forest")
            val counter = GameEvent.PlayerCountersChanged(SeatId(1), "ENERGY", 0, 1)
            val events = listOf(counter, GameEvent.SpellResolved(ForgeCardId(unrelated.id), hasFizzled = false))
            val snap = GsmSnapshot.capture(game, bridge, Board.TEST_MATCH_ID, 1)
            val ctx = annotationContext(bridge, snap, events)

            ctx.playerCounterAffectorFor(0, counter) shouldBe null
            val annotations =
                MechanicAnnotations.mechanicAnnotations(
                    events,
                    idResolver = ctx.frameIds::cardIid,
                    playerCounterAffectorResolver = ctx::playerCounterAffectorFor,
                )
            annotations.transient.single().affectorId shouldBe 0
        }

        test("resolved instant can name its current graveyard card as player counter source") {
            val (bridge, game, _) =
                startWithBoard { _, human, _ -> addCard("Shock", human, ZoneType.Graveyard) }
            val instant = game.humanPlayer.graveyard.card("Shock")
            val source = ForgeCardId(instant.id)
            val counter =
                GameEvent.PlayerCountersChanged(
                    SeatId(1),
                    "ENERGY",
                    0,
                    2,
                    sourceCardId = source,
                )
            val snap = GsmSnapshot.capture(game, bridge, Board.TEST_MATCH_ID, 1)
            val ctx = annotationContext(bridge, snap, listOf(counter))

            ctx.playerCounterAffectorFor(0, counter) shouldBe ctx.frameIds.cardIid(source)
        }
    })
