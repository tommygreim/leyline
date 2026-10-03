package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType

/** Forge callback coverage for TapOrUntap's native dynamic two-button prompt. */
class TapOrUntapInteractionTest :
    SessionTest({
        session(
            "Twiddle untaps an opponent permanent through the native binary prompt",
            fullControl = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true
                humanbattlefield=Island
                humanhand=Twiddle
                humanlibrary=Forest;Forest
                aibattlefield=Mountain|Tapped
                ailibrary=Mountain;Mountain
                """,
        ) {
            val target = ai.battlefield.iid("Mountain")
            cardByIid(target)?.isTapped shouldBe true
            castSpellByName("Twiddle") shouldBe true
            selectTargets(listOf(target))
            passUntilResolved(maxPasses = 12)

            val request = lastSelectNReq()
            assertSoftly {
                request.listType shouldBe SelectionListType.Dynamic
                request.idType shouldBe IdType.PromptParameterIndex
                request.idsList shouldContainExactly listOf(0, 1)
                request.prompt.parametersList.map { it.type } shouldContainExactly
                    listOf(ParameterType.NonLocalizedString, ParameterType.NonLocalizedString)
                request.prompt.parametersList.map { it.stringValue } shouldContainExactly listOf("Tap", "Untap")
            }
            respondToSelectN(listOf(1))
            passUntilResolved(maxPasses = 12)
            cardByIid(target)?.isTapped shouldBe false
        }

        session(
            "Twiddle taps an own permanent through the native binary prompt",
            fullControl = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true
                humanbattlefield=Island;Forest
                humanhand=Twiddle
                humanlibrary=Forest;Forest
                aibattlefield=Mountain
                ailibrary=Mountain;Mountain
                """,
        ) {
            val target = human.battlefield.iid("Forest")
            cardByIid(target)?.isTapped shouldBe false
            castSpellByName("Twiddle") shouldBe true
            selectTargets(listOf(target))
            passUntilResolved(maxPasses = 12)
            lastSelectNReq().idsList shouldContainExactly listOf(0, 1)
            respondToSelectN(listOf(0))
            passUntilResolved(maxPasses = 12)
            cardByIid(target)?.isTapped shouldBe true
        }
    })
