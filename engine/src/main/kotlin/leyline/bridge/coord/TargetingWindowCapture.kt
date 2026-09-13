package leyline.bridge.coord

import forge.game.GameEntity
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.TargetingCandidateValue
import leyline.bridge.handoff.TargetingWindowValue
import leyline.bridge.types.AbilityKeywordFamily
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.SnapshotCapture
import leyline.game.state.ProjectionState

/** Engine-thread capture and legality for one immutable targeting window. */
internal class TargetingWindowCapture(
    private val owner: MatchCutCoordinator,
    private val runtimeSeat: leyline.bridge.types.SeatId = owner.humanSeat,
) {
    fun capture(
        request: PromptRequest,
        targetingAbility: SpellAbility?,
        abilityIdentity: ResolvedAbilityIdentity?,
    ): TargetingWindowValue {
        val sourceId = request.sourceEntityId?.let(::ForgeCardId)
        val sourceCard =
            sourceId?.let { id ->
                owner.bridge.findCard(id) ?: targetingAbility?.hostCard?.takeIf { it.id == id.value }
            }
        val sourceGrpId = sourceCard?.let(owner.bridge::resolveGrpId) ?: 0
        val defaultTargetingGrpId =
            abilityIdentity?.abilityGrpId?.takeIf { it != 0 }
                ?: owner.bridge.cardRepository
                    .findByGrpId(sourceGrpId)
                    ?.abilityIds
                    ?.firstOrNull { (grpId, _) ->
                        owner.bridge.cardRepository
                            .findAbilityInfo(grpId)
                            ?.category == 4
                    }?.first
                ?: owner.bridge.cardRepository
                    .findByGrpId(sourceGrpId)
                    ?.abilityIds
                    ?.firstOrNull()
                    ?.first
                ?: 0
        val shape = targetShape(targetingAbility, abilityIdentity, sourceGrpId, defaultTargetingGrpId)
        return TargetingWindowValue(
            sourceForgeCardId = sourceId,
            sourceGrpId = sourceGrpId,
            outerAbilityGrpId = shape.outerAbilityGrpId,
            targetingAbilityGrpId = shape.targetingAbilityGrpId,
            targetSourceZoneId = shape.targetSourceZoneId.takeIf { it != 0 } ?: candidateSourceZoneId(request),
            targetPromptId = shape.promptId ?: request.targetPromptId,
            targetIndex = request.targetIndex,
            minTargets = request.min,
            maxTargets = request.max,
            chooserSeatId = runtimeSeat,
            finishOptionIndex = request.targetingFinishOptionIndex,
            candidates =
                if (request.targetingCandidates.isNotEmpty()) {
                    request.targetingCandidates
                } else {
                    request.candidateRefs.mapNotNull { ref ->
                        when (ref.kind) {
                            PromptCandidateKind.Card ->
                                TargetingCandidateValue.Card(
                                    ref.index,
                                    ForgeCardId(ref.entityId),
                                    zoneId(ref.zone, cardOwnerSeat(ForgeCardId(ref.entityId))),
                                )
                            PromptCandidateKind.Player ->
                                playerSeat(ref.entityId)?.let { TargetingCandidateValue.Player(ref.index, it) }
                        }
                    }
                },
            isTriggeredAbility = request.isTriggeredAbility,
            forgeAbilityId = targetingAbility?.id ?: request.forgeAbilityId,
            isActivatedAbility = targetingAbility?.rootAbility?.isActivatedAbility == true,
            stackAbilityGrpId = abilityIdentity?.abilityGrpId ?: 0,
        )
    }

    fun captureTransientSourceCard(
        value: TargetingWindowValue,
        targetingAbility: SpellAbility?,
    ) = value.sourceForgeCardId
        ?.let { id -> owner.bridge.findCard(id) ?: targetingAbility?.hostCard?.takeIf { it.id == id.value } }
        ?.let { SnapshotCapture.captureBoundCard(it, checkNotNull(owner.bridge.getGame()), owner.bridge) }

    fun resolveEntities(value: TargetingWindowValue): Map<Int, GameEntity> =
        value.candidates
            .mapNotNull { candidate ->
                val entity =
                    when (candidate) {
                        is TargetingCandidateValue.Card -> owner.bridge.findCard(candidate.forgeCardId)
                        is TargetingCandidateValue.Player -> owner.bridge.getPlayer(candidate.seatId)
                        is TargetingCandidateValue.StackObject -> null
                    }
                entity?.let { candidate.optionIndex to it }
            }.toMap()

    fun resolveStackAbilities(value: TargetingWindowValue): Map<Int, SpellAbility> =
        value.candidates
            .mapNotNull { candidate ->
                if (candidate !is TargetingCandidateValue.StackObject) return@mapNotNull null
                owner.bridge
                    .getGame()
                    ?.stack
                    ?.firstOrNull { it.id == candidate.stackInstanceId }
                    ?.spellAbility
                    ?.let { candidate.optionIndex to it }
            }.toMap()

    fun resolveInstanceIds(
        value: TargetingWindowValue,
        projection: ProjectionState,
    ): Map<Int, Int> =
        value.candidates
            .mapNotNull { candidate ->
                val instanceId =
                    when (candidate) {
                        is TargetingCandidateValue.Card -> projection.identities.forgeIdToInstanceId[candidate.forgeCardId]?.value
                        is TargetingCandidateValue.Player -> candidate.seatId.value
                        is TargetingCandidateValue.StackObject ->
                            projection.identities.forgeIdToInstanceId[
                                if (candidate.isSpell) {
                                    candidate.sourceForgeCardId
                                } else {
                                    leyline.game.mapping.FrameIdResolver
                                        .triggerStackAbilityForgeId(candidate.forgeAbilityId)
                                },
                            ]?.value
                    }
                instanceId?.let { candidate.optionIndex to it }
            }.toMap()

    fun legalOptions(
        value: TargetingWindowValue,
        ability: SpellAbility?,
        entitiesByOptionIndex: Map<Int, GameEntity>,
        stackAbilitiesByOptionIndex: Map<Int, SpellAbility>,
        selected: Set<Int>,
    ): Set<Int> {
        if (selected.size >= value.maxTargets) return emptySet()
        if (ability == null) {
            return value.candidates
                .filter { it !is TargetingCandidateValue.StackObject || it.optionIndex in stackAbilitiesByOptionIndex }
                .mapTo(linkedSetOf()) { it.optionIndex } - selected
        }
        val hypothetical = selected.mapNotNull(entitiesByOptionIndex::get)
        return value.candidates.mapNotNullTo(linkedSetOf()) { candidate ->
            if (candidate.optionIndex in selected) return@mapNotNullTo null
            if (candidate is TargetingCandidateValue.StackObject) {
                val stackAbility = stackAbilitiesByOptionIndex[candidate.optionIndex] ?: return@mapNotNullTo null
                return@mapNotNullTo candidate.optionIndex.takeIf { ability.canTargetSpellAbility(stackAbility) }
            }
            val entity = entitiesByOptionIndex[candidate.optionIndex] ?: return@mapNotNullTo candidate.optionIndex
            if (canTargetWithHypothetical(ability, entity, hypothetical)) candidate.optionIndex else null
        }
    }

    private data class TargetShape(
        val outerAbilityGrpId: Int,
        val targetingAbilityGrpId: Int,
        val promptId: Int? = null,
        val targetSourceZoneId: Int = 0,
    )

    private fun targetShape(
        ability: SpellAbility?,
        identity: ResolvedAbilityIdentity?,
        sourceGrpId: Int,
        defaultTargetingGrpId: Int,
    ): TargetShape =
        when (identity?.keywordFamily) {
            AbilityKeywordFamily.Mentor ->
                TargetShape(identity.abilityGrpId, KeywordAbilityIds.MENTOR, PromptIds.MENTOR_TARGET)
            AbilityKeywordFamily.Backup ->
                TargetShape(identity.abilityGrpId, KeywordAbilityIds.BACKUP)
            null ->
                if (ability?.isMutate == true) {
                    TargetShape(
                        KeywordAbilityIds.MUTATE,
                        owner.bridge.cardRepository.findKeywordAbilityGrpId(sourceGrpId, KeywordAbilityIds.MUTATE) ?: 0,
                        PromptIds.MUTATE_TARGET,
                        ZoneIds.BATTLEFIELD,
                    )
                } else {
                    TargetShape(sourceGrpId, defaultTargetingGrpId)
                }
        }

    private fun canTargetWithHypothetical(
        ability: SpellAbility,
        candidate: GameEntity,
        hypothetical: List<GameEntity>,
    ): Boolean {
        val original = ability.targets
        val clone = original.clone()
        hypothetical.forEach(clone::add)
        return try {
            ability.setTargets(clone)
            ability.canTarget(candidate)
        } finally {
            ability.setTargets(original)
        }
    }

    private fun candidateSourceZoneId(request: PromptRequest): Int =
        if (request.targetingCandidates.isNotEmpty()) {
            ZoneIds.STACK
        } else {
            request.candidateRefs
                .firstOrNull { it.kind == PromptCandidateKind.Card && it.zone != null }
                ?.let { zoneId(it.zone, cardOwnerSeat(ForgeCardId(it.entityId))) }
                ?: 0
        }

    private fun cardOwnerSeat(cardId: ForgeCardId): SeatId =
        owner.bridge.findCard(cardId)?.owner?.let { cardOwner ->
            if (cardOwner == owner.bridge.getPlayer(SeatId(1))) SeatId(1) else SeatId(2)
        } ?: runtimeSeat

    private fun playerSeat(entityId: Int): SeatId? = listOf(SeatId(1), SeatId(2)).firstOrNull { owner.bridge.getPlayer(it)?.id == entityId }

    private fun zoneId(
        zone: String?,
        ownerSeat: SeatId,
    ): Int =
        when (zone) {
            "Battlefield" -> ZoneIds.BATTLEFIELD
            "Exile" -> ZoneIds.EXILE
            "Stack" -> ZoneIds.STACK
            "Graveyard" -> ZoneIds.graveyardOf(ownerSeat)
            "Hand" -> ZoneIds.handOf(ownerSeat)
            "Library" -> ZoneIds.libraryOf(ownerSeat)
            else -> 0
        }
}
