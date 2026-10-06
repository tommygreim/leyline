package leyline.behavior.actions.castadventure

import forge.card.CardStateName
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

/**
 * Integration test for the full adventure lifecycle:
 *
 * 1. CastAdventure action available for adventure card in hand
 * 2. Submit CastAdventure → adventure goes on stack
 * 3. Adventure resolves → creates Rat tokens
 * 4. Card moves to exile
 * 5. Cast creature from exile
 * 6. Creature resolves to battlefield
 *
 */
class AdventurePuzzleTest :
    SessionTest({

        fun MatchFlowHarness.adventureCompanionIn(zoneId: Int): GameObjectInfo =
            accumulator.objects.values.single { obj ->
                obj.type == GameObjectType.Adventure_a4aa && obj.zoneId == zoneId
            }

        session(
            "adventure lifecycle: cast adventure → tokens → exile → cast creature → battlefield",
            """
            [metadata]
            Name:Adventure Lifecycle
            Goal:Win
            Turns:3
            Difficulty:Easy
            Description:Full adventure card lifecycle.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=1

            humanhand=Ratcatcher Trainee
            humanbattlefield=Mountain;Mountain;Mountain;Mountain;Swamp;Swamp;Swamp
            humanlibrary=Mountain
            aibattlefield=Forest
            ailibrary=Forest
            """.trimIndent(),
        ) {
            phase() shouldBe "MAIN1"

            // --- Step 1: Verify CastAdventure action is available ---
            val traineeIid = human.hand.iid("Ratcatcher Trainee")
            val handCompanion = adventureCompanionIn(ZoneIds.P1_HAND)
            accumulator.zones
                .getValue(ZoneIds.P1_HAND)
                .objectInstanceIdsList shouldContain traineeIid
            accumulator.zones
                .getValue(ZoneIds.P1_HAND)
                .objectInstanceIdsList shouldNotContain handCompanion.instanceId

            // Look for CastAdventure in the latest ActionsAvailableReq
            val actionsMsg =
                allMessages
                    .asReversed()
                    .firstOrNull { it.hasActionsAvailableReq() }
            checkNotNull(actionsMsg) { "No ActionsAvailableReq found in messages" }

            val adventureActions =
                actionsMsg.actionsAvailableReq.actionsList
                    .filter { it.actionType == ActionType.CastAdventure }
            adventureActions.shouldNotBeEmpty()

            val adventureAction = adventureActions.first()
            // instanceId should match the card in hand
            adventureAction.instanceId shouldBe traineeIid

            // --- Step 2: Submit CastAdventure action ---
            val castMsg =
                performAction {
                    actionType = ActionType.CastAdventure
                    instanceId = adventureAction.instanceId
                    grpId = adventureAction.grpId
                }
            val beforeCast = messageSnapshot()
            send(submitWithGsId(castMsg))
            drainSink()
            val castFlow = messagesSince(beforeCast)
            val stackMessageIndex =
                castFlow.indexOfFirst { message ->
                    message.hasGameStateMessage() &&
                        message.gameStateMessage.gameObjectsList.any { obj ->
                            obj.type == GameObjectType.Adventure_a4aa && obj.zoneId == ZoneIds.STACK
                        }
                }
            check(stackMessageIndex >= 0) { "No state-only Adventure stack frame found" }
            val stackGsm = castFlow[stackMessageIndex].gameStateMessage
            val adventureStackCompanion =
                stackGsm.gameObjectsList.single { obj ->
                    obj.type == GameObjectType.Adventure_a4aa && obj.zoneId == ZoneIds.STACK
                }

            val exileMessageIndex =
                castFlow.indexOfFirst { message ->
                    message.hasGameStateMessage() &&
                        message.gameStateMessage.gameObjectsList.any { obj ->
                            obj.type == GameObjectType.Adventure_a4aa && obj.zoneId == ZoneIds.EXILE
                        }
                }
            check(exileMessageIndex > stackMessageIndex) { "Adventure exile frame did not follow its stack frame" }
            val exileGsm = castFlow[exileMessageIndex].gameStateMessage
            val visibleActionIndex =
                castFlow.indexOfFirst { message ->
                    message.hasActionsAvailableReq() && message.gameStateId > exileGsm.gameStateId
                }
            check(visibleActionIndex > exileMessageIndex) { "Visible priority window did not follow Adventure resolution" }

            assertSoftly {
                castFlow
                    .subList(stackMessageIndex, exileMessageIndex)
                    .count { it.hasActionsAvailableReq() } shouldBe 0
                exileGsm.annotationsList.count { AnnotationType.TokenCreated in it.typeList } shouldBe 2
                stackGsm.gameStateId shouldBeLessThan exileGsm.gameStateId
                exileGsm.gameStateId shouldBeLessThan castFlow[visibleActionIndex].gameStateId
                adventureStackCompanion.instanceId shouldNotBe handCompanion.instanceId
                accumulator.zones
                    .getValue(ZoneIds.STACK)
                    .objectInstanceIdsList shouldNotContain adventureStackCompanion.instanceId
            }

            // --- Step 3: Pass priority until adventure resolves (tokens appear) ---
            // Forge token name is "Rat Token" (from b_1_1_rat_noblock.txt)
            val tokensAppeared =
                passUntil(maxPasses = 15) {
                    human.getZone(ZoneType.Battlefield).cards.any { it.isToken && "Rat" in it.name }
                }
            tokensAppeared.shouldBeTrue()

            // Count Rat tokens — Pest Problem creates 2
            val ratTokens =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isToken && "Rat" in it.name }
            ratTokens.size shouldBe 2

            // --- Step 4: Verify card is in exile ---
            val inExile =
                human
                    .getZone(ZoneType.Exile)
                    .cards
                    .any { it.name == "Ratcatcher Trainee" }
            val exileCompanion = adventureCompanionIn(ZoneIds.EXILE)
            assertSoftly {
                inExile.shouldBeTrue()
                exileCompanion.instanceId shouldNotBe adventureStackCompanion.instanceId
                accumulator.zones
                    .getValue(ZoneIds.EXILE)
                    .objectInstanceIdsList shouldNotContain exileCompanion.instanceId
            }

            // --- Step 5: Cast creature from exile ---
            val beforeCreatureCast = messageSnapshot()
            val castCreature = castFromExile("Ratcatcher Trainee")
            val creatureCastMessages = messagesSince(beforeCreatureCast)
            val creatureStackCompanion =
                creatureCastMessages
                    .asSequence()
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList.asSequence() }
                    .first { obj -> obj.type == GameObjectType.Adventure_a4aa && obj.zoneId == ZoneIds.STACK }
            assertSoftly {
                castCreature.shouldBeTrue()
                creatureStackCompanion.instanceId shouldNotBe exileCompanion.instanceId
            }

            // --- Step 6: Pass until creature resolves to battlefield ---
            val creatureOnBattlefield =
                passUntil(maxPasses = 15) {
                    human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .any { it.name == "Ratcatcher Trainee" }
                }
            val battlefieldCompanion = adventureCompanionIn(ZoneIds.BATTLEFIELD)
            assertSoftly {
                creatureOnBattlefield.shouldBeTrue()
                battlefieldCompanion.instanceId shouldBe creatureStackCompanion.instanceId
                accumulator.zones
                    .getValue(ZoneIds.BATTLEFIELD)
                    .objectInstanceIdsList shouldNotContain battlefieldCompanion.instanceId
            }

            // Final verification: Ratcatcher Trainee on battlefield
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .any { it.name == "Ratcatcher Trainee" }
                .shouldBeTrue()
        }

        session(
            "land-front Adventure can cast its instant face",
            """
            [metadata]
            Name:Lindblum instant adventure
            Goal:Win
            Turns:3

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Lindblum, Industrial Regency
            humanbattlefield=Mountain;Mountain;Mountain
            humanlibrary=Mountain;Mountain;Mountain;Mountain
            ailibrary=Forest;Forest;Forest;Forest
            """.trimIndent(),
        ) {
            val adventure =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.CastAdventure }
            send(
                submitWithGsId(
                    performAction {
                        actionType = ActionType.CastAdventure
                        instanceId = adventure.instanceId
                        grpId = adventure.grpId
                    },
                ),
            )
            drainSink()
            passUntil(maxPasses = 8) {
                human.getZone(ZoneType.Battlefield).cards.any { it.isToken && it.type.hasCreatureType("Wizard") }
            }.shouldBeTrue()
        }

        session(
            "Flameshape resolves using Great Hall restricted mana and its life payment",
            """
            [metadata]
            Name:Flameshape restricted mana
            Goal:Win
            Turns:3

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Gandalf, Goblins' Bane
            humanbattlefield=Godless Shrine;Great Hall of the Biblioplex
            humanlibrary=Mountain;Forest;Mountain
            ailibrary=Forest;Forest;Forest
            """.trimIndent(),
        ) {
            val adventure =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.CastAdventure }
            send(
                submitWithGsId(
                    performAction {
                        actionType = ActionType.CastAdventure
                        instanceId = adventure.instanceId
                        grpId = adventure.grpId
                    },
                ),
            )
            drainSink()
            passUntil(maxPasses = 8) {
                human.getZone(ZoneType.Exile).cards.any { it.name == "Gandalf, Goblins' Bane" }
            }.shouldBeTrue()
            assertSoftly {
                human.life shouldBe 19
                human.getZone(ZoneType.Exile).cards.size shouldBe 3
            }
        }

        session(
            "Flameshape exiles remain on the cast rail once a Wizard is controlled",
            """
            [metadata]
            Name:Flameshape exile permission
            Goal:Win
            Turns:5

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Gandalf, Goblins' Bane
            humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain
            humanlibrary=Burst Lightning;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
            ailibrary=Forest;Forest;Forest;Forest;Forest;Forest;Forest;Forest
            """.trimIndent(),
        ) {
            val adventure =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.actionType == ActionType.CastAdventure }
            send(
                submitWithGsId(
                    performAction {
                        actionType = ActionType.CastAdventure
                        instanceId = adventure.instanceId
                        grpId = adventure.grpId
                    },
                ),
            )
            drainSink()
            passUntil(maxPasses = 8) {
                human.getZone(ZoneType.Exile).cards.any { it.name == "Gandalf, Goblins' Bane" }
            }.shouldBeTrue()
            val faceDownBurst =
                human.getZone(ZoneType.Exile).cards.first {
                    it.getOriginalState(CardStateName.Original)?.name == "Burst Lightning"
                }
            faceDownBurst.mayPlay(human).isEmpty().shouldBeTrue()
            castFromExile("Gandalf, Goblins' Bane").shouldBeTrue()
            passUntil(maxPasses = 8) {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Gandalf, Goblins' Bane" }
            }.shouldBeTrue()
            check(!isGameOver()) { "Gandalf scenario ended before exile-cast affordances could be inspected" }

            val exiledBurst =
                human.getZone(ZoneType.Exile).cards.first {
                    it.getOriginalState(CardStateName.Original)?.name == "Burst Lightning"
                }
            val exiledMountain =
                human.getZone(ZoneType.Exile).cards.first {
                    it.getOriginalState(CardStateName.Original)?.name == "Mountain"
                }
            exiledBurst.mayPlay(human).shouldNotBeEmpty()
            exiledMountain.mayPlay(human).shouldNotBeEmpty()
            check(getAllCastableAbilities(exiledBurst, human, checkTiming = false).isNotEmpty()) {
                "Flameshape permission exists, but no printed cast ability was recovered from face-down exile"
            }
            val burstIid = bridge.instanceId(exiledBurst)
            val mountainIid = bridge.instanceId(exiledMountain)
            val snapshot = GsmSnapshot.capture(game(), bridge, "test", 0)
            check(snapshot.zones[ZoneIds.EXILE]?.contents?.contains(ForgeCardId(exiledBurst.id)) == true)
            val projected = ActionMapper.buildFromSnapshot(1, snapshot, bridge)
            check((projected.actionsList + projected.inactiveActionsList).any { it.instanceId == burstIid }) {
                "Current bridge projection has no cast rail for Flameshape-exiled card"
            }
            check(
                (projected.actionsList + projected.inactiveActionsList).any {
                    it.instanceId == mountainIid && it.actionType == ActionType.Play_add3
                },
            ) { "Current bridge projection has no land rail for Flameshape-exiled card" }
            val landOffer =
                allMessages
                    .last { it.hasActionsAvailableReq() }
                    .actionsAvailableReq.actionsList
                    .single { it.instanceId == mountainIid && it.actionType == ActionType.Play_add3 }
            send(
                submitWithGsId(
                    performAction {
                        actionType = ActionType.Play_add3
                        instanceId = landOffer.instanceId
                        grpId = landOffer.grpId
                    },
                ),
            )
            drainSink()
            human.getZone(ZoneType.Battlefield).cards.count { it.name == "Mountain" } shouldBe 6
            passUntil(maxPasses = 15) {
                allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.any {
                    it.instanceId == burstIid && it.actionType == ActionType.Cast
                }
            }.shouldBeTrue()
            val latest = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq
            val burstOffer = latest.actionsList.single { it.instanceId == burstIid && it.actionType == ActionType.Cast }
            send(
                submitWithGsId(
                    performAction {
                        actionType = ActionType.Cast
                        instanceId = burstOffer.instanceId
                        grpId = burstOffer.grpId
                    },
                ),
            )
            drainSink()
            allMessages
                .last { it.hasCastingTimeOptionsReq() }
                .castingTimeOptionsReq.castingTimeOptionReqList
                .any { it.castingTimeOptionType == wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType.Done }
                .shouldBeTrue()
            respondToOptionalCost(0)
            allMessages.any { it.hasSelectTargetsReq() }.shouldBeTrue()
        }
    })
