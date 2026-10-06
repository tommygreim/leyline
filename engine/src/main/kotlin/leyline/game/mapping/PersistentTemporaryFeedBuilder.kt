package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationConstants
import leyline.game.annotations.TransferCategory
import leyline.game.annotations.TransferResult
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.HolderRecord
import leyline.game.state.PersistentFeedFacts
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

internal data class TemporaryPermanentFeedResult(
    val temporaryPermanent: List<AnnotationInfo>,
    val delayedTriggerAffectees: List<AnnotationInfo>,
    val currentHolders: List<HolderRecord>,
)

/** Value-only reduction for delayed cleanup feeds and holder state. */
internal object PersistentTemporaryFeedBuilder {
    fun build(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
        decayedCleanupSourcesThisGsm: Set<ForgeCardId>,
        transferResult: TransferResult,
        facts: PersistentFeedFacts,
        references: ProjectionCardReferences,
    ): TemporaryPermanentFeedResult {
        val eotTokens = snap.objects.values.filter { it.isOnBattlefield && it.endOfTurnLeavePlay }
        val tokenSources = facts.endStepTokenSources.associate { it.tokenForgeCardId to it.sourceForgeCardId }
        val decayedHolders = decayedHolders(decayedCleanupSourcesThisGsm, snap, frameIds, transferResult, references)
        val pending = pendingTriggers(snap, frameIds)
        val evoked =
            snap.boundCards.values.mapNotNull { bound ->
                if (!bound.snapshot.evokePaid) return@mapNotNull null
                val abilityGrpId =
                    bound.snapshot.grantedCastAbilityGrpId
                        ?: bound.altCost(KeywordAbilityIds.EVOKE)?.abilityGrpId ?: return@mapNotNull null
                val instanceId = frameIds.cardIid(bound.forgeCardId)
                AnnotationBuilder.temporaryPermanent(instanceId, GrpId(abilityGrpId))
            }
        val temporary =
            pending.temporaryPermanent +
                evoked +
                eotTokens.map { token ->
                    val tokenIid = frameIds.cardIid(token.forgeCardId)
                    val sourceForgeId = tokenSources[token.forgeCardId]
                    val mobilize =
                        sourceForgeId?.let { source ->
                            snap.boundCards[source]?.mobilizeCleanup?.let { source to it }
                        }
                    val affector =
                        mobilize?.let { frameIds.cardIid(FrameIdResolver.delayedTriggerHolderForgeId(it.first)) } ?: tokenIid
                    AnnotationBuilder.temporaryPermanent(
                        affectedInstanceId = tokenIid,
                        abilityGrpId = mobilize?.second?.let(::GrpId) ?: AnnotationConstants.EOT_SACRIFICE_GRP_ID,
                        affectorId = affector,
                    )
                } +
                decayedHolders.map { holder ->
                    AnnotationBuilder.temporaryPermanent(
                        affectedInstanceId = InstanceId(holder.parentIid),
                        abilityGrpId = GrpId(holder.cleanupGrpId),
                        affectorId = InstanceId(holder.iid),
                    )
                }
        val currentHolders = pending.currentHolders.toMutableList().apply { addAll(decayedHolders) }
        val delayedAffectees =
            pending.delayedTriggerAffectees +
                eotTokens
                    .groupBy { tokenSources[it.forgeCardId] to it.controller.value }
                    .mapNotNull { (key, tokens) ->
                        val (sourceForgeId, seat) = key
                        sourceForgeId ?: return@mapNotNull null
                        val bound = snap.boundCards[sourceForgeId] ?: return@mapNotNull null
                        val cleanupGrpId = bound.mobilizeCleanup ?: return@mapNotNull null
                        val holderIid = frameIds.cardIid(FrameIdResolver.delayedTriggerHolderForgeId(sourceForgeId))
                        currentHolders +=
                            HolderRecord(
                                iid = holderIid.value,
                                ownerSeat = seat,
                                objectSourceGrpId = bound.altCost(KeywordAbilityIds.MOBILIZE)?.abilityGrpId ?: 0,
                                parentIid = frameIds.cardIid(sourceForgeId).value,
                                cleanupGrpId = cleanupGrpId,
                            )
                        AnnotationBuilder.delayedTriggerAffectees(
                            triggerHolderId = holderIid,
                            tokenInstanceIds = tokens.map { frameIds.cardIid(it.forgeCardId) },
                            abilityGrpId = GrpId(cleanupGrpId),
                        )
                    }
        return TemporaryPermanentFeedResult(temporary, delayedAffectees, currentHolders)
    }

