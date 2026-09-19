package leyline.behavior.cards

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.tooling.headless.HeadlessResponseMode
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType

class RepeatableKeywordCostTest :
    SessionTest({
        session(
            "Shattering Spree asks how many times to pay Replicate",
            responseMode = HeadlessResponseMode.PolicyVisible,
            puzzle =
                """
                [metadata]
                Name:Replicate numeric choice
                Goal:Win
                Turns:1

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Shattering Spree
                humanbattlefield=Mountain;Mountain;Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Golden Egg
                ailibrary=Plains;Plains;Plains
                """.trimIndent(),
        ) {
            val cast = after { castSpellByName("Shattering Spree").shouldBeTrue() }
            val replicate =
                cast
                    .expectCastingTimeOptionsReq {
                        option(CastingTimeOptionType.Replicate, ctoId = 1)
                    }.castingTimeOptionReqList
                    .single()
            replicate.numericInputReq.minValue shouldBe 0
            replicate.numericInputReq.maxValue shouldBe 3

            respondToReplicate(replicate.ctoId, 2)
            passUntil(maxPasses = 8) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
        }
    })
