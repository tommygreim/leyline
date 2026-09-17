package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.mapping.ActionMapper
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class ProducedToManaColorTest :
    FunSpec({
        tags(UnitTag)

        test("maps single-letter color codes") {
            assertSoftly {
                ActionMapper.producedToManaColor("R") shouldBe ManaColor.Red_afc9
                ActionMapper.producedToManaColor("W") shouldBe ManaColor.White_afc9
                ActionMapper.producedToManaColor("U") shouldBe ManaColor.Blue_afc9
                ActionMapper.producedToManaColor("B") shouldBe ManaColor.Black_afc9
                ActionMapper.producedToManaColor("G") shouldBe ManaColor.Green_afc9
                ActionMapper.producedToManaColor("C") shouldBe ManaColor.Colorless_afc9
                ActionMapper.producedToManaColor("ANY") shouldBe ManaColor.AnyColor
            }
        }

        test("case insensitive") {
            ActionMapper.producedToManaColor("r") shouldBe ManaColor.Red_afc9
            ActionMapper.producedToManaColor("any") shouldBe ManaColor.AnyColor
        }

        test("unknown returns null") {
            ActionMapper.producedToManaColor("X") shouldBe null
            ActionMapper.producedToManaColor("{R}") shouldBe null
        }
    })
