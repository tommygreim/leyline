package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.opponent
import leyline.game.data.CardData
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.EarthbendProjection
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.AbilityRegistry
import leyline.game.state.EffectTracker
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Builds [ZoneInfo] protobuf messages and populates zone card lists.
 *
 * Handles player zones (hand, library, graveyard, sideboard), shared zones
 * (battlefield, stack, exile), and stack abilities. Uses [ObjectMapper] for
 * card/ability object construction.
 */
object ZoneMapper {
    private val log = LoggerFactory.getLogger(ZoneMapper::class.java)

    // --- Snapshot-based player zones ---

    /**
     * Add hand, library, and optionally graveyard/sideboard zones for a player from snapshot.
     *
     * Reads card lists and bound card values from [snap]. Missing bound cards are skipped.
     * When [gyZoneId] is null (e.g. deal-hand diff at mulligan time) no graveyard zone
     * is emitted. Reference data and instance identity are supplied explicitly.
     */
    @Suppress("detekt:LongMethod", "detekt:LongParameterList")
    internal fun addPlayerZonesFromSnapshot(
        seatId: SeatId,
        snap: GsmSnapshot,
        environment: StateProjectionEnvironment,
        instanceIdLookup: (ForgeCardId) -> InstanceId,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        handZoneId: Int,
        libZoneId: Int,
        gyZoneId: Int? = null,
        sbZoneId: Int? = null,
        viewingSeatId: Int = 0,
        revealForSeat: Int? = null,
        revealHand: Boolean = false,
        previousSnapshot: GsmSnapshot? = null,
    ) {
        val canSeeHand = viewingSeatId == 0 || viewingSeatId == seatId.value || revealHand
        val handVisibility = if (revealHand) Visibility.Public else Visibility.Private
        val cardVisibility = if (revealHand) Visibility.Public else Visibility.Private
        val handBuilder =
            ZoneInfo
                .newBuilder()
                .setZoneId(handZoneId)
                .setType(ZoneType.Hand)
                .setOwnerSeatId(seatId.value)
                .setVisibility(handVisibility)
                .addViewers(seatId.value)
        if (revealHand) handBuilder.addViewers(seatId.opponent.value)
        for (fid in snap.zones[handZoneId]?.contents ?: emptyList()) {
            val instanceId = instanceIdLookup(fid).value
            handBuilder.addObjectInstanceIds(instanceId)
            if (canSeeHand) {
                addPlayerCardObjects(
                    snap,
                    fid,
                    instanceId,
                    handZoneId,
                    seatId,
                    environment,
                    instanceIdLookup,
                    cardVisibility,
                    "hand",
                    gameObjects,
                    viewers = setOf(seatId.value),
                )
            }
        }
        zones.add(handBuilder.build())

        addLibraryZoneFromSnapshot(
            seatId,
            snap,
            previousSnapshot,
            environment,
            instanceIdLookup,
            zones,
            gameObjects,
            libZoneId,
            revealForSeat == seatId.value,
        )

        if (gyZoneId != null) {
            val gyBuilder =
                ZoneInfo
                    .newBuilder()
                    .setZoneId(gyZoneId)
                    .setType(ZoneType.Graveyard)
                    .setOwnerSeatId(seatId.value)
                    .setVisibility(Visibility.Public)
            for (fid in snap.zones[gyZoneId]?.contents ?: emptyList()) {
                val instanceId = instanceIdLookup(fid).value
                gyBuilder.addObjectInstanceIds(instanceId)
                addPlayerCardObjects(
                    snap,
                    fid,
                    instanceId,
                    gyZoneId,
                    seatId,
                    environment,
                    instanceIdLookup,
                    Visibility.Public,
                    "graveyard",
                    gameObjects,
                )
            }
            zones.add(gyBuilder.build())
        }

        if (sbZoneId != null) {
            val canSeeSideboard = viewingSeatId == 0 || viewingSeatId == seatId.value
            val sbBuilder =
                ZoneInfo
                    .newBuilder()
                    .setZoneId(sbZoneId)
                    .setType(ZoneType.Sideboard)
                    .setOwnerSeatId(seatId.value)
                    .setVisibility(Visibility.Private)
                    .addViewers(seatId.value)
            for (fid in snap.zones[sbZoneId]?.contents ?: emptyList()) {
                val instanceId = instanceIdLookup(fid).value
                if (canSeeSideboard) {
                    sbBuilder.addObjectInstanceIds(instanceId)
                    addPlayerCardObjects(
                        snap,
                        fid,
                        instanceId,
                        sbZoneId,
                        seatId,
                        environment,
                        instanceIdLookup,
                        Visibility.Private,
                        "sideboard",
                        gameObjects,
                        viewers = setOf(seatId.value),
                    )
                }
            }
            zones.add(sbBuilder.build())
        }
    }

