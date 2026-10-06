package leyline.board.visibility

import forge.game.card.CardCollection
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.TargetingCoordinator
import leyline.bridge.forge.RevealTrackingAiController
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.RevealZone
import leyline.bridge.types.SeatId
import leyline.game.mapping.ZoneIds
import leyline.testkit.BoardTest
import leyline.testkit.aiPlayer
import leyline.testkit.annotationOrNull
import leyline.testkit.annotations
import leyline.testkit.detailInt
import leyline.testkit.humanPlayer
import leyline.testkit.persistentAnnotation
import leyline.testkit.persistentAnnotationOrNull
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class RevealStateTest :
    BoardTest({
        test("puzzle opponent records public reveals from either owner but not a private library look") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:Reveal audience regression
                    Goal:Survive
                    Turns:2
                    Difficulty:Easy
                    Description:Track public reveal audiences.
                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanlibrary=Burst Lightning;Mountain
                    ailibrary=Burst Lightning;Forest
                    """.trimIndent(),
                )
            val human = board.game.humanPlayer
            val ai = board.game.aiPlayer
            val humanCard = human.getZone(ZoneType.Library).cards.first()
            val aiCard = ai.getZone(ZoneType.Library).cards.first()
            human.controller.reveal(CardCollection(listOf(humanCard)), ZoneType.Library, human, null, true)
            val privateLook = board.bridge.drainReveals(1).single()
            privateLook.viewerSeatId shouldBe privateLook.ownerSeatId

            // GameAction's public-reveal path deliberately skips the owner.
            board.game.action.reveal(CardCollection(listOf(humanCard)), human, true)
            val humanReveal = board.bridge.drainReveals(1).single()
            board.game.action.reveal(CardCollection(listOf(aiCard)), ai, true)
            val aiReveal = board.bridge.drainReveals(1).single()
            assertSoftly {
                humanReveal.forgeCardIds shouldBe listOf(ForgeCardId(humanCard.id))
                humanReveal.ownerSeatId shouldBe SeatId(1)
                humanReveal.viewerSeatId shouldBe SeatId(2)
                aiReveal.forgeCardIds shouldBe listOf(ForgeCardId(aiCard.id))
                aiReveal.ownerSeatId shouldBe SeatId(2)
                aiReveal.viewerSeatId shouldBe SeatId(1)
            }
        }

        test("public library selection retains opponent knowledge after moving to hand in the same frame") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:Reveal move regression
                    Goal:Survive
                    Turns:2
                    Difficulty:Easy
                    Description:Reveal a selection then move it to hand.
                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanlibrary=Burst Lightning;Mountain
                    ailibrary=Forest
                    """.trimIndent(),
                )
            val human = board.game.humanPlayer
            val card = human.getZone(ZoneType.Library).cards.first()
            val gsm =
                board.snapshotDiff {
                    board.game.action.reveal(CardCollection(listOf(card)), human, true)
                    board.game.action.moveToHand(card, null)
                }
            gsm.persistentAnnotation(AnnotationType.InstanceRevealedToOpponent).affectedIdsList shouldBe
                listOf(board.instanceId(card.id))
        }

        test("hand reveal emits both contracts with separate affected identities") {
            val board = startWithBoard { _, human, _ -> addCard("Lightning Bolt", human, ZoneType.Hand) }
            val card =
                board.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val actualId = board.instanceId(card.id)

            val gsm =
                board.snapshotDiff {
                    board.bridge
                        .promptBridge(SeatId(1))
                        .recordReveal(
                            listOf(ForgeCardId(card.id)),
                            SeatId(1),
                            SeatId(2),
                            RevealZone.HAND,
                            ForgeCardId(card.id),
                        )
                }

            val revealedView = gsm.gameObjectsList.single { it.type == GameObjectType.RevealedCard }
            val faceUp = gsm.persistentAnnotation(AnnotationType.CardRevealed)
            val known = gsm.persistentAnnotation(AnnotationType.InstanceRevealedToOpponent)
            assertSoftly {
                gsm.annotationOrNull(AnnotationType.RevealedCardCreated)?.affectedIdsList shouldBe
                    listOf(revealedView.instanceId)
                faceUp.affectedIdsList shouldBe listOf(revealedView.instanceId)
                faceUp.detailInt("source_zone") shouldBe ZoneIds.P1_HAND
                known.affectorId shouldBe actualId
                known.affectedIdsList shouldBe listOf(actualId)
                known.detailsList shouldBe emptyList()
            }
        }

        test("library reveal uses library view and tracks the actual hidden card") {
            val board = startWithBoard { _, human, _ -> addCard("Giant Growth", human, ZoneType.Library) }
            val card =
                board.game.humanPlayer
                    .getZone(ZoneType.Library)
                    .cards
                    .single()

            val gsm =
                board.snapshotDiff {
                    board.bridge
                        .promptBridge(SeatId(1))
                        .recordReveal(
                            listOf(ForgeCardId(card.id)),
                            SeatId(1),
                            SeatId(2),
                            RevealZone.LIBRARY,
                            ForgeCardId(card.id),
                        )
                }

            assertSoftly {
                gsm.gameObjectsList.single { it.type == GameObjectType.RevealedCard }.zoneId shouldBe ZoneIds.P1_LIBRARY
                gsm.persistentAnnotation(AnnotationType.CardRevealed).detailInt("source_zone") shouldBe ZoneIds.P1_LIBRARY
                gsm.persistentAnnotation(AnnotationType.InstanceRevealedToOpponent).affectedIdsList shouldBe
                    listOf(board.instanceId(card.id))
            }
        }

        test("simultaneous reveals coexist one row per temporary and actual identity") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                    addCard("Giant Growth", human, ZoneType.Hand)
                }
            val cards =
                board.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .toList()

            val gsm =
                board.snapshotDiff {
                    board.bridge.promptBridge(SeatId(1)).recordReveal(
                        cards.map { ForgeCardId(it.id) },
                        SeatId(1),
                        SeatId(2),
                        RevealZone.HAND,
                        ForgeCardId(cards.first().id),
                    )
                }

            assertSoftly {
                gsm.annotations(AnnotationType.RevealedCardCreated) shouldHaveSize 2
                gsm.persistentAnnotationsList.count { AnnotationType.CardRevealed in it.typeList } shouldBe 2
                gsm.persistentAnnotationsList.count { AnnotationType.InstanceRevealedToOpponent in it.typeList } shouldBe 2
            }
        }

        test("face-up state expires while opponent knowledge survives unrelated GSMs") {
            val board = startWithBoard { _, human, _ -> addCard("Lightning Bolt", human, ZoneType.Hand) }
            val card =
                board.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val reveal =
                board.snapshotDiff {
                    board.bridge
                        .promptBridge(SeatId(1))
                        .recordReveal(
                            listOf(ForgeCardId(card.id)),
                            SeatId(1),
                            SeatId(2),
                            RevealZone.HAND,
                            ForgeCardId(card.id),
                        )
                }
            val faceUpId = reveal.persistentAnnotation(AnnotationType.CardRevealed).id
            val knownId = reveal.persistentAnnotation(AnnotationType.InstanceRevealedToOpponent).id

            val unrelated = board.stateOnlyDiff()

            assertSoftly {
                unrelated.diffDeletedPersistentAnnotationIdsList shouldContain faceUpId
                unrelated.diffDeletedPersistentAnnotationIdsList shouldNotContain knownId
                board.bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations.activeAnnotations
                    .containsKey(faceUpId) shouldBe false
                board.bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations.activeAnnotations[knownId]
                    ?.typeList shouldBe
                    listOf(AnnotationType.InstanceRevealedToOpponent)
            }
        }

        test("hidden identity move and library shuffle clear opponent knowledge") {
            val movedBoard = startWithBoard { _, human, _ -> addCard("Lightning Bolt", human, ZoneType.Hand) }
            val movedCard =
                movedBoard.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val movedReveal =
                movedBoard.snapshotDiff {
                    movedBoard.bridge.promptBridge(SeatId(1)).recordReveal(
                        listOf(ForgeCardId(movedCard.id)),
                        SeatId(1),
                        SeatId(2),
                        RevealZone.HAND,
                        ForgeCardId(movedCard.id),
                    )
                }
            val movedKnownId = movedReveal.persistentAnnotation(AnnotationType.InstanceRevealedToOpponent).id
            movedBoard.game.action.moveToGraveyard(movedCard, null)
            val moved = movedBoard.stateOnlyDiff()

            val shuffledBoard =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Library)
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val libraryCard =
                shuffledBoard.game.humanPlayer
                    .getZone(ZoneType.Library)
                    .cards
                    .single()
            val handCard =
                shuffledBoard.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val libraryReveal =
                shuffledBoard.snapshotDiff {
                    shuffledBoard.bridge.promptBridge(SeatId(1)).recordReveal(
                        listOf(ForgeCardId(libraryCard.id), ForgeCardId(handCard.id)),
                        SeatId(1),
                        SeatId(2),
                        RevealZone.LIBRARY,
                        ForgeCardId(libraryCard.id),
                    )
                }
            val knownRows =
                libraryReveal.persistentAnnotationsList.filter {
                    AnnotationType.InstanceRevealedToOpponent in it.typeList
                }
            val shuffledKnownId = knownRows.single { it.affectedIdsList == listOf(shuffledBoard.instanceId(libraryCard.id)) }.id
            val handKnownId = knownRows.single { it.affectedIdsList == listOf(shuffledBoard.instanceId(handCard.id)) }.id
            shuffledBoard.game.humanPlayer.shuffle(null)
            val shuffled = shuffledBoard.stateOnlyDiff()

            assertSoftly {
                moved.diffDeletedPersistentAnnotationIdsList shouldContain movedKnownId
                moved.persistentAnnotationOrNull(AnnotationType.InstanceRevealedToOpponent).shouldBeNull()
                shuffled.diffDeletedPersistentAnnotationIdsList shouldContain shuffledKnownId
                shuffled.diffDeletedPersistentAnnotationIdsList shouldNotContain handKnownId
                shuffled.persistentAnnotationOrNull(AnnotationType.InstanceRevealedToOpponent).shouldBeNull()
                shuffledBoard.bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations.activeAnnotations
                    .containsKey(handKnownId) shouldBe true
            }
        }

        test("owner-only look emits neither public reveal contract") {
            val board = startWithBoard { _, human, _ -> addCard("Forest", human, ZoneType.Hand) }
            val card =
                board.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val coordinator = TargetingCoordinator(board.bridge.promptBridge(SeatId(1)), board.bridge.seating)

            val gsm =
                board.snapshotDiff {
                    coordinator.captureReveal(CardCollection(listOf(card)), ZoneType.Hand, board.game.humanPlayer)
                }

            assertSoftly {
                board.bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeReveal()
                    .shouldBeNull()
                gsm.annotationOrNull(AnnotationType.RevealedCardCreated).shouldBeNull()
                gsm.persistentAnnotationOrNull(AnnotationType.CardRevealed).shouldBeNull()
                gsm.persistentAnnotationOrNull(AnnotationType.InstanceRevealedToOpponent).shouldBeNull()
            }
        }

        test("AI viewer publishes a human-owned reveal with its audience and source") {
            val board = startWithBoard { _, human, _ -> addCard("Forest", human, ZoneType.Hand) }
            val card =
                board.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val sourceCardId = ForgeCardId(999)
            val controller =
                RevealTrackingAiController(
                    board.game,
                    board.game.aiPlayer,
                    board.bridge.promptBridge(SeatId(1)),
                    SeatId(2),
                ) { sourceCardId }

            controller.reveal(CardCollection(listOf(card)), ZoneType.Hand, board.game.humanPlayer, null, true)
            val record = board.bridge.drainReveals(1).single()

            assertSoftly {
                record.forgeCardIds shouldBe listOf(ForgeCardId(card.id))
                record.ownerSeatId shouldBe SeatId(1)
                record.viewerSeatId shouldBe SeatId(2)
                record.sourceZone shouldBe RevealZone.HAND
                record.sourceCardId shouldBe sourceCardId
            }
        }

        test("non-reveal path emits neither contract") {
            val board = startWithBoard { _, human, _ -> addCard("Forest", human, ZoneType.Hand) }

            val gsm = board.snapshotDiff {}

            assertSoftly {
                gsm.persistentAnnotationOrNull(AnnotationType.CardRevealed).shouldBeNull()
                gsm.persistentAnnotationOrNull(AnnotationType.InstanceRevealedToOpponent).shouldBeNull()
            }
        }
    })
