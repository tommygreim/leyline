package leyline.tooling.headless

import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.Target as ProtoTarget

/**
 * Kotlin DSL for building proto messages in tests.
 *
 * **Inbound** (client → server): [performAction], [declareAttackersResp], etc.
 * **Outbound fixtures** (server → client): [greMessage], [actionsMessage] — for
 * unit tests that hand-build GRE messages without an engine.
 *
 * Production outbound messages use [leyline.game.bundle.BundleBuilder].
 *
 * ## Usage
 *
 * ```kotlin
 * val msg = performAction {
 *     actionType = ActionType.Play_add3
 *     instanceId = 123
 *     grpId = 456
 * }
 *
 * val attack = declareAttackersResp(attackers = listOf(iid1, iid2))
 * val done   = submitAttackersReq()
 * val target = selectTargetsResp(targets = listOf(iid))
 * ```
 *
 * ## Extending with a new message type
 *
 * 1. Identify the [ClientMessageType] enum value for the outer message.
 * 2. Identify the payload builder (e.g. `FooResp.newBuilder()`).
 * 3. Add a top-level function here following the pattern:
 *    ```kotlin
 *    fun fooResp(
 *        // variable fields as params with defaults
 *        field: Type = default,
 *        seatId: Int = 1,
 *    ): ClientToGREMessage = clientMessage(ClientMessageType.FooResp_097b) {
 *        setFooResp(FooResp.newBuilder().apply {
 *            // build payload
 *        })
 *    }
 *    ```
 * 4. Keep the function name matching the proto payload (camelCase).
 *    Use Kotlin named arguments and defaults — avoid overloads.
 */

// ---------------------------------------------------------------------------
// Core builder
// ---------------------------------------------------------------------------

/** Base builder — sets type and applies [block] to the message builder. */
inline fun clientMessage(
    type: ClientMessageType,
    block: ClientToGREMessage.Builder.() -> Unit = {},
): ClientToGREMessage =
    ClientToGREMessage
        .newBuilder()
        .setType(type)
        .apply(block)
        .build()

// ---------------------------------------------------------------------------
// PerformActionResp — Play, Cast, Pass, Activate
// ---------------------------------------------------------------------------

/**
 * Build a [PerformActionResp] wrapping a single [Action].
 *
 * ```kotlin
 * performAction {
 *     actionType = ActionType.Cast
 *     instanceId = 42
 *     grpId = 12345
 * }
 * ```
 */
fun performAction(block: Action.Builder.() -> Unit): ClientToGREMessage =
    clientMessage(ClientMessageType.PerformActionResp_097b) {
        setPerformActionResp(
            PerformActionResp
                .newBuilder()
                .addActions(Action.newBuilder().apply(block)),
        )
    }

fun performAction(action: Action): ClientToGREMessage =
    clientMessage(ClientMessageType.PerformActionResp_097b) {
        setPerformActionResp(
            PerformActionResp
                .newBuilder()
                .addActions(action),
        )
    }

// ---------------------------------------------------------------------------
// Attackers — two-phase protocol
// ---------------------------------------------------------------------------

/**
 * [DeclareAttackersResp] — attacker option toggle or "Attack All".
 *
 * ```kotlin
 * declareAttackersResp(attackers = listOf(iid1, iid2))          // select specific
 * declareAttackersResp(autoDeclare = true, autoDeclareTarget = 2) // Attack All → seat 2
 * ```
 */
fun declareAttackersResp(
    attackers: List<Int> = emptyList(),
    attackerAlternatives: Map<Int, Int> = emptyMap(),
    damageRecipients: Map<Int, DamageRecipient> = emptyMap(),
    autoDeclare: Boolean = false,
    autoDeclareTarget: Int? = null,
): ClientToGREMessage =
    clientMessage(ClientMessageType.DeclareAttackersResp_097b) {
        setDeclareAttackersResp(
            DeclareAttackersResp.newBuilder().apply {
                if (autoDeclare) {
                    setAutoDeclare(true)
                    autoDeclareTarget?.let {
                        setAutoDeclareDamageRecipient(
                            DamageRecipient
                                .newBuilder()
                                .setType(DamageRecType.Player_a0e5)
                                .setPlayerSystemSeatId(it),
                        )
                    }
                }
                for (iid in attackers) {
                    addSelectedAttackers(
                        Attacker
                            .newBuilder()
                            .setAttackerInstanceId(iid)
                            .apply {
                                val alternativeGrpId = attackerAlternatives[iid] ?: 0
                                if (alternativeGrpId != 0) setAlternativeGrpId(alternativeGrpId)
                                damageRecipients[iid]?.let { setSelectedDamageRecipient(it) }
                            },
                    )
                }
            },
        )
    }

