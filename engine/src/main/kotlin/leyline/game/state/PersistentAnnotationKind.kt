package leyline.game.state

import forge.game.phase.PhaseType
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.DetailKeys
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Per-frame context the persistent-annotation lifecycle reads — phase /
 * active-player for turn-boundary expiries (EZTT, DamagedThisTurn), plus
 * battlefield membership for "card-left-play" expiries (ColorProduction).
 *
 * Built fresh per Diff GSM by [leyline.game.mapping.StateMapper] from the
 * current snapshot + bridge.
 */
data class FrameContext(
    val phase: PhaseType?,
    val activePlayerSeat: SeatId,
    /** Instance ids currently on the battlefield zone in `cur` snapshot. */
    val battlefieldIids: Set<Int>,
    /** Per-iid controller from `cur` snapshot — drives per-controller expiry
     *  gates. Map omits iids not present in `cur.boundCards` (cards already
     *  off-objects); EZTT treats unknown-controller as "expire on any Upkeep"
     *  to prevent stale accumulation. */
    val controllerOf: Map<Int, SeatId>,
    /** Instance ids currently on the stack — drives TriggeringObject expiry
     *  (the row sticks while its ability is on the stack and prunes when the
     *  ability iid is no longer present). Includes both card spells and
     *  Ability gameObjects in zone 27. */
    val stackIids: Set<Int> = emptySet(),
    /** Stack ability iids resolving in this frame. Forge can fire the resolve
     *  event before removing the Ability object from the live stack snapshot;
     *  this set lets lifecycle pruning still delete TriggeringObject in the
     *  resolution GSM. */
    val resolvingStackIids: Set<Int> = emptySet(),
    /** Authoritative set of legal DisplayCardUnderCard affectors for this frame.
     *  Null disables this lifecycle check for unit callers without a full frame. */
    val displayCardAffectors: Set<Int>? = null,
    /** Pending-holder iid to live stack-ability iid transitions for delayed effects. */
    val delayedTriggerAffectorReplacements: Map<Int, Int> = emptyMap(),
) {
    companion object {
        /** No-op context — phase=null, empty battlefield, empty stack, empty
         *  controller map. No [PersistentAnnotationKind.shouldExpire] row fires
         *  under it (EZTT gates on phase==UPKEEP, ColorProduction on
         *  iid-not-in-BF, TriggeringObject on iid-not-in-Stack — all of which
         *  are true here, but only matter when their rows exist in active).
         *  Used by legacy tests that don't exercise lifecycle expiry. */
        val INERT: FrameContext =
            FrameContext(
                phase = null,
                activePlayerSeat = SeatId(1),
                battlefieldIids = emptySet(),
                controllerOf = emptyMap(),
                stackIids = emptySet(),
            )
    }
}

/**
 * One persistent-annotation kind. Encapsulates everything
 * [PersistentAnnotationStore.computeBatch] needs to know about a kind:
 *
 *  - [matches] — does an existing active row belong to this kind?
 *  - [identityKey] — dedup key for upsert dispatch (null for kinds whose
 *    rows aren't upserted, e.g. EZTT which arrives via the transfer-originated
 *    pipeline and is removed by [shouldExpire]).
 *  - [pruneStale] — when true, active rows of this kind whose identity isn't
 *    in this frame's incoming set get pruned (full-replacement upsert).
 *  - [collisionStrategy] — how to handle an incoming row whose identity
 *    collides with an existing row.
 *  - [shouldExpire] — turn-boundary / off-zone expiry rule. EZTT fires at
 *    Upkeep; ColorProduction fires when the source iid leaves the
 *    battlefield. Returns false by default — most kinds don't auto-expire.
 *
 * Replaces the per-kind `upsertX(...)` parallel branches in [PersistentAnnotationStore].
 * Adding a new kind is now a row in [PersistentAnnotationKinds.all].
 */
sealed interface PersistentAnnotationKind {
    val name: String
    val pruneStale: Boolean
    val collisionStrategy: CollisionStrategy

    fun matches(ann: AnnotationInfo): Boolean

    fun identityKey(ann: AnnotationInfo): Any?

    /** Whether an updated row keeps its persistent id instead of delete-and-recreate. */
    fun preserveIdOnChange(ann: AnnotationInfo): Boolean = false

    fun shouldExpire(
        ann: AnnotationInfo,
        frame: FrameContext,
    ): Boolean = false
}

/** Strategy for upsert collisions where an incoming row's identity matches an
 *  existing active row's identity. */
