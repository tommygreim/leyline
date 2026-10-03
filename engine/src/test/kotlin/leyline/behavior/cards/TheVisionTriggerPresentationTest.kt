package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.codes.SlotKind
import leyline.game.data.AbilityInfo
import leyline.game.data.AbilityLocalization
import leyline.game.data.CardData
import leyline.game.data.ModalAbilityInfo
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.*

private val THE_VISION_PUZZLE =
    """
    [metadata]
    Name:The Vision trigger presentation
    Goal:Win
    Turns:5
    Difficulty:Easy
    Description:Cast a noncreature spell and choose The Vision's Technopathy mode.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanbattlefield=The Vision;Mountain
    humanhand=Lightning Bolt
    humanlibrary=Mountain;Mountain;Mountain;Mountain
    aibattlefield=Grizzly Bears
    ailibrary=Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

/** Regression coverage for the triggered The Vision modal stack identity. */
class TheVisionTriggerPresentationTest :
    SessionTest({
        // The Vision is not in the checked-in fixture catalog yet.  Keep the
        // test's client identity explicit while using Forge's real card script
        // for the actual trigger and Charm/Technopathy execution.
        val visionGrpId = 1_900_001
        val visionParentGrpId = 1_900_002
        val solarBeamGrpId = 1_900_003
        val densityControlGrpId = 1_900_004
        val technopathyGrpId = 1_900_005

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            val repo = TestCardRegistry.repo
            repo.registerData(
                CardData(
                    grpId = visionGrpId,
                    titleId = 1_900_006,
                    power = "2",
                    toughness = "5",
                    colors = emptyList(),
                    types = listOf(1, 2),
                    subtypes = emptyList(),
                    supertypes = listOf(2),
                    abilityIds = listOf(visionParentGrpId to 1_900_007),
                    abilityKinds = listOf(SlotKind.Intrinsic),
                    abilityCategories = listOf(2),
                    manaCost = emptyList(),
                ),
                "The Vision",
            )
            repo.registerAbilityInfo(visionParentGrpId, AbilityInfo(baseId = 0, manaCost = emptyList(), category = 2))
            repo.registerModalOptions(
                visionGrpId,
                ModalAbilityInfo(
                    parentGrpId = visionParentGrpId,
                    childGrpIds = listOf(solarBeamGrpId, densityControlGrpId, technopathyGrpId),
                ),
            )
            repo.registerAbilityLocalization(solarBeamGrpId, AbilityLocalization("Solar Beam — Double strike."))
            repo.registerAbilityLocalization(densityControlGrpId, AbilityLocalization("Density Control — Indestructible."))
            repo.registerAbilityLocalization(technopathyGrpId, AbilityLocalization("Technopathy — Draw a card."))
        }

        session(
            "The Vision trigger stack object uses selected Technopathy child",
            puzzle = THE_VISION_PUZZLE,
            fullControl = true,
        ) {
            castSpellUntilSelectTargetsReq("Lightning Bolt")
            selectTargets(listOf(ai.battlefield.iid("Grizzly Bears")))

            passUntil(maxPasses = 10) {
                allMessages.any { message ->
                    message.hasCastingTimeOptionsReq() &&
                        message.castingTimeOptionsReq.castingTimeOptionReqList.any {
                            it.castingTimeOptionType == CastingTimeOptionType.Modal_a7b4
                        }
                }
            }.shouldBeTrue()

            val req = lastCastingTimeOptionsReq()
            val modal =
                req.castingTimeOptionReqList
                    .single {
                        it.castingTimeOptionType == CastingTimeOptionType.Modal_a7b4
                    }.modalReq
            modal.modalOptionsList.map { it.grpId } shouldContain technopathyGrpId

            val responseStart = allMessages.size
            respondModalChoice(listOf(technopathyGrpId))

            // The response causes Forge to recreate the triggered SA before
            // it resolves.  The first post-response GSM must therefore expose
            // the selected child as the stack Ability's grpId/text, rather
            // than reusing The Vision's full card row.
            val selectedObjects =
                allMessages
                    .drop(responseStart)
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.type == GameObjectType.Ability && it.zoneId == ZoneIds.STACK }
            selectedObjects.any { it.grpId == technopathyGrpId }.shouldBeTrue()
            selectedObjects.none { it.grpId == visionGrpId }.shouldBeTrue()
            selectedObjects
                .any {
                    it.grpId == technopathyGrpId &&
                        it.objectSourceGrpId == visionGrpId
                }.shouldBeTrue()

            assertSoftly {
                TestCardRegistry.repo.findAbilityLocalization(technopathyGrpId)?.text shouldBe
                    "Technopathy — Draw a card."
                // The child identity is retained after the Forge trigger's
                // stack wrapper is recreated, not only while the prompt is open.
                selectedObjects.map { it.grpId } shouldContain technopathyGrpId
            }
        }
    })
