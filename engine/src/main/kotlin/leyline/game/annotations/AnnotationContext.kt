package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.codes.CounterTypes
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.mapping.StateProjectionEnvironment
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.ConvokePayment
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.ProjectionState
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.SyntheticEffectProjection
import leyline.game.state.TargetSpec

/**
 * Bundle of the shared annotation-time resolvers used across the
 * [AnnotationPipeline] spine and (after the port slice) the contributors.
 *
 * Holds the private projection editor, immutable frame snapshot, scoped identities, ordered
 * events, and cut-specific fact values used by annotation resolvers. Call sites
 * can read `ctx.counterAffectorFor(...)` without threading the full projection
 * input. [transferResult] is optional frame context for contributors that need
 * this frame's pre-reallocation identities or zone transfers.
 *
 * The frame-pure helpers ([stackAbilityIid], [keywordCounterResolutionForEvent])
 * are also exposed on the companion for callers that hold only a
 * [FrameIdResolver] / event list (e.g. transfer-model patchers in StateMapper
 * and the test harness).
 */
class AnnotationContext(
    val editor: ProjectionState.Editor,
    val environment: StateProjectionEnvironment,
    val snap: GsmSnapshot,
    val frameIds: FrameIdResolver,
    val events: List<GameEvent>,
    val promptFacts: PromptProjectionFacts = PromptProjectionFacts(),
    val effectFacts: EffectProjectionFacts = EffectProjectionFacts(),
    val mechanicSourceFacts: MechanicSourceFacts = MechanicSourceFacts(),
    val abilityExhaustionFacts: AbilityExhaustionFacts,
    val opponentKnowledge: List<InstanceId> = emptyList(),
    val transferResult: TransferResult? = null,
) {
    /** Private synthetic-effect planner for this tentative projection. */
    internal val effects: SyntheticEffectProjection.Planner get() = editor.effects

    /**
     * Convoke payments still pending per cast spell this frame, keyed by source
     * card. Feeds mechanic-annotation resolution; the Convoke resolve emission
     * consumes the journal entries separately.
     */
    fun activeConvokePaymentsBySource(): Map<ForgeCardId, List<TransferAnnotations.ConvokePaymentRecord>> =
        events
            .filterIsInstance<GameEvent.SpellCast>()
            .filterNot { it.isAbility }
            .mapNotNull { ev ->
                val payments =
                    promptFacts.convokePayments
                        .firstOrNull { it.key.seatId == ev.seatId && it.sourceForgeCardId == ev.cardId }
                        ?.payments
                        .orEmpty()
                if (payments.isEmpty()) {
                    null
                } else {
                    ev.cardId to payments.map { it.toConvokePaymentRecord() }
                }
            }.toMap()

    private fun ConvokePayment.toConvokePaymentRecord(): TransferAnnotations.ConvokePaymentRecord =
        TransferAnnotations.ConvokePaymentRecord(
            paymentForgeCardId = paymentForgeCardId,
            color = color,
            substitutionGrpId = substitutionGrpId,
            paymentAbilityGrpId = paymentAbilityGrpId,
        )

    /** Affector iid for a `CountersChanged` event, or null when none resolves. */
    fun counterAffectorFor(
        eventIndex: Int,
        ev: GameEvent.CountersChanged,
    ): InstanceId? {
        if (ev.affectorAbilityForgeId != 0 && ev.affectorCardId != null) {
            return InstanceId(stackAbilityIid(ev.affectorAbilityForgeId, ev.affectorCardId))
        }
        val resolved =
            keywordCounterResolutionForEvent(eventIndex, ev, events) { resolved ->
                isCounterAffectingKeywordResolution(resolved) || isCounterAffectingAbilityWordResolution(resolved)
            } ?: return null
        return InstanceId(stackAbilityIid(resolved.abilityForgeId, resolved.cardId))
    }

    private fun isCounterAffectingKeywordResolution(resolved: GameEvent.SpellResolved): Boolean {
        if (resolved.abilityGrpId in counterAffectingKeywordTriggerIds) return true
        val sourceGrpId = snap.boundCards[resolved.cardId]?.snapshot?.grpId ?: return false
        return environment.cardReferences.isBackupAbility(sourceGrpId, resolved.abilityGrpId)
    }

    private fun isCounterAffectingAbilityWordResolution(resolved: GameEvent.SpellResolved): Boolean =
        events.filterIsInstance<GameEvent.SpellCast>().any { cast ->
            cast.abilityForgeId == resolved.abilityForgeId &&
                cast.cardId == resolved.cardId &&
                (cast.opusTrigger || cast.voidTrigger)
        }

    /** Affector iid for a `PlayerCountersChanged` event, or null when none resolves. */
    fun playerCounterAffectorFor(
        @Suppress("UNUSED_PARAMETER") eventIndex: Int,
        ev: GameEvent.PlayerCountersChanged,
    ): InstanceId? {
        if (CounterTypes.counterTypeId(ev.counterType) == 0) return null
        val sourceCardId = ev.sourceCardId ?: return null
        // CounterProduced animation resolves its source against the current game
        // state. Its card view can be on the stack, in limbo, or another visible
        // zone; the client decides whether that view has an animation transform.
        if (snap.boundCards[sourceCardId]?.snapshot?.isProjectable == true) return frameIds.cardIid(sourceCardId)
        val liveStackAbility =
            snap.stack.entries.firstOrNull {
                !it.isSpell && it.forgeCardId == sourceCardId && it.forgeAbilityId == ev.sourceAbilityForgeId
            }
        return liveStackAbility?.let { InstanceId(stackAbilityIid(it.forgeAbilityId, sourceCardId)) }
    }

    /** Instance-scoped surrogate iid for a stack-resident trigger / activated ability. */
    fun stackAbilityIid(
        forgeAbilityId: Int,
        sourceForgeId: ForgeCardId,
    ): Int = stackAbilityIid(forgeAbilityId, sourceForgeId, frameIds)

    /** Source-card fallback for synthetic events without resolved ability identity. */
    fun abilityGrpIdForSource(cardId: ForgeCardId): Int {
        val bound = snap.boundCards[cardId] ?: return 0
        return bound.snapshot.grpId
    }

    fun targetSpecAbilityGrpId(spec: TargetSpec): Int {
        if (spec.promptId == PromptIds.MUTATE_TARGET) return 0
        spec.abilityIdentity
            ?.abilityGrpId
            ?.takeIf { it != 0 }
            ?.let { return it }
        return environment.cardReferences.targetSpecAbilityGrpId(spec.spellName)
    }

    /** Resolve the stack object for a completed non-spell target group. */
    fun targetSpecStackAbilityIid(spec: TargetSpec): InstanceId {
        val sourceCardId = ForgeCardId(spec.spellForgeCardId)
        val abilityGrpId = targetSpecAbilityGrpId(spec)
        val stackEntries = snap.stack.entries.filter { !it.isSpell && it.forgeCardId == sourceCardId }
        val stackEntry =
            stackEntries.firstOrNull { spec.forgeAbilityId != 0 && it.forgeAbilityId == spec.forgeAbilityId }
                ?: stackEntries.firstOrNull { abilityGrpId != 0 && it.grpId == abilityGrpId }
                ?: stackEntries.singleOrNull()
        val resolvedEvents =
            events
                .filterIsInstance<GameEvent.SpellResolved>()
                .filter { it.cardId == sourceCardId }
        val resolvedEvent =
            resolvedEvents.lastOrNull { spec.forgeAbilityId != 0 && it.abilityForgeId == spec.forgeAbilityId }
                ?: resolvedEvents.lastOrNull { abilityGrpId == 0 || it.abilityGrpId == abilityGrpId }
        val stackForgeAbilityId = stackEntry?.forgeAbilityId ?: resolvedEvent?.abilityForgeId ?: spec.forgeAbilityId
        return frameIds.triggerStackAbilityIid(stackForgeAbilityId)
    }

    companion object {
        private val counterAffectingKeywordTriggerIds =
            setOf(KeywordAbilityIds.BACKUP, KeywordAbilityIds.MENTOR, KeywordAbilityIds.TRAINING)

        /**
         * SA-id-keyed surrogate iid for a stack-resident trigger or activated
         * ability, with source-card fallback when the collector didn't surface
         * the SA id (defensive 0). Both lifecycle paths share this minter so a
         * single AB iid threads through Created → ZoneTransfer affector → Deleted.
         */
        fun stackAbilityIid(
            forgeAbilityId: Int,
            sourceForgeId: ForgeCardId,
            frameIds: FrameIdResolver,
        ): Int =
            if (forgeAbilityId != 0) {
                frameIds.triggerStackAbilityIid(forgeAbilityId).value
            } else {
                frameIds.stackAbilityIid(sourceForgeId).value
            }

        fun keywordCounterResolutionForEvent(
            eventIndex: Int,
            ev: GameEvent.CountersChanged,
            events: List<GameEvent>,
            isCounterAffectingResolution: (GameEvent.SpellResolved) -> Boolean = { resolved ->
                resolved.abilityGrpId in counterAffectingKeywordTriggerIds
            },
        ): GameEvent.SpellResolved? {
            if (ev.counterType != "P1P1" && ev.counterType != "+1/+1") return null
            for (next in events.asSequence().drop(eventIndex + 1)) {
                when {
                    next is GameEvent.CountersChanged -> return null
                    next is GameEvent.SpellResolved -> {
                        if (next.isTrigger && isCounterAffectingResolution(next)) {
                            return next
                        }
                        return null
                    }
                }
            }
            return null
        }
    }
}

internal fun GameEvent.SpellCast.isParadigmDelayedTrigger(): Boolean =
    isTrigger && abilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER

internal fun GameEvent.SpellResolved.isParadigmDelayedTrigger(): Boolean =
    isTrigger && abilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER
