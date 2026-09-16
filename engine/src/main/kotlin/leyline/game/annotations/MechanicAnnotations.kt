package leyline.game.annotations

import leyline.bridge.types.EffectId
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.codes.CounterTypes
import leyline.game.codes.KeywordGrpIds
import leyline.game.codes.KeywordQualifications
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.state.EffectTracker
import leyline.game.state.PersistentAnnotationKind
import leyline.game.state.QualificationKind
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import kotlin.collections.iterator

/**
 * Result of mechanic annotation generation.
 *
 * Three persistent slots:
 *  - [persistent]: mixed-kind list (Counter + Attachment + DisplayCardUnderCard +
 *    ControllerChangedEffect) in event order. Kept as a flat list because
 *    [leyline.game.state.CounterKind] rows and the cleanup-tracked rows must
 *    interleave through step 3a's id allocator in
 *    [leyline.game.state.PersistentAnnotationStore.computeBatch].
 *  - [perKindPersistent]: per-registry-kind full-replacement set, keyed on
 *    [PersistentAnnotationKind]. Producers populate one entry per kind they emit;
 *    `computeBatch` dispatches via [leyline.game.state.PersistentAnnotationKinds.upsertable].
 *    Adding a new persistent kind is one row in the registry plus the producer
 *    wiring — no new field here.
 *  - Cross-kind cleanup signals ([detachedForgeCardIds], [exileSourceLeftPlayForgeCardIds],
 *    [controllerRevertedForgeCardIds]) drive steps 4-6 and don't fit either slot.
 */
data class MechanicAnnotationResult(
    val transient: List<AnnotationInfo>,
    val persistent: List<AnnotationInfo>,
    /** Per-registry-kind persistent annotations — full-replacement set this GSM, keyed
     *  by [PersistentAnnotationKind]. Consumed by
     *  [leyline.game.state.PersistentAnnotationStore.computeBatch]. */
    val perKindPersistent: Map<PersistentAnnotationKind, List<AnnotationInfo>> = emptyMap(),
    /** Forge card IDs of auras/equipment that were detached this GSM. */
    val detachedForgeCardIds: List<ForgeCardId> = emptyList(),
    /** Forge card IDs of permanents that left the battlefield this GSM.
     *  Used by [leyline.game.state.PersistentAnnotationStore.computeBatch] to clean up
     *  [AnnotationType.DisplayCardUnderCard] persistent annotations. */
    val exileSourceLeftPlayForgeCardIds: List<ForgeCardId> = emptyList(),
    /** Controller-change effects created this GSM (for persistent tracking). */
    val controllerChangedEffects: List<ControllerChangedEffect> = emptyList(),
    /** Forge card IDs of permanents whose control reverted this GSM. */
    val controllerRevertedForgeCardIds: List<ForgeCardId> = emptyList(),
) {
    /** Tracks an active controller-change effect for persistent annotation lifecycle. */
    data class ControllerChangedEffect(
        val forgeCardId: ForgeCardId,
        val effectId: Int,
        val affectorInstanceId: Int,
        val stolenInstanceId: Int,
    )
}

/**
 * Stages 4–5 of the annotation pipeline: mechanic events + layered effect lifecycle.
 *
 * Stage 4: counters, shuffle, scry, surveil, tokens, attachments, controller change, etc.
 * Stage 5: LayeredEffect P/T boosts + keyword grants from [EffectTracker].
 *
 * Pure functions — no shared mutable state.
 */
@Suppress("ElseCaseInsteadOfExhaustiveWhen", "MemberNameEqualsClassName")
object MechanicAnnotations {
    private val log = LoggerFactory.getLogger(MechanicAnnotations::class.java)

