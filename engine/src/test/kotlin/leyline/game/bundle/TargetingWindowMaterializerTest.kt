package leyline.game.bundle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.TargetingCandidateValue
import leyline.bridge.handoff.TargetingWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.mapping.FrameIdResolver
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.HighlightType

class TargetingWindowMaterializerTest :
    FunSpec({
        tags(UnitTag)

        test("stack spell and ability candidates use distinct projection identities") {
            val sourceCardId = ForgeCardId(101)
            val copiedSpellCardId = ForgeCardId(102)
            val abilityForgeId = 77
            val abilityIdentity = FrameIdResolver.triggerStackAbilityForgeId(abilityForgeId)
            val prior = ProjectionState.initial()
            val editor = prior.editor()
            val spellIid = editor.identities.getOrAlloc(sourceCardId)
            val copiedSpellIid = editor.identities.getOrAlloc(copiedSpellCardId)
            val abilityIid = editor.identities.getOrAlloc(abilityIdentity)
            val projection = editor.freeze()
            val window =
                TargetingWindowValue(
                    sourceForgeCardId = sourceCardId,
                    sourceGrpId = 1,
                    outerAbilityGrpId = 2,
                    targetingAbilityGrpId = 3,
                    targetSourceZoneId = 7,
                    targetPromptId = null,
                    targetIndex = 1,
                    minTargets = 1,
                    maxTargets = 1,
                    chooserSeatId = SeatId(1),
                    candidates =
                        listOf(
                            TargetingCandidateValue.StackObject(
                                optionIndex = 4,
                                stackInstanceId = 11,
                                sourceForgeCardId = sourceCardId,
                                forgeAbilityId = 55,
                                isSpell = true,
                                isAbility = false,
                                isTrigger = false,
                            ),
                            TargetingCandidateValue.StackObject(
                                optionIndex = 7,
                                stackInstanceId = 13,
                                sourceForgeCardId = copiedSpellCardId,
                                forgeAbilityId = 56,
                                isSpell = true,
                                isAbility = false,
                                isTrigger = false,
                            ),
                            TargetingCandidateValue.StackObject(
                                optionIndex = 9,
                                stackInstanceId = 12,
                                sourceForgeCardId = sourceCardId,
                                forgeAbilityId = abilityForgeId,
                                isSpell = false,
                                isAbility = true,
                                isTrigger = true,
                            ),
                        ),
                    isTriggeredAbility = false,
                    forgeAbilityId = 0,
                )

            val prepared =
                TargetingWindowMaterializer(seatId = 1).initial(
                    gameState = GameStateMessage.newBuilder().build(),
                    gameStateId = 10,
                    counter = LogicalSequencePlanner(),
                    projection = projection,
                    transition = ProjectionTransition(prior.revision, projection),
                    window = window,
                )
            val request =
                prepared.bundle.messages
                    .single { it.hasSelectTargetsReq() }
                    .selectTargetsReq

            request.targetsList
                .single()
                .targetsList
                .map { it.targetInstanceId } shouldContainExactly
                listOf(spellIid.value, copiedSpellIid.value, abilityIid.value)
            request.targetsList
                .single()
                .targetsList
                .map { it.targetInstanceId }
                .distinct()
                .size shouldBe 3
        }

        test("card candidate with an existing Role gets ReplaceRole highlight; a plain card gets Tepid") {
            val plainCardId = ForgeCardId(201)
            val roleCardId = ForgeCardId(202)
            val prior = ProjectionState.initial()
            val editor = prior.editor()
            val plainIid = editor.identities.getOrAlloc(plainCardId)
            val roleIid = editor.identities.getOrAlloc(roleCardId)
            val projection = editor.freeze()
            val window =
                TargetingWindowValue(
                    sourceForgeCardId = ForgeCardId(1),
                    sourceGrpId = 1,
                    outerAbilityGrpId = 2,
                    targetingAbilityGrpId = 3,
                    targetSourceZoneId = 7,
                    targetPromptId = null,
                    targetIndex = 1,
                    minTargets = 1,
                    maxTargets = 2,
                    chooserSeatId = SeatId(1),
                    candidates =
                        listOf(
                            TargetingCandidateValue.Card(optionIndex = 0, forgeCardId = plainCardId, zoneId = 28),
                            TargetingCandidateValue.Card(
                                optionIndex = 1,
                                forgeCardId = roleCardId,
                                zoneId = 28,
                                hasExistingRole = true,
                            ),
                        ),
                    isTriggeredAbility = false,
                    forgeAbilityId = 0,
                )

            val prepared =
                TargetingWindowMaterializer(seatId = 1).initial(
                    gameState = GameStateMessage.newBuilder().build(),
                    gameStateId = 10,
                    counter = LogicalSequencePlanner(),
                    projection = projection,
                    transition = ProjectionTransition(prior.revision, projection),
                    window = window,
                )
            val targets =
                prepared.bundle.messages
                    .single { it.hasSelectTargetsReq() }
                    .selectTargetsReq
                    .targetsList
                    .single()
                    .targetsList

            targets.single { it.targetInstanceId == plainIid.value }.highlight shouldBe HighlightType.Tepid
            targets.single { it.targetInstanceId == roleIid.value }.highlight shouldBe HighlightType.ReplaceRole
        }

        test("missing stack projection identity fails publication") {
            val window =
                TargetingWindowValue(
                    sourceForgeCardId = ForgeCardId(101),
                    sourceGrpId = 1,
                    outerAbilityGrpId = 2,
                    targetingAbilityGrpId = 3,
                    targetSourceZoneId = 7,
                    targetPromptId = null,
                    targetIndex = 1,
                    minTargets = 1,
                    maxTargets = 1,
                    chooserSeatId = SeatId(1),
                    candidates =
                        listOf(
                            TargetingCandidateValue.StackObject(
                                optionIndex = 0,
                                stackInstanceId = 11,
                                sourceForgeCardId = ForgeCardId(101),
                                forgeAbilityId = 55,
                                isSpell = true,
                                isAbility = false,
                                isTrigger = false,
                            ),
                        ),
                    isTriggeredAbility = false,
                    forgeAbilityId = 0,
                )
            val projection = ProjectionState.initial()
            shouldThrow<IllegalStateException> {
                TargetingWindowMaterializer(seatId = 1).initial(
                    gameState = GameStateMessage.newBuilder().build(),
                    gameStateId = 10,
                    counter = LogicalSequencePlanner(),
                    projection = projection,
                    transition = ProjectionTransition(projection.revision, projection),
                    window = window,
                )
            }
        }
    })
