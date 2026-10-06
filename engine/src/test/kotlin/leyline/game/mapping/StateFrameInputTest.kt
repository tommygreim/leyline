package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
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
import leyline.game.state.EarthbendTracker
import leyline.game.state.EffectProjectionFacts
import leyline.game.state.GameBridge
import leyline.game.state.MechanicSourceFacts
import leyline.game.state.PromptProjectionFacts
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateUpdate
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class StateFrameInputTest :
    FunSpec({
        tags(UnitTag)

        test("zero-Forge state-frame input replays prompt facts without consumption") {
            val choice = leyline.game.state.ChoiceResult(ForgeCardId(1), SeatId(1), 9, null, 2)
            val facts =
                PromptProjectionFacts(
                    choiceResults =
                        listOf(
                            PromptProjectionFacts.ChoiceResultFact(
                                leyline.game.state.PromptFactKey(SeatId(1), 1),
                                choice,
                            ),
                        ),
                )
            val input =
                StateFrameInput(
                    gameStateId = 1,
                    snapshot = GsmSnapshot.forTest(matchId = "state-frame"),
                    previousSnapshot = null,
                    events = FrameEventLog.EMPTY,
                    promptFacts = facts,
                    updateType = GameStateUpdate.SendAndRecord,
                    viewingSeatId = 1,
                    revealForSeat = null,
                    effectFacts = EffectProjectionFacts(),
                    abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    persistentFeedFacts = leyline.game.state.PersistentFeedFacts(),
                    mechanicSourceFacts = MechanicSourceFacts(),
                )
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val initial =
                leyline.game.state.ProjectionState
                    .initial()

            val first = StateProjectionCompiler.compileOneViewer(bridge.stateProjectionEnvironment, input, initial)
            val second = StateProjectionCompiler.compileOneViewer(bridge.stateProjectionEnvironment, input, initial)

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe second.gsm.toByteArray().toList()
                first.output.promptFactConsumption.choiceResults shouldContainExactly
                    listOf(leyline.game.state.PromptFactKey(SeatId(1), 1))
                second.output.promptFactConsumption shouldBe first.output.promptFactConsumption
            }
        }

        test("zero-Forge state-frame input compiles every scoped effect family from facts") {
            val cardId = ForgeCardId(101)
            val signature = EarthbendTracker.Signature(timestamp = 10, staticId = 11)
            val targetIid = 100
            val keywordAffectorIid = 101
            val crewVehicleIid = 102
            val crewSourceIid = 103
            val reconfigureIid = 104
            val saddleMountIid = 105
            val saddleSourceIid = 106
            val earthbendLayerIds = listOf(7002, 7003, 7004, 7005)
            val card =
                CardSnapshot(
                    forgeCardId = cardId,
                    name = "Forest",
                    grpId = 100,
                    owner = SeatId(1),
                    controller = SeatId(1),
                    isLand = true,
                    isOnBattlefield = true,
                    netPower = 0,
                    netToughness = 0,
                )
            val snapshot =
                GsmSnapshot.forTest(
                    objects = mapOf(cardId to card),
                    zones =
                        mapOf(
                            ZoneIds.BATTLEFIELD to
                                ZoneSnapshot(
                                    id = ZoneIds.BATTLEFIELD,
                                    type = ZoneType.Battlefield,
                                    owner = null,
                                    visibility = Visibility.Public,
                                    contents = listOf(cardId),
                                ),
                        ),
                )
            val facts =
                EffectProjectionFacts(
                    boostEntries =
                        listOf(
                            EffectProjectionFacts.BoostEntry(
                                cardId,
                                1,
                                2,
                                power = 2,
                                toughness = 3,
                                sourceAbilityGrpId = 777,
                            ),
                        ),
                    keywordEntries =
                        listOf(
                            EffectProjectionFacts.KeywordEntry(cardId, 10, 11, "Haste"),
                            EffectProjectionFacts.KeywordEntry(
                                cardId,
                                12,
                                13,
                                "Flying",
                                affectorForgeCardId = ForgeCardId(301),
                            ),
                        ),
                    crewStates =
                        listOf(
                            EffectProjectionFacts.CrewState(
                                ForgeCardId(201),
                                listOf(ForgeCardId(202)),
                                isCreature = true,
                                crewAbilityGrpId = 333,
                            ),
                        ),
                    saddleStates =
                        listOf(
                            EffectProjectionFacts.SaddleState(ForgeCardId(203), listOf(ForgeCardId(204))),
                        ),
                    reconfigureStates =
                        listOf(
                            EffectProjectionFacts.ReconfigureState(
                                forgeCardId = ForgeCardId(205),
                                isAttached = true,
                                isCreature = false,
                                attachAbilityGrpId = 444,
                            ),
                        ),
                    pendingEarthbendResolutions =
                        listOf(
                            EffectProjectionFacts.PendingEarthbendResolution(
                                version = 1,
                                sourceCardId = cardId,
                                sourceAbilityGrpId = 555,
                                abilityForgeId = 0,
                                targetCardIds = listOf(cardId),
                            ),
                        ),
                    battlefieldEarthbendSignatures =
                        listOf(
                            EffectProjectionFacts.BattlefieldEarthbendSignature(cardId, signature),
                        ),
                )
            val input =
                StateFrameInput(
                    gameStateId = 1,
                    snapshot = snapshot,
                    previousSnapshot = null,
                    events = FrameEventLog.EMPTY,
                    promptFacts = PromptProjectionFacts(),
                    updateType = GameStateUpdate.SendAndRecord,
                    viewingSeatId = 1,
                    revealForSeat = null,
                    effectFacts = facts,
                    abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    persistentFeedFacts = leyline.game.state.PersistentFeedFacts(),
                    mechanicSourceFacts = MechanicSourceFacts(),
                )
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val committedIdentityBefore = bridge.getInstanceIdMap()
            val committedEffectsBefore = bridge.committedEffectProjection()
            val initial =
                leyline.game.state.ProjectionState
                    .initial()
            val first = StateProjectionCompiler.compileOneViewer(bridge.stateProjectionEnvironment, input, initial)
            val second = StateProjectionCompiler.compileOneViewer(bridge.stateProjectionEnvironment, input, initial)
            val nextIdentity = checkNotNull(first.transition).nextState.identities
            val nextEffects = checkNotNull(first.transition).nextState.effects
            val earthbend = nextEffects.earthbend.activeByTarget.getValue(cardId)
            val boost =
                nextEffects.effects.activeEffects.values
                    .single()
            val keyword =
                nextEffects.effects.activeKeywordEffects.values
                    .single()

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe second.gsm.toByteArray().toList()
                first.transition.nextState.effects shouldBe second.transition.nextState.effects
                bridge.getInstanceIdMap() shouldBe committedIdentityBefore
                bridge.committedEffectProjection() shouldBe committedEffectsBefore
                first.output.consumedEarthbendResolutionVersions shouldBe setOf(1L)
                first.gsm.annotationsList.map(AnnotationInfo::shape) shouldBe
                    expectedTransientShapes(targetIid, keywordAffectorIid, crewVehicleIid, reconfigureIid, earthbendLayerIds)
                first.gsm.persistentAnnotationsList.map(AnnotationInfo::shape) shouldBe
                    expectedPersistentShapes(
                        targetIid = targetIid,
                        keywordAffectorIid = keywordAffectorIid,
                        crewVehicleIid = crewVehicleIid,
                        crewSourceIid = crewSourceIid,
                        reconfigureIid = reconfigureIid,
                        saddleMountIid = saddleMountIid,
                        saddleSourceIid = saddleSourceIid,
                    )
                first.output.persistentBatch.deletedIds shouldBe listOf(3)

                nextIdentity.forgeIdToInstanceId.entries.map { it.toPair() } shouldBe
                    listOf(
                        cardId to InstanceId(targetIid),
                        ForgeCardId(301) to InstanceId(keywordAffectorIid),
                        ForgeCardId(201) to InstanceId(crewVehicleIid),
                        ForgeCardId(202) to InstanceId(crewSourceIid),
                        ForgeCardId(205) to InstanceId(reconfigureIid),
                        ForgeCardId(203) to InstanceId(saddleMountIid),
                        ForgeCardId(204) to InstanceId(saddleSourceIid),
                    )
                nextIdentity.nextInstanceId shouldBe 107

                earthbend.layers.all shouldBe earthbendLayerIds
                earthbend.uniqueAbilityId shouldBe 200
                nextEffects.earthbend.nextUniqueAbilityId shouldBe 201
                boost.syntheticId shouldBe 7009
                boost.sourceAbilityGrpId shouldBe 777
                keyword.keyword shouldBe "Flying"
                keyword.syntheticId shouldBe 7010
                keyword.affectorForgeCardId shouldBe ForgeCardId(301)
                nextEffects.crew.active shouldBe mapOf(ForgeCardId(201) to 7012)
                nextEffects.reconfigure.active shouldBe mapOf(ForgeCardId(205) to 7013)
                nextEffects.effects.nextId shouldBe 7014
            }
        }

        test("effect facts snapshot caller collections") {
            val crewSources = mutableListOf(ForgeCardId(202))
            val targetIds = mutableListOf(ForgeCardId(301))
            val facts =
                EffectProjectionFacts(
                    crewStates =
                        listOf(
                            EffectProjectionFacts.CrewState(
                                vehicleForgeCardId = ForgeCardId(201),
                                crewSourceForgeCardIds = crewSources,
                                isCreature = true,
                                crewAbilityGrpId = null,
                            ),
                        ),
                    pendingEarthbendResolutions =
                        listOf(
                            EffectProjectionFacts.PendingEarthbendResolution(
                                version = 1,
                                sourceCardId = ForgeCardId(10),
                                sourceAbilityGrpId = 900,
                                abilityForgeId = 0,
                                targetCardIds = targetIds,
                            ),
                        ),
                )

            crewSources += ForgeCardId(203)
            targetIds += ForgeCardId(302)

            assertSoftly {
                facts.crewStates.single().crewSourceForgeCardIds shouldBe listOf(ForgeCardId(202))
                facts.pendingEarthbendResolutions.single().targetCardIds shouldBe listOf(ForgeCardId(301))
            }
        }

        test("zero-Forge mechanic source input retries exact lifecycle mana and token projection") {
            val triggerSource = ForgeCardId(401)
            val triggeringObject = ForgeCardId(402)
            val forest = ForgeCardId(403)
            val spell = ForgeCardId(404)
            val token = ForgeCardId(405)
            val tokenCreator = ForgeCardId(406)
            val cards =
                linkedMapOf(
                    triggerSource to CardSnapshot(triggerSource, "Trigger Source", 4010, SeatId(1), SeatId(1)),
                    triggeringObject to CardSnapshot(triggeringObject, "Triggering Object", 4020, SeatId(1), SeatId(1)),
                    forest to
                        CardSnapshot(
                            forest,
                            "Forest",
                            4030,
                            SeatId(1),
                            SeatId(1),
                            basicLandManaAbilityGrpId = 1005,
                            isLand = true,
                            isOnBattlefield = true,
                        ),
                    spell to CardSnapshot(spell, "Spell", 4040, SeatId(1), SeatId(1)),
                    token to CardSnapshot(token, "Token", 4050, SeatId(1), SeatId(1), isToken = true, isOnBattlefield = true),
                    tokenCreator to CardSnapshot(tokenCreator, "Creator", 4060, SeatId(1), SeatId(1), isOnBattlefield = true),
                )
            val snapshot = GsmSnapshot.forTest(gameStateId = 17, objects = cards)
            val events =
                FrameEventLog(
                    listOf(
                        GameEvent.SpellCast(
                            cardId = triggerSource,
                            seatId = SeatId(1),
                            isAbility = true,
                            isTrigger = true,
                            abilityForgeId = 41,
                            abilityGrpId = 771,
                            triggeringObjectCardId = triggeringObject,
                        ),
                        GameEvent.SpellResolved(
                            cardId = triggerSource,
                            hasFizzled = false,
                            isTrigger = true,
                            abilityForgeId = 41,
                            abilityGrpId = 771,
                        ),
                        GameEvent.SpellCast(
                            cardId = spell,
                            seatId = SeatId(1),
                            spellGrpId = 4040,
                            manaPayments = listOf(GameEvent.ManaPayment(forest, color = 5)),
                        ),
                        GameEvent.TokenCreated(token, SeatId(1)),
                    ),
                )
            val input =
                StateFrameInput(
                    gameStateId = 17,
                    snapshot = snapshot,
                    previousSnapshot = snapshot,
                    events = events,
                    promptFacts = PromptProjectionFacts(),
                    updateType = GameStateUpdate.SendAndRecord,
                    viewingSeatId = 1,
                    revealForSeat = null,
                    effectFacts = EffectProjectionFacts(),
                    abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    persistentFeedFacts = leyline.game.state.PersistentFeedFacts(),
                    mechanicSourceFacts =
                        MechanicSourceFacts(
                            sourceZoneByForgeCardId =
                                mapOf(
                                    triggerSource to ZoneIds.P1_GRAVEYARD,
                                    triggeringObject to ZoneIds.P1_HAND,
                                ),
                            tokenCreatorByTokenForgeCardId =
                                mapOf(
                                    token to MechanicSourceFacts.TokenCreator(tokenCreator, 71),
                                ),
                        ),
                )
            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            val committedIdentityBefore = bridge.getInstanceIdMap()
            val committedEffectsBefore = bridge.committedEffectProjection()

            val initial =
                leyline.game.state.ProjectionState
                    .initial()
            val first = StateProjectionCompiler.compileOneViewer(bridge.stateProjectionEnvironment, input, initial)
            val retry = StateProjectionCompiler.compileOneViewer(bridge.stateProjectionEnvironment, input, initial)

            assertSoftly {
                first.gsm.toByteArray().toList() shouldBe retry.gsm.toByteArray().toList()
                first.transition shouldBe retry.transition
                first.gsm.annotationsList.map(AnnotationInfo::shape) shouldBe
                    expectedMechanicSourceTransientShapes()
                first.output.persistentBatch.allAnnotations
                    .map(AnnotationInfo::shape) shouldBe
                    listOf(
                        expectedAnnotation(
                            1,
                            103,
                            AnnotationType.TriggeringObject,
                            affectorId = 100,
                            details = listOf("source_zone" to ZoneIds.P1_HAND),
                        ),
                    )
                first.gsm.persistentAnnotationsList.map(AnnotationInfo::shape) shouldBe
                    listOf(
                        expectedAnnotation(
                            1,
                            103,
                            AnnotationType.TriggeringObject,
                            affectorId = 100,
                            details = listOf("source_zone" to ZoneIds.P1_HAND),
                        ),
                    )
                first.transition
                    .nextState
                    .identities
                    .forgeIdToInstanceId shouldBe
                    linkedMapOf(
                        ForgeCardId(100041) to InstanceId(100),
                        ForgeCardId(100404) to InstanceId(101),
                        triggerSource to InstanceId(102),
                        triggeringObject to InstanceId(103),
                        forest to InstanceId(104),
                        spell to InstanceId(105),
                        token to InstanceId(106),
                        tokenCreator to InstanceId(107),
                        ForgeCardId(200403) to InstanceId(108),
                        ForgeCardId(100071) to InstanceId(109),
                    )
                first.transition
                    .nextState
                    .identities
                    .nextInstanceId shouldBe 110
                bridge.getInstanceIdMap() shouldBe committedIdentityBefore
                bridge.committedEffectProjection() shouldBe committedEffectsBefore
            }
        }
    })