    @Suppress("detekt:LongParameterList")
    private fun addLibraryZoneFromSnapshot(
        seatId: SeatId,
        snap: GsmSnapshot,
        previousSnapshot: GsmSnapshot?,
        environment: StateProjectionEnvironment,
        instanceIdLookup: (ForgeCardId) -> InstanceId,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        libraryZoneId: Int,
        revealLibrary: Boolean,
    ) {
        val libraryContents = snap.zones[libraryZoneId]?.contents.orEmpty()
        val currentTop = libraryContents.firstOrNull()
        val previousTop =
            previousSnapshot
                ?.zones
                ?.get(libraryZoneId)
                ?.contents
                ?.firstOrNull()
        val previousInspectionViewers =
            if (previousSnapshot == null || previousTop == null) {
                emptySet()
            } else {
                previousSnapshot.objects[previousTop]?.mayLookSeatIds.orEmpty()
            }
        val library =
            ZoneInfo
                .newBuilder()
                .setZoneId(libraryZoneId)
                .setType(ZoneType.Library)
                .setOwnerSeatId(seatId.value)
                .setVisibility(Visibility.Hidden)
        for (fid in libraryContents) {
            val instanceId = instanceIdLookup(fid).value
            library.addObjectInstanceIds(instanceId)
            val inspectionViewers = if (fid == currentTop) snap.objects[fid]?.mayLookSeatIds.orEmpty() else emptySet()
            val inspectionWithdrawn =
                fid == previousTop &&
                    previousInspectionViewers.isNotEmpty() &&
                    inspectionViewers.isEmpty() &&
                    !revealLibrary
            if (inspectionWithdrawn) {
                gameObjects.add(hiddenLibraryObject(instanceId, libraryZoneId, seatId))
            } else if (revealLibrary || inspectionViewers.isNotEmpty()) {
                addPlayerCardObjects(
                    snap,
                    fid,
                    instanceId,
                    libraryZoneId,
                    seatId,
                    environment,
                    instanceIdLookup,
                    Visibility.Private,
                    "library",
                    gameObjects,
                    viewers = inspectionViewers.mapTo(linkedSetOf()) { it.value }.apply { if (revealLibrary) add(seatId.value) },
                )
            }
        }
        zones.add(library.build())
    }

    private fun hiddenLibraryObject(
        instanceId: Int,
        zoneId: Int,
        owner: SeatId,
    ): GameObjectInfo =
        GameObjectInfo
            .newBuilder()
            .setInstanceId(instanceId)
            .setType(GameObjectType.Card)
            .setZoneId(zoneId)
            .setVisibility(Visibility.Hidden)
            .setOwnerSeatId(owner.value)
            .setControllerSeatId(owner.value)
            .build()

