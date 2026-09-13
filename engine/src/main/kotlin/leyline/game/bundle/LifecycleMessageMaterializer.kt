package leyline.game.bundle

import leyline.bridge.coord.PriorityPolicyRuntime
import leyline.bridge.handoff.MulliganBridge
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.MulliganPhase
import leyline.bridge.types.SeatId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PlayerMapper
import leyline.game.mapping.PromptIds
import leyline.game.mapping.StateProjectionCompiler
import leyline.game.mapping.ViewerProjectionIntent
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.game.state.ViewerProjectionCursor
import wotc.mtgo.gre.external.messaging.Messages.*

/** Materializes tentative startup, deal, mulligan, and puzzle lifecycle batches. */
@Suppress("LargeClass") // one lifecycle sequence and its wire helpers
object LifecycleMessageMaterializer {
    internal data class LifecycleMessages(
        val messages: List<GREToClientMessage>,
        val nextMsgId: Int,
        val transition: ProjectionTransition? = null,
    )

    internal data class ViewerLifecycleMessages(
        val viewers: List<Pair<SeatId, List<GREToClientMessage>>>,
        val transition: ProjectionTransition,
    )

    /** Publish both private hand views and one chooser's exact keep/tuck request. */
    internal fun humanMulliganPrompt(
        bridge: GameBridge,
        viewers: List<ProjectionViewer>,
        seatId: SeatId,
        prompt: MulliganBridge.PendingPrompt,
        redraw: Boolean,
        gameStateId: Int,
        planner: LogicalSequencePlanner,
    ): ViewerLifecycleMessages {
        val stateMsgId = planner.nextMsgId()
        val requestMsgId = planner.nextMsgId()
        val prior = bridge.projectionStateSnapshot()
        val (outputs, next) =
            bridge.editProjection(prior) { editor ->
                val snapshot = GsmSnapshot.capture(checkNotNull(bridge.getGame()), bridge, "", 0)
                val deleted =
                    if (redraw) {
                        val cards =
                            listOf(ZoneIds.handOf(seatId.value), ZoneIds.libraryOf(seatId.value))
                                .flatMap { snapshot.zones[it]?.contents.orEmpty() }
                                .toSet()
                        editor.resetIdentitiesForRedraw(redrawIdentityFamily(snapshot, cards, editor)).map { it.value }
                    } else {
                        emptyList()
                    }
                viewers.map { viewer ->
                    val chooser = viewer.seatId == seatId
                    val state =
                        GsmBuilder
                            .buildDealHand(bridge, gameStateId, viewer.seatId.value, snapshot, deleted)
                            .toBuilder()
                            .setTurnInfo(TurnInfo.newBuilder().setActivePlayer(bridge.dieRollWinner).setDecisionPlayer(seatId.value))
                            .setPendingMessageCount(if (chooser) 1 else 0)
                            .build()
                    advanceViewerCursor(editor, viewer.seatId, snapshot, state)
                    val gsm =
                        GREToClientMessage
                            .newBuilder()
                            .setType(GREMessageType.GameStateMessage_695e)
                            .setMsgId(stateMsgId)
                            .setGameStateId(gameStateId)
                            .addSystemSeatIds(viewer.seatId.value)
                            .setGameStateMessage(state)
                            .build()
                    val messages =
                        if (chooser) {
                            val handIds =
                                state.zonesList
                                    .firstOrNull { it.zoneId == ZoneIds.handOf(seatId.value) }
                                    ?.objectInstanceIdsList
                                    .orEmpty()
                            listOf(gsm, reconnectMulliganRequest(requestMsgId, gameStateId, seatId, prompt, handIds, null))
                        } else {
                            listOf(gsm)
                        }
                    viewer.seatId to messages
                }
            }
        return ViewerLifecycleMessages(outputs, ProjectionTransition(prior.revision, next))
    }