private data class AnnotationShape(
    val id: Int,
    val affectorId: Int,
    val affectedIds: List<Int>,
    val types: List<AnnotationType>,
    val details: List<AnnotationDetailShape>,
)

private data class AnnotationDetailShape(
    val key: String,
    val type: KeyValuePairValueType,
    val uint32Values: List<Int>,
    val int32Values: List<Int>,
    val uint64Values: List<Long>,
    val int64Values: List<Long>,
    val boolValues: List<Boolean>,
    val stringValues: List<String>,
    val floatValues: List<Float>,
    val doubleValues: List<Double>,
)

private fun AnnotationInfo.shape(): AnnotationShape =
    AnnotationShape(
        id = id,
        affectorId = affectorId,
        affectedIds = affectedIdsList,
        types = typeList,
        details = detailsList.map(KeyValuePairInfo::shape),
    )

private fun KeyValuePairInfo.shape(): AnnotationDetailShape =
    AnnotationDetailShape(
        key = key,
        type = type,
        uint32Values = valueUint32List,
        int32Values = valueInt32List,
        uint64Values = valueUint64List,
        int64Values = valueInt64List,
        boolValues = valueBoolList,
        stringValues = valueStringList,
        floatValues = valueFloatList,
        doubleValues = valueDoubleList,
    )

