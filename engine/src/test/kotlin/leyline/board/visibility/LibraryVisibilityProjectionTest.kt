package leyline.board.visibility

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.StateMapperShell
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.Visibility

class LibraryVisibilityProjectionTest :
    BoardTest({
        test("Glarb grants its controller permission to inspect the current library top") {
            val board = startPuzzleAtMain1(GLARB_LIBRARY_PUZZLE)
            val top =
                board.human
                    .getZone(ZoneType.Library)
                    .cards
                    .first()

            val snapshot = capture(board)
            assertSoftly {
                top.name shouldBe "Command Tower"
                top.mayPlayerLook(board.human).shouldBeTrue()
                top.mayPlayerLook(board.ai).shouldBeFalse()
                snapshot.objects.getValue(ForgeCardId(top.id)).mayLookSeatIds shouldBe setOf(SeatId(1))
                snapshot.zones
                    .getValue(ZoneIds.P1_LIBRARY)
                    .contents
                    .first() shouldBe ForgeCardId(top.id)
            }
            val ownerGsm = project(board, snapshot, viewingSeatId = 1)
            val ownerTop = ownerGsm.objectFor(board, top.id)
            assertSoftly {
                ownerTop.visibility shouldBe Visibility.Private
                ownerTop.viewersList shouldBe listOf(1)
                project(board, snapshot, viewingSeatId = 2)
                    .gameObjectsList
                    .filter { it.instanceId == board.instanceId(top.id) }
                    .shouldBeEmpty()
            }
        }

        test("inspection follows the current top when library order changes") {
            val board = startPuzzleAtMain1(GLARB_LIBRARY_PUZZLE)
            val library = board.human.getZone(ZoneType.Library)
            val previousSnapshot = capture(board)
            val oldTop = library.cards.first()

            library.reorder(oldTop, library.size() - 1)
            board.game.action.checkStateEffects(true)

            val currentSnapshot = capture(board)
            val currentTop = library.cards.first()
            val gsm = project(board, currentSnapshot, viewingSeatId = 1, previousSnapshot = previousSnapshot)
            val opponentGsm = project(board, currentSnapshot, viewingSeatId = 2, previousSnapshot = previousSnapshot)

            assertSoftly {
                currentTop.name shouldBe "Lightning Bolt"
                currentTop.mayPlayerLook(board.human).shouldBeTrue()
                gsm.objectFor(board, currentTop.id).visibility shouldBe Visibility.Private
                gsm.objectFor(board, oldTop.id).visibility shouldBe Visibility.Hidden
                gsm.objectFor(board, oldTop.id).grpId shouldBe 0
                gsm.objectFor(board, oldTop.id).viewersList.shouldBeEmpty()
                opponentGsm.objectFor(board, oldTop.id).grpId shouldBe 0
            }
        }

        test("inspection follows the current top after a draw and shuffle") {
            val board = startPuzzleAtMain1(GLARB_LIBRARY_PUZZLE)
            val library = board.human.getZone(ZoneType.Library)
            val beforeDraw = capture(board)

            board.game.action.moveToHand(library.cards.first(), null)
            board.game.action.checkStateEffects(true)

            val afterDraw = capture(board)
            val postDrawTop = library.cards.first()
            assertSoftly {
                postDrawTop.name shouldBe "Lightning Bolt"
                postDrawTop.mayPlayerLook(board.human).shouldBeTrue()
                project(board, afterDraw, viewingSeatId = 1, previousSnapshot = beforeDraw)
                    .objectFor(board, postDrawTop.id)
                    .visibility shouldBe Visibility.Private
            }

            board.human.shuffle(null)
            board.game.action.checkStateEffects(true)

            val shuffledTop = library.cards.first()
            assertSoftly {
                shuffledTop.mayPlayerLook(board.human).shouldBeTrue()
                project(board, capture(board), viewingSeatId = 1, previousSnapshot = afterDraw)
                    .objectFor(board, shuffledTop.id)
                    .visibility shouldBe Visibility.Private
            }
        }

        test("removing the permission source withdraws the inspected identity") {
            val board = startPuzzleAtMain1(GLARB_LIBRARY_PUZZLE)
            val top =
                board.human
                    .getZone(ZoneType.Library)
                    .cards
                    .first()
            val previousSnapshot = capture(board)
            val glarb =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Glarb, Calamity's Augur" }

            board.game.action.moveToGraveyard(glarb, null)
            board.game.action.checkStateEffects(true)

            top.mayPlayerLook(board.human).shouldBeFalse()
            val currentSnapshot = capture(board)
            val withdrawn = project(board, currentSnapshot, viewingSeatId = 1, previousSnapshot = previousSnapshot).objectFor(board, top.id)
            val opponentWithdrawal =
                project(
                    board,
                    currentSnapshot,
                    viewingSeatId = 2,
                    previousSnapshot = previousSnapshot,
                ).objectFor(board, top.id)
            val transientReveal =
                project(
                    board,
                    currentSnapshot,
                    viewingSeatId = 1,
                    previousSnapshot = previousSnapshot,
                    revealForSeat = 1,
                ).objectFor(board, top.id)
            assertSoftly {
                withdrawn.visibility shouldBe Visibility.Hidden
                withdrawn.grpId shouldBe 0
                withdrawn.viewersList.shouldBeEmpty()
                opponentWithdrawal.visibility shouldBe Visibility.Hidden
                opponentWithdrawal.grpId shouldBe 0
                transientReveal.visibility shouldBe Visibility.Private
                transientReveal.grpId shouldBe currentSnapshot.objects.getValue(ForgeCardId(top.id)).grpId
                transientReveal.viewersList shouldBe listOf(1)
            }
        }
    })

private fun capture(board: Board): GsmSnapshot = GsmSnapshot.capture(board.game, board.bridge, "test", 1)

private fun project(
    board: Board,
    snapshot: GsmSnapshot,
    viewingSeatId: Int,
    previousSnapshot: GsmSnapshot? = null,
    revealForSeat: Int? = null,
) = StateMapperShell
    .buildFromSnapshot(
        snap = snapshot,
        gameStateId = 1,
        matchId = "test",
        bridge = board.bridge,
        viewingSeatId = viewingSeatId,
        revealForSeat = revealForSeat,
        prev = previousSnapshot,
        effectFacts = board.bridge.materializeEffectProjectionFacts(),
        abilityExhaustionFacts = AbilityExhaustionFacts(),
    ).gsm

private fun wotc.mtgo.gre.external.messaging.Messages.GameStateMessage.objectFor(
    board: Board,
    cardId: Int,
): GameObjectInfo = gameObjectsList.single { it.instanceId == board.instanceId(cardId) }

private val GLARB_LIBRARY_PUZZLE =
    """
    [metadata]
    Name:Glarb Library Inspection
    Goal:Win
    Turns:4
    Difficulty:Tutorial
    Description:Inspect the top card of your library while Glarb is on the battlefield.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanbattlefield=Glarb, Calamity's Augur;Island;Forest;Swamp
    humanlibrary=Command Tower;Lightning Bolt;Forest;Island;Swamp
    aibattlefield=Plains
    ailibrary=Plains;Plains;Plains;Plains;Plains
    """.trimIndent()