fun planeswalkerDamageRecipient(planeswalkerInstanceId: Int): DamageRecipient =
    DamageRecipient
        .newBuilder()
        .setType(DamageRecType.PlanesWalker)
        .setPlaneswalkerInstanceId(planeswalkerInstanceId)
        .build()

/** [SubmitAttackersReq] — type-only "Done" button, no payload. */
fun submitAttackersReq(seatId: Int = 1): ClientToGREMessage =
    clientMessage(ClientMessageType.SubmitAttackersReq) {
        setSystemSeatId(seatId)
    }

// ---------------------------------------------------------------------------
// Blockers — two-phase protocol
// ---------------------------------------------------------------------------

/**
 * [DeclareBlockersResp] — iterative blocker assignments.
 *
 * @param assignments blockerInstanceId → attackerInstanceId(s) it blocks
 */
fun declareBlockersResp(assignments: Map<Int, Int> = emptyMap()): ClientToGREMessage =
    clientMessage(ClientMessageType.DeclareBlockersResp_097b) {
        setDeclareBlockersResp(
            DeclareBlockersResp.newBuilder().apply {
                for ((blockerIid, attackerIid) in assignments) {
                    addSelectedBlockers(
                        Blocker
                            .newBuilder()
                            .setBlockerInstanceId(blockerIid)
                            .addSelectedAttackerInstanceIds(attackerIid),
                    )
                }
            },
        )
    }

/**
 * [DeclareBlockersResp] — deselect a single blocker. Blocker entry with
 * blockerInstanceId set and no selectedAttackerInstanceIds.
 */
fun declareBlockersRespDeselect(blockerInstanceId: Int): ClientToGREMessage =
    clientMessage(ClientMessageType.DeclareBlockersResp_097b) {
        setDeclareBlockersResp(
            DeclareBlockersResp.newBuilder().addSelectedBlockers(
                Blocker
                    .newBuilder()
                    .setBlockerInstanceId(blockerInstanceId)
                    .setMaxAttackers(1),
            ),
        )
    }

/** [SubmitBlockersReq] — type-only "Done" button. */
fun submitBlockersReq(seatId: Int = 1): ClientToGREMessage =
    clientMessage(ClientMessageType.SubmitBlockersReq) {
        setSystemSeatId(seatId)
    }

// ---------------------------------------------------------------------------
// Damage Assignment
// ---------------------------------------------------------------------------

/**
 * [AssignDamageResp] — respond to an AssignDamageReq with filled damage assignments.
 *
 * @param assigners list of (attackerInstanceId, assignments) where assignments is
 *                  a list of (blockerInstanceId, assignedDamage) pairs
 */
fun assignDamageResp(assigners: List<Pair<Int, List<Pair<Int, Int>>>>): ClientToGREMessage =
    clientMessage(ClientMessageType.AssignDamageResp_097b) {
        setAssignDamageResp(
            AssignDamageResp.newBuilder().apply {
                for ((attackerIid, assignments) in assigners) {
                    addAssigners(
                        DamageAssigner
                            .newBuilder()
                            .setInstanceId(attackerIid)
                            .apply {
                                for ((targetIid, damage) in assignments) {
                                    addAssignments(
                                        DamageAssignment
                                            .newBuilder()
                                            .setInstanceId(targetIid)
                                            .setAssignedDamage(damage),
                                    )
                                }
                            },
                    )
                }
            },
        )
    }

// ---------------------------------------------------------------------------
// Targeting
// ---------------------------------------------------------------------------

/**
 * [SelectTargetsResp] — select targets by instanceId.
 *
 * All targets get [SelectAction.Select_a1ad] (the standard "select" action).
 */
