package leyline.session.costs

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

/** Session proof for the grounded Hopeful Initiate GatherCounters payment row. */
class GatherCountersSessionTest :
    SessionTest({
        session(
            "Hopeful Initiate gathers counters across creatures before destroying target",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true

                humanbattlefield=Hopeful Initiate|Counters:P1P1=1;Hopeful Initiate|Counters:P1P1=1;Plains;Plains;Plains
                humanlibrary=Plains;Plains;Plains
                aibattlefield=Sol Ring
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 3,
        ) {
            val sources = human.getZone(ZoneType.Battlefield).cards.filter { it.name == "Hopeful Initiate" }
            sources.size shouldBe 2
            val sourceIids = sources.map { human.battlefield.iid(it) }
            val targetIid = ai.battlefield.iid("Sol Ring")
            val before = messageSnapshot()

            activateAbility("Hopeful Initiate") shouldBe true
            passUntil(maxPasses = 20) { messagesSince(before).any { it.hasSelectTargetsReq() } }
                .also { found ->
                    if (!found) error("No SelectTargetsReq; messages=${messagesSince(before).map { it.type }}")
                }
            selectTargets(listOf(targetIid))
            passUntil(maxPasses = 20) { messagesSince(before).any { it.hasPayCostsReq() } }
                .also { found ->
                    if (!found) error("No PayCostsReq; messages=${messagesSince(before).map { it.type }}")
                }

            val prompt = messagesSince(before).last { it.hasPayCostsReq() }
            val payCosts = prompt.payCostsReq
            val gather = payCosts.effectCostReq.gatherReq
            val destination = gather.destinationId
            val cardId =
                prompt.prompt.parametersList
                    .single { it.parameterName == "CardId" }
                    .numberValue
            val stackAbilityIids =
                messagesSince(before)
                    .gameStateMessages()
                    .flatMap { it.gameObjectsList }
                    .filter { it.type == GameObjectType.Ability }
                    .map { it.instanceId }

            assertSoftly {
                payCosts.effectCostReq.effectCostType shouldBe EffectCostType.GatherCounters
                payCosts.paymentActions.actionsList shouldBe emptyList()
                payCosts.effectCostReq.gatherReq.sourcesList
                    .map { it.sourceId } shouldContainExactlyInAnyOrder sourceIids
                payCosts.effectCostReq.gatherReq.sourcesList
                    .map { it.maxAmount } shouldBe listOf(1, 1)
                gather.amountToGather shouldBe 2
                cardId shouldBe destination
                stackAbilityIids shouldContain destination
                prompt.prompt.promptId shouldBe PromptIds.GATHER_COUNTERS
                prompt.allowCancel shouldBe wotc.mtgo.gre.external.messaging.Messages.AllowCancel.Abort
                prompt.allowUndo shouldBe false
            }

            respondToGatherCounters(sourceIids.map { it to 1 })
            passUntil(maxPasses = 20) {
                ai.getZone(ZoneType.Graveyard).cards.any { it.name == "Sol Ring" }
            }

            assertSoftly {
                sources.forEach { it.getCounters(forge.game.card.CounterEnumType.P1P1) shouldBe 0 }
                ai.getZone(ZoneType.Battlefield).cards.none { it.name == "Sol Ring" } shouldBe true
                ai.getZone(ZoneType.Graveyard).cards.any { it.name == "Sol Ring" } shouldBe true
                bridge.cutCoordinator.oneShotPayCosts
                    .current() shouldBe null
                allMessages.none { it.hasSelectNReq() && it.selectNReq.idsList.any { id -> id in sourceIids } } shouldBe true
            }
        }
    })
