package leyline.bridge.coord

import forge.game.ability.AbilityKey
import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.GatherCounterType
import leyline.bridge.handoff.GatherCountersSourceValue
import leyline.bridge.handoff.GatherCountersWindowInput
import leyline.bridge.handoff.PayCostsPromptSourceInput
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionViewer
import leyline.game.state.ProjectionViewerRole
import leyline.testkit.BoardTest
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.EffectCostType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GatherCountersRuntimeTest :
    BoardTest({
        test("GatherCounters keeps Player bytes and gives Observer projected state only") {
            data class Published(
                val player: List<GREToClientMessage>,
                val observer: List<GREToClientMessage>,
            )

            fun publish(withObserver: Boolean): Published {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Mountain", human, ZoneType.Hand)
                        addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                        addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                    }
                val coordinator = board.bridge.cutCoordinator
                coordinator.registerViewers(
                    buildList {
                        add(ProjectionViewer(SeatId(1), ProjectionViewerRole.Player))
                        if (withObserver) add(ProjectionViewer(SeatId(2), ProjectionViewerRole.Observer))
                    },
                )
                val creatures =
                    board.human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .filter { it.isCreature }
                creatures.forEach {
                    it.addCounterInternal(CounterEnumType.P1P1, 1, board.game.humanPlayer, true, null, AbilityKey.newMap())
                }
                val source = creatures.first()
                val ability = source.spellAbilities.first { it.isActivatedAbility() }
                val window =
                    GatherCountersWindowInput(
                        PayCostsPromptSourceInput.StackAbility(
                            ability.id,
                            ForgeCardId(source.id),
                            ability.rootAbility.definitionId,
                            emptyList(),
                        ),
                        creatures.map { GatherCountersSourceValue(ForgeCardId(it.id), 1) },
                        2,
                        GatherCounterType.P1P1,
                    )
                val finished = CountDownLatch(1)
                Thread {
                    coordinator.oneShotPayCosts.awaitGatherCounters(window, creatures, 3_000)
                    finished.countDown()
                }.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var interaction = coordinator.oneShotPayCosts.current()
                while (interaction == null && System.nanoTime() < deadline) {
                    Thread.onSpinWait()
                    interaction = coordinator.oneShotPayCosts.current()
                }
                val published = checkNotNull(interaction)
                val player = coordinator.drain(SeatId(1)).single()
                val observer = if (withObserver) coordinator.drain(SeatId(2)).single() else emptyList()
                coordinator.acceptSettled(leyline.testkit.cancelActionReq(), published.gameStateId) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                return Published(player, observer)
            }

            val playerOnly = publish(withObserver = false)
            val withObserver = publish(withObserver = true)

            assertSoftly {
                withObserver.player.map { it.toByteArray().toList() } shouldBe
                    playerOnly.player.map { it.toByteArray().toList() }
                withObserver.player.any { it.hasPayCostsReq() } shouldBe true
                withObserver.observer.size shouldBe 1
                withObserver.observer.single().hasGameStateMessage() shouldBe true
                withObserver.observer.none { it.hasPayCostsReq() } shouldBe true
                withObserver.observer
                    .single()
                    .gameStateMessage.zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldBe emptyList()
                withObserver.observer
                    .single()
                    .gameStateMessage.gameObjectsList
                    .none { it.visibility == Visibility.Private } shouldBe true
            }
        }

        test("publishes exact multi-source GatherCounters envelope and retains original handles") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                }
            board.bridge.cutCoordinator.registerViewer(SeatId(1))
            board.bridge.cutCoordinator.drain(SeatId(1))
            val creatures =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isCreature }
            creatures.forEach {
                it.addCounterInternal(CounterEnumType.P1P1, 1, board.game.humanPlayer, true, null, AbilityKey.newMap())
            }
            val source = creatures.first()
            val ability = source.spellAbilities.first { it.isActivatedAbility() }
            val root = ability.rootAbility
            val window =
                GatherCountersWindowInput(
                    promptSource =
                        PayCostsPromptSourceInput.StackAbility(
                            forgeAbilityId = ability.id,
                            sourceForgeCardId = ForgeCardId(source.id),
                            abilityDefinitionId = root.definitionId,
                            targetForgeCardIds =
                                root.targets
                                    ?.targetCards
                                    .orEmpty()
                                    .map { ForgeCardId(it.id) },
                        ),
                    sources = creatures.map { GatherCountersSourceValue(ForgeCardId(it.id), 1) },
                    amountToGather = 2,
                    counterType = GatherCounterType.P1P1,
                )
            val result = AtomicReference<leyline.bridge.handoff.GatherCountersResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    board.bridge.cutCoordinator.oneShotPayCosts
                        .awaitGatherCounters(window, creatures, 3_000),
                )
                finished.countDown()
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published =
                board.bridge.cutCoordinator.oneShotPayCosts
                    .current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published =
                    board.bridge.cutCoordinator.oneShotPayCosts
                        .current()
            }
            val interaction = checkNotNull(published)
            val batch =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .single()
            val payCostsMessage = batch.single { it.hasPayCostsReq() }
            val payCosts = payCostsMessage.payCostsReq
            val gather = payCosts.effectCostReq.gatherReq
            val projected = board.bridge.projectionStateSnapshot()
            val sourceIids = creatures.map { checkNotNull(projected.identities.forgeIdToInstanceId[ForgeCardId(it.id)]).value }
            val stackAbilityIid =
                checkNotNull(
                    projected.identities.forgeIdToInstanceId[FrameIdResolver.triggerStackAbilityForgeId(ability.id)],
                ).value
            assertSoftly {
                batch.map { it.type } shouldContainExactly
                    listOf(GREMessageType.GameStateMessage_695e, GREMessageType.PayCostsReq_695e)
                payCostsMessage.prompt.promptId shouldBe PromptIds.GATHER_COUNTERS
                payCostsMessage.allowCancel shouldBe AllowCancel.Abort
                payCostsMessage.allowUndo shouldBe false
                payCosts.effectCostReq.effectCostType shouldBe EffectCostType.GatherCounters
                payCosts.paymentActions shouldBe
                    wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
                        .getDefaultInstance()
                gather.destinationId shouldBe stackAbilityIid
                gather.sourcesList.map { it.sourceId } shouldContainExactly sourceIids
                gather.sourcesList.map { it.maxAmount } shouldContainExactly listOf(1, 1)
                gather.amountToGather shouldBe 2
                payCostsMessage.prompt.parametersList
                    .single { it.parameterName == "CardId" }
                    .numberValue shouldBe gather.destinationId
            }
            assertSoftly {
                board.bridge.cutCoordinator.acceptSettled(
                    leyline.testkit.gatherCountersResp(sourceIids.map { it to 1 }),
                    interaction.gameStateId,
                ) shouldBe
                    true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().payments.map { it.amount } shouldContainExactly listOf(1, 1)
                result.get().payments.map { it.handle } shouldContainExactly creatures
                result
                    .get()
                    .payments
                    .zip(creatures)
                    .all { (payment, card) -> payment.handle === card } shouldBe true
            }
        }

        test("rejects duplicate, unknown, over-capacity, and wrong-total GatherCounters responses") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                }
            board.bridge.cutCoordinator.registerViewer(SeatId(1))
            board.bridge.cutCoordinator.drain(SeatId(1))
            val creatures =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isCreature }
            val source = creatures.first()
            val ability = source.spellAbilities.first { it.isActivatedAbility() }
            val window =
                GatherCountersWindowInput(
                    PayCostsPromptSourceInput.StackAbility(
                        ability.id,
                        ForgeCardId(source.id),
                        ability.rootAbility.definitionId,
                        emptyList(),
                    ),
                    creatures.map { GatherCountersSourceValue(ForgeCardId(it.id), 1) },
                    2,
                    GatherCounterType.P1P1,
                )
            val finished = CountDownLatch(1)
            Thread {
                board.bridge.cutCoordinator.oneShotPayCosts
                    .awaitGatherCounters(window, creatures, 3_000)
                finished.countDown()
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published =
                board.bridge.cutCoordinator.oneShotPayCosts
                    .current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published =
                    board.bridge.cutCoordinator.oneShotPayCosts
                        .current()
            }
            val interaction = checkNotNull(published)
            val ids =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .single()
                    .single {
                        it.hasPayCostsReq()
                    }.payCostsReq.effectCostReq.gatherReq.sourcesList
                    .map { it.sourceId }
            val coordinator = board.bridge.cutCoordinator
            assertSoftly {
                coordinator.acceptSettled(
                    leyline.testkit.effectCostResp(listOf(ids[0], ids[1])),
                    interaction.gameStateId,
                ) shouldBe false
                coordinator.acceptSettled(
                    leyline.testkit.gatherCountersResp(listOf(ids[0] to 1, ids[1] to 1)),
                    interaction.gameStateId + 1,
                ) shouldBe false
                coordinator.acceptSettled(
                    leyline.testkit.gatherCountersResp(listOf(ids[0] to 1, ids[0] to 1)),
                    interaction.gameStateId,
                ) shouldBe false
                coordinator.acceptSettled(
                    leyline.testkit.gatherCountersResp(listOf(Int.MAX_VALUE to 2)),
                    interaction.gameStateId,
                ) shouldBe false
                coordinator.acceptSettled(
                    leyline.testkit.gatherCountersResp(listOf(ids[0] to 2)),
                    interaction.gameStateId,
                ) shouldBe false
                coordinator.acceptSettled(
                    leyline.testkit.gatherCountersResp(listOf(ids[0] to 1)),
                    interaction.gameStateId,
                ) shouldBe false
                coordinator.acceptSettled(leyline.testkit.cancelActionReq(), interaction.gameStateId) shouldBe true
                finished.await(3, TimeUnit.SECONDS) shouldBe true
            }
        }

        test("timeout retires GatherCounters with bounded first-fit and rejects a late response") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                }
            board.bridge.cutCoordinator.registerViewer(SeatId(1))
            board.bridge.cutCoordinator.drain(SeatId(1))
            val creatures =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isCreature }
            val source = creatures.first()
            val ability = source.spellAbilities.first { it.isActivatedAbility() }
            val window =
                GatherCountersWindowInput(
                    PayCostsPromptSourceInput.StackAbility(
                        ability.id,
                        ForgeCardId(source.id),
                        ability.rootAbility.definitionId,
                        emptyList(),
                    ),
                    creatures.map { GatherCountersSourceValue(ForgeCardId(it.id), 1) },
                    2,
                    GatherCounterType.P1P1,
                )
            val result = AtomicReference<leyline.bridge.handoff.GatherCountersResult>()
            val finished = CountDownLatch(1)
            Thread {
                result.set(
                    board.bridge.cutCoordinator.oneShotPayCosts
                        .awaitGatherCounters(window, creatures, 25),
                )
                finished.countDown()
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published =
                board.bridge.cutCoordinator.oneShotPayCosts
                    .current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published =
                    board.bridge.cutCoordinator.oneShotPayCosts
                        .current()
            }
            val interaction = checkNotNull(published)
            val ids =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .single()
                    .single { it.hasPayCostsReq() }
                    .payCostsReq
                    .effectCostReq
                    .gatherReq
                    .sourcesList
                    .map { it.sourceId }
            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().timedOut shouldBe true
                result.get().payments.map { it.amount } shouldContainExactly listOf(1, 1)
                result.get().payments.map { it.handle } shouldContainExactly creatures
                board.bridge.cutCoordinator.oneShotPayCosts
                    .current() shouldBe null
                board.bridge.cutCoordinator.acceptSettled(
                    leyline.testkit.gatherCountersResp(ids.map { it to 1 }),
                    interaction.gameStateId,
                ) shouldBe
                    false
            }
        }

        test("response wins while GatherCounters timeout claim is latched") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                    addCard("Hopeful Initiate", human, ZoneType.Battlefield)
                }
            board.bridge.cutCoordinator.registerViewer(SeatId(1))
            board.bridge.cutCoordinator.drain(SeatId(1))
            val creatures =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isCreature }
            val source = creatures.first()
            val ability = source.spellAbilities.first { it.isActivatedAbility() }
            val window =
                GatherCountersWindowInput(
                    PayCostsPromptSourceInput.StackAbility(
                        ability.id,
                        ForgeCardId(source.id),
                        ability.rootAbility.definitionId,
                        emptyList(),
                    ),
                    creatures.map { GatherCountersSourceValue(ForgeCardId(it.id), 1) },
                    2,
                    GatherCounterType.P1P1,
                )
            val result = AtomicReference<leyline.bridge.handoff.GatherCountersResult>()
            val finished = CountDownLatch(1)
            val timeoutClaim = CountDownLatch(1)
            val releaseTimeout = CountDownLatch(1)
            board.bridge.cutCoordinator.prompts.settled.beforeTimeoutClaim = {
                timeoutClaim.countDown()
                check(releaseTimeout.await(3, TimeUnit.SECONDS))
            }
            Thread {
                result.set(
                    board.bridge.cutCoordinator.oneShotPayCosts
                        .awaitGatherCounters(window, creatures, 25),
                )
                finished.countDown()
            }.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var published =
                board.bridge.cutCoordinator.oneShotPayCosts
                    .current()
            while (published == null && System.nanoTime() < deadline) {
                Thread.onSpinWait()
                published =
                    board.bridge.cutCoordinator.oneShotPayCosts
                        .current()
            }
            val interaction = checkNotNull(published)
            val ids =
                board.bridge.cutCoordinator
                    .drain(SeatId(1))
                    .single()
                    .single { it.hasPayCostsReq() }
                    .payCostsReq
                    .effectCostReq
                    .gatherReq
                    .sourcesList
                    .map { it.sourceId }
            timeoutClaim.await(3, TimeUnit.SECONDS) shouldBe true
            board.bridge.cutCoordinator.acceptSettled(
                leyline.testkit.gatherCountersResp(ids.map { it to 1 }),
                interaction.gameStateId,
            ) shouldBe
                true
            releaseTimeout.countDown()

            assertSoftly {
                finished.await(3, TimeUnit.SECONDS) shouldBe true
                result.get().timedOut shouldBe false
                result.get().payments.map { it.amount } shouldContainExactly listOf(1, 1)
                result.get().payments.map { it.handle } shouldContainExactly creatures
                board.bridge.cutCoordinator.oneShotPayCosts
                    .current() shouldBe null
                board.bridge.cutCoordinator.acceptSettled(
                    leyline.testkit.gatherCountersResp(ids.map { it to 1 }),
                    interaction.gameStateId,
                ) shouldBe
                    false
            }
            board.bridge.cutCoordinator.prompts.settled.beforeTimeoutClaim = null
        }
    })