    internal fun reconnectMulliganRequest(
        msgId: Int,
        gameStateId: Int,
        seatId: SeatId,
        prompt: MulliganBridge.PendingPrompt,
        handInstanceIds: List<Int>,
        keepRequest: GREToClientMessage?,
    ): GREToClientMessage =
        when (prompt.phase) {
            MulliganPhase.WaitingKeep ->
                keepRequest
                    ?.toBuilder()
                    ?.setMsgId(msgId)
                    ?.setGameStateId(gameStateId)
                    ?.build()
                    ?: GsmBuilder.buildMulliganReq(
                        msgId = msgId,
                        gameStateId = gameStateId,
                        seatId = seatId.value,
                        numCards = handInstanceIds.size,
                        mulliganCount = prompt.mulliganCount,
                    )
            MulliganPhase.WaitingTuck ->
                GsmBuilder.buildGroupReq(
                    msgId = msgId,
                    gameStateId = gameStateId,
                    seatId = seatId.value,
                    handInstanceIds = handInstanceIds,
                    cardsToTuck = prompt.cardsToTuck,
                )
        }

    internal fun lifecycleMessages(
        messages: List<GREToClientMessage>,
        nextMsgId: Int,
        transition: ProjectionTransition?,
    ): LifecycleMessages = LifecycleMessages(messages, nextMsgId, transition)

