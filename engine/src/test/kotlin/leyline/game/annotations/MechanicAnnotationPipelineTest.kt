package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.MechanicAnnotations
import leyline.game.event.DamageSourceKind
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Mechanic-stage annotation pipeline tests — counters, shuffle, scry, surveil,
 * tokens, power/toughness, attach/detach, tap/untap, and mixed mechanic events.
 */
class MechanicAnnotationPipelineTest :
    FunSpec({

        tags(UnitTag)

        /** Identity resolver for unit tests — forgeCardId maps to forgeCardId + 1000. */
        fun testResolver(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value + 1000)

        // -- CountersChanged --

        test("counterAddedAnnotation") {
            // Forge sends display name "+1/+1" (from CounterEnumType.getName()), not "P1P1"
            val events =
                listOf(
                    GameEvent.CountersChanged(cardId = ForgeCardId(42), counterType = "+1/+1", oldCount = 0, newCount = 2),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)

            assertSoftly {
                result.transient.size shouldBe 1
                result.transient[0].typeList shouldContain AnnotationType.CounterAdded
                result.transient[0].affectedIdsList shouldContain 1042
                result.transient[0].detailInt("counter_type") shouldBe 1 // P1P1
                result.transient[0].detailInt("transaction_amount") shouldBe 2
            }

            // Persistent: Counter state annotation with current count and correct enum value
            assertSoftly {
                result.persistent.size shouldBe 1
                result.persistent[0].typeList shouldBe
                    listOf(
                        AnnotationType.ModifiedToughness,
                        AnnotationType.ModifiedPower,
                        AnnotationType.Counter_803b,
                    )
                result.persistent[0].detailInt("count") shouldBe 2
                result.persistent[0].detailInt("counter_type") shouldBe 1 // P1P1 = 1
            }
        }

        test("enlist tap payment uses enlisting attacker as affector") {
            val events =
                listOf(
                    GameEvent.CardTapped(cardId = ForgeCardId(51), tapped = true, affectorCardId = ForgeCardId(42)),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)

            val tap = result.transient.single { AnnotationType.TappedUntappedPermanent in it.typeList }
            assertSoftly {
                tap.affectorId shouldBe 1042
                tap.affectedIdsList shouldContain 1051
                tap.detailInt("tapped") shouldBe 1
            }
        }

        test("counterRemovedAnnotation") {
            // Forge sends "LOYAL" for loyalty counters (CounterEnumType.LOYALTY.getName())
            val events =
                listOf(
                    GameEvent.CountersChanged(cardId = ForgeCardId(42), counterType = "LOYAL", oldCount = 5, newCount = 2),
                )
            val annotations = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver).transient

            assertSoftly {
                annotations.size shouldBe 1
                annotations[0].typeList shouldContain AnnotationType.CounterRemoved
                annotations[0].detailInt("transaction_amount") shouldBe 3
            }
        }

        test("unknownCardCounterKeepsTransientButSkipsPersistentState") {
            val events =
                listOf(
                    GameEvent.CountersChanged(cardId = ForgeCardId(42), counterType = "ODOR", oldCount = 0, newCount = 1),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)

            assertSoftly {
                result.transient.size shouldBe 1
                result.transient[0].typeList shouldContain AnnotationType.CounterAdded
                // Unmapped Forge counter name → 0 (CounterType.None); never a string, which
                // throws in the client's CounterAddedRemovedAnnotationParser.
                result.transient[0].detailInt("counter_type") shouldBe 0
                result.persistent.shouldBeEmpty()
            }
        }

        test("Plan counter retains authoritative persistent state") {
            val events =
                listOf(
                    GameEvent.CountersChanged(cardId = ForgeCardId(42), counterType = "PLAN", oldCount = 0, newCount = 1),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)

            assertSoftly {
                result.transient.single().detailInt("counter_type") shouldBe 211
                result.persistent.single().detailInt("counter_type") shouldBe 211
                result.persistent.single().detailInt("count") shouldBe 1
            }
        }

        test("counterUnchangedSkipped") {
            val events =
                listOf(
                    GameEvent.CountersChanged(cardId = ForgeCardId(42), counterType = "P1P1", oldCount = 3, newCount = 3),
                )
            val annotations = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver).transient
            annotations.shouldBeEmpty()
        }

        test("playerPoisonCounterAnnotation") {
            val events =
                listOf(
                    GameEvent.PlayerCountersChanged(seatId = SeatId(1), counterType = "POISON", oldCount = 8, newCount = 10),
                )
            val result =
                MechanicAnnotations.mechanicAnnotations(
                    events,
                    idResolver = ::testResolver,
                    playerCounterAffectorResolver = { _, _ -> InstanceId(277) },
                )

            assertSoftly {
                result.transient.size shouldBe 1
                result.transient[0].typeList shouldContain AnnotationType.CounterAdded
                result.transient[0].affectorId shouldBe 277
                result.transient[0].affectedIdsList shouldContain 1
                result.transient[0].detailInt("counter_type") shouldBe 3
                result.transient[0].detailInt("transaction_amount") shouldBe 2
            }

            assertSoftly {
                result.persistent.size shouldBe 1
                result.persistent[0].typeList shouldContain AnnotationType.Counter_803b
                result.persistent[0].affectedIdsList shouldContain 1
                result.persistent[0].detailInt("count") shouldBe 10
                result.persistent[0].detailInt("counter_type") shouldBe 3
            }
        }

        // -- LibraryShuffled --

        test("LibraryShuffled emits identity remap details") {
            val events =
                listOf(
                    GameEvent.LibraryShuffled(
                        seatId = SeatId(1),
                        oldIds = listOf(101, 102),
                        newIds = listOf(201, 202),
                    ),
                )

            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)

            assertSoftly {
                result.transient.single().detailIntList("OldIds") shouldBe listOf(101, 102)
                result.transient.single().detailIntList("NewIds") shouldBe listOf(201, 202)
                result.persistent.shouldBeEmpty()
            }
        }

        // -- Scry --

        test("scryAnnotation") {
            val events =
                listOf(
                    GameEvent.Scry(seatId = SeatId(2), topIds = listOf(11), bottomIds = listOf(12, 13)),
                )
            val annotations = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver).transient

            assertSoftly {
                annotations.size shouldBe 1
                annotations[0].typeList shouldContain AnnotationType.Scry_af5a
                annotations[0].detailIntList("topIds") shouldBe listOf(11)
                annotations[0].detailIntList("bottomIds") shouldBe listOf(12, 13)
            }
        }

        // -- Surveil --

        test("surveilAnnotation") {
            val events =
                listOf(
                    GameEvent.Surveil(seatId = SeatId(1), libraryIds = listOf(21), graveyardIds = listOf(22)),
                )
            val annotations = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver).transient

            assertSoftly {
                // A surveil emits no Scry annotation: the client reads only
                // topIds/bottomIds and would narrate "Scry: N top, M bottom".
                // The move is carried by its ZoneTransfers (category=Surveil).
                annotations.count { AnnotationType.Scry_af5a in it.typeList } shouldBe 0
            }
        }

        // -- TokenCreated --

        test("tokenCreatedAnnotation") {
            val events =
                listOf(
                    GameEvent.TokenCreated(cardId = ForgeCardId(99), seatId = SeatId(1), sourceCardId = ForgeCardId(42)),
                )
            val annotations =
                MechanicAnnotations
                    .mechanicAnnotations(
                        events,
                        idResolver = ::testResolver,
                        tokenAffectorResolver = { ev -> ev.sourceCardId?.let { InstanceId(2042) } },
                    ).transient

            assertSoftly {
                annotations.size shouldBe 1
                annotations[0].typeList shouldContain AnnotationType.TokenCreated
                annotations[0].affectorId shouldBe 2042
                annotations[0].affectedIdsList shouldContain 1099
            }
        }

        // -- TokenDestroyed --

        test("tokenDestroyedProducesAnnotation") {
            val events =
                listOf(
                    GameEvent.TokenDestroyed(cardId = ForgeCardId(88), seatId = SeatId(1)),
                )
            val annotations = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver).transient

            assertSoftly {
                annotations.size shouldBe 1
                annotations[0].typeList shouldContain AnnotationType.TokenDeleted
                annotations[0].affectorId shouldBe 1088
                annotations[0].affectedIdsList shouldContain 1088
            }
        }

        // -- PowerToughnessChanged --

        test("powerToughnessChangedBothAnnotations") {
            val events =
                listOf(
                    GameEvent.PowerToughnessChanged(
                        cardId = ForgeCardId(50),
                        oldPower = 2,
                        newPower = 4,
                        oldToughness = 3,
                        newToughness = 5,
                    ),
                )
            val annotations = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver).transient

            assertSoftly {
                annotations.size shouldBe 3
                annotations[0].typeList shouldContain AnnotationType.ModifiedPower
                annotations[0].affectedIdsList shouldContain 1050
                annotations[0].detailsCount shouldBe 0
                annotations[1].typeList shouldContain AnnotationType.ModifiedToughness
                annotations[1].detailsCount shouldBe 0
                annotations[2].typeList shouldContain AnnotationType.PowerToughnessModCreated
            }
        }

        test("powerOnlyChangedOneAnnotation") {
            val events =
                listOf(
                    GameEvent.PowerToughnessChanged(
                        cardId = ForgeCardId(50),
                        oldPower = 2,
                        newPower = 5,
                        oldToughness = 3,
                        newToughness = 3,
                    ),
                )
            val annotations = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver).transient

            assertSoftly {
                annotations.size shouldBe 2
                annotations[0].typeList shouldContain AnnotationType.ModifiedPower
                annotations[1].typeList shouldContain AnnotationType.PowerToughnessModCreated
            }
        }

        test("toughnessOnlyChangedOneAnnotation") {
            val events =
                listOf(
                    GameEvent.PowerToughnessChanged(
                        cardId = ForgeCardId(50),
                        oldPower = 2,
                        newPower = 2,
                        oldToughness = 3,
                        newToughness = 1,
                    ),
                )
            val annotations = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver).transient

            assertSoftly {
                annotations.size shouldBe 2
                annotations[0].typeList shouldContain AnnotationType.ModifiedToughness
                annotations[1].typeList shouldContain AnnotationType.PowerToughnessModCreated
            }
        }

        // -- CardAttached --

        test("attachProducesCorrectAnnotationShape") {
            val events =
                listOf(
                    GameEvent.CardAttached(cardId = ForgeCardId(55), targetCardId = ForgeCardId(66), seatId = SeatId(1)),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)

            // Transient: AttachmentCreated
            val created = result.transient[0]
            assertSoftly {
                result.transient.size shouldBe 1
                created.typeList shouldContain AnnotationType.AttachmentCreated
                created.affectorId shouldBe testResolver(ForgeCardId(55)).value
                created.affectedIdsList shouldBe listOf(testResolver(ForgeCardId(66)).value)
            }

            // Persistent: Attachment
            val attach = result.persistent[0]
            assertSoftly {
                result.persistent.size shouldBe 1
                attach.typeList shouldContain AnnotationType.Attachment
                attach.affectorId shouldBe testResolver(ForgeCardId(55)).value
                attach.affectedIdsList shouldBe listOf(testResolver(ForgeCardId(66)).value)
            }
        }

        test("detachReturnsDetachedForgeCardId") {
            val events =
                listOf(
                    GameEvent.CardDetached(cardId = ForgeCardId(60), seatId = SeatId(1)),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)
            result.detachedForgeCardIds shouldBe listOf(ForgeCardId(60))
        }

        // -- RemoveAttachment --

        test("detachProducesRemoveAttachment") {
            val events =
                listOf(
                    GameEvent.CardDetached(
                        cardId = ForgeCardId(60),
                        seatId = SeatId(1),
                        targetCardId = ForgeCardId(61),
                        invalidatingAbilityGrpId = 244,
                    ),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)
            val annotations = result.transient

            assertSoftly {
                annotations.size shouldBe 1
                annotations[0].typeList shouldContain AnnotationType.RemoveAttachment
                annotations[0].affectorId shouldBe testResolver(ForgeCardId(60)).value
                annotations[0].affectedIdsList shouldContain testResolver(ForgeCardId(61)).value
                annotations[0].detailInt("invalidating_grpid") shouldBe 244
            }
        }

        // -- Mixed events --

        test("zoneTransferEventsProduceNoTransientButTrackCleanup") {
            val events =
                listOf(
                    GameEvent.ZoneChanged(cardId = ForgeCardId(1), from = Zone.Hand, to = Zone.Battlefield),
                    GameEvent.LandPlayed(cardId = ForgeCardId(1), seatId = SeatId(1)),
                    GameEvent.CardDestroyed(cardId = ForgeCardId(2), seatId = SeatId(1)),
                    GameEvent.DamageDealtToPlayer(
                        sourceCardId = ForgeCardId(4),
                        targetSeatId = SeatId(1),
                        amount = 3,
                        sourceKind = DamageSourceKind.Combat,
                        changesLife = true,
                    ),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)
            result.transient.shouldBeEmpty()
            // CardDestroyed tracks for DisplayCardUnderCard cleanup
            result.exileSourceLeftPlayForgeCardIds shouldBe listOf(ForgeCardId(2))
        }

        // -- CardTapped --

        test("cardTappedProducesAnnotation") {
            val events =
                listOf(
                    GameEvent.CardTapped(cardId = ForgeCardId(70), tapped = true),
                )
            val annotations = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver).transient

            assertSoftly {
                annotations.size shouldBe 1
                annotations[0].typeList shouldContain AnnotationType.TappedUntappedPermanent
                annotations[0].affectorId shouldBe testResolver(ForgeCardId(70)).value
                annotations[0].affectedIdsList shouldContain testResolver(ForgeCardId(70)).value
                annotations[0].detailInt("tapped") shouldBe 1
            }
        }

        test("cardUntappedProducesAnnotation") {
            val events =
                listOf(
                    GameEvent.CardTapped(cardId = ForgeCardId(71), tapped = false),
                )
            val annotations = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver).transient

            assertSoftly {
                annotations.size shouldBe 1
                annotations[0].typeList shouldContain AnnotationType.TappedUntappedPermanent
                annotations[0].detailInt("tapped") shouldBe 0
            }
        }

        test("mechanicAnnotationsMultipleEvents") {
            val events =
                listOf(
                    GameEvent.CountersChanged(cardId = ForgeCardId(42), counterType = "P1P1", oldCount = 0, newCount = 1),
                    GameEvent.Scry(seatId = SeatId(1), topIds = listOf(11, 12), bottomIds = emptyList()),
                )
            val annotations = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver).transient
            assertSoftly {
                annotations.size shouldBe 2
                annotations[0].typeList shouldContain AnnotationType.CounterAdded
                annotations[1].typeList shouldContain AnnotationType.Scry_af5a
            }
        }
    })
