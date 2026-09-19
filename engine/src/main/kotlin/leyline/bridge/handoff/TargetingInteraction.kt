package leyline.bridge.handoff

import forge.game.spellability.SpellAbility
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.bridge.types.SeatId

/** Immutable targeting candidate handed to projection materialization. */
sealed interface TargetingCandidateValue {
    val optionIndex: Int

    data class Card(
        override val optionIndex: Int,
        val forgeCardId: ForgeCardId,
        val targetZone: TargetingZone,
        /** True when this candidate already has a Role-type Aura attached — the
         *  client highlights it distinctly (`HighlightType.ReplaceRole`) since
         *  targeting it will replace the existing Role (CR 702.166). */
        val hasExistingRole: Boolean = false,
    ) : TargetingCandidateValue

    data class Player(
        override val optionIndex: Int,
        val seatId: SeatId,
    ) : TargetingCandidateValue

    /** Exact stack object identity selected by Forge's stack-target callback. */
    data class StackObject(
        override val optionIndex: Int,
        val stackInstanceId: Int,
        val sourceForgeCardId: ForgeCardId,
        val forgeAbilityId: Int,
        val isSpell: Boolean,
        val isAbility: Boolean,
        val isTrigger: Boolean,
        val abilityIdentity: ResolvedAbilityIdentity? = null,
    ) : TargetingCandidateValue
}

/** Forge-zone shape of a card target, kept protocol-neutral until materialization. */
enum class TargetingZone {
    Battlefield,
    Exile,
    Stack,
    Graveyard,
    Hand,
    Library,
    Unknown,
}

/** Projection-ready value for one route-bound SelectTargets window. */
data class TargetingWindowValue(
    val sourceForgeCardId: ForgeCardId?,
    val sourceGrpId: Int,
    val outerAbilityGrpId: Int,
    val targetingAbilityGrpId: Int,
    val targetSourceZoneId: Int,
    val targetPromptId: Int?,
    val targetIndex: Int,
    val minTargets: Int,
    val maxTargets: Int,
    val chooserSeatId: SeatId,
    val candidates: List<TargetingCandidateValue>,
    /** Original callback option index for an optional finish-targeting sentinel. */
    val finishOptionIndex: Int? = null,
    val isTriggeredAbility: Boolean,
    val forgeAbilityId: Int,
    val isActivatedAbility: Boolean = false,
    val stackAbilityGrpId: Int = 0,
    /** True when the targeting ability is Forge's `ApiType.Counter` ("counter
     *  target spell/ability") — the client renders a distinct highlight color
     *  (`HighlightType.Counterspell`) for stack targets in that case. */
    val isCounterspell: Boolean = false,
) {
    init {
        require(targetIndex > 0)
        require(minTargets >= 0)
        require(maxTargets >= minTargets)
    }
}

/** One immutable client tap. `selected=false` is the wire Unselect action. */
data class TargetToggleValue(
    val instanceId: Int,
    val selected: Boolean,
)

/** Session-visible identity of the latest committed targeting request. */
enum class TargetingInteractionKind {
    Targeting,
    CompatibilityCostSelection,
}

data class PublishedTargetingInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val targetIndex: Int,
    val kind: TargetingInteractionKind = TargetingInteractionKind.Targeting,
)

/** Result of one command accepted by the targeting interaction owner. */
data class TargetingCommandReceipt(
    val interactionId: String,
    val deliveryToken: Long?,
    val completed: Boolean,
    val engineWillResume: Boolean,
)

/** Engine-thread owner for route-bound targeting prompts. */
interface TargetingInteractionRuntime {
    fun awaitTargeting(
        request: PromptRequest,
        targetingAbility: SpellAbility?,
        abilityIdentity: ResolvedAbilityIdentity?,
        timeoutMs: Long?,
    ): List<Int>
}

/** Internal control result: the exact targeting deadline retired the window before any response won. */
internal class TargetingInteractionTimeoutException : RuntimeException("Targeting interaction timed out")