enum class CollisionStrategy {
    /** Active row stays — incoming row is dropped. Used by ModifiedType-for-crew,
     *  TemporaryPermanent, and DelayedTriggerAffectees. */
    KEEP_EXISTING,

    /** Active row gets replaced when its detail list differs from the incoming row's.
     *  AbilityWordActive (value updates), CrewedThisTurn (which vehicles this turn),
     *  Designation kinds (PreparedCopyZcid swaps), and TargetSpec (target or
     *  distribution changes within one group). */
    REPLACE_IF_CHANGED,

    /** Active row always gets replaced — fresh id every collision. Counter (every counter
     *  add allocates a new id even if the value is unchanged). */
    REPLACE_ALWAYS,
}

private fun designationTypeOf(ann: AnnotationInfo): Int? =
    ann.detailsList
        .firstOrNull { it.key == DetailKeys.DESIGNATION_TYPE && it.valueInt32Count > 0 }
        ?.getValueInt32(0)

private fun firstAffectedId(ann: AnnotationInfo): Int = ann.affectedIdsList.firstOrNull() ?: 0

private fun int32Detail(
    ann: AnnotationInfo,
    key: String,
): Int? =
    ann.detailsList
        .firstOrNull { it.key == key && it.valueInt32Count > 0 }
        ?.getValueInt32(0)

private fun numericDetail(
    ann: AnnotationInfo,
    key: String,
): Int? =
    ann.detailsList
        .firstOrNull { it.key == key && (it.valueInt32Count > 0 || it.valueUint32Count > 0) }
        ?.let { detail ->
            when {
                detail.valueInt32Count > 0 -> detail.getValueInt32(0)
                detail.valueUint32Count > 0 -> detail.getValueUint32(0)
                else -> null
            }
        }

private fun stringDetail(
    ann: AnnotationInfo,
    key: String,
): String? =
    ann.detailsList
        .firstOrNull { it.key == key && it.valueStringCount > 0 }
        ?.getValueString(0)

data object CounterKind : PersistentAnnotationKind {
    override val name = "Counter"
    override val pruneStale = false
    override val collisionStrategy = CollisionStrategy.REPLACE_ALWAYS

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.Counter_803b in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any? {
        val iid = ann.affectedIdsList.firstOrNull() ?: return null
        val ctype = int32Detail(ann, DetailKeys.COUNTER_TYPE) ?: return null
        return iid to ctype
    }
}

data object AbilityWordActiveKind : PersistentAnnotationKind {
    override val name = "AbilityWordActive"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.AbilityWordActive in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any {
        val name = stringDetail(ann, DetailKeys.ABILITY_WORD_NAME).orEmpty()
        return if (name == "Opus" || name == "Void") {
            listOf(ann.affectorId, name)
        } else {
            listOf(
                ann.affectorId,
                firstAffectedId(ann),
                name,
                numericDetail(ann, DetailKeys.ABILITY_GRP_ID_UPPER) ?: 0,
            )
        }
    }

    override fun preserveIdOnChange(ann: AnnotationInfo): Boolean =
        stringDetail(ann, DetailKeys.ABILITY_WORD_NAME) in setOf("Opus", "Void", "ToSolveCondition")
}

data object QualificationKind : PersistentAnnotationKind {
    override val name = "Qualification"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.Qualification in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any =
        listOf(
            ann.affectorId,
            ann.affectedIdsList,
            numericDetail(ann, DetailKeys.GRPID) ?: 0,
            numericDetail(ann, DetailKeys.QUALIFICATION_TYPE) ?: 0,
            numericDetail(ann, DetailKeys.SOURCE_PARENT) ?: 0,
        )
}

data object CrewedThisTurnKind : PersistentAnnotationKind {
    override val name = "CrewedThisTurn"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.CrewedThisTurn in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any = ann.affectorId
}

data object SaddledThisTurnKind : PersistentAnnotationKind {
    override val name = "SaddledThisTurn"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.SaddledThisTurn in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any = ann.affectorId
}

