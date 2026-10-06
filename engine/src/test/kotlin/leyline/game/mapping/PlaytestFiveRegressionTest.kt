package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.coord.DeferredCastCostPlanMaterializer
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.game.bundle.CastingTimeOptionsBuilder
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.*

/** Production capture/projection regressions from the fifth user playtest. */
class PlaytestFiveRegressionTest :
    BoardTest({
        test("restricted double mana gives a payable instant a predictive solution") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Tablet of Discovery", human, ZoneType.Battlefield)
                    addCard("Abrade", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }
            val snapshot = SnapshotCapture.run(board.game, board.bridge, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snapshot, board.bridge)
            val abrade =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Abrade" }
            val iid = board.bridge.getOrAllocInstanceId(ForgeCardId(abrade.id)).value
            val cast = actions.actionsList.first { it.instanceId == iid && it.actionType == ActionType.Cast }
            cast.hasAutoTapSolution() shouldBe true
            cast.autoTapSolution.autoTapActionsList.sumOf { it.manaPaymentOption.manaList.sumOf { mana -> mana.count } } shouldBe 2
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .single()
                .isTapped shouldBe false
            val bears =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.name == "Grizzly Bears" }
            val bearIid = board.bridge.getOrAllocInstanceId(ForgeCardId(bears.id)).value
            actions.actionsList.any { it.instanceId == bearIid && it.actionType == ActionType.Cast } shouldBe false
        }

        test("instant spell uses Great Hall restricted red mana") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Great Hall of the Biblioplex", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Abrade", human, ZoneType.Hand)
                    addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                }
            val actions = ActionMapper.buildFromSnapshot(1, SnapshotCapture.run(board.game, board.bridge, "test", 0), board.bridge)
            val abradeIid =
                board.bridge
                    .getOrAllocInstanceId(
                        ForgeCardId(
                            board.human
                                .getZone(ZoneType.Hand)
                                .cards
                                .single()
                                .id,
                        ),
                    ).value
            val hall =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Great Hall of the Biblioplex" }
            val hallIid = board.bridge.getOrAllocInstanceId(ForgeCardId(hall.id)).value
            val cast = actions.actionsList.single { it.actionType == ActionType.Cast && it.instanceId == abradeIid }
            cast.autoTapSolution.autoTapActionsList
                .flatMap { it.manaPaymentOption.manaList }
                .single { it.srcInstanceId == hallIid }
                .color shouldBe ManaColor.Red_afc9
        }

        test("face-down exiled lands carry no identity or creature type on the wire") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Mountain", human, ZoneType.Exile)
                    addCard("Island", ai, ZoneType.Exile)
                }
            board.human
                .getZone(ZoneType.Exile)
                .cards
                .single()
                .turnFaceDown(true)
            board.ai
                .getZone(ZoneType.Exile)
                .cards
                .single()
                .turnFaceDown(true)
            val snapshot = SnapshotCapture.run(board.game, board.bridge, "test", 0)
            snapshot.objects.values
                .filter { it.isFaceDownExile }
                .size shouldBe 2
            for (card in snapshot.objects.values.filter { it.isFaceDownExile }) {
                card.mayLookSeatIds shouldBe emptyList()
                val iid = board.bridge.getOrAllocInstanceId(card.forgeCardId).value
                val obj = ObjectMapper.buildFromSnapshot(card, iid, ZoneIds.EXILE, card.owner.value, board.bridge.cardProto)
                obj.visibility shouldBe Visibility.Hidden
                obj.grpId shouldBe 0
                obj.cardTypesCount shouldBe 0
                obj.name shouldBe 0
            }
        }

        test("Kicker option refers to the keyword row and carries the full kicked mana cost") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Burst Lightning", human, ZoneType.Hand)
                    repeat(5) { addCard("Mountain", human, ZoneType.Battlefield) }
                }
            val card =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val ability =
                PriorityActionCandidates
                    .query(board.game, board.human)
                    .forCard(card)
                    .casts
                    .first()
            val fid = ForgeCardId(card.id)
            val iid = board.bridge.getOrAllocInstanceId(fid).value
            val grp = board.bridge.resolveGrpId(card, iid)
            val offer =
                GameActionBridge.ActionOffer(
                    Action
                        .newBuilder()
                        .setActionType(ActionType.Cast)
                        .setInstanceId(iid)
                        .setGrpId(grp)
                        .build(),
                    PlayerAction.CastSpell(fid, 0, ability = ability),
                )
            val plan =
                DeferredCastCostPlanMaterializer
                    .materialize(
                        board.bridge,
                        offer,
                    ) { 1L }
                    .shouldNotBeNull()
                    .plan.optional
                    .shouldNotBeNull()
            plan.entries.single().abilityGrpId shouldBe
                board.bridge.cardRepository
                    .findByGrpId(grp)!!
                    .abilityIds
                    .first()
                    .first
            plan.entries
                .single()
                .manaCost!!
                .sumOf { it.second } shouldBe 5
            val (req, _) =
                CastingTimeOptionsBuilder.buildOptionalCostCastingTimeOptionsReq(
                    iid,
                    plan.entries.map { it.type to it.abilityGrpId },
                    1,
                    plan.baseManaCost,
                    plan.entries.map { it.manaCost },
                )
            req.castingTimeOptionReqList
                .first()
                .manaCostList
                .sumOf { it.count } shouldBe 5
            req.castingTimeOptionReqList
                .last()
                .manaCostList
                .sumOf { it.count } shouldBe 1
        }
    })