    /**
     * Build [GameObjectInfo] for a card in a player zone (hand/library/graveyard) from snapshot.
     * Returns null when the snapshot has no entry for [fid] (card not findable at capture time —
     * e.g. freshly-moved cards whose Forge IDs are not yet bridged). Callers skip nulls.
     */
    @Suppress("detekt:LongParameterList")
    private fun buildPlayerCard(
        snap: GsmSnapshot,
        fid: ForgeCardId,
        instanceId: Int,
        zoneId: Int,
        seatId: SeatId,
        environment: StateProjectionEnvironment,
        visibility: Visibility,
        zoneName: String,
    ): GameObjectInfo? {
        val cardSnap =
            snap.objects[fid] ?: run {
                log.warn("no snapshot for {} card {} — skipping game object", zoneName, fid)
                return null
            }
        return ObjectMapper.buildFromSnapshot(
            cardSnap,
            instanceId,
            zoneId,
            seatId.value,
            environment.cardProto,
            visibility,
            parentLinkage = snap.boundCards[fid]?.parentLinkage,
        )
    }

    @Suppress("detekt:LongParameterList")
    private fun addPlayerCardObjects(
        snap: GsmSnapshot,
        fid: ForgeCardId,
        instanceId: Int,
        zoneId: Int,
        seatId: SeatId,
        environment: StateProjectionEnvironment,
        instanceIdLookup: (ForgeCardId) -> InstanceId,
        visibility: Visibility,
        zoneName: String,
        gameObjects: MutableList<GameObjectInfo>,
        viewers: Set<Int> = emptySet(),
    ) {
        val card =
            buildPlayerCard(snap, fid, instanceId, zoneId, seatId, environment, visibility, zoneName)
                ?: return
        gameObjects.add(if (viewers.isEmpty()) card else card.toBuilder().addAllViewers(viewers).build())
        val disturbIndex = gameObjects.size
        addDisturbBackObject(snap, fid, instanceId, zoneId, seatId, environment, instanceIdLookup, visibility, gameObjects)
        if (viewers.isNotEmpty() && gameObjects.size > disturbIndex) {
            gameObjects[disturbIndex] = gameObjects[disturbIndex].toBuilder().addAllViewers(viewers).build()
        }
    }

    @Suppress("detekt:LongParameterList")
    private fun addDisturbBackObject(
        snap: GsmSnapshot,
        fid: ForgeCardId,
        sourceInstanceId: Int,
        zoneId: Int,
        seatId: SeatId,
        environment: StateProjectionEnvironment,
        instanceIdLookup: (ForgeCardId) -> InstanceId,
        visibility: Visibility,
        gameObjects: MutableList<GameObjectInfo>,
    ) {
        val bound = snap.boundCards[fid] ?: return
        if (bound.altCost(KeywordAbilityIds.DISTURB) == null) return
        val cardSnap = snap.objects[fid] ?: return
        if (cardSnap.othersideGrpId == 0) return
        val backInstanceId = instanceIdLookup(FrameIdResolver.disturbBackForgeId(fid)).value
        gameObjects.add(
            ObjectMapper.buildDisturbBackObject(
                cardSnap,
                backInstanceId,
                sourceInstanceId,
                zoneId,
                seatId.value,
                environment.cardProto,
                visibility,
            ),
        )
    }

    // --- Snapshot-based shared zones ---

    /** Project one shared zone from immutable card values and stable reference data. */
    internal fun addSharedZoneCardsFromSnapshot(
        snap: GsmSnapshot,
        arenaZoneId: Int,
        environment: StateProjectionEnvironment,
        instanceIdLookup: (ForgeCardId) -> InstanceId,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
        keywordSnapshot: Map<Int, List<EffectTracker.KeywordEntry>> = emptyMap(),
        earthbendProjection: (ForgeCardId) -> EarthbendProjection? = { null },
        grantedAbilitySnapshot: Map<Int, List<EffectTracker.TrackedGrantedAbility>> = emptyMap(),
    ) {
        val projected =
            StateZoneProjection.projectSharedZone(
                snap = snap,
                arenaZoneId = arenaZoneId,
                environment = environment,
                instanceIdLookup = instanceIdLookup,
                keywordSnapshot = keywordSnapshot,
                earthbendProjection = earthbendProjection,
                grantedAbilitySnapshot = grantedAbilitySnapshot,
            ) ?: return
        zones.removeIf { it.zoneId == arenaZoneId }
        zones += projected.zone
        gameObjects += projected.gameObjects
    }

