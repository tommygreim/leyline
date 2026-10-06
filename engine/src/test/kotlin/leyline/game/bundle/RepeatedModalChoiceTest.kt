package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

class RepeatedModalChoiceTest :
    FunSpec({
        tags(UnitTag)

        test("repeatable modal wire requests retain repetition and exact selection bounds") {
            val request =
                CastingTimeOptionsBuilder.buildModalCastingTimeOptionsReq(
                    parentGrpId = 42,
                    modalOptions = listOf(CastingTimeOptionsBuilder.ModalOptionSpec(43)),
                    minSel = 3,
                    maxSel = 3,
                    sourceInstanceId = 100,
                    grpId = 41,
                    allowRepeat = true,
                )
            val modal = request.getCastingTimeOptionReq(0).modalReq
            assertSoftly {
                modal.repeatedSelectAllowed shouldBe true
                modal.minSel shouldBe 3
                modal.maxSel shouldBe 3
                modal.modalOptionsList.map { it.grpId } shouldBe listOf(43)
            }
        }

        test("ordinary modal requests do not allow duplicate selections") {
            val request =
                CastingTimeOptionsBuilder.buildModalCastingTimeOptionsReq(
                    parentGrpId = 42,
                    modalOptions = listOf(CastingTimeOptionsBuilder.ModalOptionSpec(43)),
                    minSel = 1,
                    maxSel = 1,
                    sourceInstanceId = 100,
                    grpId = 41,
                )
            request.getCastingTimeOptionReq(0).modalReq.repeatedSelectAllowed shouldBe false
        }
    })
