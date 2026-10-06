package leyline.mechanics.adventure

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.bundle.GsmBuilder
import leyline.game.event.FrameEventLog
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.LinkedFaceRole
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import leyline.testkit.StateMapperShell as StateMapper

class AdventureCompanionTest :
    BoardTest({
        test("Adventure card projects a face companion outside hand membership") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                }
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val parentIid = board.instanceId(card.id)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        SnapshotCapture.run(board.game, board.bridge, "test", 1),
                        1,
                        "test",
                        board.bridge,
                        viewingSeatId = 1,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            val companion = gsm.gameObjectsList.single { it.type == GameObjectType.Adventure_a4aa }
            val hand = gsm.zonesList.single { it.zoneId == ZoneIds.P1_HAND }

            assertSoftly {
                companion.grpId shouldBe 86846
                companion.parentId shouldBe parentIid
                companion.zoneId shouldBe ZoneIds.P1_HAND
                companion.visibility shouldBe Visibility.Private
                companion.ownerSeatId shouldBe 1
                companion.controllerSeatId shouldBe 1
                companion.viewersList shouldBe listOf(1)
                companion.uniqueAbilitiesList.map { it.grpId } shouldBe listOf(168862)
                hand.objectInstanceIdsList shouldContain parentIid
                hand.objectInstanceIdsList shouldNotContain companion.instanceId
            }
        }

        test("drawing Hearth Elemental projects Stoke Genius beside its parent in hand") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Hearth Elemental", human, ZoneType.Library)
                }
            val (gsm, parentIid) =
                board.transferCard("Hearth Elemental") { card, game ->
                    game.action.moveTo(ZoneType.Hand, card, null, AbilityKey.newMap())
                }
            val parent = gsm.gameObjectsList.single { it.instanceId == parentIid }
            val companion = gsm.gameObjectsList.single { it.type == GameObjectType.Adventure_a4aa }

            assertSoftly {
                parent.grpId shouldBe 86835
                companion.grpId shouldBe 86836
                companion.parentId shouldBe parentIid
                companion.zoneId shouldBe ZoneIds.P1_HAND
                companion.visibility shouldBe Visibility.Private
                companion.viewersList shouldBe listOf(1)
            }
        }

        test("opening hand deal projects Stoke Genius companion with Hearth Elemental") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Hearth Elemental", human, ZoneType.Hand)
                }
            val snapshot = SnapshotCapture.run(board.game, board.bridge, "test", 2)
            val gsm = GsmBuilder.buildDealHand(board.bridge, 2, 1, snapshot)
            val parent = gsm.gameObjectsList.single { it.grpId == 86835 }
            val companion = gsm.gameObjectsList.single { it.type == GameObjectType.Adventure_a4aa }

            assertSoftly {
                companion.grpId shouldBe 86836
                companion.parentId shouldBe parent.instanceId
                companion.zoneId shouldBe ZoneIds.P1_HAND
                companion.visibility shouldBe Visibility.Private
                companion.viewersList shouldBe listOf(1)
            }
        }

        test("opening hand deal does not reveal the opponent's Adventure companion") {
            val board =
                startWithBoard { _, _, ai ->
                    addCard("Hearth Elemental", ai, ZoneType.Hand)
                }
            val snapshot = SnapshotCapture.run(board.game, board.bridge, "test", 2)
            val gsm = GsmBuilder.buildDealHand(board.bridge, 2, 1, snapshot)

            gsm.gameObjectsList.none { it.grpId == 86835 || it.grpId == 86836 } shouldBe true
        }

        test("non-Adventure linked card has no Adventure companion") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Galedrifter", human, ZoneType.Graveyard)
                }
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        SnapshotCapture.run(board.game, board.bridge, "test", 1),
                        1,
                        "test",
                        board.bridge,
                        viewingSeatId = 1,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            gsm.gameObjectsList.count { it.type == GameObjectType.Adventure_a4aa } shouldBe 0
        }

        test("opponent hidden Adventure card has no face companion") {
            val board =
                startWithBoard { _, _, ai ->
                    addCard("Ratcatcher Trainee", ai, ZoneType.Hand)
                }
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        SnapshotCapture.run(board.game, board.bridge, "test", 1),
                        1,
                        "test",
                        board.bridge,
                        viewingSeatId = 1,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            gsm.gameObjectsList.count { it.type == GameObjectType.Adventure_a4aa } shouldBe 0
        }

        test("parent reallocation deletes and recreates Adventure companion") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                }
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val oldParentIid = board.instanceId(card.id)
            val oldCompanionIid =
                board.bridge
                    .getOrAllocInstanceId(
                        FrameIdResolver.linkedFaceCompanionForgeId(InstanceId(oldParentIid), LinkedFaceRole.Adventure),
                    ).value

            val (diff, newParentIid) = board.transferCard("Ratcatcher Trainee") { moved, game -> exile(moved, game) }
            val companion = diff.gameObjectsList.single { it.type == GameObjectType.Adventure_a4aa }
            val exile = diff.zonesList.single { it.zoneId == ZoneIds.EXILE }

            assertSoftly {
                newParentIid shouldNotBe oldParentIid
                companion.instanceId shouldNotBe oldCompanionIid
                companion.parentId shouldBe newParentIid
                companion.zoneId shouldBe ZoneIds.EXILE
                diff.diffDeletedInstanceIdsList shouldContain oldCompanionIid
                exile.objectInstanceIdsList shouldContain newParentIid
                exile.objectInstanceIdsList shouldNotContain companion.instanceId
            }
        }

        test("creature resolution preserves companion within parent lifetime") {
            val board =
                startWithBoard { game, human, _ ->
                    val card = addCard("Ratcatcher Trainee", human, ZoneType.Hand)
                    game.action.moveTo(ZoneType.Stack, card, null, AbilityKey.newMap())
                }
            val card =
                board.game.stackZone.cards
                    .single()
            val parentIid = board.instanceId(card.id)
            val companionIid =
                board.bridge
                    .getOrAllocInstanceId(
                        FrameIdResolver.linkedFaceCompanionForgeId(InstanceId(parentIid), LinkedFaceRole.Adventure),
                    ).value

            val diff = board.snapshotDiff { moveToBattlefield(card, board.game) }
            val companion = diff.gameObjectsList.single { it.type == GameObjectType.Adventure_a4aa }
            val battlefield = diff.zonesList.single { it.zoneId == ZoneIds.BATTLEFIELD }

            assertSoftly {
                board.instanceId(card.id) shouldBe parentIid
                companion.instanceId shouldBe companionIid
                companion.parentId shouldBe parentIid
                companion.zoneId shouldBe ZoneIds.BATTLEFIELD
                diff.diffDeletedInstanceIdsList shouldNotContain companionIid
                battlefield.objectInstanceIdsList shouldNotContain companionIid
            }
        }

        test("parent disappearance deletes Adventure companion") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Ratcatcher Trainee", human, ZoneType.Battlefield)
                }
            val card =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val parentFid = ForgeCardId(card.id)
            val parentIid = board.instanceId(card.id)
            val companionIid =
                board.bridge
                    .getOrAllocInstanceId(
                        FrameIdResolver.linkedFaceCompanionForgeId(InstanceId(parentIid), LinkedFaceRole.Adventure),
                    ).value
            val prev = SnapshotCapture.run(board.game, board.bridge, "test", 1)

            board.human.getZone(ZoneType.Battlefield).remove(card)
            val cur = SnapshotCapture.run(board.game, board.bridge, "test", 2)
            val diff =
                StateMapper
                    .buildDiff(
                        prev,
                        cur,
                        FrameEventLog.EMPTY,
                        2,
                        "test",
                        board.bridge,
                        viewingSeatId = 1,
                        effectFacts = board.bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            assertSoftly {
                prev.objects.keys shouldContain parentFid
                cur.objects.keys shouldNotContain parentFid
                diff.diffDeletedInstanceIdsList shouldContain parentIid
                diff.diffDeletedInstanceIdsList shouldContain companionIid
                diff.gameObjectsList.count { it.type == GameObjectType.Adventure_a4aa } shouldBe 0
                diff.gameObjectsList.map { it.instanceId } shouldNotContain companionIid
            }
        }
    })