    /**
     * Add [GameObjectType.Ability] entries for stack items not already represented
     * as cards in the stack zone. Reads from [snap.stack] — no live Forge reference needed.
     *
     * Mints iids via [FrameIdResolver.triggerStackAbilityForgeId] (SA-id-keyed
     * surrogate) so back-to-back triggers from one source card mint distinct
     * iids. Falls back to source-card-keyed surrogate when `entry.forgeAbilityId == 0`
     * — the synthetic-test path where the SA id isn't surfaced.
     */
    internal fun addStackAbilitiesFromSnapshot(
        snap: GsmSnapshot,
        environment: StateProjectionEnvironment,
        instanceIdLookup: (ForgeCardId) -> InstanceId,
        paradigmSourceStackIidLookup: (ForgeCardId) -> Int?,
        zones: MutableList<ZoneInfo>,
        gameObjects: MutableList<GameObjectInfo>,
    ) {
        if (snap.stack.entries.isEmpty()) return

        val zoneBuilder = zones.find { it.zoneId == ZoneIds.STACK }?.toBuilder() ?: return
        zones.removeIf { it.zoneId == ZoneIds.STACK }

        for (entry in snap.stack.entries) {
            // Skip spell casts — those are projected as Cards in the stack zone via
            // [addSharedZoneCardsFromSnapshot]. The Ability projection path is for
            // triggered + activated SAs (Cascade trigger, Discover trigger, etc.).
            // Without this, late-snapshot timing where Forge has already removed the
            // spell from the stack zone but the entry lingers leaks an Ability with
            // grpId == sourceCardGrpId, masking the real triggered-ability projection.
            // Triggered abilities firing off a spell-on-stack (Cascade, source_zone=27)
            // need to project even when their source spell is still in the stack zone.
            if (entry.isSpell) continue

            val abilityInstanceId = stackEntryIid(entry, instanceIdLookup)
            val grpId = entry.grpId.takeIf { it != 0 } ?: 0
            // Degraded fallback: when [SnapshotCapture] couldn't resolve the source
            // card's Arena printing (synthetic test card, unrecognized token), reuse
            // the ability grpId rather than emit 0. This re-collapses grpId ==
            // objectSourceGrpId — the exact bug the field split was introduced to
            // fix — so warn loudly so a debug session knows to look here, not at
            // the resolver.
            val sourceCardGrpId =
                entry.sourceCardGrpId.takeIf { it != 0 } ?: run {
                    log.warn(
                        "stack ability sourceCardGrpId=0 for forgeCardId={}; " +
                            "falling back to ability grpId={} — collapsing the field split",
                        entry.forgeCardId,
                        grpId,
                    )
                    grpId
                }
            val parentInstanceId =
                if (grpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER) {
                    paradigmSourceStackIidLookup(entry.forgeCardId) ?: 0
                } else {
                    instanceIdLookup(entry.forgeCardId).value
                }

            zoneBuilder.addObjectInstanceIds(abilityInstanceId)
            gameObjects.add(
                ObjectMapper.buildAbilityObject(
                    grpId = grpId,
                    sourceCardGrpId = sourceCardGrpId,
                    instanceId = abilityInstanceId,
                    ownerSeatId = entry.owner.value,
                    cardProto = environment.cardProto,
                    parentInstanceId = parentInstanceId,
                ),
            )
        }
        orderStackTopFirst(snap, zoneBuilder, instanceIdLookup)
        zones.add(zoneBuilder.build())
    }

    /** The instanceId a stack entry projects as — its card for a spell, its minted surrogate otherwise. */
    private fun stackEntryIid(
        entry: leyline.game.snapshot.StackEntry,
        instanceIdLookup: (ForgeCardId) -> InstanceId,
    ): Int =
        when {
            entry.isSpell -> instanceIdLookup(entry.forgeCardId).value
            entry.forgeAbilityId != 0 ->
                instanceIdLookup(FrameIdResolver.triggerStackAbilityForgeId(entry.forgeAbilityId)).value
            else -> instanceIdLookup(FrameIdResolver.stackAbilityForgeId(entry.forgeCardId)).value
        }

