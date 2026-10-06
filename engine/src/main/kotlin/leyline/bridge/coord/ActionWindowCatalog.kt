package leyline.bridge.coord

import leyline.bridge.handoff.ActionResponseKey
import leyline.bridge.handoff.DeferredCastCostPlan
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.SeatId
import leyline.bridge.types.WubrgColorMapping
import leyline.game.bundle.BundleBuilder
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

internal data class RuntimeActionSelection(
    val offer: GameActionBridge.ActionOffer,
    val response: Action,
)

internal data class RuntimeActionWindow(
    val seatId: SeatId,
    val actionId: String,
    val promptGameStateId: Int?,
    val offers: Map<ActionResponseKey, List<Pair<Long, GameActionBridge.ActionOffer>>>,
    val actions: ActionsAvailableReq = ActionsAvailableReq.getDefaultInstance(),
    val presentationActions: ActionsAvailableReq = ActionsAvailableReq.getDefaultInstance(),
    val publishedBatch: List<GREToClientMessage> = emptyList(),
    val legalAttackerIds: List<Int> = emptyList(),
    val legalBlockerCount: Int = 0,
    val selections: MutableMap<Long, RuntimeActionSelection> = mutableMapOf(),
    val deferredCostPlans: Map<Long, DeferredCastCostPlan> = emptyMap(),
    val deferredChildSelections: Map<Long, RuntimeActionSelection> = emptyMap(),
    val combat: RuntimeCombatWindow? = null,
    // Mutations happen under the coordinator feed lock; engine and session threads
    // read it without one, so the field stays volatile.
    @Volatile var status: ActionWindowStatus = ActionWindowStatus.Published,
)

/**
 * Lifecycle of one action window. The engine sees the window only while it is
 * [Published]; every other status hides it from `GameActionBridge.getPending`.
 */
internal sealed interface ActionWindowStatus {
    /** Materialized and installed, but not yet handed to the engine and client. */
    data object Publishing : ActionWindowStatus

    data object Published : ActionWindowStatus

    data class Claimed(
        val kind: ActionClaimKind,
        val token: Long,
    ) : ActionWindowStatus

    data object Completed : ActionWindowStatus
}

internal enum class ActionClaimKind { Immediate, Deferred }

private data class ActionExecutionIdentity(
    val command: PlayerAction,
    val stackAbilityGrpId: Int?,
    val forgeAbilityId: Int?,
    val spellGrpId: Int?,
)

private fun GameActionBridge.ActionOffer.executionIdentity() =
    ActionExecutionIdentity(command, stackAbilityGrpId, forgeAbilityId, spellGrpId)

internal fun GameActionBridge.ActionOffer.sourceCardId() =
    when (val value = command) {
        is PlayerAction.CastSpell -> value.cardId
        is PlayerAction.ActivateAbility -> value.cardId
        is PlayerAction.ActivateMana -> value.cardId
        is PlayerAction.PlayLand -> value.cardId
        PlayerAction.PassPriority,
        PlayerAction.EndTurn,
        is PlayerAction.DeclareAttackers,
        is PlayerAction.DeclareBlockers,
        -> null
    }

internal fun createRuntimeActionWindow(
    seatId: SeatId,
    pending: GameActionBridge.PendingAction,
    prepared: BundleBuilder.ActionWindowPrepared,
    messages: List<GREToClientMessage>,
    promptGsId: Int,
    nextToken: () -> Long,
    materializeDeferredCost: (Long, GameActionBridge.ActionOffer) -> DeferredCastCostPlanMaterializer.Result?,
): RuntimeActionWindow {
    val result = prepared.bundle
    val tokenizedOffers = result.actionOffers.map { nextToken() to it }
    val offers =
        tokenizedOffers
            .groupBy({ ActionResponseKey.from(it.second.action) }, { it })
    val deferred = tokenizedOffers.mapNotNull { (token, offer) -> materializeDeferredCost(token, offer)?.let { token to it } }.toMap()
    val attackerIds =
        messages
            .firstOrNull { it.hasDeclareAttackersReq() }
            ?.declareAttackersReq
            ?.attackersList
            ?.map { it.attackerInstanceId }
            .orEmpty()
    val blockerCount = messages.firstOrNull { it.hasDeclareBlockersReq() }?.declareBlockersReq?.blockersCount ?: 0
    val actions =
        messages.firstOrNull { it.hasActionsAvailableReq() }?.actionsAvailableReq
            ?: ActionsAvailableReq.getDefaultInstance()
    return RuntimeActionWindow(
        seatId,
        pending.actionId,
        promptGsId,
        offers,
        actions,
        prepared.presentationActions,
        messages,
        attackerIds,
        blockerCount,
        deferredCostPlans = deferred.mapValues { it.value.plan },
        deferredChildSelections = deferred.values.flatMap { it.childSelections.entries }.associate { it.toPair() },
        status = ActionWindowStatus.Publishing,
    )
}

