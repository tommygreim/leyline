package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.humanPlayer

class ObjectMapperTest :
    BoardTest({

        test("DFC card has othersideGrpId set") {
            // Register back face in test card DB (startWithBoard only registers board cards)
            TestCardRegistry.ensureCardRegistered("Revealing Eye")

            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Concealing Curtains", human, ZoneType.Battlefield)
                }
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Concealing Curtains" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value
            val zoneId = ZoneIds.BATTLEFIELD

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(fid)
            val obj = ObjectMapper.buildFromSnapshot(cardSnap, instanceId, zoneId, 1, b.cardProto)

            val frontGrpId = b.cardRepository.findGrpIdByName("Concealing Curtains")!!
            val backGrpId = b.cardRepository.findGrpIdByName("Revealing Eye")!!
            obj.grpId shouldBe frontGrpId
            obj.othersideGrpId shouldBe backGrpId
        }

        test("non-DFC card has othersideGrpId zero") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Grizzly Bears" }
            val fid = ForgeCardId(card.id)
            val instanceId = b.getOrAllocInstanceId(fid).value
            val zoneId = ZoneIds.BATTLEFIELD

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val cardSnap = snap.objects.getValue(fid)
            val obj = ObjectMapper.buildFromSnapshot(cardSnap, instanceId, zoneId, 1, b.cardProto)

            obj.othersideGrpId shouldBe 0
        }

        test("parameterized keyword grants retain their precise GRE ability types") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val target =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val targetIid = board.bridge.instanceId(target.id)
            val gsm =
                board.snapshotDiff {
                    target.addChangedCardKeywords(
                        listOf(
                            "Landwalk:Forest",
                            "Landwalk:Desert",
                            "Protection:White",
                            "Hexproof:White",
                            "Affinity:Artifact",
                            "TypeCycling:Island:1U:Island",
                        ),
                        null,
                        false,
                        501L,
                        null,
                    )
                }
            val projected = gsm.gameObjectsList.single { it.instanceId == targetIid }

            projected.uniqueAbilitiesList.map { it.grpId }.sorted() shouldContainExactly listOf(16, 125, 177, 185, 191, 236)
        }

        test("buildAbilityObject sets grpId (ability) and objectSourceGrpId (host card) independently") {
            // Locks in the projection split that decouples the ability row id
            // (e.g. 86 for Cascade) from the source card's grpId. Pre-fix these
            // two fields collapsed to the same value; the fix reroutes any
            // future regression that re-collapses them straight into this test.
            val (b, _, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val abilityGrpId = 86 // Cascade
            val sourceCardGrpId = 93301 // Bloodbraid Elf (canonical Cascade host)
            val abilityIid = 9999

            val obj =
                ObjectMapper.buildAbilityObject(
                    grpId = abilityGrpId,
                    sourceCardGrpId = sourceCardGrpId,
                    instanceId = abilityIid,
                    ownerSeatId = 1,
                    cardProto = b.cardProto,
                )

            io.kotest.assertions.assertSoftly {
                obj.type shouldBe wotc.mtgo.gre.external.messaging.Messages.GameObjectType.Ability
                obj.zoneId shouldBe ZoneIds.STACK
                obj.grpId shouldBe abilityGrpId
                obj.objectSourceGrpId shouldBe sourceCardGrpId
                obj.instanceId shouldBe abilityIid
                obj.ownerSeatId shouldBe 1
                obj.controllerSeatId shouldBe 1
            }
        }
    })
