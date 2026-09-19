package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.event.ZoneMove
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import leyline.game.state.InstanceIdRegistry
import leyline.game.state.ZoneHandoff
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import kotlin.collections.iterator

/** Pre-resolved mana payment: all IDs are client instanceIds, ready for annotation building. */
data class ManaPaymentRecord(
    val landInstanceId: Int,
    val manaAbilityInstanceId: Int,
    val color: Int,
    val abilityGrpId: Int,
    /** InstanceId of the spell/ability this mana pays for (ManaPaid.affectedIds). */
    val spellInstanceId: Int = 0,
)

data class AppliedTransfer(
    val origId: Int,
    val newId: Int,
    val category: TransferCategory,
    val srcZoneId: Int,
    val destZoneId: Int,
    val forgeCardId: ForgeCardId? = null,
    val grpId: Int,
    val ownerSeatId: Int,
    /** InstanceId of the ability/spell that caused this transfer (for affectorId). */
    val affectorId: Int = 0,
    /** client ManaColor ordinals for land color production (W=1, U=2, B=3, R=4, G=5). */
    val colorOrdinals: List<Int> = emptyList(),
    /** True when this PlayLand transfer came from a modal DFC land face. */
    val isMdfcLandPlay: Boolean = false,
    /** Resolved mana payments for CastSpell (one per land tapped). */
    val manaPayments: List<ManaPaymentRecord> = emptyList(),
    /** True if this transfer is an adventure spell cast (UserActionTaken actionType=16). */
    val isAdventureCast: Boolean = false,
    /** True if this transfer is an Omen spell cast (UserActionTaken actionType=24). */
    val isOmenCast: Boolean = false,
    /** Non-zero when this CastSpell transfer used an alternate cost (Madness, Flashback,
     *  Warp, Cycling, Impending). Carries the client ability grpId for the alt-cost. */
    val altCostAbilityGrpId: Int = 0,
    /** Cast-through ability identity when it differs from [altCostAbilityGrpId]. */
    val castAbilityGrpId: Int = altCostAbilityGrpId,
    /** Non-zero when the cast paid Kicker. Carries the per-card Kicker ability grpId. */
    val kickerAbilityGrpId: Int = 0,
    val additionalCostGrpId: Int = 0,
    val chosenCostPromptId: Int = 0,
    /** Non-zero when the cast chose an X value. Drives CastingTimeOption type=ChooseX. */
    val chosenX: Int = 0,
    val openingHandAbilityInstanceId: Int = 0,
    val openingHandAbilityGrpId: Int = 0,
    val openingHandSeatId: Int = 0,
)

/** A triggered or activated ability that just appeared on the stack (no previousZone entry).
 *
 *  [isActivatedAbility] — true when the source SpellCast event flagged the
 *  stack item as an activated ability (cycling, channel, unearth, …) rather
 *  than a triggered one. Activated abilities skip the persistent
 *  `TriggeringObject` annotation: that is reserved for triggers.
 *
 *  [activationZoneId] — when [isActivatedAbility] is true and the matching
 *  SpellCast event carried an explicit `activationZoneId`, prefer it over the
 *  snapshot-derived [sourceZoneId] (which can read 0 when the source card's
 *  pre-cost zone wasn't tracked through the snapshot diff — common for
 *  puzzle-injected starting states). Zero means "fall back to [sourceZoneId]". */
data class StackAbilityAppearance(
    val abilityInstanceId: Int,
    val sourceCardInstanceId: Int,
    val sourceZoneId: Int,
    val grpId: Int,
    val isActivatedAbility: Boolean = false,
    val isActivatedDiscover: Boolean = false,
    val activationZoneId: Int = 0,
    val triggeringObjectInstanceId: Int? = null,
    val triggeringObjectZoneId: Int = 0,
    val voidTrigger: Boolean = false,
)

/** A triggered ability that was on the stack and is now gone (resolved or fizzled). */
data class StackAbilityDisappearance(
    val abilityInstanceId: Int,
    val sourceCardInstanceId: Int,
    val grpId: Int,
    val hasFizzled: Boolean,
)

data class SnapshotTransferFallback(
    val forgeCardId: ForgeCardId?,
    val srcZoneId: Int,
    val destZoneId: Int,
)

/**
 * Result of zone-transfer detection.
 *
 * Contains patched copies of gameObjects/zones (with reallocated instanceIds
 * and Limbo entries) plus deferred side effects the caller must apply.
 */
data class TransferResult(
    /** Detected zone transfers for annotation building. */
    val transfers: List<AppliedTransfer>,
    /** GameObjects with instanceIds patched for zone transfers. */
    val patchedObjects: List<GameObjectInfo>,
    /** Zones with instanceIds patched + Limbo entries appended. */
    val patchedZones: List<ZoneInfo>,
    /** InstanceIds to retire to Limbo (caller applies via [leyline.game.state.ZoneTracking.retireToLimbo]). */
    val retiredIds: List<Int>,
    /** (instanceId, zoneId) pairs to record (caller applies via [leyline.game.state.ZoneTracking.recordZone]). */
    val zoneRecordings: List<Pair<Int, Int>>,
    /** Triggered abilities that just appeared on the stack. */
    val stackAbilityAppearances: List<StackAbilityAppearance> = emptyList(),
    /** Triggered abilities that left the stack (resolved/fizzled). */
    val stackAbilityDisappearances: List<StackAbilityDisappearance> = emptyList(),
    /**
     * Planned id reallocations for zone-transferred cards. The enclosing
     * projection transition commits them after mapping succeeds.
     * Empty when no zone transfers occurred.
     */
    val idReallocations: List<InstanceIdRegistry.IdReallocation> = emptyList(),
    /** Degraded transfers with no matching ordered Forge zone operation. */
    val snapshotFallbacks: List<SnapshotTransferFallback> = emptyList(),
    /** Linked-face family objects exposed only for the transfer into a hidden zone. */
    val transientHiddenFamilyIds: Set<Int> = emptySet(),
)

internal data class ZoneTransferContext(
    val previousZones: Map<Int, Int>,
    val forgeIdLookup: (InstanceId) -> ForgeCardId?,
    val idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
    val idLookup: (ForgeCardId) -> InstanceId,
    val manaAbilityGrpIdResolver: (ForgeCardId) -> GrpId = { GrpId(0) },
    /** Resolve grpId for a source card's ForgeCardId (for stack ability resolution annotations). */
    val grpIdResolver: (ForgeCardId) -> GrpId = { GrpId(0) },
    /** True when [ForgeCardId] is currently foretold. */
    val isForetoldLookup: (ForgeCardId) -> Boolean = { false },
    val pendingSpellCastLookup: (ForgeCardId) -> GameEvent.SpellCast? = { null },
    val pendingSpellResolutionLookup: (ForgeCardId) -> GameEvent.SpellResolved? = { null },
    /** True when a Forge card with this id exists. */
    val forgeCardKnown: (ForgeCardId) -> Boolean = { true },
    val stackAbilityLookup: (Int) -> StackAbilitySourceFacts? = { null },
    val paradigmSourceIidLookup: (ForgeCardId) -> Int? = { null },
    /** Cut-scoped source zone for stack lifecycle events. */
    val sourceZoneLookup: (ForgeCardId) -> Int? = { null },
    val zoneMoves: List<ZoneMove> = emptyList(),
)

internal data class StackAbilitySourceFacts(
    val sourceCardId: ForgeCardId,
    val isActivatedAbility: Boolean,
    val deferAnnouncement: Boolean = false,
)

/**
 * Stage 1 of the annotation pipeline: detect zone transfers and realloc instanceIds.
 *
 * Pure functions — no shared mutable state.
 */
@Suppress("LargeClass")
object ZoneTransferDetector {
    private val log = LoggerFactory.getLogger(ZoneTransferDetector::class.java)

