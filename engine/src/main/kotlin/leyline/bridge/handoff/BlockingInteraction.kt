package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.CardMechanicType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import kotlin.ConsistentCopyVisibility

/** Immutable engine-thread request presented before a blocking interaction waits. */
sealed interface BlockingInteraction {
    /** The adjusted, chosen cost, after optional costs, modes and X have been decided. */
    data class ManaPayment(
        val sourceId: ForgeCardId,
        val manaCost: List<Pair<ManaColor, Int>>,
        val canAutoPay: Boolean,
        val canUndo: Boolean = false,
    ) : BlockingInteraction

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
        /** Cards displayed alongside [sourceId] by mechanic-specific workflows (e.g. Mutate). */
        val recipientIds: List<ForgeCardId> = emptyList(),
        /** Client mana-cost text (`o1`, `oUoB`, ...) shown as "Pay {cost}." instead of the
         *  generic "Choose options." prompt. Null keeps the generic prompt. */
        val costText: String? = null,
    ) : BlockingInteraction

    /**
     * Choose whether a card is put on top of or bottom of its owner's library.
     *
     * This is deliberately not [Optional]: Arena selects a Scryish workflow
     * from [CardMechanicType.PutTopOrBottom] and uses the recipient card to
     * render the affected object.  The response remains the GRE optional
     * boolean (yes = top, no = bottom), but the prompt's identity and
     * workflow are no longer lost in the generic optional-action rail.
     */
    data class TopOrBottom(
        val sourceId: ForgeCardId,
        val recipientId: ForgeCardId,
    ) : BlockingInteraction

    data class FreeCast(
        val cardGrpId: Int,
        val abilityGrpId: Int,
        val sourceAbilityForgeId: Int,
        val alternativeSourceForgeCardId: ForgeCardId,
    )

    /** A Play effect's exact remaining castable cards during resolution. */
    data class ResolutionCast(
        val sourceId: ForgeCardId,
        val sourceAbilityForgeId: Int?,
        val candidateIds: List<ForgeCardId>,
        val optional: Boolean,
        val withoutManaCost: Boolean,
        val promptId: Int,
    ) : BlockingInteraction

    data class Numeric(
        val sourceId: ForgeCardId?,
        val min: Int,
        val max: Int,
        val defaultValue: Int,
        /**
         * The generic X picker is a different client workflow from keyword
         * repeat-count pickers. Arena carries the latter in a typed
         * CastingTimeOptionsReq with a numeric child request.
         */
        val presentation: NumericPresentation = NumericPresentation.Generic,
    ) : BlockingInteraction

    enum class NumericPresentation {
        Generic(null),
        Replicate(CastingTimeOptionType.Replicate),
        Multikicker(CastingTimeOptionType.Multikicker),
        ;

        val castingTimeOptionType: CastingTimeOptionType?

        constructor(castingTimeOptionType: CastingTimeOptionType?) {
            this.castingTimeOptionType = castingTimeOptionType
        }
    }

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

/** A payment window returns a command, not a partially mutated Forge payment. */
sealed interface ManaPaymentDecision {
    data class Sources(
        val actions: List<PlayerAction.ActivateMana>,
    ) : ManaPaymentDecision

    data object AutoPay : ManaPaymentDecision

    data object Undo : ManaPaymentDecision

    data object Cancel : ManaPaymentDecision
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
