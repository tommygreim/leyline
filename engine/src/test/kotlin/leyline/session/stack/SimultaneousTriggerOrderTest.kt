package leyline.session.stack

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.allAnnotations
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.OrderingType
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import kotlin.time.Duration.Companion.seconds

/**
 * CR 603.3b: when several triggered abilities controlled by one player trigger at once, that
 * player chooses the order they go on the stack. Forge asks through
 * `PCHuman.orderSimultaneousSa` -> [leyline.bridge.forge.ClientGuiGame.order]; the human seat
 * now gets the client's trigger-order browser (`TriggeredAbilityWorkflow`): a `SelectNReq`
 * tagged `SelectionContext.TriggeredAbility` + `OptionContext.Stacking` whose ids are the pending
 * trigger objects, listed left to right as the browser lays them out ("Last" ... "First").
 *
 * Soul Warden ("another creature enters: you gain 1 life") and Impact Tremors ("a creature enters
 * under your control: 1 damage to each opponent") both trigger off one Grizzly Bears, and their
 * effects land on different life totals, so the life totals after the first resolution say which
 * trigger resolved first.
 */
class SimultaneousTriggerOrderTest :
    SessionTest({

        timeout = 60.seconds.inWholeMilliseconds

        val wardenAndTremors =
            """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Soul Warden;Impact Tremors;Forest;Forest
            humanhand=Grizzly Bears
            humanlibrary=Forest;Forest;Forest
            ailibrary=Mountain;Mountain;Mountain
            """.trimIndent()

        fun MatchFlowHarness.latestRequest(): GREToClientMessage = allMessages.last { it.hasSelectNReq() }

        fun MatchFlowHarness.frameOf(request: GREToClientMessage): GameStateMessage =
            allMessages.last { it.hasGameStateMessage() && it.gameStateId == request.gameStateId }.gameStateMessage

        /** The source card behind each pending-trigger id, in the order the client lays them out. */
        fun MatchFlowHarness.sourceNames(request: GREToClientMessage): List<String> {
            val objects = frameOf(request).gameObjectsList.associateBy { it.instanceId }
            return request.selectNReq.idsList.map { id -> cardName(checkNotNull(objects[id]) { "trigger $id not projected" }.parentId) }
        }

        /** Instance ids of the stack zone, top first, from the newest frame that describes it. */
        fun MatchFlowHarness.stackTopFirst(): List<Int> =
            allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.zonesList }
                .last { it.zoneId == ZoneIds.STACK }
                .objectInstanceIdsList

        for (first in listOf("Impact Tremors", "Soul Warden")) {
            session(
                "the human orders simultaneous triggers and $first resolves first",
                puzzle = wardenAndTremors,
                fullControl = true,
            ) {
                val req = castSpellUntilSelectNReq("Grizzly Bears")
                val request = latestRequest()
                val frame = frameOf(request)
                val names = sourceNames(request)

                assertSoftly {
                    req.context shouldBe SelectionContext.TriggeredAbility_c799
                    req.optionContext shouldBe OptionContext.Stacking
                    req.listType shouldBe SelectionListType.Dynamic
                    req.idType shouldBe IdType.InstanceId_ab2c
                    req.minSel shouldBe 2
                    req.maxSel shouldBe 2
                    names.sorted() shouldContainExactly listOf("Impact Tremors", "Soul Warden")
                    // Every option resolves to an ability object the client already knows, on the stack.
                    req.idsList.forEach { id ->
                        val obj = frame.gameObjectsList.single { it.instanceId == id }
                        obj.type shouldBe GameObjectType.Ability
                        obj.zoneId shouldBe ZoneIds.STACK
                    }
                    frame.zonesList
                        .single { it.zoneId == ZoneIds.STACK }
                        .objectInstanceIdsList
                        .toSet() shouldBe req.idsList.toSet()
                }

                // Left to right is "Last" .. "First": put the wanted trigger on the right.
                val leftToRight = names.zip(req.idsList).sortedBy { (name, _) -> name == first }.map { (_, id) -> id }
                val other = if (first == "Soul Warden") "Impact Tremors" else "Soul Warden"
                respondToSelectN(leftToRight, OrderingType.OrderAsIndicated)

                // The chosen order is what Forge put on the stack, under the ids the browser showed:
                // `first` on top.
                game().stack.map { it.sourceCard.name } shouldContainExactly listOf(first, other)
                stackTopFirst() shouldContainExactly leftToRight.reversed()

                passPriority()
                if (first == "Impact Tremors") {
                    assertSoftly {
                        ai.life shouldBe 19
                        human.life shouldBe 20
                    }
                } else {
                    assertSoftly {
                        human.life shouldBe 21
                        ai.life shouldBe 20
                    }
                }
                passUntil { game().stack.isEmpty }
                assertSoftly {
                    ai.life shouldBe 19
                    human.life shouldBe 21
                }

                // The ordering frame only shows the pending triggers; each is announced once, when Forge
                // puts it on the stack, so the client never sees a second creation or a stale triggering object.
                val created =
                    allMessages
                        .allAnnotations()
                        .filter { AnnotationType.AbilityInstanceCreated in it.typeList }
                        .flatMap { it.affectedIdsList }
                val announcedInFrame = frame.annotationsList.flatMap { it.affectedIdsList }
                req.idsList.forEach { id ->
                    created.count { it == id } shouldBe 1
                    announcedInFrame shouldNotContain id
                }
            }
        }

        session(
            "answering with the placeholder id keeps Forge's order, the one the browser opens in",
            puzzle = wardenAndTremors,
            fullControl = true,
        ) {
            castSpellUntilSelectNReq("Grizzly Bears")
            val request = latestRequest()
            // Left to right, the last id is "First": the arrangement Done would submit unchanged.
            val browserFirst = sourceNames(request).last()

            // `MDNPlayerPrefs.AutoOrderTriggers` submits exactly this.
            respondToSelectN(listOf(0), OrderingType.OrderArbitraryOnce)

            game()
                .stack
                .first()
                .sourceCard.name shouldBe browserFirst
            passUntil { game().stack.isEmpty }
            assertSoftly {
                ai.life shouldBe 19
                human.life shouldBe 21
            }
        }

        session(
            "leaving the browser as opened resolves the rightmost trigger first",
            puzzle = wardenAndTremors,
            fullControl = true,
        ) {
            castSpellUntilSelectNReq("Grizzly Bears")
            val request = latestRequest()
            val browserFirst = sourceNames(request).last()

            respondToSelectN(request.selectNReq.idsList, OrderingType.OrderAsIndicated)

            game()
                .stack
                .first()
                .sourceCard.name shouldBe browserFirst
        }

        session(
            "a single trigger never prompts for an order",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Soul Warden;Forest;Forest
                humanhand=Grizzly Bears
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val cast =
                after {
                    castSpellByName("Grizzly Bears")
                    passUntil { game().stack.isEmpty }
                }

            cast.expectNoSelectNReq()
            human.life shouldBe 21
        }

        session(
            "the opponent's simultaneous triggers are ordered without asking the human",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Forest;Forest
                humanhand=Grizzly Bears
                humanlibrary=Forest;Forest;Forest
                aibattlefield=Soul Warden;Rampaging Ferocidon
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
        ) {
            val cast =
                after {
                    castSpellByName("Grizzly Bears")
                    passUntil { game().stack.isEmpty }
                }

            cast.expectNoSelectNReq()
            // Ferocidon's ping landed: both of the AI's triggers went on the stack and resolved.
            human.life shouldBe 19
        }

        session(
            "a targeted trigger among the simultaneous ones still asks for its target after the order",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Blood Artist;Zulaport Cutthroat;Mountain
                humanhand=Shock
                humanlibrary=Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            fullControl = true,
        ) {
            // Shock kills Zulaport Cutthroat: its own trigger and Blood Artist's (which targets a player) fire together.
            castSpellByName("Shock") shouldBe true
            selectTargets(listOf(instanceIdOf("Zulaport Cutthroat")))
            passUntil { allMessages.any { it.hasSelectNReq() } }
            val request = latestRequest()
            sourceNames(request).sorted() shouldContainExactly listOf("Blood Artist", "Zulaport Cutthroat")

            respondToSelectN(request.selectNReq.idsList, OrderingType.OrderAsIndicated)
            allMessages.last().hasSelectTargetsReq() shouldBe true
            selectTargets(listOf(OPPONENT_SEAT))
            passUntil { game().stack.isEmpty }

            assertSoftly {
                human.life shouldBe 22
                ai.life shouldBe 18
            }
        }

        session(
            "prowess triggers set off by a cast prompt too, and both resolve",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true

                humanbattlefield=Festival Crasher;Monastery Swiftspear;Mountain;Mountain;Mountain
                humanhand=Lightning Bolt
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
                """.trimIndent(),
        ) {
            castSpellByName("Lightning Bolt")
            selectTargets(listOf(OPPONENT_SEAT))

            val request = latestRequest()
            sourceNames(request).sorted() shouldContainExactly listOf("Festival Crasher", "Monastery Swiftspear")

            respondToSelectN(request.selectNReq.idsList, OrderingType.OrderAsIndicated)
            passUntilResolved()

            ai.life shouldBe 17
        }
    })
