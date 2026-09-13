package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext

class TersaIntiInteractionTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Tersa Lightshatter")
            TestCardRegistry.ensureCardRegistered("Inti, Seneschal of the Sun")
            TestCardRegistry.ensureCardRegistered("Cool but Rude")
            TestCardRegistry.ensureCardRegistered("Grizzly Bears")
        }

        session(
            "Tersa ETB uses the optional two-card discard prompt",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Tersa Lightshatter;Plains;Swamp
                humanbattlefield=Mountain;Mountain;Mountain
                humanlibrary=Island;Forest;Plains
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 5,
        ) {
            castSpellByName("Tersa Lightshatter") shouldBe true
            passUntil(maxPasses = 10) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()

            val prompt = allMessages.last { it.hasSelectNReq() }
            assertSoftly {
                prompt.prompt.promptId shouldBe PromptIds.DISCARD_UP_TO_TWO
                prompt.allowCancel shouldBe AllowCancel.Continue
                prompt.selectNReq.prompt.promptId shouldBe PromptIds.DISCARD_UP_TO_TWO
                prompt.selectNReq.context shouldBe SelectionContext.Discard_a163
                prompt.selectNReq.optionContext shouldBe OptionContext.Resolution_a9d7
                prompt.selectNReq.minSel shouldBe 0
                prompt.selectNReq.maxSel shouldBe 2
                prompt.selectNReq.idsList shouldHaveSize 2
            }

            respondToSelectN(prompt.selectNReq.idsList)
            human
                .getZone(ZoneType.Graveyard)
                .cards
                .map { it.name }
                .shouldContainExactly("Plains", "Swamp")
            human.getZone(ZoneType.Hand).cards shouldHaveSize 2
        }

        val intiPuzzle =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Mountain
            humanbattlefield=Inti, Seneschal of the Sun;Grizzly Bears
            humanlibrary=Plains;Island;Forest
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        session("Inti pays its optional discard before choosing an attacking creature", puzzle = intiPuzzle, turns = 5) {
            holdNextOptionalAction()
            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val bearIid = humanBattlefieldCreatures().single { it.second == "Grizzly Bears" }.first
            declareAttackers(listOf(bearIid))

            passUntil(maxPasses = 10) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            val optional = allMessages.last { it.hasOptionalActionMessage() }
            assertSoftly {
                optional.prompt.promptId shouldBe PromptIds.DISCARD_OPTIONAL
                optional.optionalActionMessage.prompt.promptId shouldBe PromptIds.DISCARD_OPTIONAL
            }
            respondToOptionalAction(accept = true)
            passUntil(maxPasses = 10) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val discard = allMessages.last { it.hasSelectNReq() }
            assertSoftly {
                discard.prompt.promptId shouldBe PromptIds.DISCARD_OPTIONAL
                discard.allowCancel shouldBe AllowCancel.Continue
                discard.selectNReq.minSel shouldBe 0
                discard.selectNReq.maxSel shouldBe 1
                discard.selectNReq.idsList shouldHaveSize 1
            }
            respondToSelectN(discard.selectNReq.idsList)

            passUntil(maxPasses = 10) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            val targets =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq.targetsList
                    .single()
            targets.targetsList.map { it.targetInstanceId } shouldContainExactly listOf(bearIid)
            selectTargets(listOf(bearIid))

            passUntil(maxPasses = 10) {
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Grizzly Bears" }
                    .netPower == 3
            }.shouldBeTrue()
            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContainExactly listOf("Mountain")
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Grizzly Bears" }
                    .netToughness shouldBe 3
            }
        }

        session("declining Inti's discard resumes play without a target prompt", puzzle = intiPuzzle, turns = 5) {
            holdNextOptionalAction()
            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val bearIid = humanBattlefieldCreatures().single { it.second == "Grizzly Bears" }.first
            declareAttackers(listOf(bearIid))
            passUntil(maxPasses = 10) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            respondToOptionalAction(accept = true)
            passUntil(maxPasses = 10) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val beforeDecline = messageSnapshot()
            respondToSelectN(emptyList())
            bridge.awaitPriority()

            assertSoftly {
                messagesSince(beforeDecline).none { it.hasSelectTargetsReq() } shouldBe true
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldContainExactly listOf("Mountain")
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Grizzly Bears" }
                    .netPower shouldBe 2
            }
        }

        session(
            "Cool but Rude levels normally and its attack trigger uses the discard cost",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Mountain
                humanbattlefield=Cool but Rude;Grizzly Bears;Mountain;Mountain
                humanlibrary=Plains;Island;Forest
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 5,
        ) {
            val cool = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Cool but Rude" }
            val coolIid = bridge.instanceId(cool)
            val levelAction =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq
                    .actionsList
                    .single {
                        it.instanceId == coolIid &&
                            it.actionType == wotc.mtgo.gre.external.messaging.Messages.ActionType.Activate_add3
                    }
            assertSoftly {
                levelAction.abilityGrpId shouldBe 143758
                activateAbility("Cool but Rude") shouldBe true
                passUntil(maxPasses = 10) { cool.classLevel == 2 }.shouldBeTrue()
            }
            assertSoftly {
                cool.classLevel shouldBe 2
                human.life shouldBe 20
                ai.life shouldBe 20
            }

            holdNextOptionalAction()
            passUntil(maxPasses = 30) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
            val bearIid = humanBattlefieldCreatures().single { it.second == "Grizzly Bears" }.first
            declareAttackers(listOf(bearIid))
            passUntil(maxPasses = 10) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            val optional = allMessages.last { it.hasOptionalActionMessage() }
            assertSoftly {
                optional.prompt.promptId shouldBe PromptIds.DISCARD_OPTIONAL
                optional.optionalActionMessage.prompt.promptId shouldBe PromptIds.DISCARD_OPTIONAL
            }
            respondToOptionalAction(accept = true)
            passUntil(maxPasses = 10) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            val discard = allMessages.last { it.hasSelectNReq() }
            discard.prompt.promptId shouldBe PromptIds.DISCARD_OPTIONAL
            respondToSelectN(discard.selectNReq.idsList)
            passUntil(maxPasses = 10) { ai.life == 18 }.shouldBeTrue()

            assertSoftly {
                // The harness may already have applied Grizzly Bears' combat
                // damage after the class trigger's two damage.
                ai.life shouldBeLessThanOrEqual 18
                human.life shouldBe 20
                allMessages.none { it.hasPrompt() && it.prompt.promptId == PromptIds.NUMERIC_INPUT } shouldBe true
            }
        }
    })