    /**
     * Stage 4: Generate standalone annotations for mechanic events (Group B + A+).
     *
     * These are NOT zone-transfer annotations — they appear alongside zone transfers
     * in the same GSM. Processes events that Stage 1-2 ignore: counters, shuffle,
     * scry, surveil, token creation, attachments.
     *
     * **Pure function** — uses [idResolver] to map forgeCardId → instanceId.
     * Returns [MechanicAnnotationResult] with both transient and persistent annotations.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
    fun mechanicAnnotations(
        events: List<GameEvent>,
        manaPaidForgeCardIds: Set<ForgeCardId> = emptySet(),
        idResolver: (ForgeCardId) -> InstanceId,
        effectIdAllocator: () -> EffectId = { EffectId(0) },
        activeStealForgeCardIds: Set<ForgeCardId> = emptySet(),
        manaAbilityGrpIdResolver: (ForgeCardId) -> GrpId = { GrpId(0) },
        counterAffectorResolver: (Int, GameEvent.CountersChanged) -> InstanceId? = { _, _ -> null },
        playerCounterAffectorResolver: (Int, GameEvent.PlayerCountersChanged) -> InstanceId? = { _, _ -> null },
        tapAffectorResolver: (GameEvent.CardTapped) -> InstanceId? = { null },
        shuffleAffectorResolver: (Int, GameEvent.LibraryShuffled) -> InstanceId? = { _, _ -> null },
        tokenAffectorResolver: (GameEvent.TokenCreated) -> InstanceId? = { null },
        stackInstanceResolver: (GameEvent.SpellCast) -> InstanceId? = { null },
        castSpellTransferCardIds: Set<ForgeCardId> = emptySet(),
        convokePaymentsBySource: Map<ForgeCardId, List<TransferAnnotations.ConvokePaymentRecord>> = emptyMap(),
        delayedTriggerHolderResolver: (ForgeCardId) -> InstanceId? = { null },
    ): MechanicAnnotationResult {
        val annotations = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()
        val qualificationPersistent = mutableListOf<AnnotationInfo>()
        val detachedForgeCardIds = mutableListOf<ForgeCardId>()
        val exileSourceLeftPlayForgeCardIds = mutableListOf<ForgeCardId>()
        val controllerChangedEffects = mutableListOf<MechanicAnnotationResult.ControllerChangedEffect>()
        val controllerRevertedForgeCardIds = mutableListOf<ForgeCardId>()
        for ((eventIndex, ev) in events.withIndex()) {
            when (ev) {
                is GameEvent.CountersChanged -> {
                    val delta = ev.newCount - ev.oldCount
                    if (delta == 0) continue
                    val instanceId = idResolver(ev.cardId)
                    if (delta > 0) {
                        annotations.add(
                            AnnotationBuilder.counterAdded(
                                instanceId,
                                ev.counterType,
                                delta,
                                affectorId = counterAffectorResolver(eventIndex, ev),
                            ),
                        )
                    } else {
                        annotations.add(AnnotationBuilder.counterRemoved(instanceId, ev.counterType, -delta))
                    }
                    // Persistent: Counter state annotation with current count
                    val counterType = CounterTypes.counterTypeId(ev.counterType)
                    if (counterType != 0) {
                        persistent.add(AnnotationBuilder.counter(instanceId, counterType, ev.newCount))
                    } else {
                        log.debug("mechanic: skipped unknown card counter type {} on iid={}", ev.counterType, instanceId.value)
                    }
                    log.debug("mechanic: counter {} {} on iid={}", if (delta > 0) "added" else "removed", ev.counterType, instanceId.value)
                }
                is GameEvent.PlayerCountersChanged -> {
                    val delta = ev.newCount - ev.oldCount
                    if (delta == 0) continue
                    val counterType = CounterTypes.counterTypeId(ev.counterType)
                    if (counterType == 0) {
                        log.debug("mechanic: skipped unknown player counter type {} on seat={}", ev.counterType, ev.seatId.value)
                        continue
                    }
                    if (delta > 0) {
                        annotations.add(
                            AnnotationBuilder.playerCounterAdded(
                                ev.seatId,
                                counterType,
                                delta,
                                affectorId = playerCounterAffectorResolver(eventIndex, ev),
                            ),
                        )
                    } else {
                        annotations.add(AnnotationBuilder.playerCounterRemoved(ev.seatId, counterType, -delta))
                    }
                    persistent.add(AnnotationBuilder.playerCounter(ev.seatId, counterType, ev.newCount))
                    log.debug(
                        "mechanic: player counter {} {} on seat={}",
                        if (delta > 0) "added" else "removed",
                        ev.counterType,
                        ev.seatId.value,
                    )
                }
                is GameEvent.LibraryShuffled -> {
                    annotations.add(AnnotationBuilder.shuffle(ev.seatId, ev.oldIds, ev.newIds, shuffleAffectorResolver(eventIndex, ev)))
                    log.debug("mechanic: shuffle seat={} cards={}", ev.seatId.value, ev.newIds.size)
                }
                is GameEvent.Scry -> {
                    annotations.add(AnnotationBuilder.scry(ev.seatId, ev.topIds, ev.bottomIds))
                    log.debug("mechanic: scry seat={} top={} bottom={}", ev.seatId.value, ev.topIds, ev.bottomIds)
                }
                is GameEvent.Surveil -> {
                    // No annotation. Surveil rides its ZoneTransfers, which carry
                    // category=Surveil. The Scry annotation is not a fit: the client
                    // narrates it strictly as a scry ("Scry: 1 top, 1 bottom" on the
                    // avatar) because ScryEventAnnotationParser reads only topIds and
                    // bottomIds — its libraryIds/graveyardIds constants are declared
                    // and never read, so there is no surveil form of this annotation.
                    log.debug("mechanic: surveil seat={} lib={} gy={}", ev.seatId.value, ev.libraryIds, ev.graveyardIds)
                }
                is GameEvent.TokenCreated -> {
                    val instanceId = idResolver(ev.cardId)
                    val affectorId = tokenAffectorResolver(ev)
                    annotations.add(AnnotationBuilder.tokenCreated(instanceId, affectorId))
                    log.debug("mechanic: tokenCreated iid={} affector={}", instanceId.value, affectorId?.value)
                }
                is GameEvent.TokenDestroyed -> {
                    val instanceId = idResolver(ev.cardId)
                    annotations.add(AnnotationBuilder.tokenDeleted(instanceId))
                    log.debug("mechanic: tokenDeleted iid={}", instanceId.value)
                }
                is GameEvent.SpellCast -> {
                    annotations.addAll(
                        TransferAnnotations.castSpellEventAnnotations(
                            ev,
                            idResolver,
                            manaAbilityGrpIdResolver,
                            stackInstanceResolver,
                            convokePaymentsBySource[ev.cardId].orEmpty(),
                        ),
                    )
                    if (ev.cardId !in castSpellTransferCardIds) {
                        persistent.addAll(
                            TransferAnnotations.castSpellEventPersistentAnnotations(
                                ev,
                                idResolver,
                                stackInstanceResolver,
                            ),
                        )
                    }
                    log.debug(
                        "mechanic: spellCast iid={} payments={} adventure={} altCost={} trigger={}",
                        idResolver(ev.cardId).value,
                        ev.manaPayments.size,
                        ev.isAdventure,
                        ev.altCostAbilityGrpId,
                        ev.isTrigger,
                    )
                }
                is GameEvent.CardTapped -> {
                    if (ev.cardId in manaPaidForgeCardIds) {
                        log.debug("mechanic: skipping tapped for mana-paid land forgeId={}", ev.cardId)
                    } else {
                        val instanceId = idResolver(ev.cardId)
                        annotations.add(
                            AnnotationBuilder.tappedUntappedPermanent(
                                instanceId,
                                tapAffectorResolver(ev) ?: ev.affectorCardId?.let(idResolver) ?: instanceId,
                                ev.tapped,
                            ),
                        )
                        log.debug("mechanic: tapped={} iid={}", ev.tapped, instanceId.value)
                    }
                }
                is GameEvent.PowerToughnessChanged -> {
                    val instanceId = idResolver(ev.cardId)
                    if (ev.oldPower != ev.newPower) {
                        annotations.add(AnnotationBuilder.modifiedPower(instanceId))
                    }
                    if (ev.oldToughness != ev.newToughness) {
                        annotations.add(AnnotationBuilder.modifiedToughness(instanceId))
                    }
                    // P/T modification event for buff animation
                    val powerDelta = ev.newPower - ev.oldPower
                    val toughnessDelta = ev.newToughness - ev.oldToughness
                    if (powerDelta != 0 || toughnessDelta != 0) {
                        annotations.add(AnnotationBuilder.powerToughnessModCreated(instanceId, powerDelta, toughnessDelta))
                    }
                    log.debug(
                        "mechanic: P/T changed iid={} {}/{}→{}/{}",
                        instanceId.value,
                        ev.oldPower,
                        ev.oldToughness,
                        ev.newPower,
                        ev.newToughness,
                    )
                }
                is GameEvent.CardAttached -> {
                    val auraIid = idResolver(ev.cardId)
                    val targetIid = idResolver(ev.targetCardId)
                    annotations.add(AnnotationBuilder.attachmentCreated(auraIid, targetIid))
                    persistent.add(AnnotationBuilder.attachment(auraIid, targetIid))
                    log.debug("mechanic: attachment aura={} target={}", auraIid.value, targetIid.value)
                }
                is GameEvent.CardDetached -> {
                    val auraIid = idResolver(ev.cardId)
                    val targetIid = ev.targetCardId?.let(idResolver)
                    annotations.add(
                        AnnotationBuilder.removeAttachment(
                            auraIid = auraIid,
                            targetIid = targetIid,
                            invalidatingGrpId = ev.invalidatingAbilityGrpId.takeIf { it != 0 }?.let(::GrpId),
                        ),
                    )
                    detachedForgeCardIds.add(ev.cardId)
                    log.debug("mechanic: removeAttachment aura={}", auraIid.value)
                }
                is GameEvent.CardsRevealed -> Unit
                is GameEvent.RevealProxiesDeleted -> {
                    for (proxyId in ev.proxyInstanceIds) {
                        annotations.add(AnnotationBuilder.revealedCardDeleted(proxyId))
                        log.debug("mechanic: revealedCardDeleted proxyIid={}", proxyId)
                    }
                }
                is GameEvent.CardTransformed -> {
                    // Emit Qualification pAnns for back-face keywords.
                    // Currently only Menace is mapped; table grows as more keywords are observed.
                    if (ev.isBackSide) {
                        val menace = KeywordQualifications.forKeyword("Menace")
                        if (menace != null) {
                            val instanceId = idResolver(ev.cardId)
                            qualificationPersistent.add(
                                AnnotationBuilder.qualification(
                                    affectorId = instanceId,
                                    instanceId = instanceId,
                                    grpId = menace.grpId,
                                    qualificationType = menace.qualificationType,
                                    qualificationSubtype = menace.qualificationSubtype,
                                    sourceParent = instanceId,
                                ),
                            )
                            log.debug("mechanic: Qualification (Menace) on transform iid={}", instanceId.value)
                        }
                    }
                }
                // Track permanents leaving battlefield for DisplayCardUnderCard cleanup.
                // CardExiled is safe to add unconditionally — findExileSourcesLeavingPlay
                // only matches cards that were an exile source (affectorId), not exiled cards.
                is GameEvent.CardDestroyed -> exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                is GameEvent.CardSacrificed -> exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                is GameEvent.CardBounced -> exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                is GameEvent.CardExiled -> {
                    val sourceId = ev.sourceCardId
                    if (sourceId != null) {
                        val sourceIid = delayedTriggerHolderResolver(ev.cardId) ?: idResolver(sourceId)
                        val exiledIid = idResolver(ev.cardId)
                        persistent.add(AnnotationBuilder.displayCardUnderCard(affectorId = sourceIid, instanceId = exiledIid))
                        log.debug("mechanic: displayCardUnderCard source={} exiled={}", sourceIid.value, exiledIid.value)
                    }
                    exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                }
                is GameEvent.ControllerChanged -> {
                    val cardIid = idResolver(ev.cardId)
                    val isRevert = ev.cardId in activeStealForgeCardIds

                    if (isRevert) {
                        // Control reverted — signal cleanup of existing CC persistent annotation
                        controllerRevertedForgeCardIds.add(ev.cardId)
                        log.debug(
                            "mechanic: controllerChanged revert iid={} {}->{}",
                            cardIid.value,
                            ev.oldControllerSeatId,
                            ev.newControllerSeatId,
                        )
                    } else {
                        // New steal: emit transient + persistent + track effect.
                        // Control-change events can land before the ability's SpellResolved in the same frame.
                        // Prefer prior resolutions for multi-spell frames, then fall forward to ability metadata.
                        val evIndex = events.indexOf(ev)
                        val priorResolved =
                            events
                                .subList(0, evIndex)
                                .filterIsInstance<GameEvent.SpellResolved>()
                        val followingAbilityResolved =
                            events
                                .subList(evIndex + 1, events.size)
                                .filterIsInstance<GameEvent.SpellResolved>()
                                .firstOrNull { it.abilityGrpId != 0 }
                        val spellResolved = priorResolved.lastOrNull() ?: followingAbilityResolved
                        val affectorIid =
                            if (spellResolved != null) {
                                idResolver(spellResolved.cardId)
                            } else {
                                InstanceId(0)
                            }
                        val sourceAbilityGrpId = spellResolved?.abilityGrpId?.takeIf { it != 0 }?.let(::GrpId)
                        val effectId = effectIdAllocator()
                        annotations.add(AnnotationBuilder.layeredEffectCreated(effectId, affectorIid))
                        annotations.add(AnnotationBuilder.controllerChanged(affectorIid, cardIid))
                        persistent.add(
                            AnnotationBuilder.controllerChangedEffect(
                                affectorIid,
                                cardIid,
                                effectId,
                                sourceAbilityGrpId = sourceAbilityGrpId,
                            ),
                        )
                        controllerChangedEffects.add(
                            MechanicAnnotationResult.ControllerChangedEffect(
                                ev.cardId,
                                effectId.value,
                                affectorIid.value,
                                cardIid.value,
                            ),
                        )
                        log.debug(
                            "mechanic: controllerChanged steal iid={} affector={} effectId={} {}->{}",
                            cardIid.value,
                            affectorIid.value,
                            effectId.value,
                            ev.oldControllerSeatId,
                            ev.newControllerSeatId,
                        )
                    }
                }
                is GameEvent.LegendRuleDeath -> exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                is GameEvent.ZoneChanged -> {
                    if (ev.from == Zone.Battlefield) exileSourceLeftPlayForgeCardIds.add(ev.cardId)
                }
                else -> {} // Remaining zone-transfer events handled in Stages 1-2, combat in Stage 3
            }
        }
        return MechanicAnnotationResult(
            transient = annotations,
            persistent = persistent,
            perKindPersistent =
                if (qualificationPersistent.isNotEmpty()) {
                    mapOf(QualificationKind to qualificationPersistent.toList())
                } else {
                    emptyMap()
                },
            detachedForgeCardIds = detachedForgeCardIds,
            exileSourceLeftPlayForgeCardIds = exileSourceLeftPlayForgeCardIds,
            controllerChangedEffects = controllerChangedEffects,
            controllerRevertedForgeCardIds = controllerRevertedForgeCardIds,
        )
    }

    /**
     * Stage 5: Build LayeredEffect lifecycle annotations from [EffectTracker.DiffResult].
     *
     * Pure function — converts diff results to proto annotations.
     * Returns (transient, persistent) matching the pipeline convention.
     *
     * Source attribution is carried by tracked lifecycle values. Forge IDs are
     * mapped to tentative client IDs only when a created annotation is emitted.
     */
    fun effectAnnotations(
        diff: EffectTracker.DiffResult,
        keywordDiff: EffectTracker.KeywordDiffResult = EffectTracker.KeywordDiffResult(emptyList(), emptyList()),
        grantedAbilityDiff: EffectTracker.GrantedAbilityDiffResult =
            EffectTracker.GrantedAbilityDiffResult(emptyList(), emptyList()),
        grantedAbilitySourceInstanceId: ((ForgeCardId) -> InstanceId)? = null,
        keywordAffectorFallbackForgeCardId: ForgeCardId? = null,
        keywordAffectorInstanceId: ((ForgeCardId) -> InstanceId)? = null,
        boostAffectorResolver: ((EffectTracker.TrackedEffect, GrpId?) -> InstanceId?)? = null,
        uniqueAbilityIdAllocator: (() -> Int)? = null,
        keywordExtraAbilityGrpIds: ((InstanceId, String) -> List<GrpId>)? = null,
    ): Pair<List<AnnotationInfo>, List<AnnotationInfo>> {
        val hasBoosts = diff.created.isNotEmpty() || diff.destroyed.isNotEmpty()
        val hasKeywords = keywordDiff.created.isNotEmpty() || keywordDiff.destroyed.isNotEmpty()
        val hasGrantedAbilities = grantedAbilityDiff.created.isNotEmpty() || grantedAbilityDiff.destroyed.isNotEmpty()
        if (!hasBoosts && !hasKeywords && !hasGrantedAbilities) {
            return emptyList<AnnotationInfo>() to emptyList()
        }

        val transient = mutableListOf<AnnotationInfo>()
        val persistent = mutableListOf<AnnotationInfo>()

        addBoostEffectAnnotations(
            transient,
            persistent,
            diff,
            boostAffectorResolver,
        )
        addKeywordEffectAnnotations(
            transient,
            persistent,
            keywordDiff,
            keywordAffectorFallbackForgeCardId,
            keywordAffectorInstanceId,
            uniqueAbilityIdAllocator,
            keywordExtraAbilityGrpIds,
        )
        addGrantedAbilityEffectAnnotations(transient, persistent, grantedAbilityDiff, grantedAbilitySourceInstanceId)

        return transient to persistent
    }

