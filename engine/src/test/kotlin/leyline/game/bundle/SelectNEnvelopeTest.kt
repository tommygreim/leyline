package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

class SelectNEnvelopeTest :
    FunSpec({
        tags(UnitTag)

        val req =
            SelectNReq
                .newBuilder()
                .setSourceId(101)
                .setMaxSel(2)
                .build()

        test("default selectN envelope uses generic prompt") {
            val envelope = SelectNEnvelope.default(req)

            assertSoftly {
                envelope.req shouldBe req
                envelope.prompt.promptId shouldBe PromptIds.SELECT_N
                envelope.allowCancel shouldBe AllowCancel.None_a526
                envelope.gameStateAugmentation shouldBe SelectNEnvelope.GameStateAugmentation.None
            }
        }

        test("legend rule envelope uses legend prompt and disables cancel") {
            val envelope = SelectNEnvelope.legendRule(req)

            assertSoftly {
                envelope.prompt.promptId shouldBe PromptIds.SELECT_N_LEGEND_RULE
                envelope.prompt.parametersCount shouldBe 1
                envelope.allowCancel shouldBe AllowCancel.No_a526
                envelope.gameStateAugmentation shouldBe SelectNEnvelope.GameStateAugmentation.None
            }
        }

        test("generic resolution envelope uses a neutral look-and-pick prompt") {
            val envelope = SelectNEnvelope.resolution(req)

            assertSoftly {
                envelope.prompt.promptId shouldBe PromptIds.SELECT_N
                envelope.prompt.parametersCount shouldBe 0
                envelope.allowCancel shouldBe AllowCancel.No_a526
                envelope.gameStateAugmentation shouldBe SelectNEnvelope.GameStateAugmentation.LookAndPick
            }
        }

        test("Stock Up resolution envelope keeps its specific look-and-pick prompt") {
            val envelope = SelectNEnvelope.resolution(req, stockUp = true)

            assertSoftly {
                envelope.prompt.promptId shouldBe PromptIds.SELECT_N_STOCK_UP
                envelope.prompt.getParameters(0).numberValue shouldBe 101
                envelope.prompt.getParameters(1).numberValue shouldBe 2
            }
        }

        test("mutate top-bottom envelope disables cancel") {
            val envelope = SelectNEnvelope.mutateTopBottom(req)

            assertSoftly {
                envelope.prompt.promptId shouldBe PromptIds.SELECT_N
                envelope.allowCancel shouldBe AllowCancel.No_a526
                envelope.gameStateAugmentation shouldBe SelectNEnvelope.GameStateAugmentation.None
            }
        }

        test("learn lesson envelope uses supplied prompt and learn augmentation") {
            val envelope = SelectNEnvelope.learnLesson(req, PromptIds.LEARN_LESSON_OR_DISCARD)

            assertSoftly {
                envelope.prompt.promptId shouldBe PromptIds.LEARN_LESSON_OR_DISCARD
                envelope.prompt.getParameters(0).numberValue shouldBe 101
                envelope.prompt.getParameters(1).numberValue shouldBe 2
                envelope.allowCancel shouldBe AllowCancel.Continue
                envelope.gameStateAugmentation shouldBe SelectNEnvelope.GameStateAugmentation.None
            }
        }
    })
