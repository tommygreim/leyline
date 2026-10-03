package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.data.CardData
import leyline.game.data.CardProtoBuilder
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.SeatSnapshot
import leyline.game.snapshot.StackEntry
import leyline.game.snapshot.StackSnapshot
import leyline.game.snapshot.ZoneSnapshot
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.GameVariant
import wotc.mtgo.gre.external.messaging.Messages.Visibility
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class StateZoneProjectionTest :
    FunSpec({
        tags(UnitTag)

        fun environment(isBrawl: Boolean = false): StateProjectionEnvironment {
            val cards = InMemoryCardRepository()
            return StateProjectionEnvironment(
                CardProtoBuilder(cards),
                MatchProjectionConfig(isBrawl),
                ProjectionCardReferences(cards),
            )
        }

        test("shared-zone projection filters unprojectable and missing card values") {
            val visibleId = ForgeCardId(1)
            val syntheticId = ForgeCardId(2)
            val missingId = ForgeCardId(3)
            val snap =
                GsmSnapshot.forTest(
                    zones =
                        mapOf(
                            ZoneIds.BATTLEFIELD to
                                ZoneSnapshot(
                                    id = ZoneIds.BATTLEFIELD,
                                    type = ZoneType.Battlefield,
                                    owner = null,
                                    visibility = Visibility.Public,
                                    contents = listOf(visibleId, syntheticId, missingId),
                                ),
                        ),
                    objects =
                        mapOf(
                            visibleId to CardSnapshot(visibleId, "Visible", 101, SeatId(2), SeatId(1)),
                            syntheticId to
                                CardSnapshot(
                                    syntheticId,
                                    "Helper",
                                    0,
                                    SeatId(1),
                                    SeatId(1),
                                    isProjectable = false,
                                ),
                        ),
                )

            val projected =
                StateZoneProjection.projectSharedZone(snap, ZoneIds.BATTLEFIELD, environment(), { InstanceId(it.value + 100) })!!

            assertSoftly {
                projected.zone.objectInstanceIdsList shouldContainExactly listOf(101)
                projected.gameObjects.map { it.instanceId } shouldContainExactly listOf(101)
                projected.gameObjects.single().ownerSeatId shouldBe 2
                projected.gameObjects.single().grpId shouldBe 101
            }
        }

        test("copied spell projects as a copied card in the stack zone") {
            val copiedId = ForgeCardId(12)
            val snap =
                GsmSnapshot.forTest(
                    zones =
                        mapOf(
                            ZoneIds.STACK to
                                ZoneSnapshot(
                                    id = ZoneIds.STACK,
                                    type = ZoneType.Stack,
                                    owner = null,
                                    visibility = Visibility.Public,
                                    contents = listOf(copiedId),
                                ),
                        ),
                    objects =
                        mapOf(
                            copiedId to
                                CardSnapshot(
                                    copiedId,
                                    "Copied spell",
                                    202,
                                    SeatId(1),
                                    SeatId(1),
                                    isCopyToken = true,
                                ),
                        ),
                )

            val projected =
                StateZoneProjection.projectSharedZone(snap, ZoneIds.STACK, environment(), { InstanceId(it.value + 100) })!!

            assertSoftly {
                projected.gameObjects.single().type shouldBe GameObjectType.Card
                projected.gameObjects.single().isCopy shouldBe true
                projected.zone.objectInstanceIdsList shouldContainExactly listOf(112)
            }
        }

        test("modal spell projects the selected mode ability on the public stack card") {
            val cards = InMemoryCardRepository()
            cards.registerData(
                CardData(
                    grpId = 202,
                    titleId = 1,
                    power = "",
                    toughness = "",
                    colors = emptyList(),
                    types = emptyList(),
                    subtypes = emptyList(),
                    supertypes = emptyList(),
                    abilityIds = listOf(204487 to 0),
                    manaCost = emptyList(),
                ),
                "Modal spell",
            )
            val environment =
                StateProjectionEnvironment(
                    CardProtoBuilder(cards),
                    MatchProjectionConfig(false),
                    ProjectionCardReferences(cards),
                )
            val spellId = ForgeCardId(12)
            val snap =
                GsmSnapshot.forTest(
                    zones =
                        mapOf(
                            ZoneIds.STACK to
                                ZoneSnapshot(
                                    id = ZoneIds.STACK,
                                    type = ZoneType.Stack,
                                    owner = null,
                                    visibility = Visibility.Public,
                                    contents = listOf(spellId),
                                ),
                        ),
                    objects =
                        mapOf(
                            spellId to CardSnapshot(spellId, "Modal spell", 202, SeatId(1), SeatId(1)),
                        ),
                    stack =
                        StackSnapshot(
                            listOf(
                                StackEntry(
                                    forgeCardId = spellId,
                                    controller = SeatId(1),
                                    owner = SeatId(1),
                                    grpId = 204487,
                                    sourceCardGrpId = 202,
                                    isSpell = true,
                                    targets = emptyList(),
                                    selectedModalAbilityGrpIds = listOf(94618, 94619),
                                ),
                            ),
                        ),
                )

            val projected =
                StateZoneProjection
                    .projectSharedZone(snap, ZoneIds.STACK, environment, { InstanceId(it.value + 100) })!!

            val objectInfo = projected.gameObjects.single()
            objectInfo.uniqueAbilitiesList.map { it.grpId } shouldBe listOf(94618, 94619)
        }

        test("unresolved single-ability stack entry is suppressed instead of using source grpId") {
            val sourceId = ForgeCardId(12)
            val cards = InMemoryCardRepository()
            cards.registerData(
                CardData(
                    grpId = 202,
                    titleId = 1,
                    power = "",
                    toughness = "",
                    colors = emptyList(),
                    types = emptyList(),
                    subtypes = emptyList(),
                    supertypes = emptyList(),
                    abilityIds = listOf(30303 to 0),
                    manaCost = emptyList(),
                ),
                "Single-ability source",
            )
            val bridge = GameBridge(cardRepository = cards)
            val snap =
                GsmSnapshot.forTest(
                    stack =
                        StackSnapshot(
                            listOf(
                                StackEntry(
                                    forgeCardId = sourceId,
                                    controller = SeatId(1),
                                    owner = SeatId(1),
                                    grpId = 0,
                                    sourceCardGrpId = 202,
                                    isSpell = false,
                                    targets = emptyList(),
                                ),
                            ),
                        ),
                )
            val zones =
                mutableListOf(
                    ZoneInfo
                        .newBuilder()
                        .setZoneId(ZoneIds.STACK)
                        .setType(ZoneType.Stack)
                        .setVisibility(Visibility.Public)
                        .build(),
                )
            val gameObjects = mutableListOf<GameObjectInfo>()

            ZoneMapper.addStackAbilitiesFromSnapshot(
                snap = snap,
                environment = bridge.stateProjectionEnvironment,
                instanceIdLookup = bridge::getOrAllocInstanceId,
                paradigmSourceStackIidLookup = { null },
                zones = zones,
                gameObjects = gameObjects,
            )

            gameObjects shouldBe emptyList()
            zones.single().objectInstanceIdsList shouldBe emptyList()
        }

        test("zone-transfer facts retain cut-scoped card semantics") {
            val cardId = ForgeCardId(7)
            val sourceId = ForgeCardId(8)
            val effectId = ForgeCardId(9)
            val snap =
                GsmSnapshot.forTest(
                    objects =
                        mapOf(
                            cardId to
                                CardSnapshot(
                                    cardId,
                                    "Foretold Forest",
                                    707,
                                    SeatId(1),
                                    SeatId(1),
                                    basicLandManaAbilityGrpId = 1005,
                                    effectSourceForgeCardId = sourceId,
                                    isForetold = true,
                                    hasParadigmKeyword = true,
                                ),
                        ),
                    stack =
                        StackSnapshot(
                            listOf(
                                StackEntry(
                                    forgeCardId = effectId,
                                    controller = SeatId(1),
                                    owner = SeatId(1),
                                    grpId = 909,
                                    sourceCardGrpId = 707,
                                    isSpell = false,
                                    targets = emptyList(),
                                    effectSourceForgeCardId = sourceId,
                                ),
                            ),
                        ),
                )

            val facts = StateZoneProjection.zoneTransferFacts(snap)

            assertSoftly {
                facts.card(cardId) shouldNotBe null
                facts.card(cardId)?.grpId shouldBe 707
                facts.card(cardId)?.basicLandManaAbilityGrpId shouldBe 1005
                facts.card(cardId)?.effectSourceForgeCardId shouldBe sourceId
                facts.card(cardId)?.isForetold shouldBe true
                facts.card(effectId) shouldNotBe null
                facts.card(effectId)?.grpId shouldBe 707
                facts.card(effectId)?.effectSourceForgeCardId shouldBe sourceId
                StateZoneProjection.isParadigm(snap, cardId) shouldBe true
                StateZoneProjection.paradigmSourceStackIid(facts, effectId) { id ->
                    if (id == sourceId) 808 else null
                } shouldBe 808
            }
        }

        test("stack ability projection resolves Paradigm parent from cut facts") {
            val helperId = ForgeCardId(7)
            val sourceId = ForgeCardId(8)
            val snap =
                GsmSnapshot.forTest(
                    stack =
                        StackSnapshot(
                            listOf(
                                StackEntry(
                                    forgeCardId = helperId,
                                    controller = SeatId(1),
                                    owner = SeatId(1),
                                    grpId = KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER,
                                    sourceCardGrpId = 808,
                                    isSpell = false,
                                    targets = emptyList(),
                                    effectSourceForgeCardId = sourceId,
                                ),
                            ),
                        ),
                )
            val zones =
                mutableListOf(
                    ZoneInfo
                        .newBuilder()
                        .setZoneId(ZoneIds.STACK)
                        .setType(ZoneType.Stack)
                        .setVisibility(Visibility.Public)
                        .build(),
                )
            val gameObjects = mutableListOf<GameObjectInfo>()
            val facts = StateZoneProjection.zoneTransferFacts(snap)

            val bridge = GameBridge(cardRepository = InMemoryCardRepository())
            ZoneMapper.addStackAbilitiesFromSnapshot(
                snap = snap,
                environment = bridge.stateProjectionEnvironment,
                instanceIdLookup = bridge::getOrAllocInstanceId,
                paradigmSourceStackIidLookup = { forgeCardId ->
                    StateZoneProjection.paradigmSourceStackIid(facts, forgeCardId) { id ->
                        if (id == sourceId) 818 else null
                    }
                },
                zones = zones,
                gameObjects = gameObjects,
            )

            gameObjects.single().parentId shouldBe 818
        }

        test("seat values drive player presence and match configuration") {
            val snap =
                GsmSnapshot.forTest(
                    seats = listOf(SeatSnapshot(SeatId(2), life = 13, startingLife = 20, maxHandSize = 7)),
                )

            assertSoftly {
                StateZoneProjection.hasSeat(snap, SeatId(1)) shouldBe false
                StateZoneProjection.hasSeat(snap, SeatId(2)) shouldBe true
                StateZoneProjection.buildGameInfo("match", MatchProjectionConfig(true)).variant shouldBe GameVariant.Brawl
                StateZoneProjection.buildGameInfo("match", MatchProjectionConfig(true)).freeMulliganCount shouldBe 1
                StateZoneProjection.buildGameInfo("match", MatchProjectionConfig(false)).variant shouldBe GameVariant.Normal
            }
        }
    })
