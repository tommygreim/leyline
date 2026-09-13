package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.config.EngineSettings
import leyline.game.mapping.PromptIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.allAnnotations
import leyline.testkit.annotation
import leyline.testkit.assertAccumulatorConsistent
import leyline.testkit.assertGsIdChain
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beInHandOf
import leyline.testkit.castingTimeOptionsResp
import leyline.testkit.clientMessage
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.findZoneTransfer
import leyline.testkit.firstWithTransferCategory
import leyline.testkit.gameStateMessages
import leyline.testkit.gsm
import leyline.testkit.orderResp
import leyline.testkit.persistentAnnotationsOfType
import leyline.testkit.searchResp
import leyline.testkit.selectNResp
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GroupResp
import wotc.mtgo.gre.external.messaging.Messages.HighlightType
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SelectTargetsResp
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage
import wotc.mtgo.gre.external.messaging.Messages.TargetSelection
import wotc.mtgo.gre.external.messaging.Messages.Target as ProtoTarget
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

/**
 * Session-tier targeting tests — SelectTargetsReq/Resp flow through MatchSession.
 *
 * Absorbs TargetingFlowTest, TwoPhaseTargetingTest, BiteDownTest. Organized by
 * mechanic: single-target creature (Giant Growth), player-targeted burn (Lightning
 * Bolt), two-phase protocol, and multi-group fight (Bite Down).
 *
 * The #92 auto-resolve regression test remains here pending a shared
 * runtime-horizon fixture — see the TODO near the test.
 */
