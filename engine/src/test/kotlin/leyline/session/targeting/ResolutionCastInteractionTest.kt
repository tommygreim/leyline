package leyline.session.targeting

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.allAnnotations
import leyline.testkit.beInExileOf
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beOnBattlefieldOf
import leyline.testkit.detailUint
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

private const val CAPSTONE_PUZZLE = "data/puzzles/resolution-cast-capstone.pzl"

private fun List<GREToClientMessage>.resolutionCastRequests() =
    filter { it.hasActionsAvailableReq() && it.prompt.promptId == PromptIds.RESOLUTION_CAST_ANY_FREE }

class ResolutionCastInteractionTest :
    SessionTest({
        session(
            "Play effect selects each free cast from a resolution browser and reopens for remaining cards",
            puzzleFile = CAPSTONE_PUZZLE,
            forgeCatalog = true,
            fullControl = true,
        ) {
            castSpellByName("Improvisation Capstone").shouldBeTrue()
            passUntil(maxPasses = 12) { allMessages.resolutionCastRequests().isNotEmpty() }.shouldBeTrue()

            val first = allMessages.resolutionCastRequests().last()
            val firstCasts = first.actionsAvailableReq.actionsList.filter { it.actionType == ActionType.Cast }
            val searGrpId = checkNotNull(bridge.cardRepository.findGrpIdByName("Sear"))
            val overlordGrpId = checkNotNull(bridge.cardRepository.findGrpIdByName("Overlord of the Boilerbilges"))
            assertSoftly {
                firstCasts.map { it.grpId }.shouldContainExactlyInAnyOrder(searGrpId, overlordGrpId)
                firstCasts.map { it.sourceId }.distinct().size shouldBe 1
                first.actionsAvailableReq.actionsList.count { it.actionType == ActionType.Pass } shouldBe 1
                firstCasts.all { it.alternativeGrpId == 149 }.shouldBeTrue()
                allMessages
                    .filter { it.hasGameStateMessage() && it.gameStateId == first.gameStateId }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .any { AnnotationType.ResolutionStart in it.typeList && it.affectorId == firstCasts.first().sourceId }
                    .shouldBeTrue()
            }

            val beforeSear = messageSnapshot()
            submitAction(firstCasts.single { it.grpId == searGrpId })
            messagesSince(beforeSear).none { it.hasOptionalActionMessage() || it.hasSelectNReq() }.shouldBeTrue()
            selectTargets(listOf(ai.battlefield.iid("Colossal Dreadmaw")))

            passUntil(maxPasses = 12) { allMessages.resolutionCastRequests().size >= 2 }.shouldBeTrue()
            val second = allMessages.resolutionCastRequests().last()
            val remaining = second.actionsAvailableReq.actionsList.filter { it.actionType == ActionType.Cast }
            assertSoftly {
                remaining.map { it.grpId } shouldBe listOf(overlordGrpId)
                remaining.single().sourceId shouldBe firstCasts.first().sourceId
                second.actionsAvailableReq.actionsList.count { it.actionType == ActionType.Pass } shouldBe 1
                messagesSince(beforeSear).none { it.hasOptionalActionMessage() || it.hasSelectNReq() }.shouldBeTrue()
            }
            val beforeOverlord = messageSnapshot()
            submitAction(remaining.single())
            messagesSince(beforeSear).none { it.hasOptionalActionMessage() || it.hasSelectNReq() }.shouldBeTrue()
            passUntil(maxPasses = 12) { messagesSince(beforeOverlord).any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntil(maxPasses = 12) { game().stack.isEmpty }.shouldBeTrue()
            assertSoftly {
                "Sear" should beInGraveyardOf(human)
                "Overlord of the Boilerbilges" should beOnBattlefieldOf(human)
                "Improvisation Capstone" should beInExileOf(human)
                "Colossal Dreadmaw" should beOnBattlefieldOf(ai)
                ai.life shouldBe 26
            }
            val castGrpIds =
                listOf("Improvisation Capstone", "Overlord of the Boilerbilges", "Sear")
                    .map { checkNotNull(bridge.cardRepository.findGrpIdByName(it)) }
            val resolutions =
                allMessages
                    .allAnnotations()
                    .filter { AnnotationType.ResolutionComplete in it.typeList }
                    .map { it.detailUint("grpid") }
                    .filter { it in castGrpIds }
                    .distinct()
            resolutions shouldBe castGrpIds
            allMessages
                .allAnnotations()
                .filter { it.affectorId == firstCasts.first().sourceId }
                .flatMap { it.typeList }
                .filter { it == AnnotationType.ResolutionStart || it == AnnotationType.ResolutionComplete } shouldBe
                listOf(AnnotationType.ResolutionStart, AnnotationType.ResolutionComplete)
        }

        session(
            "Play effect can decline all remaining casts at its first browser",
            puzzleFile = CAPSTONE_PUZZLE,
            forgeCatalog = true,
            fullControl = true,
        ) {
            castSpellByName("Improvisation Capstone").shouldBeTrue()
            passUntil(maxPasses = 12) { allMessages.resolutionCastRequests().isNotEmpty() }.shouldBeTrue()
            val picker = allMessages.resolutionCastRequests().last()
            val beforeDecline = messageSnapshot()
            submitAction(picker.actionsAvailableReq.actionsList.single { it.actionType == ActionType.Pass })
            messagesSince(beforeDecline).none { it.hasOptionalActionMessage() }.shouldBeTrue()
            passUntil(maxPasses = 12) { game().stack.isEmpty }.shouldBeTrue()
            allMessages.resolutionCastRequests().size shouldBe 1
            assertSoftly {
                "Sear" should beInExileOf(human)
                "Overlord of the Boilerbilges" should beInExileOf(human)
                "Improvisation Capstone" should beInExileOf(human)
            }
        }

        session(
            "Etali's resolving ability remains the source across two card choices",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true
                humanbattlefield=Etali, Primal Storm
                humanlibrary=Shock;Mountain;Mountain;Mountain;Mountain
                ailibrary=Grizzly Bears;Forest;Forest;Forest;Forest
            """,
            turns = 4,
            forgeCatalog = true,
            fullControl = true,
        ) {
            val etaliIid = human.battlefield.iid("Etali, Primal Storm")
            advanceToCombat()
            declareAttackers(listOf(etaliIid))
            passUntil(maxPasses = 16) { allMessages.resolutionCastRequests().isNotEmpty() }.shouldBeTrue()
            val first = allMessages.resolutionCastRequests().last()
            val firstCasts = first.actionsAvailableReq.actionsList.filter { it.actionType == ActionType.Cast }
            val bearGrpId = checkNotNull(bridge.cardRepository.findGrpIdByName("Grizzly Bears"))
            val shockGrpId = checkNotNull(bridge.cardRepository.findGrpIdByName("Shock"))
            firstCasts.map { it.grpId }.shouldContainExactlyInAnyOrder(bearGrpId, shockGrpId)
            val sourceId = firstCasts.first().sourceId
            val source =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .last { it.instanceId == sourceId }
            source.zoneId shouldBe ZoneIds.STACK

            submitAction(firstCasts.single { it.grpId == bearGrpId })
            passUntil(maxPasses = 12) { allMessages.resolutionCastRequests().size >= 2 }.shouldBeTrue()
            val second = allMessages.resolutionCastRequests().last()
            second.actionsAvailableReq.actionsList
                .single { it.actionType == ActionType.Cast }
                .sourceId shouldBe sourceId
            submitAction(second.actionsAvailableReq.actionsList.single { it.actionType == ActionType.Pass })
            allMessages.resolutionCastRequests().size shouldBe 2
            passUntil(maxPasses = 12) { game().stack.isEmpty }.shouldBeTrue()
            assertSoftly {
                "Grizzly Bears" should beOnBattlefieldOf(human)
                "Shock" should beInExileOf(human)
                "Etali, Primal Storm" should beOnBattlefieldOf(human)
            }
        }

        session(
            "Play effect can decline only the remaining cast after selecting Sear",
            puzzleFile = CAPSTONE_PUZZLE,
            forgeCatalog = true,
            fullControl = true,
        ) {
            castSpellByName("Improvisation Capstone").shouldBeTrue()
            passUntil(maxPasses = 12) { allMessages.resolutionCastRequests().isNotEmpty() }.shouldBeTrue()
            val first = allMessages.resolutionCastRequests().last()
            val searGrpId = checkNotNull(bridge.cardRepository.findGrpIdByName("Sear"))
            submitAction(first.actionsAvailableReq.actionsList.single { it.actionType == ActionType.Cast && it.grpId == searGrpId })
            selectTargets(listOf(ai.battlefield.iid("Colossal Dreadmaw")))
            passUntil(maxPasses = 12) { allMessages.resolutionCastRequests().size == 2 }.shouldBeTrue()
            val second = allMessages.resolutionCastRequests().last()
            second.actionsAvailableReq.actionsList.count { it.actionType == ActionType.Cast } shouldBe 1
            submitAction(second.actionsAvailableReq.actionsList.single { it.actionType == ActionType.Pass })
            passUntil(maxPasses = 12) { game().stack.isEmpty }.shouldBeTrue()
            assertSoftly {
                "Sear" should beInGraveyardOf(human)
                "Overlord of the Boilerbilges" should beInExileOf(human)
                "Improvisation Capstone" should beInExileOf(human)
                "Colossal Dreadmaw" should beOnBattlefieldOf(ai)
                allMessages.resolutionCastRequests().size shouldBe 2
            }
        }

        session(
            "Mizzix copy uses a resolution-cast action and skips a second cast confirmation",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Mizzix's Mastery
                humangraveyard=Shock
                humanbattlefield=Mountain;Mountain;Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Forest;Forest;Forest
            """,
            forgeCatalog = true,
            fullControl = true,
        ) {
            castSpellByName("Mizzix's Mastery").shouldBeTrue()
            selectTargets(listOf(human.graveyard.iid("Shock")))
            passUntil(maxPasses = 12) {
                allMessages.any { it.hasActionsAvailableReq() && it.prompt.promptId == PromptIds.RESOLUTION_CAST_COPIES }
            }.shouldBeTrue()
            val picker = allMessages.last { it.hasActionsAvailableReq() && it.prompt.promptId == PromptIds.RESOLUTION_CAST_COPIES }
            val shockGrpId = checkNotNull(bridge.cardRepository.findGrpIdByName("Shock"))
            val cast = picker.actionsAvailableReq.actionsList.single { it.actionType == ActionType.Cast }
            assertSoftly {
                cast.grpId shouldBe shockGrpId
                picker.actionsAvailableReq.actionsList.count { it.actionType == ActionType.Pass } shouldBe 1
                cast.alternativeGrpId shouldBe 149
            }
            val beforeCast = messageSnapshot()
            submitAction(cast)
            messagesSince(beforeCast).none { it.hasOptionalActionMessage() || it.hasSelectNReq() }.shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            passUntil(maxPasses = 12) { game().stack.isEmpty }.shouldBeTrue()
            assertSoftly {
                "Shock" should beInExileOf(human)
                "Mizzix's Mastery" should beInExileOf(human)
                ai.life shouldBe 18
            }
        }
    })
