package leyline.game.mapping

import forge.game.phase.PhaseType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.bundle.GsmFrame
import leyline.game.codes.DetailKeys
import leyline.game.data.CardProtoBuilder
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.event.ZoneMove
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.DungeonStateSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.PhaseSnapshot
import leyline.game.snapshot.SeatSnapshot
import leyline.game.snapshot.StackEntry
import leyline.game.snapshot.StackSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.AbilityExhaustionFacts
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PendingSubmittedTargets
import leyline.game.state.PersistentFeedFacts
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionViewerRole
import leyline.game.state.PromptProjectionFacts
import leyline.game.state.ViewerProjectionCursor
import leyline.testkit.ClientAccumulator
import leyline.testkit.greMessage
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class StateProjectionCompilerTest :
    FunSpec({
        test("a publicly chosen card name produces one portrait event, not repeated refresh events") {
            val fid = ForgeCardId(501)
            val card =
                CardSnapshot(
                    fid,
                    "Naming permanent",
                    901,
                    SeatId(2),
                    SeatId(2),
                    isOnBattlefield = true,
                    chosenCardNameTitleIds = listOf(1234),
                )
            val before = GsmSnapshot.forTest(matchId = "compiler", gameStateId = 1)
            val after =
                GsmSnapshot.forTest(
                    matchId = "compiler",
                    gameStateId = 2,
                    objects = mapOf(fid to card),
                    zones =
                        mapOf(
                            ZoneIds.BATTLEFIELD to
                                ZoneSnapshot(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, null, Visibility.Public, listOf(fid)),
                        ),
                )
            val emitted =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(after, before),
                    ProjectionState.initial(),
                )
            val names = emitted.gsm.annotationsList.filter { ann -> ann.detailsList.any { it.key == "Choice_Value" } }
            names.size shouldBe 1
            names.single().affectedIdsList shouldBe listOf(2)
            names
                .single()
                .detailsList
                .single { it.key == "Choice_Value" }
                .valueInt32List shouldBe listOf(1234)
            val refreshed =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(after, after),
                    emitted.transition.nextState,
                )
            refreshed.gsm.annotationsList.any { ann -> ann.detailsList.any { it.key == "Choice_Value" } } shouldBe false
        }
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("both players retain dungeon state through updates without another venture") {
            val dungeons =
                mapOf(
                    SeatId(1) to DungeonStateSnapshot(78768, 420, 146074),
                    SeatId(2) to DungeonStateSnapshot(78770, 421, 146089),
                )
            val environment = compilerEnvironment()
            val client = ClientAccumulator()
            var projection = ProjectionState.initial()
            var previous: GsmSnapshot? = null
            var initialIds = emptyList<Int>()
            listOf(PhaseType.MAIN1, PhaseType.COMBAT_BEGIN, PhaseType.MAIN2, PhaseType.END_OF_TURN).forEachIndexed { index, phase ->
                val snapshot =
                    GsmSnapshot.forTest(
                        gameStateId = index + 1,
                        phase = phaseSnapshot(phase),
                        dungeonStates = dungeons,
                    )
                val result = StateProjectionCompiler.compileOneViewer(environment, compilerInput(snapshot, previous), projection)
                client.process(greMessage(msgId = index + 1, gsm = result.gsm))
                val status = client.persistentAnnotations.values.filter { AnnotationType.DungeonStatus in it.typeList }
                assertSoftly {
                    status.map { it.affectorId } shouldBe listOf(1, 2)
                    status.map {
                        it.detailsList
                            .single { it.key == DetailKeys.CURRENT_DUNGEON }
                            .valueInt32List
                            .single()
                    } shouldBe
                        listOf(78768, 78770)
                    status.map {
                        it.detailsList
                            .single { it.key == DetailKeys.CURRENT_ROOM }
                            .valueInt32List
                            .single()
                    } shouldBe
                        listOf(146074, 146089)
                    result.gsm.annotationsList.any { AnnotationType.DungeonStatus in it.typeList } shouldBe false
                }
                if (index == 0) initialIds = status.map { it.id } else status.map { it.id } shouldBe initialIds
                previous = snapshot
                projection = result.transition.nextState
            }
        }

        test("persistent dungeon state records completion and a fresh entry without stale rooms") {
            val environment = compilerEnvironment()
            val client = ClientAccumulator()
            var projection = ProjectionState.initial()
            var previous: GsmSnapshot? = null
            val states =
                listOf(
                    DungeonStateSnapshot(78769, 420, 146086),
                    DungeonStateSnapshot(completedDungeonGrpIds = listOf(78769)),
                    DungeonStateSnapshot(completedDungeonGrpIds = listOf(78769)),
                    DungeonStateSnapshot(78768, 422, 146073, listOf(78769)),
                )
            states.forEachIndexed { index, state ->
                val snapshot = GsmSnapshot.forTest(gameStateId = index + 1, dungeonStates = mapOf(SeatId(1) to state))
                val result = StateProjectionCompiler.compileOneViewer(environment, compilerInput(snapshot, previous), projection)
                client.process(greMessage(msgId = index + 1, gsm = result.gsm))
                val status = client.persistentAnnotations.values.single { AnnotationType.DungeonStatus in it.typeList }
                assertSoftly {
                    status.affectorId shouldBe 1
                    status.detailsList.single { it.key == DetailKeys.CURRENT_DUNGEON }.valueInt32List shouldBe
                        listOf(state.currentDungeonGrpId)
                    status.detailsList.single { it.key == DetailKeys.CURRENT_DUNGEON_ZCID }.valueInt32List shouldBe
                        listOf(state.currentDungeonInstanceId)
                    status.detailsList.single { it.key == DetailKeys.CURRENT_ROOM }.valueInt32List shouldBe
                        listOf(state.currentRoomGrpId)
                    status.detailsList
                        .firstOrNull { it.key == DetailKeys.ALL_DUNGEONS_COMPLETED }
                        ?.valueInt32List
                        .orEmpty() shouldBe
                        state.completedDungeonGrpIds
                    result.transition.nextState.persistentAnnotations.activeAnnotations.values
                        .count { AnnotationType.DungeonStatus in it.typeList } shouldBe 1
                }
                previous = snapshot
                projection = result.transition.nextState
            }
        }

        test("unchanged player totals still clear published mulligan state before draws") {
            val previous = drawSnapshot(1, ForgeCardId(10), inLibrary = true)
            val current = drawSnapshot(2, ForgeCardId(10), inLibrary = false)
            val published =
                wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
                    .newBuilder()
                    .addPlayers(
                        PlayerMapper
                            .buildFromSnapshot(previous, 1)
                            .toBuilder()
                            .setPendingMessageType(wotc.mtgo.gre.external.messaging.Messages.ClientMessageType.MulliganResp_097b),
                    ).addPlayers(PlayerMapper.buildFromSnapshot(previous, 2))
                    .build()
            val baseline =
                StateProjectionCompiler
                    .compileOneViewer(
                        compilerEnvironment(),
                        compilerInput(previous),
                        ProjectionState.initial(),
                    ).transition.nextState
            val prior =
                baseline.copy(
                    viewerCursors =
                        mapOf(
                            SeatId(1) to ViewerProjectionCursor(previousSnapshot = previous, fullState = published),
                        ),
                )
            val result = StateProjectionCompiler.compileOneViewer(compilerEnvironment(), compilerInput(current, previous), prior)
            result.gsm.playersCount shouldBe 2
            result.gsm.playersList
                .first()
                .pendingMessageType.number shouldBe 0
        }

        test("public zone permission rows survive a prompt and disappear when permission expires") {
            val previous = GsmSnapshot.forTest(matchId = "compiler", gameStateId = 1)
            val current =
                GsmSnapshot.forTest(
                    matchId = "compiler",
                    gameStateId = 2,
                    objects =
                        (1..2).associate { seat ->
                            ForgeCardId(seat) to CardSnapshot(ForgeCardId(seat), "Permitted Card", 9000 + seat, SeatId(seat), SeatId(seat))
                        },
                )
            val editor = ProjectionState.initial().editor()
            val display =
                (1..2).map { seat ->
                    wotc.mtgo.gre.external.messaging.Messages.ActionInfo
                        .newBuilder()
                        .setSeatId(seat)
                        .setAction(
                            Action
                                .newBuilder()
                                .setActionType(ActionType.Cast)
                                .setInstanceId(editor.identities.getOrAlloc(ForgeCardId(seat)).value),
                        ).build()
                }
            val input = compilerInput(current, previous).copy(zoneCastActions = display)
            val result = StateProjectionCompiler.compileOneViewer(compilerEnvironment(), input, editor.freeze())
            result.gsm.actionsList shouldBe display
            result.gsm.pendingMessageCount shouldBe 0
            val next =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    input.copy(zoneCastActions = emptyList()),
                    result.transition.nextState,
                )
            next.gsm.actionsCount shouldBe 0
        }

        test("face-down exile suppresses identity for both players including the owner") {
            val fid = ForgeCardId(71)
            val snapshot =
                GsmSnapshot.forTest(
                    objects =
                        mapOf(
                            fid to
                                CardSnapshot(fid, "Secret land", 9001, SeatId(1), SeatId(1), isFaceDownExile = true),
                        ),
                    zones = mapOf(ZoneIds.EXILE to ZoneSnapshot(ZoneIds.EXILE, ZoneType.Exile, null, Visibility.Public, listOf(fid))),
                )
            for (seat in listOf(1, 2)) {
                val result =
                    StateProjectionCompiler.compileOneViewer(
                        compilerEnvironment(),
                        compilerInput(snapshot).copy(viewingSeatId = seat),
                        ProjectionState.initial(),
                    )
                val card = result.gsm.gameObjectsList.single { it.zoneId == ZoneIds.EXILE }
                card.visibility shouldBe Visibility.Hidden
                card.grpId shouldBe 0
                card.name shouldBe 0
                card.cardTypesCount shouldBe 0
            }
        }

        test("face-down zone permissions remain private to the owning player") {
            val cardId = ForgeCardId(40)
            val snap =
                GsmSnapshot.forTest(
                    objects =
                        mapOf(
                            cardId to CardSnapshot(cardId, "Private Exile", 9001, SeatId(2), SeatId(2), isForetold = true),
                        ),
                )
            val editor = ProjectionState.initial().editor()
            val iid = editor.identities.getOrAlloc(cardId).value
            val info =
                wotc.mtgo.gre.external.messaging.Messages.ActionInfo
                    .newBuilder()
                    .setSeatId(2)
                    .setAction(Action.newBuilder().setActionType(ActionType.Cast).setInstanceId(iid))
                    .build()
            val prior = editor.freeze()

            fun actionsFor(
                seat: Int,
                role: ProjectionViewerRole,
            ) = StateProjectionCompiler
                .compileViewers(
                    compilerEnvironment(),
                    prior,
                    listOf(
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(snap).copy(viewingSeatId = seat, zoneCastActions = listOf(info)),
                            role = role,
                        ),
                    ),
                ).viewers
                .single()
                .result.gsm.actionsList
            actionsFor(1, ProjectionViewerRole.Player) shouldBe emptyList()
            actionsFor(2, ProjectionViewerRole.Player) shouldBe listOf(info)
            actionsFor(2, ProjectionViewerRole.Observer) shouldBe emptyList()
        }

        test("viewer intent defensively freezes ordered supplements and order values") {
            val supplementValues = mutableListOf<ProjectionSupplement>(ProjectionSupplement.NewTurnStarted)
            val candidates = mutableListOf(ForgeCardId(10))
            val moveCards = mutableListOf(ForgeCardId(10))
            val intent =
                ViewerProjectionIntent.of(
                    supplements = supplementValues,
                    orderPrompt =
                        OrderPromptProjection.of(
                            candidates,
                            move = OrderZoneMoveFact.of(SeatId(1), moveCards, putOnTop = true),
                        ),
                )

            supplementValues += ProjectionSupplement.ReserveTriggeredAbility(7)
            candidates += ForgeCardId(11)
            moveCards += ForgeCardId(11)

            assertSoftly {
                intent.supplements shouldContainExactly listOf(ProjectionSupplement.NewTurnStarted)
                intent.orderPrompt?.candidateForgeIds shouldContainExactly listOf(ForgeCardId(10))
                intent.orderPrompt?.move?.forgeCardIds shouldContainExactly listOf(ForgeCardId(10))
            }
        }

        test("phase changes repair a missing client phase event from the authoritative snapshots") {
            val previous =
                GsmSnapshot.forTest(
                    matchId = "compiler",
                    gameStateId = 4,
                    phase = phaseSnapshot(PhaseType.MAIN1),
                )
            val current =
                GsmSnapshot.forTest(
                    matchId = "compiler",
                    gameStateId = 5,
                    phase = phaseSnapshot(PhaseType.COMBAT_BEGIN),
                )

            val result =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(current, previous),
                    ProjectionState.initial(),
                )
            val phaseAnnotations =
                result.gsm.annotationsList.filter { AnnotationType.PhaseOrStepModified in it.typeList }

            assertSoftly {
                result.gsm.turnInfo.phase shouldBe wotc.mtgo.gre.external.messaging.Messages.Phase.Combat_a549
                result.gsm.turnInfo.step shouldBe wotc.mtgo.gre.external.messaging.Messages.Step.BeginCombat_a2cb
                phaseAnnotations.size shouldBe 1
                phaseAnnotations.single().affectedIdsList shouldContainExactly listOf(1)
                phaseAnnotations
                    .single()
                    .detailsList
                    .first { it.key == "phase" }
                    .valueInt32List shouldContainExactly listOf(3)
                phaseAnnotations
                    .single()
                    .detailsList
                    .first { it.key == "step" }
                    .valueInt32List shouldContainExactly listOf(4)
            }
        }

        test("same-frame leave and return emits both touched zones in the diff") {
            val cardId = ForgeCardId(42)
            val card = CardSnapshot(cardId, "Transforming permanent", 95997, SeatId(1), SeatId(1))
            val zones =
                linkedMapOf(
                    ZoneIds.BATTLEFIELD to
                        ZoneSnapshot(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, null, Visibility.Public, listOf(cardId)),
                    ZoneIds.EXILE to
                        ZoneSnapshot(ZoneIds.EXILE, ZoneType.Exile, null, Visibility.Public, emptyList()),
                    ZoneIds.LIMBO to
                        ZoneSnapshot(ZoneIds.LIMBO, ZoneType.Limbo, null, Visibility.Public, emptyList()),
                )
            val previous = GsmSnapshot.forTest(matchId = "compiler", gameStateId = 4, objects = mapOf(cardId to card), zones = zones)
            val first =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(previous),
                    ProjectionState.initial(),
                )
            val current = previous.withGameStateId(5)
            val moves =
                FrameEventLog(
                    emptyList(),
                    listOf(
                        ZoneMove(0, cardId, Zone.Battlefield, Zone.Exile, cause = null),
                        ZoneMove(1, cardId, Zone.Exile, Zone.Battlefield, cause = null),
                    ),
                )
            val result =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(current, previous, moves),
                    first.transition.nextState,
                )

            val battlefield = result.gsm.zonesList.single { it.zoneId == ZoneIds.BATTLEFIELD }
            val exile = result.gsm.zonesList.single { it.zoneId == ZoneIds.EXILE }
            val finalIid =
                result.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(cardId)
                    .value
            battlefield.objectInstanceIdsList shouldContainExactly listOf(finalIid)
            exile.objectInstanceIdsList shouldContainExactly emptyList()
            result.gsm.annotationsList.count { AnnotationType.ZoneTransfer_af5a in it.typeList } shouldBe 2
        }

        test("phase reconciliation does not duplicate an event-backed current phase") {
            val previous =
                GsmSnapshot.forTest(
                    matchId = "compiler",
                    gameStateId = 4,
                    phase = phaseSnapshot(PhaseType.MAIN1),
                )
            val current =
                GsmSnapshot.forTest(
                    matchId = "compiler",
                    gameStateId = 5,
                    phase = phaseSnapshot(PhaseType.COMBAT_BEGIN),
                )
            val events =
                FrameEventLog(
                    listOf(GameEvent.PhaseChanged(SeatId(1), phase = 3, step = 4)),
                )

            val result =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(current, previous, events),
                    ProjectionState.initial(),
                )

            result.gsm.annotationsList.count { AnnotationType.PhaseOrStepModified in it.typeList } shouldBe 1
        }

        test("phase annotations are not repeated across stable frames per viewer") {
            val phase = phaseSnapshot(PhaseType.MAIN1)
            val firstSnapshot =
                GsmSnapshot.forTest(
                    matchId = "compiler",
                    gameStateId = 4,
                    phase = phase,
                )
            val phaseFrame = GsmFrame.from(firstSnapshot)
            val repeatedEvent =
                FrameEventLog(
                    listOf(GameEvent.PhaseChanged(SeatId(1), phaseFrame.phase.number, phaseFrame.step.number)),
                )
            val first =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(firstSnapshot, events = repeatedEvent),
                    ProjectionState.initial(),
                )
            val stableSnapshot = firstSnapshot.withGameStateId(5)
            val stable =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(stableSnapshot, first.projectionSnapshot, repeatedEvent),
                    first.transition.nextState,
                )

            assertSoftly {
                first.gsm.annotationsList.count { AnnotationType.PhaseOrStepModified in it.typeList } shouldBe 1
                stable.gsm.annotationsList.count { AnnotationType.PhaseOrStepModified in it.typeList } shouldBe 0
                stable.transition.nextState.viewerCursors
                    .getValue(SeatId(1))
                    .lastEmittedPhase shouldBe
                    first.transition.nextState.viewerCursors
                        .getValue(SeatId(1))
                        .lastEmittedPhase
            }
        }

        test("stable-frame phase suppression is isolated per viewer cursor") {
            val snapshot =
                GsmSnapshot.forTest(
                    matchId = "compiler",
                    gameStateId = 4,
                    phase = phaseSnapshot(PhaseType.MAIN1),
                )
            val frame = GsmFrame.from(snapshot)
            val events =
                FrameEventLog(
                    listOf(GameEvent.PhaseChanged(SeatId(1), frame.phase.number, frame.step.number)),
                )
            val first =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(snapshot, events = events),
                    ProjectionState.initial(),
                )
            val stable = snapshot.withGameStateId(5)
            val second =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    first.transition.nextState,
                    listOf(
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(stable, first.projectionSnapshot, events).copy(viewingSeatId = 1),
                        ),
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(stable, first.projectionSnapshot, events).copy(viewingSeatId = 2),
                            role = ProjectionViewerRole.Observer,
                        ),
                    ),
                )

            assertSoftly {
                second.viewers[0]
                    .result.gsm.annotationsList
                    .count { AnnotationType.PhaseOrStepModified in it.typeList } shouldBe 0
                second.viewers[1]
                    .result.gsm.annotationsList
                    .count { AnnotationType.PhaseOrStepModified in it.typeList } shouldBe 1
            }
        }

        test("authoritative phase advance still repairs a missing event after a stable frame") {
            val firstSnapshot =
                GsmSnapshot.forTest(
                    matchId = "compiler",
                    gameStateId = 4,
                    phase = phaseSnapshot(PhaseType.MAIN1),
                )
            val firstFrame = GsmFrame.from(firstSnapshot)
            val firstEvent =
                FrameEventLog(
                    listOf(GameEvent.PhaseChanged(SeatId(1), firstFrame.phase.number, firstFrame.step.number)),
                )
            val first =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(firstSnapshot, events = firstEvent),
                    ProjectionState.initial(),
                )
            val stableSnapshot = firstSnapshot.withGameStateId(5)
            val stable =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(stableSnapshot, first.projectionSnapshot, firstEvent),
                    first.transition.nextState,
                )
            val advancedSnapshot =
                GsmSnapshot.forTest(
                    matchId = "compiler",
                    gameStateId = 6,
                    phase = phaseSnapshot(PhaseType.COMBAT_BEGIN),
                )
            val repaired =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(advancedSnapshot, stable.projectionSnapshot),
                    stable.transition.nextState,
                )

            assertSoftly {
                stable.gsm.annotationsList.count { AnnotationType.PhaseOrStepModified in it.typeList } shouldBe 0
                repaired.gsm.annotationsList.count { AnnotationType.PhaseOrStepModified in it.typeList } shouldBe 1
                repaired.gsm.annotationsList
                    .single { AnnotationType.PhaseOrStepModified in it.typeList }
                    .affectedIdsList shouldContainExactly listOf(1)
            }
        }

        test("one shared plan renders distinct viewer baselines without renumbering Player output") {
            val previous = GsmSnapshot.forTest(matchId = "compiler", gameStateId = 4)
            val current =
                GsmSnapshot.forTest(
                    matchId = "compiler",
                    gameStateId = 5,
                    phase =
                        PhaseSnapshot(
                            turn = 1,
                            activePlayer = SeatId(2),
                            priorityPlayer = SeatId(2),
                            phase = PhaseType.MAIN1,
                        ),
                )
            val prior =
                ProjectionState.initial().copy(
                    viewerCursors =
                        mapOf(
                            SeatId(1) to ViewerProjectionCursor(previousSnapshot = previous),
                            SeatId(2) to ViewerProjectionCursor(previousSnapshot = null),
                        ),
                )
            val player = compilerInput(current, previous).copy(viewingSeatId = 1)
            val observer = compilerInput(current).copy(viewingSeatId = 2)
            val actions =
                ActionsAvailableReq
                    .newBuilder()
                    .addActions(Action.newBuilder().setActionType(ActionType.Pass))
                    .build()
            val onlyPlayer =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    prior,
                    listOf(StateProjectionCompiler.ViewerInput(player, actions = actions)),
                )
            val both =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    prior,
                    listOf(
                        StateProjectionCompiler.ViewerInput(player, actions = actions),
                        StateProjectionCompiler.ViewerInput(observer, role = ProjectionViewerRole.Observer),
                    ),
                )

            assertSoftly {
                both.viewers.map { it.seatId } shouldContainExactly listOf(SeatId(1), SeatId(2))
                both.viewers[0]
                    .result.gsm.type shouldBe GameStateType.Diff
                both.viewers[1]
                    .result.gsm.type shouldBe GameStateType.Full
                both.viewers[0]
                    .result.gsm.pendingMessageCount shouldBe 1
                both.viewers[0]
                    .result.gsm.actionsCount shouldBe 1
                both.viewers[0]
                    .result.gsm.actionsList
                    .single()
                    .seatId shouldBe 1
                both.viewers[1]
                    .result.gsm.pendingMessageCount shouldBe 0
                both.viewers[1]
                    .result.gsm.actionsCount shouldBe 0
                both.viewers[0]
                    .result.gsm
                    .toByteArray()
                    .toList() shouldBe
                    onlyPlayer.viewers
                        .single()
                        .result.gsm
                        .toByteArray()
                        .toList()
                both.transition.nextState.identities shouldBe onlyPlayer.transition.nextState.identities
                both.transition.nextState.revision shouldBe prior.revision + 1
                prior.viewerCursors[SeatId(1)]?.previousSnapshot shouldBe previous
                prior.viewerCursors[SeatId(2)]?.previousSnapshot shouldBe null
            }
        }

        test("Observer role redacts seat-private objects in Full and Diff") {
            val playerCard = ForgeCardId(10)
            val observerCard = ForgeCardId(20)
            val initial = privateHandsSnapshot(1, playerCard, observerCard, "Player card", "Observer card")
            val prior = ProjectionState.initial()
            val full =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    prior,
                    listOf(
                        StateProjectionCompiler.ViewerInput(compilerInput(initial).copy(viewingSeatId = 1)),
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(initial).copy(viewingSeatId = 2),
                            role = ProjectionViewerRole.Observer,
                        ),
                    ),
                )
            val playerId =
                full.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(playerCard)
                    .value
            val changed = privateHandsSnapshot(2, playerCard, observerCard, "Player changed", "Observer changed")
            val diff =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    full.transition.nextState,
                    listOf(
                        StateProjectionCompiler.ViewerInput(compilerInput(changed, initial).copy(viewingSeatId = 1)),
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(changed, initial).copy(viewingSeatId = 2),
                            role = ProjectionViewerRole.Observer,
                        ),
                    ),
                )

            assertSoftly {
                full.viewers[0]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly listOf(playerId)
                full.viewers[1]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly emptyList()
                diff.viewers[0]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly listOf(playerId)
                diff.viewers[1]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly emptyList()
                full.viewers[1]
                    .result.gsm.zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldContainExactly emptyList()
                diff.viewers[1]
                    .result.gsm.zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldContainExactly emptyList()
            }
        }

        test("Seat observer sees its own hand and both hand counts in Full and Diff") {
            val playerCard = ForgeCardId(10)
            val observerCard = ForgeCardId(20)
            val initial = privateHandsSnapshot(1, playerCard, observerCard, "Player card", "Observer card")
            val prior = ProjectionState.initial()
            val full =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    prior,
                    listOf(
                        StateProjectionCompiler.ViewerInput(compilerInput(initial).copy(viewingSeatId = 1)),
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(initial).copy(viewingSeatId = 2),
                            role = ProjectionViewerRole.SeatObserver,
                        ),
                    ),
                )
            val playerId =
                full.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(playerCard)
                    .value
            val observerId =
                full.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(observerCard)
                    .value
            val changed = privateHandsSnapshot(2, playerCard, observerCard, "Player changed", "Observer changed")
            val diff =
                StateProjectionCompiler.compileViewers(
                    compilerEnvironment(),
                    full.transition.nextState,
                    listOf(
                        StateProjectionCompiler.ViewerInput(compilerInput(changed, initial).copy(viewingSeatId = 1)),
                        StateProjectionCompiler.ViewerInput(
                            compilerInput(changed, initial).copy(viewingSeatId = 2),
                            role = ProjectionViewerRole.SeatObserver,
                        ),
                    ),
                )

            assertSoftly {
                full.viewers[1]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly listOf(observerId)
                diff.viewers[1]
                    .result.gsm.gameObjectsList
                    .map { it.instanceId } shouldContainExactly listOf(observerId)
                full.viewers[1]
                    .result.gsm.zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldContainExactly listOf(playerId, observerId)
                diff.transition.nextState.viewerCursors
                    .getValue(SeatId(2))
                    .fullState!!
                    .zonesList
                    .filter { it.visibility == Visibility.Private }
                    .flatMap { it.objectInstanceIdsList } shouldContainExactly listOf(playerId, observerId)
                full.viewers[1]
                    .result.gsm.actionsCount shouldBe 0
                diff.viewers[1]
                    .result.gsm.actionsCount shouldBe 0
            }
        }

        test("one compile stages order move then supplements and clears exact submitted targets") {
            val cardId = ForgeCardId(10)
            val sourceId = ForgeCardId(20)
            val pending = PendingSubmittedTargets(InstanceId(777), SeatId(1), version = 3)
            val prior =
                ProjectionState
                    .initial()
                    .copy(viewerCursors = mapOf(SeatId(1) to ViewerProjectionCursor(pendingSubmittedTargets = pending)))
            val input = compilerInput(orderSnapshot(cardId))
            val intent =
                ViewerProjectionIntent.of(
                    supplements =
                        listOf(
                            ProjectionSupplement.NewTurnStarted,
                            ProjectionSupplement.PlayerSelectingTargets(cardId, SeatId(1), stackAbilityForgeId = 9),
                            ProjectionSupplement.ReserveTriggeredAbility(10),
                            ProjectionSupplement.SubmitPendingTargets(pending.spellInstanceId, pending.casterSeatId, pending.version),
                        ),
                    orderPrompt =
                        OrderPromptProjection.of(
                            candidateForgeIds = listOf(cardId),
                            sourceForgeId = sourceId,
                            move = OrderZoneMoveFact.of(SeatId(1), listOf(cardId), putOnTop = true),
                        ),
                )

            val first = StateProjectionCompiler.compileOneViewer(compilerEnvironment(), input, prior, intent)
            val retry = StateProjectionCompiler.compileOneViewer(compilerEnvironment(), input, prior, intent)
            val next = first.transition.nextState
            val newCardId = next.identities.forgeIdToInstanceId.getValue(cardId)
            val annotationTypes = first.gsm.annotationsList.map { it.typeList.single() }
            val submitted = first.gsm.annotationsList.single { it.typeList == listOf(AnnotationType.PlayerSubmittedTargets) }
            val changed = first.gsm.annotationsList.single { it.typeList == listOf(AnnotationType.ObjectIdChanged) }
            val transfer = first.gsm.annotationsList.single { it.typeList == listOf(AnnotationType.ZoneTransfer_af5a) }
            val newTurn = first.gsm.annotationsList.single { it.typeList == listOf(AnnotationType.NewTurnStarted) }
            val selecting = first.gsm.annotationsList.single { it.typeList == listOf(AnnotationType.PlayerSelectingTargets) }

            shouldThrow<IllegalStateException> {
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    input,
                    first.transition.nextState,
                    ViewerProjectionIntent.of(
                        listOf(
                            ProjectionSupplement.SubmitPendingTargets(
                                pending.spellInstanceId,
                                pending.casterSeatId,
                                pending.version,
                            ),
                        ),
                    ),
                )
            }

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe retry.gsm.toByteArray().toList()
                first.transition shouldBe retry.transition
                prior.identities.forgeIdToInstanceId shouldBe emptyMap()
                first.projectionSnapshot.zones
                    .getValue(ZoneIds.P1_HAND)
                    .contents shouldBe emptyList()
                first.projectionSnapshot.zones
                    .getValue(ZoneIds.P1_LIBRARY)
                    .contents shouldContainExactly listOf(cardId)
                annotationTypes.indexOf(AnnotationType.ObjectIdChanged) shouldBe
                    annotationTypes.indexOf(AnnotationType.ZoneTransfer_af5a) - 1
                changed.affectedIdsList shouldContainExactly listOf(100)
                transfer.affectedIdsList shouldContainExactly listOf(newCardId.value)
                newTurn.affectedIdsList shouldContainExactly listOf(1)
                selecting.affectedIdsList shouldContainExactly
                    listOf(
                        next.identities.forgeIdToInstanceId
                            .getValue(FrameIdResolver.triggerStackAbilityForgeId(9))
                            .value,
                    )
                submitted.affectedIdsList shouldContainExactly listOf(777)
                next.identities.forgeIdToInstanceId shouldContainKey FrameIdResolver.triggerStackAbilityForgeId(9)
                next.identities.forgeIdToInstanceId shouldContainKey FrameIdResolver.triggerStackAbilityForgeId(10)
                next.viewerCursors.getValue(SeatId(1)).previousSnapshot shouldBe first.projectionSnapshot
                next.viewerCursors.getValue(SeatId(1)).pendingSubmittedTargets shouldBe null
                next.limboInstanceIds shouldBe setOf(100)
                next.protoZones[newCardId.value] shouldBe ZoneIds.P1_LIBRARY
                first.gsm.annotationsList.map { it.id } shouldContainExactly
                    (50 until 50 + first.gsm.annotationsCount).toList()
                next.persistentAnnotations.nextAnnotationId shouldBe 50 + first.gsm.annotationsCount
            }
        }

        test("order candidates are private objects even without a pending move") {
            val cardId = ForgeCardId(10)
            val snapshot = orderSnapshot(cardId)
            val result =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    compilerInput(snapshot),
                    ProjectionState.initial(),
                    ViewerProjectionIntent.of(orderPrompt = OrderPromptProjection.of(listOf(cardId))),
                )
            val instanceId =
                result.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(cardId)
                    .value
            val cardObject = result.gsm.gameObjectsList.single { it.instanceId == instanceId }

            assertSoftly {
                result.projectionSnapshot shouldBe snapshot
                cardObject.visibility shouldBe Visibility.Private
                cardObject.viewersList shouldContainExactly listOf(1)
            }
        }

        test("reservation-only supplement allocates identity and version mismatch leaves prior unchanged") {
            val prior = ProjectionState.initial()
            val input = compilerInput(GsmSnapshot.forTest(matchId = "compiler", gameStateId = 1))
            val reserved =
                StateProjectionCompiler.compileOneViewer(
                    compilerEnvironment(),
                    input,
                    prior,
                    ViewerProjectionIntent.of(listOf(ProjectionSupplement.ReserveTriggeredAbility(44))),
                )
            val mismatched =
                ViewerProjectionIntent.of(
                    listOf(ProjectionSupplement.SubmitPendingTargets(InstanceId(9), SeatId(1), version = 1)),
                )

            shouldThrow<IllegalStateException> {
                StateProjectionCompiler.compileOneViewer(compilerEnvironment(), input, prior, mismatched)
            }

            assertSoftly {
                reserved.transition.nextState.identities.forgeIdToInstanceId shouldContainKey
                    FrameIdResolver.triggerStackAbilityForgeId(44)
                reserved.gsm.annotationsList.any { it.typeList.contains(AnnotationType.PlayerSelectingTargets) } shouldBe false
                reserved.gsm.annotationsList.any { it.typeList.contains(AnnotationType.PlayerSubmittedTargets) } shouldBe false
                prior shouldBe ProjectionState.initial()
            }
        }

        test("admitted activation aliases its exact root while an older sibling stays untouched") {
            val sourceId = ForgeCardId(10)
            val olderAbilityId = 30
            val newRootAbilityId = 14
            val newAdmittedAbilityId = 40
            val older = stackAbility(sourceId, olderAbilityId)
            val newRoot = stackAbility(sourceId, newRootAbilityId)
            val newAdmitted = stackAbility(sourceId, newAdmittedAbilityId)
            val previous = stackAbilitySnapshot(1, sourceId, listOf(older, newRoot))
            val current = stackAbilitySnapshot(2, sourceId, listOf(older, newAdmitted))
            val priorEditor = ProjectionState.initial().editor()
            val olderIid =
                priorEditor.identities.getOrAlloc(FrameIdResolver.triggerStackAbilityForgeId(olderAbilityId))
            val newRootIid =
                priorEditor.identities.getOrAlloc(FrameIdResolver.triggerStackAbilityForgeId(newRootAbilityId))
            val prior = priorEditor.freeze()
            val input =
                compilerInput(
                    snapshot = current,
                    previousSnapshot = previous,
                    events =
                        FrameEventLog(
                            listOf(
                                GameEvent.SpellCast(
                                    cardId = sourceId,
                                    seatId = SeatId(1),
                                    isAbility = true,
                                    abilityForgeId = newAdmittedAbilityId,
                                    abilityGrpId = newAdmitted.grpId,
                                    rootAbilityForgeId = newRootAbilityId,
                                    stackAbilityForgeId = newAdmittedAbilityId,
                                ),
                            ),
                        ),
                )

            val next = StateProjectionCompiler.compileOneViewer(compilerEnvironment(), input, prior).transition.nextState

            assertSoftly {
                next.identities.forgeIdToInstanceId.getValue(
                    FrameIdResolver.triggerStackAbilityForgeId(olderAbilityId),
                ) shouldBe olderIid
                next.identities.forgeIdToInstanceId.getValue(
                    FrameIdResolver.triggerStackAbilityForgeId(newAdmittedAbilityId),
                ) shouldBe newRootIid
                olderIid shouldNotBe newRootIid
            }
        }

        test("actions follow an identity reallocated by a same-frame library draw") {
            val cardId = ForgeCardId(108)
            val oldIid = 100
            val actions =
                ActionsAvailableReq
                    .newBuilder()
                    .addActions(
                        Action
                            .newBuilder()
                            .setActionType(ActionType.Cast)
                            .setInstanceId(oldIid)
                            .setFacetId(oldIid)
                            .setSourceId(oldIid),
                    ).addInactiveActions(
                        Action
                            .newBuilder()
                            .setActionType(ActionType.Cast)
                            .setInstanceId(oldIid)
                            .setFacetId(oldIid)
                            .setSourceId(oldIid),
                    ).build()

            val result =
                StateProjectionCompiler.compileOneViewerWithActions(
                    compilerEnvironment(),
                    compilerInput(orderSnapshot(cardId)),
                    ProjectionState.initial(),
                    intent =
                        ViewerProjectionIntent.of(
                            orderPrompt =
                                OrderPromptProjection.of(
                                    candidateForgeIds = listOf(cardId),
                                    move = OrderZoneMoveFact.of(SeatId(1), listOf(cardId), putOnTop = true),
                                ),
                        ),
                    actions = actions,
                )
            val newIid =
                result.transition.nextState.identities.forgeIdToInstanceId
                    .getValue(cardId)
                    .value
            val cast =
                result.gsm.actionsList
                    .single { it.action.actionType == ActionType.Cast }
                    .action
            val remapped = ActionMapper.remapInstanceIds(actions, result.output.idReallocations)
            val inactive = remapped.inactiveActionsList.single()

            assertSoftly {
                newIid shouldNotBe oldIid
                cast.instanceId shouldBe newIid
                inactive.instanceId shouldBe newIid
                result.gsm.actionsList.none { it.action.instanceId == oldIid } shouldBe true
                remapped.inactiveActionsList.none { it.instanceId == oldIid } shouldBe true
            }
        }
    })

