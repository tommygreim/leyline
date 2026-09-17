package leyline.game.bundle

import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationBuilder
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.PlayerMapper
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.game.mapping.ZoneMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Frozen snapshot of turn/phase/seat state for proto construction.
 * No bridge ref, no game ref — plain values.
 *
 * Created once per bundle call via [from], then threaded through
 * GSM builders that need seat/phase info.
 */
data class GsmFrame(
    val activeSeat: Int,
    val prioritySeat: Int,
    val turnNumber: Int,
    val phase: Phase,
    val step: Step,
    val nextPhase: Phase = Phase.None_a549,
    val nextStep: Step = Step.None_a2cb,
) {
    /** Build a [TurnInfo] proto from this frame's fields. */
    fun turnInfo(): TurnInfo =
        TurnInfo
            .newBuilder()
            .setPhase(phase)
            .setStep(step)
            .setTurnNumber(turnNumber)
            .setActivePlayer(activeSeat)
            .setPriorityPlayer(prioritySeat)
            .setDecisionPlayer(prioritySeat)
            .setNextPhase(nextPhase)
            .setNextStep(nextStep)
            .build()

    /** Build a PhaseOrStepModified annotation, assigning an ID from [idSource]. */
    fun phaseAnnotation(idSource: () -> Int): AnnotationInfo =
        AnnotationBuilder
            .phaseOrStepModified(SeatId(activeSeat), phase.number, step.number)
            .toBuilder()
            .setId(idSource())
            .build()

    companion object {
        /** Build a frame from a pre-captured [GsmSnapshot]. */
        fun from(snap: GsmSnapshot): GsmFrame =
            GsmFrame(
                activeSeat = snap.phase.activePlayer.value,
                prioritySeat = snap.phase.priorityPlayer?.value ?: snap.phase.activePlayer.value,
                turnNumber = snap.phase.turn.coerceAtLeast(1),
                phase = PlayerMapper.mapPhase(snap.phase.phase),
                step = PlayerMapper.mapStep(snap.phase.phase),
                nextPhase = PlayerMapper.mapPhase(snap.phase.nextPhase),
                nextStep = PlayerMapper.mapStep(snap.phase.nextPhase),
            )
    }
}

/**
 * Lifecycle GameStateMessage factories — pre-game, mulligan, phase transition,
 * game-over, and utility GSMs.
 *
 * Pure proto construction. No session state, no bridge mutation. The main
 * state-projection pipeline lives in [leyline.game.mapping.StateProjectionCompiler];
 * this class covers everything else.
 */
object GsmBuilder {
    fun buildTransitionGameInfo(matchId: String): GameInfo =
        GameInfo
            .newBuilder()
            .setMatchID(matchId)
            .setGameNumber(1)
            .setStage(GameStage.Play_a920)
            .setType(GameType.Duel)
            .setVariant(GameVariant.Normal)
            .setMatchState(MatchState.GameInProgress)
            .setMatchWinCondition(MatchWinCondition.SingleElimination)
            .setSuperFormat(SuperFormat.Constructed)
            .setMulliganType(MulliganType.London)
            .build()

    @Suppress("UnusedPrivateProperty")
    private val log = LoggerFactory.getLogger(GsmBuilder::class.java)

    /**
     * Build a DeckMessage from a list of grpIds (one per card in the deck).
     */
    fun buildDeckMessage(
        deckGrpIds: List<Int>,
        commanderGrpIds: List<Int> = emptyList(),
    ): DeckMessage {
        val builder = DeckMessage.newBuilder()
        deckGrpIds.forEach { builder.addDeckCards(it) }
        commanderGrpIds.forEach { builder.addCommanderCards(it) }
        return builder.build()
    }