private fun expectedAnnotation(
    id: Int,
    affectedId: Int,
    vararg types: AnnotationType,
    affectorId: Int = 0,
    details: List<Pair<String, Int>> = emptyList(),
    stringDetails: List<Pair<String, String>> = emptyList(),
): AnnotationShape =
    AnnotationShape(
        id = id,
        affectorId = affectorId,
        affectedIds = listOf(affectedId),
        types = types.toList(),
        details =
            stringDetails.map { (key, value) ->
                AnnotationDetailShape(
                    key = key,
                    type = KeyValuePairValueType.String,
                    uint32Values = emptyList(),
                    int32Values = emptyList(),
                    uint64Values = emptyList(),
                    int64Values = emptyList(),
                    boolValues = emptyList(),
                    stringValues = listOf(value),
                    floatValues = emptyList(),
                    doubleValues = emptyList(),
                )
            } +
                details.map { (key, value) ->
                    AnnotationDetailShape(
                        key = key,
                        type = KeyValuePairValueType.Int32,
                        uint32Values = emptyList(),
                        int32Values = listOf(value),
                        uint64Values = emptyList(),
                        int64Values = emptyList(),
                        boolValues = emptyList(),
                        stringValues = emptyList(),
                        floatValues = emptyList(),
                        doubleValues = emptyList(),
                    )
                },
    )

