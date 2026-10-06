package leyline.session.targeting

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.assertGsIdChain

class SearchPromptSessionTest :
    SessionTest({
        session(
            "library search with no eligible cards still exposes the library and completes",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Sylvan Ranger
                humanbattlefield=Forest;Forest
                humanlibrary=Lightning Bolt;Abrade
                ailibrary=Forest;Forest
                """,
            fullControl = true,
        ) {
            castSpellByName("Sylvan Ranger") shouldBe true
            passUntil(maxPasses = 8) { allMessages.lastOrNull()?.hasOptionalActionMessage() == true } shouldBe true
            respondToOptionalAction(true)
            passUntil(maxPasses = 8) { allMessages.lastOrNull()?.hasSearchReq() == true } shouldBe true
            allMessages.last { it.hasSearchReq() }.prompt.promptId shouldBe PromptIds.SEARCH_BASIC_LAND
            val search = allMessages.last { it.hasSearchReq() }.searchReq
            search.zonesToSearchList shouldContainExactly listOf(ZoneIds.libraryOf(SeatId(1)))
            search.itemsToSearchCount shouldBe 2
            search.itemsSoughtList.shouldBeEmpty()
            respondToSearch(emptyList())
            passUntil(maxPasses = 8) { game().stack.isEmpty } shouldBe true
            human.battlefield.card("Sylvan Ranger").name shouldBe "Sylvan Ranger"
        }

        session(
            "an instant-or-flash search with no flash cards uses the native filter prompt and a flat search",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Mystical Teachings
                humanbattlefield=Island;Island;Island;Island
                humanlibrary=Lightning Bolt;Grizzly Bears
                ailibrary=Forest;Forest
                """,
            fullControl = true,
        ) {
            val search = castSpellUntilSearchReq("Mystical Teachings")
            allMessages.last { it.hasSearchReq() }.prompt.promptId shouldBe PromptIds.SEARCH_INSTANT_OR_FLASH
            search.itemsToSearchCount shouldBe 2
            search.itemsSoughtList shouldContainExactly listOf(human.library.iid("Lightning Bolt"))
            respondToSearch(search.itemsSoughtList)
            passUntil(maxPasses = 8) { game().stack.isEmpty } shouldBe true
            human.hand.card("Lightning Bolt").name shouldBe "Lightning Bolt"
            human.library.card("Grizzly Bears").name shouldBe "Grizzly Bears"
        }

        session(
            "an instant-or-flash search without either quality still presents the library and can fail to find",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Mystical Teachings
                humanbattlefield=Island;Island;Island;Island
                humanlibrary=Grizzly Bears;Forest
                ailibrary=Forest;Forest
                """,
            fullControl = true,
        ) {
            val search = castSpellUntilSearchReq("Mystical Teachings")
            allMessages.last { it.hasSearchReq() }.prompt.promptId shouldBe PromptIds.SEARCH_INSTANT_OR_FLASH
            search.itemsToSearchCount shouldBe 2
            search.itemsSoughtList.shouldBeEmpty()
            respondToSearch(emptyList())
            passUntil(maxPasses = 8) { game().stack.isEmpty } shouldBe true
            human.library.card("Grizzly Bears").name shouldBe "Grizzly Bears"
        }

        session(
            "opponent search without matching copies shows that owner's library and completes",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Surgical Extraction
                humanbattlefield=Swamp
                humanlibrary=Mountain;Mountain
                aigraveyard=Lightning Bolt
                ailibrary=Forest;Forest;Forest
                """,
            forgeCatalog = true,
            fullControl = true,
        ) {
            castSpellByName("Surgical Extraction") shouldBe true
            respondToOptionalCost(lastCastingTimeOptionsReq().castingTimeOptionReqList.single().ctoId)
            selectTargets(listOf(ai.graveyard.iid("Lightning Bolt")))
            passUntil(maxPasses = 8) { allMessages.lastOrNull()?.hasSelectNReq() == true } shouldBe true
            respondToSelectN(listOf(ai.graveyard.iid("Lightning Bolt")))
            passUntil(maxPasses = 8) { allMessages.lastOrNull()?.hasSearchReq() == true } shouldBe true
            val search = allMessages.last { it.hasSearchReq() }.searchReq
            search.zonesToSearchList shouldContainExactly listOf(ZoneIds.libraryOf(SeatId(2)))
            search.itemsToSearchCount shouldBe 3
            search.itemsSoughtList.shouldBeEmpty()
            respondToSearch(emptyList())
            passUntil(maxPasses = 8) { game().stack.isEmpty } shouldBe true
            ai.exile.card("Lightning Bolt").name shouldBe "Lightning Bolt"
        }

        session(
            "search response keeps playback diffs before post-search state",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Sylvan Ranger
                humanbattlefield=Forest;Forest
                humanlibrary=Mountain;Mountain
                aibattlefield=Forest
                ailibrary=Forest
                """,
        ) {
            castSpellUntilSearchReq("Sylvan Ranger")
            val searchMessage = allMessages.last { it.hasSearchReq() }
            val searchReq = searchMessage.searchReq
            searchReq.itemsSoughtList.shouldNotBeEmpty()
            check(searchReq.sourceId != 0) { "Triggered search must retain its engine-side source identity" }
            val hostId =
                searchMessage.prompt.parametersList
                    .first()
                    .numberValue
            check(hostId != 0) {
                "Triggered search must retain its host-card identity"
            }
            check(searchReq.sourceId != hostId) { "Triggered search ability and host identities must remain distinct" }
            val requestIndex = allMessages.indexOfLast { it.hasSearchReq() }
            val libraryIids = searchReq.itemsSoughtList.toSet()
            check(
                allMessages
                    .take(requestIndex)
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .any { it.instanceId in libraryIids },
            ) { "Library objects must be published before SearchReq" }

            respondToSearch(listOf(searchReq.itemsSoughtList.first()))

            assertGsIdChain(allMessages, context = "search response playback drain")
        }
    })
