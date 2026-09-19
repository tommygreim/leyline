package leyline.game.mapping

import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.snapshot.BoundCard
import java.util.Collections

/** Immutable, viewer-specific projection work appended to one state frame. */
class ViewerProjectionIntent private constructor(
    supplements: List<ProjectionSupplement>,
    val privateCardPrompt: PrivateCardPromptProjection?,
    val orderPrompt: OrderPromptProjection?,
) {
    val supplements: List<ProjectionSupplement> = supplements.frozenCopy()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ViewerProjectionIntent &&
            supplements == other.supplements &&
            privateCardPrompt == other.privateCardPrompt &&
            orderPrompt == other.orderPrompt

    override fun hashCode(): Int =
        31 * (31 * supplements.hashCode() + (privateCardPrompt?.hashCode() ?: 0)) + (orderPrompt?.hashCode() ?: 0)

    companion object {
        val EMPTY = ViewerProjectionIntent(emptyList(), null, null)

        fun of(
            supplements: List<ProjectionSupplement> = emptyList(),
            privateCardPrompt: PrivateCardPromptProjection? = null,
            orderPrompt: OrderPromptProjection? = null,
        ): ViewerProjectionIntent = ViewerProjectionIntent(supplements, privateCardPrompt, orderPrompt)
    }
}

/** Private card exposure and source identity required by one hidden-card prompt. */
class PrivateCardPromptProjection private constructor(
    candidateForgeIds: List<ForgeCardId>,
    val sourceForgeId: ForgeCardId?,
) {
    val candidateForgeIds: List<ForgeCardId> = candidateForgeIds.frozenCopy()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is PrivateCardPromptProjection &&
            candidateForgeIds == other.candidateForgeIds &&
            sourceForgeId == other.sourceForgeId

    override fun hashCode(): Int = 31 * candidateForgeIds.hashCode() + (sourceForgeId?.hashCode() ?: 0)

    companion object {
        fun of(
            candidateForgeIds: List<ForgeCardId>,
            sourceForgeId: ForgeCardId?,
        ): PrivateCardPromptProjection = PrivateCardPromptProjection(candidateForgeIds, sourceForgeId)
    }
}

/** Ordered annotations and identity reservations that supplement the mapped frame. */
sealed interface ProjectionSupplement {
    data object NewTurnStarted : ProjectionSupplement

    data object PhaseTransition : ProjectionSupplement

    data class PlayerSelectingTargets(
        val sourceForgeId: ForgeCardId,
        val seatId: SeatId,
        val stackAbilityForgeId: Int? = null,
    ) : ProjectionSupplement

    data class ReserveTriggeredAbility(
        val forgeAbilityId: Int,
    ) : ProjectionSupplement

    /** Ability visible before Forge admits it to the stack and retained in the next diff baseline. */
    data class PreStackAbility(
        val forgeAbilityId: Int,
        val sourceForgeCardId: ForgeCardId,
        val abilityGrpId: Int,
        val sourceCardGrpId: Int,
        val ownerSeatId: SeatId,
        val controllerSeatId: SeatId,
        val targetForgeCardIds: List<ForgeCardId>,
        val isActivatedAbility: Boolean = true,
        /** See [leyline.game.snapshot.StackEntry.deferAnnouncement]. */
        val deferAnnouncement: Boolean = false,
    ) : ProjectionSupplement

    /** Copied spell visible on the client stack while Forge is choosing its new targets. */
    data class PreStackSpell(
        val card: BoundCard,
    ) : ProjectionSupplement

    data class SubmitPendingTargets(
        val spellInstanceId: InstanceId,
        val seatId: SeatId,
        val version: Long,
    ) : ProjectionSupplement

    data class StaticParityChoice(
        val sourceForgeId: ForgeCardId,
        val evenForgeIds: List<ForgeCardId>,
        val oddForgeIds: List<ForgeCardId>,
    ) : ProjectionSupplement
}

/** Candidate exposure and optional synthetic state used by one Order prompt. */
class OrderPromptProjection private constructor(
    candidateForgeIds: List<ForgeCardId>,
    val sourceForgeId: ForgeCardId?,
    val move: OrderZoneMoveFact?,
) {
    val candidateForgeIds: List<ForgeCardId> = candidateForgeIds.frozenCopy()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is OrderPromptProjection &&
            candidateForgeIds == other.candidateForgeIds &&
            sourceForgeId == other.sourceForgeId &&
            move == other.move

    override fun hashCode(): Int = 31 * (31 * candidateForgeIds.hashCode() + (sourceForgeId?.hashCode() ?: 0)) + (move?.hashCode() ?: 0)

    companion object {
        fun of(
            candidateForgeIds: List<ForgeCardId>,
            sourceForgeId: ForgeCardId? = null,
            move: OrderZoneMoveFact? = null,
        ): OrderPromptProjection = OrderPromptProjection(candidateForgeIds, sourceForgeId, move)
    }
}

/** Shell observation for a synthetic hand-to-library move owned by one Order window. */
class OrderZoneMoveFact private constructor(
    val seatId: SeatId,
    forgeCardIds: List<ForgeCardId>,
    val putOnTop: Boolean,
) {
    val forgeCardIds: List<ForgeCardId> = forgeCardIds.frozenCopy()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is OrderZoneMoveFact &&
            seatId == other.seatId &&
            forgeCardIds == other.forgeCardIds &&
            putOnTop == other.putOnTop

    override fun hashCode(): Int = 31 * (31 * seatId.hashCode() + forgeCardIds.hashCode()) + putOnTop.hashCode()

    companion object {
        fun of(
            seatId: SeatId,
            forgeCardIds: List<ForgeCardId>,
            putOnTop: Boolean,
        ): OrderZoneMoveFact = OrderZoneMoveFact(seatId, forgeCardIds, putOnTop)
    }
}

private fun <T> List<T>.frozenCopy(): List<T> = Collections.unmodifiableList(ArrayList(this))
