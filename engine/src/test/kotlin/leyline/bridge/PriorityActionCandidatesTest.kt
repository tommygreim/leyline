package leyline.bridge

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.ofType
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class PriorityActionCandidatesTest :
    BoardTest({
        test("legal but unaffordable cast stays inactive without holding priority") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }

            val candidates = PriorityActionCandidates.query(board.game, board.human)
            val projection =
                ActionMapper.buildProjectionFromSnapshot(
                    1,
                    SnapshotCapture.run(board.game, board.bridge, "test", 0),
                    board.bridge,
                    candidates,
                )

            assertSoftly {
                candidates.hasLegalNonManaAction(board.human).shouldBeFalse()
                projection.actions.ofType(ActionType.Cast).shouldHaveSize(0)
                projection.actions.inactiveActionsList
                    .filter { it.actionType == ActionType.Cast }
                    .shouldHaveSize(1)
            }
        }
        for (spell in listOf("Counterspell", "Murder", "Bone Splinters", "Run Away Together")) {
            test("unavailable targets or sacrifice material do not hold priority: $spell") {
                val board =
                    startWithBoard { _, human, ai ->
                        addCard(spell, human, ZoneType.Hand)
                        val land = if (spell in listOf("Counterspell", "Run Away Together")) "Island" else "Swamp"
                        repeat(3) { addCard(land, human, ZoneType.Battlefield) }
                        if (spell in listOf("Bone Splinters", "Run Away Together")) addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                    }
                val candidates = PriorityActionCandidates.query(board.game, board.human)
                val projection =
                    ActionMapper.buildProjectionFromSnapshot(
                        1,
                        SnapshotCapture.run(board.game, board.bridge, "test", 0),
                        board.bridge,
                        candidates,
                    )
                assertSoftly {
                    candidates.hasLegalNonManaAction(board.human).shouldBeFalse()
                    candidates.hasLegalNonManaAction(board.human, isOwnTurn = false).shouldBeFalse()
                    projection.actions.ofType(ActionType.Cast).shouldHaveSize(0)
                }
            }
        }
        for (payable in listOf(false, true)) {
            test("graveyard casting uses the same availability for either turn: $payable") {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Think Twice", human, ZoneType.Graveyard)
                        if (payable) repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                    }
                val candidates = PriorityActionCandidates.query(board.game, board.human)
                val projection =
                    ActionMapper.buildProjectionFromSnapshot(
                        1,
                        SnapshotCapture.run(board.game, board.bridge, "test", 0),
                        board.bridge,
                        candidates,
                    )
                assertSoftly {
                    candidates.hasLegalNonManaAction(board.human) shouldBe payable
                    candidates.hasLegalNonManaAction(board.human, isOwnTurn = false) shouldBe payable
                    projection.actions.ofType(ActionType.Cast).shouldHaveSize(if (payable) 1 else 0)
                }
            }
            test("zero-mana sacrifice activation requires its material: $payable") {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Immersturm Predator", human, ZoneType.Battlefield)
                        if (payable) addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    }
                val candidates = PriorityActionCandidates.query(board.game, board.human)
                val projection =
                    ActionMapper.buildProjectionFromSnapshot(
                        1,
                        SnapshotCapture.run(board.game, board.bridge, "test", 0),
                        board.bridge,
                        candidates,
                    )
                assertSoftly {
                    candidates.hasLegalNonManaAction(board.human) shouldBe payable
                    candidates.hasLegalNonManaAction(board.human, isOwnTurn = false) shouldBe payable
                    projection.actions.ofType(ActionType.Activate_add3).shouldHaveSize(if (payable) 1 else 0)
                }
            }
        }
        test("zero-cost cast is meaningful with no mana sources") {
            val board = startWithBoard { _, human, _ -> addCard("Ornithopter", human, ZoneType.Hand) }
            val candidates = PriorityActionCandidates.query(board.game, board.human)
            val projection =
                ActionMapper.buildProjectionFromSnapshot(
                    1,
                    SnapshotCapture.run(board.game, board.bridge, "test", 0),
                    board.bridge,
                    candidates,
                )
            candidates.hasLegalNonManaAction(board.human).shouldBeTrue()
            projection.actions.ofType(ActionType.Cast).shouldHaveSize(1)
        }
        for (tapped in listOf(false, true)) {
            test("mana-only candidates require an executable source and stay separate from non-mana actions: $tapped") {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Birds of Paradise", human, ZoneType.Battlefield).isTapped = tapped
                    }
                val candidates = PriorityActionCandidates.query(board.game, board.human)
                candidates.hasLegalNonManaAction(board.human).shouldBeFalse()
                candidates.hasLegalManaAction(board.human) shouldBe !tapped
                board.human.battlefield
                    .card("Birds of Paradise")
                    .isTapped shouldBe tapped
                board.human.manaPool.totalMana() shouldBe 0
            }
        }
        for (manaAvailable in listOf(false, true)) {
            test("opponent cycling stop requires payable mana: $manaAvailable") {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Shark Typhoon", human, ZoneType.Hand)
                        if (manaAvailable) repeat(2) { addCard("Island", human, ZoneType.Battlefield) }
                    }
                val candidates = PriorityActionCandidates.query(board.game, board.human)
                val projection =
                    ActionMapper.buildProjectionFromSnapshot(
                        1,
                        SnapshotCapture.run(board.game, board.bridge, "test", 0),
                        board.bridge,
                        candidates,
                    )
                assertSoftly {
                    if (manaAvailable) {
                        candidates.hasLegalNonManaAction(board.human, isOwnTurn = false).shouldBeTrue()
                        PriorityActionCandidates.hasLegalNonManaAction(board.game, board.human, isOwnTurn = false).shouldBeTrue()
                        projection.actions.ofType(ActionType.Activate_add3).shouldHaveSize(1)
                    } else {
                        candidates.hasLegalNonManaAction(board.human, isOwnTurn = false).shouldBeFalse()
                        PriorityActionCandidates.hasLegalNonManaAction(board.game, board.human, isOwnTurn = false).shouldBeFalse()
                        projection.actions.ofType(ActionType.Activate_add3).shouldHaveSize(0)
                    }
                }
            }
        }
    })