    /**
     * Build a DealHand GSM (Diff) for a seat at mulligan time.
     * Shows both players' hand/library zones with card objects for the target seat's hand.
     */
    fun buildDealHand(
        bridge: GameBridge,
        gameStateId: Int,
        seatId: Int,
        snap: GsmSnapshot,
        diffDeletedInstanceIds: List<Int> = emptyList(),
    ): GameStateMessage {
        val human = bridge.getPlayer(SeatId(1))
        val ai = bridge.getPlayer(SeatId(2))

        val zones = mutableListOf<ZoneInfo>()
        val gameObjects = mutableListOf<GameObjectInfo>()

        // Both seats' hand + library only (client expects no graveyard at deal-hand).
        // gyZoneId=null omits the graveyard zone — protocol shape requires exactly 4 zones here.
        // Only include GameObjectInfo for the viewing seat's hand — opponent's hand
        // cards appear in objectInstanceIds (for count) but render face-down.
        if (human != null) {
            ZoneMapper.addPlayerZonesFromSnapshot(
                SeatId(1),
                snap,
                bridge.stateProjectionEnvironment,
                bridge::getOrAllocInstanceId,
                zones,
                gameObjects,
                ZoneIds.P1_HAND,
                ZoneIds.P1_LIBRARY,
                viewingSeatId = seatId,
            )
        }
        if (ai != null) {
            ZoneMapper.addPlayerZonesFromSnapshot(
                SeatId(2),
                snap,
                bridge.stateProjectionEnvironment,
                bridge::getOrAllocInstanceId,
                zones,
                gameObjects,
                ZoneIds.P2_HAND,
                ZoneIds.P2_LIBRARY,
                viewingSeatId = seatId,
            )
        }

        // Players — both have pendingMessageType: MulliganResp during mulligan
        val player1 =
            PlayerMapper
                .buildFromSnapshot(snap, 1)
                .toBuilder()
                .setPendingMessageType(ClientMessageType.MulliganResp_097b)
                .build()
        val player2 =
            PlayerMapper
                .buildFromSnapshot(snap, 2)
                .toBuilder()
                .setPendingMessageType(ClientMessageType.MulliganResp_097b)
                .build()

        // activePlayer=2 (seat 2 won die roll in template), decisionPlayer=2
        val turnInfo =
            TurnInfo
                .newBuilder()
                .setActivePlayer(2)
                .setDecisionPlayer(2)

        // Build actions for the viewing seat's opening hand (Cast/Play from hand)
        val actions =
            if (seatId > 0) {
                ActionMapper.buildFromSnapshot(seatId, snap, bridge)
            } else {
                ActionsAvailableReq.getDefaultInstance()
            }

        val gsm =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gameStateId)
                .addPlayers(player1)
                .addPlayers(player2)
                .setTurnInfo(turnInfo)
                .addAllZones(zones.sortedBy { it.zoneId })
                .addAllGameObjects(gameObjects)
                .addAllDiffDeletedInstanceIds(diffDeletedInstanceIds)
                .addAnnotations(
                    AnnotationInfo
                        .newBuilder()
                        .setId(49)
                        .setAffectorId(2)
                        .addAffectedIds(2)
                        .addType(AnnotationType.NewTurnStarted),
                ).setPrevGameStateId(gameStateId - 1)
                .setUpdate(GameStateUpdate.SendAndRecord)

        // Embed stripped actions matching expected deal-hand shape
        for (action in actions.actionsList) {
            gsm.addActions(
                ActionInfo
                    .newBuilder()
                    .setSeatId(seatId)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }

        return gsm.build()
    }

    /**
     * Build a MulliganReq GRE message.
     */
    fun buildMulliganReq(
        msgId: Int,
        gameStateId: Int,
        seatId: Int,
        numCards: Int = 7,
        mulliganCount: Int = 0,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.MulliganReq_aa0d)
            .addSystemSeatIds(seatId)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .setPrompt(
                Prompt
                    .newBuilder()
                    .setPromptId(PromptIds.MULLIGAN)
                    .addParameters(
                        PromptParameter
                            .newBuilder()
                            .setParameterName("NumberOfCards")
                            .setType(ParameterType.Number)
                            .setNumberValue(numCards),
                    ),
            ).setMulliganReq(
                MulliganReq
                    .newBuilder()
                    .setMulliganType(MulliganType.London)
                    .setFreeMulliganCount(0)
                    .setMulliganCount(mulliganCount),
            ).build()

