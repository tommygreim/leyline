package leyline.bridge.coord

import forge.game.Game
import forge.game.GameEntity
import forge.game.card.Card
import forge.game.card.CardCollectionView
import forge.game.combat.Combat
import forge.game.combat.CombatUtil
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.PriorityActionCandidates
import leyline.bridge.findCard
import leyline.bridge.handoff.BlockingInteractionRuntime
import leyline.bridge.handoff.DamageAssignmentGate
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.OwnerContext
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.handoff.PendingActionState
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.handoff.RuntimeHorizonMode
import leyline.bridge.handoff.SynchronizationContinuation
import leyline.bridge.handoff.SynchronizationPresentation
import leyline.bridge.resolveAttackDefender
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PriorityDecision
import leyline.game.data.KeywordAbilityIds
import org.slf4j.LoggerFactory
import kotlin.collections.iterator

/**
 * Owns the engine-thread priority loop and the combat callbacks.
 *
 * The four overrides routed here (`chooseSpellAbilityToPlay`,
 * `declareAttackers`, `declareBlockers`, `assignCombatDamage`) share a
 * distinctive timing model: they block the engine thread on [GameActionBridge],
 * not [leyline.bridge.handoff.InteractivePromptBridge], and they drive the
 * main game-loop decision points rather than ad-hoc choices.
 *
 * See `PlayerController`'s KDoc for the coordinator pattern.
 */
