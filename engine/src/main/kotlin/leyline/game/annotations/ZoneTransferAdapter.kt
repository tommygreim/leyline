package leyline.game.annotations

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.codes.DetailKeys
import leyline.game.event.GameEvent
import leyline.game.event.ZoneMove
import leyline.game.mapping.StateZoneProjection
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.InstanceIdRegistry
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.ProjectionState
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo

/** Zone-transfer identity planning and journal consumption within one private projection edit. */
object ZoneTransferAdapter {
    internal fun detectZoneTransfers(
        gameObjects: List<GameObjectInfo>,
        zones: List<ZoneInfo>,
        editor: ProjectionState.Editor,
        snapshot: GsmSnapshot,
        events: List<GameEvent>,
        previousSnapshot: GsmSnapshot? = null,
        mechanicSourceFacts: MechanicSourceFacts = MechanicSourceFacts(),
        zoneMoves: List<ZoneMove> = emptyList(),
    ): TransferResult {
        val annotationJournal = editor.annotations
        val plannedReallocs = mutableListOf<InstanceIdRegistry.IdReallocation>()
        val cardFacts = StateZoneProjection.zoneTransferFacts(snapshot)
        val paradigmEventSources =
            events
                .mapNotNull { event ->
                    if (event is GameEvent.SpellCast) {
                        event.paradigmSourceCardId?.let { event.cardId to it }
                    } else if (event is GameEvent.SpellResolved) {
                        event.paradigmSourceCardId?.let { event.cardId to it }
                    } else {
                        null
                    }
                }.toMap()
        events.filterIsInstance<GameEvent.SpellCast>().filter { !it.isAbility && !it.isTrigger }.forEach { event ->
            annotationJournal.recordSpellCast(event, event.spellGrpId.takeIf { it != 0 } ?: cardFacts.card(event.cardId)?.grpId)
        }
        events.filterIsInstance<GameEvent.SpellResolved>().filter { !it.isAbility && !it.isTrigger }.forEach { event ->
            annotationJournal.recordSpellResolution(
                event,
                event.spellGrpId.takeIf { it != 0 } ?: cardFacts.card(event.cardId)?.grpId,
            )
        }

        val idAllocator: (ForgeCardId) -> InstanceIdRegistry.IdReallocation = { fid ->
            val current = editor.identities.peek(fid)
            val reserved =
                current?.let { old ->
                    editor.persistentAnnotations.activeAnnotations.values
                        .singleOrNull { annotation ->
                            annotation.typeList.any { it.number == 62 } &&
                                annotation.detailInt(DetailKeys.REPLACEMENT_SOURCE_ZCID) == old.value
                        }?.affectedIdsList
                        ?.singleOrNull()
                        ?.let(::InstanceId)
                }
            val plan = if (reserved != null) editor.identities.reallocTo(fid, reserved) else editor.identities.realloc(fid)
            plannedReallocs.add(plan)
            plan
        }
        val forgeIdLookup: (InstanceId) -> ForgeCardId? = editor.identities::getForgeCardId
        val idLookup: (ForgeCardId) -> InstanceId = editor.identities::getOrAlloc

        val result =
            ZoneTransferDetector.detectZoneTransfers(
                gameObjects = gameObjects,
                zones = zones,
                events = events,
                context =
                    ZoneTransferContext(
                        previousZones = editor.protoZones.toMap(),
                        forgeIdLookup = forgeIdLookup,
                        idAllocator = idAllocator,
                        idLookup = idLookup,
                        manaAbilityGrpIdResolver = { fid -> GrpId(cardFacts.card(fid)?.basicLandManaAbilityGrpId ?: 0) },
                        grpIdResolver = { fid -> GrpId(cardFacts.card(fid)?.grpId ?: 0) },
                        isForetoldLookup = { fid -> cardFacts.card(fid)?.isForetold ?: false },
                        pendingSpellCastLookup = { fid -> annotationJournal.pendingSpellCast(fid, cardFacts.card(fid)?.grpId) },
                        pendingSpellResolutionLookup = { fid ->
                            annotationJournal.pendingSpellResolution(fid, cardFacts.card(fid)?.grpId)
                        },
                        forgeCardKnown = cardFacts::contains,
                        stackAbilityLookup = { forgeAbilityId ->
                            (snapshot.stack.entries + previousSnapshot?.stack?.entries.orEmpty())
                                .firstOrNull { it.forgeAbilityId == forgeAbilityId }
                                ?.let { StackAbilitySourceFacts(it.forgeCardId, it.isActivatedAbility, it.deferAnnouncement) }
                        },
                        paradigmSourceIidLookup = { fid ->
                            StateZoneProjection.paradigmSourceStackIid(
                                cardFacts,
                                fid,
                                paradigmEventSources[fid],
                                annotationJournal::paradigmSourceStackIidFor,
                            )
                        },
                        sourceZoneLookup = mechanicSourceFacts::recordedSourceZone,
                        zoneMoves = zoneMoves,
                    ),
            )
        result.transfers
            .filter { it.category == TransferCategory.CastSpell }
            .mapNotNull { it.forgeCardId }
            .forEach(annotationJournal::consumeSpellCast)
        result.transfers
            .filter { it.category == TransferCategory.Resolve || it.category == TransferCategory.Countered }
            .mapNotNull { it.forgeCardId }
            .forEach(annotationJournal::consumeSpellResolution)
        return result.copy(idReallocations = plannedReallocs.toList())
    }
}