    /**
     * Detect zone transfers — pure overload.
     * Takes [ZoneTransferContext] instead of a bridge for independent testability.
     *
     * Returns a [TransferResult] with patched copies of objects/zones.
     * Does not mutate [gameObjects] or [zones]. Uses [idAllocator]
     * for ID allocation but defers tracking side effects (retireToLimbo, recordZone)
     * to the caller via the result.
     */
    @Suppress(
        "LongMethod",
        // Detection branches per Forge zone-transfer category (cast, resolve,
        // exile, foretell, designation-related, mana-pay, etc.). Each new
        // category adds an arm.
        "CyclomaticComplexMethod",
    )
    internal fun detectZoneTransfers(
        gameObjects: List<GameObjectInfo>,
        zones: List<ZoneInfo>,
        events: List<GameEvent>,
        context: ZoneTransferContext,
    ): TransferResult {
        val previousZones = context.previousZones
        val forgeIdLookup = context.forgeIdLookup
        val idAllocator = context.idAllocator
        val idLookup = context.idLookup
        val manaAbilityGrpIdResolver = context.manaAbilityGrpIdResolver
        val grpIdResolver = context.grpIdResolver
        val isForetoldLookup = context.isForetoldLookup
        val pendingSpellCastLookup = context.pendingSpellCastLookup
        val pendingSpellResolutionLookup = context.pendingSpellResolutionLookup
        val forgeCardKnown = context.forgeCardKnown
        val paradigmSourceIidLookup = context.paradigmSourceIidLookup
        val ledgerIntents = ZoneMoveLedger.fold(context.zoneMoves, events)
        val pendingLedgerIntents = ledgerIntents.toMutableList()
        val patchedObjects = gameObjects.toMutableList()
        val patchedZones = zones.toMutableList()
        val transfers = mutableListOf<AppliedTransfer>()
        val retiredIds = mutableListOf<Int>()
        val zoneRecordings = mutableListOf<Pair<Int, Int>>()
        val snapshotFallbacks = mutableListOf<SnapshotTransferFallback>()

        for (i in patchedObjects.indices) {
            val obj = patchedObjects[i]
            val prevZone = previousZones[obj.instanceId]
            if (prevZone != null && prevZone != obj.zoneId) {
                if (prevZone == ZoneIds.STACK && obj.zoneId == ZoneIds.SUPPRESSED) {
                    zoneRecordings.add(obj.instanceId to obj.zoneId)
                    continue
                }
                val forgeCardId =
                    forgeIdLookup(InstanceId(obj.instanceId))
                        ?: spellCastCardIdFor(obj.grpId, events, grpIdResolver)
                        ?: spellResolvedCardIdFor(obj.grpId, events, grpIdResolver)
                        ?: paradigmCopyCardIdFor(obj.instanceId, events)
                if (isCollapsedParadigmOriginal(obj, prevZone, forgeCardId, ledgerIntents)) {
                    val collapsedForgeCardId = forgeCardId ?: continue
                    addCollapsedParadigmOriginalTransfers(
                        obj = obj,
                        objectIndex = i,
                        prevZone = prevZone,
                        forgeCardId = collapsedForgeCardId,
                        events = events,
                        patchedObjects = patchedObjects,
                        patchedZones = patchedZones,
                        transfers = transfers,
                        retiredIds = retiredIds,
                        zoneRecordings = zoneRecordings,
                        idAllocator = idAllocator,
                        idLookup = idLookup,
                        manaAbilityGrpIdResolver = manaAbilityGrpIdResolver,
                    )
                    continue
                }
                val ledgerIntent =
                    forgeCardId?.let { id ->
                        pendingLedgerIntents.removeFirstOrNull { it.matches(id, prevZone, obj.zoneId) }
                    }
                val baseCategory =
                    if (obj.zoneId == ZoneIds.STACK && forgeCardId != null && pendingSpellCastLookup(forgeCardId) != null) {
                        TransferCategory.CastSpell
                    } else if (prevZone == ZoneIds.STACK && obj.zoneId != ZoneIds.EXILE && forgeCardId != null) {
                        val pendingResolution = pendingSpellResolutionLookup(forgeCardId)
                        when {
                            pendingResolution?.hasFizzled == true -> TransferCategory.Countered
                            pendingResolution != null -> TransferCategory.Resolve
                            else ->
                                categoryForTransfer(
                                    obj,
                                    prevZone,
                                    obj.zoneId,
                                    forgeCardId,
                                    events,
                                    ledgerIntent,
                                )
                        }
                    } else {
                        categoryForTransfer(
                            obj,
                            prevZone,
                            obj.zoneId,
                            forgeCardId,
                            events,
                            ledgerIntent,
                        )
                    }
                // Foretell override: a Hand→Exile transfer where the destination card
                // is foretold (Card.foretold==true && Card.isInZone(Exile)) is the
                // foretell action, not a generic Exile. Forge fires no card-specific
                // event for this — we detect it via the post-transfer card state.
                val isHandToExile =
                    (prevZone == ZoneIds.P1_HAND || prevZone == ZoneIds.P2_HAND) &&
                        obj.zoneId == ZoneIds.EXILE
                val category =
                    if (isHandToExile && forgeCardId != null && isForetoldLookup(forgeCardId)) {
                        TransferCategory.Foretell
                    } else {
                        baseCategory
                    }
                if (ledgerIntent?.origin != TransferPlanOrigin.Event) {
                    snapshotFallbacks.add(SnapshotTransferFallback(forgeCardId, prevZone, obj.zoneId))
                    log.debug(
                        "snapshot transfer fallback card={} {}->{}",
                        forgeCardId,
                        prevZone,
                        obj.zoneId,
                    )
                }
                // Allocate new instanceId for zone transfer (protocol requires this).
                // Exception: Resolve (Stack→Battlefield) keeps the same instanceId.
                val handoff =
                    if (!keepsSameInstanceId(category, obj.zoneId) && forgeCardId != null) {
                        ZoneHandoff.fromRealloc(idAllocator(forgeCardId), obj.zoneId)
                    } else {
                        ZoneHandoff.keepingSameInstanceId(InstanceId(obj.instanceId), obj.zoneId)
                    }
                val origId = handoff.realloc.old.value
                val newId = handoff.realloc.new.value
                log.debug("zone transfer: iid {} → {} category={}", origId, newId, category)
                applyHandoffToPatchSet(handoff, patchedObjects, i, patchedZones, obj.zoneId, retiredIds)
                // Resolve affectorId: the ability instance that caused this transfer.
                // For surveil (and future mechanics), the source card's ability on the
                // stack has instanceId allocated against the SA-id-keyed surrogate
                // — see [FrameIdResolver.triggerStackAbilityForgeId]. Falls back to
                // source-card-keyed when no in-window SpellCast carries the SA id.
                val affectorId =
                    if (category == TransferCategory.Warp) {
                        origId
                    } else if (ledgerIntent?.sourceCardId != null) {
                        val sourceCardId = ledgerIntent.sourceCardId
                        val intentAbilityIds =
                            setOf(
                                ledgerIntent.sourceAbilityForgeId,
                                ledgerIntent.rootAbilityForgeId,
                                ledgerIntent.stackAbilityForgeId,
                            ) - 0
                        val lifecycleAbilities =
                            events.mapNotNull { event ->
                                when (event) {
                                    is GameEvent.SpellCast ->
                                        event
                                            .takeIf {
                                                event.cardId == sourceCardId && (event.isAbility || event.isTrigger)
                                            }?.let {
                                                Triple(it.abilityForgeId, it.rootAbilityForgeId, it.stackAbilityForgeId)
                                            }

                                    is GameEvent.SpellResolved ->
                                        event
                                            .takeIf {
                                                event.cardId == sourceCardId && (event.isAbility || event.isTrigger)
                                            }?.let {
                                                Triple(it.abilityForgeId, it.rootAbilityForgeId, it.stackAbilityForgeId)
                                            }

                                    else -> null
                                }
                            }
                        val stackAbilityForgeId =
                            lifecycleAbilities
                                .firstOrNull { (abilityId, rootId, stackId) ->
                                    intentAbilityIds.any { it == abilityId || it == rootId || it == stackId }
                                }?.first
                        val sourceIdentity =
                            if (stackAbilityForgeId != null) {
                                FrameIdResolver.triggerStackAbilityForgeId(stackAbilityForgeId)
                            } else {
                                sourceCardId
                            }
                        idLookup(sourceIdentity).value
                    } else if (forgeCardId != null && events.isNotEmpty()) {
                        val sourceCardId = TransferCategoryResolver.affectorSourceFromEvents(forgeCardId, events)
                        if (sourceCardId != null) {
                            val abilityForgeId =
                                events
                                    .filterIsInstance<GameEvent.SpellCast>()
                                    .firstOrNull { it.cardId == sourceCardId && it.abilityForgeId != 0 }
                                    ?.abilityForgeId
                            val surrogate =
                                if (abilityForgeId != null) {
                                    FrameIdResolver.triggerStackAbilityForgeId(abilityForgeId)
                                } else {
                                    FrameIdResolver.stackAbilityForgeId(sourceCardId)
                                }
                            idLookup(surrogate).value
                        } else {
                            0
                        }
                    } else {
                        0
                    }

                // Extract color ordinals from LandPlayed event for ColorProduction annotation.
                val colorOrdinals =
                    if (category == TransferCategory.PlayLand && forgeCardId != null) {
                        events
                            .filterIsInstance<GameEvent.LandPlayed>()
                            .firstOrNull { it.cardId == forgeCardId }
                            ?.colorOrdinals ?: emptyList()
                    } else {
                        emptyList()
                    }
                val isMdfcLandPlay =
                    category == TransferCategory.PlayLand &&
                        forgeCardId != null &&
                        events
                            .filterIsInstance<GameEvent.LandPlayed>()
                            .firstOrNull { it.cardId == forgeCardId }
                            ?.isMdfc == true

                // Extract mana payment info + adventure flag + alt-cost info from
                // SpellCast events.
                val spellCastEvent =
                    if (category == TransferCategory.CastSpell && forgeCardId != null) {
                        events
                            .filterIsInstance<GameEvent.SpellCast>()
                            .firstOrNull { it.cardId == forgeCardId }
                            ?: pendingSpellCastLookup(forgeCardId)
                    } else {
                        null
                    }
                val transferGrpId =
                    spellCastEvent
                        ?.spellGrpId
                        ?.takeIf { category == TransferCategory.CastSpell && it != 0 }
                        ?: obj.grpId
                if (transferGrpId != obj.grpId) {
                    patchedObjects[i] = patchedObjects[i].toBuilder().setGrpId(transferGrpId).build()
                }
                val manaPayments =
                    spellCastEvent?.manaPayments?.map { mp ->
                        val landIid = idLookup(mp.sourceCardId).value
                        val manaAbilityIid = idLookup(FrameIdResolver.manaAbilityForgeId(mp.sourceCardId)).value
                        ManaPaymentRecord(
                            landInstanceId = landIid,
                            manaAbilityInstanceId = manaAbilityIid,
                            color = mp.color,
                            abilityGrpId = MechanicSourceProjection.paymentAbilityGrpId(mp, manaAbilityGrpIdResolver).value,
                            spellInstanceId = newId,
                        )
                    } ?: emptyList()
                val isAdventureCast = spellCastEvent?.isAdventure == true
                val isOmenCast = spellCastEvent?.isOmen == true
                val altCostAbilityGrpId = spellCastEvent?.altCostAbilityGrpId ?: 0
                val castAbilityGrpId = spellCastEvent?.castAbilityGrpId ?: altCostAbilityGrpId
                val kickerAbilityGrpId = spellCastEvent?.kickerAbilityGrpId ?: 0
                val additionalCostGrpId = spellCastEvent?.additionalCostGrpId ?: 0
                val chosenCostPromptId = spellCastEvent?.chosenCostPromptId ?: 0
                val chosenX = spellCastEvent?.chosenX ?: 0
                val transferAffectorId =
                    if (category == TransferCategory.CastSpell && spellCastEvent?.isParadigmCopyCastEvent() == true) {
                        paradigmDelayedTriggerIid(events, idLookup).takeIf { it != 0 } ?: paradigmDelayedTriggerIid(patchedObjects)
                    } else {
                        affectorId
                    }
                val openingHandAction =
                    forgeCardId?.let { id ->
                        events.filterIsInstance<GameEvent.OpeningHandAction>().firstOrNull { it.cardId == id }
                    }
                val openingHandAbilityInstanceId =
                    openingHandAction
                        ?.let { idLookup(FrameIdResolver.triggerStackAbilityForgeId(it.abilityForgeId)).value }
                        ?: 0

                transfers.add(
                    AppliedTransfer(
                        origId = origId,
                        newId = newId,
                        category = category,
                        srcZoneId = prevZone,
                        destZoneId = obj.zoneId,
                        forgeCardId = forgeCardId,
                        grpId = transferGrpId,
                        ownerSeatId = obj.ownerSeatId,
                        affectorId = openingHandAbilityInstanceId.takeIf { it != 0 } ?: transferAffectorId,
                        colorOrdinals = colorOrdinals,
                        isMdfcLandPlay = isMdfcLandPlay,
                        manaPayments = manaPayments,
                        isAdventureCast = isAdventureCast,
                        isOmenCast = isOmenCast,
                        altCostAbilityGrpId = altCostAbilityGrpId,
                        castAbilityGrpId = castAbilityGrpId,
                        kickerAbilityGrpId = kickerAbilityGrpId,
                        additionalCostGrpId = additionalCostGrpId,
                        chosenCostPromptId = chosenCostPromptId,
                        chosenX = chosenX,
                        openingHandAbilityInstanceId = openingHandAbilityInstanceId,
                        openingHandAbilityGrpId = openingHandAction?.abilityGrpId ?: 0,
                        openingHandSeatId = openingHandAction?.seatId?.value ?: 0,
                    ),
                )
                zoneRecordings.add(newId to obj.zoneId)
            } else {
                zoneRecordings.add(obj.instanceId to obj.zoneId)
            }
        }

        // Post-pass: detect token sacrifices invisible to the main loop.
        detectDisappearedSacrifices(
            events,
            previousZones,
            patchedObjects,
            patchedZones,
            transfers,
            retiredIds,
            zoneRecordings,
            forgeIdLookup,
            idAllocator,
            idLookup,
            manaAbilityGrpIdResolver,
        )

        // Post-pass: detect exile-return transforms (saga final chapter,
        // Fable of the Mirror-Breaker). Forge fires paired ChangeZone events
        // (BF→Exile, Exile→BF) during an atomic resolve. Net snapshot shows
        // the card still on BF so the main diff loop skips it, but the client
        // protocol requires the two zone-hop annotations.
        detectExileReturnRoundTrips(
            context.zoneMoves,
            transfers,
            patchedObjects,
            patchedZones,
            retiredIds,
            idAllocator,
            idLookup,
        )

        val gameObjectIds = patchedObjects.map { it.instanceId }.toSet()

        // Post-pass: detect transfers into hidden zones. Library / hidden-hand
        // cards can appear only in ZoneInfo.objectInstanceIds, with no
        // GameObjectInfo for the main loop to inspect.
        detectZoneOnlyTransfers(
            events,
            ledgerIntents,
            pendingLedgerIntents,
            previousZones,
            gameObjectIds,
            patchedZones,
            transfers,
            retiredIds,
            zoneRecordings,
            snapshotFallbacks,
            forgeIdLookup,
            idAllocator,
            grpIdResolver,
            pendingSpellResolutionLookup,
        )

        // Post-pass: detect triggered ability lifecycle on the stack.
        val mainLoopIds = transfers.map { it.origId }.toSet()
        val appearances =
            detectStackAbilityAppearances(
                patchedObjects,
                previousZones,
                mainLoopIds,
                forgeIdLookup,
                idLookup,
                events,
                forgeCardKnown,
                context.stackAbilityLookup,
                paradigmSourceIidLookup,
                context.sourceZoneLookup,
            )
        val (disappearances, disappearedRetiredIds) =
            detectStackAbilityDisappearances(
                events,
                previousZones,
                gameObjectIds,
                mainLoopIds,
                forgeIdLookup,
                idLookup,
                grpIdResolver,
                forgeCardKnown,
                context.stackAbilityLookup,
            )
        // Retire disappeared ability instanceIds to Limbo so annotation
        // references (affectedIds) remain resolvable by the validating sink.
        for (id in disappearedRetiredIds) {
            retiredIds.add(id)
            appendToZone(patchedZones, ZoneIds.LIMBO, id)
        }

        detectEventOnlyParadigmCopyCasts(patchedObjects, previousZones, events, forgeIdLookup, idLookup).forEach { transfer ->
            transfers.add(transfer)
            zoneRecordings.add(transfer.newId to transfer.destZoneId)
        }
        detectCollapsedParadigmCopyTransfers(
            events = events,
            previousZones = previousZones,
            patchedObjects = patchedObjects,
            transfers = transfers,
            patchedZones = patchedZones,
            retiredIds = retiredIds,
            zoneRecordings = zoneRecordings,
            idAllocator = idAllocator,
            idLookup = idLookup,
            grpIdResolver = grpIdResolver,
        )

        // Record zones for instanceIds that appear only in zone objectInstanceIds
        // but not in gameObjects (e.g. library cards — hidden, no GameObjectInfo).
        for (zone in patchedZones) {
            for (iid in zone.objectInstanceIdsList) {
                val zonePair = iid to zone.zoneId
                if (iid !in gameObjectIds && zonePair !in zoneRecordings) {
                    zoneRecordings.add(zonePair)
                }
            }
        }

        return TransferResult(
            transfers,
            patchedObjects,
            patchedZones,
            retiredIds,
            zoneRecordings,
            stackAbilityAppearances = appearances,
            stackAbilityDisappearances = disappearances,
            snapshotFallbacks = snapshotFallbacks,
        )
    }

