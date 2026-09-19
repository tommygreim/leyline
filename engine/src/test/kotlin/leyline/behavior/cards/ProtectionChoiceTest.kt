package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.bridge.types.StaticChoiceIds
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

private val GIVER_OF_RUNES_PUZZLE =
    """
    [metadata]
    Name:Giver of Runes protection choice
    Goal:Win
    Turns:1

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20
    humanbattlefield=Giver of Runes;Grizzly Bears
    humanlibrary=Plains
    ailibrary=Forest
    """.trimIndent()

class ProtectionChoiceTest :
    SessionTest({
        session("Giver of Runes exposes Colorless and WUBRG through CardColors", puzzle = GIVER_OF_RUNES_PUZZLE) {
            activateAbility("Giver of Runes").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))

            val request = lastSelectNReq()
            assertSoftly {
                request.listType shouldBe SelectionListType.StaticSubset
                request.staticList shouldBe StaticList.CardColors
                request.idsList shouldHaveSize 6
                request.idsList shouldContainExactlyInAnyOrder
                    listOf("Colorless", "White", "Blue", "Black", "Red", "Green").map {
                        StaticChoiceIds.cardColorIdForName(it)!!
                    }
            }
        }

        session("Giver of Runes applies the selected Colorless protection", puzzle = GIVER_OF_RUNES_PUZZLE) {
            val target = human.battlefield.card("Grizzly Bears")
            activateAbility("Giver of Runes").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid(target)))
            lastSelectNReq().staticList shouldBe StaticList.CardColors
            respondToSelectN(listOf(StaticChoiceIds.cardColorIdForName("Colorless")!!))
            passUntilResolved()

            target.hasKeyword("Protection from colorless").shouldBeTrue()
        }
    })
