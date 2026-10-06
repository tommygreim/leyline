package leyline.mechanics.learn

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beInHandOf
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.lastGsmMatching
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import wotc.mtgo.gre.external.messaging.Messages.Visibility

class LearnLessonTest :
    SessionTest({
        val learnPuzzle = "data/puzzles/learn-cram-session.pzl"

        fun MatchFlowHarness.castCramSessionUntilLearn(): SelectNReq {
            val castingOptions = castSpellUntilCastingTimeOptionsReq("Cram Session")
            val hybridMana =
                castingOptions.castingTimeOptionReqList.single {
                    it.castingTimeOptionType == CastingTimeOptionType.ManaType
                }
            hybridMana.selectManaTypeReq.manaColorsList shouldContain ManaColor.Black_afc9
            respondToManaTypeChoices(listOf(hybridMana.ctoId to ManaColor.Black_afc9))
            passUntil(maxPasses = 8) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
            return lastSelectNReq()
        }

        session("Learn emits SelectNReq with sideboard Lesson candidate", puzzleFile = learnPuzzle) {
            val req = castCramSessionUntilLearn()
            val lessonId = instanceIdOf("Environmental Sciences", human, ZoneType.Sideboard)
            val handDiscardId = instanceIdOf("Forest", human, ZoneType.Hand)
            val selectNMsg = allMessages.last { it.hasSelectNReq() }

            assertSoftly {
                req.context shouldBe SelectionContext.Resolution_a163
                req.optionContext shouldBe OptionContext.Resolution_a9d7
                req.listType shouldBe SelectionListType.Dynamic
                req.idType shouldBe IdType.InstanceId_ab2c
                req.validationType shouldBe SelectionValidationType.NonRepeatable
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                req.idsList shouldContain lessonId
                req.idsList shouldContain handDiscardId
                req.sourceId shouldBeGreaterThan 0
                cardByIid(req.sourceId)?.name shouldBe "Cram Session"
                req.prompt.parametersList
                    .single()
                    .parameterName shouldBe "Parameter"
                req.prompt.parametersList
                    .single()
                    .type shouldBe ParameterType.PromptId
                req.prompt.parametersList
                    .single()
                    .promptId shouldBe PromptIds.SELECT_N_LEARN_INNER_PARAMETER

                selectNMsg.type shouldBe GREMessageType.SelectNreq
                selectNMsg.allowCancel shouldBe AllowCancel.Continue
                selectNMsg.prompt.promptId shouldBe PromptIds.LEARN_LESSON_OR_DISCARD
                selectNMsg.prompt.parametersList.map { it.parameterName } shouldBe listOf("CardId", "CardId")
                selectNMsg.prompt.parametersList.map { it.type } shouldBe listOf(ParameterType.Number, ParameterType.Number)
                selectNMsg.prompt.parametersList.map { it.numberValue } shouldBe listOf(req.sourceId, req.maxSel)
                allMessages.any { it.hasSelectTargetsReq() }.shouldBeFalse()
            }

            val candidateIds = req.idsList.toSet()
            val gsm =
                checkNotNull(
                    allMessages.lastGsmMatching { gs ->
                        gs.gameObjectsList.any { it.instanceId in candidateIds }
                    },
                ) { "No GSM carries Learn candidate game objects" }
            val lessonObj = gsm.gameObjectsList.firstOrNull { it.instanceId == lessonId }
            lessonObj.shouldNotBeNull()
            assertSoftly {
                lessonObj.zoneId shouldBe ZoneIds.P1_SIDEBOARD
                lessonObj.visibility shouldBe Visibility.Private
                lessonObj.viewersList shouldContain SeatId(1).value
                lessonObj.grpId shouldBe 76393
            }
        }

        session(
            "Learn with no discard candidate emits the lesson-only envelope",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Cram Session
                humanbattlefield=Swamp;Swamp
                humanlibrary=Swamp;Swamp;Swamp
                humansideboard=Environmental Sciences
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val req = castCramSessionUntilLearn()
            val message = allMessages.last { it.hasSelectNReq() }
            val lessonId = instanceIdOf("Environmental Sciences", human, ZoneType.Sideboard)

            assertSoftly {
                req.idsList shouldBe listOf(lessonId)
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                cardByIid(req.sourceId)?.name shouldBe "Cram Session"
                message.prompt.promptId shouldBe PromptIds.LEARN_LESSON_ONLY
                message.allowCancel shouldBe AllowCancel.Continue
                allMessages.any { it.hasSelectTargetsReq() }.shouldBeFalse()
            }
        }

        session("selecting sideboard Lesson reveals and moves it to hand", puzzleFile = learnPuzzle) {
            val req = castCramSessionUntilLearn()
            val lessonId = instanceIdOf("Environmental Sciences", human, ZoneType.Sideboard)
            val resolution =
                after {
                    respondToSelectN(listOf(lessonId))
                    passUntilResolved()
                }
            val annotations =
                resolution.messages.flatMap { msg ->
                    if (msg.hasGameStateMessage()) msg.gameStateMessage.annotationsList else emptyList()
                }
            val sideboardToHand =
                annotations.firstOrNull { ann ->
                    AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                        ann.detailInt("zone_src") == ZoneIds.P1_SIDEBOARD &&
                        ann.detailInt("zone_dest") == ZoneIds.P1_HAND
                }

            assertSoftly {
                sideboardToHand.shouldNotBeNull().detailString("category") shouldBe "Put"
                annotations.any { AnnotationType.RevealedCardCreated in it.typeList }.shouldBeTrue()
                "Environmental Sciences" should beInHandOf(human)
                human.getZone(ZoneType.Sideboard).cards.filter { it.name == "Environmental Sciences" } shouldHaveSize 0
                "Cram Session" should beInGraveyardOf(human, count = 1)
                human.life shouldBe 24
            }
        }
    })
