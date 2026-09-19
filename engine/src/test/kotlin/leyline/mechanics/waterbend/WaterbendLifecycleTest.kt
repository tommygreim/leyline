package leyline.mechanics.waterbend

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.codes.DetailKeys
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.ClientAccumulator
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import leyline.testkit.performAction
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import wotc.mtgo.gre.external.messaging.Messages.OrderingType
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq

class WaterbendLifecycleTest :
    SessionTest({
        session(
            "Giant Koi pays activated Waterbend through PayCostsReq",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Giant Koi;Coral Merfolk;Grizzly Bears;Sol Ring;Island;Island;Island
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val merfolkIid = human.battlefield.iid("Coral Merfolk")
            val bearIid = human.battlefield.iid("Grizzly Bears")
            val solRingIid = human.battlefield.iid("Sol Ring")
            val actions = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList
            val gsmActions =
                allMessages
                    .last { it.hasGameStateMessage() }
                    .gameStateMessage
                    .actionsList
                    .map { it.action }

            withClue(actions.map { "${it.actionType}:${it.abilityGrpId}:${it.grpId}" }) {
                actions.any { it.actionType == ActionType.Activate_add3 && it.abilityGrpId == 192601 } shouldBe true
            }
            withClue(gsmActions.map { "${it.actionType}:${it.abilityGrpId}:${it.manaCostList}" }) {
                gsmActions
                    .single { it.actionType == ActionType.Activate_add3 && it.abilityGrpId == 192601 }
                    .manaCostList
                    .single()
                    .count shouldBe 3
            }

            val prompt = after { activateAbility("Giant Koi", abilityIndex = 0).shouldBeTrue() }.expectOnePayCostsReq()

            assertSoftly {
                allMessages.last { it.hasPrompt() }.prompt.promptId shouldBe PromptIds.PAY_COSTS
                assertWaterbendPaymentActions(
                    prompt,
                    ids = listOf(merfolkIid, bearIid, solRingIid),
                    creatureIds = setOf(merfolkIid, bearIid),
                )
            }

            val resolveSnap = messageSnapshot()
            respondToEffectCost(listOf(merfolkIid, bearIid, solRingIid))
            passUntilResolved(maxPasses = 8)

            val tappedIds =
                messagesSince(resolveSnap)
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.isTapped }
                    .map { it.instanceId }
                    .toSet()

            assertSoftly {
                tappedIds shouldContain merfolkIid
                tappedIds shouldContain bearIid
                tappedIds shouldContain solRingIid
                bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeConvokePayments() shouldBe emptyMap()
            }
        }

        session(
            "Ruinous Waterbending emits AdditionalCost CastingTimeOption when Waterbend is paid",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Ruinous Waterbending
                humanbattlefield=Swamp;Swamp;Swamp;Coral Merfolk;Grizzly Bears;Monastery Swiftspear;Sol Ring;Manalith
                humanlibrary=Swamp;Swamp;Swamp
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val merfolkIid = human.battlefield.iid("Coral Merfolk")
            val bearIid = human.battlefield.iid("Grizzly Bears")
            val solRingIid = human.battlefield.iid("Sol Ring")
            val manalithIid = human.battlefield.iid("Manalith")

            val cto = after { castSpellByName("Ruinous Waterbending").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            val waterbendOption =
                cto.castingTimeOptionReqList.single {
                    it.castingTimeOptionType == CastingTimeOptionType.AdditionalCost
                }
            waterbendOption.grpId shouldBe RUINOUS_WATERBEND_ABILITY_GRP_ID

            val payCosts = after { respondToOptionalCost(waterbendOption.ctoId) }.expectOnePayCostsReq()

            assertSoftly {
                allMessages.last { it.hasPrompt() }.prompt.promptId shouldBe PromptIds.PAY_COSTS
                payCosts.manaCostList.any { it.count > 0 } shouldBe true
                assertWaterbendPaymentActions(
                    payCosts,
                    ids = listOf(merfolkIid, bearIid, solRingIid, manalithIid),
                    creatureIds = setOf(merfolkIid, bearIid),
                )
            }

            respondToEffectCost(listOf(merfolkIid, bearIid, solRingIid, manalithIid))
            passUntilResolved(maxPasses = 8)
            // The creatures die together, so their "gain 1 life" triggers ask for a stack order.
            respondToSelectN(lastSelectNReq().idsList, OrderingType.OrderAsIndicated)
            passUntil { game().stack.isEmpty }

            val additionalCostAnnotations =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .filter { it.detailInt(DetailKeys.TYPE) == CastingTimeOptionType.AdditionalCost.number }

            additionalCostAnnotations shouldHaveSize 1
            additionalCostAnnotations.single().detailInt(DetailKeys.ADDITIONAL_COST_GRP_ID) shouldBe RUINOUS_WATERBEND_ABILITY_GRP_ID

            val client = ClientAccumulator()
            allMessages.forEach(client::process)
            val stackIds = client.zones[ZoneIds.STACK]?.objectInstanceIdsList.orEmpty()
            val stackTrace =
                allMessages.gameStateMessages().mapNotNull { gsm ->
                    val zone = gsm.zonesList.firstOrNull { it.zoneId == ZoneIds.STACK }
                    val objects = gsm.gameObjectsList.filter { it.zoneId == ZoneIds.STACK }.map { it.instanceId }
                    if (zone == null && objects.isEmpty()) {
                        null
                    } else {
                        "gs=${gsm.gameStateId} zone=${zone?.objectInstanceIdsList} objects=$objects"
                    }
                }
            withClue("stack=$stackIds objects=${stackIds.map(client.objects::get)} trace=$stackTrace") {
                stackIds shouldBe emptyList()
            }
        }

        session(
            "Ruinous Waterbending accepts live Waterbend MakePayment responses",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Ruinous Waterbending
                humanbattlefield=Swamp;Swamp;Swamp;Coral Merfolk;Grizzly Bears;Sol Ring;Manalith
                humanlibrary=Swamp;Swamp;Swamp
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val merfolkIid = human.battlefield.iid("Coral Merfolk")
            val bearIid = human.battlefield.iid("Grizzly Bears")
            val solRingIid = human.battlefield.iid("Sol Ring")
            val manalithIid = human.battlefield.iid("Manalith")

            val cto = after { castSpellByName("Ruinous Waterbending").shouldBeTrue() }.expectOneCastingTimeOptionsReq()
            val waterbendOption =
                cto.castingTimeOptionReqList.single {
                    it.castingTimeOptionType == CastingTimeOptionType.AdditionalCost
                }
            after { respondToOptionalCost(waterbendOption.ctoId) }.expectOnePayCostsReq()

            val updatedPayCosts = after { respondToWaterbendMakePayment(merfolkIid) }.expectOnePayCostsReq()
            assertSoftly {
                updatedPayCosts.manaCostList.single { it.colorList == listOf(ManaColor.Generic) }.count shouldBe 4
                updatedPayCosts.paymentActions.actionsList.any { it.instanceId == merfolkIid } shouldBe false
            }

            respondToWaterbendMakePayment(bearIid)
            respondToWaterbendMakePayment(solRingIid)
            respondToWaterbendMakePayment(manalithIid)
            respondToWaterbendPaymentDone()
            passUntilResolved(maxPasses = 8)
            respondToSelectN(lastSelectNReq().idsList, OrderingType.OrderAsIndicated)
            passUntil { game().stack.isEmpty }

            assertSoftly {
                human.graveyard.iid("Ruinous Waterbending") shouldBeGreaterThan 0
                human.graveyard.iid("Coral Merfolk") shouldBeGreaterThan 0
                human.graveyard.iid("Grizzly Bears") shouldBeGreaterThan 0
                human.life shouldBe 22
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .filter { it.detailInt(DetailKeys.TYPE) == CastingTimeOptionType.AdditionalCost.number }
                    .shouldHaveSize(1)
            }
        }

        session(
            "Giant Koi completes native Waterbend when empty payment UI sends Cancel",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Giant Koi;Coral Merfolk;Sol Ring;Island;Island
                humanlibrary=Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val koiIid = human.battlefield.iid("Giant Koi")
            val merfolkIid = human.battlefield.iid("Coral Merfolk")
            val solRingIid = human.battlefield.iid("Sol Ring")

            after { activateAbility("Giant Koi", abilityIndex = 0).shouldBeTrue() }.expectOnePayCostsReq()
            respondToWaterbendMakePayment(merfolkIid)
            respondToWaterbendMakePayment(solRingIid)
            respondToWaterbendMakePayment(koiIid)

            val resolveSnap = messageSnapshot()
            cancelAction()
            passUntilResolved(maxPasses = 8)

            val tappedIds =
                messagesSince(resolveSnap)
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.isTapped }
                    .map { it.instanceId }
                    .toSet()

            tappedIds shouldContainExactlyInAnyOrder setOf(koiIid, merfolkIid, solRingIid)
        }
    })