    internal fun puzzleActionsReq(
        msgId: Int,
        gameStateId: Int,
        seatId: SeatId,
        actions: ActionsAvailableReq,
    ): LifecycleMessages {
        val gre =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.ActionsAvailableReq_695e)
                .addSystemSeatIds(seatId.value)
                .setMsgId(msgId)
                .setGameStateId(gameStateId)
                .setActionsAvailableReq(actions)
                .setPrompt(Prompt.newBuilder().setPromptId(PromptIds.PASS_PRIORITY).build())
                .build()
        return LifecycleMessages(listOf(gre), msgId + 1)
    }

    /**
     * Initial GRE bundle — built dynamically.
     * Seat 1: ConnectResp + DieRollResults + GameState
     * Seat 2: DieRollResults + GameState + ChooseStartingPlayerReq
     *
     * @param dieRollWinner which seat wins the die roll (1 or 2, default 2)
     */
    internal fun initialBundle(
        seatId: SeatId,
        matchId: String,
        msgIdStart: Int,
        gameStateId: Int,
        deckMessage: DeckMessage,
        bridge: GameBridge,
        dieRollWinner: Int = 2,
        includeStartingPlayerPrompt: Boolean = true,
        seedProjectionCursor: Boolean = false,
    ): LifecycleMessages {
        var msgId = msgIdStart
        val messages = mutableListOf<GREToClientMessage>()

        // Wire sequencing (Arena protocol contract): the first-connecting seat (1)
        // receives ConnectResp; the second (2) receives ChooseStartingPlayerReq.
        // These literals are NOT role checks — they reflect the match-handshake
        // sequence that Arena dictates, independent of which seat is human-controlled.
        // Role-scoped decisions use `Seating` (see `GameBridge.seating`).
        if (seatId == SeatId(1) || bridge.humanVsHuman) {
            // ConnectResp with deck + default settings
            messages.add(buildConnectResp(msgId++, seatId, deckMessage, bridge.priorityPolicy(seatId).currentSettings()))
        }

        // DieRollResults (both seats see this)
        messages.add(buildDieRollResults(msgId++, dieRollWinner))

        // Full initial GameState
        val shouldSendStartingPlayerPrompt = includeStartingPlayerPrompt && seatId == SeatId(2)
        val pendingCount = if (shouldSendStartingPlayerPrompt) 1 else 0 // ChooseStartingPlayerReq follows
        val priorProjection = bridge.projectionStateSnapshot()
        val (snapshotAndGsm, nextProjection) =
            bridge.editProjection(priorProjection) { editor ->
                val initSnap = GsmSnapshot.capture(bridge.getGame()!!, bridge, matchId, 0)
                val initialGsm =
                    GsmBuilder.buildInitialGameState(
                        matchId,
                        gameStateId,
                        bridge,
                        initSnap,
                        pendingCount,
                        seatId.value,
                        includeStartingPlayerDecision = includeStartingPlayerPrompt,
                    )
                seedInitialProtoZones(editor, initialGsm, initSnap, bridge)
                if (seedProjectionCursor) {
                    editor.viewerCursors[seatId] =
                        ViewerProjectionCursor(
                            previousSnapshot = initSnap,
                            fullState = initialGsm.toBuilder().setPendingMessageCount(0).build(),
                        )
                }
                initSnap to initialGsm
            }
        val transition = ProjectionTransition(priorProjection.revision, nextProjection)
        val gsm = snapshotAndGsm.second
        messages.add(
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .addSystemSeatIds(seatId.value)
                .setMsgId(msgId++)
                .setGameStateId(gameStateId)
                .setGameStateMessage(gsm)
                .build(),
        )

        if (shouldSendStartingPlayerPrompt) {
            // ChooseStartingPlayerReq
            messages.add(
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.ChooseStartingPlayerReq_695e)
                    .addSystemSeatIds(seatId.value)
                    .setMsgId(msgId++)
                    .setGameStateId(gameStateId)
                    .setChooseStartingPlayerReq(
                        ChooseStartingPlayerReq
                            .newBuilder()
                            .setTeamType(TeamType.Individual)
                            .addSystemSeatIds(2)
                            .addSystemSeatIds(1),
                    ).build(),
            )
        }

        return LifecycleMessages(messages, msgId, transition)
    }

    /** Prepare one initial projection revision for the fixed viewer roster. */
    internal fun initialBundles(
        viewers: List<ProjectionViewer>,
        matchId: String,
        gameStateId: Int,
        planner: LogicalSequencePlanner,
        bridge: GameBridge,
        dieRollWinner: Int = 2,
        includeStartingPlayerPrompt: Boolean = true,
    ): ViewerLifecycleMessages {
        require(viewers.isNotEmpty()) { "Initial lifecycle requires a viewer roster" }
        val connectMsgId = planner.nextMsgId()
        val dieRollMsgId = planner.nextMsgId()
        val gameStateMsgId = planner.nextMsgId()
        val hasStartingPlayerDecision =
            includeStartingPlayerPrompt && !bridge.humanVsHuman && viewers.any { it.role == ProjectionViewerRole.Player }
        val hasStartingPlayerRequest =
            hasStartingPlayerDecision &&
                viewers.any { it.seatId == SeatId(2) && it.role == ProjectionViewerRole.Player }
        val startingPlayerMsgId = if (hasStartingPlayerRequest) planner.nextMsgId() else null
        val prior = bridge.projectionStateSnapshot()
        val (messages, next) =
            bridge.editProjection(prior) { editor ->
                val snapshot = GsmSnapshot.capture(checkNotNull(bridge.getGame()), bridge, matchId, 0)
                viewers.map { viewer ->
                    val seatId = viewer.seatId
                    val shouldPrompt = hasStartingPlayerRequest && viewer.role == ProjectionViewerRole.Player && seatId == SeatId(2)
                    val deck = GsmBuilder.buildDeckMessage(bridge.getDeckGrpIds(seatId), bridge.getCommanderGrpIds(seatId))
                    val gsm =
                        GsmBuilder.buildInitialGameState(
                            matchId,
                            gameStateId,
                            bridge,
                            snapshot,
                            pendingMessageCount = if (shouldPrompt) 1 else 0,
                            viewingSeatId = seatId.value.takeIf { viewer.role.seesSeatPrivateCards } ?: -1,
                            includeStartingPlayerDecision = hasStartingPlayerDecision,
                        )
                    seedInitialProtoZones(editor, gsm, snapshot, bridge)
                    editor.viewerCursors[seatId] =
                        ViewerProjectionCursor(
                            previousSnapshot = snapshot,
                            fullState = gsm.toBuilder().setPendingMessageCount(0).build(),
                        )
                    val output =
                        buildList {
                            if (seatId ==
                                SeatId(1) ||
                                bridge.humanVsHuman
                            ) {
                                add(buildConnectResp(connectMsgId, seatId, deck, bridge.priorityPolicy(seatId).currentSettings()))
                            }
                            add(buildDieRollResults(dieRollMsgId, dieRollWinner))
                            add(
                                GREToClientMessage
                                    .newBuilder()
                                    .setType(GREMessageType.GameStateMessage_695e)
                                    .addSystemSeatIds(seatId.value)
                                    .setMsgId(gameStateMsgId)
                                    .setGameStateId(gameStateId)
                                    .setGameStateMessage(gsm)
                                    .build(),
                            )
                            if (shouldPrompt) {
                                add(
                                    GREToClientMessage
                                        .newBuilder()
                                        .setType(GREMessageType.ChooseStartingPlayerReq_695e)
                                        .addSystemSeatIds(seatId.value)
                                        .setMsgId(checkNotNull(startingPlayerMsgId))
                                        .setGameStateId(gameStateId)
                                        .setChooseStartingPlayerReq(
                                            ChooseStartingPlayerReq
                                                .newBuilder()
                                                .setTeamType(TeamType.Individual)
                                                .addSystemSeatIds(2)
                                                .addSystemSeatIds(1),
                                        ).build(),
                                )
                            }
                        }
                    seatId to output
                }
            }
        return ViewerLifecycleMessages(messages, ProjectionTransition(prior.revision, next))
    }

    /** DealHand for seat 1 (no MulliganReq) — built from game state. */

    /** DealHand only (no MulliganReq) for the given seat. */
    internal fun dealHand(
        msgId: Int,
        gameStateId: Int,
        bridge: GameBridge,
        seatId: SeatId,
        diffDeletedInstanceIds: List<Int> = emptyList(),
    ): LifecycleMessages {
        val (gsm, transition) =
            project(bridge, seatId) {
                val dealSnap = GsmSnapshot.capture(bridge.getGame()!!, bridge, "", 0)
                dealSnap to GsmBuilder.buildDealHand(bridge, gameStateId, seatId.value, dealSnap, diffDeletedInstanceIds)
            }
        val gre =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .addSystemSeatIds(seatId.value)
                .setMsgId(msgId)
                .setGameStateId(gameStateId)
                .setGameStateMessage(gsm)
                .build()
        return LifecycleMessages(listOf(gre), msgId + 1, transition)
    }

    /** One tentative redraw transition with its retired identities and next mulligan request. */
    internal fun mulliganRedraw(
        msgIdStart: Int,
        dealGameStateId: Int,
        requestGameStateId: Int,
        bridge: GameBridge,
        seatId: SeatId,
        viewers: List<ProjectionViewer>,
        mulliganCount: Int,
        numCards: Int,
    ): LifecycleMessages {
        require(viewers.any { it.seatId == seatId }) { "Redraw viewer $seatId is not registered" }
        val prior = bridge.projectionStateSnapshot()
        val (states, next) =
            bridge.editProjection(prior) { editor ->
                val dealSnapshot = GsmSnapshot.capture(checkNotNull(bridge.getGame()), bridge, "", 0)
                val redrawCardIds =
                    listOf(ZoneIds.P1_HAND, ZoneIds.P1_LIBRARY, ZoneIds.P2_HAND, ZoneIds.P2_LIBRARY)
                        .flatMap { dealSnapshot.zones[it]?.contents.orEmpty() }
                        .toSet()
                val deletedIds =
                    editor
                        .resetIdentitiesForRedraw(redrawIdentityFamily(dealSnapshot, redrawCardIds, editor))
                        .map { it.value }
                val deals =
                    viewers.associate { viewer ->
                        val viewingSeatId =
                            viewer.seatId.value.takeIf { viewer.role.seesSeatPrivateCards }
                                ?: -1
                        val deal = GsmBuilder.buildDealHand(bridge, dealGameStateId, viewingSeatId, dealSnapshot, deletedIds)
                        advanceViewerCursor(editor, viewer.seatId, dealSnapshot, deal)
                        viewer.seatId to deal
                    }
                val requestSnapshot = GsmSnapshot.capture(checkNotNull(bridge.getGame()), bridge, "", 0)
                val request = buildMulliganRequestState(requestGameStateId, requestSnapshot)
                viewers.forEach { viewer -> advanceViewerCursor(editor, viewer.seatId, requestSnapshot, request) }
                deals.getValue(seatId) to request
            }
        val deal =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .addSystemSeatIds(seatId.value)
                .setMsgId(msgIdStart)
                .setGameStateId(dealGameStateId)
                .setGameStateMessage(states.first)
                .build()
        val request =
            mulliganRequestMessages(
                msgIdStart + 1,
                requestGameStateId,
                states.second,
                mulliganCount,
                numCards,
                ProjectionTransition(prior.revision, next),
            )
        return LifecycleMessages(listOf(deal) + request.messages, request.nextMsgId, request.transition)
    }

    /** DealHand + MulliganReq bundled for seat 2 — built from game state. */
    internal fun dealHandMulliganSeat2(
        msgIdStart: Int,
        gameStateId: Int,
        bridge: GameBridge,
    ): LifecycleMessages {
        var msgId = msgIdStart
        val (gsm, transition) =
            project(bridge, SeatId(2)) {
                val deal2Snap = GsmSnapshot.capture(bridge.getGame()!!, bridge, "", 0)
                deal2Snap to
                    GsmBuilder
                        .buildDealHand(bridge, gameStateId, 2, deal2Snap)
                        .toBuilder()
                        .setPendingMessageCount(1)
                        .build()
            }
        val greGsm =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .addSystemSeatIds(2)
                .setMsgId(msgId++)
                .setGameStateId(gameStateId)
                .setGameStateMessage(gsm)
                .build()
        val greMull = GsmBuilder.buildMulliganReq(msgId++, gameStateId, 2)
        return LifecycleMessages(listOf(greGsm, greMull), msgId, transition)
    }

    /**
     * MulliganReq sequence for seat 1: GameState(decision=1) + PromptReq + MulliganReq.
     */
    internal fun mulliganReqSeat1(
        msgIdStart: Int,
        gameStateId: Int,
        bridge: GameBridge,
        mulliganCount: Int = 0,
        numCards: Int = 7,
    ): LifecycleMessages {
        // 1) Thin GSM Diff: seat 2 no longer pending, decisionPlayer=1
        val (gsm, transition) =
            project(bridge, SeatId(1)) {
                val mulliganSnap = GsmSnapshot.capture(bridge.getGame()!!, bridge, "", 0)
                mulliganSnap to buildMulliganRequestState(gameStateId, mulliganSnap)
            }
        return mulliganRequestMessages(msgIdStart, gameStateId, gsm, mulliganCount, numCards, transition)
    }

    private fun buildMulliganRequestState(
        gameStateId: Int,
        snapshot: GsmSnapshot,
    ): GameStateMessage =
        GameStateMessage
            .newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .addPlayers(PlayerMapper.buildFromSnapshot(snapshot, 2))
            .setTurnInfo(TurnInfo.newBuilder().setActivePlayer(2).setDecisionPlayer(1))
            .setPendingMessageCount(2)
            .setPrevGameStateId(gameStateId - 1)
            .addAllTimers(PlayerMapper.buildTimers())
            .setUpdate(GameStateUpdate.SendAndRecord)
            .build()

    private fun mulliganRequestMessages(
        msgIdStart: Int,
        gameStateId: Int,
        gsm: GameStateMessage,
        mulliganCount: Int,
        numCards: Int,
        transition: ProjectionTransition,
    ): LifecycleMessages {
        var msgId = msgIdStart
        val greGsm =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .addSystemSeatIds(1)
                .setMsgId(msgId++)
                .setGameStateId(gameStateId)
                .setGameStateMessage(gsm)
                .build()

        // 2) PromptReq: "who's going first" notification (promptId=37, PlayerId→seat 2)
        val grePrompt =
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.PromptReq)
                .addSystemSeatIds(2)
                .addSystemSeatIds(1)
                .setMsgId(msgId++)
                .setGameStateId(gameStateId)
                .setPrompt(
                    Prompt
                        .newBuilder()
                        .setPromptId(PromptIds.STARTING_PLAYER)
                        .addParameters(
                            PromptParameter
                                .newBuilder()
                                .setParameterName("PlayerId")
                                .setType(ParameterType.Reference_a14a)
                                .setReference(
                                    Reference
                                        .newBuilder()
                                        .setType(ReferenceType.PlayerSeatId)
                                        .setId(2),
                                ),
                        ),
                ).build()

        // 3) MulliganReq for seat 1
        val greMull = GsmBuilder.buildMulliganReq(msgId++, gameStateId, 1, numCards = numCards, mulliganCount = mulliganCount)

        return LifecycleMessages(listOf(greGsm, grePrompt, greMull), msgId, transition)
    }

    /**
     * Puzzle initial bundle — ConnectResp + Full GSM with stage=Play and
     * all zones populated from the live game state. No DieRoll, no mulligan.
     *
     * pendingMessageCount=1 because ActionsAvailableReq follows immediately.
     */
    internal fun puzzleInitialBundle(
        seatId: SeatId,
        matchId: String,
        msgIdStart: Int,
        gameStateId: Int,
        bridge: GameBridge,
    ): LifecycleMessages {
        var msgId = msgIdStart
        val messages = mutableListOf<GREToClientMessage>()

        // Role gate: only the human seat gets a ConnectResp handshake.
        if (seatId == bridge.seating.humanSeat) {
            val deck = GsmBuilder.buildDeckMessage(bridge.getDeckGrpIds(seatId), bridge.getCommanderGrpIds(seatId))
            messages.add(buildConnectResp(msgId++, seatId, deck, bridge.priorityPolicy(seatId).currentSettings()))
        }

        // Full GSM built from live game state (stage=Play, cards in zones)
        val priorProjection = bridge.projectionStateSnapshot()
        val input =
            StateFrameInputCapture(bridge, matchId, seatId.value).capture(
                game = bridge.getGame()!!,
                gameStateId = gameStateId,
                revealForSeat = null,
                events = StateFrameInputCapture.Events.CloseBundleFrame,
                priorProjectionOverride = priorProjection,
                includePreviousSnapshot = false,
            ) { _, _ -> GameStateUpdate.SendAndRecord }
        val snap = input.state.snapshot
        val fullResult =
            StateProjectionCompiler.compileOneViewer(
                environment = bridge.stateProjectionEnvironment,
                input = input.state,
                prior = input.priorProjection,
                intent = ViewerProjectionIntent.EMPTY,
            )
        val transition = fullResult.transition
        val tentative = transition.nextState.copy(revision = transition.expectedRevision)
        val (actions, nextProjection) =
            bridge.editProjection(tentative) {
                ActionMapper.buildFromSnapshot(seatId.value, snap, bridge)
            }
        val preparedTransition = transition.copy(nextState = nextProjection)
        val gsm = GsmBuilder.embedActions(fullResult.gsm, actions, GsmFrame.from(snap), recipientSeatId = seatId.value)

        messages.add(
            GREToClientMessage
                .newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .addSystemSeatIds(seatId.value)
                .setMsgId(msgId++)
                .setGameStateId(gameStateId)
                .setGameStateMessage(gsm)
                .build(),
        )

        return LifecycleMessages(messages, msgId, preparedTransition)
    }

    // --- private helpers ---

    private fun project(
        bridge: GameBridge,
        seatId: SeatId,
        block: () -> Pair<GsmSnapshot, GameStateMessage>,
    ): Pair<GameStateMessage, ProjectionTransition> {
        val prior = bridge.projectionStateSnapshot()
        val (result, next) =
            bridge.editProjection(prior) { editor ->
                val (snapshot, gsm) = block()
                advanceViewerCursor(editor, seatId, snapshot, gsm)
                gsm
            }
        return result to ProjectionTransition(prior.revision, next)
    }

    private fun advanceViewerCursor(
        editor: ProjectionState.Editor,
        seatId: SeatId,
        snapshot: GsmSnapshot,
        gsm: GameStateMessage,
    ) {
        seedProtoZones(editor, gsm)
        val prior = editor.viewerCursors[seatId] ?: ViewerProjectionCursor()
        editor.viewerCursors[seatId] =
            prior.copy(
                previousSnapshot = snapshot.withGameStateId(gsm.gameStateId),
                fullState = prior.fullState?.applyDiff(gsm),
            )
    }

    /** Keep hidden cards in the zone ledger so later public moves have a source identity. */
    private fun seedProtoZones(
        editor: ProjectionState.Editor,
        gsm: GameStateMessage,
    ) {
        gsm.zonesList.forEach { zone ->
            zone.objectInstanceIdsList.forEach { instanceId ->
                editor.protoZones[instanceId] = zone.zoneId
            }
        }
    }

    /** Keep the pre-deal hidden hand cards in their engine zone for later opening actions. */
    private fun seedInitialProtoZones(
        editor: ProjectionState.Editor,
        gsm: GameStateMessage,
        snapshot: GsmSnapshot,
        bridge: GameBridge,
    ) {
        seedProtoZones(editor, gsm)
        listOf(ZoneIds.P1_HAND, ZoneIds.P2_HAND).forEach { handZoneId ->
            snapshot.zones[handZoneId]?.contents.orEmpty().forEach { forgeCardId ->
                editor.protoZones[bridge.getOrAllocInstanceId(forgeCardId).value] = handZoneId
            }
        }
    }

    private fun redrawIdentityFamily(
        snapshot: GsmSnapshot,
        redrawCardIds: Set<ForgeCardId>,
        editor: ProjectionState.Editor,
    ): Set<ForgeCardId> =
        buildSet {
            redrawCardIds.forEach { forgeCardId ->
                add(forgeCardId)
                val bound = snapshot.boundCards[forgeCardId]
                if (bound?.altCost(KeywordAbilityIds.DISTURB) != null && snapshot.objects[forgeCardId]?.othersideGrpId != 0) {
                    add(FrameIdResolver.disturbBackForgeId(forgeCardId))
                }
                val parentInstanceId = editor.identities.peek(forgeCardId) ?: return@forEach
                bound?.linkedFaces?.forEach { face ->
                    add(FrameIdResolver.linkedFaceCompanionForgeId(parentInstanceId, face.role))
                }
            }
        }

    /** DieRollResults — [winner] seat rolls higher, random d20 values.
     *  Uses [forge.util.MyRandom] so a seeded game produces deterministic rolls. */
    private fun buildDieRollResults(
        msgId: Int,
        winner: Int = 2,
    ): GREToClientMessage {
        // Generate random d20 values; ensure winner > loser (re-roll on tie).
        // MyRandom.getRandom() respects the seed set in GameBridge.start().
        val rng = forge.util.MyRandom.getRandom()
        var high: Int
        var low: Int
        do {
            high = rng.nextInt(20) + 1
            low = rng.nextInt(20) + 1
        } while (high <= low)
        val seat1Roll = if (winner == 1) high else low
        val seat2Roll = if (winner == 2) high else low
        return GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.DieRollResultsResp_695e)
            .addSystemSeatIds(winner)
            .addSystemSeatIds(if (winner == 1) 2 else 1)
            .setMsgId(msgId)
            .setDieRollResultsResp(
                DieRollResultsResp
                    .newBuilder()
                    .addPlayerDieRolls(PlayerDieRoll.newBuilder().setSystemSeatId(1).setRollValue(seat1Roll))
                    .addPlayerDieRolls(PlayerDieRoll.newBuilder().setSystemSeatId(2).setRollValue(seat2Roll)),
            ).build()
    }

    /** ConnectResp — success + deck + default settings + version info. */
    private fun buildConnectResp(
        msgId: Int,
        seatId: SeatId,
        deckMessage: DeckMessage,
        settings: SettingsMessage,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.ConnectResp_695e)
            .addSystemSeatIds(seatId.value)
            .setMsgId(msgId)
            .setConnectResp(
                ConnectResp
                    .newBuilder()
                    .setStatus(ConnectionStatus.Success_aa9e)
                    .setProtoVer(ProtoVersion.PersistentAnnotations)
                    .setSettings(settings)
                    .setDeckMessage(deckMessage)
                    .setGrpVersion(
                        Version
                            .newBuilder()
                            .setMajorVersion(56)
                            .setMinorVersion(10)
                            .setBuildVersion(1),
                    ).setGreVersion(
                        Version
                            .newBuilder()
                            .setMajorVersion(56)
                            .setMinorVersion(10)
                            .setBuildVersion(1),
                    ),
            ).build()

    internal fun defaultSettings(): SettingsMessage = PriorityPolicyRuntime.defaultSettings()
}
