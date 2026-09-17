package leyline.session.actions

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage

/**
 * Session-tier activated ability tests — full MatchSession round-trip.
 *
 * Board-level action field tests live in [ActivatedAbilityTest] (BoardTest).
 */
class ActivatedAbilityInteractionTest :
    SessionTest({
        fun MatchFlowHarness.enableStackAutoResolve() {
            updateSettings(
                SettingsMessage
                    .newBuilder()
                    .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                    .build(),
            )
        }

        session(
            "Goblin Fireslinger tap-to-ping deals damage to opponent",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=5

                humanbattlefield=Goblin Fireslinger
                humanlibrary=Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain
                """,
        ) {
            assertSoftly {
                phase() shouldBe "MAIN1"
                ai.life shouldBe 5
            }

            // Activate tap ability → wait for SelectTargetsReq before responding
            // (drainSink returns before the engine emits the prompt under load).
            assertSoftly {
                activateAbility("Goblin Fireslinger").shouldBeTrue()
                passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            }
            selectTargets(listOf(OPPONENT_SEAT))

            val targetSpec = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            val stackAbilityIids =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.type == GameObjectType.Ability }
                    .map { it.instanceId }
            assertSoftly {
                stackAbilityIids shouldContain targetSpec.affectorId
                targetSpec.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                allMessages.persistentAnnotationsOfType(AnnotationType.LinkInfo).none {
                    it.affectorId in stackAbilityIids
                } shouldBe true
                passUntil(maxPasses = 10) { ai.life < 5 }.shouldBeTrue()
                ai.life shouldBe 4
                allMessages.deletedPersistentAnnotationIds() shouldContain targetSpec.id
            }
        }

        session(
            "modal activated sacrifice ability asks mode before target and costs",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Goblin Cratermaker;Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain
                """,
        ) {
            val slice = after { activateAbility("Goblin Cratermaker").shouldBeTrue() }

            slice.expectNoPayCostsReq()
            slice.expectNoSelectTargetsReq()
            val modalReq =
                slice
                    .expectOneCastingTimeOptionsReq()
                    .castingTimeOptionReqList
                    .single()
                    .modalReq
            modalReq.modalOptionsList.map { it.grpId } shouldBe listOf(121501)
            modalReq.excludedOptionsList.map { it.grpId } shouldBe listOf(121502)
        }

        session(
            "activated Discover links its stack ability to the sacrificed source",
            puzzleFile = "data/puzzles/discover-hidden-courtyard.pzl",
        ) {
            val sourceIid = human.battlefield.iid("Hidden Courtyard")

            enableStackAutoResolve()
            activateAbility("Hidden Courtyard").shouldBeTrue()
            passUntil(maxPasses = 5) {
                allMessages.persistentAnnotationsOfType(AnnotationType.LinkInfo).isNotEmpty()
            }.shouldBeTrue()

            val abilityObjects =
                allMessages
                    .gameStateMessages()
                    .flatMap { it.gameObjectsList }
                    .filter { it.type == GameObjectType.Ability }
                    .distinctBy { it.instanceId }
            val abilityObject =
                abilityObjects.firstOrNull { it.grpId == 169776 }
                    ?: error(
                        "No activated Discover Ability object; projected=" +
                            abilityObjects.map { "iid=${it.instanceId} grp=${it.grpId} source=${it.objectSourceGrpId}" },
                    )
            val linkInfo = allMessages.persistentAnnotationsOfType(AnnotationType.LinkInfo).single()
            assertSoftly {
                abilityObject.objectSourceGrpId shouldBe 87440
                linkInfo.affectorId shouldBe abilityObject.instanceId
                linkInfo.affectedIdsList shouldBe listOf(sourceIid)
                linkInfo.detailInt("LinkType") shouldBe 2
            }

            // Accepting "do you want to play Llanowar Elves?" (PlayEffect's
            // single-castable-option confirm) and the free-cast decision after it
            // both happen automatically during the LinkInfo wait above — nothing
            // pending to answer explicitly by this point.
            val resolved =
                passUntil(maxPasses = 10) {
                    human.getZone(ZoneType.Battlefield).cards.any { it.name == "Llanowar Elves" } &&
                        human.getZone(ZoneType.Graveyard).cards.any { it.name == "Hidden Courtyard" }
                }
            assertSoftly {
                resolved.shouldBeTrue()
                allMessages.deletedPersistentAnnotationIds() shouldContain linkInfo.id
                human.battlefield.card("Llanowar Elves")
                human.graveyard.card("Hidden Courtyard")
                allMessages.persistentAnnotationsOfType(AnnotationType.TriggeringObject).none {
                    it.affectorId == abilityObject.instanceId
                } shouldBe true
                abilityObject.zoneId shouldBe ZoneIds.STACK
            }
        }
    })