private fun expectedTransientShapes(
    targetIid: Int,
    keywordAffectorIid: Int,
    crewVehicleIid: Int,
    reconfigureIid: Int,
    earthbendLayerIds: List<Int>,
): List<AnnotationShape> =
    listOf(
        expectedAnnotation(
            50,
            targetIid,
            AnnotationType.PowerToughnessModCreated,
            affectorId = targetIid,
            details = listOf("power" to 0, "toughness" to 0),
        ),
        expectedAnnotation(51, 7006, AnnotationType.LayeredEffectCreated),
        expectedAnnotation(52, 7007, AnnotationType.LayeredEffectCreated),
        expectedAnnotation(53, 7008, AnnotationType.LayeredEffectCreated),
        expectedAnnotation(54, 7006, AnnotationType.LayeredEffectDestroyed),
        expectedAnnotation(55, 7007, AnnotationType.LayeredEffectDestroyed),
        expectedAnnotation(56, 7008, AnnotationType.LayeredEffectDestroyed),
        expectedAnnotation(
            57,
            targetIid,
            AnnotationType.PowerToughnessModCreated,
            affectorId = targetIid,
            details = listOf("power" to 2, "toughness" to 3),
        ),
        expectedAnnotation(58, earthbendLayerIds[0], AnnotationType.LayeredEffectCreated, affectorId = targetIid),
        expectedAnnotation(59, earthbendLayerIds[1], AnnotationType.LayeredEffectCreated, affectorId = targetIid),
        expectedAnnotation(60, earthbendLayerIds[2], AnnotationType.LayeredEffectCreated, affectorId = targetIid),
        expectedAnnotation(61, earthbendLayerIds[3], AnnotationType.LayeredEffectCreated, affectorId = targetIid),
        expectedAnnotation(62, 7009, AnnotationType.LayeredEffectCreated, affectorId = targetIid),
        expectedAnnotation(63, 7010, AnnotationType.LayeredEffectCreated, affectorId = keywordAffectorIid),
        expectedAnnotation(64, 7012, AnnotationType.LayeredEffectCreated),
        expectedAnnotation(
            65,
            crewVehicleIid,
            AnnotationType.ShouldntPlay,
            affectorId = crewVehicleIid,
            details = listOf("abilityGrpId" to 333),
            stringDetails = listOf("Reason" to "RedundantActivation"),
        ),
        expectedAnnotation(66, 7013, AnnotationType.LayeredEffectCreated, affectorId = reconfigureIid),
    )

