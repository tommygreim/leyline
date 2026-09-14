package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.StaticChoiceIds
import leyline.game.annotations.AnnotationBuilder
import leyline.game.annotations.AnnotationConstants
import leyline.game.annotations.TransferResult
import leyline.game.codes.KeywordGrpIds
import leyline.game.codes.QualificationType
import leyline.game.data.KeywordAbilityIds
import leyline.game.event.GameEvent
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.PreparedRole
import leyline.game.state.AbilityWordActiveKind
import leyline.game.state.ClassLevelKind
import leyline.game.state.ColorProductionKind
import leyline.game.state.CommanderDesignationKind
import leyline.game.state.DayNightDesignationKind
import leyline.game.state.DelayedTriggerAffecteesKind
import leyline.game.state.FaceDownCloakKind
import leyline.game.state.FaceDownDisguiseKind
import leyline.game.state.FaceDownForetellKind
import leyline.game.state.FaceDownManifestDreadKind
import leyline.game.state.HolderRecord
import leyline.game.state.LinkInfoChoiceKind
import leyline.game.state.PersistentAnnotationKind
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.PlayerSpeedDesignationKind
import leyline.game.state.PreparedDesignationKind
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.QualificationKind
import leyline.game.state.TemporaryPermanentKind
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Snap- or event-derived persistent annotation inputs for one GSM build. */
internal data class PersistentFeedSet(
    val perKind: Map<PersistentAnnotationKind, List<AnnotationInfo>> = emptyMap(),
) {
    operator fun get(kind: PersistentAnnotationKind): List<AnnotationInfo> = perKind[kind].orEmpty()

    fun withAdditional(
        kind: PersistentAnnotationKind,
        annotations: List<AnnotationInfo>,
    ): PersistentFeedSet =
        if (annotations.isEmpty()) {
            this
        } else {
            PersistentFeedSet(perKind + (kind to (get(kind) + annotations)))
        }
}

internal data class PersistentFeedBuildResult(
    val feeds: PersistentFeedSet,
    val currentHolders: List<HolderRecord>,
)

internal object PersistentFeedBuilder {
    fun build(
        events: List<GameEvent>,
        snap: GsmSnapshot,
        prev: GsmSnapshot?,
        frameIds: FrameIdResolver,
        decayedCleanupSourcesThisGsm: Set<ForgeCardId>,
        transferResult: TransferResult,
        promptFacts: PromptProjectionFacts = PromptProjectionFacts(),
        persistentFeedFacts: PersistentFeedFacts = PersistentFeedFacts(),
        references: ProjectionCardReferences,
    ): PersistentFeedBuildResult {
        val qualification = buildQualificationAnnotations(snap, frameIds, persistentFeedFacts)
        val temporaryPermanent =
            PersistentTemporaryFeedBuilder.build(
                snap,
                frameIds,
                decayedCleanupSourcesThisGsm,
                transferResult,
                persistentFeedFacts,
                references,
            )
        val abilityWord =
            PersistentAbilityWordFeedBuilder.build(events, snap, prev, frameIds, promptFacts, persistentFeedFacts, references)
        val designations = buildDesignationAnnotations(snap, frameIds)
        val dayNightDesignation = buildDayNightDesignationAnnotations(snap)
        val faceDownForetell = buildFaceDownForetellAnnotations(snap, frameIds)
        val faceDownDisguise = buildFaceDownDisguiseAnnotations(snap, frameIds)
        val faceDownCloak = buildFaceDownCloakAnnotations(snap, frameIds)
        val faceDownManifestDread = buildFaceDownManifestDreadAnnotations(snap, frameIds)
        val colorProduction = buildColorProductionAnnotations(snap, frameIds)
        val classLevel = buildClassLevelAnnotations(snap, frameIds)
        val linkInfo = buildLinkInfoAnnotations(snap, frameIds, references)

        return PersistentFeedBuildResult(
            feeds =
                PersistentFeedSet(
                    perKind =
                        mapOf(
                            QualificationKind to qualification,
                            TemporaryPermanentKind to temporaryPermanent.temporaryPermanent,
                            DelayedTriggerAffecteesKind to temporaryPermanent.delayedTriggerAffectees,
                            AbilityWordActiveKind to abilityWord,
                            DayNightDesignationKind to dayNightDesignation,
                            FaceDownForetellKind to faceDownForetell,
                            FaceDownDisguiseKind to faceDownDisguise,
                            FaceDownCloakKind to faceDownCloak,
                            FaceDownManifestDreadKind to faceDownManifestDread,
                            ColorProductionKind to colorProduction,
                            ClassLevelKind to classLevel,
                            LinkInfoChoiceKind to linkInfo,
                        ) + designations,
                ),
            currentHolders = temporaryPermanent.currentHolders,
        )
    }

