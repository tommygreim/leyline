package leyline.match

import leyline.bridge.coord.MulliganRedrawFacts
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Mulligan flow delegate for [MatchHandler]. Owns the per-seat mulligan state
 * (hand grpIds, mull count, London tuck) and is testable without a live Netty
 * channel.
 *
 * Uses provider lambdas for session/matchId/seatId to avoid holding
 * [MatchConnection] references directly. Cross-seat lookups (e.g. sending the
 * opponent's DealHand) go through [MatchRegistry.getConnection] → peer's
 * [MulliganHandler], which requires both seat handlers to be registered first.
 */
class MulliganHandler(
    private val engineSettings: EngineSettings,
    private val registry: MatchRegistry,
    private val sessionProvider: () -> GameOps?,
    private val matchIdProvider: () -> String,
    private val seatIdProvider: () -> SeatId,
) {
    private val log = LoggerFactory.getLogger(MulliganHandler::class.java)

    var mulliganCount = 0
        private set

    var seat1Hand: List<Int> = emptyList()
    var seat2Hand: List<Int> = emptyList()

    private val session get() = sessionProvider()
    private val matchId get() = matchIdProvider()
    private val seatId: SeatId get() = seatIdProvider()

    /** Release the engine only after both independent player sessions have initial state. */
    fun startPlayersIfReady() {
        val bridge = registry.getMatch(matchId)?.bridge ?: return
        if (!bridge.humanVsHuman) return
        val connections = listOf(SeatId(1), SeatId(2)).map { registry.getConnection(matchId, it) ?: return }
        if (connections.any { it.session !is MatchSession }) return
        connections.forEach { it.armRuntimeDeliveryObserver() }
        bridge.releaseHumanStartup()
    }

    /** Progress the automatic Familiar startup once both seats have live sessions. */
    fun startFamiliarIfReady() {
        val match = registry.getMatch(matchId)
        if (match?.bridge?.isPuzzle == true) {
            return
        }

        val bridge = match?.bridge ?: return
        val playerConnection = registry.getConnection(matchId, bridge.seating.humanSeat) ?: return
        val familiarConnection = registry.getConnection(matchId, bridge.seating.familiarSeat) ?: return
        if (playerConnection.session !is MatchSession || familiarConnection.session !is FamiliarSession) return
        if (!bridge.cutCoordinator.lifecycle.claimFamiliarStartup()) return

        familiarConnection.mulliganHandler.publishFamiliarStartup(playerConnection, bridge)
    }

    private fun publishFamiliarStartup(
        playerConnection: MatchConnection,
        bridge: GameBridge,
    ) {
        if (engineSettings.skipMulligan) {
            log.info("Match Door GRE: skipMulligan — bypassing mulligan phase")
            sendDealHandViaConnection(session, bridge)
            playerConnection.mulliganHandler.sendDealHandPublic()
            playerConnection.session?.onMulliganKeep()
        } else {
            log.info("Match Door GRE: seat {} chose starting player", seatId.value)
            sendDealHandAndMulligan()
            playerConnection.mulliganHandler.sendDealHandPublic()
            playerConnection.mulliganHandler.sendMulliganReq()
            // Keep runtime delivery armed while the client is deciding. If the
            // engine's mulligan timeout auto-keeps first, its opening actions must
            // still reach the client without waiting for a later request.
            playerConnection.armRuntimeDeliveryObserver()
        }
    }

    /** Handle MulliganResp — keep or mulligan decision. */
    fun onMulliganResp(greMsg: ClientToGREMessage) {
        val s = session ?: return
        val bridge = s.gameBridge
        if (bridge.isPuzzle) {
            log.info("Match Door GRE: ignoring MulliganResp for puzzle")
            return
        }
        if (!bridge.isInteractiveSeat(seatId)) return // Familiar — no action

        val decision = greMsg.mulliganResp.decision
        if (bridge.humanVsHuman) {
            when (decision) {
                MulliganOption.AcceptHand -> bridge.submitKeep(seatId)
                MulliganOption.Mulligan -> bridge.submitMull(seatId)
                else -> Unit
            }
            return
        }
        log.info("Match Door GRE: seat {} mulligan decision={}", seatId.value, decision)

        when (decision) {
            MulliganOption.AcceptHand -> {
                if (!bridge.submitKeep(seatId)) {
                    // The engine may have auto-kept after the mulligan timeout while
                    // the client was still showing the hand. Resume delivery for
                    // that already-completed startup instead of dropping the stale
                    // keep response on the floor.
                    if (bridge.mulliganBridge(seatId).pendingPrompt() == null) {
                        log.info("Match Door GRE: seat {} keep arrived after auto-keep", seatId.value)
                        s.onMulliganKeep()
                    }
                    return
                }
                bridge.awaitPriority()
                s.onMulliganKeep()
            }
            MulliganOption.Mulligan,
            MulliganOption.None_a2b7,
            MulliganOption.UNRECOGNIZED,
            -> {
                if (!bridge.submitMull(seatId)) return
                mulliganCount++
                seat1Hand = bridge.getHandGrpIds(SeatId(1))
                sendMulliganRedraw(MulliganRedrawFacts(reportedMulliganCount = 0, numCards = seat1Hand.size))
            }
        }
    }

    /** Handle GroupResp — London tuck. */
    fun onGroupResp(greMsg: ClientToGREMessage) {
        val s = session ?: return
        val bridge = s.gameBridge
        if (!bridge.isInteractiveSeat(seatId)) return

        val groups = greMsg.groupResp.groupsList
        val tuckIds = if (groups.size >= 2) groups[1].idsList else groups.firstOrNull()?.idsList ?: emptyList()
        log.info("Match Door GRE: seat {} GroupResp tuck {} cards", seatId.value, tuckIds.size)
        val handCards = bridge.getHandCards(seatId)
        val tuckCards =
            tuckIds.mapNotNull { iid ->
                val forgeId = bridge.getForgeCardId(InstanceId(iid))?.value
                handCards.firstOrNull { it.id == forgeId }
            }
        if (bridge.humanVsHuman) {
            val pending = bridge.mulliganBridge(seatId).pendingPrompt() ?: return
            if (tuckCards.size != pending.cardsToTuck || tuckIds.size != tuckIds.distinct().size || tuckCards.size != tuckIds.size) return
            bridge.submitTuck(seatId, tuckCards)
            return
        }
        bridge.submitTuck(seatId, tuckCards)
        bridge.awaitPriority()
        s.onMulliganKeep()
    }

    // --- Senders ---

    /**
     * DealHand via any SessionOps (works for both MatchSession and FamiliarSession).
     * Used when ChooseStartingPlayerResp arrives on the Familiar channel with skipMulligan.
     */
    private fun sendDealHandViaConnection(
        s: SessionOps?,
        bridge: GameBridge?,
    ) {
        if (s == null || bridge == null) return
        bridge.cutCoordinator.lifecycle.publishDealHand(seatId)
        deliverTemplate(s, bridge, "deal_hand")
    }

    /** DealHand only — public for cross-connection calls. */
    fun sendDealHandPublic() {
        sendDealHand()
    }

    /** DealHand only (no MulliganReq) for this handler's seat. */
    private fun sendDealHand(diffDeletedInstanceIds: List<Int> = emptyList()) {
        val s = session ?: return
        val bridge = s.gameBridge
        bridge.cutCoordinator.lifecycle.publishDealHand(seatId, diffDeletedInstanceIds)
        deliverTemplate(s, bridge, "deal_hand")
    }

    /**
     * MulliganReq sequence for seat 1.
     *
     * @param reportedMulliganCount mulliganCount for the proto (default: internal counter).
     * @param numCards NumberOfCards prompt value (default: 7 for London).
     */
    fun sendMulliganReq(
        reportedMulliganCount: Int = mulliganCount,
        numCards: Int = 7,
    ) {
        val s = session ?: return
        val bridge = s.gameBridge
        bridge.cutCoordinator.lifecycle.publishMulliganRequest(seatId, reportedMulliganCount, numCards)
        deliverTemplate(s, bridge, "mulligan_request")
    }

    private fun sendMulliganRedraw(facts: MulliganRedrawFacts) {
        val s = session ?: return
        val bridge = s.gameBridge
        bridge.cutCoordinator.lifecycle.publishMulliganRedraw(seatId, facts)
        deliverTemplate(s, bridge, "mulligan_redraw")
    }

    /** DealHand + MulliganReq bundled (for seat 2). */
    private fun sendDealHandAndMulligan() {
        val s = session ?: return
        val bridge = s.gameBridge
        bridge.cutCoordinator.lifecycle.publishDealHandMulligan(seatId)
        deliverTemplate(s, bridge, "deal_hand_and_mulligan_request")
    }

    private fun deliverTemplate(
        s: SessionOps,
        bridge: GameBridge,
        template: String,
    ) {
        s.deliverLifecycle(bridge)
        Tap.outboundTemplate(template, matchId = matchId, seat = seatId.value)
    }
}
