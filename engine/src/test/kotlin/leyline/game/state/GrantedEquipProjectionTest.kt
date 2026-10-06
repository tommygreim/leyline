package leyline.game.state

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.codes.KeywordGrpIds
import leyline.game.data.grantedKeywordAbilityGrpId
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class GrantedEquipProjectionTest :
    BoardTest({
        test("effect-granted equip retains the exact cost row across text actions and stack identity") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("The Irencrag", human, ZoneType.Battlefield)
                    addCard("Thor, God of Thunder", human, ZoneType.Battlefield)
                    repeat(3) { addCard("Mountain", human, ZoneType.Battlefield) }
                }
            val artifact = board.human.battlefield.card("The Irencrag")
            board.bridge.cardRepository.findGrantedKeywordAbilityGrpId(board.bridge.resolveGrpId(artifact), "Equip:3") shouldBe 1156
            board.stateOnlyDiff() // Prime the registry before animation/renaming.
            artifact.addChangedCardKeywords(listOf("Equip:3"), null, true, 801L, null)
            artifact.addChangedName("Everflame, Heroes' Legacy", false, 801L, 0L)
            val equip = artifact.spellAbilities.single { it.isEquip }
            equip.activatingPlayer = board.human
            board.bridge.cardRepository.grantedKeywordAbilityGrpId(equip) shouldBe 1156
            board.bridge
                .abilityRegistryFor(
                    artifact,
                    board.bridge.cardRepository.findByGrpId(86980),
                )!!
                .generatedUniqueAbilityId(equip) shouldBe
                20_000
            val gsm = board.stateOnlyDiff()
            val action =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .actionsList
                    .single { it.instanceId == board.instanceId(artifact.id) && it.abilityGrpId == 1156 }
            val added =
                gsm.persistentAnnotationsList.single {
                    AnnotationType.AddAbility_af5a in it.typeList && it.detailInt("grpid") == 1156
                }
            assertSoftly {
                board.bridge.resolveAbilityIdentity(artifact, equip)?.abilityGrpId shouldBe 1156
                action.manaCostList.sumOf { it.count } shouldBe 3
                added.detailInt("UniqueAbilityId") shouldBe action.uniqueAbilityId
                gsm.gameObjectsList
                    .single { it.instanceId == action.instanceId }
                    .uniqueAbilitiesList
                    .any { it.grpId == 1156 && it.id == action.uniqueAbilityId } shouldBe true
                board.bridge
                    .materializeEffectProjectionFacts()
                    .keywordEntries
                    .any { it.keyword == "Equip" } shouldBe false
            }
        }

        test("removing granted equip retires its text without stripping the equipped creature's flying") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("The Irencrag", human, ZoneType.Battlefield)
                    addCard("Thor, God of Thunder", human, ZoneType.Battlefield)
                }
            val artifact = board.human.battlefield.card("The Irencrag")
            val thor = board.human.battlefield.card("Thor, God of Thunder")
            val flyingId =
                board.bridge.cardRepository.findKeywordAbilityGrpId(
                    board.bridge.resolveGrpId(thor),
                    KeywordGrpIds.forKeyword("Flying")!!,
                )!!
            board.bridge.cardRepository.findGrantedKeywordAbilityGrpId(board.bridge.resolveGrpId(artifact), "Equip:3") shouldBe 1156
            artifact.addChangedCardKeywords(listOf("Equip:3"), null, true, 802L, null)
            val initial = board.stateOnlyDiff()
            val granted =
                initial.persistentAnnotationsList.single {
                    AnnotationType.AddAbility_af5a in it.typeList && it.detailInt("grpid") == 1156
                }
            val retired = board.snapshotDiff { artifact.removeChangedCardKeywords(802L, 0L) }
            assertSoftly {
                retired.diffDeletedPersistentAnnotationIdsList.contains(granted.id) shouldBe true
                thor.hasKeyword(forge.game.keyword.Keyword.FLYING) shouldBe true
                board.bridge
                    .projectionStateSnapshot()
                    .viewerCursors
                    .getValue(SeatId(1))
                    .fullState!!
                    .gameObjectsList
                    .single { it.instanceId == board.instanceId(thor.id) }
                    .uniqueAbilitiesList
                    .any { it.grpId == flyingId } shouldBe true
            }
        }
    })
