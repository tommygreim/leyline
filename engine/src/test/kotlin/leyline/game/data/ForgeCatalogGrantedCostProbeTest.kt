package leyline.game.data

import com.google.protobuf.util.JsonFormat
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.ForgeCatalogTag
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.allGameObjects
import leyline.testkit.annotationsOfType
import leyline.testkit.battlefield
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.persistentAnnotationsOfType
import leyline.tooling.headless.MatchFlowHarness
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import java.io.File

class ForgeCatalogGrantedCostProbeTest :
    FunSpec({
        tags(IntegrationTag, ForgeCatalogTag)
        timeout = 600_000L
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("granted evoke offers the alternate cost and sacrifices the resolved creature") {
            grantedCostProbe("evoke") { repo ->
                val yarok = repo.findGrpIdByName("Yarok, the Desecrated")!!
                val offers =
                    allMessages
                        .last { it.hasActionsAvailableReq() }
                        .actionsAvailableReq.actionsList
                        .filter { it.grpId == yarok && it.actionType == ActionType.Cast }
                offers.size shouldBe 2
                val evoke = offers.single { it.alternativeGrpId != 0 }
                evoke.manaCostList.sumOf { it.count } shouldBe 4
                submitAction(evoke)
                passUntilResolved(maxPasses = 20)
                assertSoftly {
                    human.getZone(ZoneType.Graveyard).cards.count { it.name == "Yarok, the Desecrated" } shouldBe 1
                    allMessages
                        .persistentAnnotationsOfType(AnnotationType.CastingTimeOption)
                        .any { it.detailInt("alternateCostGrpId") == evoke.alternativeGrpId } shouldBe true
                    allMessages
                        .persistentAnnotationsOfType(AnnotationType.TemporaryPermanent)
                        .any { it.detailInt("AbilityGrpId") == evoke.alternativeGrpId } shouldBe true
                }
                val sacrifice =
                    allMessages
                        .annotationsOfType(AnnotationType.ZoneTransfer_af5a)
                        .single { it.detailString("category") == "Sacrifice" }
                allMessages.allGameObjects().last { it.instanceId == sacrifice.affectorId }.grpId shouldBe evoke.alternativeGrpId
            }
        }

        test("removing the grant leaves only the printed cost") {
            grantedCostProbe("revoked") { repo ->
                val ashling = human.battlefield.iid("Ashling, the Limitless")
                castSpellByName("Unsummon") shouldBe true
                selectTargets(listOf(ashling))
                passUntilResolved(maxPasses = 12)
                val yarok = repo.findGrpIdByName("Yarok, the Desecrated")!!
                val offers = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList
                offers
                    .filter { it.grpId == yarok && it.actionType == ActionType.Cast }
                    .map { it.alternativeGrpId } shouldBe listOf(0)
            }
        }

        test("ordinary casts and nonqualifying creatures do not acquire evoke") {
            grantedCostProbe("ordinary") { repo ->
                val bears = repo.findGrpIdByName("Grizzly Bears")!!
                val offers = allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList
                offers
                    .filter { it.grpId == bears && it.actionType == ActionType.Cast }
                    .map { it.alternativeGrpId } shouldBe listOf(0)
                castSpellByName("Yarok, the Desecrated") shouldBe true
                passUntilResolved(maxPasses = 12)
                human.getZone(ZoneType.Graveyard).cards.count { it.name == "Yarok, the Desecrated" } shouldBe 0
                allMessages.persistentAnnotationsOfType(AnnotationType.CastingTimeOption).size shouldBe 0
            }
        }
    })

private fun grantedCostProbe(
    name: String,
    block: MatchFlowHarness.(ForgeCardRepository) -> Unit,
) {
    val repo = ForgeCardRepository.open()
    val harness = MatchFlowHarness(cardRepositoryOverride = repo)
    try {
        harness.connect(
            puzzleText =
                """
                [metadata]
                Name:Granted evoke
                Goal:Survive
                Turns:2
                Difficulty:Easy
                Description:Choose a granted alternative casting cost.
                [state]
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Ashling, the Limitless;Island;Island;Island;Swamp;Forest;Plains
                humanhand=Yarok, the Desecrated;Grizzly Bears;Unsummon
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        )
        harness.block(repo)
    } finally {
        val output = File("build/forge-catalog-probe/granted-evoke-$name.gre.json")
        output.parentFile.mkdirs()
        output.writeText(harness.allMessages.joinToString(",", "[", "]") { JsonFormat.printer().print(it) })
        harness.shutdown()
    }
}
