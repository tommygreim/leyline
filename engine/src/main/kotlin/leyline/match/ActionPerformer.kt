package leyline.match

import leyline.bridge.coord.PriorityPolicyRuntime
import leyline.game.data.KeywordAbilityIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Handles the `PerformActionResp` dispatch cycle: validate the inbound action,
 * submit a correlated value response to the coordinator, and drive the
 * post-action continuation. The engine thread resolves the retained executable
 * action and owns all progression after that point.
 *
 * **Threading:** Callers invoke inside the session lock. This class adds no
 * locking of its own.
 *
 * **State:** Stateless between calls. Priority settings are submitted to the
 * match runtime, which remains the sole owner of mutable policy state.
 */
internal class ActionPerformer(
    private val sink: GreMessageSink,
    private val counters: SessionCounters,
    private val targetingHandler: TargetingHandler,
    private val priorityPolicy: PriorityPolicyRuntime,
    private val ctx: SessionContext,
    private val continuation: MatchRuntimeContinuation,
    private val matchId: String,
) {
    private val log = LoggerFactory.getLogger(ActionPerformer::class.java)

    /**
     * Handle a client action (land play, spell cast, activate, pass, …) and
     * advance the engine to the next priority stop.
     */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    fun perform(
        greMsg: ClientToGREMessage,
        completedActionId: String?,
    ) {
        var acceptedClaim: leyline.bridge.coord.MatchActionWindowRuntime.ActionClaim? = null
        try {
            val bridge = ctx.bridge
            val seatBridge = bridge.seat(counters.seatId)
            // Reject stale actions — client may resend with outdated gameStateId.
            // Compare against the last prompt's gsId, not currentGsId. Trailing
            // post-content echoes (and any future bundle that emits a non-prompt
            // GRE between the AAR and the client's response) advance currentGsId
            // past the AAR's gsId; a legitimate response targets the AAR's gsId,
            // not the latest counter value. Anything strictly less than the last
            // prompt is genuinely stale (a newer prompt has been emitted since).
            val clientGsId = greMsg.gameStateId
            if (clientGsId != 0 && clientGsId < bridge.committedSequence().lastPromptGsId) {
                log
                    .atDebug()
                    .addKeyValue("event", "match.action_rejected")
                    .addKeyValue("match_id", matchId)
                    .addKeyValue("seat", counters.seatId.value)
                    .addKeyValue("response_type", greMsg.type.name)
                    .addKeyValue("game_state_id", clientGsId)
                    .addKeyValue("reason", "stale_game_state")
                    .log("Client action rejected")
                return
            }

            val blocking =
                bridge.cutCoordinator
                    .promptRuntimes(counters.seatId)
                    .blocking
                    .current()
            val optional = blocking?.interaction as? leyline.bridge.handoff.BlockingInteraction.Optional
            val freeCast = optional?.freeCast
            if (blocking != null && freeCast != null) {
                val action = greMsg.performActionResp.actionsList.firstOrNull() ?: return
                val cardInstanceId = optional.sourceId?.let(bridge::peekInstanceId)?.value ?: return
                val promptBridge = bridge.promptBridge(counters.seatId)
                val sourceInstanceId =
                    promptBridge.resolveTriggerStackAbilityInstanceId(freeCast.sourceAbilityForgeId)
                        ?: return
                val alternativeSourceZcid =
                    bridge.peekInstanceId(freeCast.alternativeSourceForgeCardId)?.value
                        ?: return
                val accepted =
                    when (action.actionType) {
                        ActionType.Pass -> false
                        ActionType.Cast ->
                            action.grpId == freeCast.cardGrpId &&
                                action.instanceId == cardInstanceId &&
                                action.abilityGrpId == freeCast.abilityGrpId &&
                                action.sourceId == sourceInstanceId &&
                                action.alternativeGrpId == 149 &&
                                action.alternativeSourceZcid == alternativeSourceZcid
                        else -> return
                    }
                if (action.actionType == ActionType.Cast && !accepted) return
                if (!bridge.cutCoordinator
                        .promptRuntimes(
                            counters.seatId,
                        ).blocking
                        .submitOptional(blocking.interactionId, clientGsId, accepted)
                ) {
                    return
                }
                bridge.prioritySignal.markPromptResolved()
                continuation.awaitHorizon(completedActionId)
                return
            }

            val paymentResult = targetingHandler.tryHandlePayCostsPerformAction(greMsg)
            when (paymentResult) {
                HandlerResult.Resume -> continuation.awaitHorizon(completedActionId)
                HandlerResult.Waiting -> Unit
                HandlerResult.NotHandled -> Unit
            }
            if (paymentResult != HandlerResult.NotHandled) {
                return
            }

            val pending =
                seatBridge.action.getPending() ?: run {
                    log
                        .atDebug()
                        .addKeyValue("event", "match.action_rejected")
                        .addKeyValue("match_id", matchId)
                        .addKeyValue("seat", counters.seatId.value)
                        .addKeyValue("response_type", greMsg.type.name)
                        .addKeyValue("game_state_id", clientGsId)
                        .addKeyValue("reason", "no_pending_action")
                        .log("Client action rejected")
                    bridge.cutCoordinator.drain(counters.seatId).forEach { sink.sendBundledGRE(it) }
                    return
                }
            val action = greMsg.performActionResp.actionsList.firstOrNull()
            if (action == null) {
                log
                    .atDebug()
                    .addKeyValue("event", "match.action_rejected")
                    .addKeyValue("match_id", matchId)
                    .addKeyValue("seat", counters.seatId.value)
                    .addKeyValue("response_type", greMsg.type.name)
                    .addKeyValue("game_state_id", clientGsId)
                    .addKeyValue("reason", "missing_action")
                    .log("Client action rejected")
                return
            }
            val mayDefer =
                action.actionType == ActionType.Cast ||
                    action.actionType == ActionType.CastAdventure ||
                    action.actionType == ActionType.CastLeftRoom ||
                    action.actionType == ActionType.CastRightRoom ||
                    action.actionType == ActionType.CastOmen ||
                    action.actionType == ActionType.CastMdfc
            val claim = bridge.cutCoordinator.claimPriorityResponse(pending.actionId, clientGsId, action, defer = mayDefer)
            if (claim == null) {
                log
                    .atDebug()
                    .addKeyValue("event", "match.action_rejected")
                    .addKeyValue("match_id", matchId)
                    .addKeyValue("seat", counters.seatId.value)
                    .addKeyValue("response_type", greMsg.type.name)
                    .addKeyValue("game_state_id", clientGsId)
                    .addKeyValue("reason", "action_window_mismatch")
                    .addKeyValue("phase", pending.state.phase)
                    .log("Client action rejected")
                return
            }
            acceptedClaim = claim.actionClaim
            log
                .atDebug()
                .addKeyValue("event", "match.action_accepted")
                .addKeyValue("match_id", matchId)
                .addKeyValue("seat", counters.seatId.value)
                .addKeyValue("response_type", greMsg.type.name)
                .addKeyValue("game_state_id", clientGsId)
                .addKeyValue("action_type", action.actionType.name)
                .addKeyValue("phase", pending.state.phase)
                .log("Client action accepted")

            // Bind an optional hold to the accepted action; the engine confirms its completion.
            val autoPassPriority = greMsg.performActionResp.autoPassPriority
            if (autoPassPriority != AutoPassPriority.None_a099) {
                priorityPolicy.submitAutoPassPriority(autoPassPriority)
                log.debug("autoPassPriority={}", autoPassPriority)
            }

            if (!mayDefer) {
                check(bridge.cutCoordinator.completeActionClaim(claim.actionClaim)) { "Accepted action claim did not complete" }
                acceptedClaim = null
            }
            when (action.actionType) {
                ActionType.Pass, ActionType.FloatMana -> Unit
                ActionType.Play_add3, ActionType.PlayMdfc -> {
                    Tap.actionResult(matchId, counters.seatId.value, action.actionType, action.instanceId, claim.cardId, true)
                }
                ActionType.Cast -> {
                    val submitted = submitCastOrDefer(action, claim.actionClaim) ?: return
                    acceptedClaim = null
                    Tap.actionResult(matchId, counters.seatId.value, action.actionType, action.instanceId, claim.cardId, submitted)
                }
                ActionType.Activate_add3 -> {
                    Tap.actionResult(matchId, counters.seatId.value, action.actionType, action.instanceId, claim.cardId, true)
                }
                ActionType.ActivateMana -> {
                    Tap.actionResult(matchId, counters.seatId.value, action.actionType, action.instanceId, claim.cardId, true)
                }
                ActionType.CastMdfc, ActionType.CastAdventure, ActionType.CastOmen, ActionType.CastLeftRoom, ActionType.CastRightRoom -> {
                    val submitted = submitCastOrDefer(action, claim.actionClaim) ?: return
                    acceptedClaim = null
                    Tap.actionResult(matchId, counters.seatId.value, action.actionType, action.instanceId, claim.cardId, submitted)
                }
                ActionType.SpecialTurnFaceUp_add3 -> {
                    Tap.actionResult(matchId, counters.seatId.value, action.actionType, action.instanceId, claim.cardId, true)
                }
                else -> {
                    log.info("Unhandled action type {}, passing", action.actionType)
                    return
                }
            }

            continuation.awaitClientVisibleHorizon(ignoredActionId = completedActionId)
        } catch (ex: Exception) {
            acceptedClaim?.let { ctx.bridge.cutCoordinator.failActionClaim(it, ex) }
            ctx.bridge.cutCoordinator.fail(ex)
        }
    }

    private fun submitCastOrDefer(
        action: Action,
        actionClaim: leyline.bridge.coord.MatchActionWindowRuntime.ActionClaim,
    ): Boolean? {
        if (targetingHandler.checkAlternateAdditionalCostChoice(actionClaim)) {
            return null
        }
        if (targetingHandler.checkHybridManaTypeOptions(actionClaim)) {
            return null
        }
        val skipOptionalCostPrompt =
            action.alternativeGrpId == KeywordAbilityIds.JUMP_START || action.alternativeGrpId == KeywordAbilityIds.RETRACE
        if (!skipOptionalCostPrompt && targetingHandler.checkOptionalCosts(actionClaim)) {
            return null
        }
        check(ctx.bridge.cutCoordinator.completeActionClaim(actionClaim)) { "Deferred action claim did not complete" }
        return true
    }
}
