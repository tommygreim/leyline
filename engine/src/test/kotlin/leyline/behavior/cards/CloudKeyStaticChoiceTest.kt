package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.bridge.types.StaticChoiceIds
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

/**
 * `chooseSomeType("Card", ...)` — before this, every "choose a card type"
 * effect (`ChooseTypeEffect`'s `Type$ Card`) fell through to
 * `StaticChoiceIds.subtypeIdFor`, which never matches a card-type name
 * ("Artifact" isn't a subtype), so `choices` was always empty and the
 * player never saw a prompt at all — the first valid type was silently
 * auto-picked. See ISSUES.md I40.
 */
class CloudKeyStaticChoiceTest :
    SessionTest({
        session(
            "Cloud Key's card-type choice is a real prompt on the CardTypes static domain",
            puzzle = """
                [metadata]
                Name:Cloud Key Card Type Choice
                Goal:Play the Specified Permanent
                Turns:3
                Difficulty:Easy
                Description:Cast Cloud Key and answer its card-type choice.
                Targets:Cloud Key

                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain;Mountain;Mountain
                humanhand=Cloud Key
                humanlibrary=Mountain
                ailibrary=Forest
                """,
        ) {
            val req = castSpellUntilSelectNReq("Cloud Key")

            assertSoftly {
                req.listType shouldBe SelectionListType.StaticSubset
                // Not Resolution: that context makes the client show the opponent's hand.
                req.context shouldBe SelectionContext.Replacement_a163
                req.staticList shouldBe StaticList.CardTypes
                req.idsList shouldContainExactlyInAnyOrder
                    listOf("Artifact", "Creature", "Enchantment", "Instant", "Sorcery").map {
                        StaticChoiceIds.cardTypeIdFor(it)!!
                    }
                req.idsList shouldContain StaticChoiceIds.cardTypeIdFor("Creature")!!
            }
        }
    })
