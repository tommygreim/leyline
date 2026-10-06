package leyline.game.mapping

import forge.card.CardStateName
import forge.card.mana.ManaCost
import forge.game.card.Card
import forge.game.cost.CostPayment
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import leyline.bridge.ActionAvailability
import leyline.bridge.ActionCostParts
import leyline.bridge.ActionManaCosts
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.buildLandPlayAbility
import leyline.bridge.buildMdfcBackLandAbility
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.handoff.GameActionBridge.ActionOffer
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.data.KeywordAbilityIds
import leyline.game.data.grantedKeywordAbilityGrpId
import leyline.game.snapshot.AltCostBinding
import leyline.game.snapshot.BoundCard
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.LinkedFaceRole
import leyline.game.state.AbilityRegistry
import leyline.game.state.GameBridge
import leyline.game.state.InstanceIdRegistry
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.Target as GreTarget

/**
 * Projects Forge priority choices into client [Action] / [ActionsAvailableReq]
 * values and pairs each executable action with its engine-side [ActionOffer].
 *
 * [buildProjectionFromSnapshot] is the production boundary. Zone membership,
 * object identity, and bound card metadata come from one immutable
 * [GsmSnapshot]. The matching window's live player, abilities, and
 * [PriorityActionCandidates] supply legality, costs, and executable commands.
 * The returned [ActionProjection.actions] and [ActionProjection.offers]
 * preserve the same order, allowing a client response to resolve back to the
 * exact window-scoped command.
 *
 * [buildNaiveActionsFromSnapshot] is the presentation-only permissive action
 * list embedded in opponent-turn / remote / transition GSMs. It reads zone
 * membership and card identity from the immutable snapshot and creates no
 * executable offers, so priority-window publication must use the snapshot
 * projection path.
 *
 * Action emission order is protocol-significant: the client associates display
 * text with the first compatible action shape. Keep Cast before Activate for a
 * hand card and preserve each zone rail's declared order.
 */
@Suppress("LargeClass") // action emission spans multiple zones and wire shapes.
object ActionMapper {
    /**
     * Preserve inactive affordances in GSM action metadata.  In particular,
     * Arena uses this tree to keep a permitted alternate-zone card beside the
     * hand; the separate ActionsAvailableReq inactiveActions list alone does
     * not keep a Tablet land visible after its land timing becomes illegal.
     * Active rows win only when every wire-level action discriminator agrees;
     * a single card may legitimately expose several activated or alternate
     * actions with the same action type and instance id.
     */
    fun actionsForGsm(actions: ActionsAvailableReq): List<Action> =
        (actions.actionsList + actions.inactiveActionsList)
            .distinctBy {
                listOf(
                    it.actionType.number,
                    it.instanceId,
                    it.facetId,
                    it.abilityGrpId,
                    it.alternativeGrpId,
                    it.uniqueAbilityId,
                    it.sourceId,
                    it.alternativeSourceZcid,
                )
            }

    private val log = LoggerFactory.getLogger(ActionMapper::class.java)

    /** Protocol actions and their positionally aligned, window-scoped executable commands. */
    data class ActionProjection(
        val actions: ActionsAvailableReq,
        val offers: List<ActionOffer>,
    )

    /**
     * Rewrites every object reference in an action bundle after projection has
     * allocated replacement instance ids.  Actions are commonly captured from
     * the state before a zone transition is projected (for example, a card
     * drawn from the library), while the action is embedded in the GSM after
     * that transition.  Leaving the old id in the action makes the client
     * render/resolve an action for an object that no longer exists.
     *
     * This intentionally lives at the protocol boundary rather than in a
     * card/mechanic mapper: all action families can carry object references.
     */
    internal fun remapInstanceIds(
        actions: ActionsAvailableReq,
        reallocations: List<InstanceIdRegistry.IdReallocation>,
    ): ActionsAvailableReq {
        if (reallocations.isEmpty()) return actions
        val replacements = reallocations.associate { it.old.value to it.new.value }

        fun iid(value: Int): Int {
            var current = value
            val visited = mutableSetOf<Int>()
            while (visited.add(current)) {
                val replacement = replacements[current] ?: return current
                current = replacement
            }
            return current
        }

        fun manaRequirement(value: ManaRequirement): ManaRequirement = value.toBuilder().setObjectId(iid(value.objectId)).build()

        fun manaInfo(value: ManaInfo): ManaInfo = value.toBuilder().setSrcInstanceId(iid(value.srcInstanceId)).build()

        fun manaPaymentOption(value: ManaPaymentOption): ManaPaymentOption =
            value
                .toBuilder()
                .clearMana()
                .addAllMana(value.manaList.map(::manaInfo))
                .build()

        fun cost(value: Cost): Cost {
            val builder = value.toBuilder().setObjectId(iid(value.objectId))
            if (value.hasOrCost()) {
                val nested =
                    value.orCost
                        .toBuilder()
                        .clearCosts()
                        .addAllCosts(value.orCost.costsList.map(::cost))
                        .build()
                builder.setOrCost(nested)
            }
            if (value.hasAndCost()) {
                val nested =
                    value.andCost
                        .toBuilder()
                        .clearCosts()
                        .addAllCosts(value.andCost.costsList.map(::cost))
                        .build()
                builder.setAndCost(nested)
            }
            return builder.build()
        }

        fun target(value: GreTarget): GreTarget = value.toBuilder().setTargetInstanceId(iid(value.targetInstanceId)).build()

        fun targetSelection(value: TargetSelection): TargetSelection =
            value
                .toBuilder()
                .clearTargets()
                .addAllTargets(value.targetsList.map(::target))
                .build()

        fun autoTapAction(value: AutoTapAction): AutoTapAction =
            value
                .toBuilder()
                .setInstanceId(iid(value.instanceId))
                .apply {
                    if (value.hasManaPaymentOption()) {
                        setManaPaymentOption(manaPaymentOption(value.manaPaymentOption))
                    }
                }.build()

        fun autoTapSolution(value: AutoTapSolution): AutoTapSolution =
            value
                .toBuilder()
                .clearAutoTapActions()
                .addAllAutoTapActions(value.autoTapActionsList.map(::autoTapAction))
                .build()

        fun manaSelection(value: ManaSelection): ManaSelection = value.toBuilder().setInstanceId(iid(value.instanceId)).build()

        fun action(value: Action): Action =
            value
                .toBuilder()
                .setInstanceId(iid(value.instanceId))
                .setFacetId(iid(value.facetId))
                .setSourceId(iid(value.sourceId))
                .setDisqualifyingSourceId(iid(value.disqualifyingSourceId))
                .setAlternativeSourceZcid(iid(value.alternativeSourceZcid))
                .clearManaPaymentOptions()
                .addAllManaPaymentOptions(value.manaPaymentOptionsList.map(::manaPaymentOption))
                .clearManaCost()
                .addAllManaCost(value.manaCostList.map(::manaRequirement))
                .clearTargets()
                .addAllTargets(value.targetsList.map(::targetSelection))
                .clearAuxiliaryManaCosts()
                .addAllAuxiliaryManaCosts(
                    value.auxiliaryManaCostsList.map { bundle ->
                        bundle
                            .toBuilder()
                            .clearCosts()
                            .addAllCosts(bundle.costsList.map(::cost))
                            .build()
                    },
                ).clearCosts()
                .addAllCosts(value.costsList.map(::cost))
                .clearManaSelections()
                .addAllManaSelections(value.manaSelectionsList.map(::manaSelection))
                .apply {
                    if (value.hasAutoTapSolution()) setAutoTapSolution(autoTapSolution(value.autoTapSolution))
                    if (value.hasTwobridGenericManaCost()) {
                        setTwobridGenericManaCost(manaRequirement(value.twobridGenericManaCost))
                    }
                }.build()

        return actions
            .toBuilder()
            .clearActions()
            .addAllActions(actions.actionsList.map(::action))
            .clearInactiveActions()
            .addAllInactiveActions(actions.inactiveActionsList.map(::action))
            .build()
    }

    private fun canExecute(
        sa: SpellAbility,
        player: Player,
    ): Boolean = ActionAvailability.canExecute(sa, player)

    /**
     * Start every spell-cast action with the supported non-mana parts of its
     * Forge cost. Cast rails vary in identity fields, but all share this cost
     * projection contract.
     */
    private fun castActionBuilder(
        actionType: ActionType,
        ability: SpellAbility?,
    ): Action.Builder =
        Action
            .newBuilder()
            .setActionType(actionType)
            .also { it.addAllCosts(ActionCostParts.of(ability?.payCosts)) }

    /**
     * Naive action list for opponent-turn / remote-frame GSM embedding: Cast
     * for all non-lands, inactive Play for all lands in hand, ActivateMana for
     * untapped battlefield permanents — no canPlay/canPay checks.
     *
     * Client expects the human's potential actions embedded during the AI's
     * turn regardless of phase. Zone membership, card identity, and grpIds
     * come from the immutable [GsmSnapshot]; the live bridge is consulted for
     * Forge card objects and identity only, never for zone iteration (the
     * same boundary [buildProjectionFromSnapshot] uses). This keeps the
     * permissive wire shape and ordering the client relies on without a
     * second general action builder iterating live Player zones.
     */
    @Suppress("CyclomaticComplexMethod") // inherent complexity — action types × zone rails
    fun buildNaiveActionsFromSnapshot(
        seatId: Int,
        snap: GsmSnapshot,
        bridge: GameBridge,
    ): ActionsAvailableReq {
        val builder = ActionsAvailableReq.newBuilder()
        val player = bridge.getPlayer(SeatId(seatId)) ?: return passOnlyActions()
        val hand = snap.zones[ZoneIds.handOf(seatId)]?.contents.orEmpty()
        val battlefield = snap.zones[ZoneIds.BATTLEFIELD]?.contents.orEmpty()

        // Battlefield permanents: ActivateMana for untapped sources (own only).
        for (fid in battlefield) {
            val cardSnap = snap.objects[fid] ?: continue
            if (cardSnap.controller.value != seatId) continue
            if (cardSnap.tapped || !cardSnap.hasManaAbilities) continue
            val forgeCard = bridge.findCard(fid) ?: continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            builder.addAllActions(
                ActivatedActionEmitter.buildActivateManaAction(
                    forgeCard,
                    instanceId,
                    cardSnap.grpId,
                    { c -> snap.boundCards[ForgeCardId(c.id)]?.data },
                    { c, d -> bridge.abilityRegistryFor(c, d) },
                ),
            )
        }

        // Hand cards: lands still need secondary spell-face presentation.
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            if (!cardSnap.isLand) continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            emitPlayLandAction(builder, instanceId, cardSnap.grpId, canPlay = false)
            if (cardSnap.isAdventureCard || cardSnap.isOmenCard) {
                val forgeCard = bridge.findCard(fid) ?: continue
                val player = bridge.getPlayer(SeatId(seatId)) ?: continue
                val castable = getAllCastableAbilities(forgeCard, player, checkTiming = false)
                addSecondaryFaceCastActions(
                    forgeCard,
                    player,
                    instanceId,
                    cardSnap.grpId,
                    cardSnap,
                    builder,
                    castable,
                )
            }
        }