    private fun buildQualificationAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
        facts: PersistentFeedFacts,
    ): List<AnnotationInfo> =
        snap.objects.values
            .filter { it.isOnAdventure }
            .map { AnnotationBuilder.qualification(instanceId = frameIds.cardIid(it.forgeCardId)) } +
            suspectedQualificationAnnotations(snap, frameIds) +
            facts.combatQualifications
                .sortedWith(
                    compareBy<PersistentFeedFacts.CombatQualificationRow> { frameIds.cardIid(it.affectedForgeId).value }
                        .thenBy { it.qualificationType.wireValue }
                        .thenBy { it.abilityGrpId },
                ).map { row ->
                    AnnotationBuilder.qualification(
                        affectorId = frameIds.cardIid(row.affectorForgeId),
                        instanceId = frameIds.cardIid(row.affectedForgeId),
                        grpId = GrpId(row.abilityGrpId),
                        qualificationType = row.qualificationType,
                        sourceParent = frameIds.cardIid(row.sourceParentForgeId),
                        cantBlockObjects = row.cantBlockForgeIds.map { frameIds.cardIid(it).value }.sorted(),
                        cantBeBlockedByObjects = row.cantBeBlockedByForgeIds.map { frameIds.cardIid(it).value }.sorted(),
                    )
                }

    private fun suspectedQualificationAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> {
        val menaceGrpId = KeywordGrpIds.forKeyword("Menace")?.let(::GrpId) ?: return emptyList()
        return snap.boundCards.values
            .asSequence()
            .filter { it.snapshot.isOnBattlefield && it.designations.isSuspected }
            .flatMap { card ->
                val iid = frameIds.cardIid(card.forgeCardId)
                sequenceOf(
                    AnnotationBuilder.qualification(
                        affectorId = iid,
                        instanceId = iid,
                        grpId = menaceGrpId,
                        qualificationType = QualificationType.CombatKeyword,
                        sourceParent = iid,
                    ),
                    AnnotationBuilder.qualification(
                        affectorId = iid,
                        instanceId = iid,
                        grpId = AnnotationConstants.SUSPECTED_CANT_BLOCK_GRP_ID,
                        qualificationType = QualificationType.CantBlock,
                        sourceParent = iid,
                    ),
                )
            }.toList()
    }

    fun remapDelayedTriggerAffectees(
        feeds: PersistentFeedSet,
        activeAnnotations: Collection<AnnotationInfo>,
        affectorReplacements: Map<Int, Int>,
    ): PersistentFeedSet {
        val remapped =
            activeAnnotations
                .filter { DelayedTriggerAffecteesKind.matches(it) && it.affectorId in affectorReplacements }
                .map { annotation ->
                    annotation
                        .toBuilder()
                        .setId(0)
                        .setAffectorId(affectorReplacements.getValue(annotation.affectorId))
                        .build()
                }
        return feeds.withAdditional(DelayedTriggerAffecteesKind, remapped)
    }

    fun retainDelayedTriggerAffectees(
        feeds: PersistentFeedSet,
        activeAnnotations: Collection<AnnotationInfo>,
        holderIids: Set<Int>,
    ): PersistentFeedSet {
        val retained =
            activeAnnotations.filter { annotation ->
                DelayedTriggerAffecteesKind.matches(annotation) && annotation.affectorId in holderIids
            }
        return feeds.withAdditional(DelayedTriggerAffecteesKind, retained)
    }

    private fun buildDesignationAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): Map<PersistentAnnotationKind, List<AnnotationInfo>> {
        val simpleRows =
            CardStateDesignations.simplePersistent.associate { spec ->
                val kind = spec.persistentKind ?: error("simple persistent designation missing kind: ${spec.kind}")
                val emit = spec.persistentEmitter ?: error("simple persistent designation missing emitter: ${spec.kind}")
                kind to
                    snap.boundCards.values.mapNotNull { bound ->
                        if (!spec.readRole(bound)) return@mapNotNull null
                        emit(frameIds.cardIid(bound.forgeCardId))
                    }
            }
        val prepared =
            snap.boundCards.values
                .mapNotNull { bound ->
                    val source = bound.designations.prepared as? PreparedRole.Source ?: return@mapNotNull null
                    AnnotationBuilder.preparedDesignation(
                        instanceId = frameIds.cardIid(bound.forgeCardId),
                        preparedCopyInstanceId = frameIds.cardIid(source.copyForgeCardId),
                    )
                }
        val commander =
            snap.boundCards.values
                .filter { it.designations.isCommander && it.snapshot.grpId > 0 }
                .flatMap { bound ->
                    val iid = frameIds.cardIid(bound.forgeCardId)
                    val grpId = GrpId(bound.snapshot.grpId)
                    val colorIdentity = bound.designations.commanderColorIdentity
                    val tax = bound.designations.commanderTax
                    listOf(
                        AnnotationBuilder.commanderPlayerDesignation(
                            seatId = bound.snapshot.owner,
                            grpId = grpId,
                            colorIdentity = colorIdentity,
                            costIncrease = tax,
                        ),
                        AnnotationBuilder.commanderObjectDesignation(
                            instanceId = iid,
                            grpId = grpId,
                            colorIdentity = colorIdentity,
                            costIncrease = tax,
                        ),
                    )
                }
        val playerSpeed =
            snap.seats
                .filter { it.speed > 0 }
                .map { seat ->
                    AnnotationBuilder.playerSpeedDesignation(
                        seatId = seat.seatId,
                        speed = seat.speed,
                        triggerHolderIid = FrameIdResolver.speedTriggerHolderIid(seat.seatId),
                    )
                }
        return simpleRows +
            mapOf(
                PreparedDesignationKind to prepared,
                CommanderDesignationKind to commander,
                PlayerSpeedDesignationKind to playerSpeed,
            )
    }

    private fun buildDayNightDesignationAnnotations(snap: GsmSnapshot): List<AnnotationInfo> =
        snap.dayTime?.let { isNight ->
            listOf(
                AnnotationBuilder.dayNightDesignation(
                    designationType =
                        if (isNight) {
                            AnnotationConstants.DESIGNATION_TYPE_NIGHT
                        } else {
                            AnnotationConstants.DESIGNATION_TYPE_DAY
                        },
                    activePlayerSpellCount = snap.activePlayerSpellsCastThisTurn,
                ),
            )
        } ?: emptyList()

    private fun buildFaceDownDisguiseAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        snap.boundCards.values
            .mapNotNull { bound ->
                if (bound.snapshot.faceDownKind != leyline.game.snapshot.FaceDownKind.Disguise) return@mapNotNull null
                AnnotationBuilder.faceDownPersistent(
                    instanceId = frameIds.cardIid(bound.forgeCardId),
                    reason = AnnotationConstants.FACEDOWN_REASON_DISGUISE,
                    abilityGrpId = GrpId(KeywordAbilityIds.DISGUISE),
                )
            }

    private fun buildFaceDownForetellAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        snap.boundCards.values
            .mapNotNull { bound ->
                if (!bound.snapshot.isForetold) return@mapNotNull null
                AnnotationBuilder.faceDownPersistent(
                    instanceId = frameIds.cardIid(bound.forgeCardId),
                    reason = AnnotationConstants.FACEDOWN_REASON_FORETELL,
                    abilityGrpId = GrpId(KeywordAbilityIds.FORETELL),
                )
            }

    private fun buildFaceDownManifestDreadAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        snap.boundCards.values
            .mapNotNull { bound ->
                if (bound.snapshot.faceDownKind != leyline.game.snapshot.FaceDownKind.ManifestDread) return@mapNotNull null
                AnnotationBuilder.faceDownPersistent(
                    instanceId = frameIds.cardIid(bound.forgeCardId),
                    reason = AnnotationConstants.FACEDOWN_REASON_MANIFEST_DREAD,
                    abilityGrpId = GrpId(KeywordAbilityIds.MANIFEST_DREAD),
                )
            }

    private fun buildFaceDownCloakAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        snap.boundCards.values
            .mapNotNull { bound ->
                if (bound.snapshot.faceDownKind != leyline.game.snapshot.FaceDownKind.Cloak) return@mapNotNull null
                AnnotationBuilder.faceDownPersistent(
                    instanceId = frameIds.cardIid(bound.forgeCardId),
                    reason = AnnotationConstants.FACEDOWN_REASON_CLOAK,
                    abilityGrpId = GrpId(KeywordAbilityIds.CLOAK),
                )
            }

    private fun buildColorProductionAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        snap.boundCards.values.mapNotNull { bound ->
            if (!bound.snapshot.isOnBattlefield) return@mapNotNull null
            val colors = bound.snapshot.manaProductionColors
            if (colors.isEmpty()) return@mapNotNull null
            AnnotationBuilder.colorProduction(frameIds.cardIid(bound.forgeCardId), colors)
        }

    private fun buildClassLevelAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
    ): List<AnnotationInfo> =
        snap.boundCards.values.mapNotNull { bound ->
            val level = bound.snapshot.classLevel ?: return@mapNotNull null
            AnnotationBuilder.classLevel(frameIds.cardIid(bound.forgeCardId), level)
        }

    private fun buildLinkInfoAnnotations(
        snap: GsmSnapshot,
        frameIds: FrameIdResolver,
        references: ProjectionCardReferences,
    ): List<AnnotationInfo> =
        snap.boundCards.values.flatMap { bound ->
            if (!bound.snapshot.isOnBattlefield) return@flatMap emptyList()
            if (bound.snapshot.chosenType == null && bound.snapshot.chosenColorIds.isEmpty()) return@flatMap emptyList()
            val sourceAbilityGrpId = references.choiceSourceAbilityGrpId(bound.data) ?: return@flatMap emptyList()
            val sourceIid = frameIds.cardIid(bound.forgeCardId)
            buildList {
                val chosenTypeId = bound.snapshot.chosenType?.let { StaticChoiceIds.subtypeIdFor(it) }
                if (chosenTypeId != null) {
                    add(
                        AnnotationBuilder.linkInfoChoice(
                            sourceInstanceId = sourceIid,
                            affectedIds = listOf(6, chosenTypeId),
                            chooseLinkType = "Type",
                            sourceAbilityGrpId = GrpId(sourceAbilityGrpId),
                        ),
                    )
                }
                bound.snapshot.chosenColorIds.firstOrNull()?.let { colorId ->
                    add(
                        AnnotationBuilder.linkInfoChoice(
                            sourceInstanceId = sourceIid,
                            affectedIds = listOf(colorId),
                            chooseLinkType = "Color",
                            sourceAbilityGrpId = GrpId(sourceAbilityGrpId),
                        ),
                    )
                }
            }
        }

    internal fun decayedCleanupGrpIdForSource(
        sourceForgeId: ForgeCardId,
        snap: GsmSnapshot,
        references: ProjectionCardReferences,
        transferResult: TransferResult? = null,
    ): Int? = PersistentTemporaryFeedBuilder.decayedCleanupGrpIdForSource(sourceForgeId, snap, references, transferResult)
}