private fun compilerEnvironment(): StateProjectionEnvironment {
    val cards = InMemoryCardRepository()
    return StateProjectionEnvironment(
        CardProtoBuilder(cards),
        MatchProjectionConfig(isBrawlOrCommander = false),
        ProjectionCardReferences(cards),
    )
}

private fun compilerInput(
    snapshot: GsmSnapshot,
    previousSnapshot: GsmSnapshot? = null,
    events: FrameEventLog = FrameEventLog.EMPTY,
): StateFrameInput =
    StateFrameInput(
        gameStateId = snapshot.gameStateId,
        snapshot = snapshot,
        previousSnapshot = previousSnapshot,
        events = events,
        promptFacts = PromptProjectionFacts(),
        updateType = GameStateUpdate.Send,
        viewingSeatId = 1,
        revealForSeat = null,
        effectFacts = EffectProjectionFacts(),
        mechanicSourceFacts = MechanicSourceFacts(),
        abilityExhaustionFacts = AbilityExhaustionFacts(),
        persistentFeedFacts = PersistentFeedFacts(),
    )

private fun phaseSnapshot(phase: PhaseType): PhaseSnapshot =
    PhaseSnapshot(
        turn = 1,
        activePlayer = SeatId(1),
        priorityPlayer = SeatId(1),
        phase = phase,
    )

