package leyline.mechanics.warp

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.getAllCastableAbilities
import leyline.game.codes.DetailKeys
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.beInExileOf
import leyline.testkit.beMissingFrom
import leyline.testkit.beOnBattlefieldOf
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.hasCard
import leyline.testkit.hasDetail
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

private const val WARP_DELAYED_ABILITY_GRP_ID = KeywordAbilityIds.WARP_DELAYED_TRIGGER

private val WARP_PUZZLE =
    """
    [metadata]
    Name:Warp - Germinating Wurm cast for warp cost
    Goal:Cast Germinating Wurm from hand for its warp cost ({1}{G}).
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Germinating Wurm
    humanbattlefield=Forest;Forest
    humanlibrary=Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

private val REGULAR_COST_PUZZLE =
    """
    [metadata]
    Name:Warp - regular-cost cast stays on battlefield
    Goal:Cast Germinating Wurm for 4G; it stays post-turn.
    Turns:5
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Germinating Wurm
    humanbattlefield=Forest;Forest;Forest;Forest;Forest
    humanlibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

private val WARP_END_STEP_PUZZLE =
    """
    [metadata]
    Name:Warp - warp-cost cast exiles at end of turn
    Goal:Cast Germinating Wurm for {1}{G}; exiled at end of turn.
    Turns:5
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Germinating Wurm
    humanbattlefield=Forest;Forest;Forest;Forest;Forest
    humanlibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

private val QUANTUM_RIDDLER_PUZZLE =
    """
    [metadata]
    Name:Warp - delayed exile lifecycle
    Goal:Cast Quantum Riddler for its warp cost, then exile it at end step.
    Turns:5
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Quantum Riddler
    humanbattlefield=Island;Island;Island;Island;Island
    humanlibrary=Island;Island;Island;Island;Island;Island;Island;Island
    ailibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
    """.trimIndent()

@Suppress("UnnecessaryNotNullOperator")
class WarpLifecycleTest :
    SessionTest({
        for (otherCards in 0..2) {
            session(
                "draw trigger shows active replacement context with $otherCards other cards in hand",
                puzzle =
                    QUANTUM_RIDDLER_PUZZLE.replace(
                        "humanhand=Quantum Riddler",
                        "humanhand=Quantum Riddler" + ";Island".repeat(otherCards),
                    ),
                fullControl = true,
            ) {
                val cardGrpId = checkNotNull(bridge.cardRepository.findGrpIdByName("Quantum Riddler"))
                val warpRow = checkNotNull(bridge.cardRepository.findKeywordAbilityGrpId(cardGrpId, KeywordAbilityIds.WARP))
                check(castSpellByName("Quantum Riddler", alternativeGrpId = warpRow))
                check(passUntil(maxPasses = 20) { human.hasCard("Quantum Riddler", ZoneType.Battlefield) })
                val trigger =
                    SnapshotCapture
                        .run(game(), bridge, "replacement-context", 0)
                        .stack.entries
                        .single { !it.isSpell }
                check(passUntil(maxPasses = 20) { game().stack.isEmpty })
                val stackObjects =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .filter { it.type == GameObjectType.Ability && it.grpId == 86788 }
                assertSoftly {
                    trigger.grpId shouldBe 86788
                    trigger.contextualAbilities shouldBe if (otherCards <= 1) listOf(190918 to cardGrpId) else emptyList()
                    human.getZone(ZoneType.Hand).cards.size shouldBe otherCards + if (otherCards <= 1) 2 else 1
                    stackObjects.shouldNotBeEmpty()
                    stackObjects.any { it.uniqueAbilitiesList.any { row -> row.grpId == 190918 } } shouldBe (otherCards <= 1)
                }
            }
        }

        session("alternativeGrpId cast chooses the warp spell ability", puzzle = WARP_PUZZLE) {
            val warpAbilityGrpId = warpAbilityGrpId()

            check(castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId))
            check(passUntil(maxPasses = 20) { game().stack.isEmpty })

            assertSoftly {
                "Germinating Wurm" should beMissingFrom(ZoneType.Hand, human)
                human.hasCardAnywhereExceptHand("Germinating Wurm") shouldBe true
            }
        }

        session("warp cast emits CastThroughAbility annotation for the selected rail", puzzle = WARP_PUZZLE) {
            val warpAbilityGrpId = warpAbilityGrpId()

            check(castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId))
            check(passUntil(maxPasses = 20) { game().stack.isEmpty })

            val cto =
                allMessages
                    .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
                    .flatMap { it.persistentAnnotationsList }
                    .firstOrNull { it.typeList.contains(AnnotationType.CastingTimeOption) }

            assertSoftly(cto) {
                it shouldNotBe null
                it!!.detailInt("type") shouldBe 13
                it.detailInt("alternateCostGrpId") shouldBe warpAbilityGrpId
                it.detailInt("castAbilityGrpId") shouldBe warpAbilityGrpId
            }
        }

        session("regular-cost cast keeps Germinating Wurm on the battlefield", puzzle = REGULAR_COST_PUZZLE) {
            check(castSpellByName("Germinating Wurm"))
            check(passUntil(maxPasses = 20) { game().stack.isEmpty })
            "Germinating Wurm" should beOnBattlefieldOf(human)

            passUntilTurn(2, maxPasses = 30)

            // One copy in the puzzle, so battlefield membership excludes the rest.
            "Germinating Wurm" should beOnBattlefieldOf(human)
            allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.persistentAnnotationsList }
                .none { AnnotationType.DelayedTriggerAffectees in it.typeList } shouldBe true
        }

        session("warp-cost cast exiles Germinating Wurm at end of turn", puzzle = WARP_END_STEP_PUZZLE) {
            val warpAbilityGrpId = warpAbilityGrpId()

            check(castSpellByName("Germinating Wurm", alternativeGrpId = warpAbilityGrpId))
            check(passUntil(maxPasses = 20) { game().stack.isEmpty })
            passUntilTurn(2, maxPasses = 30)

            assertSoftly {
                "Germinating Wurm" should beInExileOf(human)
            }
        }

        session("warp delayed exile preserves its pending row through ability resolution", puzzle = QUANTUM_RIDDLER_PUZZLE) {
            val riddlerGrpId = bridge.cardRepository.findGrpIdByName("Quantum Riddler")!!
            val warpAbilityGrpId = bridge.cardRepository.findKeywordAbilityGrpId(riddlerGrpId, KeywordAbilityIds.WARP)!!

            check(castSpellByName("Quantum Riddler", alternativeGrpId = warpAbilityGrpId))
            check(passUntil(maxPasses = 20) { game().stack.isEmpty })

            val battlefieldIid = human.battlefield.iid("Quantum Riddler")
            val holder =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .single { it.type == GameObjectType.TriggerHolder }
            val pending =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .single { AnnotationType.DelayedTriggerAffectees in it.typeList }
            val castAction =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .single {
                        AnnotationType.UserActionTaken in it.typeList &&
                            it.hasDetail(DetailKeys.ALTERNATIVE_GRP_ID) &&
                            it.detailInt(DetailKeys.ALTERNATIVE_GRP_ID) == warpAbilityGrpId
                    }
            assertSoftly {
                castAction.detailInt(DetailKeys.ABILITY_GRP_ID) shouldBe 0
                holder.objectSourceGrpId shouldBe riddlerGrpId
                holder.parentId shouldBe battlefieldIid
                holder.uniqueAbilitiesList.single().grpId shouldBe WARP_DELAYED_ABILITY_GRP_ID
                pending.affectedIdsList shouldBe listOf(battlefieldIid)
                pending.detailInt(DetailKeys.ABILITY_GRP_ID) shouldBe WARP_DELAYED_ABILITY_GRP_ID
                pending.detailInt(DetailKeys.REMOVES_FROM_ZONE) shouldBe 1
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .none {
                        AnnotationType.TemporaryPermanent in it.typeList &&
                            battlefieldIid in it.affectedIdsList
                    } shouldBe true
            }

            val endStepStart = allMessages.size
            check(passUntil(maxPasses = 30) { human.hasCard("Quantum Riddler", ZoneType.Exile) })
            val endStepMessages = allMessages.drop(endStepStart)
            val endStepGsms = endStepMessages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            val ability =
                endStepGsms
                    .flatMap { it.gameObjectsList }
                    .single { it.type == GameObjectType.Ability && it.grpId == WARP_DELAYED_ABILITY_GRP_ID }
            val created =
                endStepGsms
                    .flatMap { it.annotationsList }
                    .single {
                        AnnotationType.AbilityInstanceCreated in it.typeList &&
                            ability.instanceId in it.affectedIdsList
                    }
            val updated =
                endStepGsms
                    .flatMap { it.persistentAnnotationsList }
                    .first {
                        it.id == pending.id &&
                            AnnotationType.DelayedTriggerAffectees in it.typeList &&
                            it.affectorId == ability.instanceId
                    }
            val transfer =
                endStepGsms
                    .flatMap { it.annotationsList }
                    .single {
                        AnnotationType.ZoneTransfer_af5a in it.typeList &&
                            it.detailString(DetailKeys.CATEGORY) == "Warp"
                    }
            val resolution = endStepGsms.flatMap { it.annotationsList }.filter { it.affectorId == ability.instanceId }
            val deleted =
                endStepGsms
                    .flatMap { it.annotationsList }
                    .single {
                        AnnotationType.AbilityInstanceDeleted in it.typeList &&
                            ability.instanceId in it.affectedIdsList
                    }

            assertSoftly {
                created.affectorId shouldBe battlefieldIid
                updated.affectedIdsList shouldBe pending.affectedIdsList
                transfer.affectorId shouldBe battlefieldIid
                transfer.detailInt(DetailKeys.ZONE_SRC) shouldBe ZoneIds.BATTLEFIELD
                transfer.detailInt(DetailKeys.ZONE_DEST) shouldBe ZoneIds.EXILE
                resolution.flatMap { it.typeList } shouldContain AnnotationType.ResolutionStart
                resolution.flatMap { it.typeList } shouldContain AnnotationType.ResolutionComplete
                deleted.affectorId shouldBe battlefieldIid
                endStepGsms.flatMap { it.diffDeletedPersistentAnnotationIdsList } shouldContain pending.id
            }

            val exiled = human.getCardsIn(ZoneType.Exile).single { it.name == "Quantum Riddler" }
            check(passUntil(maxPasses = 30) { getAllCastableAbilities(exiled, human).isNotEmpty() })
            check(!isGameOver())
            getAllCastableAbilities(exiled, human).shouldNotBeEmpty()
            val exileCast =
                ActionMapper
                    .buildFromSnapshot(1, SnapshotCapture.run(game(), bridge, "test", 0), bridge)
                    .actionsList
                    .single { it.actionType == ActionType.Cast && it.instanceId == bridge.instanceId(exiled) }
            exileCast.hasAutoTapSolution() shouldBe true
            check(castFromExile("Quantum Riddler"))
            check(passUntil(maxPasses = 20) { game().stack.isEmpty })
            "Quantum Riddler" should beOnBattlefieldOf(human)
        }
    })

private fun MatchFlowHarness.warpAbilityGrpId(): Int {
    val repo = bridge.cardRepository
    val wurmGrpId = repo.findGrpIdByName("Germinating Wurm")!!
    return repo.findKeywordAbilityGrpId(wurmGrpId, KeywordAbilityIds.WARP)!!
}

private fun forge.game.player.Player.hasCardAnywhereExceptHand(name: String): Boolean =
    hasCard(name, ZoneType.Battlefield) ||
        hasCard(name, ZoneType.Exile) ||
        hasCard(name, ZoneType.Graveyard)
