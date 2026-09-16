package leyline.game.annotations

import leyline.bridge.types.EffectId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.bridge.types.WireId
import leyline.game.codes.CounterTypes
import leyline.game.codes.DetailKeys
import leyline.game.codes.QualificationType
import leyline.game.event.DamageSourceKind
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.CounterType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType

/**
 * Builds client-format [AnnotationInfo] protos for [GameStateMessage] bundles.
 *
 * client annotations are the **semantic layer** on top of raw game state diffs.
 * The client has two independent parser families that consume them:
 * - **State parsers** (~58 classes): mutate card/game state fields directly
 *   (P/T, counters, abilities, designations, attachments)
 * - **Event parsers** (~46 classes): produce [GameRulesEvent] objects that drive
 *   the animation/sound pipeline (zone transfers, damage flash, life counter)
 *
 * Some annotation types fire **both** parsers (e.g. ResolutionStart, Shuffle,
 * DieRoll). Each builder method here maps to one [AnnotationType] enum value
 * and matches the expected detail key names, value types, and shape.
 * Detail keys are case-sensitive; the client throws on missing required fields.
 *
 * **Ordering contract:** [objectIdChanged] must appear before [zoneTransfer]
 * for the same card. The client's ZoneTransfer event parser expects the new
 * instanceId to be resolvable — the ObjectIdChanged state parser stores the
 * old→new mapping in `newIdToOldIdMap` which must run first.
 *
 * **Typed parameters:** ID slots use value classes ([InstanceId], [SeatId],
 * [GrpId], [EffectId], [WireId]) so positional cross-type swaps fail at compile
 * time. Zones stay [Int] (use [leyline.game.mapping.ZoneIds] constants). Data
 * fields (amounts, deltas, counts, enum ordinals) stay [Int].
 *
 * @see ZoneTransferDetector for zone transfer detection
 * @see ZoneMoveLedger for event-to-category resolution
 * @see TransferAnnotations for transfer-stage annotation generation
 * @see CombatAnnotations for combat-stage annotations
 * @see MechanicAnnotations for mechanic and effect annotations
 */
@Suppress("LargeClass")
object AnnotationBuilder {
    private const val MANA_SPEC_DOES_NOT_EMPTY_VALUE = 14695

    /** DamageDealt `type` values: 1 = combat, 2 = spell/ability, 3 = fight. */
    private const val COMBAT_DAMAGE_TYPE = 1
    private const val NONCOMBAT_DAMAGE_TYPE = 2
    private const val FIGHT_DAMAGE_TYPE = 3

    /** DamageDealt `markDamage` flag — always 1; the client requires the detail key present. */
    private const val MARK_DAMAGE_FLAG = 1

    /**
     * Set `affectorId` on the builder iff [id] is non-null and non-zero.
     * Many annotations treat `affectorId` as optional — a null or zero value means
     * "no affector", and the client expects the proto field to be omitted (default 0).
     */
    private fun AnnotationInfo.Builder.setOptionalAffector(id: InstanceId?): AnnotationInfo.Builder =
        apply {
            if (id != null && id.value != 0) setAffectorId(id.value)
        }

