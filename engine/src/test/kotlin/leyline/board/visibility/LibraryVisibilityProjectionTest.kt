package leyline.board.visibility

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.LibraryKnowledgeTracker
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.ProjectionState
import leyline.testkit.Board
import leyline.testkit.BoardTest
import leyline.testkit.StateMapperShell
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.Visibility

class LibraryVisibilityProjectionTest :
    BoardTest({
        test("a public permanent put on library bottom remains inspectable by both players until shuffle") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Library)
                    addCard("Island", human, ZoneType.Library)
                }
            val bear = board.human.battlefield.card("Grizzly Bears")
            val previous = capture(board)
            board.game.action.moveToLibrary(bear, -1, null)
            val snapshot = capture(board)
            // The collector specializes battlefield-to-library moves as CardBounced.
            val events = listOf(GameEvent.CardBounced(ForgeCardId(bear.id), SeatId(1)))
            val knowledge =
                LibraryKnowledgeTracker.plan(
                    LibraryKnowledgeTracker.State(),
                    snapshot,
                    previous,
                    events,
                    board.bridge::getOrAllocInstanceId,
                )
            val state = board.bridge.projectionStateSnapshot().copy(libraryKnowledge = knowledge)
            val retained =
                LibraryKnowledgeTracker.plan(
                    knowledge,
                    snapshot,
                    snapshot,
                    emptyList(),
                    board.bridge::getOrAllocInstanceId,
                )
            assertSoftly {
                knowledge.viewers[ForgeCardId(bear.id)] shouldBe setOf(SeatId(1), SeatId(2))
                retained shouldBe knowledge
                // Projection is pure: a real zone move allocates a new client ID
                // in its result, without mutating the live board's identity map.
                project(board, snapshot, 1, projectionState = state)
                    .gameObjectsList
                    .single { it.zoneId == ZoneIds.P1_LIBRARY }
                    .visibility shouldBe Visibility.Public
                project(board, snapshot, 2, projectionState = state)
                    .gameObjectsList
                    .single { it.zoneId == ZoneIds.P1_LIBRARY }
                    .grpId shouldBe
                    snapshot.objects.getValue(ForgeCardId(bear.id)).grpId
            }
            // A later, event-free frame must retain the public identity as well.
            val laterState = state.copy(libraryKnowledge = retained)
            for (viewer in listOf(1, 2)) {
                project(board, snapshot, viewer, projectionState = laterState)
                    .gameObjectsList
                    .single { it.zoneId == ZoneIds.P1_LIBRARY }
                    .visibility shouldBe Visibility.Public
            }
            board.human.shuffle(null)
            val shuffled = capture(board)
            val forgotten =
                LibraryKnowledgeTracker.plan(
                    knowledge,
                    shuffled,
                    snapshot,
                    listOf(GameEvent.LibraryShuffled(SeatId(1))),
                    board.bridge::getOrAllocInstanceId,
                )
            forgotten.viewers shouldBe emptyMap()
            val withdrawal =
                project(
                    board,
                    shuffled,
                    1,
                    snapshot,
                    events = FrameEventLog(listOf(GameEvent.LibraryShuffled(SeatId(1)))),
                    projectionState = state,
                )
            withClue("withdrawal objects=${withdrawal.gameObjectsList}") {
                withdrawal.gameObjectsList.single { it.zoneId == ZoneIds.P1_LIBRARY }.visibility shouldBe Visibility.Hidden
            }
        }

        test("a scried bottom card is remembered only by the scrying player") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Library)
                    addCard("Forest", human, ZoneType.Library)
                }
            val bear = board.human.library.card("Grizzly Bears")
            val previous = capture(board)
            board.human.getZone(ZoneType.Library).reorder(bear, 1)
            val snapshot = capture(board)
            val event = GameEvent.Scry(SeatId(1), emptyList(), listOf(board.instanceId(bear.id)))
            val knowledge =
                LibraryKnowledgeTracker.plan(
                    LibraryKnowledgeTracker.State(),
                    snapshot,
                    previous,
                    listOf(event),
                    board.bridge::getOrAllocInstanceId,
                )
            val state = board.bridge.projectionStateSnapshot().copy(libraryKnowledge = knowledge)
            assertSoftly {
                knowledge.viewers[ForgeCardId(bear.id)] shouldBe setOf(SeatId(1))
                project(board, snapshot, 1, projectionState = state).objectFor(board, bear.id).viewersList shouldBe listOf(1)
                project(board, snapshot, 2, projectionState = state)
                    .gameObjectsList
                    .filter { it.instanceId == board.instanceId(bear.id) }
                    .shouldBeEmpty()
            }
            val opponentShuffled =
                LibraryKnowledgeTracker.plan(
                    knowledge,
                    snapshot,
                    previous,
                    listOf(GameEvent.LibraryShuffled(SeatId(2))),
                    board.bridge::getOrAllocInstanceId,
                )
            opponentShuffled shouldBe knowledge
            val afterOwnShuffle =
                LibraryKnowledgeTracker.plan(
                    knowledge,
                    snapshot,
                    previous,
                    listOf(event, GameEvent.LibraryShuffled(SeatId(1))),
                    board.bridge::getOrAllocInstanceId,
                )
            afterOwnShuffle.viewers shouldBe emptyMap()
            val afterNewLook =
                LibraryKnowledgeTracker.plan(
                    knowledge,
                    snapshot,
                    previous,
                    listOf(GameEvent.LibraryShuffled(SeatId(1)), event),
                    board.bridge::getOrAllocInstanceId,
                )
            afterNewLook.viewers[ForgeCardId(bear.id)] shouldBe setOf(SeatId(1))
        }

        test("putting a face-down permanent into a library does not reveal its identity") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Library)
                }
            val bear = board.human.battlefield.card("Grizzly Bears")
            bear.turnFaceDown()
            val previous = capture(board)
            previous.objects
                .getValue(ForgeCardId(bear.id))
                .isFaceDown
                .shouldBeTrue()
            board.game.action.moveToLibrary(bear, -1, null)
            val knowledge =
                LibraryKnowledgeTracker.plan(
                    LibraryKnowledgeTracker.State(),
                    capture(board),
                    previous,
                    listOf(GameEvent.CardBounced(ForgeCardId(bear.id), SeatId(1))),
                    board.bridge::getOrAllocInstanceId,
                )
            knowledge.viewers shouldBe emptyMap()
        }

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
            val shuffledSnapshot = capture(board)
            val shuffleProjection =
                project(
                    board,
                    shuffledSnapshot,
                    viewingSeatId = 1,
                    previousSnapshot = afterDraw,
                    events = FrameEventLog(listOf(GameEvent.LibraryShuffled(SeatId(1)))),
                )
            assertSoftly {
                shuffledTop.mayPlayerLook(board.human).shouldBeTrue()
                val inspectedTop =
                    shuffleProjection.gameObjectsList.single {
                        it.zoneId == ZoneIds.P1_LIBRARY && it.visibility == Visibility.Private
                    }
                inspectedTop.viewersList shouldBe listOf(1)
                inspectedTop.grpId shouldBe shuffledSnapshot.objects.getValue(ForgeCardId(shuffledTop.id)).grpId
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
    events: FrameEventLog = FrameEventLog.EMPTY,
    projectionState: ProjectionState = board.bridge.projectionStateSnapshot(),
) = StateMapperShell
    .buildFromSnapshot(
        snap = snapshot,
        gameStateId = 1,
        matchId = "test",
        bridge = board.bridge,
        viewingSeatId = viewingSeatId,
        revealForSeat = revealForSeat,
        prev = previousSnapshot,
        events = events,
        mechanicSourceFacts = MechanicSourceFacts(),
        projectionState = projectionState,
        effectFacts = board.bridge.materializeEffectProjectionFacts(),
        abilityExhaustionFacts = AbilityExhaustionFacts(),
    ).gsm

private fun wotc.mtgo.gre.external.messaging.Messages.GameStateMessage.objectFor(
    board: Board,
    cardId: Int,
): GameObjectInfo =
    gameObjectsList.singleOrNull { it.instanceId == board.instanceId(cardId) }
        ?: error("No projected card $cardId / iid ${board.instanceId(cardId)}: $gameObjectsList")

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
