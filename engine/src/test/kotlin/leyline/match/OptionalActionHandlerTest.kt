package leyline.match

import forge.game.zone.ZoneType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest

class OptionalActionHandlerTest :
    SessionTest({
        session(
            "accepted optional action publishes a chained search without waiting for action priority",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Formidable Speaker;Forest
                humanbattlefield=Forest;Forest;Forest
                humanlibrary=Grizzly Bears
                ailibrary=Mountain
                """,
            turns = 3,
        ) {
            holdNextOptionalAction()

            castSpellByName("Formidable Speaker") shouldBe true
            passUntil(maxPasses = 4) { allMessages.any { it.hasOptionalActionMessage() } } shouldBe true

            respondToOptionalAction(accept = true)
            val discard = allMessages.lastOrNull { it.hasSelectNReq() } ?: error("Expected discard SelectNReq")
            discard.prompt.promptId shouldBe PromptIds.DISCARD_OPTIONAL
            respondToSelectN(discard.selectNReq.idsList)
            val search = allMessages.lastOrNull { it.hasSearchReq() }?.searchReq ?: error("Expected chained SearchReq")

            search.itemsSoughtCount shouldBe 1
            respondToSearch(search.itemsSoughtList)
            human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Grizzly Bears"
        }
    })
