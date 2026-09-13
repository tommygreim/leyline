package leyline.match

import leyline.DevCheck
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.DamageAssignmentCommand
import leyline.bridge.handoff.DamageAssignmentRow
import leyline.bridge.handoff.DeclarationAnswer
import leyline.bridge.handoff.PendingActionKind
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import kotlin.collections.iterator

/**
 * Handles combat-related client messages.
 *
 * Reads committed prompt horizons from the match runtime.
 */
open class CombatHandler(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    protected val ctx: SessionContext,
) {
    private val log = LoggerFactory.getLogger(CombatHandler::class.java)

    /** Clear all combat state for puzzle hot-swap. */
    fun reset() = Unit

    fun hasPendingAttackers(): Boolean =
        ctx.bridge
            .seat(counters.seatId)
            .action
            .getPending()
            ?.state
            ?.kind == PendingActionKind.DECLARE_ATTACKERS

    /**
     * True if a Submit (finalize) message carries a stale gsId — the client may
     * send on either channel (race), and the echo-back advances the counter, so
     * a Submit from the slower channel arrives with an outdated gsId. Shared by
     * [onDeclareAttackers] and [onDeclareBlockers]; see ActionPerformer.perform
     * for the rationale.
     */
    private fun isStaleSubmit(
        greMsg: ClientToGREMessage,
        label: String,
    ): Boolean {
        val clientGsId = greMsg.gameStateId
        if (clientGsId != 0 && clientGsId < ctx.bridge.committedSequence().lastPromptGsId) {
            log.debug(
                "CombatHandler: stale {} gsId={} (lastPrompt={}), ignoring",
                label,
                clientGsId,
                ctx.bridge.committedSequence().lastPromptGsId,
            )
            return true
        }
        return false
    }

    private fun DamageRecipient.toDeclarationTarget(): DeclarationAnswer.Target? =
        when (type) {
            DamageRecType.Player_a0e5 -> DeclarationAnswer.Target.Player(playerSystemSeatId)
            DamageRecType.PlanesWalker -> DeclarationAnswer.Target.Planeswalker(planeswalkerInstanceId)
            DamageRecType.None_a0e5,
            DamageRecType.Team_a0e5,
            DamageRecType.UNRECOGNIZED,
            -> null
        }

    private fun rejectDeclaration(
        greMsg: ClientToGREMessage,
        actionId: String,
        promptGameStateId: Int?,
    ) {
        if (greMsg.gameStateId != promptGameStateId) return
        ctx.bridge.cutCoordinator.publishIllegalRequest(counters.seatId, greMsg, FailureReason.UnexpectedMessage)
        ctx.bridge.cutCoordinator.republishDeclaration(actionId)
        sink.sendPriorityState(ctx.bridge)
    }

    /**
     * Handle DeclareAttackersResp or SubmitAttackersReq from the client.
     *
     * Arena uses a two-phase combat protocol:
     * - **DeclareAttackersResp** (type=30): iterative update — client sends current
     *   attacker selection on each creature toggle or "Attack All" click.
     *   If `auto_declare=true`, selects all attackers retained by the published runtime window.
     * - **SubmitAttackersReq** (type=31): finalize — client sends empty "Done" signal.
     *   The runtime resolves the retained client-domain selection to the engine action.
     */
    fun onDeclareAttackers(greMsg: ClientToGREMessage): Boolean {
        val bridge = ctx.bridge
        val isSubmit = greMsg.type == ClientMessageType.SubmitAttackersReq

        // Reject stale Submit messages — the client may send on either channel (race).
        // The echo-back advances the counter, so a Submit from the slower channel
        // arrives with an outdated gsId. Only applies to Submit (type-only signal);
        // iterative DeclareAttackersResp always carries fresh data.
        // Compare against lastPromptGsId — see ActionPerformer.perform for the
        // rationale; this branch shares the predicate.
        if (isSubmit && isStaleSubmit(greMsg, "SubmitAttackersReq")) return false

        if (!isSubmit) {
            val resp = greMsg.declareAttackersResp
            log.debug(
                "CombatHandler: DeclareAttackersResp autoDeclare={} selectedCount={} selectedIds={}",
                resp.autoDeclare,
                resp.selectedAttackersCount,
                resp.selectedAttackersList.map { it.attackerInstanceId },
            )
            val pending = bridge.seat(counters.seatId).action.getPending() ?: return false
            if (resp.selectedAttackersList.any {
                    it.hasSelectedDamageRecipient() && it.selectedDamageRecipient.toDeclarationTarget() == null
                }
            ) {
                log.warn("CombatHandler: attacker declaration contains an unsupported damage recipient")
                rejectDeclaration(greMsg, pending.actionId, pending.promptGameStateId)
                return false
            }
            val recipients =
                resp.selectedAttackersList
                    .mapNotNull { attacker ->
                        if (!attacker.hasSelectedDamageRecipient()) return@mapNotNull null
                        val target = attacker.selectedDamageRecipient.toDeclarationTarget() ?: return@mapNotNull null
                        attacker.attackerInstanceId to target
                    }.toMap()
            val answer =
                DeclarationAnswer.Attackers.of(
                    attackerInstanceIds = resp.selectedAttackersList.map { it.attackerInstanceId },
                    attackAlternativeByAttacker = resp.selectedAttackersList.associate { it.attackerInstanceId to it.alternativeGrpId },
                    defenderByAttacker = recipients,
                    autoDeclare = resp.autoDeclare,
                )
            if (!bridge.cutCoordinator.updateDeclaration(pending.actionId, greMsg.gameStateId, answer)) {
                log.warn("CombatHandler: attacker declaration did not match exact pending window")
                rejectDeclaration(greMsg, pending.actionId, pending.promptGameStateId)
                return false
            }
            drainPendingPlayback()
            return false
        }

        return submitAttackers(greMsg.gameStateId)
    }

    private fun submitAttackers(responseGameStateId: Int): Boolean {
        // Finalize — use last known selection
        val bridge = ctx.bridge
        val seatBridge = bridge.seat(counters.seatId)
        val pending =
            seatBridge.action.getPending() ?: run {
                log.warn("CombatHandler: SubmitAttackersReq but no pending action — recovering")
                DevCheck.fail { "SubmitAttackersReq but no pending action" }
                sink.sendRealGameState(bridge)
                return false
            }

        bridge.cutCoordinator.submitDeclaredAction(
            pending.actionId,
            responseGameStateId,
        )
        // Release the committed confirmation before the engine resumes. The
        // coordinator queue preserves ordering at this delivery boundary.
        sink.sendPriorityState(bridge)
        return true
    }

    /**
     * Handle CancelActionReq during attack declaration — pass combat with no attackers.
     *
     * The client sends CancelActionReq when the player clicks "Cancel" during the
     * declare attackers phase. This submits an empty attacker list to the engine,
     * which passes combat entirely (no attacks, skip to post-combat main).
     */
    fun onCancelAttackers(gameStateId: Int): Boolean {
        val bridge = ctx.bridge
        val seatBridge = bridge.seat(counters.seatId)
        val pending =
            seatBridge.action.getPending() ?: run {
                log.warn("CombatHandler: CancelAttackers but no pending action — recovering")
                DevCheck.fail { "CancelAttackers but no pending action" }
                sink.sendRealGameState(bridge)
                return false
            }

        log.info("CombatHandler: CancelAttackers — submitting empty attackers to pass combat")

        val submitted =
            bridge.cutCoordinator.submitDeclaredAction(
                pending.actionId,
                responseGameStateId = gameStateId,
            )
        if (!submitted) {
            log.warn("CombatHandler: CancelActionReq did not match current attacker window")
            return false
        }
        return true
    }

    /**
     * Handle DeclareBlockersResp or SubmitBlockersReq from the client.
     *
     * Same two-phase protocol as attackers:
     * - **DeclareBlockersResp** (type=32): iterative update with blocker assignments
     * - **SubmitBlockersReq** (type=33): finalize — the runtime resolves the retained selection.
     */
    fun onDeclareBlockers(greMsg: ClientToGREMessage): Boolean {
        val bridge = ctx.bridge
        val isSubmit = greMsg.type == ClientMessageType.SubmitBlockersReq

        // Reject stale Submit — same pattern as attackers (see onDeclareAttackers).
        if (isSubmit && isStaleSubmit(greMsg, "SubmitBlockersReq")) return false

        if (!isSubmit) {
            // DeclareBlockersResp is diff-style: each entry carries only the
            // toggled blocker. Non-empty selectedAttackerInstanceIds → assign;
            // empty → deselect. Server must accumulate across responses.
            val resp = greMsg.declareBlockersResp
            val pending = bridge.seat(counters.seatId).action.getPending() ?: return false
            val touched = resp.selectedBlockersList.map { it.blockerInstanceId }
            val assignments =
                resp.selectedBlockersList
                    .mapNotNull { blocker ->
                        blocker.selectedAttackerInstanceIdsList.firstOrNull()?.let { blocker.blockerInstanceId to it }
                    }.toMap()
            if (!bridge.cutCoordinator.updateDeclaration(
                    pending.actionId,
                    greMsg.gameStateId,
                    DeclarationAnswer.Blockers.of(assignments, touched),
                )
            ) {
                log.warn("CombatHandler: blocker declaration did not match exact pending window")
                rejectDeclaration(greMsg, pending.actionId, pending.promptGameStateId)
                return false
            }
            drainPendingPlayback()
            return false
        }

        // SubmitBlockersReq: finalize
        val seatBridge = bridge.seat(counters.seatId)
        val pending =
            seatBridge.action.getPending() ?: run {
                log.warn("CombatHandler: SubmitBlockersReq but no pending action — recovering")
                DevCheck.fail { "SubmitBlockersReq but no pending action" }
                sink.sendRealGameState(bridge)
                return false
            }

        bridge.cutCoordinator.submitDeclaredAction(
            pending.actionId,
            greMsg.gameStateId,
        )
        return true
    }

    /**
     * Handle AssignDamageResp from client.
     *
     * Parses value assignments and submits them to the coordinator. Live card
     * handles stay on the engine side of the interaction window.
     */
    fun onAssignDamage(greMsg: ClientToGREMessage): Boolean {
        val bridge = ctx.bridge
        val resp = greMsg.assignDamageResp
        val pending =
            bridge.cutCoordinator
                .promptRuntimes(ctx.seatId)
                .blocking
                .current()
                ?.takeIf { it.interaction is BlockingInteraction.Damage }
                ?: run {
                    log.warn("CombatHandler: AssignDamageResp but no pending damage assignment")
                    DevCheck.fail { "AssignDamageResp but no pending damage assignment" }
                    sink.sendRealGameState(bridge)
                    return false
                }
        // Parse all assigners. First assigner completes the blocking future;
        // subsequent assigners are cached for Forge's per-attacker loop.
        val assignmentValues = mutableListOf<DamageAssignmentCommand>()

        for (assigner in resp.assignersList) {
            log.info(
                "CombatHandler: damage rows={} total={}",
                assigner.assignmentsList.map { "${it.instanceId} → ${it.assignedDamage}" },
                assigner.totalDamage,
            )
            assignmentValues +=
                DamageAssignmentCommand(
                    attackerInstanceId = assigner.instanceId,
                    assignments = assigner.assignmentsList.map { DamageAssignmentRow(it.instanceId, it.assignedDamage) },
                    totalDamage = assigner.totalDamage,
                )
        }

        log.info(
            "CombatHandler: AssignDamageResp assigners={} cached={}",
            resp.assignersCount,
            maxOf(0, assignmentValues.size - 1),
        )

        // Complete the future — engine thread unblocks in WPC.assignCombatDamage
        if (assignmentValues.isEmpty()) log.warn("CombatHandler: no assigners in response, completing with empty map")
        if (!bridge.cutCoordinator
                .promptRuntimes(
                    ctx.seatId,
                ).blocking
                .submitDamageCommand(pending.interactionId, greMsg.gameStateId, assignmentValues)
        ) {
            return false
        }
        return true
    }

    /**
     * Drain any pending playback messages.
     *
     * The engine thread may have captured AI actions (via [GamePlayback])
     * between the last drain and now, queuing messages with new gsIds.
     * Drain and send the already committed batches.
     */
    private fun drainPendingPlayback() {
        for (batch in ctx.bridge.cutCoordinator.drain(counters.seatId)) {
            sink.sendBundledGRE(batch)
        }
    }
}