    private fun detectEventOnlyParadigmCopyCasts(
        objects: List<GameObjectInfo>,
        previousZones: Map<Int, Int>,
        events: List<GameEvent>,
        forgeIdLookup: (InstanceId) -> ForgeCardId?,
        idLookup: (ForgeCardId) -> InstanceId,
    ): List<AppliedTransfer> {
        val affectorId = paradigmDelayedTriggerIid(events, idLookup).takeIf { it != 0 } ?: paradigmDelayedTriggerIid(objects)
        val copyCastIds =
            events
                .filterIsInstance<GameEvent.SpellCast>()
                .filter {
                    !it.isAbility &&
                        !it.isTrigger &&
                        it.altCostAbilityGrpId == 149 &&
                        it.castAbilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER
                }.map { it.cardId }
                .toSet()
        val copyCastsWithStackIids =
            events
                .filterIsInstance<GameEvent.SpellCast>()
                .filter { it.isParadigmCopyCastEvent() && it.stackInstanceId != 0 }
                .map { it.cardId }
                .toSet()
        return objects.mapNotNull { obj ->
            if (obj.zoneId != ZoneIds.STACK) return@mapNotNull null
            if (previousZones.containsKey(obj.instanceId)) return@mapNotNull null
            val forgeCardId = forgeIdLookup(InstanceId(obj.instanceId)) ?: return@mapNotNull null
            if (forgeCardId in copyCastsWithStackIids) return@mapNotNull null
            val isParadigmStackCopy = obj.isCopy && obj.hasParadigmAbility()
            if (forgeCardId !in copyCastIds && !isParadigmStackCopy) return@mapNotNull null
            AppliedTransfer(
                origId = obj.instanceId,
                newId = obj.instanceId,
                category = TransferCategory.CastSpell,
                srcZoneId = ZoneIds.EXILE,
                destZoneId = ZoneIds.STACK,
                forgeCardId = forgeCardId,
                grpId = obj.grpId,
                ownerSeatId = obj.ownerSeatId,
                affectorId = affectorId,
                altCostAbilityGrpId = 149,
                castAbilityGrpId = KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun detectCollapsedParadigmCopyTransfers(
        events: List<GameEvent>,
        previousZones: Map<Int, Int>,
        patchedObjects: MutableList<GameObjectInfo>,
        transfers: MutableList<AppliedTransfer>,
        patchedZones: MutableList<ZoneInfo>,
        retiredIds: MutableList<Int>,
        zoneRecordings: MutableList<Pair<Int, Int>>,
        idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
        idLookup: (ForgeCardId) -> InstanceId,
        grpIdResolver: (ForgeCardId) -> GrpId,
    ) {
        val affectorId = paradigmDelayedTriggerIid(events, idLookup).takeIf { it != 0 } ?: paradigmDelayedTriggerIid(patchedObjects)
        val resolvedCopyIds =
            events
                .filterIsInstance<GameEvent.SpellResolved>()
                .filter { it.isParadigmCopy && !it.hasFizzled && it.stackInstanceId != 0 }
                .associateBy { it.cardId }
        val stackIdsByCard = mutableMapOf<ForgeCardId, Int>()
        for (cast in events.filterIsInstance<GameEvent.SpellCast>().filter { it.isParadigmCopyCastEvent() }) {
            val currentId = cast.stackInstanceId
            if (currentId == 0) continue
            if (previousZones[currentId] == ZoneIds.STACK) continue
            val existingCastIndex = transfers.indexOfFirst { it.forgeCardId == cast.cardId && it.category == TransferCategory.CastSpell }
            if (existingCastIndex >= 0) {
                val existingCast = transfers[existingCastIndex]
                stackIdsByCard[cast.cardId] = existingCast.newId
                transfers[existingCastIndex] =
                    existingCast.copy(
                        affectorId = existingCast.affectorId.takeIf { it != 0 } ?: affectorId,
                        altCostAbilityGrpId = existingCast.altCostAbilityGrpId.takeIf { it != 0 } ?: 149,
                        castAbilityGrpId =
                            existingCast.castAbilityGrpId
                                .takeIf { it != 0 }
                                ?: KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER,
                    )
                continue
            }
            val handoff = ZoneHandoff.fromRealloc(paradigmCopyStackRealloc(cast.cardId, currentId, idAllocator), ZoneIds.STACK)
            val exileId = handoff.realloc.old.value
            val stackId = handoff.realloc.new.value
            stackIdsByCard[cast.cardId] = stackId
            retiredIds.add(exileId)
            appendToZone(patchedZones, ZoneIds.LIMBO, exileId)
            patchedObjects.indexOfFirst { it.instanceId == currentId }.takeIf { it >= 0 }?.let { index ->
                patchedObjects[index] = patchedObjects[index].toBuilder().setInstanceId(stackId).build()
            }
            patchZoneInstanceId(patchedZones, ZoneIds.STACK, currentId, stackId)
            transfers.add(
                AppliedTransfer(
                    origId = exileId,
                    newId = stackId,
                    category = TransferCategory.CastSpell,
                    srcZoneId = ZoneIds.EXILE,
                    destZoneId = ZoneIds.STACK,
                    forgeCardId = cast.cardId,
                    grpId = grpIdResolver(cast.cardId).value,
                    ownerSeatId = cast.seatId.value,
                    affectorId = affectorId,
                    altCostAbilityGrpId = 149,
                    castAbilityGrpId = KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER,
                ),
            )
            if (cast.cardId !in resolvedCopyIds) zoneRecordings.add(stackId to ZoneIds.STACK)
        }

        for (resolved in resolvedCopyIds.values) {
            val stackId = stackIdsByCard[resolved.cardId] ?: resolved.stackInstanceId
            if (transfers.any { it.origId == stackId && it.srcZoneId == ZoneIds.STACK && it.destZoneId == ZoneIds.EXILE }) {
                continue
            }
            val handoff = ZoneHandoff.fromRealloc(idAllocator(resolved.cardId), ZoneIds.EXILE)
            val exileId = handoff.realloc.new.value
            transfers.add(
                AppliedTransfer(
                    origId = stackId,
                    newId = exileId,
                    category = TransferCategory.Exile,
                    srcZoneId = ZoneIds.STACK,
                    destZoneId = ZoneIds.EXILE,
                    forgeCardId = resolved.cardId,
                    grpId = grpIdResolver(resolved.cardId).value,
                    ownerSeatId = 0,
                ),
            )
            retiredIds.add(stackId)
            appendToZone(patchedZones, ZoneIds.LIMBO, stackId)
            appendToZone(patchedZones, ZoneIds.EXILE, exileId)
            zoneRecordings.add(exileId to ZoneIds.EXILE)
        }
    }

    private fun GameEvent.SpellCast.isParadigmCopyCastEvent(): Boolean =
        !isAbility &&
            !isTrigger &&
            altCostAbilityGrpId == 149 &&
            castAbilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER

    private fun paradigmCopyStackRealloc(
        cardId: ForgeCardId,
        currentStackId: Int,
        idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
    ): InstanceIdRegistry.IdReallocation {
        val first = idAllocator(cardId)
        if (first.old.value != first.new.value || first.new.value != currentStackId) return first

        val second = idAllocator(cardId)
        return InstanceIdRegistry.IdReallocation(InstanceId(currentStackId), second.new)
    }

    private fun paradigmCopyCardIdFor(
        instanceId: Int,
        events: List<GameEvent>,
    ): ForgeCardId? =
        events
            .filterIsInstance<GameEvent.SpellCast>()
            .firstOrNull { it.stackInstanceId == instanceId && it.isParadigmCopyCastEvent() }
            ?.cardId

    private fun paradigmDelayedTriggerIid(
        events: List<GameEvent>,
        idLookup: (ForgeCardId) -> InstanceId,
    ): Int {
        val abilityForgeId =
            events
                .filterIsInstance<GameEvent.SpellCast>()
                .firstOrNull { it.isTrigger && it.abilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER }
                ?.abilityForgeId
                ?: events
                    .filterIsInstance<GameEvent.SpellResolved>()
                    .firstOrNull { it.isTrigger && it.abilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER }
                    ?.abilityForgeId
        return abilityForgeId
            ?.takeIf { it != 0 }
            ?.let { idLookup(FrameIdResolver.triggerStackAbilityForgeId(it)).value }
            ?: 0
    }

    private fun paradigmDelayedTriggerIid(objects: List<GameObjectInfo>): Int =
        objects
            .firstOrNull {
                it.zoneId == ZoneIds.STACK &&
                    it.type == GameObjectType.Ability &&
                    it.grpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER
            }?.instanceId ?: 0

    /** Infer category for a zone transfer annotation from zone IDs. */
    @Suppress("CyclomaticComplexMethod", "UnusedParameter")
    fun inferCategory(
        obj: GameObjectInfo,
        srcZone: Int,
        destZone: Int,
    ): TransferCategory =
        when {
            srcZone == ZoneIds.P1_HAND || srcZone == ZoneIds.P2_HAND ->
                when (destZone) {
                    ZoneIds.STACK -> TransferCategory.CastSpell
                    ZoneIds.BATTLEFIELD -> TransferCategory.PlayLand
                    else -> TransferCategory.ZoneTransfer
                }
            srcZone == ZoneIds.STACK && destZone == ZoneIds.BATTLEFIELD -> TransferCategory.Resolve
            srcZone == ZoneIds.BATTLEFIELD ->
                when (destZone) {
                    ZoneIds.P1_GRAVEYARD, ZoneIds.P2_GRAVEYARD -> TransferCategory.Destroy
                    ZoneIds.EXILE -> TransferCategory.Exile
                    else -> TransferCategory.ZoneTransfer
                }
            srcZone == ZoneIds.P1_LIBRARY || srcZone == ZoneIds.P2_LIBRARY ->
                when (destZone) {
                    ZoneIds.BATTLEFIELD -> TransferCategory.Search
                    else -> TransferCategory.ZoneTransfer
                }
            srcZone == ZoneIds.P1_SIDEBOARD || srcZone == ZoneIds.P2_SIDEBOARD ->
                when (destZone) {
                    ZoneIds.P1_HAND, ZoneIds.P2_HAND -> TransferCategory.Put
                    else -> TransferCategory.ZoneTransfer
                }
            srcZone == ZoneIds.P1_GRAVEYARD || srcZone == ZoneIds.P2_GRAVEYARD ->
                when (destZone) {
                    ZoneIds.P1_HAND, ZoneIds.P2_HAND, ZoneIds.BATTLEFIELD -> TransferCategory.Return
                    ZoneIds.EXILE -> TransferCategory.Exile
                    else -> TransferCategory.ZoneTransfer
                }
            srcZone == ZoneIds.EXILE ->
                when (destZone) {
                    ZoneIds.P1_HAND, ZoneIds.P2_HAND, ZoneIds.BATTLEFIELD -> TransferCategory.Return
                    ZoneIds.STACK -> TransferCategory.CastSpell
                    else -> TransferCategory.ZoneTransfer
                }
            else -> TransferCategory.ZoneTransfer
        }

    private fun categoryForTransfer(
        obj: GameObjectInfo,
        srcZone: Int,
        destZone: Int,
        forgeCardId: ForgeCardId?,
        events: List<GameEvent>,
        ledgerIntent: ZoneMoveIntent?,
    ): TransferCategory {
        if (srcZone == ZoneIds.STACK && destZone == ZoneIds.EXILE && isParadigmStackSpell(obj, forgeCardId, events)) {
            return TransferCategory.Exile
        }
        val eventCategory = ledgerIntent?.takeIf { it.origin == TransferPlanOrigin.Event }?.category
        return eventCategory ?: fallbackCategory(forgeCardId, events, obj, srcZone, destZone)
    }

    private fun isParadigmStackSpell(
        obj: GameObjectInfo,
        forgeCardId: ForgeCardId?,
        events: List<GameEvent>,
    ): Boolean =
        obj.hasParadigmAbility() ||
            events.any { it is GameEvent.SpellResolved && it.cardId == forgeCardId && it.isParadigmCopy }

    private fun GameObjectInfo.hasParadigmAbility(): Boolean = uniqueAbilitiesList.any { it.grpId == KeywordAbilityIds.PARADIGM }

    private fun spellCastCardIdFor(
        grpId: Int,
        events: List<GameEvent>,
        grpIdResolver: (ForgeCardId) -> GrpId,
    ): ForgeCardId? =
        events
            .filterIsInstance<GameEvent.SpellCast>()
            .firstOrNull { grpIdResolver(it.cardId).value == grpId }
            ?.cardId

    private fun spellResolvedCardIdFor(
        grpId: Int,
        events: List<GameEvent>,
        grpIdResolver: (ForgeCardId) -> GrpId,
    ): ForgeCardId? =
        events
            .filterIsInstance<GameEvent.SpellResolved>()
            .firstOrNull { grpIdResolver(it.cardId).value == grpId }
            ?.cardId

    private fun fallbackCategory(
        forgeCardId: ForgeCardId?,
        events: List<GameEvent>,
        obj: GameObjectInfo,
        srcZone: Int,
        destZone: Int,
    ): TransferCategory {
        val inferred = inferCategory(obj, srcZone, destZone)
        val eventCategory = forgeCardId?.takeIf { events.isNotEmpty() }?.let { TransferCategoryResolver.categoryFromEvents(it, events) }
        return if (
            eventCategory != null &&
            eventCategory != inferred &&
            eventCategory in CAST_OR_RESOLVE &&
            inferred in CAST_OR_RESOLVE
        ) {
            inferred
        } else {
            eventCategory ?: inferred
        }
    }

    private val CAST_OR_RESOLVE = setOf(TransferCategory.CastSpell, TransferCategory.Resolve)

    private fun isCollapsedParadigmOriginal(
        obj: GameObjectInfo,
        prevZone: Int,
        forgeCardId: ForgeCardId?,
        ledgerIntents: List<ZoneMoveIntent>,
    ): Boolean {
        val handToExile = (prevZone == ZoneIds.P1_HAND || prevZone == ZoneIds.P2_HAND) && obj.zoneId == ZoneIds.EXILE
        if (!handToExile || forgeCardId == null || !obj.hasParadigmAbility()) return false
        val cardMoves = ledgerIntents.filter { it.move.cardId == forgeCardId }
        return cardMoves.any { it.move.from == Zone.Hand && it.move.to == Zone.Stack && it.category == TransferCategory.CastSpell } &&
            cardMoves.any { it.move.from == Zone.Stack && it.move.to == Zone.Exile && it.category == TransferCategory.Exile }
    }

    @Suppress("LongParameterList")
    private fun addCollapsedParadigmOriginalTransfers(
        obj: GameObjectInfo,
        objectIndex: Int,
        prevZone: Int,
        forgeCardId: ForgeCardId,
        events: List<GameEvent>,
        patchedObjects: MutableList<GameObjectInfo>,
        patchedZones: MutableList<ZoneInfo>,
        transfers: MutableList<AppliedTransfer>,
        retiredIds: MutableList<Int>,
        zoneRecordings: MutableList<Pair<Int, Int>>,
        idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
        idLookup: (ForgeCardId) -> InstanceId,
        manaAbilityGrpIdResolver: (ForgeCardId) -> GrpId,
    ) {
        val stackHandoff = ZoneHandoff.fromRealloc(idAllocator(forgeCardId), ZoneIds.STACK)
        val exileHandoff = ZoneHandoff.fromRealloc(idAllocator(forgeCardId), ZoneIds.EXILE)
        val handId = stackHandoff.realloc.old.value
        val stackId = stackHandoff.realloc.new.value
        val exileId = exileHandoff.realloc.new.value
        val spellCastEvent = events.filterIsInstance<GameEvent.SpellCast>().firstOrNull { it.cardId == forgeCardId }
        val manaPayments =
            spellCastEvent?.manaPayments?.map { mp ->
                ManaPaymentRecord(
                    landInstanceId = idLookup(mp.sourceCardId).value,
                    manaAbilityInstanceId = idLookup(FrameIdResolver.manaAbilityForgeId(mp.sourceCardId)).value,
                    color = mp.color,
                    abilityGrpId = MechanicSourceProjection.paymentAbilityGrpId(mp, manaAbilityGrpIdResolver).value,
                    spellInstanceId = stackId,
                )
            } ?: emptyList()

        patchedObjects[objectIndex] = obj.toBuilder().setInstanceId(exileId).build()
        patchZoneInstanceId(patchedZones, ZoneIds.EXILE, obj.instanceId, exileId)
        retiredIds.add(handId)
        appendToZone(patchedZones, ZoneIds.LIMBO, handId)
        retiredIds.add(stackId)
        appendToZone(patchedZones, ZoneIds.LIMBO, stackId)
        transfers.add(
            AppliedTransfer(
                origId = handId,
                newId = stackId,
                category = TransferCategory.CastSpell,
                srcZoneId = prevZone,
                destZoneId = ZoneIds.STACK,
                forgeCardId = forgeCardId,
                grpId = obj.grpId,
                ownerSeatId = obj.ownerSeatId,
                manaPayments = manaPayments,
            ),
        )
        transfers.add(
            AppliedTransfer(
                origId = stackId,
                newId = exileId,
                category = TransferCategory.Exile,
                srcZoneId = ZoneIds.STACK,
                destZoneId = ZoneIds.EXILE,
                forgeCardId = forgeCardId,
                grpId = obj.grpId,
                ownerSeatId = obj.ownerSeatId,
            ),
        )
        zoneRecordings.add(exileId to ZoneIds.EXILE)
    }

    @Suppress("LongMethod", "LongParameterList")
    private fun detectZoneOnlyTransfers(
        events: List<GameEvent>,
        ledgerIntents: List<ZoneMoveIntent>,
        pendingLedgerIntents: MutableList<ZoneMoveIntent>,
        previousZones: Map<Int, Int>,
        gameObjectIds: Set<Int>,
        patchedZones: MutableList<ZoneInfo>,
        transfers: MutableList<AppliedTransfer>,
        retiredIds: MutableList<Int>,
        zoneRecordings: MutableList<Pair<Int, Int>>,
        snapshotFallbacks: MutableList<SnapshotTransferFallback>,
        forgeIdLookup: (InstanceId) -> ForgeCardId?,
        idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
        grpIdResolver: (ForgeCardId) -> GrpId,
        pendingSpellResolutionLookup: (ForgeCardId) -> GameEvent.SpellResolved?,
    ) {
        val currentZoneById =
            patchedZones
                .asSequence()
                .flatMap { zone -> zone.objectInstanceIdsList.asSequence().map { iid -> iid to zone.zoneId } }
                .toMap()

        for ((iid, destZone) in currentZoneById) {
            if (!isZoneOnlyTransferCandidate(iid, destZone, gameObjectIds, transfers)) continue
            val prevZone = previousZones[iid] ?: continue
            if (prevZone == destZone) continue

            val forgeCardId = forgeIdLookup(InstanceId(iid)) ?: continue
            val ownerSeatId = ownerSeatIdForZone(destZone) ?: ownerSeatIdForZone(prevZone) ?: 0
            if (isCollapsedCastResolveToLibrary(ledgerIntents, forgeCardId, prevZone, destZone)) {
                // The final hidden snapshot collapses Hand->Stack->Library.
                // Ordered Forge moves remain authoritative for both legs.
                val castHandoff = ZoneHandoff.fromRealloc(idAllocator(forgeCardId), ZoneIds.STACK)
                val resolveHandoff = ZoneHandoff.fromRealloc(idAllocator(forgeCardId), destZone)
                val handId = castHandoff.realloc.old.value
                val stackId = castHandoff.realloc.new.value
                val libraryId = resolveHandoff.realloc.new.value
                val pendingResolution = pendingSpellResolutionLookup(forgeCardId)
                val grpId = pendingResolution?.spellGrpId?.takeIf { it != 0 } ?: grpIdResolver(forgeCardId).value

                patchZoneInstanceId(patchedZones, destZone, iid, libraryId)
                retiredIds.add(handId)
                appendToZone(patchedZones, ZoneIds.LIMBO, handId)
                retiredIds.add(stackId)
                appendToZone(patchedZones, ZoneIds.LIMBO, stackId)
                transfers.add(
                    AppliedTransfer(
                        origId = handId,
                        newId = stackId,
                        category = TransferCategory.CastSpell,
                        srcZoneId = prevZone,
                        destZoneId = ZoneIds.STACK,
                        forgeCardId = forgeCardId,
                        grpId = grpId,
                        ownerSeatId = ownerSeatId,
                    ),
                )
                transfers.add(
                    AppliedTransfer(
                        origId = stackId,
                        newId = libraryId,
                        category = TransferCategory.Resolve,
                        srcZoneId = ZoneIds.STACK,
                        destZoneId = destZone,
                        forgeCardId = forgeCardId,
                        grpId = grpId,
                        ownerSeatId = ownerSeatId,
                    ),
                )
                zoneRecordings.add(libraryId to destZone)
                log.debug(
                    "zone-only cast-resolve transfer: iid {} -> stack {} -> library {}",
                    handId,
                    stackId,
                    libraryId,
                )
                continue
            }
            val ledgerIntent = pendingLedgerIntents.removeFirstOrNull { it.matches(forgeCardId, prevZone, destZone) }
            val pendingResolution =
                pendingSpellResolutionLookup(forgeCardId)
                    ?.takeIf { prevZone == ZoneIds.STACK }
            val category = zoneOnlyTransferCategory(prevZone, destZone, forgeCardId, events, ledgerIntent, pendingResolution)
            if (ledgerIntent?.origin != TransferPlanOrigin.Event) {
                snapshotFallbacks.add(SnapshotTransferFallback(forgeCardId, prevZone, destZone))
                log.debug("snapshot transfer fallback card={} {}->{}", forgeCardId, prevZone, destZone)
            }
            val spellCastEvent =
                if (category == TransferCategory.CastSpell) {
                    events
                        .filterIsInstance<GameEvent.SpellCast>()
                        .firstOrNull { it.cardId == forgeCardId }
                } else {
                    null
                }
            val handoff =
                if (!keepsSameInstanceId(category, destZone)) {
                    ZoneHandoff.fromRealloc(idAllocator(forgeCardId), destZone)
                } else {
                    ZoneHandoff.keepingSameInstanceId(InstanceId(iid), destZone)
                }
            val origId = handoff.realloc.old.value
            val newId = handoff.realloc.new.value

            applyHiddenHandoffToZoneSet(handoff, patchedZones, destZone, retiredIds)
            transfers.add(
                AppliedTransfer(
                    origId = origId,
                    newId = newId,
                    category = category,
                    srcZoneId = prevZone,
                    destZoneId = destZone,
                    forgeCardId = forgeCardId,
                    grpId = pendingResolution?.spellGrpId?.takeIf { it != 0 } ?: grpIdResolver(forgeCardId).value,
                    ownerSeatId = ownerSeatId,
                    isAdventureCast = spellCastEvent?.isAdventure == true,
                    altCostAbilityGrpId = spellCastEvent?.altCostAbilityGrpId ?: 0,
                    kickerAbilityGrpId = spellCastEvent?.kickerAbilityGrpId ?: 0,
                    additionalCostGrpId = spellCastEvent?.additionalCostGrpId ?: 0,
                    chosenCostPromptId = spellCastEvent?.chosenCostPromptId ?: 0,
                    chosenX = spellCastEvent?.chosenX ?: 0,
                ),
            )
            zoneRecordings.add(newId to destZone)
            log.debug("zone-only transfer: iid {} -> {} category={}", origId, newId, category)
        }
    }

    private fun isZoneOnlyTransferCandidate(
        iid: Int,
        destZone: Int,
        gameObjectIds: Set<Int>,
        transfers: List<AppliedTransfer>,
    ): Boolean = iid !in gameObjectIds && destZone != ZoneIds.LIMBO && transfers.none { it.origId == iid || it.newId == iid }

    private fun keepsSameInstanceId(
        category: TransferCategory,
        destinationZoneId: Int,
    ): Boolean = category == TransferCategory.Resolve && destinationZoneId == ZoneIds.BATTLEFIELD

    private fun isCollapsedCastResolveToLibrary(
        ledgerIntents: List<ZoneMoveIntent>,
        forgeCardId: ForgeCardId,
        prevZone: Int,
        destZone: Int,
    ): Boolean {
        val handToLibrary =
            (prevZone == ZoneIds.P1_HAND || prevZone == ZoneIds.P2_HAND) &&
                (destZone == ZoneIds.P1_LIBRARY || destZone == ZoneIds.P2_LIBRARY)
        if (!handToLibrary) return false
        val cardMoves = ledgerIntents.filter { it.move.cardId == forgeCardId }
        return cardMoves.any { it.move.from == Zone.Hand && it.move.to == Zone.Stack && it.category == TransferCategory.CastSpell } &&
            cardMoves.any { it.move.from == Zone.Stack && it.move.to == Zone.Library && it.category == TransferCategory.Resolve }
    }

    private fun zoneOnlyTransferCategory(
        prevZone: Int,
        destZone: Int,
        forgeCardId: ForgeCardId,
        events: List<GameEvent>,
        ledgerIntent: ZoneMoveIntent?,
        pendingResolution: GameEvent.SpellResolved?,
    ): TransferCategory =
        when {
            pendingResolution?.hasFizzled == true -> TransferCategory.Countered
            pendingResolution != null -> TransferCategory.Resolve
            else ->
                categoryForTransfer(
                    GameObjectInfo.getDefaultInstance(),
                    prevZone,
                    destZone,
                    forgeCardId,
                    events,
                    ledgerIntent,
                )
        }

    private fun ownerSeatIdForZone(zoneId: Int): Int? =
        when (zoneId) {
            ZoneIds.P1_HAND, ZoneIds.P1_LIBRARY, ZoneIds.P1_GRAVEYARD, ZoneIds.P1_SIDEBOARD, ZoneIds.REVEALED_P1 -> 1
            ZoneIds.P2_HAND, ZoneIds.P2_LIBRARY, ZoneIds.P2_GRAVEYARD, ZoneIds.P2_SIDEBOARD, ZoneIds.REVEALED_P2 -> 2
            else -> null
        }

    private inline fun <T> MutableList<T>.removeFirstOrNull(predicate: (T) -> Boolean): T? {
        val index = indexOfFirst(predicate)
        return if (index == -1) null else removeAt(index)
    }

    /**
     * Recover the source card forge id for a stack-ability surrogate.
     *
     * The surrogate inverse `abilityForgeId.value - STACK_ABILITY_ID_OFFSET`
     * yields different things under the two surrogate schemes:
     *   - SA-id-keyed surrogate → the Forge SpellAbility id
     *   - source-card-keyed surrogate (legacy fallback) → the source card forge id
     *
     * Disambiguation: when an in-window `SpellCast` / `SpellResolved` event
     * with matching `abilityForgeId` is present, its `cardId` is the source
     * card. Otherwise the inverse value is only safe to treat as a source-card
     * forge id when [forgeCardKnown] confirms a card with that id exists; SA
     * ids and card ids share the same numeric range so an unguarded fallback
     * can resolve to the wrong card and silently mis-emit grpId / sourceIid.
     * Returns `null` when no event disambiguates and the inverse isn't a
     * known card — the caller drops the appearance/disappearance.
     */
    private fun resolveStackAbilitySourceCard(
        abilityForgeId: ForgeCardId,
        events: List<GameEvent>,
        eventFilter: (GameEvent) -> Boolean,
        forgeCardKnown: (ForgeCardId) -> Boolean,
    ): ForgeCardId? {
        if (!FrameIdResolver.isStackAbilityForgeId(abilityForgeId)) return null
        val inverseValue = FrameIdResolver.stackAbilitySourceForgeId(abilityForgeId).value
        for (ev in events) {
            if (!eventFilter(ev)) continue
            val saId = abilityForgeIdOf(ev) ?: continue
            if (saId != inverseValue) continue
            return cardIdOf(ev)
        }
        val candidate = ForgeCardId(inverseValue)
        return if (forgeCardKnown(candidate)) {
            candidate
        } else {
            log.debug(
                "stack ability surrogate {} could not disambiguate source card via events; inverse {} not a known card forge id",
                abilityForgeId.value,
                inverseValue,
            )
            null
        }
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun abilityForgeIdOf(ev: GameEvent): Int? =
        when (ev) {
            is GameEvent.SpellCast -> ev.abilityForgeId.takeIf { it != 0 }
            is GameEvent.SpellResolved -> ev.abilityForgeId.takeIf { it != 0 }
            else -> null
        }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun cardIdOf(ev: GameEvent): ForgeCardId? =
        when (ev) {
            is GameEvent.SpellCast -> ev.cardId
            is GameEvent.SpellResolved -> ev.cardId
            else -> null
        }

    // --- private helpers ---

    /**
     * Detect triggered abilities that just appeared on the stack.
     * These are [GameObjectType.Ability] objects in the stack zone with no [previousZones] entry.
     */
    @Suppress("CyclomaticComplexMethod", "LongParameterList")
    private fun detectStackAbilityAppearances(
        patchedObjects: List<GameObjectInfo>,
        previousZones: Map<Int, Int>,
        mainLoopIds: Set<Int>,
        forgeIdLookup: (InstanceId) -> ForgeCardId?,
        idLookup: (ForgeCardId) -> InstanceId,
        events: List<GameEvent>,
        forgeCardKnown: (ForgeCardId) -> Boolean,
        stackAbilityLookup: (Int) -> StackAbilitySourceFacts?,
        paradigmSourceIidLookup: (ForgeCardId) -> Int?,
        sourceZoneLookup: (ForgeCardId) -> Int?,
    ): List<StackAbilityAppearance> {
        val appearances = mutableListOf<StackAbilityAppearance>()
        for (obj in patchedObjects) {
            if (obj.type != GameObjectType.Ability) continue
            if (obj.zoneId != ZoneIds.STACK) continue
            if (obj.instanceId in mainLoopIds) continue
            if (previousZones.containsKey(obj.instanceId)) continue

            val abilityForgeId = forgeIdLookup(InstanceId(obj.instanceId)) ?: continue
            val stackFacts = stackAbilityLookup(FrameIdResolver.stackAbilitySourceForgeId(abilityForgeId).value)
            if (stackFacts?.deferAnnouncement == true) continue
            val sourceCardForgeId =
                stackFacts?.sourceCardId
                    ?: resolveStackAbilitySourceCard(
                        abilityForgeId,
                        events,
                        eventFilter = { ev -> ev is GameEvent.SpellCast },
                        forgeCardKnown = forgeCardKnown,
                    ) ?: continue
            // Discriminate trigger vs activated. The matching SpellCast event
            // (same source card, isAbility set by the collector) tells us
            // which lifecycle path applies. Activated abilities skip the
            // persistent TriggeringObject — that annotation is trigger-only.
            val matchingCast =
                events
                    .filterIsInstance<GameEvent.SpellCast>()
                    .firstOrNull {
                        it.cardId == sourceCardForgeId &&
                            it.abilityForgeId == FrameIdResolver.stackAbilitySourceForgeId(abilityForgeId).value
                    }
            val isActivated = matchingCast?.let { it.isAbility && !it.isTrigger } ?: stackFacts?.isActivatedAbility ?: false
            val isParadigmTrigger = matchingCast?.abilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER
            val sourceCardIid =
                if (isParadigmTrigger) {
                    paradigmSourceIidLookup(sourceCardForgeId) ?: idLookup(sourceCardForgeId).value
                } else {
                    matchingCast?.sourceInstanceIdAtCast?.value ?: idLookup(sourceCardForgeId).value
                }
            val sourceZoneId =
                matchingCast
                    ?.activationZoneId
                    ?.takeIf { it != 0 }
                    ?: matchingCast?.let { sourceZoneLookup(it.cardId) }
                    ?: if (sourceCardIid > 0) previousZones[sourceCardIid] ?: 0 else 0
            val activationZone =
                if (isActivated) matchingCast?.activationZoneId ?: sourceZoneId else 0
            val triggeringObjectIid =
                matchingCast?.triggeringObjectInstanceId?.value
                    ?: matchingCast?.triggeringObjectCardId?.let { idLookup(it).value }
            val triggeringObjectZone =
                matchingCast?.triggeringObjectCardId?.let(sourceZoneLookup)
                    ?: triggeringObjectZoneId(triggeringObjectIid, patchedObjects, previousZones)

            appearances.add(
                StackAbilityAppearance(
                    abilityInstanceId = obj.instanceId,
                    sourceCardInstanceId = sourceCardIid,
                    sourceZoneId = sourceZoneId,
                    grpId = obj.grpId,
                    isActivatedAbility = isActivated,
                    isActivatedDiscover = matchingCast?.isActivatedDiscover == true,
                    activationZoneId = activationZone,
                    triggeringObjectInstanceId = triggeringObjectIid,
                    triggeringObjectZoneId = triggeringObjectZone,
                    voidTrigger = matchingCast?.voidTrigger == true,
                ),
            )
            log.debug(
                "stack ability appeared: iid={} grpId={} source={} activated={}",
                obj.instanceId,
                obj.grpId,
                sourceCardIid,
                isActivated,
            )
        }
        return appearances
    }

    private fun triggeringObjectZoneId(
        triggeringObjectIid: Int?,
        patchedObjects: List<GameObjectInfo>,
        previousZones: Map<Int, Int>,
    ): Int {
        if (triggeringObjectIid == null) return 0
        return patchedObjects.firstOrNull { it.instanceId == triggeringObjectIid }?.zoneId
            ?: previousZones[triggeringObjectIid]
            ?: 0
    }

    /**
     * Detect triggered abilities that left the stack (resolved or fizzled).
     * Finds instanceIds in [previousZones] that were on the stack but are absent from current objects.
     *
     * Returns (disappearances, retiredInstanceIds). Caller folds retired IDs into
     * [TransferResult] and appends them to Limbo — keeps this function pure.
     */
    @Suppress("LongParameterList")
    private fun detectStackAbilityDisappearances(
        events: List<GameEvent>,
        previousZones: Map<Int, Int>,
        currentInstanceIds: Set<Int>,
        mainLoopIds: Set<Int>,
        forgeIdLookup: (InstanceId) -> ForgeCardId?,
        idLookup: (ForgeCardId) -> InstanceId,
        grpIdResolver: (ForgeCardId) -> GrpId,
        forgeCardKnown: (ForgeCardId) -> Boolean,
        stackAbilityLookup: (Int) -> StackAbilitySourceFacts?,
    ): Pair<List<StackAbilityDisappearance>, List<Int>> {
        val resolvedEvents = events.filterIsInstance<GameEvent.SpellResolved>()
        val disappearances = mutableListOf<StackAbilityDisappearance>()
        val newRetiredIds = mutableListOf<Int>()

        for ((instanceId, zoneId) in previousZones) {
            if (zoneId != ZoneIds.STACK) continue
            if (instanceId in currentInstanceIds) continue
            if (instanceId in mainLoopIds) continue

            // Only match ability objects (forge ID in the stack-ability surrogate range).
            val abilityForgeId = forgeIdLookup(InstanceId(instanceId)) ?: continue
            if (!FrameIdResolver.isStackAbilityForgeId(abilityForgeId)) continue

            val stackFacts = stackAbilityLookup(FrameIdResolver.stackAbilitySourceForgeId(abilityForgeId).value)
            val sourceCardForgeId =
                stackFacts?.sourceCardId
                    ?: resolveStackAbilitySourceCard(
                        abilityForgeId,
                        events,
                        eventFilter = { ev -> ev is GameEvent.SpellResolved },
                        forgeCardKnown = forgeCardKnown,
                    ) ?: continue
            val sourceCardIid = idLookup(sourceCardForgeId).value
            val grpId = grpIdResolver(sourceCardForgeId).value

            // Correlate with SpellResolved event for fizzle detection.
            val resolvedEv = resolvedEvents.firstOrNull { it.cardId == sourceCardForgeId }
            val hasFizzled = resolvedEv?.hasFizzled == true

            newRetiredIds.add(instanceId)
            disappearances.add(
                StackAbilityDisappearance(
                    abilityInstanceId = instanceId,
                    sourceCardInstanceId = sourceCardIid,
                    grpId = grpId,
                    hasFizzled = hasFizzled,
                ),
            )
            log.debug("stack ability disappeared: iid={} grpId={} fizzled={}", instanceId, grpId, hasFizzled)
        }
        return disappearances to newRetiredIds
    }

    /**
     * Detect exile-return round-trips: Forge fired paired ChangeZone events
     * (BF→Exile, Exile→BF) for the same card within one resolve — typical of
     * saga final-chapter transforms (`DB$ ChangeZone | Origin$ Battlefield |
     * Destination$ Exile | SubAbility$ ChangeZone | ... | Origin$ Exile |
     * Destination$ Battlefield | Transformed$ True`).
     *
     * The main snapshot-diff loop misses these because the net state shows
     * the card still on Battlefield. Emit synthetic [TransferCategory.Exile]
     * and [TransferCategory.Return] transfers with two fresh allocations so
     * the client sees the expected ObjectIdChanged + ZoneTransfer pairs.
     *
     * Expected shape:
     * `ObjectIdChanged(A→B)` + `ZT(B, BF→Exile, "Exile")` +
     * `ObjectIdChanged(B→C)` + `ZT(C, Exile→BF, "Return")`.
     */
    @Suppress("LongParameterList")
    private fun detectExileReturnRoundTrips(
        zoneMoves: List<ZoneMove>,
        transfers: MutableList<AppliedTransfer>,
        patchedObjects: MutableList<GameObjectInfo>,
        patchedZones: MutableList<ZoneInfo>,
        retiredIds: MutableList<Int>,
        idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
        idLookup: (ForgeCardId) -> InstanceId,
    ) {
        // Dedupe by ForgeCardId: if a card bounces exile→return multiple times in
        // one resolve (delayed-trigger + chapter interactions), we only synthesize
        // once — the later pairs would try to retire already-retired iids.
        val exiled =
            zoneMoves
                .filter { it.from == Zone.Battlefield && it.to == Zone.Exile }
                .distinctBy { it.cardId }
        if (exiled.isEmpty()) return

        for (ev in exiled) {
            // Match a subsequent Exile→BF move for the same Forge card.
            val returned =
                zoneMoves.any {
                    it.cardId == ev.cardId && it.order > ev.order && it.from == Zone.Exile && it.to == Zone.Battlefield
                }
            if (!returned) continue

            val currentIid = idLookup(ev.cardId).value
            val objIdx = patchedObjects.indexOfFirst { it.instanceId == currentIid }
            if (objIdx < 0) continue
            val currentObj = patchedObjects[objIdx]
            if (currentObj.zoneId != ZoneIds.BATTLEFIELD) continue

            // Skip if the main loop or another post-pass already accounted for this.
            if (transfers.any { it.origId == currentIid || it.newId == currentIid }) continue

            // Allocate two fresh instanceIds: one for the Exile step, one for Return.
            val exileAlloc = idAllocator(ev.cardId)
            val exileIid = exileAlloc.new.value
            val returnAlloc = idAllocator(ev.cardId)
            val returnIid = returnAlloc.new.value

            transfers.add(
                AppliedTransfer(
                    origId = currentIid,
                    newId = exileIid,
                    category = TransferCategory.Exile,
                    srcZoneId = ZoneIds.BATTLEFIELD,
                    destZoneId = ZoneIds.EXILE,
                    forgeCardId = ev.cardId,
                    grpId = currentObj.grpId,
                    ownerSeatId = currentObj.ownerSeatId,
                    affectorId = 0,
                ),
            )
            transfers.add(
                AppliedTransfer(
                    origId = exileIid,
                    newId = returnIid,
                    category = TransferCategory.Return,
                    srcZoneId = ZoneIds.EXILE,
                    destZoneId = ZoneIds.BATTLEFIELD,
                    forgeCardId = ev.cardId,
                    grpId = currentObj.grpId,
                    ownerSeatId = currentObj.ownerSeatId,
                    affectorId = 0,
                ),
            )

            retiredIds.add(currentIid)
            retiredIds.add(exileIid)
            appendToZone(patchedZones, ZoneIds.LIMBO, currentIid)
            appendToZone(patchedZones, ZoneIds.LIMBO, exileIid)
            // Synthesize intermediate GameObjectInfos for the retired iids so
            // ZoneTransfer annotations referencing them resolve against a real
            // object (matches tribute-to-horobi.md gsId 145: both 288 and 318
            // persist in Limbo alongside 319 on BF for animation continuity).
            patchedObjects.add(
                currentObj
                    .toBuilder()
                    .setInstanceId(currentIid)
                    .setZoneId(ZoneIds.LIMBO)
                    .build(),
            )
            patchedObjects.add(
                currentObj
                    .toBuilder()
                    .setInstanceId(exileIid)
                    .setZoneId(ZoneIds.LIMBO)
                    .build(),
            )
            patchedObjects[objIdx] = currentObj.toBuilder().setInstanceId(returnIid).build()
            patchZoneInstanceId(patchedZones, ZoneIds.BATTLEFIELD, currentIid, returnIid)

            log.debug(
                "exile-return transform: forgeCardId={} currentIid={} exileIid={} returnIid={}",
                ev.cardId.value,
                currentIid,
                exileIid,
                returnIid,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun detectDisappearedSacrifices(
        events: List<GameEvent>,
        previousZones: Map<Int, Int>,
        patchedObjects: MutableList<GameObjectInfo>,
        patchedZones: MutableList<ZoneInfo>,
        transfers: MutableList<AppliedTransfer>,
        retiredIds: MutableList<Int>,
        zoneRecordings: MutableList<Pair<Int, Int>>,
        forgeIdLookup: (InstanceId) -> ForgeCardId?,
        idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation,
        idLookup: (ForgeCardId) -> InstanceId,
        manaAbilityGrpIdResolver: (ForgeCardId) -> GrpId,
    ) {
        val currentInstanceIds = patchedObjects.map { it.instanceId }.toSet()
        val sacrificeEvents = events.filterIsInstance<GameEvent.CardSacrificed>()
        if (sacrificeEvents.isEmpty()) return
        val manaAbilityEvents = events.filterIsInstance<GameEvent.ManaAbilityActivated>()
        val spellCastEvents = events.filterIsInstance<GameEvent.SpellCast>()
        // Skip instanceIds already processed by the main transfer loop to avoid
        // double-processing regular (non-token) sacrifices that are still in gameObjects.
        val mainLoopOrigIds = transfers.map { it.origId }.toSet()

        for ((instanceId, zoneId) in previousZones) {
            if (zoneId != ZoneIds.BATTLEFIELD) continue
            if (instanceId in mainLoopOrigIds) continue
            val forgeCardId = forgeIdLookup(InstanceId(instanceId)) ?: continue
            val sacrificeEv = sacrificeEvents.firstOrNull { it.cardId == forgeCardId } ?: continue

            val stillOnBattlefield = instanceId in currentInstanceIds
            val ownerSeat = sacrificeEv.seatId
            val destZone = ZoneIds.graveyardOf(ownerSeat)
            val handoff = ZoneHandoff.fromRealloc(idAllocator(forgeCardId), destZone)
            val origId = handoff.realloc.old.value
            val newId = handoff.realloc.new.value

            // If still in gameObjects, strip it so the client sees it leave.
            val resolvedGrpId =
                if (stillOnBattlefield) {
                    val idx = patchedObjects.indexOfFirst { it.instanceId == instanceId }
                    val grp =
                        if (idx >= 0) {
                            val g = patchedObjects[idx].grpId
                            patchedObjects.removeAt(idx)
                            g
                        } else {
                            0
                        }
                    removeFromZone(patchedZones, ZoneIds.BATTLEFIELD, instanceId)
                    appendToZone(patchedZones, destZone, newId)
                    grp
                } else {
                    0
                }

            val manaPayments =
                buildManaSacrificePayments(
                    forgeCardId,
                    origId,
                    manaAbilityEvents,
                    spellCastEvents,
                    idLookup,
                    manaAbilityGrpIdResolver,
                )

            // Remove this mana source from CastSpell transfers to avoid duplication.
            if (manaPayments.isNotEmpty()) {
                for (i in transfers.indices) {
                    val t = transfers[i]
                    if (t.category == TransferCategory.CastSpell && t.manaPayments.any { it.landInstanceId == origId }) {
                        transfers[i] = t.copy(manaPayments = t.manaPayments.filter { it.landInstanceId != origId })
                    }
                }
            }

            handoff.limboRetirement?.let { limbo ->
                retiredIds.add(limbo.value)
                appendToZone(patchedZones, ZoneIds.LIMBO, limbo.value)
            }

            transfers.add(
                AppliedTransfer(
                    origId = origId,
                    newId = newId,
                    category = TransferCategory.Sacrifice,
                    srcZoneId = ZoneIds.BATTLEFIELD,
                    destZoneId = destZone,
                    forgeCardId = forgeCardId,
                    grpId = resolvedGrpId,
                    ownerSeatId = ownerSeat.value,
                    manaPayments = manaPayments,
                ),
            )
            val (recIid, recZone) = handoff.zoneAssignment
            zoneRecordings.add(recIid.value to recZone)
            log.debug("disappeared token: iid {} → {} category=Sacrifice manaPayments={}", origId, newId, manaPayments.size)
        }
    }

    /** Build mana payment records for a sacrifice that activated a mana ability. */
    private fun buildManaSacrificePayments(
        forgeCardId: ForgeCardId,
        origId: Int,
        manaAbilityEvents: List<GameEvent.ManaAbilityActivated>,
        spellCastEvents: List<GameEvent.SpellCast>,
        idLookup: (ForgeCardId) -> InstanceId,
        manaAbilityGrpIdResolver: (ForgeCardId) -> GrpId,
    ): List<ManaPaymentRecord> {
        if (manaAbilityEvents.none { it.cardId == forgeCardId }) return emptyList()
        val castEv =
            spellCastEvents.firstOrNull { sc ->
                sc.manaPayments.any { it.sourceCardId == forgeCardId }
            } ?: return emptyList()
        val mp = castEv.manaPayments.first { it.sourceCardId == forgeCardId }
        return listOf(
            ManaPaymentRecord(
                landInstanceId = origId,
                manaAbilityInstanceId = idLookup(FrameIdResolver.manaAbilityForgeId(forgeCardId)).value,
                color = mp.color,
                abilityGrpId = MechanicSourceProjection.paymentAbilityGrpId(mp, manaAbilityGrpIdResolver).value,
                spellInstanceId = idLookup(castEv.cardId).value,
            ),
        )
    }

    /**
     * Apply a [ZoneHandoff]'s structural mutations to the local patch-set:
     * rewrite the GameObject's instanceId, rewrite the source zone's
     * `objectInstanceIds` list, append the old iid to Limbo, and signal
     * retirement to the caller's [retiredIds] accumulator.
     *
     * No-op when [ZoneHandoff.limboRetirement] is null (Resolve / keep-same-iid).
     */
    private fun applyHandoffToPatchSet(
        handoff: ZoneHandoff,
        patchedObjects: MutableList<GameObjectInfo>,
        objectIndex: Int,
        patchedZones: MutableList<ZoneInfo>,
        sourceZoneId: Int,
        retiredIds: MutableList<Int>,
    ) {
        val limbo = handoff.limboRetirement ?: return
        val origId = limbo.value
        val newId = handoff.realloc.new.value
        val obj = patchedObjects[objectIndex]
        patchedObjects[objectIndex] = obj.toBuilder().setInstanceId(newId).build()
        patchZoneInstanceId(patchedZones, sourceZoneId, origId, newId)
        retiredIds.add(origId)
        appendToZone(patchedZones, ZoneIds.LIMBO, origId)
    }

    private fun applyHiddenHandoffToZoneSet(
        handoff: ZoneHandoff,
        patchedZones: MutableList<ZoneInfo>,
        destinationZoneId: Int,
        retiredIds: MutableList<Int>,
    ) {
        val limbo = handoff.limboRetirement ?: return
        val origId = limbo.value
        val newId = handoff.realloc.new.value
        patchZoneInstanceId(patchedZones, destinationZoneId, origId, newId)
        retiredIds.add(origId)
        appendToZone(patchedZones, ZoneIds.LIMBO, origId)
    }

    /** Replace oldId with newId in a zone's objectInstanceIds list (after instanceId realloc). */
    private fun patchZoneInstanceId(
        zones: MutableList<ZoneInfo>,
        zoneId: Int,
        oldId: Int,
        newId: Int,
    ) {
        val idx = zones.indexOfFirst { it.zoneId == zoneId }
        if (idx < 0) return
        val zone = zones[idx]
        val ids = zone.objectInstanceIdsList.toMutableList()
        val idIdx = ids.indexOf(oldId)
        if (idIdx >= 0) {
            ids[idIdx] = newId
            zones[idx] =
                zone
                    .toBuilder()
                    .clearObjectInstanceIds()
                    .addAllObjectInstanceIds(ids)
                    .build()
        }
    }

    /** Append an instanceId to a zone's objectInstanceIds list. */
    private fun appendToZone(
        zones: MutableList<ZoneInfo>,
        zoneId: Int,
        instanceId: Int,
    ) {
        val idx = zones.indexOfFirst { it.zoneId == zoneId }
        if (idx < 0) return
        zones[idx] = zones[idx].toBuilder().addObjectInstanceIds(instanceId).build()
    }

    /** Remove an instanceId from a zone's objectInstanceIds list (no-op if not found). */
    private fun removeFromZone(
        zones: MutableList<ZoneInfo>,
        zoneId: Int,
        instanceId: Int,
    ) {
        val idx = zones.indexOfFirst { it.zoneId == zoneId }
        if (idx < 0) return
        val zone = zones[idx]
        val ids = zone.objectInstanceIdsList.filter { it != instanceId }
        zones[idx] =
            zone
                .toBuilder()
                .clearObjectInstanceIds()
                .addAllObjectInstanceIds(ids)
                .build()
    }
}
