package leyline.bridge.handoff

import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.ForgePlayerId
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

/** Stable protocol selectors; excludes client-populated payment and auto-tap detail. */
data class ActionResponseKey(
    val type: ActionType,
    val instanceId: Int,
    val grpId: Int,
    val abilityGrpId: Int,
    val alternativeGrpId: Int,
) {
    companion object {
        @Suppress("ElseCaseInsteadOfExhaustiveWhen")
        fun from(action: Action) =
            when (action.actionType) {
                ActionType.Pass, ActionType.FloatMana -> ActionResponseKey(ActionType.Pass, 0, 0, 0, 0)
                ActionType.Play_add3, ActionType.PlayMdfc, ActionType.SpecialTurnFaceUp_add3 ->
                    ActionResponseKey(action.actionType, action.instanceId, 0, 0, 0)
                else ->
                    ActionResponseKey(
                        action.actionType,
                        action.instanceId,
                        action.grpId,
                        action.abilityGrpId,
                        action.alternativeGrpId,
                    )
            }
    }
}

/** Describes the game context when the engine is waiting for a player action. */
data class PendingActionState(
    val phase: String,
    val turn: Int,
    val activePlayerId: Int,
    val priorityPlayerId: Int,
    val kind: PendingActionKind = PendingActionKind.PRIORITY,
    /** Frozen engine policy for the priority point following this synchronization barrier. */
    val synchronizationContinuation: SynchronizationContinuation = SynchronizationContinuation.Reevaluate,
    val synchronizationPresentation: SynchronizationPresentation = SynchronizationPresentation.StateOnly,
)

enum class SynchronizationContinuation { Reevaluate, RequireVisible, AllowSyncOnly }

enum class SynchronizationPresentation { StateOnly, PhaseTransition }

enum class RuntimeHorizonMode { Direct, Observed }

enum class PendingActionKind {
    PRIORITY,
    SYNC_ONLY,
    DECLARE_ATTACKERS,
    DECLARE_BLOCKERS,
}

/** A game entity that can be targeted: card or player. */
sealed class Target {
    data class Card(
        val cardId: ForgeCardId,
    ) : Target()

    data class Player(
        val playerId: ForgePlayerId,
    ) : Target()
}

/** Actions a player can take when they have priority. */
sealed class PlayerAction {
    data object PassPriority : PlayerAction()

    data class CastSpell(
        val cardId: ForgeCardId,
        val abilityId: Int? = null,
        val targets: List<Target> = emptyList(),
        val ability: SpellAbility? = null,
    ) : PlayerAction()

    data class ActivateAbility(
        val cardId: ForgeCardId,
        val abilityId: Int,
        val targets: List<Target> = emptyList(),
        val ability: SpellAbility? = null,
    ) : PlayerAction()

    data class ActivateMana(
        val cardId: ForgeCardId,
        val abilityId: Int? = null,
        val selectedColor: Byte? = null,
        val ability: SpellAbility? = null,
    ) : PlayerAction()

    data class PlayLand(
        val cardId: ForgeCardId,
        val ability: LandAbility? = null,
    ) : PlayerAction()

    data class DeclareAttackers(
        val attackerIds: List<ForgeCardId>,
        val attackAlternativeByAttacker: Map<ForgeCardId, Int> = emptyMap(),
        val defender: Target? = null,
        val defenderByAttacker: Map<ForgeCardId, Target> = emptyMap(),
    ) : PlayerAction()

    data class DeclareBlockers(
        val blockAssignments: Map<ForgeCardId, ForgeCardId>,
    ) : PlayerAction()

    /** Auto-pass all remaining priority in this turn (matches desktop "End Turn" button). */
    data object EndTurn : PlayerAction()
}