        // Hand cards: non-land spells (Cast before Activate_add3 — client uses
        // emission order for text assignment), then CastAdventure per card.
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            if (cardSnap.isLand) continue
            val forgeCard = bridge.findCard(fid) ?: continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            val grpId = cardSnap.grpId
            val castable = getAllCastableAbilities(forgeCard, player, checkTiming = false)
            if (cardSnap.isRoom) {
                addRoomCastActions(
                    card = forgeCard,
                    player = player,
                    instanceId = instanceId,
                    builder = builder,
                    checkLegality = false,
                    castable = castable,
                )
                continue
            }
            builder.addActions(
                buildNaiveCastAction(
                    card = forgeCard,
                    sa = choosePrimaryHandCastAbility(forgeCard, castable),
                    instanceId = instanceId,
                    grpId = grpId,
                    player = player,
                    cardData = snap.boundCards[fid]?.data,
                ),
            )
            if (cardSnap.isAdventureCard) {
                val adventureSa = forgeCard.getState(CardStateName.Secondary)?.nonManaAbilities?.firstOrNull()
                val advAction = adventureSa?.let { buildAdventureAction(it, player, instanceId, grpId, checkLegality = false) }
                if (advAction != null) {
                    builder.addActions(advAction)
                }
            }
            if (cardSnap.isOmenCard) {
                val omenSa = castable.firstOrNull { it.isOmen }
                omenSa
                    ?.let { buildOmenAction(it, player, instanceId, checkLegality = false) }
                    ?.let(builder::addActions)
            }
        }

        // Transition/remote GSMs have no inactive-action channel. Keep
        // zone-cast affordances (Flashback and static MayPlay grants) in the
        // same presentation rail as hand cards; the real priority projection
        // below still separates executable and inactive actions.
        addZoneCastActionsFromSnap(
            seatId = seatId,
            snap = snap,
            builder = builder,
            bridge = bridge,
            candidates = null,
            addOffer = { action, _, _, _, _ -> builder.addActions(action) },
            autoTapSolution = { _, _, _ -> null },
            presentationOnly = true,
        )

        // Modal DFC back faces (spell side only — the land side is never
        // playable in naive mode).
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            addMdfcFaceActions(
                card = forgeCard,
                player = player,
                instanceId = bridge.getOrAllocInstanceId(fid).value,
                parentGrpId = cardSnap.grpId,
                cardRepository = bridge.cardRepository,
                builder = builder,
                checkLegality = false,
            )
        }

        // Pass + FloatMana always available
        builder.addActions(Action.newBuilder().setActionType(ActionType.Pass))
        builder.addActions(Action.newBuilder().setActionType(ActionType.FloatMana))

        log.trace(
            "buildNaiveActionsFromSnapshot: seat={} mana={} lands={} casts={} total={}",
            seatId,
            builder.actionsList.count { it.actionType == ActionType.ActivateMana },
            builder.actionsList.count { it.actionType == ActionType.Play_add3 },
            builder.actionsList.count { it.actionType == ActionType.Cast },
            builder.actionsCount,
        )
        return builder.build()
    }

    /**
     * Build [ActionsAvailableReq] from a pre-captured [GsmSnapshot].
     *
     * Zone iteration and card-identity reads come from the snapshot (immutable,
     * race-free). Candidate legality comes from [PriorityActionCandidates].
     * This is the production action-emission path; permissive opponent-turn /
     * remote-frame embedding uses [buildNaiveActionsFromSnapshot].
     */
    fun buildFromSnapshot(
        seatId: Int,
        snap: GsmSnapshot,
        bridge: GameBridge,
    ): ActionsAvailableReq = buildProjectionFromSnapshot(seatId, snap, bridge).actions

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NoNameShadowing") // action families × zone-specific shapes.
    fun buildProjectionFromSnapshot(
        seatId: Int,
        snap: GsmSnapshot,
        bridge: GameBridge,
        priorityCandidates: PriorityActionCandidates? = null,
    ): ActionProjection {
        val builder = ActionsAvailableReq.newBuilder()
        val offers = mutableListOf<ActionOffer>()
        val player = bridge.getPlayer(SeatId(seatId))
        val candidates = priorityCandidates ?: bridge.getGame()?.let { game -> player?.let { PriorityActionCandidates.query(game, it) } }

        fun bindOffer(
            action: Action,
            command: PlayerAction,
            stackAbilityGrpId: Int? = null,
            forgeAbilityId: Int? = null,
            spellGrpId: Int? = null,
        ) {
            val castCandidates =
                (command as? PlayerAction.CastSpell)
                    ?.ability
                    ?.hostCard
                    ?.let { candidates?.forCard(it)?.casts }
                    .orEmpty()
            offers += ActionOffer(action, command, stackAbilityGrpId, forgeAbilityId, spellGrpId, castCandidates.toList())
        }

        fun addOffer(
            action: Action,
            command: PlayerAction,
            stackAbilityGrpId: Int? = null,
            forgeAbilityId: Int? = null,
            spellGrpId: Int? = null,
        ) {
            builder.addActions(action)
            bindOffer(action, command, stackAbilityGrpId, forgeAbilityId, spellGrpId)
        }

        val handZoneId = ZoneIds.handOf(seatId)
        val hand = snap.zones[handZoneId]?.contents.orEmpty()
        val battlefield = snap.zones[ZoneIds.BATTLEFIELD]?.contents.orEmpty()

        fun autoTapForCost(
            player: Player,
            cost: ManaCost,
            ability: SpellAbility,
        ): AutoTapSolution? =
            buildAutoTapSolution(
                cost,
                player,
                idResolver = { forgeCardId -> bridge.getOrAllocInstanceId(forgeCardId) },
                grpIdResolver = { c -> GrpId(bridge.resolveGrpId(c, bridge.instanceId(c))) },
                cardDataLookup = { bridge.cardRepository.findByGrpId(it.value) },
                abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                ability = ability,
            )

        // --- Battlefield: ActivateMana + Activate (own permanents only) ---
        for (fid in battlefield) {
            val card = snap.objects[fid] ?: continue
            if (card.controller.value != seatId) continue

            val instanceId = bridge.getOrAllocInstanceId(fid).value
            val grpId = card.grpId

            if (!card.tapped && card.hasManaAbilities) {
                val forgeCard = bridge.findCard(fid) ?: continue
                for (
                manaAction in
                ActivatedActionEmitter.buildActivateManaActions(
                    forgeCard,
                    instanceId,
                    grpId,
                    { c -> snap.boundCards[ForgeCardId(c.id)]?.data },
                    { c, d -> bridge.abilityRegistryFor(c, d) },
                    candidates?.forCard(forgeCard)?.manaAbilities ?: emptyList(),
                )
                ) {
                    addOffer(
                        manaAction.action,
                        PlayerAction.ActivateMana(fid, manaAction.abilityIndex, ability = manaAction.ability),
                    )
                }
            } else if (card.tapped && card.hasManaAbilities) {
                val forgeCard = bridge.findCard(fid) ?: continue
                for (
                manaAction in
                ActivatedActionEmitter.buildActivateManaActions(
                    forgeCard,
                    instanceId,
                    grpId,
                    { c -> snap.boundCards[ForgeCardId(c.id)]?.data },
                    { c, d -> bridge.abilityRegistryFor(c, d) },
                    candidates?.forCard(forgeCard)?.manaAbilities ?: emptyList(),
                )
                ) {
                    addOffer(
                        manaAction.action,
                        PlayerAction.ActivateMana(fid, manaAction.abilityIndex, ability = manaAction.ability),
                    )
                }
                builder.addAllInactiveActions(
                    ActivatedActionEmitter.buildInactiveActivateManaActions(
                        forgeCard,
                        instanceId,
                        grpId,
                        { c -> snap.boundCards[ForgeCardId(c.id)]?.data },
                        { c, d -> bridge.abilityRegistryFor(c, d) },
                    ),
                )
            }

            if (card.hasNonManaActivatedAbilities) {
                val forgeCard = bridge.findCard(fid) ?: continue
                val player = bridge.getPlayer(SeatId(seatId)) ?: continue
                ActivatedActionEmitter.emitPlayableNonManaActivatedAbilities(
                    builder = builder,
                    card = forgeCard,
                    player = player,
                    instanceId = { instanceId },
                    grpId = { c -> if (c === forgeCard) grpId else bridge.resolveGrpId(c, bridge.instanceId(c)) },
                    cardData = { id -> bridge.cardRepository.findByGrpId(id) },
                    envelope = ActivatedActionEmitter.Envelope.PERMANENT_SOURCE,
                    abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                    autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
                    skipSpecialTurnFaceUp = true,
                    abilities = candidates?.forCard(forgeCard)?.activations ?: emptyList(),
                    onActive = { action, abilityIndex, ability, abilityGrpId ->
                        bindOffer(
                            action,
                            PlayerAction.ActivateAbility(fid, abilityIndex, ability = ability),
                            abilityGrpId.takeIf { it != 0 },
                            ability.id,
                        )
                    },
                )
            }
        }

        // --- Battlefield: room door casts (for the unlocked-door's locked sibling) ---
        // A bf Room with one door already unlocked still offers CastLeftRoom /
        // CastRightRoom for the still-locked door. Stack/resolve runs the same
        // way as a hand cast — the cast emits a new stack iid that resolves
        // back onto the same bf room. Once both doors are unlocked
        // `getLockedRooms()` returns empty and no offer fires.
        for (fid in battlefield) {
            val cardSnap = snap.objects[fid] ?: continue
            if (cardSnap.controller.value != seatId) continue
            if (!cardSnap.isRoom) continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            if (forgeCard.lockedRooms.isEmpty()) continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            addRoomCastActions(
                forgeCard,
                player,
                instanceId,
                builder,
                checkLegality = true,
                castable = candidates?.forCard(forgeCard)?.casts ?: emptyList(),
                autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
            ) { action, abilityIndex, ability ->
                bindOffer(action, PlayerAction.CastSpell(fid, abilityIndex, ability = ability))
            }
        }

        // --- Battlefield: Special_TurnFaceUp for supported face-down creatures ---
        // A controller's supported face-down permanent surfaces a dedicated
        // Special_TurnFaceUp_add3 action carrying the per-card "Turn face up"
        // ability grpId on `alternativeGrpId` and the printed disguise cost
        // as `manaCost`. Distinct from `Activate_add3` — the client routes
        // it through a different UI flow (card-flip animation).
        for (fid in battlefield) {
            val cardSnap = snap.objects[fid] ?: continue
            if (cardSnap.controller.value != seatId) continue
            val faceDownKind = cardSnap.faceDownKind ?: continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            val cardData = snap.boundCards[fid]?.data
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            addSpecialTurnFaceUpActions(
                card = forgeCard,
                player = player,
                instanceId = instanceId,
                cardData = cardData,
                fallbackAlternativeGrpId =
                    when (faceDownKind) {
                        // Cloak has no printed turn-up row, so the projected Ward {2}
                        // ability supplies its client-addressable fallback identity.
                        leyline.game.snapshot.FaceDownKind.Cloak ->
                            leyline.game.data.KeywordAbilityIds.WARD_TWO
                        leyline.game.snapshot.FaceDownKind.Disguise ->
                            bridge.cardRepository.findKeywordAbilityGrpId(
                                cardSnap.grpId,
                                leyline.game.data.KeywordAbilityIds.DISGUISE,
                            ) ?: 0
                        leyline.game.snapshot.FaceDownKind.ManifestDread ->
                            leyline.game.data.KeywordAbilityIds.MANIFEST_DREAD
                    },
                abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                builder = builder,
                abilities = candidates?.forCard(forgeCard)?.activations ?: emptyList(),
                onActive = { action, abilityIndex, ability ->
                    bindOffer(
                        action,
                        PlayerAction.ActivateAbility(fid, abilityIndex, ability = ability),
                        forgeAbilityId = ability.id,
                    )
                },
            )
        }
        // --- Hand: lands ---
        for (fid in hand) {
            val card = snap.objects[fid] ?: continue
            if (!card.isLand) continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            val grpId = card.grpId
            val landAbility = bridge.findCard(fid)?.let { candidates?.forCard(it)?.landAbility }
            val canPlayLand = landAbility != null && player?.canPlayLand(landAbility.hostCard, false, landAbility) == true
            emitPlayLandAction(builder, instanceId, grpId, canPlayLand) { action ->
                bindOffer(action, PlayerAction.PlayLand(fid))
            }
            if (card.isAdventureCard || card.isOmenCard) {
                val player = bridge.getPlayer(SeatId(seatId)) ?: continue
                val forgeCard = bridge.findCard(fid) ?: continue
                val castable = candidates?.forCard(forgeCard)?.casts ?: emptyList()
                addSecondaryFaceCastActions(
                    forgeCard,
                    player,
                    instanceId,
                    grpId,
                    card,
                    builder,
                    castable,
                    autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
                ) { action, index, ability ->
                    bindOffer(
                        action,
                        PlayerAction.CastSpell(fid, index, ability = ability),
                        spellGrpId = linkedFaceGrpId(snap.boundCards[fid], action.actionType),
                    )
                }
            }
        }

        // --- Hand: non-land spells (Cast + CastAdventure) ---
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            if (cardSnap.isLand) continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            // Rooms ride a dedicated CastLeftRoom / CastRightRoom rail handled
            // below — they have no plain `Cast` offer. The unlock SAs are part
            // of `getAllCastableAbilities` (so cast index resolution works),
            // but the hand-cast emit must skip them.
            if (cardSnap.isRoom) {
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                addRoomCastActions(
                    forgeCard,
                    player,
                    instanceId,
                    builder,
                    checkLegality = true,
                    castable = candidates?.forCard(forgeCard)?.casts ?: emptyList(),
                    autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
                ) { action, abilityIndex, ability ->
                    bindOffer(action, PlayerAction.CastSpell(fid, abilityIndex, ability = ability))
                }
                continue
            }
            val castable = candidates?.forCard(forgeCard)?.casts ?: emptyList()
            if (forgeCard.isSplitCard) {
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                addSplitCastActions(
                    forgeCard,
                    player,
                    instanceId,
                    builder,
                    castable,
                    bridge.cardRepository,
                ) { action, index, ability, faceGrpId ->
                    bindOffer(
                        action,
                        PlayerAction.CastSpell(fid, index, ability = ability),
                        spellGrpId = faceGrpId,
                    )
                }
                continue
            }
            val sa = choosePrimaryHandCastAbility(forgeCard, castable)
            if (sa == null) {
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                emitUncastableHandCost(
                    card = forgeCard,
                    player = player,
                    instanceId = instanceId,
                    grpId = cardSnap.grpId,
                    cardData = snap.boundCards[fid]?.data,
                    builder = builder,
                )
                addSecondaryFaceCastActions(
                    forgeCard,
                    player,
                    instanceId,
                    cardSnap.grpId,
                    cardSnap,
                    builder,
                    castable,
                    autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
                ) { action, index, ability ->
                    bindOffer(
                        action,
                        PlayerAction.CastSpell(fid, index, ability = ability),
                        spellGrpId = linkedFaceGrpId(snap.boundCards[fid], action.actionType),
                    )
                }
                continue
            }
            val abilityIndex = castable.indexOfFirst { it === sa }
            val canPay = canExecute(sa, player)
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            val grpId = cardSnap.grpId
            val preferAltCostFirst = castable.any { it.isCastFaceDown }
            val displayedManaCost = displayedHandCastCost(forgeCard, sa, player, snap.boundCards[fid]?.data)

            if (preferAltCostFirst) {
                // The face-cast modal defaults to the first Cast offer even
                // when the visual click lands on the second card. Put Disguise's
                // face-down option first so the modal commit submits the
                // `alternativeGrpId=307` action instead of the printed spell.
                addHandAltCostCastActions(
                    bridge = bridge,
                    card = forgeCard,
                    player = player,
                    instanceId = instanceId,
                    grpId = grpId,
                    altCosts = snap.boundCards[fid]?.altCosts ?: emptyList(),
                    builder = builder,
                    castable = castable,
                    autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
                    onActive = { action, index, ability ->
                        bindOffer(action, PlayerAction.CastSpell(fid, index, ability = ability))
                    },
                )
            }

            if (!canPay) {
                val inactiveBuilder =
                    castActionBuilder(ActionType.Cast, sa)
                        .setInstanceId(instanceId)
                        .setGrpId(grpId)
                        .setFacetId(instanceId)
                        .addAllManaCost(displayedManaCost)
                builder.addInactiveActions(inactiveBuilder)
                if (!preferAltCostFirst) {
                    addHandAltCostCastActions(
                        bridge = bridge,
                        card = forgeCard,
                        player = player,
                        instanceId = instanceId,
                        grpId = grpId,
                        altCosts = snap.boundCards[fid]?.altCosts ?: emptyList(),
                        builder = builder,
                        castable = castable,
                        autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
                        onActive = { action, index, ability ->
                            bindOffer(action, PlayerAction.CastSpell(fid, index, ability = ability))
                        },
                    )
                }
                // Adventure / Omen offers are independent of the main face's
                // payability — emit them even when the main cast is unaffordable.
                addSecondaryFaceCastActions(
                    forgeCard,
                    player,
                    instanceId,
                    grpId,
                    cardSnap,
                    builder,
                    castable,
                    autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
                ) { action, index, ability ->
                    bindOffer(
                        action,
                        PlayerAction.CastSpell(fid, index, ability = ability),
                        spellGrpId = linkedFaceGrpId(snap.boundCards[fid], action.actionType),
                    )
                }
                continue
            }

            val actionBuilder =
                castActionBuilder(ActionType.Cast, sa)
                    .setInstanceId(instanceId)
                    .setGrpId(grpId)
                    .setFacetId(instanceId)
                    .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))

            actionBuilder.addAllManaCost(displayedManaCost)
            val displayCost = CastDisplayCost.of(sa, player)
            if (displayCost != null && !displayCost.isNoCost) {
                autoTapForCost(player, displayCost, sa)?.let(actionBuilder::setAutoTapSolution)
            }
            addOffer(actionBuilder.build(), PlayerAction.CastSpell(fid, abilityIndex, ability = sa))

            if (!preferAltCostFirst) {
                addHandAltCostCastActions(
                    bridge = bridge,
                    card = forgeCard,
                    player = player,
                    instanceId = instanceId,
                    grpId = grpId,
                    altCosts = snap.boundCards[fid]?.altCosts ?: emptyList(),
                    builder = builder,
                    castable = castable,
                    autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
                    onActive = { action, index, ability ->
                        bindOffer(action, PlayerAction.CastSpell(fid, index, ability = ability))
                    },
                )
            }

            addSecondaryFaceCastActions(
                forgeCard,
                player,
                instanceId,
                grpId,
                cardSnap,
                builder,
                castable,
                autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
            ) { action, index, ability ->
                bindOffer(
                    action,
                    PlayerAction.CastSpell(fid, index, ability = ability),
                    spellGrpId = linkedFaceGrpId(snap.boundCards[fid], action.actionType),
                )
            }
        }

        // --- Hand: modal DFC back-face actions (spell and land faces) ---
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            val instanceId = bridge.getOrAllocInstanceId(fid).value
            addMdfcFaceActions(
                card = forgeCard,
                player = player,
                instanceId = instanceId,
                parentGrpId = cardSnap.grpId,
                cardRepository = bridge.cardRepository,
                builder = builder,
                checkLegality = true,
                castable = candidates?.forCard(forgeCard)?.casts ?: emptyList(),
                mdfcLandAbility = candidates?.forCard(forgeCard)?.mdfcLandAbility,
                autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
                onCast = { action, index, ability ->
                    bindOffer(action, PlayerAction.CastSpell(fid, index, ability = ability))
                },
                onLand = { action ->
                    bindOffer(action, PlayerAction.PlayLand(fid, candidates?.forCard(forgeCard)?.mdfcLandAbility))
                },
            )
        }

        // --- Hand: non-battlefield activated abilities (Channel, Ninjutsu, etc.) ---
        // Plot is intentionally NOT here — Plot's hand SA rides the Cast-with-alt-cost
        // rail via [addHandAltCostCastActions] (mirroring Warp / Sneak).
        for (fid in hand) {
            val cardSnap = snap.objects[fid] ?: continue
            if (!cardSnap.hasNonManaActivatedAbilities) continue
            val player = bridge.getPlayer(SeatId(seatId)) ?: continue
            val forgeCard = bridge.findCard(fid) ?: continue
            ActivatedActionEmitter.emitPlayableNonManaActivatedAbilities(
                builder = builder,
                card = forgeCard,
                player = player,
                instanceId = { bridge.getOrAllocInstanceId(fid).value },
                grpId = { c -> if (c === forgeCard) cardSnap.grpId else bridge.resolveGrpId(c, bridge.instanceId(c)) },
                cardData = { id -> bridge.cardRepository.findByGrpId(id) },
                envelope = ActivatedActionEmitter.Envelope.ABILITY_ONLY,
                abilityRegistryLookup = { c, d -> bridge.abilityRegistryFor(c, d) },
                autoTapSolution = { cost, ability -> autoTapForCost(player, cost, ability) },
                abilities = candidates?.forCard(forgeCard)?.activations ?: emptyList(),
                onActive = { action, abilityIndex, ability, abilityGrpId ->
                    bindOffer(
                        action,
                        PlayerAction.ActivateAbility(fid, abilityIndex, ability = ability),
                        abilityGrpId.takeIf { it != 0 },
                        ability.id,
                    )
                },
            )
        }

        // --- Zone casts (graveyard, exile, command) ---
        addZoneCastActionsFromSnap(
            seatId,
            snap,
            builder,
            bridge,
            candidates,
            ::addOffer,
            { player, cost, ability -> autoTapForCost(player, cost, ability) },
        )

        // --- Graveyard: activated abilities (Unearth, Embalm, Eternalize) ---
        addGraveyardActivatedActionsFromSnap(seatId, snap, builder, bridge, candidates, ::bindOffer, ::autoTapForCost)

        // Pass + FloatMana always available
        addOffer(Action.newBuilder().setActionType(ActionType.Pass).build(), PlayerAction.PassPriority)
        addOffer(Action.newBuilder().setActionType(ActionType.FloatMana).build(), PlayerAction.PassPriority)

        val manaCount = builder.actionsList.count { it.actionType == ActionType.ActivateMana }
        val landCount = builder.actionsList.count { it.actionType == ActionType.Play_add3 }
        val castCount = builder.actionsList.count { it.actionType == ActionType.Cast }
        val activateCount = builder.actionsList.count { it.actionType == ActionType.Activate_add3 }
        val inactiveCount = builder.inactiveActionsCount
        log.trace(
            "buildFromSnapshot: seat={} mana={} activate={} lands={} casts={} inactive={} total={}",
            seatId,
            manaCount,
            activateCount,
            landCount,
            castCount,
            inactiveCount,
            builder.actionsCount,
        )

        check(builder.actionsCount == offers.size) { "Every active priority action must have an executable offer" }
        return ActionProjection(builder.build(), offers)
    }

    /**
     * Choose the primary hand-cast ability when Forge surfaces several
     * castable variants for one card. Shared by the snapshot projection and
     * the naive opponent-turn list so both emit the same Cast offer.
     *
     * Prefers the WithoutManaCost face (face-down disguise casts), then — for
     * an AlternateAdditionalCost card, which expands into one castable variant
     * per additional-cost option — the variant with the lowest mana cost: the
     * one whose additional cost is non-mana, i.e. the printed cost. The offer
     * is a single Cast at the base cost; the option choice rides a
     * ChooseOrCost CastingTimeOptionsReq after the cast is submitted.
     */
    private fun choosePrimaryHandCastAbility(
        card: Card,
        castable: List<SpellAbility>,
    ): SpellAbility? {
        val primary = castable.filterNot { it.isAdventure || it.isOmen }
        primary.firstOrNull { it.hasParam("WithoutManaCost") }?.let { return it }
        if (card.keywords.none { it.original.startsWith("AlternateAdditionalCost") }) {
            return primary.firstOrNull()
        }
        return primary
            .filter { it.isSpell && it.alternativeCost == null }
            .minByOrNull { it.payCosts?.totalMana?.cmc ?: Int.MAX_VALUE }
            ?: primary.firstOrNull()
    }

    /**
     * Cost-only inactive Cast offer for a hand card that has no timing-legal cast.
     *
     * The client takes a card's displayed cost from its cheapest offer and falls back
     * to the printed cost when it has none, so without this a state-derived reduction
     * disappears whenever the card cannot be cast at that moment. Timing is resolved
     * again without its filter purely to pick the ability whose cost to show.
     */
    private fun emitUncastableHandCost(
        card: Card,
        player: Player,
        instanceId: Int,
        grpId: Int,
        cardData: CardData?,
        builder: ActionsAvailableReq.Builder,
    ) {
        val untimed = getAllCastableAbilities(card, player, checkTiming = false)
        val sa = choosePrimaryHandCastAbility(card, untimed) ?: return
        builder.addInactiveActions(
            castActionBuilder(ActionType.Cast, sa)
                .setInstanceId(instanceId)
                .setGrpId(grpId)
                .setFacetId(instanceId)
                .addAllManaCost(displayedHandCastCost(card, sa, player, cardData)),
        )
    }

    /**
     * Displayed cost for a hand-cast offer. Shared by the snapshot projection
     * and the naive opponent-turn list so both show the same mana cost.
     *
     * AlternateAdditionalCost variants each bake their own option's cost into
     * payCosts (Forge copies the base SA per option, then adds that option's
     * Cost), and which variant survives depends on board state — so the host
     * card's own printed mana cost (from card data) is the variant-independent
     * display. The option choice rides the post-submit ChooseOrCost prompt.
     */
    private fun displayedHandCastCost(
        card: Card,
        sa: SpellAbility?,
        player: Player,
        cardData: CardData?,
    ): List<ManaRequirement> =
        if (card.keywords.any { it.original.startsWith("AlternateAdditionalCost") }) {
            CastDisplayCost.requirements(null, player, cardData)
        } else {
            CastDisplayCost.requirements(sa, player, cardData)
        }

    /**
     * Zone casts (graveyard, exile, command) — picks the first castable SA per
     * card and dispatches via [CastRails]. Each (zone, rail-bucket) pair is
     * declared in [zoneRailBuckets]; rails matching the SA win, otherwise the
     * fallback path emits a printed-cost or best-effort shape. Unpayable zone
     * casts stay visible as inactive actions so automation does not repeatedly
     * submit a cast that Forge will bounce back to the source zone.
     */

    /** UI affordances only: these never register executable priority offers. */
    fun captureZoneCastDisplayInfos(
        snap: GsmSnapshot,
        bridge: GameBridge,
        viewingSeatId: Int? = null,
    ): List<ActionInfo> =
        snap.seats.flatMap { seat ->
            zoneCastDisplayActions(seat.seatId.value, snap, bridge).actionsList.mapNotNull { action ->
                val fid = bridge.getForgeCardId(InstanceId(action.instanceId))
                val card = snap.objects[fid]
                if (viewingSeatId != null &&
                    seat.seatId.value != viewingSeatId &&
                    (card == null || card.isForetold || card.faceDownKind != null)
                ) {
                    null
                } else {
                    ActionInfo
                        .newBuilder()
                        .setSeatId(seat.seatId.value)
                        .setAction(stripActionForGsm(action))
                        .build()
                }
            }
        }

    fun zoneCastDisplayActions(
        seatId: Int,
        snap: GsmSnapshot,
        bridge: GameBridge,
    ): ActionsAvailableReq {
        val builder = ActionsAvailableReq.newBuilder()
        addZoneCastActionsFromSnap(
            seatId,
            snap,
            builder,
            bridge,
            null,
            addOffer = { _, _, _, _, _ -> error("Display capture must not register offers") },
            autoTapSolution = { _, _, _ -> null },
            presentationOnly = true,
        )
        return builder.build()
    }

    private fun addZoneCastActionsFromSnap(
        seatId: Int,
        snap: GsmSnapshot,
        builder: ActionsAvailableReq.Builder,
        bridge: GameBridge,
        candidates: PriorityActionCandidates?,
        addOffer: (Action, PlayerAction, Int?, Int?, Int?) -> Unit,
        autoTapSolution: (Player, ManaCost, SpellAbility) -> AutoTapSolution?,
        presentationOnly: Boolean = false,
    ) {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return
        for ((zoneId, rails) in zoneRailBuckets) {
            val zone = snap.zones[zoneId] ?: continue
            for (fid in zone.contents) {
                val forgeCard = bridge.findCard(fid) ?: continue
                val cardSnap = snap.objects[fid]
                val mayPlayGranted = forgeCard.mayPlay(player).isNotEmpty()
                // Shared graveyard/exile buckets contain both players' cards.
                // A card owned by the other seat belongs on this viewer's rail
                // only when Forge has actually granted this player permission
                // to play it. Without this guard, an opponent's Harmonize card
                // discarded by Duress appeared beside the local player's hand.
                if (cardSnap?.owner?.value != seatId && !mayPlayGranted) continue
                // Tablet of Discovery and similar effects grant a temporary
                // "you may play" permission to a milled/exiled land. Land
                // plays are a separate action family from Cast and must be
                // projected even though the hand loop never sees this card.
                if (mayPlayGranted && forgeCard.getOriginalState(CardStateName.Original)?.type?.isLand == true) {
                    val landAbility = buildLandPlayAbility(forgeCard, player) ?: continue
                    val canPlay =
                        presentationOnly ||
                            landAbility.canPlay()
                    val actionBuilder =
                        playLandActionBuilder(
                            bridge.getOrAllocInstanceId(fid).value,
                            cardSnap?.grpId ?: bridge.resolveGrpId(forgeCard, bridge.getOrAllocInstanceId(fid).value),
                        )
                    val action = actionBuilder.setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Play_add3)).build()
                    when {
                        canPlay && presentationOnly -> builder.addActions(action)
                        canPlay -> addOffer(action, PlayerAction.PlayLand(fid), null, null, null)
                        else -> builder.addInactiveActions(action.toBuilder().clearShouldStop())
                    }
                    val backAbility = buildMdfcBackLandAbility(forgeCard, player)
                    if (backAbility != null) {
                        val backAction =
                            Action
                                .newBuilder()
                                .setActionType(ActionType.PlayMdfc)
                                .setInstanceId(bridge.getOrAllocInstanceId(fid).value)
                                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.PlayMdfc))
                                .build()
                        when {
                            presentationOnly -> builder.addActions(backAction)
                            backAbility.canPlay() -> addOffer(backAction, PlayerAction.PlayLand(fid, backAbility), null, null, null)
                            else -> builder.addInactiveActions(backAction.toBuilder().clearShouldStop())
                        }
                    }
                    addZoneSecondaryFaceCastActions(
                        card = forgeCard,
                        player = player,
                        instanceId = bridge.getOrAllocInstanceId(fid).value,
                        parentGrpId = cardSnap?.grpId ?: bridge.resolveGrpId(forgeCard, bridge.getOrAllocInstanceId(fid).value),
                        bound = snap.boundCards[fid],
                        cardSnap = cardSnap,
                        castable = candidates?.forCard(forgeCard)?.casts ?: emptyList(),
                        builder = builder,
                        presentationOnly = presentationOnly,
                        autoTapSolution = autoTapSolution,
                    ) { action, abilityIndex, ability, spellGrpId ->
                        addOffer(action, PlayerAction.CastSpell(fid, abilityIndex, ability = ability), null, null, spellGrpId)
                    }
                    continue
                }
                // A MayPlay-granted Room in exile/graveyard is still a two-door
                // spell, including when exile keeps its current Forge state
                // face down. A generic Cast offer silently picks the left
                // door and bypasses Arena's modal face chooser.
                if (cardSnap?.isRoom == true && mayPlayGranted) {
                    val instanceId = bridge.getOrAllocInstanceId(fid).value
                    val roomCandidates = candidates?.forCard(forgeCard)?.casts ?: emptyList()
                    addRoomCastActions(
                        card = forgeCard,
                        player = player,
                        instanceId = instanceId,
                        builder = builder,
                        checkLegality = !presentationOnly,
                        castable =
                            if (presentationOnly) getAllCastableAbilities(forgeCard, player, checkTiming = false) else roomCandidates,
                        autoTapSolution = { cost, ability -> autoTapSolution(player, cost, ability) },
                        emitActiveAction = presentationOnly,
                    ) { action, abilityIndex, ability ->
                        if (!presentationOnly) {
                            addOffer(action, PlayerAction.CastSpell(fid, abilityIndex, ability = ability), null, null, null)
                        }
                    }
                    continue
                }
                val castable = candidates?.forCard(forgeCard)?.casts ?: emptyList()
                // The timed candidate set is intentionally what drives active
                // offers.  It is not sufficient for the card rail, though:
                // Forge removes a sorcery-speed flashback/escape/retrace SA
                // from this set while the player is outside a legal cast
                // window.  Arena still sends the inactive graveyard offer so
                // the card remains visible beside the hand (and continues to
                // show its current cost).  Recover the untimed candidates for
                // this presentation-only path, but only accept a SA that
                // actually belongs to this zone's rail; otherwise a normal
                // hand spell would leak into a graveyard/exile rail.
                val castableRailSa = castable.firstOrNull { candidate -> rails.any { it.saPredicate(candidate) } }
                val untimed =
                    if (castableRailSa == null || rails.isEmpty()) {
                        // Keep Forge's zone/additional-cost legality check.
                        // Only the timing predicate is intentionally bypassed;
                        // e.g. Escape must remain hidden when its required
                        // graveyard exiles are unavailable.
                        getAllCastableAbilities(forgeCard, player, checkTiming = false)
                            .filter { candidate ->
                                candidate.setActivatingPlayer(player)
                                candidate.payCosts?.let { CostPayment.canPayAdditionalCosts(it, candidate, false) } != false
                            }
                    } else {
                        castable
                    }
                // A static MayPlay effect (Tablet of Discovery is the common
                // example) grants a normal spell permission in a zone; it does
                // not carry one of the keyword alt-cost rails.
                val sa =
                    if (rails.isEmpty()) {
                        castable.firstOrNull() ?: untimed.firstOrNull()
                    } else {
                        castableRailSa
                            // A plain MayPlay permission produces a normal spell
                            // ability, so it intentionally matches none of the
                            // keyword-specific exile rails. Preserve Forge's
                            // timed candidate here: choosing the separately
                            // recomputed untimed copy makes the identity check
                            // below classify a legal, payable cast as inactive.
                            ?: castable.firstOrNull().takeIf { mayPlayGranted }
                            ?: untimed.firstOrNull { candidate ->
                                rails.any { it.saPredicate(candidate) } || mayPlayGranted
                            }
                    } ?: continue
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                val sourceGrpId =
                    cardSnap?.grpId
                        ?: bridge.resolveGrpId(forgeCard, instanceId)
                val bound = snap.boundCards[fid]
                val rail = rails.firstOrNull { it.saPredicate(sa) }
                // An SA recovered only from the untimed set is presentation
                // data, never an executable priority offer.
                val executable =
                    presentationOnly ||
                        (castable.any { it === sa } && canExecute(sa, player))
                val omit = rail?.omitGrpIdAndFacetId == true
                val actionGrpId =
                    when (rail?.grpIdMode) {
                        ZoneCastGrpIdMode.OtherSide -> cardSnap?.othersideGrpId?.takeIf { it > 0 } ?: sourceGrpId
                        else -> sourceGrpId
                    }
                val actionFacetId =
                    when {
                        rail?.grpIdMode == ZoneCastGrpIdMode.OtherSide && cardSnap?.othersideGrpId?.takeIf { it > 0 } != null ->
                            bridge.getOrAllocInstanceId(FrameIdResolver.disturbBackForgeId(fid)).value
                        else -> instanceId
                    }

                val actionBuilder =
                    castActionBuilder(ActionType.Cast, sa)
                        .setInstanceId(instanceId)
                if (executable) {
                    actionBuilder.setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))
                }
                if (!omit) {
                    actionBuilder.setGrpId(actionGrpId)
                    actionBuilder.setFacetId(actionFacetId)
                }
                if (rail?.emitAlternativeSourceZcid == true) {
                    actionBuilder.setAlternativeSourceZcid(instanceId)
                }

                if (rail != null) {
                    configureZoneCastRailShape(actionBuilder, sa, rail, bound, player)
                } else {
                    configureZoneCastFallback(actionBuilder, sa, bound, player)
                }
                zoneCastAutoTapSolution(executable, sa, player, autoTapSolution)
                    ?.let(actionBuilder::setAutoTapSolution)
                if (executable) {
                    val action = actionBuilder.build()
                    if (presentationOnly) {
                        // Naive/transition actions are presentation-only and
                        // intentionally have no candidate/offer binding.
                        builder.addActions(action)
                    } else {
                        val abilityIndex = castable.indexOfFirst { it === sa }
                        check(abilityIndex >= 0) { "Zone cast ability is absent from its candidate set" }
                        addOffer(
                            action,
                            PlayerAction.CastSpell(fid, abilityIndex, ability = sa),
                            null,
                            null,
                            null,
                        )
                    }
                } else {
                    builder.addInactiveActions(actionBuilder)
                }
                if (mayPlayGranted) {
                    addZoneSecondaryFaceCastActions(
                        card = forgeCard,
                        player = player,
                        instanceId = instanceId,
                        parentGrpId = sourceGrpId,
                        bound = bound,
                        cardSnap = cardSnap,
                        castable = castable,
                        builder = builder,
                        presentationOnly = presentationOnly,
                        autoTapSolution = autoTapSolution,
                    ) { action, abilityIndex, ability, spellGrpId ->
                        addOffer(action, PlayerAction.CastSpell(fid, abilityIndex, ability = ability), null, null, spellGrpId)
                    }
                    addZoneMdfcBackFaceActions(
                        card = forgeCard,
                        player = player,
                        instanceId = instanceId,
                        parentGrpId = sourceGrpId,
                        cardRepository = bridge.cardRepository,
                        castable = castable,
                        builder = builder,
                        presentationOnly = presentationOnly,
                        autoTapSolution = autoTapSolution,
                    ) { action, command ->
                        addOffer(action, command, null, null, null)
                    }
                }
            }
        }
    }

    /** A MayPlay grant applies to every printed spell face, not only the first
     *  spell returned by Forge. Preserve Adventure/Omen's distinct protocol
     *  action and selected face identity outside the hand as well. */
    private fun addZoneSecondaryFaceCastActions(
        card: Card,
        player: Player,
        instanceId: Int,
        parentGrpId: Int,
        bound: BoundCard?,
        cardSnap: leyline.game.snapshot.CardSnapshot?,
        castable: List<SpellAbility>,
        builder: ActionsAvailableReq.Builder,
        presentationOnly: Boolean,
        autoTapSolution: (Player, ManaCost, SpellAbility) -> AutoTapSolution?,
        onActive: (Action, Int, SpellAbility, Int?) -> Unit,
    ) {
        val faces =
            listOfNotNull(
                ActionType.CastAdventure.takeIf { cardSnap?.isAdventureCard == true },
                ActionType.CastOmen.takeIf { cardSnap?.isOmenCard == true },
            )
        if (faces.isEmpty()) return
        val untimed = getAllCastableAbilities(card, player, checkTiming = false)
        for (type in faces) {
            val matches: (SpellAbility) -> Boolean =
                if (type == ActionType.CastAdventure) ({ it.isAdventure }) else ({ it.isOmen })
            val index = castable.indexOfFirst(matches)
            val ability = castable.getOrNull(index) ?: untimed.firstOrNull(matches) ?: continue
            ability.setActivatingPlayer(player)
            val executable = presentationOnly || (index >= 0 && canExecute(ability, player))
            val actionBuilder =
                castActionBuilder(type, ability)
                    .setInstanceId(instanceId)
                    .addAllManaCost(CastDisplayCost.requirements(ability, player, null))
            if (type == ActionType.CastAdventure) actionBuilder.setGrpId(parentGrpId)
            if (executable) {
                actionBuilder.setShouldStop(ShouldStopEvaluator.shouldStop(type))
                zoneCastAutoTapSolution(!presentationOnly, ability, player, autoTapSolution)
                    ?.let(actionBuilder::setAutoTapSolution)
                val action = actionBuilder.build()
                if (presentationOnly) {
                    builder.addActions(action)
                } else {
                    onActive(action, index, ability, linkedFaceGrpId(bound, type))
                }
            } else {
                builder.addInactiveActions(actionBuilder)
            }
        }
    }

    private fun addZoneMdfcBackFaceActions(
        card: Card,
        player: Player,
        instanceId: Int,
        parentGrpId: Int,
        cardRepository: CardRepository,
        castable: List<SpellAbility>,
        builder: ActionsAvailableReq.Builder,
        presentationOnly: Boolean,
        autoTapSolution: (Player, ManaCost, SpellAbility) -> AutoTapSolution?,
        onActive: (Action, PlayerAction) -> Unit,
    ) {
        if (!card.isModal || !card.hasState(CardStateName.Backside)) return
        val untimed = getAllCastableAbilities(card, player, checkTiming = false)
        val index = castable.indexOfFirst(::isMdfcBackSpell)
        val spell = castable.getOrNull(index) ?: untimed.firstOrNull(::isMdfcBackSpell)
        if (spell != null) {
            spell.setActivatingPlayer(player)
            val action = buildMdfcSpellAction(spell, player, instanceId, parentGrpId, cardRepository)
            if (action != null) {
                when {
                    presentationOnly -> builder.addActions(action)
                    index >= 0 && canExecute(spell, player) -> {
                        val payableAction = action.toBuilder()
                        zoneCastAutoTapSolution(true, spell, player, autoTapSolution)?.let(payableAction::setAutoTapSolution)
                        onActive(payableAction.build(), PlayerAction.CastSpell(ForgeCardId(card.id), index, ability = spell))
                    }
                    else -> builder.addInactiveActions(action.toBuilder().clearShouldStop())
                }
            }
        }
        val land = buildMdfcBackLandAbility(card, player) ?: return
        val action =
            Action
                .newBuilder()
                .setActionType(ActionType.PlayMdfc)
                .setInstanceId(instanceId)
                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.PlayMdfc))
                .build()
        when {
            presentationOnly -> builder.addActions(action)
            land.canPlay() -> onActive(action, PlayerAction.PlayLand(ForgeCardId(card.id), land))
            else -> builder.addInactiveActions(action.toBuilder().clearShouldStop())
        }
    }

    private fun zoneCastAutoTapSolution(
        executable: Boolean,
        ability: SpellAbility,
        player: Player,
        build: (Player, ManaCost, SpellAbility) -> AutoTapSolution?,
    ): AutoTapSolution? {
        if (!executable) return null
        val cost = CastDisplayCost.of(ability, player) ?: return null
        if (cost.isNoCost) return null
        return build(player, cost, ability)
    }

    /** Per-source-zone rail buckets for [addZoneCastActionsFromSnap]. Empty
     *  buckets (e.g. COMMAND) participate in iteration so cards there hit the
     *  fallback path — historically commander-cast shape. */
    private val zoneRailBuckets: List<Pair<Int, List<ZoneCastRail>>> =
        listOf(
            ZoneIds.EXILE to CastRails.fromExile,
            ZoneIds.P1_GRAVEYARD to CastRails.fromGraveyard,
            ZoneIds.P2_GRAVEYARD to CastRails.fromGraveyard,
            ZoneIds.COMMAND to emptyList(),
        )

    /**
     * Graveyard-zone activated abilities (Unearth, Embalm, Eternalize, …).
     *
     * Walks each own-graveyard card's `forgeCard.spellAbilities`, accepts
     * activated non-mana abilities whose `canPlay()` passes (Forge enforces
     * `ActivationZone$ Graveyard` and `SorcerySpeed$` checks). Emits
     * `Activate_add3` with the minimal envelope: `instanceId + abilityGrpId +
     * manaCost` (each mana slot echoes `abilityGrpId`). NO `grpId` /
     * `facetId` / `shouldStop` — graveyard activations omit all three.
     */
    @Suppress("CyclomaticComplexMethod") // mirrors the hand-zone activated-ability emit path; same shape, same complexity
    private fun addGraveyardActivatedActionsFromSnap(
        seatId: Int,
        snap: GsmSnapshot,
        builder: ActionsAvailableReq.Builder,
        bridge: GameBridge,
        candidates: PriorityActionCandidates?,
        addOffer: (Action, PlayerAction, Int?, Int?) -> Unit,
        autoTapSolution: (Player, ManaCost, SpellAbility) -> AutoTapSolution?,
    ) {
        val player = bridge.getPlayer(SeatId(seatId)) ?: return
        val graveyardZoneId =
            when (seatId) {
                1 -> ZoneIds.P1_GRAVEYARD
                2 -> ZoneIds.P2_GRAVEYARD
                else -> return
            }
        val zone = snap.zones[graveyardZoneId] ?: return
        for (fid in zone.contents) {
            val cardSnap = snap.objects[fid] ?: continue
            if (!cardSnap.hasNonManaActivatedAbilities) continue
            val forgeCard = bridge.findCard(fid) ?: continue
            val cardData = snap.boundCards[fid]?.data
            for ((abilityIndex, ability) in (candidates?.forCard(forgeCard)?.activations ?: emptyList()).withIndex()) {
                if (!ability.canPlay()) continue
                val canPay = canExecute(ability, player)
                val instanceId = bridge.getOrAllocInstanceId(fid).value
                val registry = bridge.abilityRegistryFor(forgeCard, cardData)
                val abilityGrpId = registry?.forSpellAbility(ability.definitionId) ?: 0
                val abilityCost = CastDisplayCost.of(ability, player) ?: ability.payCosts?.totalMana
                ActivatedActionEmitter.emitActivatedAbilityAction(
                    builder = builder,
                    instanceId = instanceId,
                    grpId = cardSnap.grpId,
                    abilityGrpId = abilityGrpId,
                    uniqueAbilityId = ActivatedActionEmitter.uniqueAbilityIdFor(cardData, abilityGrpId),
                    abilityCost = abilityCost,
                    autoTapSolution =
                        if (canPay && abilityCost != null && !abilityCost.isNoCost) {
                            autoTapSolution(player, abilityCost, ability)
                        } else {
                            null
                        },
                    canPay = canPay,
                    envelope = ActivatedActionEmitter.Envelope.ABILITY_ONLY,
                    nonManaCosts = ability.payCosts,
                    onActive = { action ->
                        addOffer(
                            action,
                            PlayerAction.ActivateAbility(fid, abilityIndex, ability = ability),
                            abilityGrpId.takeIf { it != 0 },
                            ability.id,
                        )
                    },
                )
            }
        }
    }

    private fun emitPlayLandAction(
        builder: ActionsAvailableReq.Builder,
        instanceId: Int,
        grpId: Int,
        canPlay: Boolean,
        onActive: (Action) -> Unit = {},
    ) {
        val actionBuilder = playLandActionBuilder(instanceId, grpId)
        if (canPlay) {
            val action = actionBuilder.setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Play_add3)).build()
            builder.addActions(action)
            onActive(action)
        } else {
            builder.addInactiveActions(actionBuilder)
        }
    }

    private fun playLandActionBuilder(
        instanceId: Int,
        grpId: Int,
    ): Action.Builder =
        Action
            .newBuilder()
            .setActionType(ActionType.Play_add3)
            .setInstanceId(instanceId)
            .setGrpId(grpId)
            .setFacetId(instanceId)

    /**
     * Configure a Cast action's keyword-specific fields per the rail's row in
     * [CastRails]. Reference fixtures live under data/puzzles/.
     * (plot-railway-brawler, foretell-demon-bolt, disturb-lunarch,
     * escape-glimpse-of-freedom). The rail descriptor encodes:
     *
     *  - `alternativeGrpId` source (Universal-149 vs per-card row from BoundCard)
     *  - `abilityGrpId` mode (None / FixedKeyword / EchoAlternative)
     *  - whether mana cost is emitted, and whether each ManaRequirement echoes
     *    the alternativeGrpId.
     *
     * `omitGrpIdAndFacetId` is honored by the caller before calling here.
     */
    private fun configureZoneCastRailShape(
        actionBuilder: Action.Builder,
        sa: SpellAbility,
        rail: ZoneCastRail,
        bound: BoundCard?,
        player: Player,
    ) {
        val altCosts = bound?.altCosts ?: emptyList()
        val altSource = rail.altGrpIdSource
        val needsCostAware =
            altSource is AltGrpIdSource.FromBoundCard && altSource.lookupMode == LookupMode.CostAware
        val payCostPairs: List<Pair<ManaColor, Int>> =
            if (needsCostAware) {
                computeEffectiveCost(sa, player)
                    ?.takeIf { !it.isNoCost }
                    ?.let { forgeManaCostToPairs(it) }
                    ?: emptyList()
            } else {
                emptyList()
            }
        val altGrpId = resolveAltGrpId(rail, altCosts, payCostPairs)
        if (altGrpId > 0) actionBuilder.setAlternativeGrpId(altGrpId)

        val abilityGrpId =
            when (val mode = rail.abilityGrpIdMode) {
                AbilityGrpIdMode.None -> 0
                is AbilityGrpIdMode.FixedKeyword -> mode.baseId
                AbilityGrpIdMode.EchoAlternative -> altGrpId
            }
        if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)

        if (rail.emitManaCost) {
            emitAltCostManaCost(
                actionBuilder,
                sa,
                player,
                abilityGrpIdEcho = if (rail.echoAlternativeOnMana) altGrpId else 0,
            )
        }
    }

    /**
     * Fallback for zone-cast SAs that don't match any [CastRails] rail —
     * unrecognized alt-costs (Madness) and no-alt zone casts
     * (commander, etc.). Emit printed mana cost from [CardData] when there's
     * no alt cost; otherwise emit effective SA cost with optional abilityGrpId
     * lookup. Best-effort shape until a CastRails row lands for the keyword.
     */
    private fun configureZoneCastFallback(
        actionBuilder: Action.Builder,
        sa: SpellAbility,
        bound: BoundCard?,
        player: Player,
    ) {
        val altCost = sa.alternativeCost
        if (altCost == null) {
            actionBuilder.addAllManaCost(CastDisplayCost.requirements(sa, player, bound?.data))
        } else {
            val keywordId = KeywordAbilityIds.fromForgeAltCostName(altCost.name)
            val abilityGrpId = if (keywordId != null) bound?.altCost(keywordId)?.abilityGrpId ?: 0 else 0
            if (abilityGrpId > 0) actionBuilder.setAbilityGrpId(abilityGrpId)
            emitAltCostManaCost(actionBuilder, sa, player, abilityGrpIdEcho = 0)
        }
    }

    /** Emit the SA's displayed mana cost; echo [abilityGrpIdEcho] on each
     *  ManaRequirement when non-zero (the per-card alt-cost ability id is
     *  what the client tags every mana symbol with for keyword-cost casts). */
    private fun emitAltCostManaCost(
        actionBuilder: Action.Builder,
        sa: SpellAbility,
        player: Player,
        abilityGrpIdEcho: Int,
    ) {
        actionBuilder.addAllManaCost(CastDisplayCost.requirements(sa, player, null, abilityGrpIdEcho.takeIf { it > 0 }))
    }

    private fun isMdfcBackSpell(sa: SpellAbility): Boolean =
        sa.hostCard?.isModal == true && sa.cardStateName == CardStateName.Backside && sa.isSpell && !sa.isLandAbility

    @Suppress("LongParameterList") // face identity, legality inputs, and exact-source callbacks stay coupled.
    private fun addMdfcFaceActions(
        card: Card,
        player: Player,
        instanceId: Int,
        parentGrpId: Int,
        cardRepository: CardRepository?,
        builder: ActionsAvailableReq.Builder,
        checkLegality: Boolean,
        castable: List<SpellAbility> = getAllCastableAbilities(card, player, checkTiming = checkLegality),
        mdfcLandAbility: LandAbility? = buildMdfcBackLandAbility(card),
        autoTapSolution: ((ManaCost, SpellAbility) -> AutoTapSolution?)? = null,
        onCast: (Action, Int, SpellAbility) -> Unit = { _, _, _ -> },
        onLand: (Action) -> Unit = {},
    ) {
        if (!card.isModal || !card.hasState(CardStateName.Backside)) return

        val backSpell = castable.firstOrNull(::isMdfcBackSpell)
        if (backSpell != null) {
            val action = buildMdfcSpellAction(backSpell, player, instanceId, parentGrpId, cardRepository)
            if (action != null) {
                if (!checkLegality || canExecute(backSpell, player)) {
                    val payableAction = action.toBuilder()
                    if (checkLegality) {
                        CastDisplayCost.of(backSpell, player)?.let { cost ->
                            autoTapSolution?.invoke(cost, backSpell)?.let(payableAction::setAutoTapSolution)
                        }
                    }
                    val built = payableAction.build()
                    builder.addActions(built)
                    val index = castable.indexOfFirst { it === backSpell }
                    check(!checkLegality || index >= 0) { "MDFC spell ability is absent from its candidate set" }
                    onCast(built, index.coerceAtLeast(0), backSpell)
                } else if (canPlay(backSpell)) {
                    builder.addInactiveActions(action)
                }
            }
        }

        val landAbility = mdfcLandAbility
        if (landAbility != null) {
            landAbility.activatingPlayer = player
            val canPlay = checkLegality && canPlay(landAbility)
            val action =
                Action
                    .newBuilder()
                    .setActionType(ActionType.PlayMdfc)
                    .setInstanceId(instanceId)
                    .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.PlayMdfc))
            if (canPlay) {
                val built = action.build()
                builder.addActions(built)
                onLand(built)
            } else if (checkLegality) {
                builder.addInactiveActions(action)
            }
        }
    }

    private fun buildMdfcSpellAction(
        sa: SpellAbility,
        player: Player,
        instanceId: Int,
        parentGrpId: Int,
        cardRepository: CardRepository?,
    ): Action? {
        if (!ActionAvailability.hasLegalTargetsAndModes(sa)) return null
        sa.setActivatingPlayer(player)
        val actionBuilder =
            castActionBuilder(ActionType.CastMdfc, sa)
                .setInstanceId(instanceId)
                .setSourceId(instanceId)
                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.CastMdfc))
        val abilityGrpId = resolveMdfcBackAbilityGrpId(sa, parentGrpId, cardRepository)
        if (abilityGrpId != 0) {
            actionBuilder.setAbilityGrpId(abilityGrpId)
        }
        actionBuilder.addAllManaCost(CastDisplayCost.requirements(sa, player, null, abilityGrpId.takeIf { it != 0 }))
        return actionBuilder.build()
    }

    private fun resolveMdfcBackAbilityGrpId(
        sa: SpellAbility,
        parentGrpId: Int,
        cardRepository: CardRepository?,
    ): Int {
        if (cardRepository == null) return 0
        val backName = sa.cardState?.name
        val backGrpId =
            backName?.let(cardRepository::findGrpIdByNameAnyFace)
                ?: cardRepository.findLinkedFaces(parentGrpId).firstOrNull { it != parentGrpId }
                ?: return 0
        return cardRepository
            .findByGrpId(backGrpId)
            ?.abilityIds
            ?.firstOrNull()
            ?.first ?: 0
    }

    private fun canPlay(sa: SpellAbility): Boolean =
        try {
            sa.canPlay()
        } catch (_: Exception) {
            false
        }

    /**
     * Naive single Cast action: instanceId + grpId + facetId + shouldStop +
     * displayed mana cost. No autoTap, no legality gate — naive frames embed
     * the human's potential casts during the AI's turn. The cast ability and
     * displayed cost come from the shared [choosePrimaryHandCastAbility] /
     * [displayedHandCastCost] so the naive list and the snapshot projection
     * agree on the same offer.
     */
    private fun buildNaiveCastAction(
        card: Card,
        sa: SpellAbility?,
        instanceId: Int,
        grpId: Int,
        player: Player,
        cardData: CardData?,
    ): Action =
        castActionBuilder(ActionType.Cast, sa)
            .setInstanceId(instanceId)
            .setGrpId(grpId)
            .setFacetId(instanceId)
            .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))
            .addAllManaCost(displayedHandCastCost(card, sa, player, cardData))
            .build()

    /**
     * Emit Adventure / Omen offers for a hand card. Both ride a Secondary
     * state (subtype "Adventure" or "Omen") and are independent of the main
     * face's payability. Called from both the affordable and unaffordable
     * main-cast branches so the secondary face surfaces regardless.
     *
     * Per-action-type field divergence: CastAdventure carries `grpId =
     * creature face` (the client rejects unknown grpIds on IsPrimaryCard=0
     * Adventure faces); CastOmen and CastLeftRoom/CastRightRoom omit
     * `grpId`. That asymmetry is intentional, not an oversight.
     */
    private fun addSecondaryFaceCastActions(
        card: Card,
        player: Player,
        instanceId: Int,
        grpId: Int,
        cardSnap: leyline.game.snapshot.CardSnapshot,
        builder: ActionsAvailableReq.Builder,
        castable: List<SpellAbility> = getAllCastableAbilities(card, player),
        autoTapSolution: ((ManaCost, SpellAbility) -> AutoTapSolution?)? = null,
        onActive: (Action, Int, SpellAbility) -> Unit = { _, _, _ -> },
    ) {
        fun withPaymentPlan(
            action: Action,
            ability: SpellAbility,
        ): Action {
            val cost = CastDisplayCost.of(ability, player) ?: return action
            if (cost.isNoCost) return action
            val solution = autoTapSolution?.invoke(cost, ability) ?: return action
            return action.toBuilder().setAutoTapSolution(solution).build()
        }

        if (cardSnap.isAdventureCard) {
            val adventureSa = castable.firstOrNull { it.isAdventure }
            val advAction = adventureSa?.let { buildAdventureAction(it, player, instanceId, grpId, checkLegality = true) }
            if (advAction != null) {
                val action = withPaymentPlan(advAction, adventureSa)
                builder.addActions(action)
                onActive(action, castable.indexOfFirst { it === adventureSa }, adventureSa)
            } else {
                buildInactiveAdventureAction(card, player, instanceId, grpId)
                    ?.let { builder.addInactiveActions(it) }
            }
        }
        if (cardSnap.isOmenCard) {
            val omenSa = castable.firstOrNull { it.isOmen }
            val omenAction = omenSa?.let { buildOmenAction(it, player, instanceId, checkLegality = true) }
            if (omenAction != null) {
                val action = withPaymentPlan(omenAction, omenSa)
                builder.addActions(action)
                onActive(action, castable.indexOfFirst { it === omenSa }, omenSa)
            } else {
                buildInactiveOmenAction(card, player, instanceId)
                    ?.let { builder.addInactiveActions(it) }
            }
        }
    }

    private fun linkedFaceGrpId(
        bound: BoundCard?,
        actionType: ActionType,
    ): Int? {
        val role =
            when {
                actionType == ActionType.CastAdventure -> LinkedFaceRole.Adventure
                actionType == ActionType.CastOmen -> LinkedFaceRole.Omen
                else -> return null
            }
        return bound?.linkedFaces?.firstOrNull { it.role == role }?.grpId
    }

    /** Build a CastAdventure action for an adventure card, or null if not castable. */
    private fun buildAdventureAction(
        adventureSa: SpellAbility,
        player: Player,
        instanceId: Int,
        creatureGrpId: Int,
        checkLegality: Boolean,
    ): Action? {
        if (checkLegality) {
            adventureSa.setActivatingPlayer(player)
            val canCast = canExecute(adventureSa, player)
            if (!canCast) return null
        }

        // grpId = creature face — client can't resolve IsPrimaryCard=0 adventure
        // faces and rejects the action if grpId is unknown. manaCost from the
        // adventure SA provides the correct cost for the Choose One modal.
        return castActionBuilder(ActionType.CastAdventure, adventureSa)
            .setInstanceId(instanceId)
            .setGrpId(creatureGrpId)
            .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.CastAdventure))
            .addAllManaCost(CastDisplayCost.requirements(adventureSa, player, null))
            .build()
    }

    /**
     * Emit one CastLeftRoom and/or CastRightRoom action per locked door whose
     * SA is castable. Each emit carries `actionType + instanceId + manaCost`
     * only — door identity is encoded by `actionType` alone (no grpId,
     * facetId, abilityGrpId, alternativeGrpId).
     *
     * From hand, Forge surfaces door SAs via `card.getSpells()` (the split-cast
     * shape — `cardStateName=LeftSplit/RightSplit`). From battlefield, the
     * locked door's SA comes from `card.getUnlockAbility(state)`.
     */
    private fun addRoomCastActions(
        card: Card,
        player: Player,
        instanceId: Int,
        builder: ActionsAvailableReq.Builder,
        checkLegality: Boolean,
        castable: List<SpellAbility> = getAllCastableAbilities(card, player, checkTiming = checkLegality),
        autoTapSolution: ((ManaCost, SpellAbility) -> AutoTapSolution?)? = null,
        emitActiveAction: Boolean = true,
        onActive: (Action, Int, SpellAbility) -> Unit = { _, _, _ -> },
    ) {
        val untimed = getAllCastableAbilities(card, player, checkTiming = false)
        for (state in listOf(CardStateName.LeftSplit, CardStateName.RightSplit)) {
            if (state !in card.lockedRooms) continue
            val descriptor = RoomDoorCastDescriptors.forState(state) ?: continue
            val abilityIndex = castable.indexOfFirst { it.cardStateName == state }
            val sa = castable.getOrNull(abilityIndex) ?: untimed.firstOrNull { it.cardStateName == state } ?: continue
            sa.setActivatingPlayer(player)
            val canPay =
                if (checkLegality) {
                    abilityIndex >= 0 && canExecute(sa, player)
                } else {
                    abilityIndex >= 0
                }
            val actionBuilder =
                castActionBuilder(descriptor.actionType, sa)
                    .setInstanceId(instanceId)
                    .setShouldStop(ShouldStopEvaluator.shouldStop(descriptor.actionType))
                    .addAllManaCost(CastDisplayCost.requirements(sa, player, null))
            if (canPay) {
                val cost = CastDisplayCost.of(sa, player)
                if (cost != null && !cost.isNoCost) {
                    autoTapSolution?.invoke(cost, sa)?.let(actionBuilder::setAutoTapSolution)
                }
                val action = actionBuilder.build()
                if (emitActiveAction) builder.addActions(action)
                onActive(action, abilityIndex, sa)
            } else {
                builder.addInactiveActions(actionBuilder)
            }
        }
    }

    private fun addSplitCastActions(
        card: Card,
        player: Player,
        instanceId: Int,
        builder: ActionsAvailableReq.Builder,
        castable: List<SpellAbility>,
        cardRepository: CardRepository,
        onActive: (Action, Int, SpellAbility, Int) -> Unit,
    ) {
        for (state in listOf(CardStateName.LeftSplit, CardStateName.RightSplit)) {
            val abilityIndex = castable.indexOfFirst { it.cardStateName == state && it.alternativeCost == null }
            val sa = castable.getOrNull(abilityIndex) ?: continue
            val faceName = card.getState(state)?.name ?: continue
            val parentName = card.getOriginalState(CardStateName.Original)?.name ?: continue
            val parentGrpId = cardRepository.findGrpIdByName(parentName) ?: continue
            val faceGrpId =
                cardRepository.findLinkedFaces(parentGrpId).singleOrNull {
                    cardRepository.findNameByGrpId(it) == faceName
                } ?: continue
            sa.setActivatingPlayer(player)
            val action =
                castActionBuilder(ActionType.Cast, sa)
                    .setInstanceId(instanceId)
                    .setGrpId(faceGrpId)
                    .setFacetId(instanceId)
                    .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))
                    .addAllManaCost(CastDisplayCost.requirements(sa, player, null))
            val canCast = canExecute(sa, player)
            if (canCast) {
                val built = action.build()
                builder.addActions(built)
                onActive(built, abilityIndex, sa, faceGrpId)
            } else {
                builder.addInactiveActions(action)
            }
        }
    }

    /**
     * Emit the `Special_TurnFaceUp_add3` action for a supported face-down
     * permanent. The action's `alternativeGrpId` identifies the turn-up rail,
     * and `manaCost` comes from the Forge special action.
     *
     * Field divergence from regular Cast / Activate: no `grpId`, no
     * `facetId` — the action-type alone identifies the target permanent
     * (sibling pattern to CastOmen / CastLeftRoom).
     */
    private fun addSpecialTurnFaceUpActions(
        card: Card,
        player: Player,
        instanceId: Int,
        cardData: CardData?,
        fallbackAlternativeGrpId: Int,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
        builder: ActionsAvailableReq.Builder,
        abilities: List<SpellAbility> = getNonManaActivatedAbilities(card, player),
        onActive: (Action, Int, SpellAbility) -> Unit = { _, _, _ -> },
    ) {
        val abilityIndex = abilities.indexOfFirst { it.isTurnFaceUp }
        val turnFaceUpSa = abilities.getOrNull(abilityIndex) ?: return
        turnFaceUpSa.setActivatingPlayer(player)
        val canPay = canExecute(turnFaceUpSa, player)
        val registry = abilityRegistryLookup(card, cardData)
        val alternativeGrpId = registry?.forSpellAbility(turnFaceUpSa.definitionId) ?: fallbackAlternativeGrpId
        if (alternativeGrpId == 0) return
        val actionBuilder =
            Action
                .newBuilder()
                .setActionType(ActionType.SpecialTurnFaceUp_add3)
                .setInstanceId(instanceId)
                .setAlternativeGrpId(alternativeGrpId)
                .setAlternativeSourceZcid(instanceId)
                .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.SpecialTurnFaceUp_add3))
                .addAllManaCost(CastDisplayCost.requirements(turnFaceUpSa, player, null, alternativeGrpId))
        if (canPay) {
            val action = actionBuilder.build()
            builder.addActions(action)
            onActive(action, abilityIndex, turnFaceUpSa)
        } else {
            builder.addInactiveActions(actionBuilder)
        }
    }

    /**
     * Build a CastOmen action for an Omen-capable card, or null if not castable.
     * Mirrors [buildAdventureAction] but emits the minimal envelope —
     * `actionType + instanceId + manaCost` only. No grpId / facetId. The Omen
     * face is encoded by `actionType` alone (sibling: CastLeftRoom).
     */
    private fun buildOmenAction(
        omenSa: SpellAbility,
        player: Player,
        instanceId: Int,
        checkLegality: Boolean,
    ): Action? {
        if (checkLegality) {
            omenSa.setActivatingPlayer(player)
            val canCast = canExecute(omenSa, player)
            if (!canCast) return null
        }

        return castActionBuilder(ActionType.CastOmen, omenSa)
            .setInstanceId(instanceId)
            .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.CastOmen))
            .addAllManaCost(CastDisplayCost.requirements(omenSa, player, null))
            .build()
    }

    /** Build an inactive CastOmen action (unaffordable), or null if card has no Omen state. */
    private fun buildInactiveOmenAction(
        card: Card,
        player: Player,
        instanceId: Int,
    ): Action? {
        val omenState = card.getState(CardStateName.Secondary) ?: return null
        val omenSa = omenState.nonManaAbilities?.firstOrNull() ?: return null
        omenSa.setActivatingPlayer(player)
        if (!omenSa.canPlay()) return null
        return castActionBuilder(ActionType.CastOmen, omenSa)
            .setInstanceId(instanceId)
            .addAllManaCost(CastDisplayCost.requirements(omenSa, player, null))
            .build()
    }

    /** Build an inactive CastAdventure action (unaffordable), or null if card has no adventure state. */
    private fun buildInactiveAdventureAction(
        card: Card,
        player: Player,
        instanceId: Int,
        creatureGrpId: Int,
    ): Action? {
        // The inactive offer carries face identity and cost even when the
        // current phase forbids casting a sorcery Adventure. Without it the
        // linked-face mana badge blinks off outside a legal cast window.
        val adventureSa = card.getState(CardStateName.Secondary)?.nonManaAbilities?.firstOrNull() ?: return null
        adventureSa.setActivatingPlayer(player)
        return castActionBuilder(ActionType.CastAdventure, adventureSa)
            .setInstanceId(instanceId)
            .setGrpId(creatureGrpId)
            .addAllManaCost(CastDisplayCost.requirements(adventureSa, player, null))
            .build()
    }

    /**
     * Hand-zone alt-cost casts — emit one [Action] per eligible alt-cost SA.
     * Iterates [CastRails.handWithAltCost]; a rail matches when its
     * [HandWithAltCost.saPredicate] holds for the SA. Each action carries:
     *
     *  - `instanceId` = hand card iid, `grpId` = card grpId, `facetId` = iid
     *  - `abilityGrpId` = 0 (the alt-cost row is on `alternativeGrpId`)
     *  - `alternativeGrpId` = per-card row resolved per the rail's lookup mode
     *  - `manaCost` entries echo `alternativeGrpId` on each slot
     *
     * Madness and Flashback are intentionally NOT in
     * [CastRails.handWithAltCost] — they ride other rails (OptionalAction for
     * Madness, zone-cast for Flashback).
     */
    private fun addHandAltCostCastActions(
        bridge: GameBridge,
        card: Card,
        player: Player,
        instanceId: Int,
        grpId: Int,
        altCosts: List<AltCostBinding>,
        builder: ActionsAvailableReq.Builder,
        castable: List<SpellAbility> = getAllCastableAbilities(card, player),
        autoTapSolution: (ManaCost, SpellAbility) -> AutoTapSolution?,
        onActive: (Action, Int, SpellAbility) -> Unit = { _, _, _ -> },
    ) {
        val emitted = mutableSetOf<Pair<Int, List<Pair<ManaColor, Int>>>>()
        for ((abilityIndex, sa) in castable.withIndex()) {
            val rail = CastRails.handWithAltCost.firstOrNull { it.saPredicate(sa) } ?: continue
            if (!ActionAvailability.hasLegalTargetsAndModes(sa)) continue
            val effectiveCost = computeEffectiveCostForOffer(rail, sa, player, altCosts)
            val payCostPairs = effectiveCost.first
            val alternativeGrpId = bridge.cardRepository.grantedKeywordAbilityGrpId(sa) ?: effectiveCost.second
            if (rail.kind == AltCostKind.EMERGE && alternativeGrpId <= 0) continue
            val canPay = canExecute(sa, player)
            if (!canPay) continue
            if (alternativeGrpId <= 0) continue
            if (!emitted.add(alternativeGrpId to payCostPairs)) continue

            val actionBuilder =
                castActionBuilder(ActionType.Cast, sa)
                    .setInstanceId(instanceId)
                    .setGrpId(grpId)
                    .setFacetId(instanceId)
                    .setAlternativeGrpId(alternativeGrpId)
                    .setShouldStop(ShouldStopEvaluator.shouldStop(ActionType.Cast))

            if (payCostPairs.isNotEmpty()) {
                payCostPairs.forEach { (color, count) ->
                    actionBuilder.addManaCost(
                        ManaRequirement
                            .newBuilder()
                            .addColor(color)
                            .setCount(count)
                            .setAbilityGrpId(alternativeGrpId),
                    )
                }
            }
            // Native hand, hover and modal highlights require a payment plan
            // even when the alternate Cast action is already marked active.
            CastDisplayCost.of(sa, player)?.takeUnless { it.isNoCost }?.let { cost ->
                autoTapSolution(cost, sa)?.let(actionBuilder::setAutoTapSolution)
            }
            val action = actionBuilder.build()
            builder.addActions(action)
            onActive(action, abilityIndex, sa)
        }
    }

    private fun computeEffectiveCostForOffer(
        rail: HandWithAltCost,
        sa: SpellAbility,
        player: Player,
        altCosts: List<AltCostBinding>,
    ): Pair<List<Pair<ManaColor, Int>>, Int> {
        if (rail.kind == AltCostKind.EMERGE) {
            val alternativeGrpId = resolveAltGrpId(rail, altCosts, emptyList())
            val payCostPairs = altCosts.firstOrNull { it.abilityGrpId == alternativeGrpId }?.manaCost.orEmpty()
            return payCostPairs to alternativeGrpId
        }
        val effectiveCost = computeEffectiveCost(sa, player)
        val payCostPairs = effectiveCost?.takeIf { !it.isNoCost }?.let { forgeManaCostToPairs(it) } ?: emptyList()
        return payCostPairs to resolveAltGrpId(rail, altCosts, payCostPairs)
    }

    internal fun passOnlyActions(): ActionsAvailableReq =
        ActionsAvailableReq
            .newBuilder()
            .addActions(Action.newBuilder().setActionType(ActionType.Pass))
            .build()

    @Suppress("LongParameterList")
    private fun buildAutoTapSolution(
        manaCost: ManaCost,
        player: Player,
        idResolver: (ForgeCardId) -> InstanceId,
        grpIdResolver: (Card) -> GrpId,
        cardDataLookup: (GrpId) -> CardData?,
        abilityRegistryLookup: (Card, CardData?) -> AbilityRegistry?,
        ability: SpellAbility,
    ): AutoTapSolution? =
        ActionAutoTapSupport.build(
            manaCost,
            ActionBuildContext(player, idResolver, grpIdResolver, cardDataLookup, abilityRegistryLookup),
            ability,
        )

    internal fun computeEffectiveCost(
        sa: SpellAbility,
        player: Player,
    ): forge.card.mana.ManaCost? = ActionManaCosts.computeEffectiveCost(sa, player)

    internal fun forgeManaCostToPairs(manaCost: forge.card.mana.ManaCost): List<Pair<ManaColor, Int>> =
        ActionManaCosts.forgeManaCostToPairs(manaCost)

    /**
     * Convert a Forge [ManaCost] into proto [ManaRequirement] entries on an action builder.
     *
     * When [abilityGrpId] is set, each [ManaRequirement] embeds it — client expects this
     * for hand-zone activated abilities (Channel, Ninjutsu, etc.) so the client can associate
     * cost display with the specific ability modal option.
     */
    private fun addManaCostFromForge(
        manaCost: forge.card.mana.ManaCost,
        actionBuilder: Action.Builder,
        abilityGrpId: Int? = null,
    ) = ActionManaCosts.addManaCostFromForge(manaCost, actionBuilder, abilityGrpId)

    internal fun forgeManaCostToRequirements(
        manaCost: forge.card.mana.ManaCost,
        abilityGrpId: Int? = null,
    ): List<ManaRequirement> = ActionManaCosts.forgeManaCostToRequirements(manaCost, abilityGrpId)

    internal fun producedToManaColor(produced: String): ManaColor? = ActionManaCosts.producedToManaColor(produced)

    /**
     * Strip an Action down to the minimal format used inside GSM embedded actions.
     *
     * GSM actions carry fewer fields than ActionsAvailableReq actions:
     * - Cast/CastAdventure/CastOmen/CastMdfc/CastLeftRoom/CastRightRoom: instanceId + manaCost + cast-variant identity fields
     * - Play/PlayMdfc: instanceId
     * - ActivateMana: instanceId + abilityGrpId
     * - Activate: instanceId + abilityGrpId + manaCost
     * - Pass/FloatMana: empty
     *
     * No grpId, facetId, shouldStop, or autoTapSolution.
     */
    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    fun stripActionForGsm(action: Action): Action {
        val b = Action.newBuilder().setActionType(action.actionType)
        if (action.actionType == ActionType.Cast ||
            action.actionType == ActionType.CastAdventure ||
            action.actionType == ActionType.CastOmen ||
            action.actionType == ActionType.CastMdfc ||
            action.actionType == ActionType.CastLeftRoom ||
            action.actionType == ActionType.CastRightRoom
        ) {
            b.setInstanceId(action.instanceId)
            if (action.abilityGrpId != 0) b.setAbilityGrpId(action.abilityGrpId)
            if (action.sourceId != 0) b.setSourceId(action.sourceId)
            if (action.alternativeGrpId != 0) b.setAlternativeGrpId(action.alternativeGrpId)
            if (action.alternativeSourceZcid != 0) b.setAlternativeSourceZcid(action.alternativeSourceZcid)
            b.addAllManaCost(action.manaCostList)
        } else if (action.actionType == ActionType.Play_add3 || action.actionType == ActionType.PlayMdfc) {
            b.setInstanceId(action.instanceId)
        } else if (action.actionType == ActionType.ActivateMana || action.actionType == ActionType.Activate_add3) {
            b.setInstanceId(action.instanceId)
            if (action.abilityGrpId != 0) b.setAbilityGrpId(action.abilityGrpId)
            if (action.actionType == ActionType.Activate_add3) b.addAllManaCost(action.manaCostList)
        } else if (action.actionType == ActionType.SpecialTurnFaceUp_add3) {
            b.setInstanceId(action.instanceId)
            if (action.abilityGrpId != 0) b.setAbilityGrpId(action.abilityGrpId)
            if (action.alternativeGrpId != 0) b.setAlternativeGrpId(action.alternativeGrpId)
            if (action.alternativeSourceZcid != 0) b.setAlternativeSourceZcid(action.alternativeSourceZcid)
            b.addAllManaCost(action.manaCostList)
        } else if (action.actionType != ActionType.Pass && action.actionType != ActionType.FloatMana) {
            b.setInstanceId(action.instanceId)
        }
        return b.build()
    }
}
