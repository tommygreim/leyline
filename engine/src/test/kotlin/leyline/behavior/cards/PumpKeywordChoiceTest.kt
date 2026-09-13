package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.game.codes.KeywordGrpIds
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

private val PRACTICED_OFFENSE_PUZZLE =
    """
    [metadata]
    Name:Practiced Offense keyword choice
    Goal:Win
    Turns:1

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20
    humanbattlefield=Plains;Plains;Plains;Grizzly Bears
    humanhand=Practiced Offense
    humanlibrary=Plains
    ailibrary=Forest
    """.trimIndent()

class PumpKeywordChoiceTest :
    SessionTest({
        session("Practiced Offense offers only double strike and lifelink", puzzle = PRACTICED_OFFENSE_PUZZLE) {
            castSpellByName("Practiced Offense").shouldBeTrue()
            selectTargets(listOf(HUMAN_SEAT))
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))

            val request = allMessages.last { it.hasSelectNReq() }.selectNReq
            assertSoftly {
                request.listType shouldBe SelectionListType.StaticSubset
                request.staticList shouldBe StaticList.Keywords
                request.idsList shouldBe
                    listOf(
                        KeywordGrpIds.forKeyword("Double Strike")!!,
                        KeywordGrpIds.forKeyword("Lifelink")!!,
                    )
            }
        }

        session("Practiced Offense applies the selected lifelink keyword", puzzle = PRACTICED_OFFENSE_PUZZLE) {
            val target = human.battlefield.card("Grizzly Bears")
            val targetIid = human.battlefield.iid("Grizzly Bears")
            castSpellByName("Practiced Offense").shouldBeTrue()
            selectTargets(listOf(HUMAN_SEAT))
            selectTargets(listOf(targetIid))
            respondToSelectN(listOf(KeywordGrpIds.forKeyword("Lifelink")!!))
            passUntilResolved()

            assertSoftly {
                target.hasKeyword("Lifelink").shouldBeTrue()
                target.hasKeyword("Double Strike") shouldBe false
            }
        }
    })
