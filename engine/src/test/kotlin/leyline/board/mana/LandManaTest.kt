package leyline.board.mana

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.ZoneIds
import leyline.game.seedDiffBaseline
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTest
import leyline.testkit.ClientAccumulator
import leyline.testkit.annotation
import leyline.testkit.assertLimboContains
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.detailString
import leyline.testkit.gsm
import leyline.testkit.gsmOrNull
import leyline.testkit.haveManaCost
import leyline.testkit.humanPlayer
import leyline.testkit.mana
import leyline.testkit.mergedGsm
import leyline.testkit.ofType
import leyline.testkit.persistentAnnotation
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

/**
 * Land play and mana production subsystem tests.
 *
 * Covers: zone transfer on land play, ColorProduction annotation ordinals,
 * instanceId reallocation, Limbo retirement, accumulated client state,
 * Play/ActivateMana action fields, autoTapSolution for mana sources.
 *
 * For land ETB choices (shock lands), see ShockLandEtbTest.
 */
class LandManaTest :
    BoardTest({

        // --- Zone transfer & annotation shape ---

        test("play land — annotations, zone transfer, instanceId realloc, Limbo") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Hand)
                }

            val player = board.human
            val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
            val origId = board.instanceId(land.id)
            val cardId = land.id

            val gsm =
                board.snapshotDiff {
                    moveToBattlefield(land, board.game)
                }
            val newId = board.instanceId(cardId)

            origId shouldNotBe newId

            // Strict type ordering: OIC → ZT → UAT (client replays sequentially for animations)
            val types = gsm.annotationsList.map { it.typeList.first() }
            types shouldBe
                listOf(
                    AnnotationType.ObjectIdChanged,
                    AnnotationType.ZoneTransfer_af5a,
                    AnnotationType.UserActionTaken,
                )
            val ids = gsm.annotationsList.map { it.id }
            assertSoftly {
                ids shouldBe ids.sorted()
                ids.toSet().size shouldBe ids.size
                val oic = gsm.annotation(AnnotationType.ObjectIdChanged)
                oic.affectedIdsList.shouldContain(origId)
                oic.detailInt("orig_id") shouldBe origId
                oic.detailInt("new_id") shouldBe newId
                oic.affectorId shouldBe 0

                val zt = gsm.annotation(AnnotationType.ZoneTransfer_af5a)
                zt.affectedIdsList.shouldContain(newId)
                zt.detailInt("zone_src") shouldBe ZoneIds.P1_HAND
                zt.detailInt("zone_dest") shouldBe ZoneIds.BATTLEFIELD
                zt.detailString("category") shouldBe "PlayLand"
                zt.affectorId shouldBe 0

                val uat = gsm.annotation(AnnotationType.UserActionTaken)
                uat.affectorId shouldBe SEAT_ID
                uat.affectedIdsList.shouldContain(newId)
                uat.detailInt("actionType") shouldBe ActionType.Play_add3.number

                gsm.prevGameStateId shouldBe gsm.gameStateId - 1

                val entered = gsm.persistentAnnotation(AnnotationType.EnteredZoneThisTurn)
                entered.affectedIdsList.shouldContain(newId)

                val landObj = gsm.gameObjectsList.first { it.instanceId == newId }
                landObj.zoneId shouldBe ZoneIds.BATTLEFIELD
                landObj.uniqueAbilitiesCount shouldBeGreaterThan 0

                gsm.gameObjectsList.none { it.instanceId == origId } shouldBe true
                assertLimboContains(gsm, origId)
                gsm.diffDeletedInstanceIdsList shouldNotContain origId
            }
        }

        test("play land — accumulated client state consistent") {
            val board = startGameAtMain1()

            val startResult = board.gameStart()
            val acc = ClientAccumulator()
            acc.seedFull(handshakeFull(board.game, board.bridge, board.counter.currentGsId()))
            acc.processAll(startResult.messages)
            board.bridge.seedDiffBaseline(board.game)

            playLand(board.bridge) ?: error("No land in hand")
            val postResult = board.postAction()
            acc.processAll(postResult.messages)
            val oic = postResult.mergedGsm.annotation(AnnotationType.ObjectIdChanged)
            val origId = oic.detailInt("orig_id")
            val newId = oic.detailInt("new_id")

            assertSoftly {
                acc.objects[newId].shouldNotBeNull().zoneId shouldBe ZoneIds.BATTLEFIELD

                val handZone =
                    acc.zones.values
                        .first { it.type == ProtoZoneType.Hand && it.ownerSeatId == 1 }
                handZone.objectInstanceIdsList shouldNotContain origId

                acc.zones[ZoneIds.BATTLEFIELD]!!.objectInstanceIdsList.shouldContain(newId)
                acc.zones[ZoneIds.LIMBO]!!.objectInstanceIdsList.shouldContain(origId)
            }

            acc.assertConsistent("after play land")
        }

        // --- Color production ---

        test("Forest — ColorProduction [5]") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                }
            board
                .playLandFromHand()
                .persistentAnnotation(AnnotationType.ColorProduction)
                .detailIntList("colors") shouldBe listOf(ManaColor.Green_afc9.number)
        }

        test("Jungle Hollow — ColorProduction [3, 5]") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Jungle Hollow", human, ZoneType.Hand)
                }
            board
                .playLandFromHand()
                .persistentAnnotation(AnnotationType.ColorProduction)
                .detailIntList("colors")
                .shouldContainExactlyInAnyOrder(ManaColor.Black_afc9.number, ManaColor.Green_afc9.number)
        }

        test("Llanowar Elves — ColorProduction [5] from battlefield snapshot") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Llanowar Elves", human, ZoneType.Battlefield)
                }
            val elf =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Llanowar Elves" }
            val elfIid = board.instanceId(elf.id)

            handshakeFull(board.game, board.bridge, board.counter.currentGsId())
                .persistentAnnotationsList
                .single { AnnotationType.ColorProduction in it.typeList && elfIid in it.affectedIdsList }
                .detailIntList("colors") shouldBe listOf(ManaColor.Green_afc9.number)
        }

        test("Riverpyre Verge — ColorProduction follows its activation restriction") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Riverpyre Verge", human, ZoneType.Battlefield)
                    addCard("Island", human, ZoneType.Hand)
                }
            val verge =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Riverpyre Verge" }
            val vergeIid = board.instanceId(verge.id)

            handshakeFull(board.game, board.bridge, board.counter.currentGsId())
                .persistentAnnotationsList
                .single { AnnotationType.ColorProduction in it.typeList && vergeIid in it.affectedIdsList }
                .detailIntList("colors") shouldBe listOf(ManaColor.Red_afc9.number)

            board
                .playLandFromHand()
                .persistentAnnotationsList
                .single { AnnotationType.ColorProduction in it.typeList && vergeIid in it.affectedIdsList }
                .detailIntList("colors")
                .shouldContainExactlyInAnyOrder(ManaColor.Red_afc9.number, ManaColor.Blue_afc9.number)
        }

        // --- Action fields ---

        test("Play action — shouldStop, no abilityGrpId, no manaCost") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }

            val actions = ActionMapper.buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
            val playActions = actions.ofType(ActionType.Play_add3)
            assertSoftly {
                playActions.shouldHaveSize(2)
                for (a in playActions) {
                    a.shouldStop.shouldBeTrue()
                    a.instanceId shouldBeGreaterThan 0
                    a.grpId shouldBeGreaterThan 0
                    a.facetId shouldBe a.instanceId
                    a.abilityGrpId shouldBe 0
                    a.manaCostCount shouldBe 0
                }

                val pass = actions.ofType(ActionType.Pass)
                pass.shouldHaveSize(1)
                pass[0].instanceId shouldBe 0
                pass[0].grpId shouldBe 0
                pass[0].shouldStop.shouldBeFalse()
            }
        }

        test("ActivateMana fields after land on battlefield") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }

            val player = board.human
            val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
            board.snapshotDiff { moveToBattlefield(land, board.game) }

            val manaActions =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .ofType(ActionType.ActivateMana)
            assertSoftly {
                manaActions.shouldHaveSize(2)
                for (a in manaActions) {
                    a.instanceId shouldNotBe 0
                    a.grpId shouldNotBe 0
                    a.abilityGrpId shouldBe 1005
                    a.facetId shouldBe a.instanceId
                    a.shouldStop.shouldBeFalse()
                    a.isBatchable.shouldBeTrue()
                    a.manaPaymentOptionsCount shouldBeGreaterThan 0
                    a.manaSelectionsCount shouldBeGreaterThan 0
                }
            }
        }

        test("dual land ActivateMana exposes selectable colors") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Jungle Hollow", human, ZoneType.Battlefield)
                }

            val action =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .ofType(ActionType.ActivateMana)
                    .single()
            val colors = listOf(ManaColor.Black_afc9, ManaColor.Green_afc9)
            val selection = action.manaSelectionsList.single()
            val cardData = board.bridge.cardRepository.findByGrpId(action.grpId) ?: error("missing card data")
            val abilityIndex = cardData.abilityIds.indexOfFirst { (grpId, _) -> grpId == action.abilityGrpId }
            val expectedUniqueAbilityId = 50 + abilityIndex

            assertSoftly {
                action.abilityGrpId shouldNotBe 0
                abilityIndex shouldNotBe -1
                action.uniqueAbilityId shouldBe expectedUniqueAbilityId
                action.manaPaymentOptionsList
                    .map { it.manaList.single().color } shouldContainExactlyInAnyOrder colors

                selection.selectionCount shouldBe 1
                selection.validationType shouldBe SelectionValidationType.NonRepeatable
                selection.optionsList.map { it.selectedColor } shouldContainExactlyInAnyOrder colors
                selection.optionsList
                    .map { it.manaList.single().color } shouldContainExactlyInAnyOrder colors
            }
        }

        test("Cast action — manaCost, autoTapSolution with mana source details") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }

            val player = board.human
            val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
            board.snapshotDiff { moveToBattlefield(land, board.game) }

            val cast =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .ofType(ActionType.Cast)
            cast.shouldHaveSize(1)

            val a = cast[0]
            assertSoftly {
                a.shouldStop.shouldBeTrue()
                a.instanceId shouldNotBe 0
                a.grpId shouldNotBe 0
                a should haveManaCost(generic = 1, green = 1)
                a.hasAutoTapSolution().shouldBeTrue()
                a.autoTapSolution.autoTapActionsCount shouldBe 2

                for (tap in a.autoTapSolution.autoTapActionsList) {
                    tap.instanceId shouldNotBe 0
                    tap.abilityGrpId shouldBe 1005
                    tap.hasManaPaymentOption().shouldBeTrue()
                    for (m in tap.manaPaymentOption.manaList) {
                        m.srcInstanceId shouldNotBe 0
                        m.abilityGrpId shouldBe 1005
                        m.color shouldBe ManaColor.Green_afc9
                        m.count shouldBe 1
                    }
                }
            }
        }

        test("an X spell still gets an autoTapSolution for its colored pips") {
            // Traumatic Critique {X}{U}{R}. No source produces "X" mana, so counting X
            // as a colored requirement leaves the solution unsolvable and the action
            // ships without one. The client then reads Action.CanAffordToCast as false
            // (the cost is not entirely X) and HighlightUtil never lights the card in
            // hand — the defect seen at 2:07 of the 2026-09-16 screencast.
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Traumatic Critique", human, ZoneType.Hand)
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                }

            val cast =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .ofType(ActionType.Cast)
                    .single()

            assertSoftly {
                cast.manaCostList.flatMap { it.colorList } shouldContain ManaColor.X
                cast.hasAutoTapSolution().shouldBeTrue()
                cast.autoTapSolution.autoTapActionsCount shouldBe 2
                cast.autoTapSolution.autoTapActionsList
                    .flatMap { it.manaPaymentOption.manaList }
                    .map { it.color } shouldContainExactlyInAnyOrder listOf(ManaColor.Blue_afc9, ManaColor.Red_afc9)
            }
        }

        test("hybrid two-or-color autoTapSolution can use two generic for a missing color") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Island", human, ZoneType.Battlefield)
                    addCard("Mountain", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Temur Tawnyback", human, ZoneType.Hand)
                }

            val cast =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .ofType(ActionType.Cast)
            cast.shouldHaveSize(1)

            val autoTap = cast[0].autoTapSolution
            assertSoftly {
                cast[0].hasAutoTapSolution().shouldBeTrue()
                autoTap.autoTapActionsCount shouldBe 4
                autoTap.autoTapActionsList
                    .flatMap { it.manaPaymentOption.manaList }
                    .map { it.color } shouldContainExactlyInAnyOrder
                    listOf(ManaColor.Blue_afc9, ManaColor.Red_afc9, ManaColor.White_afc9, ManaColor.Black_afc9)
            }
        }

        test("dual land autoTapSolution — Jungle Hollow casts Grizzly Bears") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Jungle Hollow", human, ZoneType.Battlefield)
                    addCard("Jungle Hollow", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }

            val cast =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .ofType(ActionType.Cast)
            cast.shouldHaveSize(1)

            val a = cast[0]
            assertSoftly {
                a.hasAutoTapSolution().shouldBeTrue()
                a.autoTapSolution.autoTapActionsCount shouldBe 2
                a.autoTapSolution.autoTapActionsList
                    .map { it.instanceId }
                    .toSet()
                    .size shouldBe a.autoTapSolution.autoTapActionsCount
                for (tap in a.autoTapSolution.autoTapActionsList) {
                    tap.instanceId shouldNotBe 0
                    tap.hasManaPaymentOption().shouldBeTrue()
                }
            }
        }

        test("GSM embedded actions stripped — no grpId, facetId, autoTap") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }

            val player = board.human
            val land = player.getZone(ZoneType.Hand).cards.first { it.isLand }
            board.snapshotDiff { moveToBattlefield(land, board.game) }

            val result = board.postAction()
            val gsm = result.gsmOrNull.shouldNotBeNull()
            gsm.pendingMessageCount shouldBe 1

            fun actionStub(type: ActionType) = gsm.actionsList.map { it.action }.filter { it.actionType == type }

            val cast = actionStub(ActionType.Cast)
            assertSoftly {
                cast.shouldHaveSize(1)
                cast[0].instanceId shouldNotBe 0
                cast[0].grpId shouldBe 0
                cast[0].facetId shouldBe 0
                cast[0].shouldStop.shouldBeFalse()
                cast[0].hasAutoTapSolution().shouldBeFalse()
            }

            val mana = actionStub(ActionType.ActivateMana)
            mana.shouldHaveSize(2)
            for (a in mana) {
                assertSoftly {
                    a.instanceId shouldNotBe 0
                    a.grpId shouldBe 0
                    a.facetId shouldBe 0
                }
            }

            val pass = actionStub(ActionType.Pass)
            pass.shouldHaveSize(1)
            pass[0].instanceId shouldBe 0
        }

        // --- Limbo accumulation ---

        test("Limbo grows across multiple land plays") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Hand)
                    addCard("Forest", human, ZoneType.Hand)
                }

            val player = board.human
            val lands = player.getZone(ZoneType.Hand).cards.filter { it.isLand }
            val origId1 = board.bridge.instance(lands[0])
            val origId2 = board.bridge.instance(lands[1])

            board.snapshotDiff { moveToBattlefield(lands[0], board.game) }
            assertSoftly {
                board.bridge.getLimboInstanceIds().shouldHaveSize(1)
                board.bridge.getLimboInstanceIds().shouldContain(origId1)
            }

            board.snapshotDiff { moveToBattlefield(lands[1], board.game) }
            assertSoftly {
                board.bridge.getLimboInstanceIds().shouldHaveSize(2)
                board.bridge.getLimboInstanceIds().shouldContain(origId1)
                board.bridge.getLimboInstanceIds().shouldContain(origId2)
            }
        }

        // --- AutoTap preference ---

        test("autoTapSolution prefers lands over mana dorks") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Plains", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Llanowar Elves", human, ZoneType.Battlefield)
                    addCard("Pacifism", human, ZoneType.Hand)
                }

            val cast =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .ofType(ActionType.Cast)
            cast.shouldHaveSize(1)

            val autoTap = cast[0].autoTapSolution
            autoTap.shouldNotBeNull()
            autoTap.autoTapActionsCount shouldBe 2

            val landInstanceIds =
                board.game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isLand }
                    .map { board.instanceId(it.id) }
                    .toSet()

            for (tap in autoTap.autoTapActionsList) {
                (tap.instanceId in landInstanceIds) shouldBe true
            }
        }
    })
