package leyline.session.zones

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.haveOnTop
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import forge.game.zone.ZoneType as ForgeZoneType

/** Optional static abilities that modify the next surveil decision. */
class StaticApplicationConfirmationTest :
    SessionTest({
        val surveilWithOptionalStatic =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Enhanced Surveillance;Forest;Forest
            humanhand=Wary Thespian
            humanlibrary=Grizzly Bears;Island;Mountain;Forest;Forest
            aibattlefield=Mountain
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

        for ((accept, count) in listOf(true to 3, false to 1)) {
            session(
                "Enhanced Surveillance ${if (accept) "accepted" else "declined"} modifies surveil to $count cards",
                puzzle = surveilWithOptionalStatic,
            ) {
                holdNextOptionalAction()
                castSpellByName("Wary Thespian").shouldBeTrue()
                passUntil(maxPasses = 8) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()

                val optional = allMessages.last { it.hasOptionalActionMessage() }
                val sourceId = human.battlefield.iid("Enhanced Surveillance")
                assertSoftly {
                    optional.prompt.promptId shouldBe PromptIds.SOURCE_EFFECT_CONFIRMATION
                    optional.optionalActionMessage.prompt shouldBe optional.prompt
                    optional.optionalActionMessage.sourceId shouldBe sourceId
                    optional.optionalActionMessage.highlightIdsList shouldBe listOf(sourceId)
                    optional.prompt.parametersList.map { it.type } shouldBe listOf(ParameterType.Number, ParameterType.NonLocalizedString)
                    optional.prompt.getParameters(0).numberValue shouldBe sourceId
                    optional.prompt.getParameters(1).stringValue shouldContain "Surveil 3 instead of 1?"
                    optional.prompt.getParameters(1).stringValue shouldContain "additional two cards"
                }
                respondToOptionalAction(accept)

                passUntil(maxPasses = 8) { allMessages.any { it.hasGroupReq() } }.shouldBeTrue()
                val group = allMessages.last { it.hasGroupReq() }.groupReq
                group.context shouldBe GroupingContext.Surveil
                group.instanceIdsList shouldHaveSize count

                val first = group.instanceIdsList.first()
                cardName(first) shouldBe "Grizzly Bears"
                respondToGroupReq(awayInstanceIds = listOf(first), allInstanceIds = group.instanceIdsList)
                if (accept) {
                    val order = allMessages.last { it.hasOrderReq() }.orderReq
                    respondToOrder(order.idsList.sortedBy { cardName(it) != "Island" })
                }

                assertSoftly {
                    human.getZone(ForgeZoneType.Graveyard).cards.map { it.name } shouldBe listOf("Grizzly Bears")
                    human.library should haveOnTop("Island")
                    human.battlefield.card("Wary Thespian").name shouldBe "Wary Thespian"
                    bridge.cutCoordinator.grouping
                        .current()
                        .shouldBeNull()
                    bridge.cutCoordinator.currentBlockingInteraction().shouldBeNull()
                }
            }
        }

        for ((acceptFirst, secondBase, count) in listOf(Triple(true, 3, 5), Triple(false, 1, 3))) {
            session(
                "each optional surveil source describes the count after previous ${if (acceptFirst) "accepted" else "declined"} modifiers",
                puzzle =
                    surveilWithOptionalStatic.replace(
                        "Enhanced Surveillance;Forest",
                        "Enhanced Surveillance;Enhanced Surveillance;Forest",
                    ),
            ) {
                holdNextOptionalAction()
                castSpellByName("Wary Thespian").shouldBeTrue()
                passUntil(maxPasses = 8) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
                respondToOptionalAction(acceptFirst)
                passUntil(maxPasses = 8) { allMessages.any { it.hasGroupReq() } }.shouldBeTrue()

                val confirmations = allMessages.filter { it.hasOptionalActionMessage() }.distinctBy { it.msgId }
                val group = allMessages.last { it.hasGroupReq() }.groupReq
                assertSoftly {
                    confirmations shouldHaveSize 2
                    confirmations.map { it.optionalActionMessage.sourceId }.distinct() shouldHaveSize 2
                    confirmations
                        .first()
                        .prompt
                        .getParameters(1)
                        .stringValue shouldContain "Surveil 3 instead of 1?"
                    confirmations
                        .last()
                        .prompt
                        .getParameters(1)
                        .stringValue shouldContain "Surveil $count instead of $secondBase?"
                    group.context shouldBe GroupingContext.Surveil
                    group.instanceIdsList shouldHaveSize count
                }
                respondToGroupReq(awayInstanceIds = listOf(group.instanceIdsList.first()), allInstanceIds = group.instanceIdsList)
                val order = allMessages.last { it.hasOrderReq() }.orderReq
                respondToOrder(order.idsList.sortedBy { cardName(it) != "Island" })
                assertSoftly {
                    human.library should haveOnTop("Island")
                    bridge.cutCoordinator.grouping
                        .current()
                        .shouldBeNull()
                    bridge.cutCoordinator.currentBlockingInteraction().shouldBeNull()
                }
            }
        }
    })