    /**
     * Project the stack zone top-first.
     *
     * The client reads `objectInstanceIds[0]` as the top of the stack
     * (`MtgGameState.GetTopCardOnStack`), and the fan layout, `IsTopOfStack` VFX,
     * auto-tap and the cost prompts all follow that same index — so a bottom-first
     * list does not merely draw a response behind the spell it answers, it points
     * every "top of stack" lookup at the wrong object.
     *
     * Forge's MagicStack pushes with `addFirst`, so [snap.stack] is already
     * top-first. The zone list is not: spells arrive in the zone's own arrival
     * order and abilities are appended after them, which no single reversal can
     * fix once both are present.
     *
     * Only ids already in the zone are reordered; anything the stack snapshot does
     * not name keeps its relative order at the back.
     */
    private fun orderStackTopFirst(
        snap: GsmSnapshot,
        zoneBuilder: ZoneInfo.Builder,
        instanceIdLookup: (ForgeCardId) -> InstanceId,
    ) {
        val current = zoneBuilder.objectInstanceIdsList.toList()
        if (current.size < 2) return
        val remaining = current.toMutableList()
        val ordered = mutableListOf<Int>()
        for (entry in snap.stack.entries) {
            val iid = stackEntryIid(entry, instanceIdLookup)
            if (remaining.remove(iid)) ordered.add(iid)
        }
        // Ids the stack snapshot does not name are objects being projected ahead of
        // Forge's own push — a spell mid-cast, whose frame is built before the stack
        // entry exists. Those are the newest, so they lead, newest of them first.
        ordered.addAll(0, remaining.reversed())
        if (ordered == current) return
        zoneBuilder.clearObjectInstanceIds()
        zoneBuilder.addAllObjectInstanceIds(ordered)
    }

    /**
     * Pick the chapter-specific ability grpId from a [CardData], independent of
     * the Forge stack entry.
     *
     * Chapter abilities are trigger rows (client `Abilities.Category` = 2)
     * listed in chapter order. Read-ahead sagas (e.g. the DMU cycle) lead
     * [CardData.abilityIds] with a shared static "Read ahead" row
     * (Category = 3), so a positional index into [CardData.abilityIds] would
     * misresolve chapter I. Filtering to trigger rows resolves the chapter
     * grpIds regardless of leading non-chapter abilities.
     */
    internal fun chapterGrpIdFromCardData(
        cardData: CardData,
        chapterIdx: Int,
    ): Int? {
        require(cardData.abilityCategories.size == cardData.abilityIds.size) {
            "abilityCategories must align 1:1 with abilityIds for chapter resolution"
        }
        val chapterGrpIds =
            cardData.abilityIds
                .zip(cardData.abilityCategories)
                .filter { (_, category) -> category == AbilityRegistry.TRIGGER_CATEGORY }
                .map { (ability, _) -> ability.first }
        return chapterGrpIds.getOrNull(chapterIdx - 1)
    }

    // --- Initial game zones ---

