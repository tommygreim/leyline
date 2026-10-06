package leyline.game.mapping

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.state.CitysBlessingDesignationKind
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import leyline.testkit.detailString
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class CitysBlessingProjectionTest :
    BoardTest({
        beforeSpec { leyline.testkit.registerUpstreamCatalogCards("Tendershoot Dryad") }

        test("blessing gain targets the player and persists after all Ascend sources leave") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Tendershoot Dryad", human, ZoneType.Battlefield)
                    repeat(8) { addCard("Forest", human, ZoneType.Battlefield) }
                }
            val initial = board.stateOnlyDiff()
            initial.persistentAnnotationsList.filter(CitysBlessingDesignationKind::matches).shouldBeEmpty()
            val initialBadge =
                board.bridge.projectionStateSnapshot().persistentAnnotations.activeAnnotations.values.single {
                    AnnotationType.AbilityWordActive in it.typeList &&
                        it.detailString("AbilityWordName") ==
                        "Ascend"
                }
            initialBadge.detailInt("value") shouldBe 9
            initialBadge.detailsList.any { it.key == "City's Blessing" } shouldBe false

            val gained = board.snapshotDiff { board.human.setBlessing(true, null) }
            val designation = gained.persistentAnnotationsList.single(CitysBlessingDesignationKind::matches)
            val gain = gained.annotationsList.single { AnnotationType.GainDesignation in it.typeList }
            val badge =
                gained.persistentAnnotationsList.single {
                    AnnotationType.AbilityWordActive in it.typeList &&
                        it.detailString("AbilityWordName") == "Ascend"
                }
            assertSoftly {
                designation.affectorId shouldBe 1
                designation.affectedIdsList shouldBe listOf(1)
                designation.detailInt("DesignationType") shouldBe 6
                designation.detailInt("PromptMessage") shouldBe 126
                designation.detailsList.size shouldBe 2
                gain.affectorId shouldBe 1
                gain.affectedIdsList shouldBe listOf(1)
                gain.detailsList.size shouldBe 1
                gain.detailInt("DesignationType") shouldBe 6
                badge.id shouldBe initialBadge.id
                badge.detailInt("City's Blessing") shouldBe 1
            }
            val retained =
                board.snapshotDiff {
                    val dryad = board.human.battlefield.card("Tendershoot Dryad")
                    board.game.action.moveToGraveyard(dryad, null)
                }
            assertSoftly {
                board.human.hasBlessing() shouldBe true
                retained.diffDeletedPersistentAnnotationIdsList shouldNotContain designation.id
                retained.annotationsList
                    .filter {
                        AnnotationType.GainDesignation in it.typeList || AnnotationType.LoseDesignation in it.typeList
                    }.shouldBeEmpty()
                board.bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations.activeAnnotations.values
                    .single(
                        CitysBlessingDesignationKind::matches,
                    ).id shouldBe
                    designation.id
            }
        }

        test("Ascend badge follows a source entering the battlefield") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Tendershoot Dryad", human, ZoneType.Hand)
                }
            val dryad = board.human.hand.card("Tendershoot Dryad")
            val oldIid = board.instanceId(dryad.id)
            val gsm = board.snapshotDiff { board.game.action.moveToPlay(dryad, null, null) }
            val badge =
                gsm.persistentAnnotationsList.single {
                    AnnotationType.AbilityWordActive in it.typeList && it.detailString("AbilityWordName") == "Ascend"
                }
            val newIid = board.instanceId(dryad.id)
            assertSoftly {
                oldIid shouldNotBe newIid
                badge.affectedIdsList shouldBe listOf(newIid)
                badge.affectorId shouldBe 1
                badge.detailInt("value") shouldBe 1
            }
        }

        test("phased-out permanents do not count toward Ascend and phased-out sources lose their badge") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Tendershoot Dryad", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Forest", ai, ZoneType.Battlefield)
                }
            board.stateOnlyDiff()
            val reduced =
                board.snapshotDiff {
                    board.human.battlefield
                        .card("Forest")
                        .phase(false)
                }
            reduced.persistentAnnotationsList
                .single {
                    AnnotationType.AbilityWordActive in it.typeList && it.detailString("AbilityWordName") == "Ascend"
                }.detailInt("value") shouldBe 1
            board.snapshotDiff {
                board.human.battlefield
                    .card("Tendershoot Dryad")
                    .phase(false)
            }
            board.bridge
                .projectionStateSnapshot()
                .persistentAnnotations.activeAnnotations.values
                .filter {
                    AnnotationType.AbilityWordActive in it.typeList && it.detailString("AbilityWordName") == "Ascend"
                }.shouldBeEmpty()
        }

        test("ten permanents without Ascend do not project a blessing or Ascend badge") {
            val board = startWithBoard { _, human, _ -> repeat(10) { addCard("Forest", human, ZoneType.Battlefield) } }
            val gsm = board.stateOnlyDiff()
            assertSoftly {
                board.human.hasBlessing() shouldBe false
                gsm.persistentAnnotationsList.filter(CitysBlessingDesignationKind::matches).shouldBeEmpty()
                gsm.persistentAnnotationsList
                    .filter {
                        AnnotationType.AbilityWordActive in it.typeList &&
                            it.detailString("AbilityWordName") == "Ascend"
                    }.shouldBeEmpty()
            }
        }
    })