data object ModifiedTypeForCrewKind : PersistentAnnotationKind {
    override val name = "ModifiedTypeForCrew"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.KEEP_EXISTING

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.ModifiedType in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object TemporaryPermanentKind : PersistentAnnotationKind {
    override val name = "TemporaryPermanent"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.KEEP_EXISTING

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.TemporaryPermanent in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any {
        val firstAffected = ann.affectedIdsList.firstOrNull()
        return firstAffected ?: ann.affectorId
    }
}

data object DelayedTriggerAffecteesKind : PersistentAnnotationKind {
    override val name = "DelayedTriggerAffectees"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.KEEP_EXISTING

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.DelayedTriggerAffectees in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any = ann.affectorId
}

data object TargetSpecKind : PersistentAnnotationKind {
    override val name = "TargetSpec"
    override val pruneStale = false
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.TargetSpec in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any = ann.affectorId to (int32Detail(ann, DetailKeys.INDEX) ?: 0)

    override fun preserveIdOnChange(ann: AnnotationInfo): Boolean = true

    override fun shouldExpire(
        ann: AnnotationInfo,
        frame: FrameContext,
    ): Boolean =
        frame.phase != null &&
            (ann.affectorId !in frame.stackIids || ann.affectorId in frame.resolvingStackIids)
}

data object MutateLayeredEffectKind : PersistentAnnotationKind {
    override val name = "MutateLayeredEffect"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.LayeredEffect in ann.typeList &&
            int32Detail(ann, DetailKeys.ABILITY_GRP_ID) == leyline.game.data.KeywordAbilityIds.MUTATE