    /**
     * Build a GroupReq GRE message for London mulligan tuck.
     *
     * Client expects TWO groupSpecs:
     *   1. Keep group: (handSize - cardsToTuck) cards stay in Hand/Top
     *   2. Tuck group: cardsToTuck cards go to Library/Bottom
     * groupType=Ordered because the player must order cards going to bottom.
     */
    fun buildGroupReq(
        msgId: Int,
        gameStateId: Int,
        seatId: Int,
        handInstanceIds: List<Int>,
        cardsToTuck: Int,
    ): GREToClientMessage {
        val keepCount = handInstanceIds.size - cardsToTuck
        return GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.GroupReq_695e)
            .addSystemSeatIds(seatId)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .setPrompt(
                Prompt
                    .newBuilder()
                    .setPromptId(PromptIds.GROUP_SCRY)
                    .addParameters(cardIdPromptParameter()),
            ).setGroupReq(
                GroupReq
                    .newBuilder()
                    .addAllInstanceIds(handInstanceIds)
                    .addGroupSpecs(
                        GroupSpecification
                            .newBuilder()
                            .setLowerBound(keepCount)
                            .setUpperBound(keepCount)
                            .setZoneType(ZoneType.Hand)
                            .setSubZoneType(SubZoneType.Top),
                    ).addGroupSpecs(
                        GroupSpecification
                            .newBuilder()
                            .setLowerBound(cardsToTuck)
                            .setUpperBound(cardsToTuck)
                            .setZoneType(ZoneType.Library)
                            .setSubZoneType(SubZoneType.Bottom),
                    ).setGroupType(GroupType.Ordered)
                    .setContext(GroupingContext.LondonMulligan)
                    .setSourceId(seatId),
            ).setAllowCancel(AllowCancel.No_a526)
            .build()
    }

    /**
     * Build the initial Full [GameStateMessage] for the connection bundle.
     * Zones show the pre-deal state: all cards in library, hands empty.
     * No game objects (cards are face-down).
     */
    fun buildInitialGameState(
        matchId: String,
        gameStateId: Int,
        bridge: GameBridge,
        snap: GsmSnapshot,
        pendingMessageCount: Int = 0,
        viewingSeatId: Int = 0,
        includeStartingPlayerDecision: Boolean = true,
    ): GameStateMessage {
        val isBrawl = bridge.isBrawlOrCommander
        val gameVariant = if (isBrawl) GameVariant.Brawl else GameVariant.Normal
        val freeMulliganCount = if (isBrawl) 1 else 0

        val deckConstraints =
            if (isBrawl) {
                DeckConstraintInfo
                    .newBuilder()
                    .setMinDeckSize(58)
                    .setMaxDeckSize(59)
                    .setMaxSideboardSize(1)
                    .setMinCommanderSize(1)
                    .setMaxCommanderSize(1)
            } else {
                DeckConstraintInfo
                    .newBuilder()
                    .setMinDeckSize(60)
                    .setMaxDeckSize(250)
                    .setMaxSideboardSize(15)
            }

        val gameInfo =
            GameInfo
                .newBuilder()
                .setMatchID(matchId)
                .setGameNumber(1)
                .setStage(GameStage.Start_a920)
                .setType(GameType.Duel)
                .setVariant(gameVariant)
                .setMatchState(MatchState.GameInProgress)
                .setMatchWinCondition(MatchWinCondition.SingleElimination)
                .setSuperFormat(SuperFormat.Constructed)
                .setMulliganType(MulliganType.London)
                .setFreeMulliganCount(freeMulliganCount)
                .setDeckConstraintInfo(deckConstraints)

        // Seat 2 has pending ChooseStartingPlayerResp during the normal two-seat handshake.
        val player1 = PlayerMapper.buildFromSnapshot(snap, 1)
        val player2 = PlayerMapper.buildFromSnapshot(snap, 2)
        val player2WithPendingDecision =
            if (includeStartingPlayerDecision) {
                player2.toBuilder().setPendingMessageType(ClientMessageType.ChooseStartingPlayerResp_097b).build()
            } else {
                player2
            }

        val zones = mutableListOf<ZoneInfo>()
        // Shared zones (9)
        zones.add(ZoneMapper.makeZone(ZoneIds.REVEALED_P1, ZoneType.Revealed, 1, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.REVEALED_P2, ZoneType.Revealed, 2, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.SUPPRESSED, ZoneType.Suppressed, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.PENDING, ZoneType.Pending, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.COMMAND, ZoneType.Command, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.STACK, ZoneType.Stack, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.EXILE, ZoneType.Exile, 0, Visibility.Public))
        zones.add(ZoneMapper.makeZone(ZoneIds.LIMBO, ZoneType.Limbo, 0, Visibility.Public))
        // Per-player zones (4 each = 8)
        addInitialPlayerZones(snap, bridge, zones, viewingSeatId)

        // Brawl: populate zone 26 with commander cards as full game objects.
        // Commanders start in the command zone, not the library — the client
        // needs both objectInstanceIds on the zone AND GameObjectInfo entries.
        val gameObjects = mutableListOf<GameObjectInfo>()
        if (isBrawl) {
            ZoneMapper.addSharedZoneCardsFromSnapshot(
                snap = snap,
                arenaZoneId = ZoneIds.COMMAND,
                environment = bridge.stateProjectionEnvironment,
                instanceIdLookup = bridge::getOrAllocInstanceId,
                zones = zones,
                gameObjects = gameObjects,
            )
        }

        val builder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(gameStateId)
                .setGameInfo(gameInfo)
                // Full-state consumers require TurnInfo even before any turn or decision begins.
                .setTurnInfo(TurnInfo.newBuilder())
                .addTeams(
                    TeamInfo
                        .newBuilder()
                        .setId(1)
                        .addPlayerIds(1)
                        .setStatus(TeamStatus.InGame_a458),
                ).addTeams(
                    TeamInfo
                        .newBuilder()
                        .setId(2)
                        .addPlayerIds(2)
                        .setStatus(TeamStatus.InGame_a458),
                ).addPlayers(player1)
                .addPlayers(player2WithPendingDecision)
                .addAllZones(zones.sortedBy { it.zoneId })
                .addAllGameObjects(gameObjects)
                .addAllTimers(PlayerMapper.buildTimers())
                .setUpdate(GameStateUpdate.SendAndRecord)
        if (includeStartingPlayerDecision) builder.setTurnInfo(TurnInfo.newBuilder().setDecisionPlayer(2))
        if (pendingMessageCount > 0) builder.setPendingMessageCount(pendingMessageCount)
        return builder.build()
    }

    private fun addInitialPlayerZones(
        snap: GsmSnapshot,
        bridge: GameBridge,
        zones: MutableList<ZoneInfo>,
        viewingSeatId: Int,
    ) {
        if (bridge.getPlayer(SeatId(1)) != null) {
            ZoneMapper.addInitialPlayerZonesFromSnapshot(
                SeatId(1),
                snap,
                bridge::getOrAllocInstanceId,
                zones,
                ZoneIds.P1_HAND,
                ZoneIds.P1_LIBRARY,
                ZoneIds.P1_GRAVEYARD,
                ZoneIds.P1_SIDEBOARD,
                viewingSeatId,
            )
        }
        if (bridge.getPlayer(SeatId(2)) != null) {
            ZoneMapper.addInitialPlayerZonesFromSnapshot(
                SeatId(2),
                snap,
                bridge::getOrAllocInstanceId,
                zones,
                ZoneIds.P2_HAND,
                ZoneIds.P2_LIBRARY,
                ZoneIds.P2_GRAVEYARD,
                ZoneIds.P2_SIDEBOARD,
                viewingSeatId,
            )
        }
    }

    /**
     * Build a Diff GameStateMessage for phase transitions (Beginning→Main1 sequence).
     * Matches expected protocol shape: stage=Play, phase/step, life totals, timers.
     * Optionally embeds actions (for the Main1 state).
     */
    fun buildTransitionState(
        gameStateId: Int,
        prevGameStateId: Int,
        matchId: String,
        bridge: GameBridge,
        frame: GsmFrame,
        snap: GsmSnapshot,
        isStageTransition: Boolean = false,
        actions: ActionsAvailableReq? = null,
        actionSeatId: Int = 0,
    ): GameStateMessage {
        val builder =
            GameStateMessage
                .newBuilder()
                .setType(GameStateType.Diff)
                .setGameStateId(gameStateId)
                .setPrevGameStateId(prevGameStateId)
                .setTurnInfo(frame.turnInfo())
                .addPlayers(PlayerMapper.buildFromSnapshot(snap, 1))
                .addPlayers(PlayerMapper.buildFromSnapshot(snap, 2))
                .addAnnotations(frame.phaseAnnotation { bridge.nextAnnotationId() }) // phase change
                .addAnnotations(frame.phaseAnnotation { bridge.nextAnnotationId() }) // step change
                .addAllTimers(PlayerMapper.buildTimers())
                .setUpdate(GameStateUpdate.SendHiFi)

        if (isStageTransition) {
            builder.setGameInfo(buildTransitionGameInfo(matchId))
        }

        // Embed stripped-down actions for display only — SendHiFi never carries
        // pendingMessageCount, the real decision-pending signal lives on the
        // SendAndRecord commit message that follows later in the bundle.
        // actionSeatId = recipient seat (human), not necessarily the active player.
        if (actions != null) {
            val embedSeat = if (actionSeatId != 0) actionSeatId else frame.activeSeat
            for (action in actions.actionsList) {
                builder.addActions(
                    ActionInfo
                        .newBuilder()
                        .setSeatId(embedSeat)
                        .setAction(ActionMapper.stripActionForGsm(action)),
                )
            }
        }

        return builder.build()
    }

    /** Empty Diff used as priority-pass marker in the double-diff pattern. */
    fun buildEmptyDiff(gameStateId: Int): GameStateMessage =
        GameStateMessage
            .newBuilder()
            .setType(GameStateType.Diff)
            .setGameStateId(gameStateId)
            .addAllTimers(PlayerMapper.buildTimers())
            .setUpdate(GameStateUpdate.SendHiFi)
            .build()

    /**
     * Embed stripped-down actions into an already-built GSM (post-realloc).
     *
     * Used by [BundleBuilder.postAction] to re-embed actions after zone-transfer
     * instanceId reallocation and phase annotations have been applied.
     */
    fun embedActions(
        gsm: GameStateMessage,
        actions: ActionsAvailableReq,
        frame: GsmFrame,
        recipientSeatId: Int = 0,
    ): GameStateMessage {
        val builder =
            gsm
                .toBuilder()
                .setPendingMessageCount(1)
        val seatForActions = if (recipientSeatId != 0) recipientSeatId else frame.prioritySeat
        for (action in actions.actionsList) {
            builder.addActions(
                ActionInfo
                    .newBuilder()
                    .setSeatId(seatForActions)
                    .setAction(ActionMapper.stripActionForGsm(action)),
            )
        }
        return builder.build()
    }
}