fun selectTargetsResp(
    targets: List<Int>,
    targetIdx: Int = 1,
): ClientToGREMessage =
    clientMessage(ClientMessageType.SelectTargetsResp_097b) {
        setSelectTargetsResp(
            SelectTargetsResp.newBuilder().setTarget(
                TargetSelection.newBuilder().setTargetIdx(targetIdx).apply {
                    for (iid in targets) {
                        addTargets(
                            ProtoTarget
                                .newBuilder()
                                .setTargetInstanceId(iid)
                                .setLegalAction(SelectAction.Select_a1ad),
                        )
                    }
                },
            ),
        )
    }

/**
 * [CancelActionReq] — cancel the current pending action (e.g. back out of targeting).
 *
 * Empty message — the type field (`CancelActionReq_097b = 5`) is the sole signal.
 */
fun cancelActionReq(): ClientToGREMessage =
    clientMessage(ClientMessageType.CancelActionReq_097b) {
        setCancelActionReq(CancelActionReq.newBuilder())
    }

/**
 * [SubmitTargetsReq] — finalize targeting (the "Done" button).
 *
 * Type-only, no payload — matches reference client behavior.
 */
fun submitTargetsReq(): ClientToGREMessage = clientMessage(ClientMessageType.SubmitTargetsReq)

// ---------------------------------------------------------------------------
// SelectN — legend rule, "choose N" prompts
// ---------------------------------------------------------------------------

/**
 * [SelectNResp] — respond to a SelectNReq with selected instanceIds.
 *
 * Used for legend rule (choose 1 to keep) and similar "choose N" prompts.
 *
 * ```kotlin
 * selectNResp(ids = listOf(iid))
 * ```
 */
fun selectNResp(
    ids: List<Int>,
    useArbitrary: OrderingType? = null,
): ClientToGREMessage =
    clientMessage(ClientMessageType.SelectNresp) {
        setSelectNResp(
            SelectNResp.newBuilder().apply {
                for (id in ids) addIds(id)
                useArbitrary?.let { setUseArbitrary(it) }
            },
        )
    }

fun orderResp(ids: List<Int>): ClientToGREMessage =
    clientMessage(ClientMessageType.OrderResp_097b) {
        setOrderResp(
            OrderResp
                .newBuilder()
                .addAllIds(ids)
                .setOrdering(OrderingType.OrderAsIndicated),
        )
    }

/** Respond to a fixed-total DistributionReq with one amount per target. */
fun distributionResp(amounts: List<Pair<Int, Int>>): ClientToGREMessage =
    clientMessage(ClientMessageType.DistributionResp_097b) {
        setDistributionResp(
            DistributionResp.newBuilder().apply {
                amounts.forEach { (instanceId, amount) ->
                    addDistributions(Distribution.newBuilder().setInstanceId(instanceId).setAmount(amount))
                }
            },
        )
    }

/** [SearchResp] — respond to a library/search prompt with selected instanceIds. */
fun searchResp(itemsFound: List<Int>): ClientToGREMessage =
    clientMessage(ClientMessageType.SearchResp_097b) {
        setSearchResp(
            SearchResp.newBuilder().apply {
                addAllItemsFound(itemsFound)
            },
        )
    }

/** [SearchFromGroupsResp] — respond with one echoed group row. */
fun groupedSearchResp(
    groupId: Int,
    ids: List<Int>,
    maxSelect: Int,
): ClientToGREMessage =
    clientMessage(ClientMessageType.SearchFromGroupsResp_097b) {
        setSearchFromGroupsResp(
            SearchFromGroupsResp.newBuilder().addGroups(
                Group
                    .newBuilder()
                    .setGroupId(groupId)
                    .setMaxSelect(maxSelect)
                    .addAllIds(ids),
            ),
        )
    }

fun groupedSearchFailResp(): ClientToGREMessage =
    clientMessage(ClientMessageType.SearchFromGroupsResp_097b) {
        setSearchFromGroupsResp(SearchFromGroupsResp.getDefaultInstance())
    }

// ---------------------------------------------------------------------------
// Modal — CastingTimeOptionsResp
// ---------------------------------------------------------------------------

/**
 * [CastingTimeOptionsResp] — respond to a modal/kicker CastingTimeOptionsReq.
 *
 * @param selectedGrpIds the grpIds of chosen modal options
 * @param ctoId must match the ctoId from the request (usually 1)
 */