private fun assertWaterbendPaymentActions(
    payCosts: PayCostsReq,
    ids: List<Int>,
    creatureIds: Set<Int>,
) {
    val actions = payCosts.paymentActions.actionsList
    ids.forEach { iid ->
        val action = actions.single { it.instanceId == iid }
        val mana =
            action.manaPaymentOptionsList
                .single()
                .manaList
                .single()
        assertSoftly {
            action.actionType shouldBe ActionType.MakePayment
            mana.abilityGrpId shouldBe 384
            mana.srcInstanceId shouldBe iid
            mana.specsList.map { it.type } shouldContain ManaSpecType.ManaSubstitution
            if (iid in creatureIds) {
                mana.specsList.map { it.type } shouldContain ManaSpecType.FromCreature
            }
        }
    }
}

private const val RUINOUS_WATERBEND_ABILITY_GRP_ID = 192688

private fun MatchFlowHarness.respondToWaterbendMakePayment(instanceId: Int) {
    send(
        submitWithGsId(
            performAction {
                actionType = ActionType.MakePayment
                this.instanceId = instanceId
            }.toBuilder().setGameStateId(latestPromptGsId()).build(),
        ),
    )
    drainSink()
}

private fun MatchFlowHarness.respondToWaterbendPaymentDone() {
    send(
        submitWithGsId(
            performAction { actionType = ActionType.Pass }
                .toBuilder()
                .setGameStateId(latestPromptGsId())
                .build(),
        ),
    )
    drainSink()
}
