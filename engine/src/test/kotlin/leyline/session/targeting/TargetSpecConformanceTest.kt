package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class TargetSpecConformanceTest :
    SessionTest({
        session(
            "multiple targets in one group share one TargetSpec with aligned distributions",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Twin Bolt
                humanbattlefield=Mountain;Mountain;Centaur Courser
                humanlibrary=Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Mountain
                """,
        ) {
            val opponentIid = ai.battlefield.iid("Grizzly Bears")
            castSpellByName("Twin Bolt") shouldBe true

            val request = allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq
            val selection = request.targetsList.single()
            assertSoftly {
                request.abilityGrpId shouldBe 95649
                selection.targetIdx shouldBe 1
                selection.targetingAbilityGrpId shouldBe 90088
                selection.prompt.promptId shouldBe PromptIds.CHOOSE_ANY_TARGET
            }

            selectTargets(listOf(OPPONENT_SEAT, opponentIid))

            val targetSpecs = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec)
            targetSpecs.shouldHaveSize(1)
            val targetSpec = targetSpecs.single()
            assertSoftly {
                targetSpec.affectedIdsList shouldBe listOf(OPPONENT_SEAT, opponentIid)
                targetSpec.detailInt("abilityGrpId") shouldBe 90088
                targetSpec.detailInt("index") shouldBe 1
                targetSpec.detailInt("promptId") shouldBe PromptIds.CHOOSE_ANY_TARGET
                targetSpec.detailInt("promptParameters") shouldBe targetSpec.affectorId
                targetSpec.detailIntList("distributions") shouldBe listOf(1, 1)
            }
        }

        session(
            "partial divided target set uses Forge's final allocation",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Twin Bolt
                humanbattlefield=Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Mountain
                """,
        ) {
            castSpellByName("Twin Bolt") shouldBe true
            selectTargets(listOf(OPPONENT_SEAT))

            val targetSpec = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            assertSoftly {
                targetSpec.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                targetSpec.detailIntList("distributions") shouldBe listOf(2)
            }
        }

        session(
            "land target prompt id is shared by request and TargetSpec",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Stone Rain
                humanbattlefield=Mountain;Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Forest
                ailibrary=Mountain
                """,
        ) {
            val targetIid = ai.battlefield.iid("Forest")
            castSpellByName("Stone Rain") shouldBe true
            val requestPromptId =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq.targetsList
                    .single()
                    .prompt.promptId
            requestPromptId shouldBe PromptIds.TARGET_LAND

            selectTargets(listOf(targetIid))
            val targetSpec = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            targetSpec.detailInt("promptId") shouldBe requestPromptId
        }

        session(
            "Forge-selected opponent still emits TargetSpec",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Pilfer
                humanbattlefield=Swamp;Swamp
                humanlibrary=Swamp
                aihand=Grizzly Bears
                aibattlefield=Mountain
                ailibrary=Mountain
                """,
        ) {
            castSpellByName("Pilfer") shouldBe true

            val targetSpec = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            assertSoftly {
                targetSpec.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                targetSpec.detailInt("promptId") shouldBe PromptIds.TARGET_OPPONENT
            }
        }

        session(
            "chained draw and damage effects show distinct target prompts",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Together as One
                humanbattlefield=Plains;Island;Swamp;Mountain;Forest;Mountain
                humanlibrary=Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Mountain
                """,
        ) {
            castSpellByName("Together as One") shouldBe true
            val drawSelection =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq.targetsList
                    .single()
            assertSoftly {
                drawSelection.targetIdx shouldBe 1
                drawSelection.prompt.promptId shouldBe PromptIds.TARGET_PLAYER_DRAWS_X
            }

            selectTargets(listOf(OPPONENT_SEAT))
            val damageSelection =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq.targetsList
                    .single()
            assertSoftly {
                damageSelection.targetIdx shouldBe 2
                damageSelection.prompt.promptId shouldBe PromptIds.DEAL_X_DAMAGE_TO_ANY_TARGET
            }
        }

        session(
            "chained bounce and fixed damage effects show distinct target prompts",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Jeskai Revelation
                humanbattlefield=Plains;Island;Mountain;Mountain;Mountain;Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Grizzly Bears
                ailibrary=Mountain
                """,
        ) {
            val creatureIid = ai.battlefield.iid("Grizzly Bears")
            castSpellByName("Jeskai Revelation") shouldBe true
            val bounceSelection =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq.targetsList
                    .single()
            assertSoftly {
                bounceSelection.targetIdx shouldBe 1
                bounceSelection.prompt.promptId shouldBe PromptIds.RETURN_TARGET_SPELL_OR_PERMANENT_TO_HAND
            }

            selectTargets(listOf(creatureIid))
            val damageSelection =
                allMessages
                    .last { it.hasSelectTargetsReq() }
                    .selectTargetsReq.targetsList
                    .single()
            assertSoftly {
                damageSelection.targetIdx shouldBe 2
                damageSelection.prompt.promptId shouldBe PromptIds.DEAL_FOUR_DAMAGE_TO_ANY_TARGET
            }
        }

        session(
            "AI-cast targeted spell emits TargetSpec from the live stack",
            fullControl = true,
            puzzle = """
                ActivePlayer=AI
                ActivePhase=Main1
                HumanLife=2
                AILife=20

                humanhand=Negate
                humanbattlefield=Island;Island
                humanlibrary=Island;Island;Island
                aihand=Burst Lightning
                aibattlefield=Mountain
                ailibrary=Mountain;Mountain;Mountain
                """,
        ) {
            val targetSpec =
                allMessages
                    .persistentAnnotationsOfType(AnnotationType.TargetSpec)
                    .first { HUMAN_SEAT in it.affectedIdsList }
            assertSoftly {
                targetSpec.detailInt("index") shouldBe 1
                targetSpec.detailInt("promptId") shouldBe PromptIds.CHOOSE_ANY_TARGET
            }
        }
    })