/** Bare record for a state-only synchronization stop; it offers the client no actions. */
internal fun synchronizationActionWindow(
    seatId: SeatId,
    actionId: String,
): RuntimeActionWindow =
    RuntimeActionWindow(
        seatId = seatId,
        actionId = actionId,
        promptGameStateId = null,
        offers = emptyMap(),
    )

internal fun resolveActionOffer(
    offers: Map<ActionResponseKey, List<Pair<Long, GameActionBridge.ActionOffer>>>,
    response: Action,
): Pair<Long, GameActionBridge.ActionOffer>? {
    chooseExecutionIdentity(offers[ActionResponseKey.from(response)].orEmpty(), response)?.let { return it }
    return chooseExecutionIdentity(
        offers.values
            .flatten()
            .filter { matchesPartialResponse(it.second.action, response) },
        response,
    )
}

internal fun RuntimeActionWindow.resolveOfferedSelection(
    responseGameStateId: Int,
    response: Action,
): Pair<Long, GameActionBridge.ActionOffer>? {
    if (responseGameStateId != promptGameStateId) return null
    return resolveActionOffer(offers, response)
}

private fun chooseExecutionIdentity(
    offers: List<Pair<Long, GameActionBridge.ActionOffer>>,
    response: Action,
): Pair<Long, GameActionBridge.ActionOffer>? {
    val identities = offers.distinctBy { it.second.executionIdentity() }
    val exact = identities.filter { it.second.action == response }
    if (exact.size == 1) return exact.single()
    if (identities.size == 1) return identities.first()
    val best = identities.maxOfOrNull { selectorMatchScore(it.second.action, response) } ?: return null
    return identities.singleOrNull { selectorMatchScore(it.second.action, response) == best }
}

private fun selectorMatchScore(
    offer: Action,
    response: Action,
): Int =
    listOf(
        offer.grpId to response.grpId,
        offer.abilityGrpId to response.abilityGrpId,
        offer.uniqueAbilityId to response.uniqueAbilityId,
        offer.alternativeGrpId to response.alternativeGrpId,
    ).count { (offered, returned) -> offered != 0 && returned != 0 && offered == returned }

private fun matchesPartialResponse(
    offer: Action,
    response: Action,
): Boolean =
    offer.actionType == response.actionType &&
        offer.instanceId == response.instanceId &&
        (response.grpId == 0 || offer.grpId == 0 || offer.grpId == response.grpId) &&
        (response.abilityGrpId == 0 || offer.abilityGrpId == 0 || offer.abilityGrpId == response.abilityGrpId) &&
        (response.alternativeGrpId == 0 || offer.alternativeGrpId == 0 || offer.alternativeGrpId == response.alternativeGrpId)

internal fun selectedManaColor(action: Action): Byte? {
    val paymentColors =
        action.manaPaymentOptionsList
            .asSequence()
            .flatMap { it.manaList.asSequence() }
            .filter { it.srcInstanceId == 0 || it.srcInstanceId == action.instanceId }
            .map { it.color }
            .filter { it != ManaColor.None_afc9 }
            .distinct()
            .toList()
    paymentColors.singleOrNull()?.let(WubrgColorMapping::magicMaskForManaColor)?.let { return it }
    return action.manaSelectionsList
        .asSequence()
        .filter { it.instanceId == 0 || it.instanceId == action.instanceId }
        .flatMap { it.optionsList.asSequence() }
        .map { it.selectedColor }
        .filter { it != ManaColor.None_afc9 }
        .distinct()
        .singleOrNull()
        ?.let(WubrgColorMapping::magicMaskForManaColor)
}

internal fun hasAmbiguousActionCatalog(offers: List<GameActionBridge.ActionOffer>): Boolean =
    offers
        .groupBy { ActionResponseKey.from(it.action) }
        .values
        .any { variants ->
            val identities = variants.map { it.executionIdentity() }.distinct()
            identities.size > 1 && variants.map { it.action }.distinct().size == 1
        }
