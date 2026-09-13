package leyline.game.bundle

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionState

class BlockingInteractionMaterializerTest :
    FunSpec({
        tags(UnitTag)

        test("caps blocker damage at the attacker's available damage") {
            val prepared =
                BlockingInteractionMaterializer(seatId = 1).damage(
                    prior = ProjectionState.initial(),
                    counter = LogicalSequencePlanner(),
                    interaction =
                        BlockingInteraction.Damage.of(
                            attackerId = ForgeCardId(1),
                            blockerIds = listOf(ForgeCardId(2)),
                            damageDealt = 4,
                            hasDeathtouch = false,
                            hasTrample = true,
                            hasDefender = true,
                        ),
                    blockerToughness = mapOf(ForgeCardId(2) to 5),
                )

            val assigner =
                prepared.bundle.messages
                    .single()
                    .assignDamageReq.damageAssignersList
                    .single()
            assertSoftly {
                assigner.totalDamage shouldBe 4
                assigner.assignmentsList.single().minDamage shouldBe 5
                assigner.assignmentsList.single().assignedDamage shouldBe 4
            }
        }

        test("optional interactions carry the same semantic prompt inside and outside the payload") {
            val prepared =
                BlockingInteractionMaterializer(seatId = 1).generalOptional(
                    prior = ProjectionState.initial(),
                    counter = LogicalSequencePlanner(),
                    interaction =
                        BlockingInteraction.Optional(
                            sourceId = ForgeCardId(42),
                            forceSnapshotBeforePrompt = false,
                            customPromptId = PromptIds.DISCARD_OPTIONAL,
                            commanderReturn = null,
                        ),
                )

            val message = prepared.bundle.messages.single { it.hasOptionalActionMessage() }
            assertSoftly {
                message.prompt.promptId shouldBe PromptIds.DISCARD_OPTIONAL
                message.optionalActionMessage.prompt shouldBe message.prompt
            }
        }
    })
