package leyline.bridge.coord

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.StaticChoiceKind
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.bundle.SettledPromptMaterializationContext
import leyline.game.bundle.StaticChoiceWindowMaterializer
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

/** Pins the protocol identity domains that make Arena's dedicated dungeon workflows activate. */
class DungeonProtocolShapeTest :
    FunSpec({
        tags(UnitTag)

        test("dungeon choices bypass generic static choices and retain all CardGrpIds") {
            val request =
                PromptRequest(
                    promptType = "choose_dungeon",
                    message = "Choose a dungeon",
                    options = listOf("Lost Mine of Phandelver", "Tomb of Annihilation"),
                    route = PromptRouteResolver.resolve(PromptSemantic.StaticDungeonChoice),
                    staticList = StaticList.None_a56d,
                    staticOptionIds = listOf(78769, 78770),
                )

            val window = StaticChoiceWindowCapture.initial(request)

            window.kind shouldBe StaticChoiceKind.Dungeon
            window.options.map { it.protocolValue } shouldBe listOf(78769, 78770)
            val emitted = materializeDungeonChoice(request)
            // Arena checks IsChoiceSelection before IsDungeonSelection. StaticSubset
            // always selects the generic workflow, regardless of the GRP identity domain.
            emitted.listType shouldBe SelectionListType.Dynamic
            emitted.idType shouldBe IdType.CardGrpId
            emitted.staticList shouldBe StaticList.None_a56d
            emitted.idsList shouldBe listOf(78769, 78770)
            emitted.minSel shouldBe 1
            emitted.maxSel shouldBe 1
        }

        test("room choices bypass generic choices and retain the dedicated room AbilityGrpIds") {
            val request =
                PromptRequest(
                    promptType = "choose_dungeon_room",
                    message = "Choose a room",
                    options = listOf("Goblin Bazaar", "Twisted Caverns"),
                    route = PromptRouteResolver.resolve(PromptSemantic.StaticDungeonRoomChoice),
                    staticList = StaticList.None_a56d,
                    staticOptionIds = listOf(146075, 146076),
                )

            val emitted = materializeDungeonChoice(request)
            // The dedicated dungeon-room predicate precedes ordinary dynamic ability choices.
            emitted.listType shouldBe SelectionListType.Dynamic
            emitted.idType shouldBe IdType.AbilityGrpId
            emitted.staticList shouldBe StaticList.None_a56d
            emitted.idsList shouldBe listOf(146075, 146076)
            emitted.minSel shouldBe 1
            emitted.maxSel shouldBe 1
        }
    })

private fun materializeDungeonChoice(request: PromptRequest): wotc.mtgo.gre.external.messaging.Messages.SelectNReq {
    val projection = ProjectionState.initial()
    val context =
        SettledPromptMaterializationContext(
            gameState = GameStateMessage.getDefaultInstance(),
            gameStateId = 1,
            sequence = LogicalSequencePlanner(initialGsId = 1, initialMsgId = 0),
            projection = projection,
            transition = ProjectionTransition(projection.revision, projection),
            seatId = 1,
        )
    return StaticChoiceWindowMaterializer()
        .prepare(context, StaticChoiceWindowCapture.initial(request))
        .bundle.messages
        .single { it.hasSelectNReq() }
        .selectNReq
}
