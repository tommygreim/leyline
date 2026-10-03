package leyline.mechanics.mutate

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.CardMechanicType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

private const val MUTATE_CARD = "Insatiable Hemophage"
private const val TARGET_CREATURE = "Runeclaw Bear"

class MutateActionTest :
    BoardTest({
        test("ActionMapper offers hand Cast with per-card mutate alternativeGrpId") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard("Swamp", human, ZoneType.Battlefield)
                    addCard(MUTATE_CARD, human, ZoneType.Hand)
                    addCard(TARGET_CREATURE, human, ZoneType.Battlefield)
                }
            val human = game.humanPlayer
            val grpId = b.cardRepository.findGrpIdByName(MUTATE_CARD)!!
            val mutateGrpId = b.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.MUTATE)!!
            mutateGrpId shouldBeGreaterThan 0

            val snap = SnapshotCapture.run(game, b, "test", 0)
            val actions = ActionMapper.buildFromSnapshot(1, snap, b)
            val cardIid = human.hand.iid(MUTATE_CARD)

            val offer =
                actions.actionsList.firstOrNull {
                    it.actionType == ActionType.Cast &&
                        it.instanceId == cardIid &&
                        it.grpId == grpId &&
                        it.alternativeGrpId == mutateGrpId
                }

            assertSoftly {
                offer shouldNotBe null
                offer!!.facetId shouldBe cardIid
                offer.abilityGrpId shouldBe 0
                offer.manaCostList.forEach { it.abilityGrpId shouldBe mutateGrpId }
            }
        }
    })

class MutateLifecycleTest :
    SessionTest({
        session(
            "mutate prompts for top or under, then resolves under with Suppressed component shape",
            puzzle = MUTATE_PUZZLE,
        ) {
            val prompt = castMutateAndSelectTarget()
            val result = resolveMutate(putOnTop = false)

            assertMutateMerge(
                prompt = prompt,
                result = result,
                expectedIsTop = 0,
                expectedVisibleGrpId = prompt.targetGrpId,
            )
        }

        session(
            "mutate top choice makes the mutating component the visible merged face",
            puzzle = MUTATE_PUZZLE,
        ) {
            val prompt = castMutateAndSelectTarget()
            val result = resolveMutate(putOnTop = true)

            assertMutateMerge(
                prompt = prompt,
                result = result,
                expectedIsTop = 1,
                expectedVisibleGrpId = prompt.cardGrpId,
            )
        }
    })

private data class MutatePromptState(
    val cardGrpId: Int,
    val targetGrpId: Int,
    val mutateGrpId: Int,
    val stackIid: Int,
    val targetIid: Int,
)

private data class MutateResolutionState(
    val gsms: List<wotc.mtgo.gre.external.messaging.Messages.GameStateMessage>,
    val suppressedIid: Int,
)

private val MUTATE_PUZZLE =
    """
    [metadata]
    Name:Mutate Hemophage
    Goal:Win
    Turns:4

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=$MUTATE_CARD
    humanbattlefield=Swamp;Swamp;Swamp;$TARGET_CREATURE
    humanlibrary=Swamp;Swamp;Swamp
    ailibrary=Mountain;Mountain;Mountain
    """.trimIndent()

private fun MatchFlowHarness.castMutateAndSelectTarget(): MutatePromptState {
    val cardGrpId = bridge.cardRepository.findGrpIdByName(MUTATE_CARD)!!
    val targetGrpId = bridge.cardRepository.findGrpIdByName(TARGET_CREATURE)!!
    val mutateGrpId = bridge.cardRepository.findKeywordAbilityGrpId(cardGrpId, KeywordAbilityIds.MUTATE)!!
    val targetIid = instanceIdOf(TARGET_CREATURE)

    val castSlice = after { castSpellByName(MUTATE_CARD, alternativeGrpId = mutateGrpId).shouldBeTrue() }
    val selectTargetsMsg = castSlice.messages.firstOrNull { it.hasSelectTargetsReq() }
    selectTargetsMsg shouldNotBe null

    val req = selectTargetsMsg!!.selectTargetsReq
    val stackIid = req.sourceId
    val targetGroup = req.targetsList.single()
    val promptParameter = targetGroup.prompt.parametersList.single()
    assertSoftly {
        selectTargetsMsg.allowCancel shouldBe AllowCancel.Abort
        selectTargetsMsg.allowUndo.shouldBeTrue()
        req.abilityGrpId shouldBe KeywordAbilityIds.MUTATE
        targetGroup.prompt.promptId shouldBe PromptIds.MUTATE_TARGET
        promptParameter.numberValue shouldBe stackIid
        targetGroup.targetSourceZoneId shouldBe ZoneIds.BATTLEFIELD
        targetGroup.targetingAbilityGrpId shouldBe mutateGrpId
        targetGroup.targetsList.map { it.targetInstanceId } shouldContain targetIid
    }

    holdNextOptionalAction()
    val topBottomSlice = after { selectTargets(listOf(targetIid)) }
    val optionalMsg = topBottomSlice.messages.firstOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
    optionalMsg shouldNotBe null
    val optional = optionalMsg!!.optionalActionMessage
    val targetSpec =
        topBottomSlice.messages
            .filter { it.hasGameStateMessage() }
            .flatMap { it.gameStateMessage.persistentAnnotationsList }
            .firstOrNull { AnnotationType.TargetSpec in it.typeList && it.affectedIdsList.contains(targetIid) }
    assertSoftly {
        optionalMsg.allowCancel shouldBe AllowCancel.No_a526
        optional.sourceId shouldBe stackIid
        optional.recipientIdsList shouldBe listOf(targetIid)
        optional.optionalActionTypesList shouldContain CardMechanicType.Mutate

        withClue("Mutate target prompt TargetSpec") { targetSpec shouldNotBe null }
        targetSpec!!.affectorId shouldBe stackIid
        targetSpec.detailInt("abilityGrpId") shouldBe 0
        targetSpec.detailInt("promptId") shouldBe PromptIds.MUTATE_TARGET
        targetSpec.detailInt("promptParameters") shouldBe stackIid
    }

    return MutatePromptState(cardGrpId, targetGrpId, mutateGrpId, stackIid, targetIid)
}

