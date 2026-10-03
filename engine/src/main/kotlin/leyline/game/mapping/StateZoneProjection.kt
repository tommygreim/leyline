package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.snapshot.EarthbendProjection
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.EffectTracker
import wotc.mtgo.gre.external.messaging.Messages.DeckConstraintInfo
import wotc.mtgo.gre.external.messaging.Messages.GameInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameStage
import wotc.mtgo.gre.external.messaging.Messages.GameType
import wotc.mtgo.gre.external.messaging.Messages.GameVariant
import wotc.mtgo.gre.external.messaging.Messages.MatchState
import wotc.mtgo.gre.external.messaging.Messages.MatchWinCondition
import wotc.mtgo.gre.external.messaging.Messages.MulliganType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo

/** Snapshot-complete semantic reducer for ordinary state and shared-zone projection. */
object StateZoneProjection {
    data class SharedZoneProjection(
        val zone: ZoneInfo,
        val gameObjects: List<GameObjectInfo>,
    )

    data class ZoneTransferCardFacts(
        val grpId: Int,
        val basicLandManaAbilityGrpId: Int,
        val isForetold: Boolean,
        val effectSourceForgeCardId: ForgeCardId?,
    )

    class ZoneTransferFacts internal constructor(
        private val cards: Map<ForgeCardId, ZoneTransferCardFacts>,
    ) {
        fun card(forgeCardId: ForgeCardId): ZoneTransferCardFacts? = cards[forgeCardId]

        fun contains(forgeCardId: ForgeCardId): Boolean = forgeCardId in cards
    }

    fun buildGameInfo(
        matchId: String,
        config: MatchProjectionConfig,
    ): GameInfo {
        val isBrawl = config.isBrawlOrCommander
        val builder =
            GameInfo
                .newBuilder()
                .setMatchID(matchId)
                .setGameNumber(1)
                .setStage(GameStage.Play_a920)
                .setType(GameType.Duel)
                .setVariant(if (isBrawl) GameVariant.Brawl else GameVariant.Normal)
                .setMatchState(MatchState.GameInProgress)
                .setMatchWinCondition(MatchWinCondition.SingleElimination)
                .setMulliganType(MulliganType.London)
        if (isBrawl) {
            builder.setDeckConstraintInfo(
                DeckConstraintInfo
                    .newBuilder()
                    .setMinDeckSize(58)
                    .setMaxDeckSize(59)
                    .setMaxSideboardSize(1)
                    .setMinCommanderSize(1)
                    .setMaxCommanderSize(1),
            )
            builder.setFreeMulliganCount(1)
        }
        return builder.build()
    }

    fun hasSeat(
        snap: GsmSnapshot,
        seatId: SeatId,
    ): Boolean = snap.seats.any { it.seatId == seatId }

    fun projectSharedZone(
        snap: GsmSnapshot,
        arenaZoneId: Int,
        environment: StateProjectionEnvironment,
        instanceIdLookup: (ForgeCardId) -> InstanceId,
        keywordSnapshot: Map<Int, List<EffectTracker.KeywordEntry>> = emptyMap(),
        earthbendProjection: (ForgeCardId) -> EarthbendProjection? = { null },
        grantedAbilitySnapshot: Map<Int, List<EffectTracker.TrackedGrantedAbility>> = emptyMap(),
    ): SharedZoneProjection? {
        val originalZone = snap.zones[arenaZoneId] ?: return null
        val zoneBuilder =
            ZoneInfo
                .newBuilder()
                .setZoneId(originalZone.id)
                .setType(originalZone.type)
                .setVisibility(originalZone.visibility)
                .also { builder -> originalZone.owner?.let { builder.setOwnerSeatId(it.value) } }
        val gameObjects = mutableListOf<GameObjectInfo>()
        val selectedModalAbilityGrpIds =
            if (arenaZoneId == ZoneIds.STACK) {
                snap.stack.entries
                    .filter { it.isSpell && it.selectedModalAbilityGrpIds.isNotEmpty() }
                    .associate { it.forgeCardId to it.selectedModalAbilityGrpIds }
            } else {
                emptyMap()
            }
        for (forgeCardId in originalZone.contents) {
            val bound = snap.boundCards[forgeCardId] ?: continue
            val card = bound.snapshot
            if (!card.isProjectable) continue
            val instanceId = instanceIdLookup(forgeCardId).value
            zoneBuilder.addObjectInstanceIds(instanceId)
            gameObjects +=
                ObjectMapper
                    .buildFromSnapshot(
                        card,
                        instanceId,
                        arenaZoneId,
                        card.owner.value,
                        environment.cardProto,
                        if (card.isFaceDownExile) Visibility.Private else Visibility.Public,
                        keywordSnapshot,
                        parentLinkage = bound.parentLinkage,
                        earthbend = earthbendProjection(forgeCardId),
                        grantedAbilitySnapshot = grantedAbilitySnapshot,
                        abilityGrpIdsOverride = selectedModalAbilityGrpIds[forgeCardId],
                    ).let { obj ->
                        if (card.isFaceDownExile && card.mayLookSeatIds.isNotEmpty()) {
                            obj.toBuilder().addAllViewers(card.mayLookSeatIds.map { it.value }).build()
                        } else {
                            obj
                        }
                    }
        }
        return SharedZoneProjection(zoneBuilder.build(), gameObjects.toList())
    }

    fun zoneTransferFacts(snap: GsmSnapshot): ZoneTransferFacts {
        val cards =
            snap.boundCards
                .mapValuesTo(linkedMapOf()) { (_, bound) ->
                    val card = bound.snapshot
                    ZoneTransferCardFacts(
                        grpId = card.grpId,
                        basicLandManaAbilityGrpId = card.basicLandManaAbilityGrpId,
                        isForetold = card.isForetold,
                        effectSourceForgeCardId = card.effectSourceForgeCardId,
                    )
                }
        for (entry in snap.stack.entries) {
            val existing = cards[entry.forgeCardId]
            cards[entry.forgeCardId] =
                existing?.copy(
                    effectSourceForgeCardId = existing.effectSourceForgeCardId ?: entry.effectSourceForgeCardId,
                ) ?: ZoneTransferCardFacts(
                    grpId = entry.sourceCardGrpId,
                    basicLandManaAbilityGrpId = 0,
                    isForetold = false,
                    effectSourceForgeCardId = entry.effectSourceForgeCardId,
                )
        }
        return ZoneTransferFacts(cards)
    }

    fun paradigmSourceStackIid(
        facts: ZoneTransferFacts?,
        forgeCardId: ForgeCardId,
        eventSourceForgeCardId: ForgeCardId? = null,
        stackIidLookup: (ForgeCardId) -> Int?,
    ): Int? =
        stackIidLookup(forgeCardId)
            ?: eventSourceForgeCardId?.let(stackIidLookup)
            ?: facts?.card(forgeCardId)?.effectSourceForgeCardId?.let(stackIidLookup)

    fun isParadigm(
        snap: GsmSnapshot,
        forgeCardId: ForgeCardId,
    ): Boolean {
        val bound = snap.boundCards[forgeCardId]
        return bound?.snapshot?.hasParadigmKeyword == true ||
            bound?.altCost(leyline.game.data.KeywordAbilityIds.PARADIGM) != null
    }
}