    override fun identityKey(ann: AnnotationInfo): Any = ann.affectorId to firstAffectedId(ann)
}

data object PreparedDesignationKind : PersistentAnnotationKind {
    override val name = "PreparedDesignation"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.Designation in ann.typeList &&
            designationTypeOf(ann) == AnnotationConstants.DESIGNATION_TYPE_PREPARED

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object PlottedDesignationKind : PersistentAnnotationKind {
    override val name = "PlottedDesignation"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.Designation in ann.typeList &&
            designationTypeOf(ann) == AnnotationConstants.DESIGNATION_TYPE_PLOTTED

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object CommanderDesignationKind : PersistentAnnotationKind {
    override val name = "CommanderDesignation"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.Designation in ann.typeList &&
            designationTypeOf(ann) == AnnotationConstants.DESIGNATION_TYPE_COMMANDER

    override fun identityKey(ann: AnnotationInfo): Any {
        val affected = firstAffectedId(ann)
        val grpId = int32Detail(ann, DetailKeys.GRPID) ?: 0
        return if (ann.affectorId in 1..2 && affected == ann.affectorId) {
            "player" to (ann.affectorId to grpId)
        } else {
            "object" to affected
        }
    }
}

data object SaddledDesignationKind : PersistentAnnotationKind {
    override val name = "SaddledDesignation"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.Designation in ann.typeList &&
            designationTypeOf(ann) == AnnotationConstants.DESIGNATION_TYPE_SADDLED

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object SuspectedDesignationKind : PersistentAnnotationKind {
    override val name = "SuspectedDesignation"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.Designation in ann.typeList &&
            designationTypeOf(ann) == AnnotationConstants.DESIGNATION_TYPE_SUSPECTED

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object SolvedDesignationKind : PersistentAnnotationKind {
    override val name = "SolvedDesignation"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.Designation in ann.typeList &&
            designationTypeOf(ann) == AnnotationConstants.DESIGNATION_TYPE_SOLVED

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object LeftUnlockedDesignationKind : PersistentAnnotationKind {
    override val name = "LeftUnlockedDesignation"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.Designation in ann.typeList &&
            designationTypeOf(ann) == AnnotationConstants.DESIGNATION_TYPE_LEFT_UNLOCKED

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object RightUnlockedDesignationKind : PersistentAnnotationKind {
    override val name = "RightUnlockedDesignation"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.Designation in ann.typeList &&
            designationTypeOf(ann) == AnnotationConstants.DESIGNATION_TYPE_RIGHT_UNLOCKED

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object ManaCreatureDesignationKind : PersistentAnnotationKind {
    override val name = "ManaCreatureDesignation"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.Designation in ann.typeList &&
            designationTypeOf(ann) == AnnotationConstants.DESIGNATION_TYPE_MANA_CREATURE

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

/** Persistent player-owned speed state. */
data object PlayerSpeedDesignationKind : PersistentAnnotationKind {
    override val name = "PlayerSpeedDesignation"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.Designation in ann.typeList &&
            designationTypeOf(ann) == AnnotationConstants.DESIGNATION_TYPE_PLAYER_SPEED

    override fun identityKey(ann: AnnotationInfo): Any = ann.affectorId

    override fun preserveIdOnChange(ann: AnnotationInfo): Boolean = true
}

/**
 * Persistent `FaceDown` annotation for face-down disguise creatures on the
 * battlefield. Carries `REASON=6` (Disguise) + `abilityGrpId=307`
 * (Disguise BaseId) detail keys. Lives across the card's entire face-down
 * residence on the battlefield; pruned when the card flips face-up
 * (`Special_TurnFaceUp_add3`) or leaves the battlefield.
 */
data object FaceDownDisguiseKind : PersistentAnnotationKind {
    override val name = "FaceDownDisguise"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean {
        if (AnnotationType.FaceDown !in ann.typeList) return false
        val reason =
            ann.detailsList
                .firstOrNull { it.key == leyline.game.codes.DetailKeys.REASON_UPPER }
                ?.valueInt32List
                ?.firstOrNull() ?: return false
        return reason == AnnotationConstants.FACEDOWN_REASON_DISGUISE
    }

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

/** Persistent `FaceDown` annotation for a foretold card in exile. */
data object FaceDownForetellKind : PersistentAnnotationKind {
    override val name = "FaceDownForetell"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean {
        if (AnnotationType.FaceDown !in ann.typeList) return false
        val reason =
            ann.detailsList
                .firstOrNull { it.key == leyline.game.codes.DetailKeys.REASON_UPPER }
                ?.valueInt32List
                ?.firstOrNull() ?: return false
        return reason == AnnotationConstants.FACEDOWN_REASON_FORETELL
    }

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object FaceDownCloakKind : PersistentAnnotationKind {
    override val name = "FaceDownCloak"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean {
        if (AnnotationType.FaceDown !in ann.typeList) return false
        val reason =
            ann.detailsList
                .firstOrNull { it.key == leyline.game.codes.DetailKeys.REASON_UPPER }
                ?.valueInt32List
                ?.firstOrNull() ?: return false
        return reason == AnnotationConstants.FACEDOWN_REASON_CLOAK
    }

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object FaceDownManifestDreadKind : PersistentAnnotationKind {
    override val name = "FaceDownManifestDread"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean {
        if (AnnotationType.FaceDown !in ann.typeList) return false
        val reason =
            ann.detailsList
                .firstOrNull { it.key == leyline.game.codes.DetailKeys.REASON_UPPER }
                ?.valueInt32List
                ?.firstOrNull() ?: return false
        return reason == AnnotationConstants.FACEDOWN_REASON_MANIFEST_DREAD
    }

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

/**
 * Game-scope Day/Night state designation. Single row per game — Day and Night
 * are mutually exclusive, and the row carries a running `ActivePlayerSpellCount`
 * tally that updates each GSM. At a flip, the outgoing state's row is pruned
 * (lands in `diffDeletedPersistentAnnotationIds`) and the incoming state's row
 * gets a fresh persistent id.
 *
 * Identity key collapses both 10 and 11 to a constant so the row replaces in
 * place across a flip — APSC ticks become detail-list updates under
 * REPLACE_IF_CHANGED, and a Day↔Night flip looks like a designation-type
 * change on the same identity.
 */
data object DayNightDesignationKind : PersistentAnnotationKind {
    override val name = "DayNightDesignation"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.Designation in ann.typeList &&
            firstAffectedId(ann) == 0 &&
            designationTypeOf(ann).let {
                it == AnnotationConstants.DESIGNATION_TYPE_DAY ||
                    it == AnnotationConstants.DESIGNATION_TYPE_NIGHT
            }

    override fun identityKey(ann: AnnotationInfo): Any = "DayNight"
}

/**
 * Pure-snapshot persistent annotation: card-entered-zone-this-turn marker.
 * Does NOT participate in upsert dispatch — rows arrive via the transfer-
 * originated pipeline, one EZTT per zone-transfer destination.
 *
 * MTG rule: "entered this turn" markers expire at the start of the
 * controller's next turn. Our hook is the Upkeep step plus a controller
 * match — `controllerOf[iid] == activePlayerSeat` at `phase == UPKEEP`.
 * Cards no longer present in `frame.controllerOf` (already off-objects)
 * expire on any Upkeep so stale rows don't pin in the persistent set.
 */
data object EnteredZoneThisTurnKind : PersistentAnnotationKind {
    override val name = "EnteredZoneThisTurn"
    override val pruneStale = false
    override val collisionStrategy = CollisionStrategy.KEEP_EXISTING

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.EnteredZoneThisTurn in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any? = null

    override fun shouldExpire(
        ann: AnnotationInfo,
        frame: FrameContext,
    ): Boolean {
        if (ann.affectorId == ZoneIds.STACK && ann.affectedIdsList.any { it in frame.resolvingStackIids }) {
            return true
        }
        val phase = frame.phase ?: return false
        if (phase != PhaseType.UPKEEP) return false
        val affected = ann.affectedIdsList.firstOrNull() ?: return false
        val controller = frame.controllerOf[affected]
        // Known controller: gate on activePlayer == controller. Unknown
        // controller (card already off-objects): expire on any Upkeep — the
        // protocol doesn't care about a marker on an iid the client no
        // longer tracks, and leaving it pinned grows the persistent set.
        return controller == null || controller == frame.activePlayerSeat
    }
}

data object EtbReplacementEffectKind : PersistentAnnotationKind {
    override val name = "EtbReplacementEffect"
    override val pruneStale = false
    override val collisionStrategy = CollisionStrategy.KEEP_EXISTING

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.forNumber(62) in ann.typeList &&
            int32Detail(ann, DetailKeys.REPLACEMENT_SOURCE_ZCID) != null

    override fun identityKey(ann: AnnotationInfo): Any? = null

    override fun shouldExpire(
        ann: AnnotationInfo,
        frame: FrameContext,
    ): Boolean = ann.affectedIdsList.any { it in frame.battlefieldIids }
}

/**
 * Class level marker. Upserted from each Class on the battlefield, and removed
 * when it leaves. The client rebuilds card state from every message and does
 * not carry the level forward, so it has to stay persistent.
 */
data object ClassLevelKind : PersistentAnnotationKind {
    override val name = "ClassLevel"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.ClassLevel in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any? = ann.affectedIdsList.firstOrNull()

    override fun shouldExpire(
        ann: AnnotationInfo,
        frame: FrameContext,
    ): Boolean {
        val sourceIid = ann.affectedIdsList.firstOrNull() ?: return false
        return sourceIid !in frame.battlefieldIids
    }
}

/**
 * Source color-production marker. Upserted from the current battlefield mana
 * sources, and removed when the source leaves or stops producing mana.
 */
data object ColorProductionKind : PersistentAnnotationKind {
    override val name = "ColorProduction"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.ColorProduction in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any? = ann.affectedIdsList.firstOrNull()

    override fun shouldExpire(
        ann: AnnotationInfo,
        frame: FrameContext,
    ): Boolean {
        val sourceIid = ann.affectedIdsList.firstOrNull() ?: return false
        return sourceIid !in frame.battlefieldIids
    }
}

data object LinkInfoChoiceKind : PersistentAnnotationKind {
    override val name = "LinkInfoChoice"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.LinkInfo in ann.typeList &&
            int32Detail(ann, DetailKeys.LINK_TYPE) == 3

    override fun identityKey(ann: AnnotationInfo): Any = ann.affectorId to stringDetail(ann, DetailKeys.CHOOSE_LINK_TYPE).orEmpty()
}

data object LinkInfoStackAbilityKind : PersistentAnnotationKind {
    override val name = "LinkInfoStackAbility"
    override val pruneStale = false
    override val collisionStrategy = CollisionStrategy.KEEP_EXISTING

    override fun matches(ann: AnnotationInfo): Boolean =
        AnnotationType.LinkInfo in ann.typeList &&
            int32Detail(ann, DetailKeys.LINK_TYPE) == 2

    override fun identityKey(ann: AnnotationInfo): Any? = null

    override fun shouldExpire(
        ann: AnnotationInfo,
        frame: FrameContext,
    ): Boolean = ann.affectorId !in frame.stackIids || ann.affectorId in frame.resolvingStackIids
}

data object ManaDetailsKind : PersistentAnnotationKind {
    override val name = "ManaDetails"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.ManaDetails in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any = ann.affectorId to firstAffectedId(ann)
}

data object AbilityExhaustedKind : PersistentAnnotationKind {
    override val name = "AbilityExhausted"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.AbilityExhausted in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann) to (int32Detail(ann, DetailKeys.ABILITY_GRP_ID_UPPER) ?: 0)

    override fun shouldExpire(
        ann: AnnotationInfo,
        frame: FrameContext,
    ): Boolean {
        val sourceIid = firstAffectedId(ann)
        return sourceIid != 0 && sourceIid !in frame.controllerOf
    }
}

/**
 * Pure-snapshot persistent annotation: the "trigger ↔ source" link drawn by
 * the client as a glowing arrow. One row per ability instance on the stack,
 * carrying the source permanent (or stack object, for cascade/copy) the
 * ability triggered from.
 *
 * Lifecycle: emitted once when the ability appears on the stack (snap-diff
 * or event-driven path), expires when the ability iid is no longer in the
 * stack zone — same hook the client uses to drop the rendered arrow.
 */
data object TriggeringObjectKind : PersistentAnnotationKind {
    override val name = "TriggeringObject"
    override val pruneStale = false
    override val collisionStrategy = CollisionStrategy.KEEP_EXISTING

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.TriggeringObject in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any? = null

    override fun shouldExpire(
        ann: AnnotationInfo,
        frame: FrameContext,
    ): Boolean = ann.affectorId !in frame.stackIids || ann.affectorId in frame.resolvingStackIids
}

data object DisplayCardUnderCardKind : PersistentAnnotationKind {
    override val name = "DisplayCardUnderCard"
    override val pruneStale = false
    override val collisionStrategy = CollisionStrategy.KEEP_EXISTING

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.DisplayCardUnderCard in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any? = null

    override fun shouldExpire(
        ann: AnnotationInfo,
        frame: FrameContext,
    ): Boolean = frame.displayCardAffectors?.let { ann.affectorId !in it } ?: false
}

data object CardRevealedKind : PersistentAnnotationKind {
    override val name = "CardRevealed"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.CardRevealed in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object InstanceRevealedToOpponentKind : PersistentAnnotationKind {
    override val name = "InstanceRevealedToOpponent"
    override val pruneStale = true
    override val collisionStrategy = CollisionStrategy.KEEP_EXISTING

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.InstanceRevealedToOpponent in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any = firstAffectedId(ann)
}

data object CastingTimeOptionKind : PersistentAnnotationKind {
    override val name = "CastingTimeOption"
    override val pruneStale = false
    override val collisionStrategy = CollisionStrategy.REPLACE_IF_CHANGED

    override fun matches(ann: AnnotationInfo): Boolean = AnnotationType.CastingTimeOption in ann.typeList

    override fun identityKey(ann: AnnotationInfo): Any = ann.affectorId

    override fun shouldExpire(
        ann: AnnotationInfo,
        frame: FrameContext,
    ): Boolean = ann.affectorId !in frame.stackIids || ann.affectorId in frame.resolvingStackIids
}

object PersistentAnnotationKinds {
    /**
     * Upsert-path kinds — rows are identity-keyed, dispatched by
     * [PersistentAnnotationStore.computeBatch]'s mechanic upsert pass. Order
     * matches the legacy step-3 ordering so frame-local id allocation stays
     * stable across the migration.
     */
    val upsertable: List<PersistentAnnotationKind> =
        listOf(
            CounterKind,
            AbilityWordActiveKind,
            QualificationKind,
            CrewedThisTurnKind,
            SaddledThisTurnKind,
            ModifiedTypeForCrewKind,
            TemporaryPermanentKind,
            DelayedTriggerAffecteesKind,
            CardRevealedKind,
            InstanceRevealedToOpponentKind,
            TargetSpecKind,
            MutateLayeredEffectKind,
            ColorProductionKind,
            ClassLevelKind,
            LinkInfoChoiceKind,
            ManaDetailsKind,
            AbilityExhaustedKind,
            PreparedDesignationKind,
            PlottedDesignationKind,
            CommanderDesignationKind,
            SaddledDesignationKind,
            SuspectedDesignationKind,
            SolvedDesignationKind,
            LeftUnlockedDesignationKind,
            RightUnlockedDesignationKind,
            ManaCreatureDesignationKind,
            PlayerSpeedDesignationKind,
            DayNightDesignationKind,
            FaceDownForetellKind,
            FaceDownDisguiseKind,
            FaceDownCloakKind,
            FaceDownManifestDreadKind,
        )

    /** Lifecycle-only kinds — pass through pure-append in the transfer pipeline,
     *  removed only when [PersistentAnnotationKind.shouldExpire] fires. */
    val lifecycleOnly: List<PersistentAnnotationKind> =
        listOf(
            EnteredZoneThisTurnKind,
            EtbReplacementEffectKind,
            CastingTimeOptionKind,
            TriggeringObjectKind,
            LinkInfoStackAbilityKind,
            DisplayCardUnderCardKind,
        )

    /** All kinds — iterated by the lifecycle expiry pass at the top of computeBatch. */
    val all: List<PersistentAnnotationKind> = upsertable + lifecycleOnly
}
