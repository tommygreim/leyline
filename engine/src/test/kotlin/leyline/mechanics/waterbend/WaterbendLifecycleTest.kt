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
import leyline.testkit.clientMessage
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import leyline.testkit.performAction
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaCostSpecType
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq
import wotc.mtgo.gre.external.messaging.Messages.PerformAutoTapActionsResp

class WaterbendLifecycleTest :
    SessionTest({
        session(
            "Activated Waterbend confirms a mixed reducer and ordinary mana payment once",
            puzzleFile = "data/puzzles/payment-waterbend-koi.pzl",
            fullControl = true,
        ) {
            after { activateAbility("Giant Koi", abilityIndex = 0).shouldBeTrue() }.expectOnePayCostsReq()
            val remaining =
                after { respondToWaterbendMakePayment(human.battlefield.iid("Coral Merfolk")) }
                    .expectOnePayCostsReq()
            assertSoftly {
                remaining.manaCostList.single().count shouldBe 2
                remaining.autoTapActionsReq.autoTapSolutionsCount shouldBe 1
                remaining.paymentActions.actionsList.none { it.actionType == ActionType.Pass } shouldBe true
            }

            val confirmed = after { respondToWaterbendPaymentDone() }
            confirmed.messages.none { it.hasPayCostsReq() } shouldBe true
            passUntilResolved(maxPasses = 8)
            assertSoftly {
                human.battlefield.card("Coral Merfolk").isTapped shouldBe true
                human.battlefield.card("Grizzly Bears").isTapped shouldBe false
                human.battlefield.card("Short Sword").isTapped shouldBe false
                human.battlefield.card("Giant Koi").isTapped shouldBe false
                human
                    .getZone(forge.game.zone.ZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Island" }
                    .all { it.isTapped } shouldBe true
                game().stack.isEmpty shouldBe true
            }
        }

        session(
            "Waterbend confirmation without enough ordinary mana remains cancellable",
            puzzleFile = "data/puzzles/payment-waterbend-koi.pzl",
            fullControl = true,
        ) {
            after { activateAbility("Giant Koi", abilityIndex = 0).shouldBeTrue() }.expectOnePayCostsReq()
            val remaining = after { respondToWaterbendPaymentDone() }.expectOnePayCostsReq()
            assertSoftly {
                remaining.manaCostList.single().count shouldBe 3
                remaining.autoTapActionsReq.autoTapSolutionsCount shouldBe 0
                remaining.paymentActions.actionsList.none { it.actionType == ActionType.MakePayment } shouldBe true
            }
            cancelAction()
            assertSoftly {
                human.getZone(forge.game.zone.ZoneType.Battlefield).cards.all { !it.isTapped } shouldBe true
                game().stack.isEmpty shouldBe true
            }
            after { activateAbility("Giant Koi", abilityIndex = 0).shouldBeTrue() }.expectOnePayCostsReq()
            respondToWaterbendMakePayment(human.battlefield.iid("Coral Merfolk"))
            val confirmed = after { respondToWaterbendPaymentDone() }
            confirmed.messages.none { it.hasPayCostsReq() } shouldBe true
            passUntilResolved(maxPasses = 8)
            game().stack.isEmpty shouldBe true
        }

        session(
            "Giant Koi pays activated Waterbend through PayCostsReq",
            puzzleFile = "data/puzzles/payment-waterbend-koi.pzl",
            fullControl = true,
        ) {
            val merfolkIid = human.battlefield.iid("Coral Merfolk")
            val bearIid = human.battlefield.iid("Grizzly Bears")
            val swordIid = human.battlefield.iid("Short Sword")
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
                    ids = listOf(merfolkIid, bearIid, swordIid),
                    creatureIds = setOf(merfolkIid, bearIid),
                )
            }

            val resolveSnap = messageSnapshot()
            respondToEffectCost(listOf(merfolkIid, bearIid, swordIid))
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
                tappedIds shouldContain swordIid
                human.battlefield.card("Giant Koi").isTapped shouldBe false
                human
                    .getZone(forge.game.zone.ZoneType.Battlefield)
                    .cards
                    .filter { it.name == "Island" }
                    .all { !it.isTapped } shouldBe true
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
            // Both death triggers resolve through the ordinary stack flow.
            human.life shouldBe 22
            game().stack.isEmpty shouldBe true

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
            game().stack.isEmpty shouldBe true

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
            "Giant Koi finishes Waterbend without publishing an empty Cancel-only window",
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
            val resolveSnap = messageSnapshot()
            respondToWaterbendMakePayment(koiIid)
            messagesSince(resolveSnap).none { it.hasPayCostsReq() } shouldBe true
            passUntilResolved(maxPasses = 8)

            val tappedIds =
                messagesSince(resolveSnap)
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.isTapped }
                    .map { it.instanceId }
                    .toSet()

            tappedIds shouldContainExactlyInAnyOrder setOf(koiIid, merfolkIid, solRingIid)
        }

        session(
            "Giant Koi Cancel abandons a partial Waterbend selection",
            puzzleFile = "data/puzzles/payment-waterbend-koi.pzl",
        ) {
            after { activateAbility("Giant Koi", abilityIndex = 0).shouldBeTrue() }.expectOnePayCostsReq()
            after { respondToWaterbendMakePayment(human.battlefield.iid("Coral Merfolk")) }.expectOnePayCostsReq()
            cancelAction()
            assertSoftly {
                human.getZone(forge.game.zone.ZoneType.Battlefield).cards.all { !it.isTapped } shouldBe true
                game().stack.isEmpty shouldBe true
                bridge.promptBridge(SeatId(1)).journal.activeConvokePayments() shouldBe emptyMap()
            }
            after { activateAbility("Giant Koi", abilityIndex = 0).shouldBeTrue() }.expectOnePayCostsReq()
            cancelAction()
        }

        session(
            "Optional Waterbend Cancel refunds a partial selection and supports a paid retry",
            puzzleFile = "data/puzzles/payment-waterbend-ruinous.pzl",
            fullControl = true,
        ) {
            val option =
                after { castSpellByName("Ruinous Waterbending").shouldBeTrue() }
                    .expectOneCastingTimeOptionsReq()
                    .castingTimeOptionReqList
                    .single { it.castingTimeOptionType == CastingTimeOptionType.AdditionalCost }
            after { respondToOptionalCost(option.ctoId) }.expectOnePayCostsReq()
            after { respondToWaterbendMakePayment(human.battlefield.iid("Coral Merfolk")) }.expectOnePayCostsReq()
            cancelAction()
            assertSoftly {
                human.hand
                    .card("Ruinous Waterbending")
                    .zone.zoneType shouldBe forge.game.zone.ZoneType.Hand
                human.getZone(forge.game.zone.ZoneType.Battlefield).cards.all { !it.isTapped } shouldBe true
                ai.battlefield.card("Walking Corpse").isTapped shouldBe false
                human.life shouldBe 20
                game().stack.isEmpty shouldBe true
                bridge.promptBridge(SeatId(1)).journal.activeConvokePayments() shouldBe emptyMap()
            }

            val retryOption =
                after { castSpellByName("Ruinous Waterbending").shouldBeTrue() }
                    .expectOneCastingTimeOptionsReq()
                    .castingTimeOptionReqList
                    .single { it.castingTimeOptionType == CastingTimeOptionType.AdditionalCost }
            after { respondToOptionalCost(retryOption.ctoId) }.expectOnePayCostsReq()
            listOf("Coral Merfolk", "Grizzly Bears", "Short Sword", "Witching Well").forEach {
                respondToWaterbendMakePayment(human.battlefield.iid(it))
            }
            respondToWaterbendPaymentDone()
            passUntilResolved(maxPasses = 12)
            assertSoftly {
                human.life shouldBe 23
                human.graveyard.iid("Ruinous Waterbending") shouldBeGreaterThan 0
                ai.graveyard.iid("Walking Corpse") shouldBeGreaterThan 0
                human.battlefield.card("Short Sword").isTapped shouldBe true
                human.battlefield.card("Witching Well").isTapped shouldBe true
                game().stack.isEmpty shouldBe true
            }
        }
    })

private fun assertWaterbendPaymentActions(
    payCosts: PayCostsReq,
    ids: List<Int>,
    creatureIds: Set<Int>,
) {
    payCosts.manaCostList.forEach { requirement ->
        requirement.specsList shouldContain ManaCostSpecType.Waterbend
    }
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
            clientMessage(ClientMessageType.PerformAutoTapActionsResp_097b) {
                gameStateId = latestPromptGsId()
                setPerformAutoTapActionsResp(PerformAutoTapActionsResp.newBuilder().setIndex(0))
            },
        ),
    )
    drainSink()
}
