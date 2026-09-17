package leyline.mechanics.blight

import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.allAnnotations
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

class BlightLifecycleTest :
    SessionTest({
        session(
            "selected Blight branch pays with a controlled creature and records its cast option",
            puzzleFile = "data/puzzles/blight-bogslither-positive.pzl",
        ) {
            val snap = messageSnapshot()
            val option =
                after { castSpellByName("Bogslither's Embrace") }
                    .expectOneCastingTimeOptionsReq()
                    .castingTimeOptionReqList
                    .single()

            assertSoftly {
                option.castingTimeOptionType shouldBe CastingTimeOptionType.ChooseOrCost
                option.isRequired shouldBe true
                option.selectNReq.prompt.promptId shouldBe PromptIds.CHOOSE_OR_COST
                // The mana-only branch ("pay {3}") isn't labeled with a card-specific
                // prompt (see DeferredCastCostPlanMaterializer), but it still needs a
                // real Prompt.Parameters entry: the client indexes idsList straight into
                // this list with no bounds check, so a short list crashes the client.
                option.selectNReq.prompt.parametersList
                    .map { it.promptId } shouldBe listOf(PromptIds.CHOOSE_OR_COST_PAY_BLIGHT, PromptIds.SELECT_N)
                option.selectNReq.idsList shouldBe listOf(1, 2)
            }

            after { respondToAlternateCost(option.ctoId, option.selectNReq.idsList.first()) }
                .expectOneSelectTargetsReq()
            selectTargets(listOf(ai.battlefield.iid("Centaur Courser")))
            val costPrompt = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq
            costPrompt.targetsList
                .single()
                .targetsList
                .map { it.targetInstanceId } shouldBe
                listOf(human.battlefield.iid("Grizzly Bears"))
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
            passUntilResolved(maxPasses = 8)

            val messages = messagesSince(snap)
            val chosenCost =
                messages
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .single { it.detailInt(DetailKeys.CHOSEN_COST_PROMPT_ID) == PromptIds.CHOOSE_OR_COST_PAY_BLIGHT }
            val counterAdded =
                messages
                    .allAnnotations()
                    .single { AnnotationType.CounterAdded in it.typeList }
            val counterState =
                messages
                    .persistentAnnotationsOfType(AnnotationType.Counter_803b)
                    .single { human.battlefield.iid("Grizzly Bears") in it.affectedIdsList }

            assertSoftly {
                chosenCost.detailInt(DetailKeys.TYPE) shouldBe CastingTimeOptionType.ChooseOrCost.number
                chosenCost.id shouldBeIn messages.deletedPersistentAnnotationIds()
                counterAdded.detailInt(DetailKeys.COUNTER_TYPE) shouldBe 2
                counterAdded.detailInt(DetailKeys.TRANSACTION_AMOUNT) shouldBe 1
                counterState.detailInt(DetailKeys.COUNTER_TYPE) shouldBe 2
                counterState.detailInt(DetailKeys.COUNT) shouldBe 1
                human.battlefield.card("Grizzly Bears").getCounters(CounterEnumType.M1M1) shouldBe 1
                ai.getZone(ZoneType.Exile).cards.map { it.name } shouldBe listOf("Centaur Courser")
            }
        }

        session(
            "without an eligible controlled creature only the mana branch remains",
            puzzleFile = "data/puzzles/blight-bogslither-no-creature.pzl",
        ) {
            val cast = after { castSpellByName("Bogslither's Embrace") }
            cast.messages.filter { it.hasCastingTimeOptionsReq() } shouldHaveSize 0
            val targetPrompt = cast.expectOneSelectTargetsReq()
            targetPrompt.targetsList.flatMap { it.targetsList }.map { it.targetInstanceId } shouldContain
                ai.battlefield.iid("Centaur Courser")
        }

        session(
            "cancelled Blight selection does not leak into a mana recast",
            puzzleFile = "data/puzzles/blight-bogslither-positive.pzl",
        ) {
            after { castSpellByName("Bogslither's Embrace") }
                .expectOneCastingTimeOptionsReq()
                .let { request ->
                    val option = request.castingTimeOptionReqList.single()
                    after { respondToAlternateCost(option.ctoId, option.selectNReq.idsList.first()) }
                        .expectOneSelectTargetsReq()
                }
            after { cancelAction() }

            val recastSnapshot = messageSnapshot()
            val recast = after { castSpellByName("Bogslither's Embrace") }.expectOneCastingTimeOptionsReq()
            val manaOption = recast.castingTimeOptionReqList.single()
            after { respondToAlternateCost(manaOption.ctoId, manaOption.selectNReq.idsList.last()) }
                .expectOneSelectTargetsReq()
            selectTargets(listOf(ai.battlefield.iid("Centaur Courser")))
            passUntilResolved(maxPasses = 8)

            messagesSince(recastSnapshot).persistentAnnotationsOfType(AnnotationType.CastingTimeOption).shouldBeEmpty()
        }
    })