private fun expectedPersistentShapes(
    targetIid: Int,
    keywordAffectorIid: Int,
    crewVehicleIid: Int,
    crewSourceIid: Int,
    reconfigureIid: Int,
    saddleMountIid: Int,
    saddleSourceIid: Int,
): List<AnnotationShape> =
    listOf(
        expectedAnnotation(
            1,
            targetIid,
            AnnotationType.ModifiedToughness,
            AnnotationType.ModifiedPower,
            AnnotationType.LayeredEffect,
            affectorId = targetIid,
            details = listOf("effect_id" to 7009, "sourceAbilityGRPID" to 777),
        ),
        expectedAnnotation(
            2,
            targetIid,
            AnnotationType.AddAbility_af5a,
            AnnotationType.LayeredEffect,
            affectorId = keywordAffectorIid,
            details =
                listOf(
                    "effect_id" to 7010,
                    "grpid" to 8,
                    "UniqueAbilityId" to 7011,
                    "originalAbilityObjectZcid" to keywordAffectorIid,
                ),
        ),
        expectedAnnotation(
            4,
            targetIid,
            AnnotationType.AddAbility_af5a,
            AnnotationType.LayeredEffect,
            affectorId = targetIid,
            details =
                listOf(
                    "originalAbilityObjectZcid" to targetIid,
                    "UniqueAbilityId" to 200,
                    "grpid" to 9,
                    "sourceAbilityGRPID" to 555,
                    "effect_id" to 7003,
                ),
        ),
        expectedAnnotation(
            5,
            targetIid,
            AnnotationType.ModifiedPower,
            AnnotationType.LayeredEffect,
            affectorId = targetIid,
            details = listOf("sourceAbilityGRPID" to 555, "effect_id" to 7004),
        ),
        expectedAnnotation(
            6,
            targetIid,
            AnnotationType.ModifiedToughness,
            AnnotationType.LayeredEffect,
            affectorId = targetIid,
            details = listOf("sourceAbilityGRPID" to 555, "effect_id" to 7005),
        ),
        expectedAnnotation(
            7,
            crewSourceIid,
            AnnotationType.CrewedThisTurn,
            affectorId = crewVehicleIid,
        ),
        expectedAnnotation(
            8,
            saddleSourceIid,
            AnnotationType.SaddledThisTurn,
            affectorId = saddleMountIid,
        ),
        expectedAnnotation(
            9,
            crewVehicleIid,
            AnnotationType.ModifiedType,
            AnnotationType.LayeredEffect,
            affectorId = crewVehicleIid,
            details = listOf("effect_id" to 7012, "sourceAbilityGRPID" to 333),
        ),
        expectedAnnotation(
            10,
            reconfigureIid,
            AnnotationType.ModifiedType,
            AnnotationType.LayeredEffect,
            affectorId = reconfigureIid,
            details = listOf("effect_id" to 7013, "sourceAbilityGRPID" to 444),
        ),
        expectedAnnotation(
            11,
            targetIid,
            AnnotationType.Designation,
            affectorId = targetIid,
            details = listOf("DesignationType" to 23, "ControllerId" to 1),
        ),
    )

