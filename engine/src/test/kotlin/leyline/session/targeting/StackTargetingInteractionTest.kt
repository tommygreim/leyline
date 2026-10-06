package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.data.AbilityInfo
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.gameStateMessages
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CardMechanicType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import forge.game.zone.ZoneType as ForgeZoneType

class StackTargetingInteractionTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Death to Our Enemies")
            TestCardRegistry.ensureCardRegistered("Ugin, Eye of the Storms")
            // The fourth-plan trigger owns a separate, hidden client ability
            // row for its reflexive "when you do" damage choice.
            TestCardRegistry.repo.registerAbilityInfo(
                206203,
                AbilityInfo(
                    baseId = 0,
                    manaCost = emptyList(),
                    category = 2,
                    hiddenAbilityIds = listOf(206398),
                ),
            )
            TestCardRegistry.repo.registerAbilityInfo(
                206398,
                AbilityInfo(baseId = 0, manaCost = emptyList(), category = 2),
            )
        }

        fun MatchFlowHarness.latestTargetCandidateIds(): List<Int> =
            allMessages
                .last { it.hasSelectTargetsReq() }
                .selectTargetsReq
                .targetsList
                .flatMap { it.targetsList }
                .map { it.targetInstanceId }
                .filter { it > OPPONENT_SEAT }

        fun MatchFlowHarness.targetPromptDebug(cardName: String): String {
            val prompts =
                allMessages
                    .filter { it.hasSelectTargetsReq() }
                    .takeLast(3)
                    .map { msg ->
                        val ids =
                            msg.selectTargetsReq.targetsList
                                .flatMap { it.targetsList }
                                .map { it.targetInstanceId }
                        val names = ids.map { iid -> iid to (cardByIid(iid)?.name ?: "<no card>") }
                        "gs=${msg.gameStateId} source=${msg.selectTargetsReq.sourceId} ids=$names"
                    }
            val history =
                bridge
                    .promptBridge(SeatId(HUMAN_SEAT))
                    .history
                    .takeLast(5)
                    .map { "${it.promptType}/${it.semantic} ${it.message} candidates=${it.candidateCount} result=${it.result}" }
            return "Expected target '$cardName'. prompts=$prompts history=$history"
        }

        fun MatchFlowHarness.latestTargetIidByCardName(cardName: String): Int {
            val found =
                waitFor(timeoutMs = 2_000L) {
                    drainSink()
                    latestTargetCandidateIds().any { iid -> cardByIid(iid)?.name == cardName }
                }
            withClue(targetPromptDebug(cardName)) { found.shouldBeTrue() }
            val candidateIds = latestTargetCandidateIds()
            return findInstanceId(candidateIds, cardName)
        }

        fun MatchFlowHarness.latestTargetSourceName(): String? {
            val sourceId =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq
                    .sourceId
            return cardByIid(sourceId)?.name
        }

        session(
            "equipment target and stack objects use Equip rather than a separate discard activation",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Mjölnir, Hammer of Thor;Ragavan, Nimble Pilferer;Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Forest;Forest;Forest
            """,
        ) {
            activateAbility("Mjölnir, Hammer of Thor").shouldBeTrue()
            val prompt = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq
            prompt.targetsList.single().targetingAbilityGrpId shouldBe 206227
            val projected = allMessages.gameStateMessages().flatMap { it.gameObjectsList }.filter { it.instanceId == prompt.sourceId }
            projected.map { it.grpId }.distinct() shouldBe listOf(206227)

            selectTargets(listOf(human.battlefield.iid("Ragavan, Nimble Pilferer")))
            passUntilResolved()
            assertSoftly {
                human.battlefield.card("Mjölnir, Hammer of Thor").attachedTo shouldBe human.battlefield.card("Ragavan, Nimble Pilferer")
                human.battlefield.card("Ragavan, Nimble Pilferer").damage shouldBe 0
                ai.battlefield.card("Grizzly Bears").damage shouldBe 0
            }
        }

        session(
            "targeted instant can be cast while another targeted instant is on stack",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock;Lightning Bolt
                humanbattlefield=Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains;Plains;Plains
                """,
        ) {
            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))

            assertSoftly {
                hasPendingAction().shouldBeTrue()
                castSpellByName("Lightning Bolt").shouldBeTrue()

                human
                    .getZone(ForgeZoneType.Hand)
                    .cards
                    .any { it.name == "Lightning Bolt" }
                    .shouldBeFalse()
                game().stackZone.size() shouldBe 2

                // The client reads objectInstanceIds[0] as the top of the stack
                // (MtgGameState.GetTopCardOnStack), so the response must lead the
                // list — otherwise it draws behind what it answers and every
                // top-of-stack lookup resolves to the wrong object.
                val stackZone =
                    allMessages
                        .gameStateMessages()
                        .flatMap { gsm -> gsm.zonesList.filter { it.zoneId == ZoneIds.STACK } }
                        .last { it.objectInstanceIdsCount == 2 }
                stackZone.objectInstanceIdsList.map { cardByIid(it)?.name } shouldBe
                    listOf("Lightning Bolt", "Shock")
            }
        }

        session(
            "Counterspell highlights its stack target with HighlightType.Counterspell",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock;Counterspell
                humanbattlefield=Mountain;Island;Island
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains
                """,
        ) {
            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))

            castSpellByName("Counterspell").shouldBeTrue()
            val shockTarget = latestTargetIidByCardName("Shock")

            val target =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq
                    .targetsList
                    .flatMap { it.targetsList }
                    .single { it.targetInstanceId == shockTarget }
            target.highlight shouldBe wotc.mtgo.gre.external.messaging.Messages.HighlightType.Counterspell
        }

        session(
            "Aether Gust exposes both its stack and battlefield targets",
            fullControl = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Giant Growth;Aether Gust
                humanbattlefield=Forest;Island;Island;Grizzly Bears
                humanlibrary=Forest;Forest;Forest
                ailibrary=Plains;Plains;Plains
                """.trimIndent(),
        ) {
            val bearIid = human.battlefield.iid("Grizzly Bears")
            castSpellByName("Giant Growth").shouldBeTrue()
            selectTargets(listOf(bearIid))

            val promptStart = messageSnapshot()
            castSpellByName("Aether Gust").shouldBeTrue()
            val targetIds =
                messagesSince(promptStart)
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq
                    .targetsList
                    .flatMap { it.targetsList }
                    .map { it.targetInstanceId }
            val giantGrowthTarget = targetIds.single { cardByIid(it)?.name == "Giant Growth" }
            val bearTarget = targetIds.single { cardByIid(it)?.name == "Grizzly Bears" }

            assertSoftly {
                cardByIid(giantGrowthTarget)?.name shouldBe "Giant Growth"
                bearTarget shouldBe bearIid
            }
            selectTargets(listOf(bearTarget))
        }

        session(
            "Aether Gust offers top or bottom for a battlefield permanent",
            fullControl = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Aether Gust
                humanbattlefield=Forest;Island;Island;Grizzly Bears
                humanlibrary=Forest;Forest;Forest
                ailibrary=Plains;Plains;Plains
                """.trimIndent(),
        ) {
            holdNextOptionalAction()
            val bearIid = human.battlefield.iid("Grizzly Bears")
            castSpellByName("Aether Gust").shouldBeTrue()
            selectTargets(listOf(bearIid))
            passUntil(maxPasses = 8) {
                allMessages.any { it.type == GREMessageType.OptionalActionMessage_695e }
            }

            val optional = allMessages.last { it.type == GREMessageType.OptionalActionMessage_695e }.optionalActionMessage
            assertSoftly {
                optional.optionalActionTypesList shouldBe listOf(CardMechanicType.PutTopOrBottom)
                optional.recipientIdsList shouldBe listOf(bearIid)
                optional.prompt.parametersList.map { it.numberValue } shouldBe listOf(optional.sourceId, 1, 1)
            }
            respondToOptionalAction(false)
            passUntilResolved(maxPasses = 8)

            human
                .getZone(ForgeZoneType.Library)
                .cards
                .last()
                .name shouldBe "Grizzly Bears"
            val bear = human.library.card("Grizzly Bears")
            bridge.projectionStateSnapshot().libraryKnowledge.viewers[ForgeCardId(bear.id)] shouldBe
                setOf(SeatId(HUMAN_SEAT), SeatId(OPPONENT_SEAT))
            passPriority()
            waitFor(timeoutMs = 2_000L) {
                drainSink()
                checkNotNull(
                    bridge
                        .projectionStateSnapshot()
                        .viewerCursors
                        .getValue(SeatId(HUMAN_SEAT))
                        .fullState,
                ).turnInfo.phase !=
                    wotc.mtgo.gre.external.messaging.Messages.Phase.Main1_a549
            }.shouldBeTrue()
            val cursor = bridge.projectionStateSnapshot().viewerCursors.getValue(SeatId(HUMAN_SEAT))
            checkNotNull(cursor.fullState)
                .gameObjectsList
                .single { it.instanceId == human.library.iid("Grizzly Bears") }
                .visibility shouldBe wotc.mtgo.gre.external.messaging.Messages.Visibility.Public
        }

        session(
            "Aether Gust accepts the top placement and keeps the public card known",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Aether Gust
                humanbattlefield=Island;Island;Grizzly Bears
                humanlibrary=Forest;Forest;Forest
                ailibrary=Plains;Plains;Plains
            """,
        ) {
            holdNextOptionalAction()
            castSpellByName("Aether Gust").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
            passUntil(maxPasses = 8) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            respondToOptionalAction(true)
            passUntilResolved(maxPasses = 8)
            human
                .getZone(ForgeZoneType.Library)
                .cards
                .first()
                .name shouldBe "Grizzly Bears"
            val bearIid = human.library.iid("Grizzly Bears")
            val bear = human.library.card("Grizzly Bears")
            bridge.projectionStateSnapshot().libraryKnowledge.viewers[ForgeCardId(bear.id)] shouldBe
                setOf(SeatId(HUMAN_SEAT), SeatId(OPPONENT_SEAT))
            val remembered =
                allMessages
                    .gameStateMessages()
                    .flatMap { it.gameObjectsList }
                    .last { it.instanceId == bearIid && it.zoneId == ZoneIds.P1_LIBRARY }
            remembered.visibility shouldBe wotc.mtgo.gre.external.messaging.Messages.Visibility.Public
        }

        session(
            "normal priority allows Slip Out the Back in response to own Aether Gust",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Aether Gust;Slip Out the Back
                humanbattlefield=Island;Island;Island;Grizzly Bears
                humanlibrary=Forest;Forest;Forest;Forest;Forest
                aibattlefield=Plains
                ailibrary=Plains;Plains;Plains;Plains;Plains
            """,
            turns = 4,
        ) {
            val bearIid = human.battlefield.iid("Grizzly Bears")
            castSpellByName("Aether Gust").shouldBeTrue()
            selectTargets(listOf(bearIid))
            castSpellByName("Slip Out the Back").shouldBeTrue()
            selectTargets(listOf(bearIid))
            passUntilResolved(maxPasses = 12)
            // With no remaining response, normal automation may already have
            // advanced to the next turn. Assert the emitted transition itself.
            val phasingFrame =
                allMessages.gameStateMessages().single { gsm ->
                    gsm.annotationsList.any { AnnotationType.PhasedOut_af5a in it.typeList }
                }
            assertSoftly {
                phasingFrame.gameObjectsList.single { it.instanceId == bearIid }.zoneId shouldBe ZoneIds.PHASED_OUT
                phasingFrame.diffDeletedInstanceIdsList.contains(bearIid).shouldBeFalse()
                // Even a diff must preserve the client's ObjectIds membership.
                phasingFrame.zonesList
                    .filter { it.zoneId == ZoneIds.BATTLEFIELD }
                    .flatMap { it.objectInstanceIdsList }
                    .contains(bearIid)
                    .shouldBeTrue()
            }
            passUntil(maxPasses = 40) {
                allMessages.gameStateMessages().any { gsm ->
                    gsm.annotationsList.any { AnnotationType.PhasedIn in it.typeList && bearIid in it.affectedIdsList }
                }
            }.shouldBeTrue()
            allMessages
                .gameStateMessages()
                .any { gsm ->
                    gsm.annotationsList.any { AnnotationType.PhasedIn in it.typeList && bearIid in it.affectedIdsList }
                }.shouldBeTrue()
            human.battlefield.card("Grizzly Bears").netPower shouldBe 3
        }

        session(
            "normal priority allows Burst Lightning in response to own Aether Gust",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Aether Gust;Burst Lightning
                humanbattlefield=Island;Island;Mountain;Grizzly Bears
                humanlibrary=Forest;Forest;Forest
                ailibrary=Plains;Plains;Plains
            """,
        ) {
            holdNextOptionalAction()
            castSpellByName("Aether Gust").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
            castSpellByName("Burst Lightning").shouldBeTrue()
            respondToOptionalCost(
                lastCastingTimeOptionsReq()
                    .castingTimeOptionReqList
                    .single {
                        it.castingTimeOptionType == wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType.Done
                    }.ctoId,
            )
            selectTargets(listOf(OPPONENT_SEAT))
            passUntil(maxPasses = 8) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            ai.life shouldBe 18
            respondToOptionalAction(false)
            passUntilResolved(maxPasses = 8)
            human.library.card("Grizzly Bears").name shouldBe "Grizzly Bears"
        }

        session(
            "Aether Gust offers top or bottom for a spell on the stack",
            fullControl = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Giant Growth;Aether Gust
                humanbattlefield=Forest;Island;Island;Grizzly Bears
                humanlibrary=Forest;Forest;Forest
                ailibrary=Plains;Plains;Plains
                """.trimIndent(),
        ) {
            holdNextOptionalAction()
            castSpellByName("Giant Growth").shouldBeTrue()
            selectTargets(listOf(human.battlefield.iid("Grizzly Bears")))
            castSpellByName("Aether Gust").shouldBeTrue()
            val growthIid = latestTargetIidByCardName("Giant Growth")
            selectTargets(listOf(growthIid))
            passUntil(maxPasses = 8) {
                allMessages.any { it.type == GREMessageType.OptionalActionMessage_695e }
            }

            val optional = allMessages.last { it.type == GREMessageType.OptionalActionMessage_695e }.optionalActionMessage
            assertSoftly {
                optional.optionalActionTypesList shouldBe listOf(CardMechanicType.PutTopOrBottom)
                optional.recipientIdsList shouldBe listOf(growthIid)
            }
            respondToOptionalAction(false)
            passUntilResolved(maxPasses = 10)

            human
                .getZone(ForgeZoneType.Library)
                .cards
                .last()
                .name shouldBe "Giant Growth"
        }

        session(
            "Make Disappear without Casualty counters the only stack spell",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock;Make Disappear
                humanbattlefield=Grizzly Bears;Mountain;Island;Island
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains;Plains;Plains
                """,
        ) {
            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))

            castSpellByName("Make Disappear").shouldBeTrue()
            respondToOptionalCost(0)
            latestTargetSourceName() shouldBe "Make Disappear"
            val shockTarget = latestTargetIidByCardName("Shock")
            selectTargets(listOf(shockTarget))
            passUntilResolved(maxPasses = 10)

            ai.life shouldBe 20
        }

        session(
            "required stack target stays distinct from cancelling the counterspell",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock;It'll Quench Ya!
                humanbattlefield=Mountain;Island;Island
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains
                """,
        ) {
            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))

            castSpellByName("It'll Quench Ya!").shouldBeTrue()
            val prompt = allMessages.last { it.hasSelectTargetsReq() }
            val selection = prompt.selectTargetsReq.targetsList.single()
            val shockTarget = latestTargetIidByCardName("Shock")
            assertSoftly {
                selection.minTargets shouldBe 1
                selection.maxTargets shouldBe 1
                prompt.allowCancel shouldBe AllowCancel.Abort
            }
            selectTargets(listOf(shockTarget))
            passUntilResolved(maxPasses = 10)

            assertSoftly {
                ai.life shouldBe 20
                human.graveyard.card("Shock").name shouldBe "Shock"
                human.graveyard.card("It'll Quench Ya!").name shouldBe "It'll Quench Ya!"
            }
        }

        session(
            "cancelling a required stack target restores the parent cast without paying mana",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock;It'll Quench Ya!
                humanbattlefield=Mountain;Island;Island
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains
                """,
        ) {
            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            val tappedBeforeCounterspell =
                human
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .filter { it.isLand && it.isTapped }
                    .map { it.id }
                    .toSet()
            castSpellByName("It'll Quench Ya!").shouldBeTrue()

            cancelAction()

            assertSoftly {
                human.hand.card("It'll Quench Ya!").name shouldBe "It'll Quench Ya!"
                human
                    .getZone(ForgeZoneType.Battlefield)
                    .cards
                    .filter { it.isLand && it.isTapped }
                    .map { it.id }
                    .toSet() shouldBe tappedBeforeCounterspell
                game().stackZone.size() shouldBe 1
            }
        }

        session(
            "Make Disappear with Casualty can counter two stack spells",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock;Lightning Bolt;Make Disappear
                humanbattlefield=Grizzly Bears;Mountain;Mountain;Island;Island
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains;Plains;Plains
                """,
        ) {
            val bearIid = human.battlefield.iid("Grizzly Bears")

            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            castSpellByName("Lightning Bolt").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))

            castSpellByName("Make Disappear").shouldBeTrue()
            respondToOptionalCost(1)
            latestTargetSourceName() shouldBe "Make Disappear"
            val originalPrompt = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq
            val boltTarget = latestTargetIidByCardName("Lightning Bolt")
            selectTargets(listOf(boltTarget))
            respondToEffectCost(listOf(bearIid))
            passUntil(maxPasses = 4) { bridge.cutCoordinator.targeting.current() != null }.shouldBeTrue()
            latestTargetSourceName() shouldBe "Make Disappear"
            val copyPrompt = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq
            assertSoftly {
                copyPrompt.abilityGrpId shouldBe originalPrompt.abilityGrpId
                copyPrompt.abilityGrpId shouldNotBe 0
                copyPrompt.targetsList.single().targetingAbilityGrpId shouldBe
                    originalPrompt.targetsList.single().targetingAbilityGrpId
            }
            val shockTarget = latestTargetIidByCardName("Shock")
            selectTargets(listOf(shockTarget))
            passUntilResolved(maxPasses = 20)

            ai.life shouldBe 20
        }

        session(
            "Casualty copy is visible on the stack while choosing its new target",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Cut Your Losses
                humanbattlefield=Grizzly Bears;Island;Island;Island;Island;Island;Island
                humanlibrary=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest
                ailibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
                """,
        ) {
            val bearIid = human.battlefield.iid("Grizzly Bears")

            castSpellByName("Cut Your Losses").shouldBeTrue()
            respondToOptionalCost(1)
            val originalPrompt = allMessages.last { it.hasSelectTargetsReq() }
            selectTargets(listOf(OPPONENT_SEAT))
            respondToEffectCost(listOf(bearIid))
            val copyPrompt = allMessages.last { it.hasSelectTargetsReq() }
            val copyObject =
                allMessages
                    .gameStateMessages()
                    .filter { it.gameStateId == copyPrompt.gameStateId }
                    .flatMap { it.gameObjectsList }
                    .single { it.instanceId == copyPrompt.selectTargetsReq.sourceId }

            assertSoftly {
                copyPrompt.selectTargetsReq.sourceId shouldNotBe originalPrompt.selectTargetsReq.sourceId
                copyObject.type shouldBe GameObjectType.Card
                copyObject.zoneId shouldBe ZoneIds.STACK
                copyObject.isCopy.shouldBeTrue()
            }
        }

        session(
            "cast triggers with a refreshed ability row target from a visible ability",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Ugin, Eye of the Storms
                humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Plains;Plains;Plains
                """,
        ) {
            castSpellByName("Ugin, Eye of the Storms").shouldBeTrue()
            waitFor(timeoutMs = 2_000L) {
                drainSink()
                allMessages.any { it.hasSelectTargetsReq() }
            }.shouldBeTrue()
            val prompt = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq
            val visible = bridge.projectionStateSnapshot().viewerCursors[SeatId(HUMAN_SEAT)]?.fullState
            val source = visible?.gameObjectsList?.singleOrNull { it.instanceId == prompt.sourceId }
            withClue("target prompt source ${prompt.sourceId} is absent from the visible GSM") {
                (source != null).shouldBeTrue()
            }
            source?.type shouldBe GameObjectType.Ability
            source?.grpId shouldBe 188687
            val selecting =
                allMessages
                    .gameStateMessages()
                    .last { it.gameStateId == allMessages.last { message -> message.hasSelectTargetsReq() }.gameStateId }
                    .annotationsList
                    .single { AnnotationType.PlayerSelectingTargets in it.typeList }
            selecting.affectedIdsList shouldBe listOf(prompt.sourceId)
            val target = latestTargetIidByCardName("Grizzly Bears")
            selectTargets(listOf(target))
        }

        session(
            "Death to Our Enemies reflexive damage prompt projects its hidden child ability",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Shock
                humanbattlefield=Death to Our Enemies|Counters:PLAN=3;Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains
                """,
        ) {
            val promptStart = messageSnapshot()
            castSpellByName("Shock").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))

            passUntil(maxPasses = 12) {
                messagesSince(promptStart).any { message ->
                    message.hasSelectTargetsReq() &&
                        message.selectTargetsReq.targetsList.any { target ->
                            target.targetingAbilityGrpId == 206398
                        }
                }
            }.shouldBeTrue()

            val prompt =
                messagesSince(promptStart).last { message ->
                    message.hasSelectTargetsReq() &&
                        message.selectTargetsReq.targetsList.any { target ->
                            target.targetingAbilityGrpId == 206398
                        }
                }
            val selection = prompt.selectTargetsReq.targetsList.single()
            val source =
                allMessages
                    .gameStateMessages()
                    .last { gameState -> gameState.gameStateId == prompt.gameStateId }
                    .gameObjectsList
                    .single { objectInfo ->
                        objectInfo.instanceId == prompt.selectTargetsReq.sourceId
                    }

            assertSoftly {
                prompt.selectTargetsReq.abilityGrpId shouldBe 105022
                selection.targetingAbilityGrpId shouldBe 206398
                selection.minTargets shouldBe 1
                selection.maxTargets shouldBe 2
                source.type shouldBe GameObjectType.Ability
                source.grpId shouldBe 206398
                source.objectSourceGrpId shouldBe 105022
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
