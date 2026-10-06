package leyline.session.mechanics

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.data.KeywordAbilityIds
import leyline.testkit.SessionTest
import leyline.testkit.allActions
import leyline.testkit.haveManaCost
import leyline.testkit.performAction
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CardType

private fun cloakPuzzle(topCard: String) =
    """
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Cryptic Coat
    humanbattlefield=Island;Island;Island;Island;Island;Forest
    humanlibrary=$topCard;Plains;Plains
    ailibrary=Forest;Forest;Forest;Forest
    """.trimIndent()

class CloakSessionTest :
    SessionTest({
        session(
            "Cryptic Coat cloaks, attaches, and turns a creature face up for its mana cost",
            puzzle = cloakPuzzle("Centaur Courser"),
            turns = 4,
        ) {
            castSpellByName("Cryptic Coat") shouldBe true
            passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Battlefield).cards.any { it.isCloaked }
            } shouldBe true

            val cloaked = human.getZone(ZoneType.Battlefield).cards.single { it.isCloaked }
            val coat = human.getZone(ZoneType.Battlefield).cards.single { it.name == "Cryptic Coat" }
            val cloakedIid = bridge.instanceId(cloaked)
            val coatIid = bridge.instanceId(coat)
            lastSelectNReq().idsList shouldBe listOf(cloakedIid)
            respondToSelectN(listOf(cloakedIid))
            passUntil(maxPasses = 6) {
                allMessages.allActions().any {
                    it.actionType == ActionType.SpecialTurnFaceUp_add3 && it.instanceId == cloakedIid
                }
            } shouldBe true
            val offer =
                allMessages
                    .allActions()
                    .lastOrNull {
                        it.actionType == ActionType.SpecialTurnFaceUp_add3 && it.instanceId == cloakedIid
                    }
            val cloakedObject = accumulator.objects[cloakedIid] ?: error("No accumulated object $cloakedIid")
            val coatObject = accumulator.objects[coatIid] ?: error("No accumulated object $coatIid")
            val turnUp = requireNotNull(offer) { "No turn-face-up offer for cloaked creature" }
            val faceDownAnnotation =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .last { AnnotationType.FaceDown in it.typeList && cloakedIid in it.affectedIdsList }

            assertSoftly {
                cloaked.isFaceDown shouldBe true
                cloaked.hasKeyword("Ward:2") shouldBe true
                coat.isAttachedToEntity(cloaked) shouldBe true
                cloakedObject.isFacedown shouldBe true
                cloakedObject.overlayGrpId shouldBe 3
                cloakedObject.cardTypesList shouldContainExactly listOf(CardType.Creature)
                cloakedObject.power.value shouldBe 2
                cloakedObject.toughness.value shouldBe 2
                cloakedObject.uniqueAbilitiesList.single().grpId shouldBe KeywordAbilityIds.WARD_TWO
                coatObject.parentId shouldBe cloakedIid
                faceDownAnnotation.detailsList.associate { it.key to it.valueInt32List.single() } shouldBe
                    mapOf("REASON" to 7, "abilityGrpId" to KeywordAbilityIds.CLOAK)
                turnUp.alternativeGrpId shouldBe KeywordAbilityIds.WARD_TWO
                turnUp.abilityGrpId shouldBe 0
                turnUp should haveManaCost(generic = 2, green = 1)
            }

            send(submitWithGsId(performAction(turnUp)))
            drainSink()

            assertSoftly {
                cloaked.isFaceDown shouldBe false
                cloaked.name shouldBe "Centaur Courser"
                coat.isAttachedToEntity(cloaked) shouldBe true
            }
        }

        session(
            "cloaked noncreature has no turn-face-up action",
            puzzle = cloakPuzzle("Island"),
            turns = 4,
        ) {
            castSpellByName("Cryptic Coat") shouldBe true
            passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Battlefield).cards.any { it.isCloaked }
            } shouldBe true

            val cloaked = human.getZone(ZoneType.Battlefield).cards.single { it.isCloaked }
            val cloakedIid = bridge.instanceId(cloaked)
            val hasTurnUp =
                allMessages
                    .allActions()
                    .any { it.actionType == ActionType.SpecialTurnFaceUp_add3 && it.instanceId == cloakedIid }

            assertSoftly {
                cloaked.isFaceDown shouldBe true
                cloaked.hasKeyword("Ward:2") shouldBe true
                hasTurnUp shouldBe false
            }
        }
    })