fun castingTimeOptionsResp(
    selectedGrpIds: List<Int>,
    ctoId: Int = 1,
): ClientToGREMessage =
    clientMessage(ClientMessageType.CastingTimeOptionsResp_097b) {
        setCastingTimeOptionsResp(
            CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                CastingTimeOptionResp
                    .newBuilder()
                    .setCtoId(ctoId)
                    .setCastingTimeOptionType(CastingTimeOptionType.Modal_a7b4)
                    .setChooseModalResp(
                        ChooseModalResp.newBuilder().apply {
                            for (grpId in selectedGrpIds) addGrpIds(grpId)
                        },
                    ),
            ),
        )
    }

/**
 * [CastingTimeOptionsResp] for optional costs (kicker, buyback).
 * Sends the selected [ctoId] — 0 for Done (decline), >0 for the cost option.
 */
fun optionalCostResp(ctoId: Int): ClientToGREMessage =
    clientMessage(ClientMessageType.CastingTimeOptionsResp_097b) {
        setCastingTimeOptionsResp(
            CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                CastingTimeOptionResp.newBuilder().setCtoId(ctoId),
            ),
        )
    }

fun castingTimeXResp(
    ctoId: Int,
    value: Int,
): ClientToGREMessage =
    clientMessage(ClientMessageType.CastingTimeOptionsResp_097b) {
        setCastingTimeOptionsResp(
            CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                CastingTimeOptionResp
                    .newBuilder()
                    .setCtoId(ctoId)
                    .setCastingTimeOptionType(CastingTimeOptionType.ChooseX_a7b4)
                    .setNumericInputResp(NumericInputResp.newBuilder().setNumericInputValue(value)),
            ),
        )
    }

/**
 * [CastingTimeOptionsResp] for a required alternate additional cost.
 * [optionIndex] is the offered `selectNReq` id naming the branch.
 */
fun alternateCostResp(
    ctoId: Int,
    optionIndex: Int,
): ClientToGREMessage =
    clientMessage(ClientMessageType.CastingTimeOptionsResp_097b) {
        setCastingTimeOptionsResp(
            CastingTimeOptionsResp.newBuilder().setCastingTimeOptionResp(
                CastingTimeOptionResp
                    .newBuilder()
                    .setCtoId(ctoId)
                    .setCastingTimeOptionType(CastingTimeOptionType.ChooseOrCost)
                    .setSelectNResp(SelectNResp.newBuilder().addIds(optionIndex)),
            ),
        )
    }

fun manaTypeResp(choicesByCtoId: List<Pair<Int, ManaColor>>): ClientToGREMessage =
    clientMessage(ClientMessageType.CastingTimeOptionsResp_097b) {
        setCastingTimeOptionsResp(
            CastingTimeOptionsResp.newBuilder().apply {
                choicesByCtoId.forEach { (ctoId, color) ->
                    addCastingTimeOptionResps(
                        CastingTimeOptionResp
                            .newBuilder()
                            .setCtoId(ctoId)
                            .setCastingTimeOptionType(CastingTimeOptionType.ManaType)
                            .setSelectManaTypeResp(SelectManaTypeResp.newBuilder().setManaColor(color)),
                    )
                }
            },
        )
    }

fun effectCostResp(selectedInstanceIds: List<Int>): ClientToGREMessage =
    clientMessage(ClientMessageType.EffectCostResp_097b) {
        setEffectCostResp(
            EffectCostResp
                .newBuilder()
                .setEffectCostType(EffectCostType.Select_a59c)
                .setCostSelection(
                    SelectNResp.newBuilder().apply {
                        selectedInstanceIds.forEach { addIds(it) }
                    },
                ),
        )
    }

fun gatherCountersResp(gatherings: List<Pair<Int, Int>>): ClientToGREMessage =
    clientMessage(ClientMessageType.EffectCostResp_097b) {
        setEffectCostResp(
            EffectCostResp
                .newBuilder()
                .setEffectCostType(EffectCostType.GatherCounters)
                .setGatherResp(
                    GatherResp.newBuilder().apply {
                        gatherings.forEach { (instanceId, amount) ->
                            addGatherings(Gathering.newBuilder().setInstanceId(instanceId).setAmount(amount))
                        }
                    },
                ),
        )
    }
