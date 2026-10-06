package leyline.bridge.types

import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

class ManaColorMappingTest :
    FunSpec({
        tags(UnitTag)

        test("maps Forge snow mana shard") {
            assertSoftly {
                ManaColorMapping.fromShard(ManaCostShard.S) shouldBe ManaColor.Snow_afc9
                ManaColorMapping.deriveManaCost(ManaCost("S S")) shouldBe listOf(ManaColor.Snow_afc9 to 2)
            }
        }

        test("preserves Phyrexian pip identity and payment alternatives") {
            assertSoftly {
                ManaColorMapping.phyrexianColors(ManaCostShard.BP) shouldBe
                    listOf(ManaColor.Black_afc9, ManaColor.Phyrexian_afc9)
                ManaColorMapping.phyrexianColors(ManaCostShard.BGP) shouldBe
                    listOf(ManaColor.Black_afc9, ManaColor.Green_afc9, ManaColor.Phyrexian_afc9)
                ManaColorMapping.requirementColors(ManaCostShard.BP) shouldBe
                    listOf(ManaColor.Black_afc9, ManaColor.Phyrexian_afc9)
                ManaColorMapping.manaTypeAlternative(ManaCostShard.BP) shouldBe ManaColor.Phyrexian_afc9
                ManaColorMapping.colorCounts(ManaCost("B/P B/P")) shouldBe
                    mapOf(ManaColor.Phyrexian_afc9 to 2)
            }
        }

        test("preserves ordinary hybrid pip identity and payment alternatives") {
            assertSoftly {
                ManaColorMapping.standardHybridColors(ManaCostShard.UB) shouldBe
                    listOf(ManaColor.Blue_afc9, ManaColor.Black_afc9)
                ManaColorMapping.requirementColors(ManaCostShard.UB) shouldBe
                    listOf(ManaColor.Blue_afc9, ManaColor.Black_afc9)
                ManaColorMapping.paymentColors(ManaCostShard.UB) shouldBe
                    listOf(ManaColor.Blue_afc9, ManaColor.Black_afc9)
                ManaColorMapping.manaTypeAlternative(ManaCostShard.UB) shouldBe ManaColor.Black_afc9
            }
        }

        test("maps client payment colors to Forge payment shards") {
            assertSoftly {
                ManaColorMapping.paymentShard(ManaColor.White_afc9) shouldBe ManaCostShard.WHITE
                ManaColorMapping.paymentShard(ManaColor.Blue_afc9) shouldBe ManaCostShard.BLUE
                ManaColorMapping.paymentShard(ManaColor.Generic) shouldBe ManaCostShard.GENERIC
                ManaColorMapping.paymentShard(ManaColor.Colorless_afc9) shouldBe null
            }
        }

        test("maps Forge payment shards to client payment colors") {
            assertSoftly {
                ManaColorMapping.paymentWireColor(ManaCostShard.WHITE) shouldBe ManaColor.White_afc9
                ManaColorMapping.paymentWireColor(ManaCostShard.GREEN) shouldBe ManaColor.Green_afc9
                ManaColorMapping.paymentWireColor(ManaCostShard.GENERIC) shouldBe ManaColor.Colorless_afc9
                ManaColorMapping.paymentCostColor(ManaCostShard.GENERIC) shouldBe ManaColor.Generic
            }
        }

        test("aggregates client payment cost into Forge payment shards") {
            ManaColorMapping.paymentShardCounts(
                listOf(
                    ManaColor.White_afc9 to 1,
                    ManaColor.Generic to 2,
                    ManaColor.Colorless_afc9 to 1,
                ),
            ) shouldBe mapOf(ManaCostShard.WHITE to 1, ManaCostShard.GENERIC to 2)
        }

        test("derives action cost pairs with generic last") {
            ManaColorMapping.deriveManaCostWithGenericLast(ManaCost("2 W U")) shouldBe
                listOf(ManaColor.White_afc9 to 1, ManaColor.Blue_afc9 to 1, ManaColor.Generic to 2)
        }

        test("derives WUBRG cost pairs with generic first") {
            ManaColorMapping.deriveWubrgCostWithGenericFirst(ManaCost("2 W U")) shouldBe
                listOf(ManaColor.Generic to 2, ManaColor.White_afc9 to 1, ManaColor.Blue_afc9 to 1)
        }
    })
