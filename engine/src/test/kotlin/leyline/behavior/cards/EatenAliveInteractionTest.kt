package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.beInGraveyardOf
import leyline.testkit.haveManaCost
import leyline.testkit.ofType
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

class EatenAliveInteractionTest :
    SessionTest({
        val eatenAliveState =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Eaten Alive
            humanbattlefield=Swamp;Swamp;Swamp;Swamp;Swamp;Walking Corpse
            humanlibrary=Swamp
            aibattlefield=Centaur Courser
            ailibrary=Swamp
            """.trimIndent()

        val deadlyPrecisionState =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Deadly Precision
            humanbattlefield=Swamp;Swamp;Swamp;Swamp;Swamp;Swamp;Grizzly Bears
            humanlibrary=Swamp
            aibattlefield=Centaur Courser
            ailibrary=Swamp
            """.trimIndent()

        fun MatchFlowHarness.latestCastActionsFor(cardName: String): List<Action> {
            val iid = human.hand.iid(cardName)
            return allMessages
                .asReversed()
                .first { it.hasActionsAvailableReq() }
                .actionsAvailableReq
                .ofType(ActionType.Cast)
                .filter { it.instanceId == iid }
        }

        session("Eaten Alive exposes a single cast action with base mana cost", puzzle = eatenAliveState) {
            val casts = latestCastActionsFor("Eaten Alive")
            casts shouldHaveSize 1
            casts.single() should haveManaCost(black = 1)
        }

        session("sacrifice-mode cast resolves fully after target and sacrifice selection", puzzle = eatenAliveState) {
            submitAction(latestCastActionsFor("Eaten Alive").single())
            respondToOptionalCost(1)

            val targetId = ai.battlefield.iid("Centaur Courser")
            after { selectTargets(listOf(targetId)) }.expectOnePayCostsReq()

            val sacId = human.battlefield.iid("Walking Corpse")
            respondToEffectCost(listOf(sacId))

            passUntilResolved(maxPasses = 8)

            ai.getZone(ForgeZoneType.Exile).cards.map { it.name } shouldBe listOf("Centaur Courser")
            "Walking Corpse" should beInGraveyardOf(human)
        }

        session("Deadly Precision prompts for alternate additional cost before targeting", puzzle = deadlyPrecisionState) {
            val action = latestCastActionsFor("Deadly Precision").single()
            val req = after { submitAction(action) }.expectOneCastingTimeOptionsReq()

            val option = req.castingTimeOptionReqList.single()
            assertSoftly {
                option.castingTimeOptionType shouldBe CastingTimeOptionType.ChooseOrCost
                option.isRequired shouldBe true
                option.grpId shouldBe action.grpId
                option.selectNReq.prompt.promptId shouldBe PromptIds.CHOOSE_OR_COST
                // The mana-only branch ("pay {4}") isn't labeled with a card-specific
                // prompt, but it still needs a real Prompt.Parameters entry: the client
                // indexes idsList straight into this list with no bounds check.
                option.selectNReq.prompt.parametersList
                    .map { it.promptId } shouldBe listOf(PromptIds.CHOOSE_OR_COST_PAY_SACRIFICE, PromptIds.SELECT_N)
                option.selectNReq.idsList shouldBe listOf(1, 2)
            }

            after { respondToOptionalCost(2) }.expectOneSelectTargetsReq()
        }

        session("a required alternate-cost choice survives a response that selects no branch", puzzle = deadlyPrecisionState) {
            val action = latestCastActionsFor("Deadly Precision").single()
            submitAction(action)

            // ctoId 0 is the Done/decline shape; the ChooseOrCost option is
            // required, so it names no branch. The match must stay playable.
            respondToOptionalCost(0)

            val targets = after { respondToOptionalCost(2) }.expectOneSelectTargetsReq()
            targets.targetsList shouldHaveSize 1
            targets.targetsList.flatMap { it.targetsList }.map { it.targetInstanceId } shouldContain
                ai.battlefield.iid("Centaur Courser")
        }

        session("each offered branch index resolves its own cost branch", puzzle = deadlyPrecisionState) {
            val action = latestCastActionsFor("Deadly Precision").single()
            val req = after { submitAction(action) }.expectOneCastingTimeOptionsReq()
            val ctoId = req.castingTimeOptionReqList.single().ctoId

            after { respondToAlternateCost(ctoId, optionIndex = 1) }.expectOneSelectTargetsReq()
            val targetId = ai.battlefield.iid("Centaur Courser")
            after { selectTargets(listOf(targetId)) }.expectOnePayCostsReq()

            val sacId = human.battlefield.iid("Grizzly Bears")
            respondToEffectCost(listOf(sacId))
            passUntilResolved(maxPasses = 8)

            "Grizzly Bears" should beInGraveyardOf(human)
        }
    })
