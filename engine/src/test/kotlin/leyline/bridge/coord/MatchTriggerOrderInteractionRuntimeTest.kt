package leyline.bridge.coord

import forge.game.spellability.SpellAbility
import forge.game.trigger.WrappedAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PublishedTriggerOrderInteraction
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.TriggerOrderInteractionResult
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.testkit.Board
import leyline.testkit.BoardTest
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.ClientMessageType
import wotc.mtgo.gre.external.messaging.Messages.ClientToGREMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.OrderingType
import wotc.mtgo.gre.external.messaging.Messages.SelectNResp
import wotc.mtgo.gre.external.messaging.Messages.SelectReplacementResp
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MatchTriggerOrderInteractionRuntimeTest :
    BoardTest({
        val puzzle =
            """
            [metadata]
            Name:trigger order runtime
            Goal:Win
            Turns:1

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Grizzly Bears
            humanbattlefield=Soul Warden;Impact Tremors
            ailibrary=Forest
            """.trimIndent()

        fun triggers(board: Board): List<SpellAbility> =
            board.human
                .getZone(ZoneType.Battlefield)
                .cards
                .sortedBy { it.id }
                .map { card ->
                    val trigger = card.triggers.single()
                    WrappedAbility(trigger, trigger.ensureAbility(), board.human).also { it.activatingPlayer = board.human }
                }

        fun request(abilities: List<SpellAbility>): PromptRequest =
            PromptRequest(
                promptType = "order_triggers",
                message = "Choose the order to put triggered abilities on the stack",
                options = abilities.map { it.toString() },
                min = abilities.size,
                max = abilities.size,
                defaultIndex = 0,
                route = PromptRouteResolver.resolve(PromptSemantic.OrderTriggers),
            )

        fun awaitPublished(coordinator: MatchCutCoordinator): PublishedTriggerOrderInteraction {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published = coordinator.triggerOrder.current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published = coordinator.triggerOrder.current()
            }
            return checkNotNull(published)
        }

        test("captures each simultaneous trigger of the runtime seat in Forge's order") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            val all = triggers(board)
            val initial = TriggerOrderWindowCapture(coordinator).initial(request(all), all)
            initial.shouldNotBeNull()
            assertSoftly {
                initial.value.options.map { it.originalOptionIndex } shouldBe listOf(0, 1)
                initial.value.options.map { it.forgeAbilityId } shouldBe all.map { it.id }
                initial.value.options.map { it.sourceCardGrpId } shouldBe
                    all.map { board.bridge.resolveGrpId(checkNotNull(it.hostCard)) }
                initial.value.options.forEach {
                    it.abilityGrpId shouldBe
                        board.bridge.resolveAbilityIdentity(all[it.originalOptionIndex].hostCard, all[it.originalOptionIndex])?.abilityGrpId
                    it.controllerSeatId shouldBe SeatId(1)
                }
                (initial.handlesByOption[1] === all[1]) shouldBe true
            }
        }

        test("capture leaves Forge's order alone for shapes the trigger browser does not cover") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            val all = triggers(board)
            val spell =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
                    .firstSpellAbility
            val cases =
                listOf(
                    request(all).copy(route = ResolvedPromptRoute.Search(PromptSemantic.Search)) to all,
                    request(all).copy(options = listOf("one")) to all,
                    request(all.take(1)) to all.take(1),
                    request(listOf(all[0], spell)) to listOf(all[0], spell),
                    request(all) to listOf(all[0], all[0]),
                )

            cases.forEach { (candidateRequest, candidates) ->
                TriggerOrderWindowCapture(coordinator).initial(candidateRequest, candidates).shouldBeNull()
            }
            // Triggers the runtime seat does not control are not its decision.
            TriggerOrderWindowCapture(coordinator, SeatId(2)).initial(request(all), all).shouldBeNull()
            coordinator.triggerOrder.current().shouldBeNull()
        }

        test("publishes the trigger browser request and admits only a permutation of its ids") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = triggers(board)
            val result = AtomicReference<TriggerOrderInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.triggerOrder.awaitTriggerOrder(request(all), all, 3_000))
                finished.countDown()
            }.start()

            val published = awaitPublished(coordinator)
            val batch = coordinator.drain(SeatId(1)).flatten()
            val req = batch.single { it.hasSelectNReq() }
            val select = req.selectNReq
            val frame = batch.first { it.hasGameStateMessage() }.gameStateMessage
            val baseline = board.bridge.projectionStateSnapshot()
            val acceptedBefore = board.bridge.responseAcceptance.responsesAccepted()

            fun response(
                ids: List<Int>,
                useArbitrary: OrderingType? = null,
                type: ClientMessageType = ClientMessageType.SelectNresp,
                gameStateId: Int = published.gameStateId,
                respId: Int = req.msgId,
            ): ClientToGREMessage =
                ClientToGREMessage
                    .newBuilder()
                    .setType(type)
                    .setGameStateId(gameStateId)
                    .setRespId(respId)
                    .apply {
                        if (type == ClientMessageType.SelectNresp) {
                            setSelectNResp(
                                SelectNResp
                                    .newBuilder()
                                    .addAllIds(ids)
                                    .apply { useArbitrary?.let(::setUseArbitrary) },
                            )
                        } else {
                            setSelectReplacementResp(SelectReplacementResp.getDefaultInstance())
                        }
                    }.build()

            fun reject(message: ClientToGREMessage) {
                coordinator.acceptSettled(message, message.gameStateId, message.respId) shouldBe false
            }

            val ids = select.idsList
            assertSoftly {
                req.type shouldBe GREMessageType.SelectNreq
                req.gameStateId shouldBe published.gameStateId
                req.prompt.promptId shouldBe PromptIds.SELECT_N
                req.allowCancel shouldBe AllowCancel.No_a526
                frame.pendingMessageCount shouldBe 1
                select.context shouldBe SelectionContext.TriggeredAbility_c799
                select.optionContext shouldBe OptionContext.Stacking
                select.listType shouldBe SelectionListType.Dynamic
                select.idType shouldBe IdType.InstanceId_ab2c
                select.validationType shouldBe SelectionValidationType.NonRepeatable
                select.minSel shouldBe 2
                select.maxSel shouldBe 2
                ids shouldHaveSize 2
                // "Last" to "First": the reverse of Forge's resolve-first order.
                ids.map { id -> frame.gameObjectsList.single { it.instanceId == id }.parentId } shouldBe
                    all.reversed().map {
                        board.bridge
                            .peekInstanceId(
                                leyline.bridge.types.ForgeCardId(checkNotNull(it.hostCard).id),
                            )!!
                            .value
                    }
                ids.forEach { id ->
                    frame.gameObjectsList.single { it.instanceId == id }.type shouldBe GameObjectType.Ability
                }

                reject(response(ids, type = ClientMessageType.SelectReplacementResp_097b))
                reject(response(emptyList()))
                reject(response(ids.take(1)))
                reject(response(listOf(ids[0], ids[0])))
                reject(response(listOf(ids[0], ids[1] + 1000)))
                reject(response(ids + ids[0]))
                reject(response(ids, gameStateId = published.gameStateId + 1))
                reject(response(ids, respId = req.msgId + 1))
                board.bridge.projectionStateSnapshot() shouldBe baseline
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore
                coordinator.triggerOrder.current().shouldNotBeNull()
                finished.await(100, TimeUnit.MILLISECONDS) shouldBe false

                // Left to right as submitted: the first trigger is "Last", the second "First".
                coordinator.acceptSettled(response(ids, OrderingType.OrderAsIndicated), published.gameStateId, req.msgId) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().optionIndices shouldContainExactly listOf(0, 1)
                (result.get().handles[0] === all[0]) shouldBe true
                result.get().timedOut shouldBe false
                coordinator.triggerOrder.current().shouldBeNull()
                board.bridge.responseAcceptance.responsesAccepted() shouldBe acceptedBefore + 1
            }
        }

        /** Publishes a window over the two triggers and answers it with [answer] built from the published ids. */
        fun answered(answer: (List<Int>) -> SelectNResp): Pair<List<SpellAbility>, TriggerOrderInteractionResult> {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = triggers(board)
            val result = AtomicReference<TriggerOrderInteractionResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(coordinator.triggerOrder.awaitTriggerOrder(request(all), all, 3_000))
                finished.countDown()
            }.start()
            val published = awaitPublished(coordinator)
            val req = coordinator.drain(SeatId(1)).flatten().single { it.hasSelectNReq() }
            val message =
                ClientToGREMessage
                    .newBuilder()
                    .setType(ClientMessageType.SelectNresp)
                    .setSelectNResp(answer(req.selectNReq.idsList))
                    .build()
            coordinator.acceptSettled(message, published.gameStateId, req.msgId) shouldBe true
            finished.await(3, TimeUnit.SECONDS) shouldBe true
            return all to result.get()
        }

        test("the browser's arrangement decides the order: the rightmost trigger resolves first") {
            // Dragging the two around: "Last" now holds what Forge would resolve first.
            val (all, result) =
                answered { ids ->
                    SelectNResp
                        .newBuilder()
                        .addAllIds(ids.reversed())
                        .setUseArbitrary(OrderingType.OrderAsIndicated)
                        .build()
                }

            result.optionIndices shouldContainExactly listOf(1, 0)
            result.handles.map { it.id } shouldContainExactly listOf(all[1].id, all[0].id)
        }

        test("the auto-order placeholder answer keeps Forge's order") {
            val (all, result) =
                answered {
                    SelectNResp
                        .newBuilder()
                        .addIds(0)
                        .setUseArbitrary(OrderingType.OrderArbitraryOnce)
                        .build()
                }

            result.optionIndices shouldContainExactly listOf(0, 1)
            result.handles.map { it.id } shouldContainExactly all.map { it.id }
        }

        test("timeout returns Forge's order and retires the slot") {
            val board = startPuzzleAtMain1(puzzle)
            val coordinator = board.bridge.cutCoordinator
            coordinator.drain(SeatId(1))
            val all = triggers(board)
            val bridge =
                InteractivePromptBridge(timeoutMs = 25, strict = false).also {
                    it.runtimeBindings = coordinator.prompts.bindings(SeatId(1))
                }
            val result = bridge.requestTriggerOrder(request(all), all)
            result.shouldNotBeNull()
            assertSoftly {
                result.optionIndices shouldContainExactly listOf(0, 1)
                result.timedOut shouldBe true
                coordinator.triggerOrder.current().shouldBeNull()
            }
        }
    })
