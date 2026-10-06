package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.MechanicAnnotationResult
import leyline.game.annotations.MechanicAnnotations
import leyline.game.event.GameEvent
import leyline.game.iid
import leyline.game.mapping.ZoneIds
import leyline.game.state.EffectTracker
import leyline.game.state.PersistentAnnotationStore
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Persistent-stage annotation pipeline tests — computeBatch lifecycle,
 * DisplayCardUnderCard creation/cleanup, and exile source tracking.
 */
class PersistentAnnotationPipelineTest :
    FunSpec({

        tags(UnitTag)

        /** Identity resolver for unit tests — forgeCardId maps to forgeCardId + 1000. */
        fun testResolver(forgeCardId: ForgeCardId): InstanceId = InstanceId(forgeCardId.value + 1000)

        test("copy choice rectangle retires without pruning an unrelated life payment rectangle") {
            val copy =
                AnnotationBuilder.pendingEffect(
                    InstanceId(101),
                    SeatId(1),
                    leyline.bridge.types.GrpId(701),
                )
            val life =
                AnnotationBuilder
                    .replacementEffect(
                        leyline.bridge.types.EffectId(900),
                        InstanceId(202),
                        leyline.bridge.types.GrpId(702),
                        InstanceId(200),
                    ).toBuilder()
                    .setId(10)
                    .build()
            val opened =
                PersistentAnnotationStore.computeBatch(
                    currentActive = mapOf(10 to life),
                    startPersistentId = 11,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = MechanicAnnotationResult(emptyList(), emptyList(), mapOf(PendingEffectKind to listOf(copy))),
                    resolveInstanceId = ::testResolver,
                )
            opened.allAnnotations.size shouldBe 2
            val closed =
                PersistentAnnotationStore.computeBatch(
                    currentActive = opened.allAnnotations.associateBy { it.id },
                    startPersistentId = opened.nextPersistentId,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = MechanicAnnotationResult(emptyList(), emptyList()),
                    resolveInstanceId = ::testResolver,
                )
            closed.allAnnotations shouldBe listOf(life)
            closed.deletedIds shouldBe listOf(11)
        }

        test("pending effects from one source retain independent ability identities across refresh") {
            val first = AnnotationBuilder.pendingEffect(InstanceId(101), SeatId(1), leyline.bridge.types.GrpId(701))
            val second = AnnotationBuilder.pendingEffect(InstanceId(101), SeatId(1), leyline.bridge.types.GrpId(702))

            fun batch(
                active: Map<Int, wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>,
                next: Int,
                effects: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>,
            ) = PersistentAnnotationStore.computeBatch(
                currentActive = active,
                startPersistentId = next,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = MechanicAnnotationResult(emptyList(), emptyList(), mapOf(PendingEffectKind to effects)),
                resolveInstanceId = ::testResolver,
            )
            val opened = batch(emptyMap(), 1, listOf(first, second))
            opened.allAnnotations.size shouldBe 2
            val refresh = batch(opened.allAnnotations.associateBy { it.id }, opened.nextPersistentId, listOf(first, second))
            refresh.allAnnotations shouldBe opened.allAnnotations
            refresh.deletedIds.shouldBeEmpty()
            val closedFirst = batch(refresh.allAnnotations.associateBy { it.id }, refresh.nextPersistentId, listOf(second))
            closedFirst.allAnnotations shouldBe listOf(opened.allAnnotations.last())
            closedFirst.deletedIds shouldBe listOf(opened.allAnnotations.first().id)
        }

        test("copy layer annotation persists across refresh and retires when the layer is removed") {
            val copy = AnnotationBuilder.copiedPermanent(InstanceId(301), leyline.bridge.types.GrpId(801))

            fun batch(
                active: Map<Int, wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>,
                next: Int,
                copies: List<wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo>,
            ) = PersistentAnnotationStore.computeBatch(
                currentActive = active,
                startPersistentId = next,
                effectPersistent = emptyList(),
                effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                transferPersistent = emptyList(),
                mechanicResult = MechanicAnnotationResult(emptyList(), emptyList(), mapOf(CopiedPermanentKind to copies)),
                resolveInstanceId = ::testResolver,
            )
            val first = batch(emptyMap(), 1, listOf(copy))
            val refresh = batch(first.allAnnotations.associateBy { it.id }, first.nextPersistentId, listOf(copy))
            refresh.allAnnotations shouldBe first.allAnnotations
            refresh.deletedIds.shouldBeEmpty()
            val removed = batch(refresh.allAnnotations.associateBy { it.id }, refresh.nextPersistentId, emptyList())
            removed.allAnnotations.shouldBeEmpty()
            removed.deletedIds shouldBe listOf(1)
        }

        // -- DisplayCardUnderCard --

        test("retiring one keyword recipient preserves the other and does not resurrect on full state") {
            val grants =
                listOf(7010 to 100, 7011 to 200).map { (effectId, recipient) ->
                    AnnotationBuilder.addAbilityPacked(
                        affectedId = InstanceId(recipient),
                        grpIds = listOf(leyline.bridge.types.GrpId(8)),
                        effectId = leyline.bridge.types.EffectId(effectId),
                        uniqueAbilityIds = listOf(recipient),
                        originalAbilityObjectZcids = listOf(500),
                        affectorId = InstanceId(500),
                    )
                }
            val initial =
                PersistentAnnotationStore.computeBatch(
                    currentActive = emptyMap(),
                    startPersistentId = 1,
                    effectPersistent = grants,
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = MechanicAnnotationResult(emptyList(), emptyList()),
                    resolveInstanceId = ::testResolver,
                )
            val retired =
                PersistentAnnotationStore.computeBatch(
                    currentActive = initial.allAnnotations.associateBy { it.id },
                    startPersistentId = initial.nextPersistentId,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    destroyedEffectIds = listOf(7010),
                    transferPersistent = emptyList(),
                    mechanicResult = MechanicAnnotationResult(emptyList(), emptyList()),
                    resolveInstanceId = ::testResolver,
                )
            retired.deletedIds shouldBe listOf(initial.allAnnotations[0].id)
            retired.allAnnotations.map { it.affectedIdsList } shouldBe listOf(listOf(200))
            val full =
                PersistentAnnotationStore.computeBatch(
                    currentActive = retired.allAnnotations.associateBy { it.id },
                    startPersistentId = retired.nextPersistentId,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = MechanicAnnotationResult(emptyList(), emptyList()),
                    resolveInstanceId = ::testResolver,
                )
            full.allAnnotations shouldBe retired.allAnnotations
        }

        test("cardExiledWithSourceEmitsDisplayCardUnderCard") {
            val events =
                listOf(
                    GameEvent.CardExiled(
                        cardId = ForgeCardId(80),
                        seatId = SeatId(1),
                        sourceCardId = ForgeCardId(90),
                        fromBattlefield = true,
                    ),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)

            result.transient.shouldBeEmpty()
            result.persistent.size shouldBe 1
            val ann = result.persistent[0]
            assertSoftly {
                ann.typeList shouldContain AnnotationType.DisplayCardUnderCard
                ann.affectorId shouldBe testResolver(ForgeCardId(90)).value
                ann.affectedIdsList shouldBe listOf(testResolver(ForgeCardId(80)).value)
            }
            val tmpZone = ann.detailsList.first { it.key == "TemporaryZoneTransfer" }
            tmpZone.getValueInt32(0) shouldBe 1
        }

        test("cardExiledFromHandWithSourceEmitsDisplayCardUnderCard") {
            val events =
                listOf(
                    GameEvent.CardExiled(
                        cardId = ForgeCardId(80),
                        seatId = SeatId(2),
                        sourceCardId = ForgeCardId(90),
                        fromBattlefield = false,
                    ),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)

            val ann = result.persistent.single()
            assertSoftly {
                ann.typeList shouldContain AnnotationType.DisplayCardUnderCard
                ann.affectorId shouldBe testResolver(ForgeCardId(90)).value
                ann.affectedIdsList shouldBe listOf(testResolver(ForgeCardId(80)).value)
            }
        }

        test("cardExiledWithoutSourceDoesNotEmitDisplayCardUnderCard") {
            val events =
                listOf(
                    GameEvent.CardExiled(cardId = ForgeCardId(80), seatId = SeatId(1)),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)
            result.transient.shouldBeEmpty()
            result.persistent.shouldBeEmpty()
        }

        test("cardDestroyedPopulatesExileSourceLeftPlay") {
            val events =
                listOf(
                    GameEvent.CardDestroyed(cardId = ForgeCardId(90), seatId = SeatId(1)),
                )
            val result = MechanicAnnotations.mechanicAnnotations(events, idResolver = ::testResolver)
            result.exileSourceLeftPlayForgeCardIds shouldBe listOf(ForgeCardId(90))
        }

        test("computeBatchRemovesDisplayCardUnderCardWhenSourceLeavesPlay") {
            val ann =
                AnnotationBuilder
                    .displayCardUnderCard(affectorId = 1090.iid, instanceId = 1080.iid)
                    .toBuilder()
                    .setId(5)
                    .build()
            val active = mapOf(5 to ann)
            val mechanicResult =
                MechanicAnnotationResult(
                    transient = emptyList(),
                    persistent = emptyList(),
                    exileSourceLeftPlayForgeCardIds = listOf(ForgeCardId(90)),
                )
            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 10,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult,
                    resolveInstanceId = { fid -> InstanceId(fid.value + 1000) },
                    // Reverse lookup: iid 1090 → forgeCardId 90 (inverse of testResolver)
                    resolveForgeCardId = { iid -> ForgeCardId(iid.value - 1000) },
                )
            result.allAnnotations.shouldBeEmpty()
            result.deletedIds shouldBe listOf(5)
        }

        test("computeBatchRemovesDisplayCardUnderCardAfterZoneTransferRealloc") {
            // Simulates the real bug: Banishing Light (forgeId=1) had iid 111 when
            // DisplayCardUnderCard was created. After destruction (BF→GY), its iid
            // was reallocated to 125. The reverse lookup resolves the OLD iid (111)
            // back to forgeCardId 1, matching the exileSourceLeftPlayForgeCardIds.
            val ann =
                AnnotationBuilder
                    .displayCardUnderCard(affectorId = 111.iid, instanceId = 116.iid)
                    .toBuilder()
                    .setId(3)
                    .build()
            val active = mapOf(3 to ann)
            val mechanicResult =
                MechanicAnnotationResult(
                    transient = emptyList(),
                    persistent = emptyList(),
                    exileSourceLeftPlayForgeCardIds = listOf(ForgeCardId(1)), // forgeCardId of Banishing Light
                )
            // Reverse lookup: old iid 111 → forgeCardId 1, new iid 125 → forgeCardId 1
            val forgeCardIdMap = mapOf(111 to 1, 125 to 1)
            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = active,
                    startPersistentId = 10,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = mechanicResult,
                    resolveInstanceId = { fid -> InstanceId(fid.value + 1000) },
                    resolveForgeCardId = { iid -> forgeCardIdMap[iid.value]?.let { ForgeCardId(it) } },
                )
            result.allAnnotations.shouldBeEmpty()
            result.deletedIds shouldBe listOf(3)
        }

        test("computeBatchRemovesAttachmentAfterAuraZoneTransferRealloc") {
            val attachment =
                AnnotationBuilder
                    .attachment(auraIid = 111.iid, targetIid = 108.iid)
                    .toBuilder()
                    .setId(3)
                    .build()
            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = mapOf(3 to attachment),
                    startPersistentId = 10,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult =
                        MechanicAnnotationResult(
                            transient = emptyList(),
                            persistent = emptyList(),
                            detachedForgeCardIds = listOf(ForgeCardId(1)),
                        ),
                    // The detach event observes the post-transfer id 125.
                    resolveInstanceId = { InstanceId(125) },
                    // The attachment row still names the pre-transfer id 111.
                    resolveForgeCardId = { iid -> if (iid.value == 111) ForgeCardId(1) else null },
                )

            result.allAnnotations.shouldBeEmpty()
            result.deletedIds shouldBe listOf(3)
        }

        test("computeBatchKeepsAttachmentForDifferentDetachedAura") {
            val attachment =
                AnnotationBuilder
                    .attachment(auraIid = 111.iid, targetIid = 108.iid)
                    .toBuilder()
                    .setId(3)
                    .build()
            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = mapOf(3 to attachment),
                    startPersistentId = 10,
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult =
                        MechanicAnnotationResult(
                            transient = emptyList(),
                            persistent = emptyList(),
                            detachedForgeCardIds = listOf(ForgeCardId(2)),
                        ),
                    resolveInstanceId = { InstanceId(125) },
                    resolveForgeCardId = { iid -> if (iid.value == 111) ForgeCardId(1) else null },
                )

            result.allAnnotations shouldBe listOf(attachment)
            result.deletedIds.shouldBeEmpty()
        }

        test("computeBatchRemovesStackEnteredZoneThisTurnOnResolve") {
            val stackAnn =
                AnnotationBuilder
                    .enteredZoneThisTurn(ZoneIds.STACK, 100.iid)
                    .toBuilder()
                    .setId(1)
                    .build()
            val battlefieldAnn =
                AnnotationBuilder
                    .enteredZoneThisTurn(ZoneIds.BATTLEFIELD, 200.iid)
                    .toBuilder()
                    .setId(2)
                    .build()

            val result =
                PersistentAnnotationStore.computeBatch(
                    currentActive = mapOf(1 to stackAnn, 2 to battlefieldAnn),
                    startPersistentId = 10,
                    frame = FrameContext.INERT.copy(resolvingStackIids = setOf(100)),
                    effectPersistent = emptyList(),
                    effectDiff = EffectTracker.DiffResult(emptyList(), emptyList()),
                    transferPersistent = emptyList(),
                    mechanicResult = MechanicAnnotationResult(transient = emptyList(), persistent = emptyList()),
                    resolveInstanceId = { fid -> InstanceId(fid.value + 1000) },
                )

            result.deletedIds shouldBe listOf(1)
            result.allAnnotations.map { it.id } shouldBe listOf(2)
        }
    })
