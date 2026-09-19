package leyline.mechanics.evoke

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.detailUint
import leyline.testkit.gameStateMessages
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.OrderingType

class EvokeLifecycleTest :
    SessionTest({
        session(
            "paid Evoke keeps its marker through entry and retires it with the sacrifice ability",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Mulldrifter
                humanbattlefield=Island;Island;Island
                humanlibrary=Island;Island;Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val mulldrifterGrpId = bridge.cardRepository.findGrpIdByName("Mulldrifter")!!
            val evokeAbilityGrpId = bridge.cardRepository.findKeywordAbilityGrpId(mulldrifterGrpId, KeywordAbilityIds.EVOKE)!!

            val snap = messageSnapshot()
            castSpellByName("Mulldrifter", alternativeGrpId = evokeAbilityGrpId).shouldBeTrue()
            val castMessages = messagesSince(snap)
            val cto =
                castMessages
                    .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                    .single { it.detailInt("alternateCostGrpId") == evokeAbilityGrpId }
            val marker = castMessages.persistentAnnotationsOfType(AnnotationType.TemporaryPermanent).single()
            val castAction =
                castMessages
                    .annotationsOfType(AnnotationType.UserActionTaken)
                    .single { it.detailInt("actionType") == ActionType.Cast.number && it.affectedIdsList == marker.affectedIdsList }

            assertSoftly {
                cto.detailInt("type") shouldBe CastingTimeOptionType.CastThroughAbility.number
                cto.detailInt("castAbilityGrpId") shouldBe evokeAbilityGrpId
                marker.affectorId shouldBe marker.affectedIdsList.single()
                marker.detailInt("AbilityGrpId") shouldBe evokeAbilityGrpId
                castAction.detailInt("abilityGrpId") shouldBe 0
                castAction.detailInt("alternativeGrpId") shouldBe evokeAbilityGrpId
            }

            passUntilResolved(maxPasses = 12)
            // The draw trigger and the evoke sacrifice trigger fire together: the player orders them.
            respondToSelectN(lastSelectNReq().idsList, OrderingType.OrderAsIndicated)
            passUntilResolved(maxPasses = 12)
            val lifecycle = messagesSince(snap)
            val entryFrame =
                lifecycle
                    .gameStateMessages()
                    .first { gsm ->
                        gsm.annotationsList.any {
                            AnnotationType.ZoneTransfer_af5a in it.typeList && it.detailString("category") == "Resolve"
                        }
                    }
            val sacrifice =
                lifecycle
                    .annotationsOfType(AnnotationType.ZoneTransfer_af5a)
                    .single { it.detailString("category") == "Sacrifice" }
            val evokeAbility = lifecycle.allGameObjects().last { it.instanceId == sacrifice.affectorId }
            val evokeResolution =
                lifecycle
                    .annotationsOfType(AnnotationType.ResolutionStart)
                    .last { it.affectorId == evokeAbility.instanceId }

            assertSoftly {
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain "Mulldrifter"
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Mulldrifter" } shouldBe false
                entryFrame.diffDeletedPersistentAnnotationIdsList shouldNotContain marker.id
                evokeAbility.parentId shouldBe marker.affectedIdsList.single()
                evokeAbility.grpId shouldBe evokeAbilityGrpId
                sacrifice.affectorId shouldBe evokeAbility.instanceId
                evokeResolution.detailUint("grpid") shouldBe evokeAbility.grpId
                lifecycle.deletedPersistentAnnotationIds() shouldContain marker.id
                lifecycle.annotationsOfType(AnnotationType.AbilityInstanceDeleted).any {
                    evokeAbility.instanceId in it.affectedIdsList
                } shouldBe true
            }
        }

        session(
            "ordinary cast stays on the battlefield without Evoke state",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Mulldrifter
                humanbattlefield=Island;Island;Island;Island;Island
                humanlibrary=Island;Island;Island;Island;Island
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val snap = messageSnapshot()
            castSpellByName("Mulldrifter").shouldBeTrue()
            passUntilResolved(maxPasses = 12)
            val lifecycle = messagesSince(snap)

            assertSoftly {
                human.getZone(ZoneType.Battlefield).cards.map { it.name } shouldContain "Mulldrifter"
                human.getZone(ZoneType.Graveyard).cards.any { it.name == "Mulldrifter" } shouldBe false
                lifecycle.persistentAnnotationsOfType(AnnotationType.TemporaryPermanent).shouldBeEmpty()
                lifecycle.persistentAnnotationsOfType(AnnotationType.CastingTimeOption).shouldBeEmpty()
                lifecycle.annotationsOfType(AnnotationType.ZoneTransfer_af5a).none {
                    it.detailString("category") == "Sacrifice"
                } shouldBe true
            }
        }
    })
