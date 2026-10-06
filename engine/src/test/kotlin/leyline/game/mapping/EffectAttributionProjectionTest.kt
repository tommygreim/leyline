package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.event.FrameEventLog
import leyline.game.event.GameEvent
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.GameBridge
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PromptProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class EffectAttributionProjectionTest :
    FunSpec({
        tags(UnitTag)

        test("enriched effect facts replay exactly across creation and stable frames") {
            val targetId = ForgeCardId(101)
            val keywordSourceId = ForgeCardId(301)
            val firstSnapshot = effectSnapshot(targetId, gameStateId = 1)
            val facts =
                EffectProjectionFacts(
                    boostEntries =
                        listOf(
                            EffectProjectionFacts.BoostEntry(
                                targetId,
                                timestamp = 1L,
                                staticId = 2L,
                                power = 2,
                                toughness = 3,
                                sourceAbilityGrpId = 777,
                            ),
                        ),
                    keywordEntries =
                        listOf(
                            EffectProjectionFacts.KeywordEntry(
                                targetId,
                                timestamp = 3L,
                                staticId = 4L,
                                keyword = "Flying",
                                affectorForgeCardId = keywordSourceId,
                            ),
                        ),
                )
            val firstInput = frameInput(firstSnapshot, previous = null, facts = facts)
            val initial =
                leyline.game.state.ProjectionState
                    .initial()
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val committedEffectsBefore = bridge.committedEffectProjection()

            val first = StateProjectionCompiler.compileOneViewer(bridge.stateProjectionEnvironment, firstInput, initial)
            val firstRetry =
                StateProjectionCompiler.compileOneViewer(bridge.stateProjectionEnvironment, firstInput, initial)
            val nextIdentity = checkNotNull(first.transition).nextState.identities
            val nextEffects = checkNotNull(first.transition).nextState.effects

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe firstRetry.gsm.toByteArray().toList()
                first.gsm.annotationsList.map(AnnotationInfo::effectShape) shouldBe createdTransientShapes()
                first.gsm.persistentAnnotationsList.map(AnnotationInfo::effectShape) shouldBe createdPersistentShapes()
                nextIdentity.forgeIdToInstanceId.entries.map { it.toPair() } shouldBe
                    listOf(
                        targetId to InstanceId(100),
                        keywordSourceId to InstanceId(101),
                    )
                nextIdentity.nextInstanceId shouldBe 102
                nextEffects.effects.activeEffects.values
                    .single()
                    .sourceAbilityGrpId shouldBe 777
                nextEffects.effects.activeKeywordEffects.values
                    .single()
                    .affectorForgeCardId shouldBe keywordSourceId
                nextEffects.effects.activeEffects.values
                    .single()
                    .syntheticId shouldBe 7005
                nextEffects.effects.activeKeywordEffects.values
                    .single()
                    .syntheticId shouldBe 7006
                nextEffects.effects.nextId shouldBe 7008
                bridge.getInstanceIdMap() shouldBe emptyMap()
                bridge.committedEffectProjection() shouldBe committedEffectsBefore
            }

            bridge.commitProjection(checkNotNull(first.transition))
            val stableSnapshot =
                effectSnapshot(targetId, gameStateId = 2)
            val stableInput =
                frameInput(
                    stableSnapshot,
                    previous = first.projectionSnapshot,
                    facts = facts,
                )
            val stable =
                StateProjectionCompiler.compileOneViewer(
                    bridge.stateProjectionEnvironment,
                    stableInput,
                    first.transition.nextState,
                )
            val stableRetry =
                StateProjectionCompiler.compileOneViewer(bridge.stateProjectionEnvironment, stableInput, first.transition.nextState)

            assertSoftly {
                stable.gsm.toByteArray().toList() shouldBe stableRetry.gsm.toByteArray().toList()
                stable.gsm.annotationsList shouldBe emptyList()
                stable.gsm.persistentAnnotationsList shouldBe emptyList()
                stable.transition.nextState.effects shouldBe nextEffects
                stable.transition.nextState.identities shouldBe nextIdentity
                bridge.getInstanceIdMap() shouldBe nextIdentity.instanceIdToForgeId
                bridge.committedEffectProjection() shouldBe nextEffects
            }
        }

        test("unresolved keyword attribution uses the latest immutable resolution event") {
            val targetId = ForgeCardId(101)
            val earlierSourceId = ForgeCardId(401)
            val latestSourceId = ForgeCardId(402)
            val snapshot = effectSnapshot(targetId, gameStateId = 1)
            val facts =
                EffectProjectionFacts(
                    keywordEntries =
                        listOf(
                            EffectProjectionFacts.KeywordEntry(targetId, 1L, 0L, "Flying"),
                        ),
                )
            val input =
                frameInput(
                    snapshot,
                    previous = null,
                    facts = facts,
                    events =
                        FrameEventLog(
                            listOf(
                                GameEvent.SpellResolved(earlierSourceId, hasFizzled = false),
                                GameEvent.SpellResolved(latestSourceId, hasFizzled = false),
                            ),
                        ),
                )
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val initial =
                leyline.game.state.ProjectionState
                    .initial()

            val first = StateProjectionCompiler.compileOneViewer(bridge.stateProjectionEnvironment, input, initial)
            val retry = StateProjectionCompiler.compileOneViewer(bridge.stateProjectionEnvironment, input, initial)
            val nextIdentity = checkNotNull(first.transition).nextState.identities
            val latestSourceIid = nextIdentity.forgeIdToInstanceId.getValue(latestSourceId).value
            val addAbility =
                first.gsm.persistentAnnotationsList.single {
                    AnnotationType.AddAbility_af5a in it.typeList
                }

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe retry.gsm.toByteArray().toList()
                addAbility.affectorId shouldBe latestSourceIid
                addAbility.detailsList
                    .single { it.key == "originalAbilityObjectZcid" }
                    .getValueInt32(0) shouldBe latestSourceIid
                nextIdentity.forgeIdToInstanceId.entries.map { it.toPair() } shouldBe
                    listOf(
                        targetId to InstanceId(100),
                        latestSourceId to InstanceId(101),
                    )
                nextIdentity.nextInstanceId shouldBe 102
                bridge.getInstanceIdMap() shouldBe emptyMap()
            }
        }
    })

