package leyline.game.mapping

import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.PromptProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Value-only reduction for persistent ability-word annotation feeds. */
internal object PersistentAbilityWordFeedBuilder {
    fun build(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        prev: GsmSnapshot?,
        frameIds: FrameIdResolver,
        promptFacts: PromptProjectionFacts,
        facts: PersistentFeedFacts,
        references: ProjectionCardReferences,
    ): List<AnnotationInfo> {
        val scanned =
            snap.abilityWordEntries.map { entry ->
                val instanceId = entry.forgeCardId?.let(frameIds::cardIid)?.value ?: entry.instanceId
                AnnotationBuilder.abilityWordActive(
                    instanceId = InstanceId(instanceId),
                    abilityWordName = entry.abilityWordName,
                    value = entry.value,
                    threshold = entry.threshold,
                    abilityGrpId = entry.abilityGrpId?.let(::GrpId),
                    colors = entry.colors,
                    hasBlessing = entry.hasBlessing,
                    affectorId = InstanceId(entry.affectorId ?: instanceId),
                    affectedIds =
                        entry.affectedForgeCardIds
                            .map(frameIds::cardIid)
                            .ifEmpty { entry.affectedIds.ifEmpty { listOf(instanceId) }.map(::InstanceId) },
                )
            }
        val stackState =
            AbilityWordFeedMerger.merge(
                scanned +
                    OpusAbilityWordFeedBuilder.build(events, frameIds).filterNot(scanned::contains) +
                    VoidAbilityWordFeedBuilder.build(events, frameIds).filterNot(scanned::contains) +
                    ColorsSpentToCastFeedBuilder.build(events, frameIds).filterNot(scanned::contains),
            )
        return stackState +
            collectEvidence(frameIds, promptFacts, facts) +
            convoke(snap, frameIds, promptFacts) +
            training(events, snap, prev, frameIds, references)
    }

    private fun collectEvidence(
        frameIds: FrameIdResolver,
        promptFacts: PromptProjectionFacts,
        facts: PersistentFeedFacts,
    ): List<AnnotationInfo> {
        val activeKeys = promptFacts.collectEvidenceCosts.mapTo(hashSetOf()) { it.key }
        return facts.collectEvidence.mapNotNull { fact ->
            if (fact.key !in activeKeys) return@mapNotNull null
            AnnotationBuilder.abilityWordActive(
                instanceId = frameIds.cardIid(fact.sourceForgeCardId),
                abilityWordName = "CollectEvidenceCount",
                value = fact.graveyardManaValue,
                threshold = fact.threshold,
                abilityGrpId = GrpId(fact.abilityGrpId),
            )
        }
    }

    private fun convoke(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
        promptFacts: PromptProjectionFacts,
    ): List<AnnotationInfo> {
        val stackForgeIds =
            snap.zones[ZoneIds.STACK]
                ?.contents
                ?.toSet()
                .orEmpty()
        if (stackForgeIds.isEmpty()) return emptyList()
        return promptFacts.convokePayments.mapNotNull { fact ->
            if (fact.payments.isEmpty() || fact.sourceForgeCardId !in stackForgeIds) return@mapNotNull null
            AnnotationBuilder.abilityWordActive(
                instanceId = frameIds.cardIid(fact.sourceForgeCardId),
                abilityWordName = "ConvokeCount",
                value = fact.payments.size,
                abilityGrpId = GrpId(KeywordAbilityIds.CONVOKE),
            )
        }
    }

    private fun training(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        prev: GsmSnapshot?,
        frameIds: FrameIdResolver,
        references: ProjectionCardReferences,
    ): List<AnnotationInfo> {
        val attackEvents = events.filterIsInstance<GameEvent.AttackersDeclared>()
        if (attackEvents.isEmpty()) return emptyList()
        val powerSnap = prev ?: snap
        return attackEvents.flatMap { event ->
            event.attackerCardIds.mapNotNull { trainerId ->
                val trainer = powerSnap.boundCards[trainerId] ?: snap.boundCards[trainerId] ?: return@mapNotNull null
                if (!references.hasTraining(trainer.snapshot.grpId)) return@mapNotNull null
                val trainerPower = trainer.snapshot.netPower ?: return@mapNotNull null
                val partnerId =
                    event.attackerCardIds.firstOrNull { otherId ->
                        if (otherId == trainerId) return@firstOrNull false
                        val other = powerSnap.boundCards[otherId] ?: snap.boundCards[otherId] ?: return@firstOrNull false
                        (other.snapshot.netPower ?: Int.MIN_VALUE) > trainerPower
                    } ?: return@mapNotNull null
                val trainerIid = frameIds.cardIid(trainerId)
                AnnotationBuilder.abilityWordActive(
                    instanceId = trainerIid,
                    abilityWordName = "Training",
                    affectorId = trainerIid,
                    affectedIds = listOf(frameIds.cardIid(partnerId)),
                )
            }
        }
    }
}
