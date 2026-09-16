package leyline.session.turns

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.session.combat.COMBAT_DECK
import leyline.testkit.AI_FIRST_SEED
import leyline.testkit.ScriptedAction
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.detail
import leyline.testkit.detailInt
import leyline.testkit.firstWithTransferCategory
import leyline.testkit.gsm
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Phase

/**
 * Session-tier AI-turn tests — MatchSession behavior during opponent turns.
 *
 * Absorbs AiFirstTurnShapeTest, AiTurnNoAarTest, AiLandPlayOrderTest. Organized
 * by concern: boot-time wire conformance (seed-based full game start), AI-turn
 * message properties (puzzle-based for determinism), and AI land-play diff
 * discipline (scripted AI).
 *
 * AiTurnConformanceTest is NOT absorbed here — it runs below MatchSession.
 * Tracked separately for board-tier migration.
 */
class AiTurnInteractionTest :
    SessionTest({

        fun aiTurnActionsAvailableReqs(messages: List<GREToClientMessage>): List<ActionsAvailableReq> {
            val aars = mutableListOf<ActionsAvailableReq>()
            var lastActivePlayer = OPPONENT_SEAT
            for (msg in messages) {
                if (msg.hasGameStateMessage() && msg.gameStateMessage.hasTurnInfo()) {
                    lastActivePlayer = msg.gameStateMessage.turnInfo.activePlayer
                }
                if (msg.type == GREMessageType.ActionsAvailableReq_695e && lastActivePlayer == OPPONENT_SEAT) {
                    aars.add(msg.actionsAvailableReq)
                }
            }
            return aars
        }

        // ─── Boot wire conformance (seed-based, real game start) ────────────────

        session("AI-first boot — ≤1 Full post-handshake + phaseTransitionDiff pattern", seed = AI_FIRST_SEED) {
            val handshakeEnd = allMessages.indexOfLast { it.type == GREMessageType.MulliganReq_aa0d }
            val postHandshakeMessages = allMessages.drop(handshakeEnd + 1)
            val gsms =
                postHandshakeMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }

            // ≤1 Full GSM with zones — no heavyweight Full spam
            val fullWithZones =
                gsms
                    .filter { it.type == GameStateType.Full && it.zonesCount > 0 }

            // Find the phaseTransitionDiff start: first Diff GSM with gameInfo
            val ptStart = gsms.indexOfFirst { it.type == GameStateType.Diff && it.hasGameInfo() }

            assertSoftly {
                fullWithZones.size shouldBeLessThanOrEqual 1

                ptStart shouldBeGreaterThanOrEqual 0
                gsms.size shouldBeGreaterThanOrEqual (ptStart + 3)
                aiTurnActionsAvailableReqs(postHandshakeMessages).shouldBeEmpty()
            }

            // GSM N+0: SendHiFi with one PhaseOrStepModified per step traversed + gameInfo
            val gsm0 = gsms[ptStart]
            val phaseAnns0 =
                gsm0.annotationsList
                    .filter { AnnotationType.PhaseOrStepModified in it.typeList }

            // GSM N+1: SendHiFi echo with turnInfo
            val gsm1 = gsms[ptStart + 1]

            // GSM N+2: SendAndRecord with 1 PhaseOrStepModified
            val gsm2 = gsms[ptStart + 2]
            val phaseAnns2 =
                gsm2.annotationsList
                    .filter { AnnotationType.PhaseOrStepModified in it.typeList }

            assertSoftly {
                gsm0.type shouldBe GameStateType.Diff
                gsm0.update shouldBe GameStateUpdate.SendHiFi
                gsm0.hasGameInfo().shouldBeTrue()
                // Arena sends one annotation per step actually traversed and never
                // repeats a phase/step pair in a frame; match that contract.
                phaseAnns0.size shouldBeGreaterThanOrEqual 1
                val pairs0 = phaseAnns0.map { it.detailInt("phase") to it.detailInt("step") }
                pairs0 shouldBe pairs0.distinct()

                gsm1.type shouldBe GameStateType.Diff
                gsm1.update shouldBe GameStateUpdate.SendHiFi
                gsm1.hasTurnInfo().shouldBeTrue()

                gsm2.type shouldBe GameStateType.Diff
                gsm2.update shouldBe GameStateUpdate.SendAndRecord
                phaseAnns2 shouldHaveSize 1
                phaseAnns2.single().id shouldBe gsm0.annotationsList.maxOf { it.id } + 1
            }
        }

        // ─── AI-turn message properties (puzzle-based, deterministic) ───────────

        session(
            "AI turn — every phase transition annotated with PhaseOrStepModified",
            puzzle = """
                ActivePlayer=AI
                ActivePhase=MAIN1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
            turns = 3,
        ) {
            val startTurn = turn()
            passUntil(maxPasses = 40) { isGameOver() || turn() > startTurn }

            val gsmsWithTurnInfo =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
                    .filter { it.hasTurnInfo() }

            val phaseChanges = mutableListOf<Int>()
            for (i in 1 until gsmsWithTurnInfo.size) {
                val prev = gsmsWithTurnInfo[i - 1].turnInfo
                val curr = gsmsWithTurnInfo[i].turnInfo
                if (curr.phase != prev.phase || curr.step != prev.step) {
                    phaseChanges.add(i)
                }
            }
            phaseChanges.map { gsmsWithTurnInfo[it].turnInfo.phase } shouldContain Phase.Combat_a549

            val missing =
                phaseChanges.filter { i ->
                    gsmsWithTurnInfo[i].annotationsList.none { ann ->
                        AnnotationType.PhaseOrStepModified in ann.typeList
                    }
                }
            if (missing.isNotEmpty()) {
                val report =
                    buildString {
                        appendLine("${missing.size}/${phaseChanges.size} phase transitions missing PhaseOrStepModified:")
                        missing.take(5).forEach { i ->
                            val gsm = gsmsWithTurnInfo[i]
                            appendLine(
                                "  gsId=${gsm.gameStateId} " +
                                    "phase=${gsm.turnInfo.phase}/${gsm.turnInfo.step} update=${gsm.update}",
                            )
                        }
                    }
                withClue(report) {
                    missing shouldBe emptyList()
                }
            }
        }

        session(
            "AI turn with no legal instant-speed actions emits no ActionsAvailableReq",
            puzzle = """
                ActivePlayer=AI
                ActivePhase=MAIN1
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
            turns = 3,
        ) {
            val startTurn = turn()
            val turnSlice = after { passUntil(maxPasses = 30) { isGameOver() || turn() > startTurn } }

            aiTurnActionsAvailableReqs(turnSlice.messages).shouldBeEmpty()
        }

        session(
            "AI turn with legal instant-speed cast emits ActionsAvailableReq",
            puzzle = """
                ActivePlayer=AI
                ActivePhase=MAIN1
                HumanLife=20
                AILife=20

                humanhand=Burst Lightning
                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
            turns = 3,
        ) {
            val aiTurnAars = aiTurnActionsAvailableReqs(allMessages)
            aiTurnAars shouldHaveSize 1
            aiTurnAars.single().actionsList.map { it.actionType } shouldContain ActionType.Cast
        }

        session(
            "turnInfo phase never stale during AI combat",
            puzzle = """
                ActivePlayer=AI
                ActivePhase=COMBAT_DECLARE_ATTACKERS
                HumanLife=20
                AILife=20

                humanbattlefield=Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Raging Goblin|Attacking|Tapped;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
            turns = 3,
        ) {
            // Use full message history — combat resolved during onPuzzleStart
            val gsms =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
                    .filter { it.hasTurnInfo() && it.turnInfo.activePlayer == OPPONENT_SEAT }

            gsms.map { it.turnInfo.phase } shouldContain Phase.Combat_a549

            // Filter to the combat turn only
            val combatTurn = gsms.first().turnInfo.turnNumber
            val sameTurnGsms = gsms.filter { it.turnInfo.turnNumber == combatTurn }
            val stalePhases =
                sameTurnGsms
                    .map { it.turnInfo.phase }
                    .filter { it == Phase.Beginning_a549 || it == Phase.Main1_a549 }
            stalePhases shouldBe emptyList()
        }

        // ─── AI land-play diff discipline (scripted AI) ─────────────────────────

        val scriptedLandThenGoblin =
            listOf(
                ScriptedAction.PlayLand("Mountain"),
                ScriptedAction.CastSpell("Raging Goblin"),
                ScriptedAction.PassPriority,
                ScriptedAction.DeclareNoAttackers,
                ScriptedAction.PassPriority,
            )

        session("AI land play precedes CastSpell in one completed-step frame", deckList = COMBAT_DECK, seed = 42L) {
            installScriptedAi(scriptedLandThenGoblin)
            passUntilTurn(3)

            val gsMessages = allMessages.filter { it.hasGameStateMessage() }

            val playLandMsg = gsMessages.firstWithTransferCategory("PlayLand")
            val castSpellMsg = gsMessages.firstWithTransferCategory("CastSpell")
            playLandMsg.shouldNotBeNull()
            castSpellMsg.shouldNotBeNull()

            val playLandGsm = playLandMsg.gameStateMessage
            val castSpellGsm = castSpellMsg.gameStateMessage

            // --- PlayLand diff facets ---
            val annTypes = playLandGsm.annotationsList.map { it.typeList.firstOrNull() }
            val userAction =
                playLandGsm.annotationsList.first {
                    it.typeList.contains(AnnotationType.UserActionTaken)
                }
            val landObj =
                playLandGsm.gameObjectsList.firstOrNull { obj ->
                    obj.cardTypesList.contains(CardType.Land_a80b) && obj.zoneId == 28
                }
            val creatureOnStack =
                playLandGsm.gameObjectsList.firstOrNull { obj ->
                    obj.cardTypesList.contains(CardType.Creature) && obj.zoneId == 27
                }

            // --- CastSpell diff facets ---
            val transferCategories =
                playLandGsm.annotationsList
                    .filter { AnnotationType.ZoneTransfer_af5a in it.typeList }
                    .mapNotNull { it.detail("category")?.valueStringList?.firstOrNull() }
            val combinedFrames =
                gsMessages.count { message ->
                    message.gameStateMessage.annotationsList.any { ann ->
                        AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                            ann.detail("category")?.valueStringList?.firstOrNull() in setOf("PlayLand", "CastSpell")
                    }
                }

            assertSoftly {
                playLandGsm.gameStateId shouldBe castSpellGsm.gameStateId
                combinedFrames shouldBe 1
                transferCategories.indexOf("PlayLand") shouldBeLessThan transferCategories.indexOf("CastSpell")

                // PlayLand annotation triple
                annTypes shouldContain AnnotationType.ObjectIdChanged
                annTypes shouldContain AnnotationType.ZoneTransfer_af5a
                annTypes shouldContain AnnotationType.UserActionTaken
                userAction.detailInt("actionType") shouldBe 3

                // The completed-step cut includes both final objects.
                landObj.shouldNotBeNull()
                creatureOnStack.shouldNotBeNull()
            }
        }

        session("AI-first land play not discarded (default AI, no script)", deckList = COMBAT_DECK, seed = 2L) {
            passUntilTurn(2)

            val playLandMessages =
                allMessages.filter { it.hasGameStateMessage() }.filter { gre ->
                    val gsm = gre.gameStateMessage
                    gsm.annotationsList.any { ann ->
                        AnnotationType.ZoneTransfer_af5a in ann.typeList &&
                            ann.detail("category")?.getValueString(0) == "PlayLand"
                    }
                }
            playLandMessages.size shouldBe 1
        }
    })
