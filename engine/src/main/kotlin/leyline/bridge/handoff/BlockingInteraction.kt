package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.CardMechanicType
import kotlin.ConsistentCopyVisibility

/** Immutable engine-thread request presented before a blocking interaction waits. */
sealed interface BlockingInteraction {
    data class Optional(
        val sourceId: ForgeCardId?,
        val forceSnapshotBeforePrompt: Boolean,
        val customPromptId: Int?,
        val commanderReturn: CommanderReturnPromptContext?,
        val freeCast: FreeCast? = null,
        val etbPayLifeReplacement: Boolean = false,
        /** Client workflow hint (`OptionalActionMessage.optionalActionTypes`) — e.g.
         *  Explore routes to a dedicated browser only when this is set. Null keeps
         *  the message tag-free, same as before this field existed. */
        val mechanicType: CardMechanicType? = null,
    ) : BlockingInteraction

    data class FreeCast(
        val cardGrpId: Int,
        val abilityGrpId: Int,
        val sourceAbilityForgeId: Int,
        val alternativeSourceForgeCardId: ForgeCardId,
    )

    data class Numeric(
        val sourceId: ForgeCardId?,
        val min: Int,
        val max: Int,
        val defaultValue: Int,
    ) : BlockingInteraction

    @ConsistentCopyVisibility
    data class Damage private constructor(
        val attackerId: ForgeCardId,
        val blockerIds: List<ForgeCardId>,
        val damageDealt: Int,
        val hasDeathtouch: Boolean,
        val hasTrample: Boolean,
        val hasDefender: Boolean,
    ) : BlockingInteraction {
        companion object {
            fun of(
                attackerId: ForgeCardId,
                blockerIds: List<ForgeCardId>,
                damageDealt: Int,
                hasDeathtouch: Boolean,
                hasTrample: Boolean,
                hasDefender: Boolean,
            ): Damage =
                Damage(
                    attackerId,
                    blockerIds.toList(),
                    damageDealt,
                    hasDeathtouch,
                    hasTrample,
                    hasDefender,
                )
        }
    }
}

/** Immutable declaration response values; the coordinator resolves engine actions. */
sealed interface DeclarationAnswer {
    /** Client-domain target identity. Forge resolves it from the published window. */
    sealed interface Target {
        data class Player(
            val seatId: Int,
        ) : Target

        data class Planeswalker(
            val instanceId: Int,
        ) : Target
    }

    @ConsistentCopyVisibility
    data class Attackers internal constructor(
        val attackerInstanceIds: List<Int>,
        val attackAlternativeByAttacker: Map<Int, Int>,
        val defenderByAttacker: Map<Int, Target>,
        val autoDeclare: Boolean,
    ) : DeclarationAnswer {
        companion object {
            fun of(
                attackerInstanceIds: List<Int>,
                attackAlternativeByAttacker: Map<Int, Int> = emptyMap(),
                defenderByAttacker: Map<Int, Target> = emptyMap(),
                autoDeclare: Boolean = false,
            ): Attackers =
                Attackers(
                    attackerInstanceIds.toList(),
                    attackAlternativeByAttacker.toMap(),
                    defenderByAttacker.toMap(),
                    autoDeclare,
                )
        }
    }

    @ConsistentCopyVisibility
    data class Blockers internal constructor(
        val blockAssignments: Map<Int, Int>,
        val touchedBlockerInstanceIds: List<Int>,
    ) : DeclarationAnswer {
        companion object {
            fun of(
                blockAssignments: Map<Int, Int>,
                touchedBlockerInstanceIds: List<Int> = blockAssignments.keys.toList(),
            ): Blockers = Blockers(blockAssignments.toMap(), touchedBlockerInstanceIds.toList())
        }
    }
}

/** Immutable client-domain damage response; the blocking runtime resolves card handles. */
data class DamageAssignmentCommand(
    val attackerInstanceId: Int,
    val assignments: List<DamageAssignmentRow>,
    val totalDamage: Int,
)

/** One raw client row. Duplicate rows remain visible until the runtime validates them. */
data class DamageAssignmentRow(
    val targetInstanceId: Int,
    val assignedDamage: Int,
)