private fun MatchFlowHarness.resolveMutate(putOnTop: Boolean): MutateResolutionState {
    val resolveStart = messageSnapshot()
    respondToOptionalAction(accept = putOnTop)
    passUntilResolved(maxPasses = 12)
    val gsms = messagesSince(resolveStart).filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
    val suppressedObject =
        gsms
            .flatMap { it.gameObjectsList }
            .lastOrNull { it.zoneId == ZoneIds.SUPPRESSED }
    val objectSummary = gsms.flatMap { it.gameObjectsList }.map { "${it.instanceId}:${it.grpId}:${it.zoneId}" }
    withClue(
        "objects=" +
            objectSummary +
            " merged=" + human.getZone(ZoneType.Merged).cards.map { "${it.name}:${it.id}" },
    ) {
        suppressedObject shouldNotBe null
    }
    return MutateResolutionState(gsms, suppressedObject!!.instanceId)
}

private fun MatchFlowHarness.assertMutateMerge(
    prompt: MutatePromptState,
    result: MutateResolutionState,
    expectedIsTop: Int,
    expectedVisibleGrpId: Int,
) {
    val gsms = result.gsms
    val mergeEffect =
        gsms
            .flatMap { it.persistentAnnotationsList }
            .firstOrNull {
                AnnotationType.LayeredEffect in it.typeList &&
                    it.affectorId == result.suppressedIid &&
                    it.affectedIdsList.contains(prompt.targetIid)
            }
    val stackToSuppressedTransfer =
        gsms
            .flatMap { it.annotationsList }
            .filter { AnnotationType.ZoneTransfer_af5a in it.typeList }
            .any {
                it.affectedIdsList.contains(result.suppressedIid) &&
                    it.detailInt("zone_src") == ZoneIds.STACK &&
                    it.detailInt("zone_dest") == ZoneIds.SUPPRESSED
            }
    val targetObject = accumulator.objects[prompt.targetIid]
    val finalSuppressedZone = accumulator.zones.getValue(ZoneIds.SUPPRESSED)
    val finalBattlefieldZone = accumulator.zones.getValue(ZoneIds.BATTLEFIELD)

    assertSoftly {
        withClue("Mutate LayeredEffect pAnn") { mergeEffect shouldNotBe null }
        mergeEffect!!.detailIntList("abilityGRPIDs") shouldContain prompt.mutateGrpId
        mergeEffect.detailInt("isTop") shouldBe expectedIsTop
        mergeEffect.detailInt("abilityGrpId") shouldBe KeywordAbilityIds.MUTATE
        stackToSuppressedTransfer shouldBe false

        withClue("merged target object") { targetObject shouldNotBe null }
        targetObject!!.zoneId shouldBe ZoneIds.BATTLEFIELD
        targetObject.grpId shouldBe expectedVisibleGrpId
        targetObject.uniqueAbilitiesList.map { it.grpId } shouldContain prompt.mutateGrpId
        targetObject.uniqueAbilitiesList.count { it.grpId == prompt.mutateGrpId } shouldBe 1
        targetObject.abilityOriginalCardGrpIdsList.size shouldBe targetObject.uniqueAbilitiesCount
        targetObject.abilityOriginalCardGrpIdsList shouldBe List(targetObject.uniqueAbilitiesCount) { prompt.cardGrpId }
        finalSuppressedZone.objectInstanceIdsList shouldContain result.suppressedIid
        finalBattlefieldZone.objectInstanceIdsList shouldContain prompt.targetIid
        finalBattlefieldZone.objectInstanceIdsList shouldNotContain result.suppressedIid
    }
}