    private fun pendingTriggers(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): TemporaryPermanentFeedResult {
        val holders =
            snap.pendingTriggers.map { pending ->
                HolderRecord(
                    iid = frameIds.cardIid(pending.holderForgeId).value,
                    ownerSeat = pending.owner.value,
                    objectSourceGrpId = pending.holderObjectSourceGrpId,
                    parentIid = pending.parentInstanceId,
                    cleanupGrpId = pending.cleanupAbilityGrpId,
                    sourceForgeCardId = pending.sourceForgeCardId,
                    runtimeTriggerId = pending.runtimeTriggerId,
                )
            }
        val affectees =
            snap.pendingTriggers.filter { it.affectedCardIds.isNotEmpty() }.map { pending ->
                AnnotationBuilder.delayedTriggerAffectees(
                    triggerHolderId = frameIds.cardIid(pending.holderForgeId),
                    tokenInstanceIds = pending.affectedCardIds.map(frameIds::cardIid),
                    abilityGrpId = GrpId(pending.cleanupAbilityGrpId),
                    removesFromZone = pending.removesFromZone,
                )
            }
        val temporary =
            snap.pendingTriggers.filter { it.emitsTemporaryPermanent }.flatMap { pending ->
                val holderIid = frameIds.cardIid(pending.holderForgeId)
                pending.affectedCardIds.map { affected ->
                    AnnotationBuilder.temporaryPermanent(
                        affectedInstanceId = frameIds.cardIid(affected),
                        abilityGrpId = GrpId(pending.cleanupAbilityGrpId),
                        affectorId = holderIid,
                    )
                }
            }
        return TemporaryPermanentFeedResult(temporary, affectees, holders)
    }

    private fun decayedHolders(
        sourceForgeIds: Set<ForgeCardId>,
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
        transferResult: TransferResult,
        references: ProjectionCardReferences,
    ): List<HolderRecord> =
        sourceForgeIds.mapNotNull { sourceForgeId ->
            val bound = snap.boundCards[sourceForgeId]
            val transfer = transferResult.transfers.firstOrNull { it.forgeCardId == sourceForgeId }
            val sourceIid = decayedSourceIid(sourceForgeId, bound?.snapshot, frameIds, transferResult) ?: return@mapNotNull null
            val cleanupGrpId = decayedCleanupGrpIdForSource(sourceForgeId, snap, references, transferResult) ?: return@mapNotNull null
            HolderRecord(
                iid = frameIds.cardIid(FrameIdResolver.delayedTriggerHolderForgeId(sourceForgeId)).value,
                ownerSeat = bound?.snapshot?.controller?.value ?: transfer?.ownerSeatId ?: return@mapNotNull null,
                objectSourceGrpId = KeywordAbilityIds.DECAYED,
                parentIid = sourceIid,
                cleanupGrpId = cleanupGrpId,
            )
        }

    private fun decayedSourceIid(
        sourceForgeId: ForgeCardId,
        source: CardSnapshot?,
        frameIds: FrameIdResolver,
        transferResult: TransferResult,
    ): Int? {
        if (source?.isOnBattlefield == true) return frameIds.cardIid(sourceForgeId).value
        return transferResult.transfers
            .firstOrNull { it.forgeCardId == sourceForgeId && it.category == TransferCategory.Sacrifice }
            ?.origId
    }

    fun decayedCleanupGrpIdForSource(
        sourceForgeId: ForgeCardId,
        snap: GsmSnapshot,
        references: ProjectionCardReferences,
        transferResult: TransferResult? = null,
    ): Int? {
        snap.boundCards[sourceForgeId]?.decayedCleanup?.let { return it }
        val grpId = transferResult?.transfers?.firstOrNull { it.forgeCardId == sourceForgeId }?.grpId ?: return null
        return references.decayedCleanupGrpId(grpId)
    }
}