    fun zoneTransfer(
        instanceId: InstanceId,
        srcZoneId: Int,
        destZoneId: Int,
        category: String,
        actingSeatId: SeatId? = null,
        affectorId: InstanceId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ZoneTransfer_af5a)
            .apply {
                // affectorId takes precedence (ability instance); fall back to actingSeatId (player seat)
                val aff =
                    when {
                        affectorId != null && affectorId.value != 0 -> affectorId.value
                        actingSeatId != null && actingSeatId.value != 0 -> actingSeatId.value
                        else -> 0
                    }
                if (aff != 0) setAffectorId(aff)
            }.addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.ZONE_SRC, srcZoneId))
            .addDetails(int32Detail(DetailKeys.ZONE_DEST, destZoneId))
            .addDetails(typedStringDetail(DetailKeys.CATEGORY, category))
            .build()

    /** Spell/ability begins resolving. Client uses this to start resolution animation. */
    fun resolutionStart(
        instanceId: InstanceId,
        grpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ResolutionStart)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.GRPID, grpId.value))
            .build()

    /** Labels one static SelectN option with the objects affected by that choice. */
    fun selectNDecoration(
        sourceId: InstanceId,
        optionIndex: Int,
        affectedObjectIds: List<InstanceId>,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.SelectNdecoration)
            .setAffectorId(sourceId.value)
            .addAffectedIds(optionIndex)
            .addDetails(int32ListDetail(DetailKeys.AFFECTED_OBJECTS, affectedObjectIds.map { it.value }))
            .build()

    /** A new turn started. Client uses this to reset turn-scoped state.
     *  [activeSeat] = the active player's seat for the new turn. */
    fun newTurnStarted(activeSeat: SeatId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.NewTurnStarted)
            .setAffectorId(activeSeat.value)
            .addAffectedIds(activeSeat.value)
            .build()

    /** Phase/step changed. Client uses this to animate the phase tracker.
     *  [activeSeat] = active player seat, [phase]/[step] = proto enum ordinals. */
    fun phaseOrStepModified(
        activeSeat: SeatId,
        phase: Int,
        step: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.PhaseOrStepModified)
            .addAffectedIds(activeSeat.value)
            .addDetails(int32Detail(DetailKeys.PHASE, phase))
            .addDetails(int32Detail(DetailKeys.STEP, step))
            .build()

    /** Card's instanceId changed (e.g. zone move creates new object).
     *  [affectorId] = ability instance that caused the change (null = unset). */
    fun objectIdChanged(
        origId: InstanceId,
        newId: InstanceId,
        affectorId: InstanceId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ObjectIdChanged)
            .setOptionalAffector(affectorId)
            .addAffectedIds(origId.value)
            .addDetails(int32Detail(DetailKeys.ORIG_ID, origId.value))
            .addDetails(int32Detail(DetailKeys.NEW_ID, newId.value))
            .build()

    /**
     * Ties a game state change back to a player interaction.
     * [seatId] = acting player's seat (affectorId).
     * [actionType] = client [ActionType] enum (Cast, Play_add3, ActivateMana, CastAdventure, …).
     * [abilityGrpId] = ability group ID (0 for land play).
     * [alternativeGrpId] = alt-cost ability grpId (Madness, Flashback, Warp, Cycling, etc.).
     *   Pass 0 (default) when the spell was cast for its regular cost. When non-zero, the
     *   client renders the cast as having gone through an alternate cost path.
     */
    fun userActionTaken(
        instanceId: InstanceId,
        seatId: SeatId,
        actionType: ActionType = ActionType.None_add3,
        abilityGrpId: GrpId = GrpId(0),
        alternativeGrpId: GrpId = GrpId(0),
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.UserActionTaken)
            .setAffectorId(seatId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.ACTION_TYPE, actionType.number))
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID, abilityGrpId.value))
            .apply {
                if (alternativeGrpId.value != 0) {
                    addDetails(int32Detail(DetailKeys.ALTERNATIVE_GRP_ID, alternativeGrpId.value))
                }
            }.build()

    /**
     * CastingTimeOption — persistent annotation marking how a spell on the stack was cast.
     *
     * Most common shape (and the one used by the alt-cost mechanic family):
     * **CastThroughAbility** — spell cast via an alternate cost ability
     * (Madness, Flashback, Warp, Cycling, Impending). [alternateCostGrpId] and
     * [castAbilityGrpId] usually both carry the alt-cost ability's grpId. Free casts use the
     * no-mana-cost identity in [alternateCostGrpId] and the granting ability in [castAbilityGrpId].
     *
     * Persistent while the spell is on the stack; deleted via
     * `diffDeletedPersistentAnnotationIds` when the spell resolves or leaves the stack.
     *
     * Other [CastingTimeOptionType] values (Kicker, AdditionalCost, ChooseX_a7b4, …) exist but
     * are not exercised by alt-cost mechanics.
     *
     * [stackInstanceId] = the self-attached spell instance on the stack.
     * [alternateCostGrpId] = the alternate or free-cast cost identity.
     * [castAbilityGrpId] = the ability granting that cast route.
     */
    fun castingTimeOption(
        stackInstanceId: InstanceId,
        type: CastingTimeOptionType,
        alternateCostGrpId: GrpId,
        castAbilityGrpId: GrpId = alternateCostGrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CastingTimeOption)
            .setAffectorId(stackInstanceId.value)
            .addAffectedIds(stackInstanceId.value)
            .addDetails(int32Detail(DetailKeys.TYPE, type.number))
            .addDetails(int32Detail(DetailKeys.ALTERNATE_COST_GRP_ID, alternateCostGrpId.value))
            .addDetails(int32Detail(DetailKeys.CAST_ABILITY_GRP_ID, castAbilityGrpId.value))
            .build()

    /** CastingTimeOption type=3 (Kicker) — spell cast with kicker paid.
     *  kickerAbilityGrpId carries the per-card Kicker ability grpId. */
    fun castingTimeOptionKicker(
        stackInstanceId: InstanceId,
        kickerAbilityGrpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CastingTimeOption)
            .setAffectorId(stackInstanceId.value)
            .addAffectedIds(stackInstanceId.value)
            .addDetails(int32Detail(DetailKeys.TYPE, CastingTimeOptionType.Kicker.number))
            .addDetails(int32Detail(DetailKeys.KICKER_ABILITY_GRP_ID, kickerAbilityGrpId.value))
            .build()

    /** CastingTimeOption type=5 (AdditionalCost) — spell cast with an additional cost paid. */
    fun castingTimeOptionAdditionalCost(
        stackInstanceId: InstanceId,
        additionalCostGrpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CastingTimeOption)
            .setAffectorId(stackInstanceId.value)
            .addAffectedIds(stackInstanceId.value)
            .addDetails(int32Detail(DetailKeys.TYPE, CastingTimeOptionType.AdditionalCost.number))
            .addDetails(int32Detail(DetailKeys.ADDITIONAL_COST_GRP_ID, additionalCostGrpId.value))
            .build()

    fun castingTimeOptionChosenCost(
        stackInstanceId: InstanceId,
        chosenCostPromptId: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CastingTimeOption)
            .setAffectorId(stackInstanceId.value)
            .addAffectedIds(stackInstanceId.value)
            .addDetails(int32Detail(DetailKeys.TYPE, CastingTimeOptionType.ChooseOrCost.number))
            .addDetails(int32Detail(DetailKeys.CHOSEN_COST_PROMPT_ID, chosenCostPromptId))
            .build()

    /** CastingTimeOption type=2 (ChooseX_a7b4) — spell cast with chosen X value. */
    fun castingTimeOptionChooseX(
        stackInstanceId: InstanceId,
        value: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CastingTimeOption)
            .setAffectorId(stackInstanceId.value)
            .addAffectedIds(stackInstanceId.value)
            .addDetails(int32Detail(DetailKeys.TYPE, CastingTimeOptionType.ChooseX_a7b4.number))
            .addDetails(int32Detail(DetailKeys.VALUE, value))
            .build()

    /**
     * Mana was spent to pay for a spell/ability.
     * [spellInstanceId] = the spell/ability instance that consumed the mana (affectedIds).
     * [landInstanceId] = the land (or mana source) that produced the mana (affectorId).
     * [manaId] = mana payment tracking ID, or null when substitution payments omit it.
     * [color] = mana color as int bitmask (e.g. 2 = blue), matching the client format.
     * [substitutionGrpId] = keyword/base row when the payment substitutes for mana (Convoke).
     * When mana tracking is not available, pass defaults (0, 0).
     */
    fun manaPaid(
        spellInstanceId: InstanceId,
        landInstanceId: InstanceId,
        manaId: Int? = 0,
        color: Int = 0,
        substitutionGrpId: GrpId = GrpId(0),
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ManaPaid)
            .setAffectorId(landInstanceId.value)
            .addAffectedIds(spellInstanceId.value)
            .apply {
                if (manaId != null) {
                    addDetails(int32Detail(DetailKeys.ID, manaId))
                }
                addDetails(int32Detail(DetailKeys.COLOR, color))
                if (substitutionGrpId.value != 0) {
                    addDetails(int32Detail(DetailKeys.SUBSTITUTION_GRPID, substitutionGrpId.value))
                }
            }.build()

    fun manaDetails(
        sourceInstanceId: InstanceId,
        manaId: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ManaDetails)
            .setAffectorId(sourceInstanceId.value)
            .addAffectedIds(manaId)
            .addDetails(int32Detail(DetailKeys.MANA_SPEC_TYPE_DOES_NOT_EMPTY, MANA_SPEC_DOES_NOT_EMPTY_VALUE))
            .build()

    /**
     * Permanent tapped or untapped (e.g. tapping land for mana).
     * [permanentId] = the permanent being tapped (affectedIds).
     * [abilityId] = the ability instance that caused the tap (affectorId).
     *   Client expects a transient mana ability id; we approximate with the spell id.
     */
    fun tappedUntappedPermanent(
        permanentId: InstanceId,
        abilityId: InstanceId,
        tapped: Boolean = true,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.TappedUntappedPermanent)
            .setAffectorId(abilityId.value)
            .addAffectedIds(permanentId.value)
            .addDetails(int32Detail(DetailKeys.TAPPED, if (tapped) 1 else 0))
            .build()

    /**
     * Ability instance created on the stack.
     * [abilityInstanceId] = the ability/spell instance being created (affectedIds).
     * [affectorId] = the land or permanent that triggered this ability creation (e.g. tapping a land for mana).
     *   Pass null when not applicable (e.g. casting a spell from hand).
     * [sourceZoneId] = zone the ability/spell came from (e.g. Hand=31).
     * Client expects this field; client may use it for animation origin.
     */
    fun abilityInstanceCreated(
        abilityInstanceId: InstanceId,
        affectorId: InstanceId? = null,
        sourceZoneId: Int = 0,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.AbilityInstanceCreated)
            .setAffectorId(affectorId?.value ?: 0)
            .addAffectedIds(abilityInstanceId.value)
            .addDetails(int32Detail(DetailKeys.SOURCE_ZONE, sourceZoneId))
            .build()

    /**
     * Ability instance deleted (e.g. hand's play ability consumed after casting,
     * or a mana ability instance cleared after payment).
     * [abilityInstanceId] = the ability/spell instance being removed (affectedIds).
     * [affectorId] = the permanent that owns the ability, when applicable (e.g. tapped land).
     *   Pass null when not applicable.
     */
    fun abilityInstanceDeleted(
        abilityInstanceId: InstanceId,
        affectorId: InstanceId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.AbilityInstanceDeleted)
            .setAffectorId(affectorId?.value ?: 0)
            .addAffectedIds(abilityInstanceId.value)
            .build()

    /** Spell/ability done resolving. Client uses this to finalize stack→battlefield move. */
    fun resolutionComplete(
        instanceId: InstanceId,
        grpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ResolutionComplete)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.GRPID, grpId.value))
            .build()

    /**
     * Damage dealt by a card. Client uses this for damage presentation.
     *
     * [targetId] is polymorphic — creatures pass their [InstanceId.toWireId]; player damage
     * passes their [SeatId.toWireId].
     */
    fun damageDealt(
        sourceInstanceId: InstanceId,
        targetId: WireId,
        amount: Int,
        sourceKind: DamageSourceKind,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.DamageDealt_af5a)
            .setAffectorId(sourceInstanceId.value)
            .addAffectedIds(targetId.value)
            .addDetails(int32Detail(DetailKeys.DAMAGE, amount))
            .addDetails(
                int32Detail(
                    DetailKeys.TYPE,
                    when (sourceKind) {
                        DamageSourceKind.Combat -> COMBAT_DAMAGE_TYPE
                        DamageSourceKind.SpellOrAbility -> NONCOMBAT_DAMAGE_TYPE
                        DamageSourceKind.Fight -> FIGHT_DAMAGE_TYPE
                    },
                ),
            ).addDetails(int32Detail(DetailKeys.MARK_DAMAGE, MARK_DAMAGE_FLAG))
            .build()

    /** Player life total changed. Client uses this for life counter animation. */
    fun modifiedLife(
        playerSeatId: SeatId,
        lifeDelta: Int,
        affectorId: InstanceId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ModifiedLife)
            .setOptionalAffector(affectorId)
            .addAffectedIds(playerSeatId.value)
            .addDetails(int32Detail(DetailKeys.LIFE, lifeDelta))
            .build()

    fun replacementEffect(
        replacementId: EffectId,
        affectedId: InstanceId,
        abilityGrpId: GrpId,
        sourceZoneChangeId: InstanceId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(checkNotNull(AnnotationType.forNumber(62)))
            .setAffectorId(replacementId.value)
            .addAffectedIds(affectedId.value)
            .addDetails(int32Detail(DetailKeys.GRPID, abilityGrpId.value))
            .addDetails(int32Detail(DetailKeys.REPLACEMENT_SOURCE_ZCID, sourceZoneChangeId.value))
            .build()

    fun choiceResult(
        sourceInstanceId: InstanceId,
        chooserSeatId: SeatId,
        choiceValue: Int,
        choiceDomain: Int? = null,
        sentiment: Int = 2,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ChoiceResult)
            .setAffectorId(sourceInstanceId.value)
            .addAffectedIds(chooserSeatId.value)
            .addDetails(int32Detail(DetailKeys.CHOICE_VALUE, choiceValue))
            .apply { choiceDomain?.let { addDetails(int32Detail(DetailKeys.CHOICE_DOMAIN, it)) } }
            .addDetails(int32Detail(DetailKeys.CHOICE_SENTIMENT, sentiment))
            .build()

    fun coinFlip(
        abilityInstanceId: InstanceId,
        flipperSeatId: SeatId,
        result: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CoinFlip)
            .setAffectorId(abilityInstanceId.value)
            .addAffectedIds(flipperSeatId.value)
            .addDetails(int32Detail(DetailKeys.COIN_FLIP_RESULT, result))
            .build()

    fun linkInfoChoice(
        sourceInstanceId: InstanceId,
        affectedIds: List<Int>,
        chooseLinkType: String,
        sourceAbilityGrpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.LinkInfo)
            .setAffectorId(sourceInstanceId.value)
            .apply { affectedIds.forEach { addAffectedIds(it) } }
            .addDetails(int32Detail(DetailKeys.LINK_TYPE, 3))
            .addDetails(typedStringDetail(DetailKeys.CHOOSE_LINK_TYPE, chooseLinkType))
            .addDetails(int32Detail(DetailKeys.SOURCE_ABILITY_GRPID, sourceAbilityGrpId.value))
            .build()

    fun linkInfoActivatedAbility(
        abilityInstanceId: InstanceId,
        sourceCardInstanceId: InstanceId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.LinkInfo)
            .setAffectorId(abilityInstanceId.value)
            .addAffectedIds(sourceCardInstanceId.value)
            .addDetails(int32Detail(DetailKeys.LINK_TYPE, 2))
            .build()

    /** Card's power changed. State parser — P/T values from gameObject fields, not annotation.
     *  Optional details (context needed): effect_id, counter_type, count, sourceAbilityGRPID. */
    fun modifiedPower(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ModifiedPower)
            .addAffectedIds(instanceId.value)
            .build()

    /** Card's toughness changed. State parser — P/T values from gameObject fields, not annotation.
     *  Optional details (context needed): effect_id, counter_type, count, sourceAbilityGRPID. */
    fun modifiedToughness(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ModifiedToughness)
            .addAffectedIds(instanceId.value)
            .build()

    /**
     * Player lost the game. client annotation type 2 (LossOfGame_af5a).
     * [affectedPlayerSeatId] = seat of the losing player.
     * [reason] = [AnnotationLossReason] (life total, concede, poison, empty library).
     */
    fun lossOfGame(
        affectedPlayerSeatId: SeatId,
        reason: AnnotationLossReason,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.LossOfGame_af5a)
            .addAffectedIds(affectedPlayerSeatId.value)
            .addDetails(lossReasonDetail(reason))
            .build()

    /** Generic combat result marker. Client dispatches synthetic GameRulesEvent based on type. */
    fun syntheticEvent(
        attackerIid: InstanceId,
        targetSeatId: SeatId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.SyntheticEvent)
            .setAffectorId(attackerIid.value)
            .addAffectedIds(targetSeatId.value)
            .addDetails(int32Detail(DetailKeys.TYPE, 1))
            .build()

    /** Persistent annotation: card entered a zone this turn. Client uses for summoning sickness, ETB display. */
    fun enteredZoneThisTurn(
        zoneId: Int,
        instanceIds: List<InstanceId>,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.EnteredZoneThisTurn)
            .setAffectorId(zoneId)
            .apply { instanceIds.forEach { addAffectedIds(it.value) } }
            .build()

    /** Convenience for single-instance [enteredZoneThisTurn]. */
    fun enteredZoneThisTurn(
        zoneId: Int,
        instanceId: InstanceId,
    ): AnnotationInfo = enteredZoneThisTurn(zoneId, listOf(instanceId))

    // -- Group A+ annotation builders (attachments) --

    /** Transient: Aura/Equipment attached to target. client type 70 (AttachmentCreated).
     *  [auraIid] = the aura/equipment instanceId, [targetIid] = the enchanted/equipped permanent.
     *  Shape: affectorId=auraIid, affectedIds=[targetIid]. */
    fun attachmentCreated(
        auraIid: InstanceId,
        targetIid: InstanceId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.AttachmentCreated)
            .setAffectorId(auraIid.value)
            .addAffectedIds(targetIid.value)
            .build()

    /** Persistent: Ongoing attachment relationship. client type 20 (Attachment).
     *  [auraIid] = the aura/equipment instanceId, [targetIid] = the enchanted/equipped permanent.
     *  Shape: affectorId=auraIid, affectedIds=[targetIid]. */
    fun attachment(
        auraIid: InstanceId,
        targetIid: InstanceId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Attachment)
            .setAffectorId(auraIid.value)
            .addAffectedIds(targetIid.value)
            .build()

    /** Transient: Aura/Equipment detached from target. client type 12 (RemoveAttachment).
     *  [auraIid] = the aura/equipment instanceId that was removed. */
    fun removeAttachment(
        auraIid: InstanceId,
        targetIid: InstanceId? = null,
        invalidatingGrpId: GrpId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.RemoveAttachment)
            .apply {
                if (targetIid != null) {
                    setAffectorId(auraIid.value)
                    addAffectedIds(targetIid.value)
                } else {
                    addAffectedIds(auraIid.value)
                }
                if (invalidatingGrpId != null) {
                    addDetails(int32Detail(DetailKeys.INVALIDATING_GRPID, invalidatingGrpId.value))
                }
            }.build()

    // -- Group B+ annotation builders (reveals) --

    /** Card revealed to all players. client type 59 (RevealedCardCreated).
     *  [instanceId] = the revealed card's instanceId. */
    fun revealedCardCreated(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.RevealedCardCreated)
            .addAffectedIds(instanceId.value)
            .build()

    /** Card un-revealed (no longer visible). client type 60 (RevealedCardDeleted).
     *  [instanceId] = the card's instanceId being removed from revealed zone. */
    fun revealedCardDeleted(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.RevealedCardDeleted)
            .addAffectedIds(instanceId.value)
            .build()

    /** Short-lived face-up state carried by a RevealedCard view. */
    fun cardRevealed(
        affectorId: InstanceId,
        revealedCardId: InstanceId,
        sourceZoneId: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CardRevealed)
            .setAffectorId(affectorId.value)
            .addAffectedIds(revealedCardId.value)
            .addDetails(int32Detail(DetailKeys.SOURCE_ZONE, sourceZoneId))
            .build()

    // -- Group B annotation builders --

    /** Token was created. client type 35 (TokenCreated).
     *  [instanceId] = the new token's instanceId in the game state.
     *  [affectorId] = the resolving spell or stack ability that created it. */
    fun tokenCreated(
        instanceId: InstanceId,
        affectorId: InstanceId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.TokenCreated)
            .apply { affectorId?.let { setAffectorId(it.value) } }
            .addAffectedIds(instanceId.value)
            .build()

    /** Token was destroyed (left battlefield). client type 41 (TokenDeleted).
     *  [instanceId] = the token's instanceId. */
    fun tokenDeleted(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.TokenDeleted)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .build()

    /** Counter added to a permanent. client type 16 (CounterAdded). */
    fun counterAdded(
        instanceId: InstanceId,
        counterType: String,
        amount: Int,
        affectorId: InstanceId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CounterAdded)
            .setOptionalAffector(affectorId)
            .addAffectedIds(instanceId.value)
            .addDetails(counterTypeDetail(counterType))
            .addDetails(int32Detail(DetailKeys.TRANSACTION_AMOUNT, amount))
            .build()

    /** Counter removed from a permanent. client type 17 (CounterRemoved). */
    fun counterRemoved(
        instanceId: InstanceId,
        counterType: String,
        amount: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CounterRemoved)
            .addAffectedIds(instanceId.value)
            .addDetails(counterTypeDetail(counterType))
            .addDetails(int32Detail(DetailKeys.TRANSACTION_AMOUNT, amount))
            .build()

    /** Counter added to a player. Player-counter wire uses numeric counter ids. */
    fun playerCounterAdded(
        seatId: SeatId,
        counterType: Int,
        amount: Int,
        affectorId: InstanceId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CounterAdded)
            .apply { if (affectorId != null) setAffectorId(affectorId.value) }
            .addAffectedIds(seatId.value)
            .addDetails(int32Detail(DetailKeys.COUNTER_TYPE, counterType))
            .addDetails(int32Detail(DetailKeys.TRANSACTION_AMOUNT, amount))
            .build()

    /** Counter removed from a player. Player-counter wire uses numeric counter ids. */
    fun playerCounterRemoved(
        seatId: SeatId,
        counterType: Int,
        amount: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CounterRemoved)
            .addAffectedIds(seatId.value)
            .addDetails(int32Detail(DetailKeys.COUNTER_TYPE, counterType))
            .addDetails(int32Detail(DetailKeys.TRANSACTION_AMOUNT, amount))
            .build()

    /** Library shuffled. client type 56 (Shuffle). */
    fun shuffle(
        seatId: SeatId,
        oldIds: List<Int>,
        newIds: List<Int>,
        affectorId: InstanceId?,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Shuffle)
            .addAffectedIds(seatId.value)
            .apply { affectorId?.let { setAffectorId(it.value) } }
            .addDetails(int32ListDetail(DetailKeys.OLD_IDS, oldIds))
            .addDetails(int32ListDetail(DetailKeys.NEW_IDS, newIds))
            .build()

    /** Scry action. client annotation type 65 (Scry_af5a). */
    fun scry(
        seatId: SeatId,
        topIds: List<Int>,
        bottomIds: List<Int>,
    ): AnnotationInfo {
        val affected = topIds + bottomIds
        return AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Scry_af5a)
            .apply { if (affected.isEmpty()) addAffectedIds(seatId.value) else addAllAffectedIds(affected) }
            .addDetails(int32ListDetail(DetailKeys.TOP_IDS, topIds))
            .addDetails(int32ListDetail(DetailKeys.BOTTOM_IDS, bottomIds))
            .build()
    }

    // -- Tier 1 state annotations --

    /** Counter state: authoritative counter count on a permanent. client type 14 (Counter_803b).
     *  Three-parser pattern: type 14 (this, state) + 16 (CounterAdded, event) + 17 (CounterRemoved, event).
     *  P/T counters also carry ModifiedPower/ModifiedToughness co-types that read the same details.
     *  [counterType] = numeric counter type (1 = +1/+1). */
    fun counter(
        instanceId: InstanceId,
        counterType: Int,
        count: Int,
    ): AnnotationInfo {
        val builder = AnnotationInfo.newBuilder()
        if (isPowerToughnessCounter(counterType)) {
            builder.addType(AnnotationType.ModifiedToughness)
            builder.addType(AnnotationType.ModifiedPower)
        }
        return builder
            .addType(AnnotationType.Counter_803b)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.COUNT, count))
            .addDetails(int32Detail(DetailKeys.COUNTER_TYPE, counterType))
            .build()
    }

    private fun isPowerToughnessCounter(counterType: Int): Boolean =
        counterType == CounterType.P1P1.number || counterType == CounterType.M1M1.number

    /** Counter type as the client's numeric `CounterType`. The client reads this
     *  detail as int32 (`CounterAddedRemovedAnnotationParser`); a string value
     *  throws there and costs the whole GRE message. Unmapped Forge names send 0. */
    private fun counterTypeDetail(counterType: String): KeyValuePairInfo =
        int32Detail(DetailKeys.COUNTER_TYPE, CounterTypes.counterTypeId(counterType))

    /** Counter state on a player. */
    fun playerCounter(
        seatId: SeatId,
        counterType: Int,
        count: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Counter_803b)
            .addAffectedIds(seatId.value)
            .addDetails(int32Detail(DetailKeys.COUNT, count))
            .addDetails(int32Detail(DetailKeys.COUNTER_TYPE, counterType))
            .build()

    /**
     * Persistent annotation for ability word condition tracking.
     *
     * Shape:
     * - types: [AbilityWordActive]
     * - affectorId: creature instanceId (or seat=1 for Descended)
     * - affectedIds: [creature instanceId]
     * - details: AbilityWordName (always), value/threshold/AbilityGrpId (quantitative only)
     */
    fun abilityWordActive(
        instanceId: InstanceId,
        abilityWordName: String,
        value: Int? = null,
        threshold: Int? = null,
        abilityGrpId: GrpId? = null,
        colors: List<Int>? = null,
        affectorId: InstanceId = instanceId,
        affectedIds: List<InstanceId> = listOf(instanceId),
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.AbilityWordActive)
            .setAffectorId(affectorId.value)
            .apply { affectedIds.forEach { addAffectedIds(it.value) } }
            .addDetails(typedStringDetail(DetailKeys.ABILITY_WORD_NAME, abilityWordName))
            .apply {
                if (value != null) addDetails(int32Detail(DetailKeys.VALUE, value))
                if (threshold != null) addDetails(int32Detail(DetailKeys.THRESHOLD, threshold))
                if (abilityGrpId != null) addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID_UPPER, abilityGrpId.value))
                if (colors != null) addDetails(int32ListDetail(DetailKeys.COLORS, colors))
            }.build()

    /**
     * Keyword grant via layered effect — multi-creature form.
     * Types: [AddAbility_af5a, LayeredEffect]. One pAnn covers all affected creatures.
     *
     * Shape: flat affectedIds list, one UniqueAbilityId per creature, shared grpId.
     */
    fun addAbilityMulti(
        affectedIds: List<InstanceId>,
        grpId: GrpId,
        effectId: EffectId,
        uniqueAbilityIds: List<Int>,
        originalAbilityObjectZcid: Int,
        affectorId: InstanceId,
    ): AnnotationInfo {
        val builder =
            AnnotationInfo
                .newBuilder()
                .addType(AnnotationType.AddAbility_af5a)
                .addType(AnnotationType.LayeredEffect)
                .setAffectorId(affectorId.value)
                .addDetails(int32Detail(DetailKeys.GRPID, grpId.value))
                .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
                .addDetails(int32Detail(DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID, originalAbilityObjectZcid))
        affectedIds.forEach { builder.addAffectedIds(it.value) }
        uniqueAbilityIds.forEach { builder.addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, it)) }
        return builder.build()
    }

    /** One card's generated activated ability supplied by a layered effect. */
    fun addAbilityLayered(
        affectedId: InstanceId,
        grpId: GrpId,
        effectId: EffectId,
        uniqueAbilityId: Int,
        originalAbilityObjectZcid: Int,
        affectorId: InstanceId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.AddAbility_af5a)
            .addType(AnnotationType.LayeredEffect)
            .setAffectorId(affectorId.value)
            .addAffectedIds(affectedId.value)
            .addDetails(int32Detail(DetailKeys.GRPID, grpId.value))
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
            .addDetails(int32Detail(DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID, originalAbilityObjectZcid))
            .addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, uniqueAbilityId))
            .build()

    /**
     * Multi-keyword grant for auras (e.g. Flying + First Strike from Angelic Destiny).
     * Packs multiple grpIds/UniqueAbilityIds into one [AddAbility+LayeredEffect] pAnn.
     */
    fun addAbilityPacked(
        affectedId: InstanceId,
        grpIds: List<GrpId>,
        effectId: EffectId,
        uniqueAbilityIds: List<Int>,
        originalAbilityObjectZcids: List<Int>,
        affectorId: InstanceId,
    ): AnnotationInfo {
        require(grpIds.size == uniqueAbilityIds.size) { "grpIds and uniqueAbilityIds must match" }
        val builder =
            AnnotationInfo
                .newBuilder()
                .addType(AnnotationType.AddAbility_af5a)
                .addType(AnnotationType.LayeredEffect)
                .setAffectorId(affectorId.value)
                .addAffectedIds(affectedId.value)
                .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
        grpIds.forEach { builder.addDetails(int32Detail(DetailKeys.GRPID, it.value)) }
        uniqueAbilityIds.forEach { builder.addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, it)) }
        originalAbilityObjectZcids.forEach {
            builder.addDetails(int32Detail(DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID, it))
        }
        return builder.build()
    }

    /**
     * Persistent annotation marking a card as eligible for an alternate cast
     * (adventure from exile, etc.).
     *
     * grpId=196 appears universal for adventure Qualification — likely a
     * fixed ability ID, not per-card.
     */
    fun qualification(
        instanceId: InstanceId,
        qualificationType: QualificationType = QualificationType.Adventure,
        qualificationSubtype: Int = 0,
        grpId: GrpId = AnnotationConstants.ADVENTURE_QUALIFICATION_GRP_ID,
        sourceParent: InstanceId = InstanceId(0),
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Qualification)
            .addAffectedIds(instanceId.value)
            .addDetails(qualificationDetail(DetailKeys.SOURCE_PARENT, sourceParent.value))
            .addDetails(qualificationDetail(DetailKeys.GRPID, grpId.value))
            .addDetails(qualificationDetail(DetailKeys.QUALIFICATION_SUBTYPE, qualificationSubtype))
            .addDetails(qualificationDetail(DetailKeys.QUALIFICATION_TYPE, qualificationType.wireValue))
            .build()

    // -- Tier 1 state annotations (abilities, effects, designations) --

    /** Granted ability state. client type 9 (AddAbility_af5a). */
    fun addAbility(
        instanceId: InstanceId,
        grpId: GrpId,
        effectId: EffectId,
        uniqueAbilityId: Int,
        originalAbilityObjectZcid: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.AddAbility_af5a)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.GRPID, grpId.value))
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
            .addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, uniqueAbilityId))
            .addDetails(int32Detail(DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID, originalAbilityObjectZcid))
            .build()

    /** Ability removed by effect. client type 23 (RemoveAbility). */
    fun removeAbility(
        instanceId: InstanceId,
        effectId: EffectId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.RemoveAbility)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
            .build()

    /** Per-ability use tracking. client type 82 (AbilityExhausted). */
    fun abilityExhausted(
        instanceId: InstanceId,
        abilityGrpId: GrpId,
        usesRemaining: Int,
        uniqueAbilityId: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.AbilityExhausted)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID_UPPER, abilityGrpId.value))
            .addDetails(int32Detail(DetailKeys.USES_REMAINING, usesRemaining))
            .addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, uniqueAbilityId))
            .build()

    /** Designation gained (Monarch, City's Blessing, Initiative). client type 46 (GainDesignation).
     *  Event parser — emits DesignationCreatedEvent. */
    fun gainDesignation(
        seatId: SeatId,
        designationType: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.GainDesignation)
            .addAffectedIds(seatId.value)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, designationType))
            .build()

    /** Designation state (persistent). client type 45 (Designation).
     *  Stub — always-present key only. Full version needs PromptMessage, CostIncrease,
     *  grpid, ActivePlayerSpellCount, value, ColorIdentity (context needed). */
    fun designation(
        seatId: SeatId,
        designationType: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .addAffectedIds(seatId.value)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, designationType))
            .build()

    /** Persistent player speed designation (DesignationType=21). */
    fun playerSpeedDesignation(
        seatId: SeatId,
        speed: Int,
        triggerHolderIid: InstanceId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .setAffectorId(seatId.value)
            .addAffectedIds(seatId.value)
            .addAffectedIds(triggerHolderIid.value)
            .addDetails(int32Detail(DetailKeys.VALUE, speed))
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, AnnotationConstants.DESIGNATION_TYPE_PLAYER_SPEED))
            .build()

    /** GainDesignation transient on a card (Prepared, Saddled, Plotted, Door states).
     *  Card-scoped variant — defaults affector to the affected card unless a
     *  resolving ability is the protocol source for this designation change.
     *  See [gainDesignation] for the seat-scoped variant (Monarch, Initiative, City's Blessing). */
    fun gainDesignationOnCard(
        instanceId: InstanceId,
        designationType: Int,
        affectorId: InstanceId = instanceId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.GainDesignation)
            .setAffectorId(affectorId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, designationType))
            .build()

    /** GainDesignation transient on the game itself. Lite shape — `DesignationType`
     *  is the only detail key. Used by game-scope state primitives (Day=10/Night=11).
     *  `affectedIds=[0]`, no `affectorId` — the rules engine, not a specific source,
     *  drove the state change. */
    fun gainDesignationOnGame(designationType: Int): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.GainDesignation)
            .addAffectedIds(0)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, designationType))
            .build()

    /** LoseDesignation transient on the game itself. Lite shape. Pairs with
     *  [gainDesignationOnGame] in the same GSM at a Day↔Night flip — outgoing
     *  state's lose plus incoming state's gain. `affectedIds=[0]`, no `affectorId`. */
    fun loseDesignationOnGame(designationType: Int): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.LoseDesignation)
            .addAffectedIds(0)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, designationType))
            .build()

    /** Persistent `Designation` for game-scope Day/Night state.
     *  Carries the `ActivePlayerSpellCount` running tally — re-emitted every GSM
     *  once the state is established. `affectedIds=[0]`, no `affectorId`.
     *  Detail-key order matches the rest of the Designation family — auxiliary
     *  rich key (APSC) first, `DesignationType` last (mirrors how
     *  [preparedDesignation] orders `PreparedCopyZcid` then `DesignationType`). */
    fun dayNightDesignation(
        designationType: Int,
        activePlayerSpellCount: Int,
    ): AnnotationInfo {
        require(
            designationType == AnnotationConstants.DESIGNATION_TYPE_DAY ||
                designationType == AnnotationConstants.DESIGNATION_TYPE_NIGHT,
        ) {
            "dayNightDesignation requires DesignationType=10 (Day) or 11 (Night), got $designationType"
        }
        return AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .addAffectedIds(0)
            .addDetails(int32Detail(DetailKeys.ACTIVE_PLAYER_SPELL_COUNT, activePlayerSpellCount))
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, designationType))
            .build()
    }

    /** Persistent Commander `Designation` attached to a player seat. */
    fun commanderPlayerDesignation(
        seatId: SeatId,
        grpId: GrpId,
        colorIdentity: List<Int>,
        costIncrease: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .setAffectorId(seatId.value)
            .addAffectedIds(seatId.value)
            .addDetails(int32ListDetail(DetailKeys.COLOR_IDENTITY, colorIdentity))
            .addDetails(int32Detail(DetailKeys.COST_INCREASE, costIncrease))
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, AnnotationConstants.DESIGNATION_TYPE_COMMANDER))
            .addDetails(int32Detail(DetailKeys.GRPID, grpId.value))
            .build()

    /** Persistent Commander `Designation` attached to the commander object. */
    fun commanderObjectDesignation(
        instanceId: InstanceId,
        grpId: GrpId,
        colorIdentity: List<Int>,
        costIncrease: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32ListDetail(DetailKeys.COLOR_IDENTITY, colorIdentity))
            .addDetails(int32Detail(DetailKeys.COST_INCREASE, costIncrease))
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, AnnotationConstants.DESIGNATION_TYPE_COMMANDER))
            .addDetails(int32Detail(DetailKeys.GRPID, grpId.value))
            .build()

    /** Persistent `Designation` for the `Prepared` card-state designation.
     *  Carries the int32 `PreparedCopyZcid` detail pointing at the prepare-spell exile copy.
     *  affector / affectedIds both = the prepared creature's instance id. */
    fun preparedDesignation(
        instanceId: InstanceId,
        preparedCopyInstanceId: InstanceId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.PREPARED_COPY_ZCID, preparedCopyInstanceId.value))
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, AnnotationConstants.DESIGNATION_TYPE_PREPARED))
            .build()

    /** Persistent `Designation` for the `Plotted` card-state designation (DesignationType=18).
     *  Carries no extra detail keys — Plotted has no analog of `PreparedCopyZcid` because the
     *  plotted card itself sits in exile (no copy). affector / affectedIds both = the plotted
     *  card's instance id. */
    fun plottedDesignation(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, AnnotationConstants.DESIGNATION_TYPE_PLOTTED))
            .build()

    /** Persistent `Designation` for the `Saddled` card-state designation (DesignationType=17).
     *  affector / affectedIds both = the saddled mount's battlefield instance id. */
    fun saddledDesignation(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, AnnotationConstants.DESIGNATION_TYPE_SADDLED))
            .build()

    /** Persistent `Designation` for the `Suspected` card-state designation (DesignationType=16). */
    fun suspectedDesignation(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, AnnotationConstants.DESIGNATION_TYPE_SUSPECTED))
            .build()

    /** Persistent `Designation` for a solved Case (DesignationType=15). */
    fun solvedDesignation(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, AnnotationConstants.DESIGNATION_TYPE_SOLVED))
            .build()

    /** Persistent `Designation` for the `LeftUnlocked` Room-door state (DesignationType=19).
     *  affector / affectedIds both = the Room card's battlefield instance id. */
    fun leftUnlockedDesignation(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, AnnotationConstants.DESIGNATION_TYPE_LEFT_UNLOCKED))
            .build()

    /** Persistent `Designation` for the `RightUnlocked` Room-door state (DesignationType=20).
     *  affector / affectedIds both = the Room card's battlefield instance id. */
    fun rightUnlockedDesignation(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, AnnotationConstants.DESIGNATION_TYPE_RIGHT_UNLOCKED))
            .build()

    /** LoseDesignation transient on a card. Fires when a card-state designation
     *  ends (e.g. prepared spell cast → source creature unprepared). */
    fun loseDesignation(
        instanceId: InstanceId,
        designationType: Int,
        affectorId: InstanceId = instanceId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.LoseDesignation)
            .setAffectorId(affectorId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, designationType))
            .build()

    /** FaceDown transient annotation. Marks an exile object as face-down
     *  (foretell, hideaway, suspend). Carries no detail keys. */
    fun faceDown(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.FaceDown)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .build()

    /** Persistent FaceDown annotation for a face-down card. Carries the
     *  mechanic discriminator on `REASON` and the corresponding ability
     *  identity on `abilityGrpId`.
     *
     *  affector / affectedIds both = the face-down card's instance id.
     *  Lives across many GSMs; deleted via diff-tracking when the card stops
     *  satisfying the mechanic-specific face-down state. */
    fun faceDownPersistent(
        instanceId: InstanceId,
        reason: Int,
        abilityGrpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.FaceDown)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.REASON_UPPER, reason))
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID, abilityGrpId.value))
            .build()

    /** SuppressedPowerAndToughness transient annotation. Pairs with FaceDown
     *  for face-down exile objects that lose their P/T projection. */
    fun suppressedPowerAndToughness(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.SuppressedPowerAndToughness)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .build()

    /** Layered effect creation event (buff/debuff started). client type 18 (LayeredEffectCreated).
     *  Transient — fires once when the effect begins. No detail keys on this annotation;
     *  all metadata lives on the companion LayeredEffect persistent annotation.
     *  [affectorId] = ability instance on stack that created the effect (optional — ~35% omitted). */
    fun layeredEffectCreated(
        effectId: EffectId,
        affectorId: InstanceId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.LayeredEffectCreated)
            .addAffectedIds(effectId.value)
            .setOptionalAffector(affectorId)
            .build()

    fun redundantActivation(
        instanceId: InstanceId,
        abilityGrpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ShouldntPlay)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(typedStringDetail(DetailKeys.SHOULDNT_PLAY_REASON, "RedundantActivation"))
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID, abilityGrpId.value))
            .build()

    /** Layered effect state (continuous effects). client type 51 (LayeredEffect).
     *  Persistent — present in every GSM while the effect is active.
     *
     *  Client expects multi-type arrays: `[ModifiedToughness, ModifiedPower, LayeredEffect]`
     *  for P/T buffs — the co-types are part of the contract (drive client animation dispatch).
     *  [affectorId] = the affected creature (for P/T buffs), not the ability instance.
     *  [sourceAbilityGrpId] = ability grpId that created the effect (drives specific VFX, e.g. Prowess).
     *
     *  No `LayeredEffectType` for P/T buffs — client only expects it for CopyObject. */
    fun layeredEffect(
        instanceId: InstanceId,
        effectId: EffectId,
        powerDelta: Int = 0,
        toughnessDelta: Int = 0,
        affectorId: InstanceId? = null,
        sourceAbilityGrpId: GrpId? = null,
        affectedInstanceIds: List<InstanceId> = listOf(instanceId),
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            // Multi-type: co-type with ModifiedPower/ModifiedToughness for P/T buffs
            .apply {
                if (toughnessDelta != 0) addType(AnnotationType.ModifiedToughness)
                if (powerDelta != 0) addType(AnnotationType.ModifiedPower)
            }.addType(AnnotationType.LayeredEffect)
            .addAllAffectedIds(affectedInstanceIds.map { it.value })
            .setOptionalAffector(affectorId)
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
            .apply {
                if (sourceAbilityGrpId != null) {
                    addDetails(int32Detail(DetailKeys.SOURCE_ABILITY_GRPID, sourceAbilityGrpId.value))
                }
            }.build()

    fun mutateLayeredEffect(
        componentId: InstanceId,
        targetId: InstanceId,
        effectId: EffectId,
        abilityGrpIds: List<Int>,
        isTop: Boolean,
        abilityGrpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.LayeredEffect)
            .setAffectorId(componentId.value)
            .addAffectedIds(targetId.value)
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
            .addDetails(int32ListDetail(DetailKeys.ABILITY_GRP_IDS, abilityGrpIds))
            .addDetails(int32Detail(DetailKeys.IS_TOP, if (isTop) 1 else 0))
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID, abilityGrpId.value))
            .build()

    // -- Tier 2 detail-carrying annotations --

    /** Land color production for card frame rendering. client type 110 (ColorProduction).
     *  [colors] = client ManaColor ordinals (W=1, U=2, B=3, R=4, G=5). */
    fun colorProduction(
        instanceId: InstanceId,
        colors: List<Int>,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ColorProduction)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32ListDetail(DetailKeys.COLORS, colors))
            .build()

    fun classLevel(
        instanceId: InstanceId,
        level: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ClassLevel)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.LEVEL, level))
            .build()

    /** Persistent annotation linking a triggered ability on the stack back to the
     *  exact object that caused it. Client draws the source-arrow UI from that
     *  object to the ability on the stack. Removed when the ability resolves
     *  or is otherwise removed from the stack.
     *  Shape: affectorId = stack ability instance, affectedIds = [source card].
     *  Client annotation type 32 (TriggeringObject). */
    fun triggeringObject(
        abilityInstanceId: InstanceId,
        sourceCardInstanceId: InstanceId,
        sourceZone: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.TriggeringObject)
            .setAffectorId(abilityInstanceId.value)
            .addAffectedIds(sourceCardInstanceId.value)
            .addDetails(int32Detail(DetailKeys.SOURCE_ZONE, sourceZone))
            .build()

    /** Target specification for spells/abilities. client type 26 (TargetSpec). */
    fun targetSpec(
        instanceIds: List<InstanceId>,
        affectorId: InstanceId,
        abilityGrpId: GrpId,
        index: Int,
        promptId: Int,
        promptParameters: Int,
        distributions: List<Int> = emptyList(),
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.TargetSpec)
            .setAffectorId(affectorId.value)
            .addAllAffectedIds(instanceIds.map { it.value })
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID, abilityGrpId.value))
            .addDetails(int32Detail(DetailKeys.INDEX, index))
            .addDetails(int32Detail(DetailKeys.PROMPT_ID, promptId))
            .addDetails(int32Detail(DetailKeys.PROMPT_PARAMETERS, promptParameters))
            .apply {
                if (distributions.isNotEmpty()) {
                    addDetails(int32ListDetail(DetailKeys.DISTRIBUTIONS, distributions))
                }
            }.build()

    fun targetSpec(
        instanceId: InstanceId,
        affectorId: InstanceId,
        abilityGrpId: GrpId,
        index: Int,
        promptId: Int,
        promptParameters: Int,
    ): AnnotationInfo = targetSpec(listOf(instanceId), affectorId, abilityGrpId, index, promptId, promptParameters)

    /** P/T modification event (buff animation). client type 71 (PowerToughnessModCreated).
     *  [affectorId] = source of the P/T change (ability instance or card). */
    fun powerToughnessModCreated(
        instanceId: InstanceId,
        power: Int,
        toughness: Int,
        affectorId: InstanceId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.PowerToughnessModCreated)
            .addAffectedIds(instanceId.value)
            .setOptionalAffector(affectorId)
            .addDetails(int32Detail(DetailKeys.POWER, power))
            .addDetails(int32Detail(DetailKeys.TOUGHNESS, toughness))
            .build()

    /** Card displayed under another card (exile-under-permanent, imprint, adventure exile).
     *  client type 38 (DisplayCardUnderCard). Persistent while source permanent remains.
     *  Shape: affectorId=sourcePermanentIid, affectedIds=[exiledCardIid]. */
    fun displayCardUnderCard(
        affectorId: InstanceId,
        instanceId: InstanceId,
        disable: Int = 0,
        temporaryZoneTransfer: Int = 1,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.DisplayCardUnderCard)
            .setAffectorId(affectorId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.DISABLE, disable))
            .addDetails(int32Detail(DetailKeys.TEMPORARY_ZONE_TRANSFER, temporaryZoneTransfer))
            .build()

    /** Predicted direct damage preview text. client type 66 (PredictedDirectDamage). */
    fun predictedDirectDamage(
        instanceId: InstanceId,
        value: Int,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.PredictedDirectDamage)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.VALUE, value))
            .build()

    // -- Controller change annotations --

    /** Transient: controller changed event. client type 15 (ControllerChanged).
     *  Shape: affectorId = spell/ability instance, affectedIds = [stolen permanent].
     *  No details field. */
    fun controllerChanged(
        affectorId: InstanceId,
        instanceId: InstanceId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ControllerChanged)
            .setAffectorId(affectorId.value)
            .addAffectedIds(instanceId.value)
            .build()

    /** Persistent: controller change continuous effect. Types: [ControllerChanged, LayeredEffect].
     *  Details: effect_id, optional sourceAbilityGRPID. Persists while steal is active; removed on expiry. */
    fun controllerChangedEffect(
        affectorId: InstanceId,
        instanceId: InstanceId,
        effectId: EffectId,
        sourceAbilityGrpId: GrpId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ControllerChanged)
            .addType(AnnotationType.LayeredEffect)
            .setAffectorId(affectorId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
            .also { b -> sourceAbilityGrpId?.let { b.addDetails(int32Detail(DetailKeys.SOURCE_ABILITY_GRPID, it.value)) } }
            .build()

    // -- Tier 2 detail-less annotations --

    /** Layered effect ended. [affectorId] = source of the destruction (e.g. aura iid for
     *  SBA_UnattachedAura; 0/omitted for EOT expiry). client type 19. */
    fun layeredEffectDestroyed(
        effectId: EffectId,
        affectorId: InstanceId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.LayeredEffectDestroyed)
            .setOptionalAffector(affectorId)
            .addAffectedIds(effectId.value)
            .build()

    /** Player is selecting targets for a spell/ability. client type 92.
     *  affectorId = caster seatId; affectedIds = [spellInstanceIdOnStack]. */
    fun playerSelectingTargets(
        instanceId: InstanceId,
        casterSeatId: SeatId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.PlayerSelectingTargets)
            .setAffectorId(casterSeatId.value)
            .addAffectedIds(instanceId.value)
            .build()

    /** Player submitted target selections. client type 93.
     *  affectorId = caster seatId; affectedIds = [spellInstanceIdOnStack]. */
    fun playerSubmittedTargets(
        instanceId: InstanceId,
        casterSeatId: SeatId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.PlayerSubmittedTargets)
            .setAffectorId(casterSeatId.value)
            .addAffectedIds(instanceId.value)
            .build()

    /**
     * Persistent: vehicle was crewed this turn. client type 94 (CrewedThisTurn).
     * Shape: affectorId = vehicle instanceId, affectedIds = crew source instanceIds.
     * Emitted when crew resolves; persists until end of turn.
     */
    fun crewedThisTurn(
        vehicleInstanceId: InstanceId,
        crewSourceInstanceIds: List<InstanceId>,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.CrewedThisTurn)
            .setAffectorId(vehicleInstanceId.value)
            .apply { crewSourceInstanceIds.forEach { addAffectedIds(it.value) } }
            .build()

    /**
     * Persistent: mount was saddled this turn. client type 104 (SaddledThisTurn).
     * Shape mirrors CrewedThisTurn: affectorId = mount instanceId,
     * affectedIds = helper creature instanceIds.
     */
    fun saddledThisTurn(
        mountInstanceId: InstanceId,
        saddleSourceInstanceIds: List<InstanceId>,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.SaddledThisTurn)
            .setAffectorId(mountInstanceId.value)
            .apply { saddleSourceInstanceIds.forEach { addAffectedIds(it.value) } }
            .build()

    /**
     * Persistent: vehicle became a creature via crew (type change). Types: [ModifiedType, LayeredEffect].
     * Shape: affectedIds = [vehicleInstanceId], effect_id, sourceAbilityGRPID (crew ability grpId).
     * Emitted when crew resolves and vehicle gains Creature type; removed on expiry.
     */
    fun modifiedTypeLayeredEffect(
        instanceId: InstanceId,
        effectId: EffectId,
        affectorId: InstanceId? = null,
        sourceAbilityGrpId: GrpId? = null,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ModifiedType)
            .addType(AnnotationType.LayeredEffect)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
            .setOptionalAffector(affectorId)
            .apply {
                if (sourceAbilityGrpId != null) {
                    addDetails(int32Detail(DetailKeys.SOURCE_ABILITY_GRPID, sourceAbilityGrpId.value))
                }
            }.build()

    fun earthbendModifiedTypeLayeredEffect(
        instanceId: InstanceId,
        affectorId: InstanceId,
        effectId: EffectId,
        sourceAbilityGrpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ModifiedType)
            .addType(AnnotationType.LayeredEffect)
            .setAffectorId(affectorId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.SOURCE_ABILITY_GRPID, sourceAbilityGrpId.value))
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
            .build()

    fun earthbendAddHasteLayeredEffect(
        instanceId: InstanceId,
        affectorId: InstanceId,
        effectId: EffectId,
        sourceAbilityGrpId: GrpId,
        uniqueAbilityId: Int,
        originalAbilityObjectZcid: Int,
        hasteGrpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.AddAbility_af5a)
            .addType(AnnotationType.LayeredEffect)
            .setAffectorId(affectorId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.ORIGINAL_ABILITY_OBJECT_ZCID, originalAbilityObjectZcid))
            .addDetails(int32Detail(DetailKeys.UNIQUE_ABILITY_ID, uniqueAbilityId))
            .addDetails(int32Detail(DetailKeys.GRPID, hasteGrpId.value))
            .addDetails(int32Detail(DetailKeys.SOURCE_ABILITY_GRPID, sourceAbilityGrpId.value))
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
            .build()

    fun earthbendModifiedPowerLayeredEffect(
        instanceId: InstanceId,
        affectorId: InstanceId,
        effectId: EffectId,
        sourceAbilityGrpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ModifiedPower)
            .addType(AnnotationType.LayeredEffect)
            .setAffectorId(affectorId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.SOURCE_ABILITY_GRPID, sourceAbilityGrpId.value))
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
            .build()

    fun earthbendModifiedToughnessLayeredEffect(
        instanceId: InstanceId,
        affectorId: InstanceId,
        effectId: EffectId,
        sourceAbilityGrpId: GrpId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ModifiedToughness)
            .addType(AnnotationType.LayeredEffect)
            .setAffectorId(affectorId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.SOURCE_ABILITY_GRPID, sourceAbilityGrpId.value))
            .addDetails(int32Detail(DetailKeys.EFFECT_ID, effectId.value))
            .build()

    fun manaCreatureDesignation(
        instanceId: InstanceId,
        controllerId: SeatId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Designation)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(int32Detail(DetailKeys.DESIGNATION_TYPE, AnnotationConstants.DESIGNATION_TYPE_MANA_CREATURE))
            .addDetails(int32Detail(DetailKeys.CONTROLLER_ID, controllerId.value))
            .build()

    /** Creatures dealt damage this turn. Persistent badge. client type 90.
     *  `affectorId` defaults to the shared Battlefield zone.
     *  `affectedIds` is the cumulative set of victims for the current turn. */
    fun damagedThisTurn(
        affectedIds: List<InstanceId>,
        affectorId: InstanceId = AnnotationConstants.BATTLEFIELD_ZONE_AFFECTOR,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.DamagedThisTurn)
            .setAffectorId(affectorId.value)
            .addAllAffectedIds(affectedIds.map { it.value })
            .build()

    /**
     * Persistent marker linking a temporary object to its lifecycle ability.
     * [abilityGrpId] identifies the ability that owns the object's eventual
     * cleanup or sacrifice. [affectorId] defaults to the affected object; a
     * delayed trigger holder can own the relationship instead.
     */
    fun temporaryPermanent(
        affectedInstanceId: InstanceId,
        abilityGrpId: GrpId = AnnotationConstants.EOT_SACRIFICE_GRP_ID,
        affectorId: InstanceId = affectedInstanceId,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.TemporaryPermanent)
            .setAffectorId(affectorId.value)
            .addAffectedIds(affectedInstanceId.value)
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID_UPPER, abilityGrpId.value))
            .build()

    /**
     * Group of tokens affected by a delayed triggered ability (e.g. Mobilize's
     * end-of-turn sacrifice trigger). Persistent annotation. Client type 74.
     *
     * @param triggerHolderId the transient trigger-holder object (typically lives
     *   in Limbo with grpId=5) that owns the delayed trigger.
     * @param tokenInstanceIds the tokens scheduled to be affected when the delayed
     *   trigger fires.
     * @param abilityGrpId the cleanup-trigger ability's grpId (e.g. 189931 for
     *   Mobilize 1's "Sacrifice them at the beginning of the next end step").
     * @param removesFromZone optional zone-removal marker. Omitted for return
     *   triggers; set to 1 for cleanup triggers such as Mobilize sacrifice.
     */
    fun delayedTriggerAffectees(
        triggerHolderId: InstanceId,
        tokenInstanceIds: List<InstanceId>,
        abilityGrpId: GrpId,
        removesFromZone: Int? = 1,
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.DelayedTriggerAffectees)
            .setAffectorId(triggerHolderId.value)
            .also { b -> tokenInstanceIds.forEach { b.addAffectedIds(it.value) } }
            .addDetails(int32Detail(DetailKeys.ABILITY_GRP_ID, abilityGrpId.value))
            .also { b -> removesFromZone?.let { b.addDetails(int32Detail(DetailKeys.REMOVES_FROM_ZONE, it)) } }
            .build()

    /** Card in hidden zone revealed to opponent. Persistent badge. client type 75. */
    fun instanceRevealedToOpponent(instanceId: InstanceId): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.InstanceRevealedToOpponent)
            .setAffectorId(instanceId.value)
            .addAffectedIds(instanceId.value)
            .build()

    /** Keyword qualification badge on a permanent. Persistent. client type 42.
     *  [grpId] = keyword grpId (e.g. 142 for Menace).
     *  [qualificationType] = [QualificationType] enum (e.g. CombatKeyword for Menace).
     *  [sourceParent] = instanceId of the permanent granting the keyword (usually self). */
    fun qualification(
        affectorId: InstanceId,
        instanceId: InstanceId,
        grpId: GrpId,
        qualificationType: QualificationType,
        qualificationSubtype: Int = 0,
        sourceParent: InstanceId,
        cantBlockObjects: List<Int> = emptyList(),
        cantBeBlockedByObjects: List<Int> = emptyList(),
    ): AnnotationInfo =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.Qualification)
            .setAffectorId(affectorId.value)
            .addAffectedIds(instanceId.value)
            .addDetails(qualificationDetail(DetailKeys.SOURCE_PARENT, sourceParent.value))
            .addDetails(qualificationDetail(DetailKeys.GRPID, grpId.value))
            .addDetails(qualificationDetail(DetailKeys.QUALIFICATION_SUBTYPE, qualificationSubtype))
            .addDetails(qualificationDetail(DetailKeys.QUALIFICATION_TYPE, qualificationType.wireValue))
            .also { builder ->
                if (cantBlockObjects.isNotEmpty()) {
                    builder.addDetails(int32ListDetail(DetailKeys.CANT_BLOCK_OBJECTS, cantBlockObjects))
                }
                if (cantBeBlockedByObjects.isNotEmpty()) {
                    builder.addDetails(int32ListDetail(DetailKeys.CANT_BE_BLOCKED_BY_OBJECTS, cantBeBlockedByObjects))
                }
            }.build()

    private fun typedStringDetail(
        key: String,
        value: String,
    ): KeyValuePairInfo =
        KeyValuePairInfo
            .newBuilder()
            .setKey(key)
            .setType(KeyValuePairValueType.String)
            .addValueString(value)
            .build()

    private fun lossReasonDetail(reason: AnnotationLossReason): KeyValuePairInfo =
        reason.wireString?.let { typedStringDetail(DetailKeys.REASON, it) }
            ?: int32Detail(
                DetailKeys.REASON,
                reason.wireInt ?: error("AnnotationLossReason ${reason.name} has no wire value"),
            )

    private fun int32Detail(
        key: String,
        value: Int,
    ): KeyValuePairInfo =
        KeyValuePairInfo
            .newBuilder()
            .setKey(key)
            .setType(KeyValuePairValueType.Int32)
            .addValueInt32(value)
            .build()

    private fun qualificationDetail(
        key: String,
        value: Int,
    ): KeyValuePairInfo =
        // The Qualification badge parser reads these numeric details from int32 fields.
        int32Detail(key, value)

    private fun int32ListDetail(
        key: String,
        values: List<Int>,
    ): KeyValuePairInfo =
        KeyValuePairInfo
            .newBuilder()
            .setKey(key)
            .setType(KeyValuePairValueType.Int32)
            .apply { values.forEach { addValueInt32(it) } }
            .build()
}