private fun stackAbility(
    sourceId: ForgeCardId,
    abilityId: Int,
) = StackEntry(
    forgeCardId = sourceId,
    controller = SeatId(1),
    owner = SeatId(1),
    grpId = 9002,
    sourceCardGrpId = 9001,
    isSpell = false,
    isActivatedAbility = true,
    targets = emptyList(),
    forgeAbilityId = abilityId,
)

private fun stackAbilitySnapshot(
    gameStateId: Int,
    sourceId: ForgeCardId,
    entries: List<StackEntry>,
): GsmSnapshot =
    GsmSnapshot.forTest(
        matchId = "compiler",
        gameStateId = gameStateId,
        objects = mapOf(sourceId to CardSnapshot(sourceId, "Ability Source", 9001, SeatId(1), SeatId(1))),
        zones =
            linkedMapOf(
                ZoneIds.BATTLEFIELD to
                    ZoneSnapshot(ZoneIds.BATTLEFIELD, ZoneType.Battlefield, null, Visibility.Public, listOf(sourceId)),
                ZoneIds.STACK to ZoneSnapshot(ZoneIds.STACK, ZoneType.Stack, null, Visibility.Public, emptyList()),
                ZoneIds.LIMBO to ZoneSnapshot(ZoneIds.LIMBO, ZoneType.Limbo, null, Visibility.Public, emptyList()),
            ),
        stack = StackSnapshot(entries),
    )

