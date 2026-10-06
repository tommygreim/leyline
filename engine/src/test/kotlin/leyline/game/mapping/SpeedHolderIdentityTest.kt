package leyline.game.mapping

import forge.card.GamePieceType
import forge.game.ability.AbilityKey
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.trigger.WrappedAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.game.state.PlayerSpeedDesignationKind
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

class SpeedHolderIdentityTest :
    BoardTest({
        test("the source-less speed stack trigger uses a real printing for either player") {
            val board =
                startWithBoard { _, human, ai ->
                    human.setSpeed(3)
                    human.createSpeedEffect()
                    ai.setSpeed(2)
                    ai.createSpeedEffect()
                }
            for (player in listOf(board.human, board.ai)) {
                val effect =
                    player.getZone(ZoneType.Command).cards.single {
                        it.gamePieceType == GamePieceType.EFFECT &&
                            it.triggers.any { trigger -> trigger.overridingAbility?.api == ApiType.ChangeSpeed }
                    }
                val trigger = effect.triggers.single()
                val ability = WrappedAbility(trigger, trigger.overridingAbility, player)
                ability.activatingPlayer = player
                ability.setTriggeringObject(AbilityKey.Map, emptyMap<forge.game.player.Player, Int>())
                board.game.stack.addAndUnfreeze(ability)
                board.stateOnlyDiff()
                val gsm =
                    board.bridge
                        .projectionStateSnapshot()
                        .viewerCursors
                        .getValue(leyline.bridge.types.SeatId(1))
                        .fullState!!
                val projected = gsm.gameObjectsList.single { it.type == GameObjectType.Ability && it.grpId == 355 }
                assertSoftly {
                    projected.objectSourceGrpId shouldBe 96296
                    projected.parentId shouldBe
                        FrameIdResolver.speedTriggerHolderIid(leyline.bridge.types.SeatId(projected.ownerSeatId)).value
                    projected.name shouldBe if (player.speed >= 3) 928985 else 928983
                }
                val atMaximum = board.snapshotDiff { player.setSpeed(4) }
                val retained =
                    board.bridge
                        .projectionStateSnapshot()
                        .viewerCursors
                        .getValue(leyline.bridge.types.SeatId(1))
                        .fullState!!
                        .gameObjectsList
                        .single { it.instanceId == projected.instanceId }
                assertSoftly {
                    atMaximum.diffDeletedInstanceIdsList shouldNotContain projected.instanceId
                    retained.objectSourceGrpId shouldBe 96296
                    retained.parentId shouldBe projected.parentId
                }
                board.game.stack.clear()
            }
        }

        test("unrelated command effects do not acquire the speed printing") {
            val board =
                startWithBoard { game, human, _ ->
                    val helper = Card(game.nextCardId(), game)
                    helper.owner = human
                    helper.gamePieceType = GamePieceType.EFFECT
                    helper.name = "Unrelated effect"
                    human.getZone(ZoneType.Command).add(helper)
                }
            val helper =
                board.human
                    .getZone(ZoneType.Command)
                    .cards
                    .single { it.name == "Unrelated effect" }
            leyline.game.snapshot.SnapshotCapture
                .resolveStackSourceCardGrpId(helper, board.bridge.cardRepository) shouldBe 0
        }

        test("both players receive the native speed ability identity rather than a blank transient") {
            val board =
                startWithBoard { _, human, ai ->
                    human.setSpeed(3)
                    ai.setSpeed(4)
                }
            val gsm = board.stateOnlyDiff()
            val holders = gsm.gameObjectsList.filter { it.type == GameObjectType.TriggerHolder }
            holders.size shouldBe 2
            holders.forEach { holder ->
                assertSoftly {
                    holder.objectSourceGrpId shouldBe 96296
                    holder.grpId shouldBe ObjectMapper.TRIGGER_HOLDER_GRP_ID
                    holder.overlayGrpId shouldBe ObjectMapper.TRIGGER_HOLDER_GRP_ID
                    holder.uniqueAbilitiesList.single().grpId shouldBe 355
                    holder.uniqueAbilitiesList.single().id shouldBe 50
                    holder.ownerSeatId shouldBe holder.controllerSeatId
                }
                val designation =
                    board.bridge.projectionStateSnapshot().persistentAnnotations.activeAnnotations.values.single {
                        PlayerSpeedDesignationKind.matches(it) && holder.instanceId in it.affectedIdsList
                    }
                designation.detailInt("value") shouldBe if (holder.ownerSeatId == 1) 3 else 4
            }
        }

        test("speed holder appears only after starting engines and survives the maximum-speed update") {
            val board = startWithBoard { _, _, _ -> }
            board
                .stateOnlyDiff()
                .gameObjectsList
                .filter { it.type == GameObjectType.TriggerHolder }
                .shouldBeEmpty()
            val started = board.snapshotDiff { board.human.setSpeed(1) }
            val holder = started.gameObjectsList.single { it.type == GameObjectType.TriggerHolder }
            holder.objectSourceGrpId shouldBe 96296
            val maximum = board.snapshotDiff { board.human.setSpeed(4) }
            maximum.diffDeletedInstanceIdsList shouldNotContain holder.instanceId
            maximum.persistentAnnotationsList.single(PlayerSpeedDesignationKind::matches).detailInt("value") shouldBe 4
        }
    })