class PriorityLoopCoordinator(
    private val owner: OwnerContext,
    private val game: Game,
    private val player: Player,
    private val actionBridge: GameActionBridge,
    private val priorityPolicy: PriorityPolicyRuntime,
    private val runtimeHorizonMode: RuntimeHorizonMode,
    private val smartPhaseSkip: Boolean,
    private val spellExecutor: SpellExecutor,
    interactionRuntime: BlockingInteractionRuntime,
) {
    private val log = LoggerFactory.getLogger(PriorityLoopCoordinator::class.java)
    private val damageAssignmentGate = DamageAssignmentGate(actionBridge, interactionRuntime)
    private var pendingAttackAlternativeByAttacker: Map<ForgeCardId, Int> = emptyMap()

    /**
     * Main priority window entry point. Notify state, block until the client
     * responds, translate the response into a Forge [SpellAbility] (or null
     * for pass). Mana abilities loop without re-passing priority because they
     * do not use the stack.
     */
    fun chooseSpellAbility(): List<SpellAbility>? {
        val handler = game.phaseHandler

        if (actionBridge.autoPassUntilEndOfTurn) {
            actionBridge.setAutoPassUntilEndOfTurn(false)
            priorityPolicy.requestTurnYield(handler.turn)
        }

        val isOwnTurn = handler.playerTurn?.id == player.id

        // Loop so that mana abilities (which don't use the stack) keep priority with the player.
        var forceVisibleAfterMana = false
        while (true) {
            val priorityCandidates = PriorityActionCandidates.query(game, player)
            actionBridge.consumeSynchronizationContinuation()
            val promptJustResolved = actionBridge.prioritySignal?.consumePromptResolved() == true
            val decision =
                priorityPolicy.classifyPriorityWindow(
                    PriorityWindowObservation(
                        isOwnTurn = isOwnTurn,
                        turn = handler.turn,
                        playerId = player.id,
                        stack = game.stack.map { PriorityStackObject(it.id, it.activatingPlayer.id) },
                        phase = handler.phase,
                        smartPhaseSkip = smartPhaseSkip,
                        promptJustResolved = promptJustResolved,
                        stackEmpty = game.stack.isEmpty,
                        forceVisible = forceVisibleAfterMana,
                        hasMeaningfulAction = priorityCandidates.hasLegalNonManaAction(player, isOwnTurn),
                    ),
                )
            forceVisibleAfterMana = false
            val skipped = decision is PriorityWindowDecision.Skip
            if (skipped) {
                priorityPolicy.recordDecision(game, PriorityDecision.Skip(decision.reason))
            }
            var mode =
                when (decision) {
                    is PriorityWindowDecision.Present -> decision.mode
                    is PriorityWindowDecision.Skip -> PriorityWindowMode.SyncOnly
                }
            if (mode == PriorityWindowMode.SyncOnly && runtimeHorizonMode == RuntimeHorizonMode.Direct) {
                if (skipped) return null
                mode = PriorityWindowMode.Visible
            }
            owner.notifyStateChanged()

            val state =
                PendingActionState(
                    phase = handler.phase?.name ?: "UNKNOWN",
                    turn = handler.turn,
                    activePlayerId = handler.playerTurn?.id ?: -1,
                    priorityPlayerId = player.id,
                    kind = if (mode == PriorityWindowMode.SyncOnly) PendingActionKind.SYNC_ONLY else PendingActionKind.PRIORITY,
                    synchronizationPresentation =
                        if (skipped) SynchronizationPresentation.PhaseTransition else SynchronizationPresentation.StateOnly,
                    synchronizationContinuation =
                        if (skipped) {
                            SynchronizationContinuation.Reevaluate
                        } else {
                            synchronizationContinuation(
                                mode = mode,
                                stackEmpty = game.stack.isEmpty,
                                autoResolve = (decision as PriorityWindowDecision.Present).autoResolve,
                            )
                        },
                )
            val action = actionBridge.awaitAction(state, priorityCandidates.takeIf { mode == PriorityWindowMode.Visible })
            actionBridge.armSynchronizationContinuation(state.synchronizationContinuation)
            when (action) {
                is PlayerAction.PassPriority -> {
                    priorityPolicy.actionCompleted(true)
                    return null
                }
                is PlayerAction.EndTurn -> {
                    priorityPolicy.actionCompleted(true)
                    priorityPolicy.requestTurnYield(handler.turn)
                    return null
                }
                is PlayerAction.CastSpell -> return spellExecutor.castSpell(action.cardId, action.abilityId, action.targets, action.ability)
                is PlayerAction.ActivateAbility -> return spellExecutor.activateAbility(
                    action.cardId,
                    action.abilityId,
                    action.targets,
                    action.ability,
                )
                is PlayerAction.ActivateMana -> {
                    val success = spellExecutor.activateMana(action.cardId, action.abilityId, action.selectedColor, action.ability)
                    if (!success) {
                        log.debug("Mana activation failed for card {}", action.cardId.value)
                    }
                    priorityPolicy.actionCompleted(success)
                    forceVisibleAfterMana = true
                    continue
                }
                is PlayerAction.PlayLand -> return spellExecutor.playLand(action.cardId)
                is PlayerAction.DeclareAttackers,
                is PlayerAction.DeclareBlockers,
                -> return null
            }
        }
    }

    internal fun actionCompleted(success: Boolean) = priorityPolicy.actionCompleted(success)

    companion object {
        internal fun synchronizationContinuation(
            mode: PriorityWindowMode,
            stackEmpty: Boolean,
            autoResolve: Boolean,
        ): SynchronizationContinuation =
            when {
                mode != PriorityWindowMode.SyncOnly || stackEmpty -> SynchronizationContinuation.Reevaluate
                autoResolve -> SynchronizationContinuation.AllowSyncOnly
                else -> SynchronizationContinuation.RequireVisible
            }
    }

    fun declareAttackers(
        attacker: Player,
        combat: Combat,
    ) {
        log.info("declareAttackers: waiting for {}", attacker.name)
        owner.notifyStateChanged()
        pendingAttackAlternativeByAttacker = emptyMap()

        val state =
            PendingActionState(
                phase = "COMBAT_DECLARE_ATTACKERS",
                turn = game.phaseHandler.turn,
                activePlayerId = attacker.id,
                priorityPlayerId = attacker.id,
                kind = PendingActionKind.DECLARE_ATTACKERS,
            )
        when (val action = actionBridge.awaitAction(state)) {
            is PlayerAction.DeclareAttackers -> {
                pendingAttackAlternativeByAttacker = action.attackAlternativeByAttacker
                val fallbackDefender = resolveAttackDefender(game, attacker, action.defender)
                for (cardId in action.attackerIds) {
                    val card = findCard(game, cardId) ?: continue
                    if (!CombatUtil.canAttack(card)) continue
                    val defender =
                        action.defenderByAttacker[cardId]?.let { resolveAttackDefender(game, attacker, it) }
                            ?: fallbackDefender
                            ?: combat.defenders.firstOrNull()
                            ?: continue
                    combat.addAttacker(card, defender)
                }
            }
            is PlayerAction.PassPriority -> {}
            else -> {}
        }
    }

    fun enlistAttackers(attackers: List<Card>): List<Card> {
        val selected = pendingAttackAlternativeByAttacker
        if (selected.isEmpty()) return emptyList()
        pendingAttackAlternativeByAttacker = emptyMap()
        return attackers.filter { card -> selected[ForgeCardId(card.id)] == KeywordAbilityIds.ENLIST }
    }

    fun exertAttackers(attackers: List<Card>): List<Card> {
        val selected = pendingAttackAlternativeByAttacker
        if (selected.isEmpty()) return emptyList()
        // Enlist is collected in Forge's next callback, so retain that part of
        // the declaration until it is consumed there.
        pendingAttackAlternativeByAttacker = selected.filterValues { it != KeywordAbilityIds.EXERT }
        return attackers.filter { card -> selected[ForgeCardId(card.id)] == KeywordAbilityIds.EXERT }
    }

    fun declareBlockers(
        defender: Player,
        combat: Combat,
    ) {
        log.info("declareBlockers: waiting for {}", defender.name)
        owner.notifyStateChanged()

        val state =
            PendingActionState(
                phase = "COMBAT_DECLARE_BLOCKERS",
                turn = game.phaseHandler.turn,
                activePlayerId = defender.id,
                priorityPlayerId = defender.id,
                kind = PendingActionKind.DECLARE_BLOCKERS,
            )
        when (val action = actionBridge.awaitAction(state)) {
            is PlayerAction.DeclareBlockers -> {
                for ((blockerCardId, attackerCardId) in action.blockAssignments) {
                    val blocker = findCard(game, blockerCardId) ?: continue
                    val attackerCard = findCard(game, attackerCardId) ?: continue
                    if (combat.isAttacking(attackerCard)) {
                        combat.addBlocker(attackerCard, blocker)
                    }
                }
            }
            is PlayerAction.PassPriority -> {}
            else -> {}
        }
    }

    /**
     * Prompt for manual combat damage distribution and block the engine thread
     * on a dedicated future. [fallback] is invoked when the future times out or
     * errors — the caller is expected to pass the matching `super` call so the
     * engine continues with the default assignment.
     */
    fun promptForCombatDamage(
        attacker: Card,
        blockers: CardCollectionView,
        damageDealt: Int,
        defender: GameEntity?,
        fallback: () -> MutableMap<Card?, Int>?,
    ): MutableMap<Card?, Int>? =
        damageAssignmentGate.await(
            attacker = attacker,
            blockers = blockers,
            damageDealt = damageDealt,
            defender = defender,
            fallback = fallback,
        )
}

internal enum class PriorityWindowMode { Visible, SyncOnly, Skip }
