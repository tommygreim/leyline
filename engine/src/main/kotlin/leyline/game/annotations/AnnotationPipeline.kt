package leyline.game.annotations

import leyline.bridge.types.EffectId
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.codes.DetailKeys
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PersistentFeedSet
import leyline.game.mapping.StateZoneProjection
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustedKind
import leyline.game.state.AbilityWireIdentity
import leyline.game.state.AnnotationProjectionState
import leyline.game.state.CardRevealedKind
import leyline.game.state.CrewedThisTurnKind
import leyline.game.state.EffectTracker
import leyline.game.state.FrameContext
import leyline.game.state.InstanceRevealedToOpponentKind
import leyline.game.state.ManaCreatureDesignationKind
import leyline.game.state.ManaDetailsKind
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.ModifiedTypeForCrewKind
import leyline.game.state.MutateLayeredEffectKind
import leyline.game.state.PersistentAnnotationKind
import leyline.game.state.PersistentAnnotationStore
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.QualificationKind
import leyline.game.state.SaddledThisTurnKind
import leyline.game.state.TargetSpecFact
import leyline.game.state.TargetSpecKind
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Hosts the diff → annotations spine lifted out of StateMapper.
 *
 * Two frame shapes feed [StateMapper]:
 * - [computeAnnotations] — stages 1-3: transfer + combat + trigger-lifecycle
 *   assembly into a transient/persistent [AnnotationPipelineResult].
 * - [computeRemainingAnnotations] — stages 4-5: mechanic + effect annotations
 *   and persistent-store batch computation.
 *
 * The shared annotation-time resolvers live on [AnnotationContext]; the
 * per-mechanic emitters live behind the [contributors] registry.
 * [EarthbendEmitter] and `buildAbilityExhaustedAnnotations` stay as spine-called
 * emitters. Earthbend is effect-diff-channel coupled and does not fit the
 * contributor contract; ability-exhaustion projection maps final cut rows.
 *
 * Transfer-model patchers (decayed-cleanup, delayed-trigger holders, reveal
 * proxies, redaction) deliberately stay in StateMapper: they reassign the
 * transfer result / mutate GSM zones and flow into GSM assembly, so they belong
 * to the mapper's mutation boundary rather than this annotation host.
 */
@Suppress("LargeClass")
object AnnotationPipeline {
    /**
     * Mechanic contributors, listed in [AnnotationContributor.rank] order.
     *
     * This is a documentation + fitness anchor, NOT a runtime dispatch list:
     * membership here does not wire a contributor in. The spine invokes each
     * contributor explicitly at its phase-correct call site, because both the
     * transient ordering and the shared effect-id allocator (`effects.nextEffectId()`,
     * drawn by crew / reconfigure / mutate-merge alike) are load-bearing — a flat
     * rank-sorted pass would reorder emitted ids and positions. [rank] is
     * descriptive: it records the canonical contribution order the call sites
     * already follow. The boundary fitness function asserts over this list; a new
     * contributor must be both registered here and invoked at a call site.
     *
     * **Placement for a new mechanic's annotation emission:**
     * - Persistent lifecycle annotation (dedup/expire tracked across GSMs) →
     *   a [leyline.game.state.PersistentAnnotationKind] row in
     *   [leyline.game.state.PersistentAnnotationKinds.all].
     * - Transient annotation tied to a zone-transfer, combat, or phase spine
     *   event → emit inline at that event's existing call site (see
     *   [assembleTransferAndCombatAnnotations]).
     * - Mechanic-scoped transient annotation independent of spine ordering →
     *   an [AnnotationContributor] entry here.
     * - Effect-id-lifecycle annotation (boost/keyword-style continuous effect)
     *   → the [EffectTracker] diff channel in the tentative projection planner,
     *   not this registry.
     */
    val contributors: List<AnnotationContributor> =
        listOf(
            ConvokeContributor,
            RevealStateContributor,
            TargetSpecContributor,
            ManaDetailsContributor,
            MutateMergeContributor,
            VehicleAttachContributor,
        )

    /** Death categories whose transfers defer behind same-frame DamageDealt emission. */
    private val deathTransferCategories =
        setOf(TransferCategory.Destroy, TransferCategory.SbaDamage, TransferCategory.SbaDeathtouch)

    /** Unfinalized transients plus persistent annotation computation. */
    internal data class RemainingAnnotationsResult(
        val transient: List<AnnotationInfo>,
        val persistent: List<AnnotationInfo>,
        val batch: PersistentAnnotationStore.BatchResult,
        val consumedTargetSpecs: List<TargetSpecFact>,
    )

    /** Stages 2-3 of the annotation pipeline: transfers → annotations + combat. */
    internal data class AnnotationPipelineResult(
        val annotations: MutableList<AnnotationInfo>,
        val transferPersistent: MutableList<AnnotationInfo>,
        val combatResult: CombatAnnotationResult,
    )

    internal fun computeAnnotations(
        ctx: AnnotationContext,
        transferResult: TransferResult,
        actingSeat: Int,
        annotationJournal: AnnotationProjectionState.Planner = ctx.editor.annotations,
        previousSnapshot: GsmSnapshot? = null,
    ): AnnotationPipelineResult {
        val events = ctx.events
        val combatTransferredIds =
            transferResult.transfers
                .mapNotNull { transfer -> transfer.forgeCardId?.let { it to transfer.origId } }
                .toMap()
        val combatResult =
            CombatAnnotations.combatAnnotations(
                events = events,
                idResolver = { fid ->
                    val transferred = combatTransferredIds[fid]
                    if (transferred != null) InstanceId(transferred) else ctx.frameIds.cardIid(fid)
                },
            )
        val stateZoneFacts = StateZoneProjection.zoneTransferFacts(ctx.snap)
        val paradigmSourceStackIidLookup: (ForgeCardId, ForgeCardId?) -> Int? = { forgeCardId, eventSourceCardId ->
            StateZoneProjection.paradigmSourceStackIid(
                facts = stateZoneFacts,
                forgeCardId = forgeCardId,
                eventSourceForgeCardId = eventSourceCardId,
                stackIidLookup = annotationJournal::paradigmSourceStackIidFor,
            )
        }
        val (annotations, transferPersistent) =
            assembleTransferAndCombatAnnotations(
                events = events,
                transferResult = transferResult,
                actingSeat = actingSeat,
                combatResult = combatResult,
                ctx = ctx,
                annotationJournal = annotationJournal,
                paradigmSourceStackIidLookup = paradigmSourceStackIidLookup,
            )
        annotations.addAll(phasingAnnotations(previousSnapshot, ctx.snap, ctx.frameIds))
        return AnnotationPipelineResult(annotations, transferPersistent, combatResult)
    }