// ---------------------------------------------------------------------------
// OptionalActionResp — shock land ETB "pay life or enter tapped"
// ---------------------------------------------------------------------------

/**
 * [OptionalActionResp] — accept (AllowYes) or decline (CancelNo) a
 * shock land ETB replacement prompt.
 *
 * ```kotlin
 * optionalActionResp(accept = true)   // pay 2 life, enter untapped
 * optionalActionResp(accept = false)  // enter tapped
 * ```
 */
fun optionalActionResp(accept: Boolean): ClientToGREMessage =
    clientMessage(ClientMessageType.OptionalActionResp) {
        setOptionalResp(
            OptionalResp
                .newBuilder()
                .setResponse(if (accept) OptionResponse.AllowYes else OptionResponse.CancelNo),
        )
    }

// ---------------------------------------------------------------------------
// Settings — for unit tests building SettingsMessage / stops
// ---------------------------------------------------------------------------

/** Build a [SettingsMessage] with DSL block. */
fun settingsMessage(block: SettingsMessage.Builder.() -> Unit): SettingsMessage = SettingsMessage.newBuilder().apply(block).build()

/** Build a [Stop] entry. */
fun stop(
    type: StopType,
    scope: SettingScope,
    status: SettingStatus,
): Stop =
    Stop
        .newBuilder()
        .setStopType(type)
        .setAppliesTo(scope)
        .setStatus(status)
        .build()

// ---------------------------------------------------------------------------
// Actions — for unit tests building ActionsAvailableReq
// ---------------------------------------------------------------------------

/** Build a [ManaRequirement] — e.g. `mana(ManaColor.Generic, 2)`. */
fun mana(
    color: ManaColor,
    count: Int,
): ManaRequirement =
    ManaRequirement
        .newBuilder()
        .addColor(color)
        .setCount(count)
        .build()

// ===========================================================================
// Outbound GRE fixtures — for unit tests that hand-build server messages
// ===========================================================================

/**
 * Build a [GREToClientMessage] wrapping a [GameStateMessage].
 *
 * ```kotlin
 * val gre = greMessage(msgId = 1, gsId = 5) {
 *     setType(GameStateType.Full)
 *     addGameObjects(...)
 * }
 * ```
 */
fun greMessage(
    msgId: Int = 1,
    gsId: Int = 0,
    seatIds: List<Int> = listOf(1),
    gsm: GameStateMessage.Builder.() -> Unit = {},
): GREToClientMessage {
    val gs =
        GameStateMessage
            .newBuilder()
            .setGameStateId(gsId)
            .apply(gsm)
            .build()
    return GREToClientMessage
        .newBuilder()
        .setType(GREMessageType.GameStateMessage_695e)
        .setMsgId(msgId)
        .setGameStateId(gsId)
        .addAllSystemSeatIds(seatIds)
        .setGameStateMessage(gs)
        .build()
}

/** Wrap a pre-built [GameStateMessage] in a [GREToClientMessage]. */
fun greMessage(
    msgId: Int = 1,
    gsm: GameStateMessage,
    seatIds: List<Int> = listOf(1),
): GREToClientMessage =
    GREToClientMessage
        .newBuilder()
        .setType(GREMessageType.GameStateMessage_695e)
        .setMsgId(msgId)
        .setGameStateId(gsm.gameStateId)
        .addAllSystemSeatIds(seatIds)
        .setGameStateMessage(gsm)
        .build()

/** Build a [GREToClientMessage] wrapping an [ActionsAvailableReq]. */
fun actionsMessage(
    msgId: Int = 1,
    gsId: Int = 0,
    seatIds: List<Int> = listOf(1),
    actions: ActionsAvailableReq.Builder.() -> Unit,
): GREToClientMessage =
    GREToClientMessage
        .newBuilder()
        .setType(GREMessageType.ActionsAvailableReq_695e)
        .setMsgId(msgId)
        .setGameStateId(gsId)
        .addAllSystemSeatIds(seatIds)
        .setActionsAvailableReq(ActionsAvailableReq.newBuilder().apply(actions))
        .build()
