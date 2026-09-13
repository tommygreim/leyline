package leyline.match

import leyline.bridge.types.ForgeCardId
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.*
import wotc.mtgo.gre.external.messaging.Messages.Target as GreTarget

/**
 * Structured client proto compression for selected connection-boundary facts.
 *
 * This is a protocol compression seam, not a second diagnostic stream. The
 * logger uses the same event-builder contract as match lifecycle events.
 */
object Tap {
    private val log = LoggerFactory.getLogger(Tap::class.java)

    // --- Inbound (client → server) ---

    fun inbound(type: ClientToMatchServiceMessageType) {
        if (!log.isDebugEnabled) return
        // UI messages are high-frequency noise — logged at TRACE in inboundGRE() instead
        if (type == ClientToMatchServiceMessageType.ClientToGreuimessage) return
        log
            .atDebug()
            .addKeyValue("event", "client.message_received")
            .addKeyValue("message_type", type.name.removeSuffix("_f487"))
            .log("Client message received")
    }

    fun inboundGRE(
        type: ClientMessageType,
        seatId: Int,
        gsId: Int,
    ) {
        val label = type.name.removeSuffix("_097b")
        val builder =
            if (type == ClientMessageType.Uimessage_a39e) {
                if (!log.isTraceEnabled) return
                log.atTrace()
            } else {
                if (!log.isDebugEnabled) return
                log.atDebug()
            }
        builder
            .addKeyValue("event", "client.gre_received")
            .addKeyValue("message_type", label)
            .addKeyValue("seat", seatId)
            .addKeyValue("game_state_id", gsId)
            .log("Client GRE message received")
    }

    fun targetSelection(
        seatId: Int,
        gsId: Int,
        targetIdx: Int,
        targets: List<GreTarget>,
    ) {
        if (!log.isDebugEnabled) return
        log
            .atDebug()
            .addKeyValue("event", "client.target_selection")
            .addKeyValue("seat", seatId)
            .addKeyValue("game_state_id", gsId)
            .addKeyValue("target_index", targetIdx)
            .addKeyValue("target_ids", targets.map { it.targetInstanceId })
            .addKeyValue("actions", targets.map { it.legalAction.name.removeSuffix("_a1ad") })
            .log("Client target selection received")
    }

    fun outboundTemplate(
        template: String,
        matchId: String? = null,
        seat: Int? = null,
    ) {
        if (!log.isDebugEnabled) return
        val builder =
            log
                .atDebug()
                .addKeyValue("event", "client.template_sent")
                .addKeyValue("template", template)
        val correlatedBuilder = matchId?.let { builder.addKeyValue("match_id", it) } ?: builder
        val seatedBuilder = seat?.let { correlatedBuilder.addKeyValue("seat", it) } ?: correlatedBuilder
        seatedBuilder.log("Client template sent")
    }

    fun actionResult(
        matchId: String,
        seat: Int,
        actionType: ActionType,
        instanceId: Int,
        forgeCardId: ForgeCardId?,
        success: Boolean,
    ) {
        if (!log.isDebugEnabled) return
        val type = actionType.name.removeSuffix("_add3")
        val builder =
            log
                .atDebug()
                .addKeyValue("event", "client.action_result")
                .addKeyValue("match_id", matchId)
                .addKeyValue("seat", seat)
                .addKeyValue("action_type", type)
                .addKeyValue("instance_id", instanceId)
                .addKeyValue("success", success)
        val sourcedBuilder = forgeCardId?.let { builder.addKeyValue("forge_card_id", it.value) } ?: builder
        sourcedBuilder.log("Client action result")
    }
}