    /** Forge retains phased permanents on the battlefield; project the explicit client phase transition. */
    private fun phasingAnnotations(
        prev: GsmSnapshot?,
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> {
        if (prev == null) return emptyList()
        val previousPhased =
            prev.zones[ZoneIds.PHASED_OUT]
                ?.contents
                .orEmpty()
                .toSet()
        val currentPhased =
            snap.zones[ZoneIds.PHASED_OUT]
                ?.contents
                .orEmpty()
                .toSet()
        val phasedOut = (currentPhased - previousPhased).map(frameIds::cardIid)
        val phasedIn = (previousPhased - currentPhased).map(frameIds::cardIid)
        return buildList {
            if (phasedOut.isNotEmpty()) add(AnnotationBuilder.phasedOut(phasedOut))
            if (phasedIn.isNotEmpty()) add(AnnotationBuilder.phasedIn(phasedIn))
        }
    }

    /**
     * Assemble stages 2-3 around the key invariant for lethal damage:
     * DamageDealt must land before the victim's destroy transfer.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    internal fun assembleTransferAndCombatAnnotations(
        events: List<GameEvent>,
        transferResult: TransferResult,
        actingSeat: Int,
        combatResult: CombatAnnotationResult,
        ctx: AnnotationContext? = null,
        annotationJournal: AnnotationProjectionState.Planner = AnnotationProjectionState.Planner(AnnotationProjectionState()),
        paradigmSourceStackIidLookup: (ForgeCardId, ForgeCardId?) -> Int? = { forgeCardId, _ ->
            annotationJournal.paradigmSourceStackIidFor(forgeCardId)
        },
    ): Pair<MutableList<AnnotationInfo>, MutableList<AnnotationInfo>> {
        val frameIds = ctx?.frameIds
        val annotations = mutableListOf<AnnotationInfo>()
        val transferPersistent = mutableListOf<AnnotationInfo>()
        val lethalDamageVictims =
            events
                .filterIsInstance<GameEvent.DamageDealtToCard>()
                .map { it.targetCardId }
                .toSet()
        // Activated-ability affector map: forgeCardId → queue of AB iids. Stamps
        // affectorId on cost-paid transfers (cycling discard, channel discard,
        // unearth GY→BF return) so the resulting annotation ties the transfer
        // back to the ability instance that caused it.
        //
        // Restricted to categories that are plausibly the "ability put X here"
        // outcome — Discard (cycling/channel cost), Resolve (unearth GY→BF) —
        // so an unrelated transfer for the same card later in the same frame
        // (e.g., a bounce trigger BF→Hand) doesn't get the AB iid stamped.
        //
        // Backed by a mutable per-card queue so chained activations on the
        // same card in one frame burn distinct AB iids in order; the same
        // forgeCardId stamping the same iid twice would conflate transfers.
        val activatedAbilityAffectors: MutableMap<ForgeCardId, ArrayDeque<Int>> =
            if (frameIds != null) {
                events
                    .filterIsInstance<GameEvent.SpellCast>()
                    .filter { it.isAbility && !it.isTrigger }
                    .map { it.cardId to AnnotationContext.stackAbilityIid(it.abilityForgeId, it.cardId, frameIds) }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, iids) -> ArrayDeque(iids) }
                    .toMutableMap()
            } else {
                mutableMapOf()
            }
        val patchedTransfers =
            if (activatedAbilityAffectors.isEmpty()) {
                transferResult.transfers
            } else {
                transferResult.transfers.map { transfer ->
                    val canCarryAffector =
                        transfer.affectorId == 0 &&
                            (
                                transfer.category == TransferCategory.Discard ||
                                    transfer.category == TransferCategory.Resolve
                            )
                    if (!canCarryAffector) return@map transfer
                    val abIid = transfer.forgeCardId?.let { activatedAbilityAffectors[it]?.removeFirstOrNull() }
                    if (abIid != null) transfer.copy(affectorId = abIid) else transfer
                }
            }
        val (deferredTransfers, immediateTransfers) =
            patchedTransfers.partition { transfer ->
                transfer.category in deathTransferCategories &&
                    transfer.forgeCardId != null &&
                    transfer.forgeCardId in lethalDamageVictims
            }

        fun emitTransfer(transfer: AppliedTransfer) {
            val (transient, persistent) = TransferAnnotations.annotationsForTransfer(transfer, SeatId(actingSeat))
            annotations.addAll(transient)
            transferPersistent.addAll(persistent)
        }

        for (transfer in immediateTransfers) emitTransfer(transfer)
        // Snapshot-derived appearances (cast spells visible on the stack at snapshot time).
        val snapshotSourceIids = transferResult.stackAbilityAppearances.map { it.sourceCardInstanceId }.toSet()
        val snapshotAppearanceIids = transferResult.stackAbilityAppearances.map { it.abilityInstanceId }.toSet()
        val abilityLineage = annotationJournal
        val eventAbilityGrpIdsByIid =
            if (ctx != null) {
                events
                    .filterIsInstance<GameEvent.SpellCast>()
                    .associate { cast ->
                        val abilityIid = ctx.stackAbilityIid(cast.abilityForgeId, cast.cardId)
                        val grpId =
                            cast.abilityGrpId.takeIf { it != 0 }
                                ?: ctx.abilityGrpIdForSource(cast.cardId)
                        abilityIid to grpId
                    }
            } else {
                emptyMap()
            }
        for (a in transferResult.stackAbilityAppearances) {
            // Source-zone resolution prefers an explicit activation zone, then
            // the closed-frame mechanic fact, then the prior projection zone.
            val sourceZone = if (a.activationZoneId != 0) a.activationZoneId else a.sourceZoneId
            val abilityGrpId = eventAbilityGrpIdsByIid[a.abilityInstanceId] ?: a.grpId
            abilityLineage.recordAbility(
                AbilityWireIdentity(
                    abilityIid = a.abilityInstanceId,
                    sourceIidAtCreate = a.sourceCardInstanceId,
                    sourceZoneAtCreate = sourceZone,
                    abilityGrpId = abilityGrpId,
                ),
            )
            annotations.add(
                AnnotationBuilder.abilityInstanceCreated(
                    InstanceId(a.abilityInstanceId),
                    InstanceId(a.sourceCardInstanceId),
                    sourceZone,
                ),
            )
            if (TriggeringObjectProjection.shouldEmit(a.grpId, a.isActivatedAbility, a.voidTrigger, ctx?.environment?.cardReferences)) {
                transferPersistent.add(
                    AnnotationBuilder.triggeringObject(
                        abilityInstanceId = InstanceId(a.abilityInstanceId),
                        sourceCardInstanceId = InstanceId(a.triggeringObjectInstanceId ?: a.sourceCardInstanceId),
                        sourceZone = a.triggeringObjectZoneId.takeIf { it != 0 } ?: sourceZone,
                    ),
                )
            }
            if (a.isActivatedDiscover) {
                transferPersistent.add(
                    AnnotationBuilder.linkInfoActivatedAbility(
                        abilityInstanceId = InstanceId(a.abilityInstanceId),
                        sourceCardInstanceId = InstanceId(a.sourceCardInstanceId),
                    ),
                )
            }
        }
        val snapshotDisappearanceIids = transferResult.stackAbilityDisappearances.map { it.abilityInstanceId }.toSet()
        // Event-driven trigger lifecycle. With auto-pass on the local turn the
        // trigger can fire and resolve between two snapshots so the snap-diff
        // misses both halves; with the per-trigger GSM split (GamePlayback)
        // each half lands in its own GSM, so cast and resolve events arrive in
        // separate drains. We emit the cast-side annotations from cast events
        // and the resolve-side from resolve events independently — guarding
        // against double-emission when the snap-diff also caught the
        // appearance/disappearance.
        var damageResidualLifeAnnotations = emptyList<AnnotationInfo>()
        var resolutionOwnedDamageInserted = false
        if (ctx != null) {
            emitTriggerLifecycleAnnotations(
                ctx = ctx,
                snapshotSourceIids = snapshotSourceIids,
                snapshotAppearanceIids = snapshotAppearanceIids,
                snapshotDisappearanceIids = snapshotDisappearanceIids,
                annotations = annotations,
                transferPersistent = transferPersistent,
                annotationJournal = annotationJournal,
                paradigmSourceStackIidLookup = paradigmSourceStackIidLookup,
            )
            damageResidualLifeAnnotations = insertResolutionEventAnnotations(ctx, annotations)
            resolutionOwnedDamageInserted =
                insertResolutionOwnedDamageAnnotations(
                    ctx = ctx,
                    annotations = annotations,
                    damageAnnotations = combatResult.resolutionOwnedAnnotations,
                    transfers = patchedTransfers,
                )
        }
        for (d in transferResult.stackAbilityDisappearances) {
            val lineage = abilityLineage.consumeAbility(d.abilityInstanceId)
            val sourceCardInstanceId = lineage?.sourceIidAtCreate ?: d.sourceCardInstanceId
            annotations.add(
                AnnotationBuilder.abilityInstanceDeleted(
                    InstanceId(d.abilityInstanceId),
                    InstanceId(sourceCardInstanceId),
                ),
            )
        }
        for (ev in events.filterIsInstance<GameEvent.PhaseChanged>()) {
            annotations.add(AnnotationBuilder.phaseOrStepModified(ev.seatId, ev.phase, ev.step))
        }
        if (!resolutionOwnedDamageInserted) annotations.addAll(combatResult.annotations)
        annotations.addAll(damageResidualLifeAnnotations)
        for (transfer in deferredTransfers) emitTransfer(transfer)
        return annotations to transferPersistent
    }

    /**
     * Inserts homogeneous noncombat damage into its unique resolving source bracket.
     *
     * A resolving card spell is identified by its Stack-origin Resolve transfer;
     * triggered and activated abilities use their stack-ability lifecycle identity.
     * Frames without exactly one owner retain the existing append behavior rather
     * than guessing across mixed or unrelated damage.
     */
    private fun insertResolutionOwnedDamageAnnotations(
        ctx: AnnotationContext,
        annotations: MutableList<AnnotationInfo>,
        damageAnnotations: List<AnnotationInfo>,
        transfers: List<AppliedTransfer>,
    ): Boolean {
        if (damageAnnotations.isEmpty()) return false

        val ownerIids =
            buildSet {
                transfers
                    .asSequence()
                    .filter { it.category == TransferCategory.Resolve && it.srcZoneId == ZoneIds.STACK }
                    .mapTo(this) { it.origId }
                ctx.events
                    .asSequence()
                    .filterIsInstance<GameEvent.SpellResolved>()
                    .filter { it.isTrigger || it.isAbility }
                    .mapTo(this) { ctx.stackAbilityIid(it.abilityForgeId, it.cardId) }
            }
        if (ownerIids.size != 1) return false

        val ownerIid = ownerIids.single()
        val completeIndex =
            annotations.indexOfFirst { annotation ->
                AnnotationType.ResolutionComplete in annotation.typeList && annotation.affectorId == ownerIid
            }
        if (completeIndex < 0) return false

        annotations.addAll(completeIndex, damageAnnotations)
        return true
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun insertResolutionEventAnnotations(
        ctx: AnnotationContext,
        annotations: MutableList<AnnotationInfo>,
    ): List<AnnotationInfo> {
        val events = ctx.events
        val payloadsByAffector = linkedMapOf<Int, MutableList<AnnotationInfo>>()
        val unmatched = mutableListOf<AnnotationInfo>()
        val damageResiduals = mutableListOf<AnnotationInfo>()
        val unclaimedDamageBySeat = mutableMapOf<Int, Int>()

        fun addPayload(
            affectorId: Int,
            annotation: AnnotationInfo,
        ) {
            if (affectorId == 0) {
                unmatched.add(annotation)
            } else {
                payloadsByAffector.getOrPut(affectorId) { mutableListOf() }.add(annotation)
            }
        }

        events.forEachIndexed { index, event ->
            when (event) {
                is GameEvent.DamageDealtToPlayer if event.changesLife ->
                    unclaimedDamageBySeat.merge(event.targetSeatId.value, event.amount, Int::plus)
                is GameEvent.CoinFlipped -> {
                    val affector = ctx.stackAbilityIid(event.abilityForgeId, event.sourceCardId)
                    addPayload(
                        affector,
                        AnnotationBuilder.coinFlip(InstanceId(affector), event.flipperSeatId, event.result),
                    )
                }
                is GameEvent.LifeChanged -> {
                    val coverage = consumeDamageOwnedLifeDelta(event, unclaimedDamageBySeat)
                    val delta = coverage.uncoveredDelta
                    if (delta == 0) return@forEachIndexed
                    val replacement = etbLifePaymentReplacement(ctx)
                    if (coverage.coveredLoss == 0 && replacement != null) {
                        val (row, transfer) = replacement
                        val payment =
                            listOf(
                                AnnotationBuilder.syntheticEvent(InstanceId(row.affectorId), event.seatId),
                                AnnotationBuilder.modifiedLife(event.seatId, delta, InstanceId(row.affectorId)),
                            )
                        val playIndex =
                            annotations.indexOfFirst { annotation ->
                                AnnotationType.UserActionTaken in annotation.typeList &&
                                    transfer.newId in annotation.affectedIdsList &&
                                    annotation.detailInt(DetailKeys.ACTION_TYPE) == ActionType.Play_add3.number
                            }
                        if (playIndex >= 0) annotations.addAll(playIndex, payment) else unmatched.addAll(payment)
                        return@forEachIndexed
                    }
                    val resolved = nextResolvedAbility(events, index)
                    val affector =
                        resolved?.let { ctx.stackAbilityIid(it.abilityForgeId, it.cardId) }
                            ?: previousActivatedAbility(events, index)?.let {
                                ctx.stackAbilityIid(it.abilityForgeId, it.cardId)
                            }
                            ?: 0
                    val annotation =
                        AnnotationBuilder.modifiedLife(event.seatId, delta, InstanceId(affector).takeIf { affector != 0 })
                    if (coverage.coveredLoss > 0) {
                        damageResiduals.add(annotation)
                    } else {
                        addPayload(affector, annotation)
                    }
                }
                else -> Unit
            }
        }

        if (payloadsByAffector.isEmpty() && unmatched.isEmpty()) return damageResiduals
        val ordered = mutableListOf<AnnotationInfo>()
        for (annotation in annotations) {
            ordered.add(annotation)
            if (AnnotationType.ResolutionStart in annotation.typeList) {
                payloadsByAffector.remove(annotation.affectorId)?.let(ordered::addAll)
            }
        }
        payloadsByAffector.values.forEach(ordered::addAll)
        ordered.addAll(unmatched)
        annotations.clear()
        annotations.addAll(ordered)
        return damageResiduals
    }

    private fun etbLifePaymentReplacement(ctx: AnnotationContext): Pair<AnnotationInfo, AppliedTransfer>? {
        val transfer = ctx.transferResult?.transfers?.singleOrNull { it.category == TransferCategory.PlayLand } ?: return null
        val row =
            ctx.editor.persistentAnnotations.activeAnnotations.values.singleOrNull { annotation ->
                annotation.typeList.any { it.number == 62 } &&
                    transfer.newId in annotation.affectedIdsList &&
                    annotation.detailInt(DetailKeys.REPLACEMENT_SOURCE_ZCID) == transfer.origId
            } ?: return null
        return row to transfer
    }

    private fun nextResolvedAbility(
        events: List<GameEvent>,
        afterIndex: Int,
    ): GameEvent.SpellResolved? =
        events
            .asSequence()
            .drop(afterIndex + 1)
            .filterIsInstance<GameEvent.SpellResolved>()
            .firstOrNull { it.isTrigger || it.isAbility }

    private fun previousActivatedAbility(
        events: List<GameEvent>,
        beforeIndex: Int,
    ): GameEvent.SpellCast? =
        events
            .asSequence()
            .take(beforeIndex)
            .filterIsInstance<GameEvent.SpellCast>()
            .lastOrNull { it.isAbility || it.isTrigger }

    private data class DamageCoverage(
        val uncoveredDelta: Int,
        val coveredLoss: Int,
    )

    private fun consumeDamageOwnedLifeDelta(
        life: GameEvent.LifeChanged,
        unclaimedDamageBySeat: MutableMap<Int, Int>,
    ): DamageCoverage {
        val delta = life.newLife - life.oldLife
        if (delta >= 0) return DamageCoverage(delta, coveredLoss = 0)
        val seat = life.seatId.value
        val unclaimedDamage = unclaimedDamageBySeat[seat] ?: 0
        val coveredLoss = minOf(-delta, unclaimedDamage)
        unclaimedDamageBySeat[seat] = unclaimedDamage - coveredLoss
        return DamageCoverage(delta + coveredLoss, coveredLoss)
    }

    /**
     * Emit AbilityInstanceCreated / TriggeringObject / ResolutionStart-Complete /
     * AbilityInstanceDeleted for triggered abilities that surfaced via the event
     * stream but were missed by snapshot-diff (auto-resolved between snapshots).
     *
     * The stack ability instanceId comes from
     * [FrameIdResolver.triggerStackAbilityIid] keyed on the event's
     * `abilityForgeId` so back-to-back triggers from one source card mint
     * distinct iids; falls back to source-card-keyed surrogate when the
     * collector didn't surface the SA id (legacy paths, defensive 0).
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun emitTriggerLifecycleAnnotations(
        ctx: AnnotationContext,
        snapshotSourceIids: Set<Int>,
        snapshotAppearanceIids: Set<Int>,
        snapshotDisappearanceIids: Set<Int>,
        annotations: MutableList<AnnotationInfo>,
        transferPersistent: MutableList<AnnotationInfo>,
        annotationJournal: AnnotationProjectionState.Planner = AnnotationProjectionState.Planner(AnnotationProjectionState()),
        paradigmSourceStackIidLookup: (ForgeCardId, ForgeCardId?) -> Int? = { forgeCardId, _ ->
            annotationJournal.paradigmSourceStackIidFor(forgeCardId)
        },
    ) {
        val events = ctx.events
        val snap = ctx.snap
        val frameIds = ctx.frameIds
        val enlistedIidsByAttacker =
            events
                .filterIsInstance<GameEvent.CardTapped>()
                .mapNotNull { tap -> tap.affectorCardId?.let { attacker -> attacker to frameIds.cardIid(tap.cardId).value } }
                .toMap()

        // Cast half: AbilityInstanceCreated (when snap-diff missed it) + persistent TriggeringObject.
        for (cast in events.filterIsInstance<GameEvent.SpellCast>().filter { it.isTrigger }) {
            val isParadigmTrigger = cast.isParadigmDelayedTrigger()
            val sourceCardIid =
                if (isParadigmTrigger) {
                    paradigmSourceStackIidLookup(cast.cardId, cast.paradigmSourceCardId)
                        ?: frameIds.cardIid(cast.cardId).value
                } else {
                    frameIds.cardIid(cast.cardId).value
                }
            val abilityIid = ctx.stackAbilityIid(cast.abilityForgeId, cast.cardId)
            val enlistTriggeringObjectIid =
                if (cast.abilityGrpId == KeywordAbilityIds.ENLIST) enlistedIidsByAttacker[cast.cardId] else null
            val triggeringObjectIid: Int =
                cast.triggeringObjectInstanceId?.value
                    ?: enlistTriggeringObjectIid
                    ?: cast.triggeringObjectCardId?.let { frameIds.cardIid(it).value }
                    ?: sourceCardIid
            val sourceZone =
                if (isParadigmTrigger) {
                    ZoneIds.STACK
                } else {
                    MechanicSourceProjection.sourceZoneId(cast, ctx.mechanicSourceFacts)
                }
            val triggeringObjectZone =
                MechanicSourceProjection.triggeringObjectZoneId(cast, sourceZone, ctx.mechanicSourceFacts)

            if (abilityIid in snapshotAppearanceIids || sourceCardIid in snapshotSourceIids) continue
            annotationJournal.recordAbility(
                AbilityWireIdentity(
                    abilityIid = abilityIid,
                    sourceIidAtCreate = sourceCardIid,
                    sourceZoneAtCreate = sourceZone,
                    abilityGrpId =
                        cast.abilityGrpId.takeIf { it != 0 }
                            ?: ctx.abilityGrpIdForSource(cast.cardId),
                ),
            )
            annotations.add(
                AnnotationBuilder.abilityInstanceCreated(
                    InstanceId(abilityIid),
                    InstanceId(sourceCardIid),
                    sourceZone,
                ),
            )
            if (TriggeringObjectProjection.shouldEmit(
                    cast.abilityGrpId,
                    isActivatedAbility = false,
                    cast.voidTrigger,
                    ctx.environment.cardReferences,
                )
            ) {
                transferPersistent.add(
                    AnnotationBuilder.triggeringObject(
                        abilityInstanceId = InstanceId(abilityIid),
                        sourceCardInstanceId = InstanceId(triggeringObjectIid),
                        sourceZone = triggeringObjectZone,
                    ),
                )
            }
        }

        // Activated-ability cast half: AbilityInstanceCreated keyed off the
        // event's activationZoneId (cycling=Hand, unearth=Graveyard, …).
        // No persistent TriggeringObject — that annotation is specific to
        // triggered abilities.
        for (cast in events.filterIsInstance<GameEvent.SpellCast>().filter { it.isAbility && !it.isTrigger }) {
            val sourceCardIid = cast.sourceInstanceIdAtCast?.value ?: frameIds.cardIid(cast.cardId).value
            val abilityIid = ctx.stackAbilityIid(cast.abilityForgeId, cast.cardId)
            val sourceZone =
                MechanicSourceProjection.sourceZoneId(cast, ctx.mechanicSourceFacts)

            if (
                abilityIid in snapshotAppearanceIids ||
                sourceCardIid in snapshotSourceIids ||
                annotationJournal.ability(abilityIid) != null
            ) {
                continue
            }
            annotationJournal.recordAbility(
                AbilityWireIdentity(
                    abilityIid = abilityIid,
                    sourceIidAtCreate = sourceCardIid,
                    sourceZoneAtCreate = sourceZone,
                    abilityGrpId =
                        cast.abilityGrpId.takeIf { it != 0 }
                            ?: ctx.abilityGrpIdForSource(cast.cardId),
                ),
            )
            annotations.add(
                AnnotationBuilder.abilityInstanceCreated(
                    InstanceId(abilityIid),
                    InstanceId(sourceCardIid),
                    sourceZone,
                ),
            )
            if (cast.isActivatedDiscover) {
                transferPersistent.add(
                    AnnotationBuilder.linkInfoActivatedAbility(
                        abilityInstanceId = InstanceId(abilityIid),
                        sourceCardInstanceId = InstanceId(sourceCardIid),
                    ),
                )
            }
        }

        // Resolve half: ResolutionStart/Complete (always — snap-diff doesn't emit
        // these for stack-only abilities) + AbilityInstanceDeleted (when snap-diff
        // missed it). Same shape applies for triggered and activated abilities.
        for (resolved in events.filterIsInstance<GameEvent.SpellResolved>().filter { it.isTrigger || it.isAbility }) {
            val sourceCardIid =
                if (resolved.isParadigmDelayedTrigger()) {
                    paradigmSourceStackIidLookup(resolved.cardId, resolved.paradigmSourceCardId)
                        ?: frameIds.cardIid(resolved.cardId).value
                } else {
                    frameIds.cardIid(resolved.cardId).value
                }
            val abilityIid = ctx.stackAbilityIid(resolved.abilityForgeId, resolved.cardId)
            val lineage =
                if (abilityIid in snapshotDisappearanceIids) {
                    annotationJournal.ability(abilityIid)
                } else {
                    annotationJournal.consumeAbility(abilityIid)
                }
            val aidSourceIid = lineage?.sourceIidAtCreate ?: sourceCardIid
            val abilityGrpId =
                lineage?.abilityGrpId?.takeIf { it != 0 }
                    ?: resolved.abilityGrpId.takeIf { it != 0 }
                    ?: ctx.abilityGrpIdForSource(resolved.cardId)

            annotations.add(AnnotationBuilder.resolutionStart(InstanceId(abilityIid), GrpId(abilityGrpId)))
            annotations.add(AnnotationBuilder.resolutionComplete(InstanceId(abilityIid), GrpId(abilityGrpId)))
            if (abilityIid !in snapshotDisappearanceIids) {
                annotations.add(
                    AnnotationBuilder.abilityInstanceDeleted(
                        InstanceId(abilityIid),
                        InstanceId(aidSourceIid),
                    ),
                )
            }
        }
    }

    private fun buildChoiceResultAnnotations(
        promptFacts: PromptProjectionFacts,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        promptFacts.choiceResults.map { fact ->
            val result = fact.result
            AnnotationBuilder.choiceResult(
                sourceInstanceId = frameIds.cardIid(result.sourceForgeCardId),
                chooserSeatId = result.chooserSeatId,
                choiceValue = result.choiceValue,
                choiceDomain = result.choiceDomain,
                sentiment = result.sentiment,
            )
        }

    // Local EffectTracker.DiffResult patch consumed only by the persistent-store
    // batch below. Synthesizes destroyed entries for earthbend layers so the
    // store emits LayeredEffectDestroyed for them. Unlike the transfer-model
    // patchers in StateMapper this does not touch the transfer result or GSM
    // zones, so it lives with the spine that consumes it.
    private fun EffectTracker.DiffResult.withDestroyedEarthbendLayers(layerIds: List<Int>): EffectTracker.DiffResult {
        if (layerIds.isEmpty()) return this
        val destroyed =
            layerIds.map { layerId ->
                EffectTracker.TrackedEffect(
                    syntheticId = layerId,
                    fingerprint = EffectTracker.EffectFingerprint(cardInstanceId = 0, timestamp = layerId.toLong(), staticId = 0L),
                    powerDelta = 0,
                    toughnessDelta = 0,
                )
            }
        return copy(destroyed = this.destroyed + destroyed)
    }

    private fun stackInstanceForEvent(
        ctx: AnnotationContext,
        castStackIidsByCard: Map<ForgeCardId, InstanceId>,
        ev: GameEvent.SpellCast,
    ): InstanceId? =
        if (ev.isAbility && ev.abilityForgeId != 0) {
            InstanceId(ctx.stackAbilityIid(ev.abilityForgeId, ev.cardId))
        } else {
            castStackIidsByCard[ev.cardId]
        }

    /** Stages 4-5: mechanic + effect annotations and persistent computation. */
    @Suppress("LongParameterList", "LongMethod")
    internal fun computeRemainingAnnotations(
        ctx: AnnotationContext,
        annotations: MutableList<AnnotationInfo>,
        transferPersistent: List<AnnotationInfo>,
        initEffectDiff: EffectTracker.DiffResult,
        effectDiff: EffectTracker.DiffResult,
        persistSnapshot: Map<Int, AnnotationInfo>,
        startPersistentId: Int,
        frameContext: FrameContext,
        keywordDiff: EffectTracker.KeywordDiffResult = EffectTracker.KeywordDiffResult(emptyList(), emptyList()),
        grantedAbilityDiff: EffectTracker.GrantedAbilityDiffResult =
            EffectTracker.GrantedAbilityDiffResult(emptyList(), emptyList()),
        combatResult: CombatAnnotationResult = CombatAnnotationResult(emptyList()),
        persistentFeeds: PersistentFeedSet = PersistentFeedSet(),
        convokePaymentsBySource: Map<ForgeCardId, List<TransferAnnotations.ConvokePaymentRecord>> = emptyMap(),
        transferResult: TransferResult,
        annotationJournal: AnnotationProjectionState.Planner,
    ): RemainingAnnotationsResult {
        val events = ctx.events
        val snap = ctx.snap
        val frameIds = ctx.frameIds
        val castSpellManaForgeIds =
            events
                .filterIsInstance<GameEvent.SpellCast>()
                .flatMap { it.manaPayments.map { mp -> mp.sourceCardId } }
                .toSet()
        val convokePaymentForgeIds = convokePaymentsBySource.values.flatten().mapTo(mutableSetOf()) { it.paymentForgeCardId }
        val sacrificedManaForgeIds =
            events
                .filterIsInstance<GameEvent.ManaAbilityActivated>()
                .filter { ma -> events.any { it is GameEvent.CardSacrificed && it.cardId == ma.cardId } }
                .map { it.cardId }
                .toSet()
        val manaPaidForgeCardIds = castSpellManaForgeIds + sacrificedManaForgeIds + convokePaymentForgeIds
        val castStackIidsByCard =
            transferResult.transfers
                .asSequence()
                .filter { it.category == TransferCategory.CastSpell }
                .mapNotNull { transfer -> transfer.forgeCardId?.let { it to InstanceId(transfer.newId) } }
                .toMap()
        val resolvingStackIidsByCard =
            transferResult.transfers
                .asSequence()
                .filter { it.category == TransferCategory.Resolve }
                .mapNotNull { transfer ->
                    val forgeCardId = transfer.forgeCardId ?: return@mapNotNull null
                    val resolvingId = if (transfer.origId != transfer.newId) transfer.origId else transfer.newId
                    forgeCardId to InstanceId(resolvingId)
                }.toMap()
        val castSpellTransferCardIds = castStackIidsByCard.keys
        val mechanicResult =
            MechanicAnnotations.mechanicAnnotations(
                events,
                manaPaidForgeCardIds,
                idResolver = { fid -> frameIds.cardIid(fid) },
                effectIdAllocator = { leyline.bridge.types.EffectId(ctx.effects.effects.nextEffectId()) },
                activeStealForgeCardIds = annotationJournal.activeStealForgeCardIds(),
                manaAbilityGrpIdResolver = { fid -> MechanicSourceProjection.manaAbilityGrpId(snap, fid) },
                counterAffectorResolver = { eventIndex, ev -> ctx.counterAffectorFor(eventIndex, ev) },
                playerCounterAffectorResolver = { eventIndex, ev -> ctx.playerCounterAffectorFor(eventIndex, ev) },
                tapAffectorResolver = { ev ->
                    MechanicSourceProjection.tapAffectorId(
                        ev,
                        resolvingStackIidsByCard,
                        castStackIidsByCard,
                        frameIds::triggerStackAbilityIid,
                        frameIds::cardIid,
                    )
                },
                shuffleAffectorResolver = { _, ev ->
                    ev.affectorCardId?.let { resolvingStackIidsByCard[it] ?: frameIds.cardIid(it) }
                },
                tokenAffectorResolver = { ev ->
                    tokenCreatedAffectorId(
                        ev,
                        resolvingStackIidsByCard,
                        stackAbilityIid = { abilityForgeId, sourceCardId ->
                            InstanceId(ctx.stackAbilityIid(abilityForgeId, sourceCardId))
                        },
                        cardIid = { sourceCardId -> frameIds.cardIid(sourceCardId) },
                        facts = ctx.mechanicSourceFacts,
                    )
                },
                stackInstanceResolver = { ev -> stackInstanceForEvent(ctx, castStackIidsByCard, ev) },
                castSpellTransferCardIds = castSpellTransferCardIds,
                convokePaymentsBySource = convokePaymentsBySource,
                delayedTriggerHolderResolver = { affected ->
                    snap.pendingTriggers
                        .firstOrNull { it.displaysAffectedCards && affected in it.affectedCardIds }
                        ?.let { pending -> frameIds.cardIid(pending.holderForgeId) }
                },
            )
        val earthbend = EarthbendEmitter.emit(ctx.effects.earthbend, ctx.effectFacts, snap)
        annotations.addAll(earthbend.destroyed)
        annotations.addAll(earthbend.created)
        // Identity-changing mechanic entries belong inside the resolution bracket.
        // The client needs token and shuffle remaps before ResolutionComplete and
        // before any DamageDealt entry that can reference the new identities.
        val (identityMechanicAnns, otherMechanic) =
            mechanicResult.transient.partition { ann ->
                AnnotationType.TokenCreated in ann.typeList || AnnotationType.Shuffle in ann.typeList
            }
        if (identityMechanicAnns.isNotEmpty()) {
            val firstCompletionOrDamageIdx =
                annotations
                    .indexOfFirst { ann ->
                        AnnotationType.ResolutionComplete in ann.typeList ||
                            AnnotationType.DamageDealt_af5a in ann.typeList
                    }.takeIf { it >= 0 } ?: annotations.size
            annotations.addAll(firstCompletionOrDamageIdx, identityMechanicAnns)
        }
        annotations.addAll(otherMechanic)
        annotations.addAll(earthbend.powerToughnessMods)
        annotations.addAll(buildChoiceResultAnnotations(ctx.promptFacts, frameIds))

        if (initEffectDiff.created.isNotEmpty()) {
            val (initTransient, _) = MechanicAnnotations.effectAnnotations(initEffectDiff)
            annotations.addAll(initTransient)
        }

        val keywordAffectorFallbackForgeCardId =
            events
                .filterIsInstance<GameEvent.SpellResolved>()
                .lastOrNull()
                ?.cardId
        val suspectedIids =
            snap.boundCards.values
                .asSequence()
                .filter { it.snapshot.isOnBattlefield && it.designations.isSuspected }
                .map { frameIds.cardIid(it.forgeCardId).value }
                .toSet()
        val (effectTransient, effectPersistent) =
            MechanicAnnotations.effectAnnotations(
                diff = effectDiff,
                keywordDiff = keywordDiff,
                grantedAbilityDiff = grantedAbilityDiff,
                grantedAbilitySourceInstanceId = frameIds::cardIid,
                keywordAffectorFallbackForgeCardId = keywordAffectorFallbackForgeCardId,
                keywordAffectorInstanceId = frameIds::cardIid,
                boostAffectorResolver = { effect, sourceAbilityGrpId ->
                    effect.sourceForgeCardId?.let(frameIds::cardIid)
                        ?: if (sourceAbilityGrpId?.value == KeywordAbilityIds.ENLIST) {
                            events
                                .filterIsInstance<GameEvent.SpellResolved>()
                                .lastOrNull { resolved ->
                                    resolved.isTrigger &&
                                        resolved.abilityGrpId == KeywordAbilityIds.ENLIST &&
                                        frameIds.cardIid(resolved.cardId).value == effect.cardInstanceId
                                }?.let { resolved ->
                                    InstanceId(ctx.stackAbilityIid(resolved.abilityForgeId, resolved.cardId))
                                }
                        } else {
                            null
                        }
                },
                uniqueAbilityIdAllocator = { ctx.effects.effects.nextEffectId() },
                keywordExtraAbilityGrpIds = { instanceId, keyword ->
                    if (keyword == "Menace" && instanceId.value in suspectedIids) {
                        listOf(AnnotationConstants.SUSPECTED_CANT_BLOCK_GRP_ID)
                    } else {
                        emptyList()
                    }
                },
            )
        annotations.addAll(effectTransient)

        // TargetSpec pAnn for each targeted spell/ability on the stack
        val pendingTargetSpecs = ctx.promptFacts.targetSpecs
        val revealState = RevealStateContributor.contribute(ctx)
        annotations.addAll(revealState.transient)
        val targetSpec = TargetSpecContributor.contribute(ctx)
        val manaDetails = ManaDetailsContributor.contribute(ctx)
        val mutateMerge = MutateMergeContributor.contribute(ctx)
        val abilityExhaustedPersistent = buildAbilityExhaustedAnnotations(ctx.abilityExhaustionFacts, frameIds)
        annotations.addAll(mutateMerge.transient)

        // Vehicle/Attach (Crew + Saddle + Reconfigure) — invoked here so its
        // crew/reconfigure effect-id allocations follow mutate-merge's on the
        // shared frame allocator, preserving emitted effect ids.
        val vehicleAttach = VehicleAttachContributor.contribute(ctx)
        annotations.addAll(vehicleAttach.transient)

        val enrichedMechanicResult =
            mechanicResult.copy(
                perKindPersistent =
                    buildMap<PersistentAnnotationKind, List<AnnotationInfo>> {
                        putAll(persistentFeeds.perKind)
                        put(
                            QualificationKind,
                            persistentFeeds[QualificationKind] +
                                mechanicResult.perKindPersistent[QualificationKind].orEmpty(),
                        )
                        put(CrewedThisTurnKind, vehicleAttach.persistent[CrewedThisTurnKind].orEmpty())
                        put(SaddledThisTurnKind, vehicleAttach.persistent[SaddledThisTurnKind].orEmpty())
                        put(ModifiedTypeForCrewKind, vehicleAttach.persistent[ModifiedTypeForCrewKind].orEmpty())
                        put(CardRevealedKind, revealState.persistent[CardRevealedKind].orEmpty())
                        put(
                            InstanceRevealedToOpponentKind,
                            revealState.persistent[InstanceRevealedToOpponentKind].orEmpty(),
                        )
                        put(TargetSpecKind, targetSpec.persistent[TargetSpecKind].orEmpty())
                        put(MutateLayeredEffectKind, mutateMerge.persistent[MutateLayeredEffectKind].orEmpty())
                        put(ManaCreatureDesignationKind, earthbend.designations)
                        put(ManaDetailsKind, manaDetails.persistent[ManaDetailsKind].orEmpty())
                        put(AbilityExhaustedKind, abilityExhaustedPersistent)
                    },
            )
        val storeEffectDiff = effectDiff.withDestroyedEarthbendLayers(earthbend.destroyedLayerIds)
        val grantedDestroyedEffects =
            grantedAbilityDiff.destroyed.map { granted ->
                EffectTracker.TrackedEffect(
                    syntheticId = granted.syntheticId,
                    fingerprint =
                        EffectTracker.EffectFingerprint(
                            cardInstanceId = granted.cardInstanceId,
                            timestamp = granted.fingerprint.timestamp,
                            staticId = granted.fingerprint.staticId,
                        ),
                    powerDelta = 0,
                    toughnessDelta = 0,
                )
            }
        val batch =
            PersistentAnnotationStore.computeBatch(
                currentActive = persistSnapshot,
                startPersistentId = startPersistentId,
                frame = frameContext,
                effectPersistent = effectPersistent + earthbend.effectPersistent,
                effectDiff = storeEffectDiff.copy(destroyed = storeEffectDiff.destroyed + grantedDestroyedEffects),
                destroyedEffectIds = keywordDiff.destroyed.map { it.syntheticId },
                transferPersistent = transferPersistent,
                mechanicResult = enrichedMechanicResult,
                combatResult = combatResult,
                activeStealForgeCardIds = annotationJournal.activeStealForgeCardIds(),
                resolveInstanceId = ctx.editor.identities::getOrAlloc,
                resolveForgeCardId = ctx.editor.identities::getForgeCardId,
            )

        // Emit LayeredEffectDestroyed for reverted steals
        for (effectId in batch.revertedEffectIds) {
            annotations.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effectId)))
        }

        annotationJournal.replaceActiveSteals(batch.activeStealForgeCardIds)

        return RemainingAnnotationsResult(
            transient = annotations.toList(),
            persistent = batch.allAnnotations,
            batch = batch,
            consumedTargetSpecs = pendingTargetSpecs,
        )
    }

    internal fun tokenCreatedAffectorId(
        event: GameEvent.TokenCreated,
        resolvingStackIidsByCard: Map<ForgeCardId, InstanceId>,
        stackAbilityIid: (Int, ForgeCardId) -> InstanceId,
        cardIid: (ForgeCardId) -> InstanceId,
        facts: MechanicSourceFacts = MechanicSourceFacts(),
    ): InstanceId? =
        MechanicSourceProjection.tokenCreatedAffectorId(
            event,
            facts,
            resolvingStackIidsByCard,
            stackAbilityIid,
            cardIid,
        )
}
