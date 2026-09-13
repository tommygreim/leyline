package leyline.match

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToMatchServiceMessageType
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.Target

class TapTest :
    FunSpec({
        tags(UnitTag)

        test("protocol compression uses stable structured events") {
            val logger = LoggerFactory.getLogger(Tap::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val previousLevel = logger.level
            logger.level = Level.DEBUG
            logger.addAppender(appender)

            try {
                Tap.inbound(ClientToMatchServiceMessageType.AuthenticateRequest_f487)
                Tap.inboundGRE(ClientMessageType.PerformActionResp_097b, 1, 12)
                Tap.targetSelection(
                    1,
                    13,
                    1,
                    listOf(
                        Target
                            .newBuilder()
                            .setTargetInstanceId(42)
                            .setLegalAction(SelectAction.Select_a1ad)
                            .build(),
                    ),
                )
                Tap.outboundTemplate("initial_bundle", matchId = "match-one", seat = 1)
                Tap.actionResult("match-one", 1, ActionType.Play_add3, 7, ForgeCardId(53), true)

                assertSoftly {
                    appender.list.map { it.message } shouldContainExactly
                        listOf(
                            "Client message received",
                            "Client GRE message received",
                            "Client target selection received",
                            "Client template sent",
                            "Client action result",
                        )
                    appender.list.map { it.keyValuePairs.map { pair -> pair.key } } shouldContainExactly
                        listOf(
                            listOf("event", "message_type"),
                            listOf("event", "message_type", "seat", "game_state_id"),
                            listOf("event", "seat", "game_state_id", "target_index", "target_ids", "actions"),
                            listOf("event", "template", "match_id", "seat"),
                            listOf("event", "match_id", "seat", "action_type", "instance_id", "success", "forge_card_id"),
                        )
                    appender.list
                        .first()
                        .keyValuePairs
                        .first()
                        .value shouldBe "client.message_received"
                    val actionResult = appender.list.last()
                    actionResult.keyValuePairs.first().value shouldBe "client.action_result"
                    actionResult.keyValuePairs.associate { it.key to it.value }["match_id"] shouldBe "match-one"
                    actionResult.keyValuePairs.associate { it.key to it.value }["seat"] shouldBe 1
                    val target = appender.list[2]
                    target.keyValuePairs.associate { it.key to it.value }["target_ids"] shouldBe listOf(42)
                    val template = appender.list[3]
                    template.keyValuePairs.associate { it.key to it.value } shouldBe
                        mapOf(
                            "event" to "client.template_sent",
                            "template" to "initial_bundle",
                            "match_id" to "match-one",
                            "seat" to 1,
                        )
                }
            } finally {
                logger.detachAppender(appender)
                logger.level = previousLevel
                appender.stop()
            }
        }
    })