private fun orderSnapshot(cardId: ForgeCardId): GsmSnapshot =
    GsmSnapshot.forTest(
        matchId = "compiler",
        gameStateId = 1,
        objects =
            mapOf(
                cardId to CardSnapshot(cardId, "Ordered Card", 9001, SeatId(1), SeatId(1)),
            ),
        zones =
            linkedMapOf(
                ZoneIds.P1_HAND to
                    ZoneSnapshot(ZoneIds.P1_HAND, ZoneType.Hand, SeatId(1), Visibility.Private, listOf(cardId)),
                ZoneIds.P1_LIBRARY to
                    ZoneSnapshot(ZoneIds.P1_LIBRARY, ZoneType.Library, SeatId(1), Visibility.Hidden, emptyList()),
            ),
    )

private fun drawSnapshot(
    gameStateId: Int,
    cardId: ForgeCardId,
    inLibrary: Boolean,
): GsmSnapshot {
    val card = CardSnapshot(cardId, "Drawn Card", 9001, SeatId(1), SeatId(1))
    return GsmSnapshot.forTest(
        matchId = "compiler",
        gameStateId = gameStateId,
        objects = mapOf(cardId to card),
        zones =
            linkedMapOf(
                ZoneIds.P1_LIBRARY to
                    ZoneSnapshot(
                        ZoneIds.P1_LIBRARY,
                        ZoneType.Library,
                        SeatId(1),
                        Visibility.Hidden,
                        if (inLibrary) listOf(cardId) else emptyList(),
                    ),
                ZoneIds.P1_HAND to
                    ZoneSnapshot(
                        ZoneIds.P1_HAND,
                        ZoneType.Hand,
                        SeatId(1),
                        Visibility.Private,
                        if (inLibrary) emptyList() else listOf(cardId),
                    ),
            ),
    )
}

private fun privateHandsSnapshot(
    gameStateId: Int,
    playerCard: ForgeCardId,
    observerCard: ForgeCardId,
    playerName: String,
    observerName: String,
): GsmSnapshot =
    GsmSnapshot.forTest(
        matchId = "compiler",
        gameStateId = gameStateId,
        seats =
            listOf(
                SeatSnapshot(SeatId(1), 20, 20, 7),
                SeatSnapshot(SeatId(2), 20, 20, 7),
            ),
        objects =
            mapOf(
                playerCard to CardSnapshot(playerCard, playerName, 9001, SeatId(1), SeatId(1)),
                observerCard to CardSnapshot(observerCard, observerName, 9002, SeatId(2), SeatId(2)),
            ),
        zones =
            linkedMapOf(
                ZoneIds.P1_HAND to
                    ZoneSnapshot(ZoneIds.P1_HAND, ZoneType.Hand, SeatId(1), Visibility.Private, listOf(playerCard)),
                ZoneIds.P2_HAND to
                    ZoneSnapshot(ZoneIds.P2_HAND, ZoneType.Hand, SeatId(2), Visibility.Private, listOf(observerCard)),
            ),
    )
