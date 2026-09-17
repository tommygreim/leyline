package leyline.session.zones

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.acceptSettled
import leyline.bridge.handoff.PromptCallStatus
import leyline.bridge.types.SeatId
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.assertAccumulatorConsistent
import leyline.testkit.assertGsIdChain
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.*
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Discard subsystem — both discard-as-cost (spell additional cost)
 * and cleanup discard (hand size enforcement).
 *
 * Board-level discard annotation tests would go in a BoardTest file.
 */
class DiscardInteractionTest :
    SessionTest({

        // --- Discard-as-cost (Mardu Outrider: {1}{B}{B} + discard a card) ---

        val marduState =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=5

            humanhand=Mardu Outrider;Mountain
            humanbattlefield=Swamp;Swamp;Swamp
            humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

        session("discard-as-cost — SelectNReq proto shape", puzzle = marduState, turns = 10) {
            castSpellByName("Mardu Outrider") shouldBe true

            val req = lastSelectNReq()
            assertSoftly {
                req.context shouldBe SelectionContext.Discard_a163
                req.listType shouldBe SelectionListType.Static
                req.optionContext shouldBe OptionContext.Payment
                // Empty selection declines the optional payment; Forge still requires exactly one card to complete it.
                req.minSel shouldBe 0
                req.maxSel shouldBe 1
                req.idsList shouldHaveSize 1
            }
        }

        session("discard-as-cost — spell resolves after responding", puzzle = marduState, turns = 10) {
            castSpellByName("Mardu Outrider") shouldBe true
            val req = lastSelectNReq()
            val mountainId = findInstanceId(req.idsList, "Mountain")
            respondToSelectN(listOf(mountainId))
            passPriority()

            assertSoftly {
                // Outrider on battlefield
                val outriders =
                    human
                        .getZone(ForgeZoneType.Battlefield)
                        .cards
                        .filter { it.name == "Mardu Outrider" }
                outriders shouldHaveSize 1
                outriders.first().netPower shouldBe 5
                outriders.first().netToughness shouldBe 5

                // Discarded Mountain in graveyard — exactly one
                human
                    .getZone(ForgeZoneType.Graveyard)
                    .cards
                    .filter { it.name == "Mountain" } shouldHaveSize 1

                // Original hand cards consumed (runtime continuation may already have
                // carried the game into the next turn's draw step, adding library cards)
                human
                    .getZone(ForgeZoneType.Hand)
                    .cards
                    .filter { it.name == "Mardu Outrider" || it.name == "Mountain" } shouldHaveSize 0

                assertAccumulatorConsistent("after mandatory discard cost")
                assertGsIdChain(allMessages, context = "mandatory discard cost flow")
            }
        }

        session("discard-as-cost — empty controller answer cancels exact payment", puzzle = marduState, turns = 2) {
            castSpellByName("Mardu Outrider") shouldBe true
            val pending =
                bridge.cutCoordinator.cardSelect
                    .current()
                    .shouldNotBeNull()
            pending.kind shouldBe leyline.bridge.handoff.CardSelectKind.Discard
            bridge.cutCoordinator.acceptSettled(leyline.testkit.selectNResp(emptyList()), pending.gameStateId) shouldBe true
            bridge.awaitPriority()

            assertSoftly {
                human
                    .getZone(ForgeZoneType.Hand)
                    .cards
                    .map { it.name }
                    .toSet() shouldBe
                    setOf("Mardu Outrider", "Mountain")
                human.getZone(ForgeZoneType.Graveyard).cards shouldHaveSize 0
                game().stack.isEmpty shouldBe true
            }
        }

        session(
            "targeted reveal discard emits SelectNReq",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Duress
                humanbattlefield=Swamp
                humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp
                aihand=Divination;Walking Corpse;Swamp
                ailibrary=Island;Island;Island;Island;Island
                """,
            turns = 10,
        ) {
            val req = castSpellUntilSelectNReq("Duress")
            val divinationId = findInstanceId(req.idsList, "Divination")
            assertSoftly {
                req.context shouldBe SelectionContext.Resolution_a163
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                req.idsList shouldHaveSize 1
            }

            val response = after { respondToSelectN(listOf(divinationId)) }

            val discarded =
                ai
                    .getZone(ForgeZoneType.Graveyard)
                    .cards
                    .filter { it.name == "Divination" }
            discarded shouldHaveSize 1
            response.messages.persistentAnnotationsOfType(AnnotationType.DisplayCardUnderCard) shouldHaveSize 0
        }

        session(
            "targeted reveal with no matching cards emits an empty SelectNReq and resumes",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Duress
                humanbattlefield=Swamp
                humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp
                aihand=Walking Corpse;Swamp
                ailibrary=Island;Island;Island;Island;Island
                """,
            turns = 10,
        ) {
            val req = castSpellUntilSelectNReq("Duress")
            assertSoftly {
                req.context shouldBe SelectionContext.Resolution_a163
                req.minSel shouldBe 0
                req.maxSel shouldBe 0
                req.idsList shouldHaveSize 0
                req.unfilteredIdsList shouldHaveSize 2
            }

            respondToSelectN(emptyList())

            assertSoftly {
                ai.getZone(ForgeZoneType.Hand).cards shouldHaveSize 2
                ai.getZone(ForgeZoneType.Graveyard).cards shouldHaveSize 0
                game().stack.isEmpty shouldBe true
                bridge
                    .promptBridge(SeatId(1))
                    .journal
                    .activeRevealEntry() shouldBe null
                bridge.cutCoordinator.revealChoices
                    .current() shouldBe null
            }
        }

        session(
            "Deep-Cavern Bat reveal exile emits SelectNReq",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Deep-Cavern Bat
                humanbattlefield=Swamp;Swamp
                humanlibrary=Swamp;Swamp;Swamp;Swamp;Swamp
                aihand=Divination;Walking Corpse;Swamp
                ailibrary=Island;Island;Island;Island;Island
                """,
            turns = 10,
        ) {
            val promptStart = messageSnapshot()
            castSpellByName("Deep-Cavern Bat") shouldBe true
            passUntil(maxPasses = 10) { messagesSince(promptStart).any { it.hasSelectNReq() } } shouldBe true

            val req = messagesSince(promptStart).last { it.hasSelectNReq() }.selectNReq
            val divinationId = findInstanceId(req.idsList, "Divination")
            assertSoftly {
                req.context shouldBe SelectionContext.Resolution_a163
                req.minSel shouldBe 0
                req.maxSel shouldBe 1
                req.idsList shouldHaveSize 2
                req.unfilteredIdsList shouldHaveSize 3
                req.idsList shouldContain divinationId
            }

            val response = after { respondToSelectN(listOf(divinationId)) }

            val batIds =
                accumulator.objects.values
                    .filter { it.grpId == 87246 }
                    .map { it.instanceId }
            val exiledDivinationId = instanceIdOf("Divination", ai, ForgeZoneType.Exile)
            val underCard = response.messages.persistentAnnotationsOfType(AnnotationType.DisplayCardUnderCard).single()
            assertSoftly {
                batIds shouldContain underCard.affectorId
                underCard.affectedIdsList shouldBe listOf(exiledDivinationId)
            }
        }

        // --- Cleanup discard (hand exceeds max hand size) ---

        session(
            "cleanup discard — hand size enforced at end of turn",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Divination;Island;Island;Island;Island;Island;Island
                humanbattlefield=Island;Island;Island
                humanlibrary=Island;Island;Island;Island;Island
                aibattlefield=Centaur Courser
                ailibrary=Island;Island;Island;Island;Island
                """,
            turns = 10,
        ) {
            human.getZone(ForgeZoneType.Hand).size() shouldBe 7

            // Cast Divination (draw 2): hand 7 → 6 (on stack) → resolve → 8
            castSpellByName("Divination") shouldBe true
            // One pass resolves Divination: hand 6 + 2 drawn = 8
            passPriority()
            human.getZone(ForgeZoneType.Hand).size() shouldBe 8

            // Pass through to cleanup and answer the discard prompt explicitly.
            val cleanupStart = messageSnapshot()
            passUntil(maxPasses = 10) {
                messagesSince(cleanupStart).any { it.hasSelectNReq() }
            } shouldBe true

            val req = messagesSince(cleanupStart).last { it.hasSelectNReq() }.selectNReq
            assertSoftly {
                req.context shouldBe SelectionContext.Discard_a163
                req.listType shouldBe SelectionListType.Static
                req.optionContext shouldBe OptionContext.Payment
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                req.idsList shouldHaveSize 8
            }

            respondToSelectN(listOf(req.idsList.first()))

            // Cleanup enforced 8 → 7; runtime continuation may then carry into the next
            // turn's draw step (7 + 1 drawn). Either depth is legitimate —
            // the enforcement itself is proven by the graveyard count below.
            human.getZone(ForgeZoneType.Hand).size() shouldBeInRange 7..8
            // Divination (resolved) + 1 discarded card
            human.getZone(ForgeZoneType.Graveyard).size() shouldBe 2

            // Verify the discard prompt was answered via the bridge
            val discardPrompts =
                bridge
                    .promptBridge(SeatId(1))
                    .history
                    .filter { it.message.contains("iscard", ignoreCase = true) }
            discardPrompts shouldHaveSize 1
            discardPrompts.first().outcome shouldBe PromptCallStatus.RESPONDED
        }

        // --- Unless-type discard (Winternight Stories: discard 2, or 1 if a creature) ---

        session(
            "unless-type discard reaches a real prompt and honors the single-creature branch",
            puzzleFile = "data/puzzles/winternight-stories-unless-discard.pzl",
            turns = 2,
        ) {
            // TargetingCoordinator.chooseCardsToDiscardUnlessType previously built its own
            // PromptRequest with no candidateRefs and the Generic semantic, which resolves
            // to no coordinator-owned runtime: bridge.requestChoice silently returned its
            // default index and the discard was never actually asked of the player.
            castSpellByName("Winternight Stories") shouldBe true
            passUntilResolved(maxPasses = 8)

            val req = lastSelectNReq()
            val bearId = findInstanceId(req.idsList, "Grizzly Bears")
            respondToSelectN(listOf(bearId))

            assertSoftly {
                req.minSel shouldBe 1
                req.maxSel shouldBe 2
                req.idsList shouldHaveSize 3

                // Discarding a single creature satisfies the unless clause: 3 drawn - 1
                // discarded, not 3 - 2.
                human.getZone(ForgeZoneType.Hand).cards.filter { it.name == "Grizzly Bears" } shouldHaveSize 2
                human.getZone(ForgeZoneType.Graveyard).cards.filter { it.name == "Grizzly Bears" } shouldHaveSize 1

                // A real interactive round trip, not a silently defaulted choice.
                val discardPrompts =
                    bridge
                        .promptBridge(SeatId(1))
                        .history
                        .filter { it.message.contains("discard", ignoreCase = true) }
                discardPrompts.shouldNotBeEmpty()
                discardPrompts.last().outcome shouldBe PromptCallStatus.RESPONDED
            }
        }
    })