private fun effectSnapshot(
    targetId: ForgeCardId,
    gameStateId: Int,
): GsmSnapshot {
    val target =
        CardSnapshot(
            forgeCardId = targetId,
            name = "Grizzly Bears",
            grpId = 93801,
            owner = SeatId(1),
            controller = SeatId(1),
            isOnBattlefield = true,
            netPower = 2,
            netToughness = 2,
        )
    return GsmSnapshot.forTest(
        gameStateId = gameStateId,
        objects = mapOf(targetId to target),
        zones =
            mapOf(
                ZoneIds.BATTLEFIELD to
                    ZoneSnapshot(
                        id = ZoneIds.BATTLEFIELD,
                        type = ZoneType.Battlefield,
                        owner = null,
                        visibility = Visibility.Public,
                        contents = listOf(targetId),
                    ),
            ),
    )
}

private fun frameInput(
    snapshot: GsmSnapshot,
    previous: GsmSnapshot?,
    facts: EffectProjectionFacts,
    events: FrameEventLog = FrameEventLog.EMPTY,
): StateFrameInput =
    StateFrameInput(
        gameStateId = snapshot.gameStateId,
        snapshot = snapshot,
        previousSnapshot = previous,
        events = events,
        promptFacts = PromptProjectionFacts(),
        updateType = GameStateUpdate.SendAndRecord,
        viewingSeatId = 1,
        revealForSeat = null,
        effectFacts = facts,
        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
        persistentFeedFacts = leyline.game.state.PersistentFeedFacts(),
        mechanicSourceFacts = MechanicSourceFacts(),
    )

private data class EffectAnnotationShape(
    val id: Int,
    val affectorId: Int,
    val affectedIds: List<Int>,
    val types: List<AnnotationType>,
    val intDetails: List<Pair<String, Int>>,
)

private fun AnnotationInfo.effectShape(): EffectAnnotationShape =
    EffectAnnotationShape(
        id = id,
        affectorId = affectorId,
        affectedIds = affectedIdsList,
        types = typeList,
        intDetails = detailsList.map { it.key to it.getValueInt32(0) },
    )

private fun createdTransientShapes(): List<EffectAnnotationShape> =
    listOf(
        EffectAnnotationShape(50, 0, listOf(7002), listOf(AnnotationType.LayeredEffectCreated), emptyList()),
        EffectAnnotationShape(51, 0, listOf(7003), listOf(AnnotationType.LayeredEffectCreated), emptyList()),
        EffectAnnotationShape(52, 0, listOf(7004), listOf(AnnotationType.LayeredEffectCreated), emptyList()),
        EffectAnnotationShape(53, 0, listOf(7002), listOf(AnnotationType.LayeredEffectDestroyed), emptyList()),
        EffectAnnotationShape(54, 0, listOf(7003), listOf(AnnotationType.LayeredEffectDestroyed), emptyList()),
        EffectAnnotationShape(55, 0, listOf(7004), listOf(AnnotationType.LayeredEffectDestroyed), emptyList()),
        EffectAnnotationShape(
            56,
            100,
            listOf(100),
            listOf(AnnotationType.PowerToughnessModCreated),
            listOf("power" to 2, "toughness" to 3),
        ),
        EffectAnnotationShape(57, 100, listOf(7005), listOf(AnnotationType.LayeredEffectCreated), emptyList()),
        EffectAnnotationShape(58, 101, listOf(7006), listOf(AnnotationType.LayeredEffectCreated), emptyList()),
    )

private fun createdPersistentShapes(): List<EffectAnnotationShape> =
    listOf(
        EffectAnnotationShape(
            1,
            100,
            listOf(100),
            listOf(AnnotationType.ModifiedToughness, AnnotationType.ModifiedPower, AnnotationType.LayeredEffect),
            listOf("effect_id" to 7005, "sourceAbilityGRPID" to 777),
        ),
        EffectAnnotationShape(
            2,
            101,
            listOf(100),
            listOf(AnnotationType.AddAbility_af5a, AnnotationType.LayeredEffect),
            listOf(
                "effect_id" to 7006,
                "grpid" to 8,
                "UniqueAbilityId" to 7007,
                "originalAbilityObjectZcid" to 101,
            ),
        ),
    )