private fun expectedMechanicSourceTransientShapes(): List<AnnotationShape> =
    listOf(
        expectedAnnotation(
            50,
            100,
            AnnotationType.AbilityInstanceCreated,
            affectorId = 102,
            details = listOf("source_zone" to ZoneIds.P1_GRAVEYARD),
        ),
        expectedAnnotation(51, 100, AnnotationType.ResolutionStart, affectorId = 100, details = listOf("grpid" to 771)),
        expectedAnnotation(52, 106, AnnotationType.TokenCreated, affectorId = 109),
        expectedAnnotation(53, 100, AnnotationType.ResolutionComplete, affectorId = 100, details = listOf("grpid" to 771)),
        expectedAnnotation(54, 100, AnnotationType.AbilityInstanceDeleted, affectorId = 102),
        expectedAnnotation(
            55,
            108,
            AnnotationType.AbilityInstanceCreated,
            affectorId = 104,
            details = listOf("source_zone" to ZoneIds.BATTLEFIELD),
        ),
        expectedAnnotation(56, 104, AnnotationType.TappedUntappedPermanent, affectorId = 108, details = listOf("tapped" to 1)),
        expectedAnnotation(
            57,
            108,
            AnnotationType.UserActionTaken,
            affectorId = 1,
            details = listOf("actionType" to 4, "abilityGrpId" to 1005),
        ),
        expectedAnnotation(
            58,
            105,
            AnnotationType.ManaPaid,
            affectorId = 104,
            details = listOf("id" to 3, "color" to 5),
        ),
        expectedAnnotation(59, 108, AnnotationType.AbilityInstanceDeleted, affectorId = 104),
        expectedAnnotation(
            60,
            105,
            AnnotationType.UserActionTaken,
            affectorId = 1,
            details = listOf("actionType" to 1, "abilityGrpId" to 0),
        ),
        expectedAnnotation(61, 7002, AnnotationType.LayeredEffectCreated),
        expectedAnnotation(62, 7003, AnnotationType.LayeredEffectCreated),
        expectedAnnotation(63, 7004, AnnotationType.LayeredEffectCreated),
        expectedAnnotation(64, 7002, AnnotationType.LayeredEffectDestroyed),
        expectedAnnotation(65, 7003, AnnotationType.LayeredEffectDestroyed),
        expectedAnnotation(66, 7004, AnnotationType.LayeredEffectDestroyed),
    )