    /**
     * Player zones for initial bundle: empty hand, full library, empty graveyard/sideboard.
     *
     * Pre-deal state: library zone shows all deck cards (hand + library zones combined,
     * since no cards have been dealt yet). Reads card lists from [snap].
     */
    internal fun addInitialPlayerZonesFromSnapshot(
        seatId: SeatId,
        snap: GsmSnapshot,
        instanceIdLookup: (ForgeCardId) -> InstanceId,
        zones: MutableList<ZoneInfo>,
        handZoneId: Int,
        libZoneId: Int,
        gyZoneId: Int,
        sbZoneId: Int,
        viewingSeatId: Int = 0,
    ) {
        // Hand — empty, with viewer
        zones.add(
            ZoneInfo
                .newBuilder()
                .setZoneId(handZoneId)
                .setType(ZoneType.Hand)
                .setOwnerSeatId(seatId.value)
                .setVisibility(Visibility.Private)
                .addViewers(seatId.value)
                .build(),
        )
        // Library — all cards (hand + library combined = full deck, pre-deal)
        val libBuilder =
            ZoneInfo
                .newBuilder()
                .setZoneId(libZoneId)
                .setType(ZoneType.Library)
                .setOwnerSeatId(seatId.value)
                .setVisibility(Visibility.Hidden)
        for (fid in snap.zones[libZoneId]?.contents ?: emptyList()) {
            libBuilder.addObjectInstanceIds(instanceIdLookup(fid).value)
        }
        for (fid in snap.zones[handZoneId]?.contents ?: emptyList()) {
            libBuilder.addObjectInstanceIds(instanceIdLookup(fid).value)
        }
        zones.add(libBuilder.build())
        // Graveyard — empty
        zones.add(makeZone(gyZoneId, ZoneType.Graveyard, seatId.value, Visibility.Public))
        val sbBuilder =
            ZoneInfo
                .newBuilder()
                .setZoneId(sbZoneId)
                .setType(ZoneType.Sideboard)
                .setOwnerSeatId(seatId.value)
                .setVisibility(Visibility.Private)
                .addViewers(seatId.value)
        val canSeeSideboard = viewingSeatId == 0 || viewingSeatId == seatId.value
        for (fid in snap.zones[sbZoneId]?.contents ?: emptyList()) {
            val instanceId = instanceIdLookup(fid).value
            if (canSeeSideboard) sbBuilder.addObjectInstanceIds(instanceId)
        }
        zones.add(sbBuilder.build())
    }

    // --- Helpers ---

    /** Build a basic ZoneInfo with no cards. */
    internal fun makeZone(
        zoneId: Int,
        type: ZoneType,
        ownerSeatId: Int,
        visibility: Visibility,
    ): ZoneInfo =
        ZoneInfo
            .newBuilder()
            .setZoneId(zoneId)
            .setType(type)
            .setOwnerSeatId(ownerSeatId)
            .setVisibility(visibility)
            .build()

    /**
     * Forge stores cards in arrival order. Public discard zones are projected
     * newest-first for the client while all engine-side ordering stays intact.
     */
    internal fun clientOrderedZone(zone: ZoneInfo): ZoneInfo {
        if (zone.zoneId !in clientNewestFirstZoneIds) return zone
        return zone
            .toBuilder()
            .clearObjectInstanceIds()
            .addAllObjectInstanceIds(zone.objectInstanceIdsList.reversed())
            .build()
    }

    private val clientNewestFirstZoneIds =
        setOf(
            ZoneIds.EXILE,
            ZoneIds.P1_GRAVEYARD,
            ZoneIds.P2_GRAVEYARD,
        )

    /** Private zone with viewers=[ownerSeatId] (hand, sideboard). */
    internal fun makePrivateZone(
        zoneId: Int,
        type: ZoneType,
        ownerSeatId: Int,
    ): ZoneInfo =
        ZoneInfo
            .newBuilder()
            .setZoneId(zoneId)
            .setType(type)
            .setOwnerSeatId(ownerSeatId)
            .setVisibility(Visibility.Private)
            .addViewers(ownerSeatId)
            .build()

    /** Returns the hand zone ID of the opponent, or 0 if viewingSeatId is 0 (no filtering). */
    internal fun opponentHandZone(viewingSeatId: Int): Int =
        when (viewingSeatId) {
            1 -> ZoneIds.P2_HAND
            2 -> ZoneIds.P1_HAND
            else -> 0
        }

    /** Returns the sideboard zone ID of the opponent, or 0 if viewingSeatId is 0 (no filtering). */
    internal fun opponentSideboardZone(viewingSeatId: Int): Int =
        when (viewingSeatId) {
            1 -> ZoneIds.P2_SIDEBOARD
            2 -> ZoneIds.P1_SIDEBOARD
            else -> 0
        }
}