    private fun addGrantedAbilityEffectAnnotations(
        transient: MutableList<AnnotationInfo>,
        persistent: MutableList<AnnotationInfo>,
        diff: EffectTracker.GrantedAbilityDiffResult,
        sourceInstanceId: ((ForgeCardId) -> InstanceId)?,
    ) {
        for (effect in diff.created) {
            val affectedId = InstanceId(effect.cardInstanceId)
            val affectorId = effect.sourceForgeCardId?.let { sourceInstanceId?.invoke(it) } ?: affectedId
            val effectId = EffectId(effect.syntheticId)
            transient.add(AnnotationBuilder.layeredEffectCreated(effectId, affectorId))
            persistent.add(
                AnnotationBuilder.addAbilityLayered(
                    affectedId = affectedId,
                    grpId = GrpId(effect.abilityGrpId),
                    effectId = effectId,
                    uniqueAbilityId = effect.uniqueAbilityId,
                    originalAbilityObjectZcid = affectorId.value,
                    affectorId = affectorId,
                ),
            )
        }
        for (effect in diff.destroyed) {
            transient.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effect.syntheticId)))
        }
    }

    private fun addBoostEffectAnnotations(
        transient: MutableList<AnnotationInfo>,
        persistent: MutableList<AnnotationInfo>,
        diff: EffectTracker.DiffResult,
        boostAffectorResolver: ((EffectTracker.TrackedEffect, GrpId?) -> InstanceId?)?,
    ) {
        for (effect in diff.created) {
            val sourceAbilityGrpId = effect.sourceAbilityGrpId?.takeUnless { effect.sourceForgeCardId != null }?.let(::GrpId)
            val cardIid = InstanceId(effect.cardInstanceId)
            val affectedIids = effect.affectedCardInstanceIds.map(::InstanceId)
            val effectId = EffectId(effect.syntheticId)
            val affectorId = boostAffectorResolver?.invoke(effect, sourceAbilityGrpId) ?: cardIid

            transient.add(AnnotationBuilder.layeredEffectCreated(effectId, affectorId))
            if (effect.powerDelta != 0 || effect.toughnessDelta != 0) {
                for (affectedIid in affectedIids) {
                    transient.add(
                        AnnotationBuilder.powerToughnessModCreated(
                            instanceId = affectedIid,
                            power = effect.powerDelta,
                            toughness = effect.toughnessDelta,
                            affectorId = affectorId,
                        ),
                    )
                }
            }
            persistent.add(
                AnnotationBuilder.layeredEffect(
                    instanceId = cardIid,
                    effectId = effectId,
                    powerDelta = effect.powerDelta,
                    toughnessDelta = effect.toughnessDelta,
                    affectorId = affectorId,
                    sourceAbilityGrpId = sourceAbilityGrpId,
                    affectedInstanceIds = affectedIids,
                ),
            )
        }

        for (effect in diff.destroyed) {
            transient.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effect.syntheticId)))
        }
    }

    private fun addKeywordEffectAnnotations(
        transient: MutableList<AnnotationInfo>,
        persistent: MutableList<AnnotationInfo>,
        keywordDiff: EffectTracker.KeywordDiffResult,
        keywordAffectorFallbackForgeCardId: ForgeCardId?,
        keywordAffectorInstanceId: ((ForgeCardId) -> InstanceId)?,
        uniqueAbilityIdAllocator: (() -> Int)?,
        keywordExtraAbilityGrpIds: ((InstanceId, String) -> List<GrpId>)?,
    ) {
        if (keywordDiff.created.isNotEmpty() && uniqueAbilityIdAllocator != null) {
            val groups =
                keywordDiff.created
                    .groupBy { Triple(it.keyword, it.fingerprint.timestamp, it.fingerprint.staticId) }
            for ((key, effects) in groups) {
                val keyword = key.first
                val grpId = GrpId(KeywordGrpIds.forKeyword(keyword) ?: continue)
                val affectorForgeCardId =
                    effects.firstNotNullOfOrNull { it.affectorForgeCardId }
                        ?: keywordAffectorFallbackForgeCardId
                val affectorId = affectorForgeCardId?.let { keywordAffectorInstanceId?.invoke(it) } ?: InstanceId(0)
                addCreatedKeywordEffectAnnotations(
                    transient,
                    persistent,
                    effects,
                    grpId,
                    affectorId,
                    uniqueAbilityIdAllocator,
                    keywordExtraAbilityGrpIds,
                )
            }
        }

        for (effect in keywordDiff.destroyed) {
            if (KeywordGrpIds.forKeyword(effect.keyword) == null) continue
            transient.add(AnnotationBuilder.layeredEffectDestroyed(EffectId(effect.syntheticId)))
        }
    }

    private fun addCreatedKeywordEffectAnnotations(
        transient: MutableList<AnnotationInfo>,
        persistent: MutableList<AnnotationInfo>,
        effects: List<EffectTracker.TrackedKeywordEffect>,
        grpId: GrpId,
        affectorId: InstanceId,
        uniqueAbilityIdAllocator: () -> Int,
        keywordExtraAbilityGrpIds: ((InstanceId, String) -> List<GrpId>)?,
    ) {
        val keyword = effects.first().keyword
        val effectsWithExtras =
            effects.map { effect ->
                effect to keywordExtraAbilityGrpIds?.invoke(InstanceId(effect.cardInstanceId), keyword).orEmpty()
            }

        if (effectsWithExtras.all { (_, extraGrpIds) -> extraGrpIds.isEmpty() }) {
            addSharedKeywordEffectAnnotations(
                transient,
                persistent,
                keyword,
                effects,
                grpId,
                affectorId,
                uniqueAbilityIdAllocator,
            )
            return
        }

        for ((effect, extraGrpIds) in effectsWithExtras) {
            val effectId = EffectId(effect.syntheticId)
            val creatureIid = InstanceId(effect.cardInstanceId)
            val grpIds = listOf(grpId) + extraGrpIds
            transient.add(
                AnnotationBuilder.layeredEffectCreated(
                    effectId,
                    if (affectorId.value != 0) affectorId else null,
                ),
            )
            persistent.add(
                AnnotationBuilder.addAbilityPacked(
                    affectedId = creatureIid,
                    grpIds = grpIds,
                    effectId = effectId,
                    uniqueAbilityIds = grpIds.map { uniqueAbilityIdAllocator() },
                    originalAbilityObjectZcids = grpIds.map { affectorId.value },
                    affectorId = affectorId,
                ),
            )
            log.debug(
                "effectAnnotations: keyword grant {} grpIds={} effectId={} iid={}",
                keyword,
                grpIds.map { it.value },
                effectId.value,
                creatureIid.value,
            )
        }
    }

    private fun addSharedKeywordEffectAnnotations(
        transient: MutableList<AnnotationInfo>,
        persistent: MutableList<AnnotationInfo>,
        keyword: String,
        effects: List<EffectTracker.TrackedKeywordEffect>,
        grpId: GrpId,
        affectorId: InstanceId,
        uniqueAbilityIdAllocator: () -> Int,
    ) {
        val effectId = EffectId(effects.first().syntheticId)
        transient.add(
            AnnotationBuilder.layeredEffectCreated(
                effectId,
                if (affectorId.value != 0) affectorId else null,
            ),
        )

        val creatureIids = effects.map { InstanceId(it.cardInstanceId) }
        persistent.add(
            AnnotationBuilder.addAbilityMulti(
                affectedIds = creatureIids,
                grpId = grpId,
                effectId = effectId,
                uniqueAbilityIds = creatureIids.map { uniqueAbilityIdAllocator() },
                originalAbilityObjectZcid = affectorId.value,
                affectorId = affectorId,
            ),
        )

        log.debug(
            "effectAnnotations: keyword grant {} grpId={} effectId={} creatures={}",
            keyword,
            grpId.value,
            effectId.value,
            creatureIids.size,
        )
    }
}