class TargetingInteractionTest :
    SessionTest({

        // ─── Giant Growth: single-target creature buff ─────────────────────────

        session("Giant Growth — prompt shape, select, resolve, zone transfer", puzzleFile = "test-puzzles/pump-spell.pzl") {
            val creatureIid = humanBattlefieldCreatures().first().first

            // Phase 1: prompt shape
            val stReq =
                after { castSpellByName("Giant Growth").shouldBeTrue() }
                    .messages
                    .firstOrNull { it.hasSelectTargetsReq() }
            stReq.shouldNotBeNull()
            val targetSelection = stReq.selectTargetsReq.targetsList.first()
            assertSoftly {
                targetSelection.minTargets shouldBe 1
                targetSelection.maxTargets shouldBe 1
                targetSelection.targetsList.map { it.targetInstanceId } shouldContain creatureIid
            }

            // Phase 2: select + resolve effects
            selectTargets(listOf(creatureIid))
            // Buff may take multiple passes to land (layered effect after resolve).
            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 4 }

            assertSoftly {
                // Creature buffed +3/+3 (Grizzly Bears 2/2 → 5/5)
                val creature = cardByIid(creatureIid)
                creature.shouldNotBeNull()
                creature.netPower shouldBeGreaterThanOrEqual 4
                creature.netToughness shouldBeGreaterThanOrEqual 4

                // Spell moved Stack → GY
                "Giant Growth" should beInGraveyardOf(human)
            }
        }

        session("selecting targets records the iterative echo as answered", puzzleFile = "test-puzzles/pump-spell.pzl") {
            val creatureIid = human.battlefield.iid("Grizzly Bears")
            castSpellByName("Giant Growth")

            selectTargets(listOf(creatureIid))

            // The engine echoes the pick back as a re-prompt and this call submits
            // against it. A driver told nothing would treat the echo as outstanding
            // and answer a window that is already closed.
            // Exactly one: the engine echoes the pick back once per target group.
            takeConsumedPromptMsgIds() shouldHaveSize 1
        }

        session("target selection requires the projected target group index", puzzleFile = "test-puzzles/pump-spell.pzl") {
            val creatureIid = humanBattlefieldCreatures().first().first
            castSpellByName("Giant Growth").shouldBeTrue()
            selectTargetsIterative(listOf(creatureIid))
            val initialPromptMsgId = latestPromptMsgId()
            val before = messageSnapshot()

            send(
                submitWithGsId(leyline.testkit.selectTargetsResp(listOf(creatureIid), targetIdx = 0)),
            )
            drainSink()

            val messages = messagesSince(before)
            messages.none { it.type == GREMessageType.SubmitTargetsResp_695e } shouldBe true
            val rePrompt = messages.last { it.hasSelectTargetsReq() }
            assertSoftly {
                rePrompt.msgId shouldBeGreaterThan initialPromptMsgId
                rePrompt.selectTargetsReq.targetsList
                    .single()
                    .selectedTargets shouldBe 1
            }

            submitTargets()
            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 4 }
            (cardByIid(creatureIid)?.netPower ?: 0) shouldBeGreaterThanOrEqual 4
        }

        session(
            "targeting prompt rejects mismatched response families without consuming the pending route",
            puzzleFile = "test-puzzles/pump-spell.pzl",
        ) {
            val creatureIid = humanBattlefieldCreatures().first().first
            castSpellByName("Giant Growth").shouldBeTrue()
            val promptBefore =
                bridge
                    .cutCoordinator
                    .targeting
                    .current()
                    .shouldNotBeNull()

            // These responses intentionally use the wrong family and have no successor to await.
            send(submitWithGsId(selectNResp(emptyList())))
            send(submitWithGsId(orderResp(emptyList())))
            send(submitWithGsId(searchResp(emptyList())))
            send(submitWithGsId(castingTimeOptionsResp(emptyList())))
            send(
                submitWithGsId(
                    clientMessage(ClientMessageType.GroupResp_097b) {
                        setGroupResp(GroupResp.getDefaultInstance())
                    },
                ),
            )
            drainSink()

            bridge
                .cutCoordinator
                .targeting
                .current()
                ?.interactionId shouldBe promptBefore.interactionId
            selectTargets(listOf(creatureIid))
            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 4 }
            (cardByIid(creatureIid)?.netPower ?: 0) shouldBeGreaterThanOrEqual 4
        }

        session("Giant Growth — invariants hold across targeting flow", puzzleFile = "test-puzzles/pump-spell.pzl") {
            val creatureIid = humanBattlefieldCreatures().first().first

            assertAccumulatorConsistent("before targeting")
            castSpellByName("Giant Growth").shouldBeTrue()
            selectTargets(listOf(creatureIid))
            passUntilResolved()

            assertAccumulatorConsistent("after targeting flow")
            assertGsIdChain(allMessages, context = "targeting flow")
        }

        test("Giant Growth — prompt timeout drains queued playback") {
            val h =
                MatchFlowHarness(
                    engineSettings =
                        EngineSettings(
                            aiSpeed = 0.0,
                            bridgeTimeoutMs = 5_000L,
                            promptFailsafeMs = 100L,
                            aiTurnWaitMs = 500L,
                            mulliganWaitMs = 500L,
                        ),
                )
            try {
                h.connectAndKeepPuzzleText(
                    """
                    [metadata]
                    Name:Targeted Prompt Timeout
                    Goal:Resolve prompt timeout
                    Turns:1

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20

                    humanhand=Giant Growth
                    humanbattlefield=Forest;Grizzly Bears
                    humanlibrary=Forest
                    ailibrary=Mountain
                    """.trimIndent(),
                )

                h.castSpellByName("Giant Growth").shouldBeTrue()
                // Generous deadline, not pacing: under parallel-fork CI load the
                // cast → prompt round trip alone can exceed 5s on a slow runner.
                waitFor(timeoutMs = 20_000L) {
                    h.drainSink()
                    h.allMessages.any { it.hasSelectTargetsReq() }
                }.shouldBeTrue()
                val promptGsId = h.allMessages.last { it.hasSelectTargetsReq() }.gameStateId

                assertSoftly {
                    waitFor(timeoutMs = 20_000L) {
                        h.drainSink()
                        h.allMessages.any { it.gameStateId > promptGsId }
                    }.shouldBeTrue()

                    val postPromptMessages = h.allMessages.filter { it.gameStateId > promptGsId }
                    postPromptMessages.shouldNotBeEmpty()
                    postPromptMessages.first().gameStateId shouldBeGreaterThan promptGsId
                    postPromptMessages.any { it.hasGameStateMessage() }.shouldBeTrue()
                }
            } finally {
                h.shutdown()
            }
        }

        session(
            "Giant Growth — multiple spells stack +3/+3 twice",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Giant Growth;Giant Growth
                humanbattlefield=Grizzly Bears;Forest;Forest
                humanlibrary=Forest;Forest;Forest;Forest;Forest
                aibattlefield=Plains
                ailibrary=Plains;Plains;Plains;Plains;Plains
                """,
        ) {
            val creatureIid = human.battlefield.iid("Grizzly Bears")

            castSpellByName("Giant Growth").shouldBeTrue()
            selectTargets(listOf(creatureIid))
            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 5 }

            castSpellByName("Giant Growth").shouldBeTrue()
            selectTargets(listOf(creatureIid))
            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 8 }

            // Grizzly Bears 2/2 + (+3/+3) × 2 = 8/8
            val creature = cardByIid(creatureIid).shouldNotBeNull()
            assertSoftly {
                creature.netPower shouldBe 8
                creature.netToughness shouldBe 8
            }
        }

        // ─── Cancel targeting ───────────────────────────────────────────────────

        session("cancel unwinds stack, card back in hand, re-cast succeeds", puzzleFile = "test-puzzles/pump-spell.pzl") {
            val creatureIid = humanBattlefieldCreatures().first().first

            // Cast → cancel
            val cancel =
                after {
                    castSpellByName("Giant Growth").shouldBeTrue()
                    cancelAction()
                }

            assertSoftly {
                game().stack.isEmpty.shouldBeTrue()
                // Puzzle has exactly 1 Giant Growth; cancel returns it to hand.
                "Giant Growth" should beInHandOf(human)
                cancel.messages.any { it.hasActionsAvailableReq() }.shouldBeTrue()
            }

            // Re-cast → select → resolve
            castSpellByName("Giant Growth").shouldBeTrue()
            selectTargets(listOf(creatureIid))
            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 4 }

            cardByIid(creatureIid).shouldNotBeNull().netPower shouldBeGreaterThanOrEqual 4
            assertAccumulatorConsistent("after cancel + re-cast")
        }

        // ─── Lightning Bolt: player + creature targeting ───────────────────────

        session(
            "Lightning Bolt — prompt shape, sourceId, resolve deals 3 damage to opponent",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Lightning Bolt
                humanbattlefield=Mountain
                humanlibrary=Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Mountain
                """,
        ) {
            val msgs = after { castSpellByName("Lightning Bolt").shouldBeTrue() }.messages
            val stMsg = msgs.firstOrNull { it.hasSelectTargetsReq() }
            stMsg.shouldNotBeNull()

            val req = stMsg.selectTargetsReq
            val targets = req.targetsList.first().targetsList
            val targetIds = targets.map { it.targetInstanceId }

            // sourceId matches stack iid (post-realloc)
            val gsms = msgs.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            val stackZone =
                gsms
                    .flatMap { it.zonesList }
                    .firstOrNull { it.type == ProtoZoneType.Stack }
            stackZone.shouldNotBeNull()
            val stackInstanceId = stackZone.objectInstanceIdsList.firstOrNull()
            assertSoftly {
                stackInstanceId.shouldNotBeNull()
                // Both players legal
                targetIds shouldContain HUMAN_SEAT
                targetIds shouldContain OPPONENT_SEAT

                // Highlights by role
                targets.first { it.targetInstanceId == OPPONENT_SEAT }.highlight shouldBe HighlightType.Hot
                targets.first { it.targetInstanceId == HUMAN_SEAT }.highlight shouldBe HighlightType.Cold
                val creatureTargets = targets.filter { it.targetInstanceId > OPPONENT_SEAT }
                creatureTargets.shouldNotBeEmpty()
                creatureTargets.forEach { it.highlight shouldBe HighlightType.Tepid }

                // Wrapper flags
                stMsg.allowCancel shouldBe AllowCancel.Abort
                stMsg.allowUndo.shouldBeTrue()

                // sourceId = stack iid
                req.sourceId shouldBe stackInstanceId
            }

            // Resolve → 3 damage to opponent (puzzle puts AI at 3 life)
            val preBoltAiLife = ai.life
            selectTargets(listOf(OPPONENT_SEAT))
            passPriority()

            assertSoftly {
                (preBoltAiLife - ai.life) shouldBe 3
            }
        }

        session("Lightning Bolt — cancel then re-cast deals damage", puzzleFile = "data/puzzles/bolt-face.pzl") {
            castSpellByName("Lightning Bolt").shouldBeTrue()
            cancelAction()
            game().stack.isEmpty.shouldBeTrue()

            val preBoltAiLife = ai.life
            castSpellByName("Lightning Bolt").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            passPriority()

            // 3 damage lands after re-cast
            (preBoltAiLife - ai.life) shouldBe 3
        }

        // ─── PST / PSuT lifecycle annotations ──────────────────────────────────

        session("Lightning Bolt — PST on cast frame, PSuT on submit frame", puzzleFile = "data/puzzles/bolt-face.pzl") {
            val castMessages = after { castSpellByName("Lightning Bolt").shouldBeTrue() }.messages

            val selectTargetsReq = castMessages.firstOrNull { it.hasSelectTargetsReq() }
            selectTargetsReq.shouldNotBeNull()
            val stackIid = selectTargetsReq.selectTargetsReq.sourceId

            // PST rides on the GSM that pairs with SelectTargetsReq — same gsId,
            // affectorId = caster seat, affectedIds = [stackIid].
            val pstFrame =
                castMessages
                    .filter { it.hasGameStateMessage() && it.gameStateId == selectTargetsReq.gameStateId }
                    .map { it.gameStateMessage }
                    .firstOrNull { gsm ->
                        gsm.annotationsList.any { AnnotationType.PlayerSelectingTargets in it.typeList }
                    }
            pstFrame.shouldNotBeNull()
            val pst = pstFrame.annotation(AnnotationType.PlayerSelectingTargets)
            assertSoftly {
                pst.affectorId shouldBe HUMAN_SEAT
                pst.affectedIdsList shouldContain stackIid
                pst.detailsCount shouldBe 0
                pstFrame.findZoneTransfer(stackIid)?.category shouldBe "CastSpell"
            }

            // Submit + drive the engine to the next frame; PSuT lands on the
            // GSM following SubmitTargetsReq with the same shape as PST.
            val submitMessages = after { selectTargets(listOf(OPPONENT_SEAT)) }.messages
            val psutFrame =
                submitMessages
                    .filter { it.hasGameStateMessage() }
                    .map { it.gameStateMessage }
                    .firstOrNull { gsm ->
                        gsm.annotationsList.any { AnnotationType.PlayerSubmittedTargets in it.typeList }
                    }
            psutFrame.shouldNotBeNull()
            val psut = psutFrame.annotation(AnnotationType.PlayerSubmittedTargets)
            assertSoftly {
                psut.affectorId shouldBe HUMAN_SEAT
                psut.affectedIdsList shouldContain stackIid
                psut.detailsCount shouldBe 0
                // PSuT leads the post-submit frame with the frame's ids ascending
                psutFrame.annotationsList.first().typeList shouldContain AnnotationType.PlayerSubmittedTargets
                psutFrame.annotationsList.map { it.id } shouldBe psutFrame.annotationsList.map { it.id }.sorted()
            }
        }

        // ─── Two-phase targeting protocol ──────────────────────────────────────

        val twoPhaseBoltState =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=3

            humanhand=Lightning Bolt
            humanbattlefield=Mountain
            humanlibrary=Mountain
            ailibrary=Mountain
            """.trimIndent()

        session("two-phase — phase-1 echo re-prompt shows selected target as Unselect", puzzle = twoPhaseBoltState) {
            val preBoltAiLife = ai.life
            castSpellByName("Lightning Bolt").shouldBeTrue()

            val echoMessages = after { selectTargetsIterative(listOf(OPPONENT_SEAT)) }.messages

            val rePromptMsg = echoMessages.firstOrNull { it.hasSelectTargetsReq() }
            rePromptMsg.shouldNotBeNull()
            val targetGroup = rePromptMsg.selectTargetsReq.getTargets(0)

            assertSoftly {
                rePromptMsg.hasPrompt().shouldBeTrue()
                rePromptMsg.prompt.promptId shouldBe 10
                rePromptMsg.allowCancel shouldBe AllowCancel.Abort
                rePromptMsg.allowUndo.shouldBeTrue()
                targetGroup.targetsList shouldHaveSize 1
                targetGroup.targetsList[0].targetInstanceId shouldBe OPPONENT_SEAT
                targetGroup.targetsList[0].legalAction shouldBe SelectAction.Unselect
                targetGroup.selectedTargets shouldBe 1
            }

            submitTargets()
            passUntilResolved()

            // Damage landed after submit
            (preBoltAiLife - ai.life) shouldBe 3
        }

        session("two-phase — phase-1 select alone does not resolve spell", puzzle = twoPhaseBoltState) {
            val preBoltAiLife = ai.life
            castSpellByName("Lightning Bolt").shouldBeTrue()

            val phase1Messages = after { selectTargetsIterative(listOf(OPPONENT_SEAT)) }.messages

            assertSoftly {
                // No damage landed yet — spell hasn't resolved
                ai.life shouldBe preBoltAiLife
                phase1Messages.any { it.hasSubmitTargetsResp() }.shouldBeFalse()
                phase1Messages.any { it.hasSelectTargetsReq() }.shouldBeTrue()
            }

            after { submitTargets() }.messages.any { it.hasSubmitTargetsResp() }.shouldBeTrue()

            passUntilResolved()
            // Damage landed only after submit
            (preBoltAiLife - ai.life) shouldBe 3
        }

        session(
            "triggered mandatory single-target selection re-prompts until submit",
            fullControl = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Spellbook Vendor;Grizzly Bears;Plains
                humanlibrary=Plains
                ailibrary=Mountain
                """.trimIndent(),
        ) {
            holdNextOptionalAction()
            passUntil(maxPasses = 4) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            val optional = allMessages.last { it.hasOptionalActionMessage() }
            assertSoftly {
                optional.prompt.promptId shouldBe PromptIds.OPTIONAL_ACTION
                optional.optionalActionMessage.prompt shouldBe optional.prompt
            }
            val promptMessages = after { respondToOptionalAction(accept = true) }.messages
            val vendorIid = human.battlefield.iid("Spellbook Vendor")
            val selecting =
                promptMessages
                    .gameStateMessages()
                    .flatMap { it.annotationsList }
                    .single { AnnotationType.PlayerSelectingTargets in it.typeList }
            val abilityIid = selecting.affectedIdsList.single()

            val phase1Messages = after { selectTargetsIterative(listOf(vendorIid)) }.messages

            assertSoftly {
                // Single-target triggers are not special on the wire: the tap
                // echoes as an iterative re-prompt, no auto-submit.
                phase1Messages.count { it.hasSelectTargetsReq() } shouldBe 1
                phase1Messages.count { it.hasSubmitTargetsResp() } shouldBe 0
            }
            val rePrompt =
                phase1Messages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq
            val group = rePrompt.getTargets(0)
            assertSoftly {
                group.selectedTargets shouldBe 1
                group.targetsList.single().targetInstanceId shouldBe vendorIid
                group.targetsList.single().legalAction shouldBe SelectAction.Unselect
            }
            // Completion still lands on the client's SubmitTargetsReq.
            val submitMessages = after { submitTargets() }.messages
            submitMessages.count { it.hasSubmitTargetsResp() } shouldBe 1
            val submitted =
                submitMessages
                    .gameStateMessages()
                    .flatMap { it.annotationsList }
                    .single { AnnotationType.PlayerSubmittedTargets in it.typeList }
            val targetSpec = submitMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            assertSoftly {
                submitted.affectedIdsList shouldBe listOf(abilityIid)
                targetSpec.affectorId shouldBe abilityIid
            }
        }

        // ─── Run Away Together: multi-target + TargetsWithDifferentControllers ──

        val runAwayTogetherState =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Run Away Together
            humanbattlefield=Island;Island;Grizzly Bears
            humanlibrary=Island
            aibattlefield=Coral Merfolk
            ailibrary=Island
            """.trimIndent()

        session("Run Away Together — initial prompt: both creatures legal with min=max=2", puzzle = runAwayTogetherState) {
            val stMsg =
                after { castSpellByName("Run Away Together").shouldBeTrue() }
                    .messages
                    .firstOrNull { it.hasSelectTargetsReq() }
            stMsg.shouldNotBeNull()
            val group = stMsg.selectTargetsReq.getTargets(0)

            val humanBearsIid = human.battlefield.iid("Grizzly Bears")
            val aiMerfolkIid = ai.battlefield.iid("Coral Merfolk")

            assertSoftly {
                group.minTargets shouldBe 2
                group.maxTargets shouldBe 2
                group.selectedTargets shouldBe 0
                group.targetsList shouldHaveSize 2
                group.targetsList.map { it.targetInstanceId } shouldContain humanBearsIid
                group.targetsList.map { it.targetInstanceId } shouldContain aiMerfolkIid
                group.targetsList.forEach {
                    it.legalAction shouldBe SelectAction.Select_a1ad
                    it.highlight shouldBe HighlightType.Tepid
                }
            }
        }

        session("Run Away Together — re-prompt: picked = Unselect, opposite-controller = Select+Tepid", puzzle = runAwayTogetherState) {
            val humanBearsIid = human.battlefield.iid("Grizzly Bears")
            val aiMerfolkIid = ai.battlefield.iid("Coral Merfolk")

            castSpellByName("Run Away Together").shouldBeTrue()

            val rePromptMsg =
                after { selectTargetsIterative(listOf(humanBearsIid)) }
                    .messages
                    .firstOrNull { it.hasSelectTargetsReq() }
            rePromptMsg.shouldNotBeNull()
            val group = rePromptMsg.selectTargetsReq.getTargets(0)

            assertSoftly {
                group.minTargets shouldBe 2
                group.maxTargets shouldBe 2
                group.selectedTargets shouldBe 1
                group.targetsList shouldHaveSize 2

                val pickedEntry = group.targetsList.first { it.targetInstanceId == humanBearsIid }
                pickedEntry.legalAction shouldBe SelectAction.Unselect

                val remainingEntry = group.targetsList.first { it.targetInstanceId == aiMerfolkIid }
                remainingEntry.legalAction shouldBe SelectAction.Select_a1ad
                remainingEntry.highlight shouldBe HighlightType.Tepid
            }
        }

        session("Run Away Together — Unselect tap removes from accumulation", puzzle = runAwayTogetherState) {
            val humanBearsIid = human.battlefield.iid("Grizzly Bears")
            val aiMerfolkIid = ai.battlefield.iid("Coral Merfolk")

            castSpellByName("Run Away Together").shouldBeTrue()

            // Pick Grizzly, then tap it again with legalAction=Unselect — accumulation clears.
            selectTargetsIterative(listOf(humanBearsIid))
            send(
                submitWithGsId(
                    clientMessage(ClientMessageType.SelectTargetsResp_097b) {
                        setSelectTargetsResp(
                            SelectTargetsResp.newBuilder().setTarget(
                                TargetSelection.newBuilder().setTargetIdx(1).addTargets(
                                    ProtoTarget
                                        .newBuilder()
                                        .setTargetInstanceId(humanBearsIid)
                                        .setLegalAction(SelectAction.Unselect),
                                ),
                            ),
                        )
                    },
                ),
            )
            drainSink()

            // Re-prompt after Unselect: both creatures selectable, selectedTargets=0.
            // Trigger one more tap to observe the latest re-prompt.
            val rePromptMsg =
                after { selectTargetsIterative(listOf(aiMerfolkIid)) }
                    .messages
                    .firstOrNull { it.hasSelectTargetsReq() }
            rePromptMsg.shouldNotBeNull()
            val group = rePromptMsg.selectTargetsReq.getTargets(0)

            assertSoftly {
                // Only Merfolk is picked now; Grizzly should be Select (opposite-controller still legal).
                group.selectedTargets shouldBe 1
                val picked = group.targetsList.first { it.targetInstanceId == aiMerfolkIid }
                picked.legalAction shouldBe SelectAction.Unselect
                val remaining = group.targetsList.first { it.targetInstanceId == humanBearsIid }
                remaining.legalAction shouldBe SelectAction.Select_a1ad
            }
        }

        session("Run Away Together — submit both: creatures return to owners' hands", puzzle = runAwayTogetherState) {
            val humanBearsIid = human.battlefield.iid("Grizzly Bears")
            val aiMerfolkIid = ai.battlefield.iid("Coral Merfolk")

            castSpellByName("Run Away Together").shouldBeTrue()
            // Real client sends one tap per SelectTargetsResp — server accumulates.
            selectTargetsIterative(listOf(humanBearsIid))
            selectTargetsIterative(listOf(aiMerfolkIid))
            submitTargets()
            passUntilResolved()

            assertSoftly {
                "Grizzly Bears" should beInHandOf(human)
                "Coral Merfolk" should beInHandOf(ai)
            }
        }

        // ─── Bite Down: multi-group fight targeting ────────────────────────────

        session("Bite Down — resolution state: damage, destroy, target in GY", puzzleFile = "data/puzzles/bite-down.pzl") {
            val dealerIid = human.battlefield.iid("Grizzly Bears")
            val targetIid = ai.battlefield.iid("Grizzly Bears")

            castSpellByName("Bite Down").shouldBeTrue()
            selectTargets(listOf(dealerIid))
            selectTargets(listOf(targetIid))

            val damageAnn =
                allMessages
                    .allAnnotations()
                    .annotation(AnnotationType.DamageDealt_af5a)
            assertSoftly {
                // affectorId = dealing creature (not the spell iid)
                damageAnn.affectorId shouldBe dealerIid
                // Damage amount = dealer power (Grizzly Bears = 2)
                damageAnn.detailInt("damage") shouldBe 2
                // Bite Down is one-sided spell damage, not the Fight keyword action.
                damageAnn.detailInt("type") shouldBe 2
                // affectedIds = reallocated target iid
                damageAnn.affectedIdsCount shouldBe 1
                damageAnn.getAffectedIds(0) shouldBeGreaterThan 0

                // Lethal fight damage death rides the damage SBA category
                allMessages.firstWithTransferCategory("SBA_Damage").shouldNotBeNull()

                // Bite Down → human GY, Grizzly Bears → ai GY
                "Bite Down" should beInGraveyardOf(human)
                "Grizzly Bears" should beInGraveyardOf(ai)

                assertAccumulatorConsistent("after Bite Down resolution")
            }
        }

        session("Bite Down — two TargetSpec persistent annotations, cleaned up on resolve", puzzleFile = "data/puzzles/bite-down.pzl") {
            val dealerIid = human.battlefield.iid("Grizzly Bears")
            val targetIid = ai.battlefield.iid("Grizzly Bears")

            castSpellByName("Bite Down").shouldBeTrue()
            val firstTargetSlice = after { selectTargets(listOf(dealerIid)) }
            val group1 =
                firstTargetSlice.messages
                    .persistentAnnotationsOfType(AnnotationType.TargetSpec)
                    .single { it.detailInt("index") == 1 }
            val secondTargetSlice = after { selectTargets(listOf(targetIid)) }
            val group2Gsm =
                secondTargetSlice.messages
                    .gameStateMessages()
                    .single { gsm ->
                        gsm.persistentAnnotationsList.any {
                            AnnotationType.TargetSpec in it.typeList && it.detailInt("index") == 2
                        }
                    }
            val group2 =
                group2Gsm.persistentAnnotationsList.single {
                    AnnotationType.TargetSpec in it.typeList && it.detailInt("index") == 2
                }
            val preResolve = listOf(group1, group2)

            assertSoftly {
                group2Gsm.diffDeletedPersistentAnnotationIdsList shouldNotContain group1.id
                group1.getAffectedIds(0) shouldNotBe group2.getAffectedIds(0)
                group1.detailInt("abilityGrpId") shouldBe group2.detailInt("abilityGrpId")
                group1.detailInt("abilityGrpId") shouldBeGreaterThan 0
                group1.detailInt("promptParameters") shouldBe group2.detailInt("promptParameters")
            }

            // Resolve/cleanup can span more than one tick: wait until both
            // target specs emitted for the two target groups have been deleted.
            val preResolveIds = preResolve.map { it.id }
            passUntil(maxPasses = 6) {
                val deletedIds =
                    allMessages
                        .deletedPersistentAnnotationIds()
                preResolveIds.all { it in deletedIds }
            }
            val allDeletedPannIds =
                allMessages
                    .deletedPersistentAnnotationIds()
            withClue("preResolve=$preResolveIds deleted=$allDeletedPannIds") {
                preResolveIds.all { it in allDeletedPannIds }.shouldBeTrue()
            }
        }

        // ─── Runtime-horizon regression #92 ────────────────────────────────────

        // TODO: Relocate to a shared runtime-horizon fixture when one exists —
        // this test is about handlePostCastPrompt / auto-resolution, not targeting.
        session(
            "#92 — non-targeted spell does not prompt Resolve while on stack",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Forest
                humanhand=Llanowar Elves
                humanlibrary=Forest;Forest;Forest;Forest;Forest
                aibattlefield=Mountain
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """,
            turns = 5,
        ) {
            // Simulate reference-client settings: auto-resolve own stack effects
            updateSettings(
                SettingsMessage
                    .newBuilder()
                    .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                    .build(),
            )

            castCreature().shouldBeTrue()

            // Before the fix, castCreature() would leave the creature on the stack
            // (ActionsAvailableReq shown as "Resolve" button) instead of auto-resolving.
            assertSoftly {
                humanBattlefieldCreatures().map { it.second } shouldContain "Llanowar Elves"
                game().stack.size() shouldBe 0
            }
        }
    })

@Suppress("NoThreadSleepInTests")
private fun waitFor(
    timeoutMs: Long,
    predicate: () -> Boolean,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) return true
        Thread.sleep(20)
    }
    return predicate()
}
