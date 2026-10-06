package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.event.DestructionCause
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.event.ZoneMove
import leyline.game.event.ZoneMoveCause
import leyline.game.mapping.ZoneIds
import leyline.game.state.InstanceIdRegistry
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneInfo
import wotc.mtgo.gre.external.messaging.Messages.ZoneType

class ZoneMoveLedgerTest :
    FunSpec({
        tags(UnitTag)

        data class Case(
            val name: String,
            val from: Zone,
            val to: Zone,
            val expected: TransferCategory,
            val api: String? = null,
            val events: List<GameEvent> = emptyList(),
        )

        val cardId = ForgeCardId(42)
        val seat = SeatId(1)
        val cases =
            listOf(
                Case("cast", Zone.Hand, Zone.Stack, TransferCategory.CastSpell, events = listOf(GameEvent.SpellCast(cardId, seat))),
                Case("cast before announcement", Zone.Graveyard, Zone.Stack, TransferCategory.CastSpell, api = "Draw"),
                Case(
                    "resolve",
                    Zone.Stack,
                    Zone.Battlefield,
                    TransferCategory.Resolve,
                    events = listOf(GameEvent.SpellResolved(cardId, false)),
                ),
                Case(
                    "play land",
                    Zone.Hand,
                    Zone.Battlefield,
                    TransferCategory.PlayLand,
                    events = listOf(GameEvent.LandPlayed(cardId, seat)),
                ),
                Case(
                    "destroy",
                    Zone.Battlefield,
                    Zone.Graveyard,
                    TransferCategory.Destroy,
                    events = listOf(GameEvent.CardDestroyed(cardId, seat)),
                ),
                Case(
                    "lethal damage death",
                    Zone.Battlefield,
                    Zone.Graveyard,
                    TransferCategory.SbaDamage,
                    events = listOf(GameEvent.CardDestroyed(cardId, seat, destruction = DestructionCause.LethalDamage)),
                ),
                Case(
                    "deathtouch death",
                    Zone.Battlefield,
                    Zone.Graveyard,
                    TransferCategory.SbaDeathtouch,
                    events = listOf(GameEvent.CardDestroyed(cardId, seat, destruction = DestructionCause.Deathtouch)),
                ),
                Case(
                    "sacrifice",
                    Zone.Battlefield,
                    Zone.Graveyard,
                    TransferCategory.Sacrifice,
                    events =
                        listOf(
                            GameEvent.CardSacrificed(cardId, seat),
                            GameEvent.ZoneChanged(cardId, Zone.Battlefield, Zone.Graveyard),
                        ),
                ),
                Case(
                    "discard",
                    Zone.Hand,
                    Zone.Graveyard,
                    TransferCategory.Discard,
                    api = "Discard",
                    events = listOf(GameEvent.CardDiscarded(cardId, seat)),
                ),
                Case(
                    "mill",
                    Zone.Library,
                    Zone.Graveyard,
                    TransferCategory.Mill,
                    api = "Mill",
                    events = listOf(GameEvent.CardMilled(cardId, seat)),
                ),
                Case(
                    "surveil",
                    Zone.Library,
                    Zone.Graveyard,
                    TransferCategory.Surveil,
                    events = listOf(GameEvent.CardSurveiled(cardId, seat)),
                ),
                Case(
                    "exile",
                    Zone.Battlefield,
                    Zone.Exile,
                    TransferCategory.Exile,
                    events = listOf(GameEvent.CardExiled(cardId, seat)),
                ),
                Case(
                    "bounce",
                    Zone.Battlefield,
                    Zone.Hand,
                    TransferCategory.Bounce,
                    events = listOf(GameEvent.CardBounced(cardId, seat)),
                ),
                Case(
                    "return",
                    Zone.Graveyard,
                    Zone.Battlefield,
                    TransferCategory.Return,
                    api = "ChangeZone",
                    events = listOf(GameEvent.ZoneChanged(cardId, Zone.Graveyard, Zone.Battlefield)),
                ),
                Case(
                    "search",
                    Zone.Library,
                    Zone.Battlefield,
                    TransferCategory.Search,
                    api = "ChangeZone",
                    events = listOf(GameEvent.ZoneChanged(cardId, Zone.Library, Zone.Battlefield)),
                ),
                Case(
                    "draw",
                    Zone.Library,
                    Zone.Hand,
                    TransferCategory.Draw,
                    api = "Draw",
                    events = listOf(GameEvent.ZoneChanged(cardId, Zone.Library, Zone.Hand)),
                ),
                Case(
                    "countered",
                    Zone.Stack,
                    Zone.Graveyard,
                    TransferCategory.Countered,
                    events = listOf(GameEvent.SpellResolved(cardId, true)),
                ),
                Case(
                    "put",
                    Zone.Sideboard,
                    Zone.Hand,
                    TransferCategory.Put,
                    api = "ChangeZone",
                    events = listOf(GameEvent.ZoneChanged(cardId, Zone.Sideboard, Zone.Hand)),
                ),
                Case(
                    "legend rule",
                    Zone.Battlefield,
                    Zone.Graveyard,
                    TransferCategory.SbaLegendRule,
                    events = listOf(GameEvent.LegendRuleDeath(cardId, seat)),
                ),
            )

        cases.forEach { case ->
            test("classifies ${case.name} from ordered move facts") {
                val intent = ZoneMoveLedger.fold(listOf(move(cardId, case.from, case.to, case.api)), case.events).single()
                intent.category shouldBe case.expected
                intent.origin shouldBe TransferPlanOrigin.Event
            }
        }

        cases.forEach { case ->
            test("plans ${case.name} from the authoritative event ledger") {
                val zoneMove = move(cardId, case.from, case.to, case.api)
                val previousZone = case.from.protocolZoneId()
                val destinationZone = case.to.protocolZoneId()
                val obj =
                    GameObjectInfo
                        .newBuilder()
                        .setInstanceId(100)
                        .setGrpId(12345)
                        .setZoneId(destinationZone)
                        .setOwnerSeatId(1)
                        .build()
                val zones =
                    listOf(
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(destinationZone)
                            .setType(case.to.protoType())
                            .addObjectInstanceIds(100)
                            .build(),
                        ZoneInfo
                            .newBuilder()
                            .setZoneId(ZoneIds.LIMBO)
                            .setType(ZoneType.Limbo)
                            .build(),
                    )

                fun result() =
                    ZoneTransferDetector.detectZoneTransfers(
                        gameObjects = listOf(obj),
                        zones = zones,
                        events = case.events,
                        context =
                            ZoneTransferContext(
                                previousZones = mapOf(100 to previousZone),
                                forgeIdLookup = { if (it.value == 100) cardId else null },
                                idAllocator = { InstanceIdRegistry.IdReallocation(InstanceId(100), InstanceId(200)) },
                                idLookup = { InstanceId(it.value + 1000) },
                                grpIdResolver = { GrpId(12345) },
                                zoneMoves = listOf(zoneMove),
                            ),
                    )

                val eventFirst = result()
                assertSoftly {
                    eventFirst shouldBe result()
                    eventFirst.transfers.single().category shouldBe case.expected
                    eventFirst.snapshotFallbacks.shouldBeEmpty()
                }
            }
        }

        test("preserves same-frame Omen cast and resolve moves") {
            val moves =
                listOf(
                    move(cardId, Zone.Hand, Zone.Stack, order = 0),
                    move(cardId, Zone.Stack, Zone.Library, order = 1),
                )
            val events = listOf(GameEvent.SpellCast(cardId, seat, isOmen = true), GameEvent.SpellResolved(cardId, false))

            ZoneMoveLedger.fold(moves, events).map { it.category } shouldContainExactly
                listOf(TransferCategory.CastSpell, TransferCategory.Resolve)
        }

        test("counter effect cause does not counter its own successfully resolving spell") {
            val targetId = ForgeCardId(43)
            val counterId = ForgeCardId(44)
            val cause = ZoneMoveCause(counterId, 17, 17, "Counter", false)
            val moves =
                listOf(
                    ZoneMove(0, targetId, Zone.Stack, Zone.Graveyard, cause),
                    ZoneMove(1, counterId, Zone.Stack, Zone.Graveyard, cause),
                )
            val events = listOf(GameEvent.SpellResolved(counterId, hasFizzled = false))

            ZoneMoveLedger.fold(moves, events).map { it.category } shouldContainExactly
                listOf(TransferCategory.Countered, TransferCategory.Resolve)
        }

        test("preserves same-frame Paradigm cast and exile moves") {
            val moves =
                listOf(
                    move(cardId, Zone.Hand, Zone.Stack, order = 0),
                    move(cardId, Zone.Stack, Zone.Exile, order = 1),
                )
            val events = listOf(GameEvent.SpellCast(cardId, seat), GameEvent.SpellResolved(cardId, false))

            ZoneMoveLedger.fold(moves, events).map { it.category } shouldContainExactly
                listOf(TransferCategory.CastSpell, TransferCategory.Exile)
        }

        test("matches Warp exile to its frozen cause identity") {
            val unrelatedCardId = ForgeCardId(43)
            val warpCause = ZoneMoveCause(cardId, 327, 327, "ChangeZone", false)
            val unrelatedCause = ZoneMoveCause(unrelatedCardId, 501, 501, "ChangeZone", false)
            val moves =
                listOf(
                    ZoneMove(0, cardId, Zone.Battlefield, Zone.Exile, warpCause),
                    ZoneMove(1, unrelatedCardId, Zone.Battlefield, Zone.Exile, unrelatedCause),
                )
            val events =
                listOf(
                    GameEvent.SpellResolved(
                        cardId = cardId,
                        hasFizzled = false,
                        isTrigger = true,
                        abilityForgeId = 328,
                        abilityGrpId = 372,
                        rootAbilityForgeId = 328,
                        stackAbilityForgeId = 327,
                    ),
                )

            ZoneMoveLedger.fold(moves, events).map { it.category } shouldContainExactly
                listOf(TransferCategory.Warp, TransferCategory.Exile)
        }

        test("does not bleed a destroy event into a later move of the same card") {
            val moves =
                listOf(
                    move(cardId, Zone.Battlefield, Zone.Graveyard, order = 0),
                    move(cardId, Zone.Graveyard, Zone.Battlefield, order = 1),
                )

            val intents = ZoneMoveLedger.fold(moves, listOf(GameEvent.CardDestroyed(cardId, seat)))

            intents.map { it.category } shouldContainExactly
                listOf(TransferCategory.Destroy, TransferCategory.Return)
            intents.map { it.origin } shouldContainExactly
                listOf(TransferPlanOrigin.Event, TransferPlanOrigin.SnapshotFallback)
        }

        test("uses frozen cause identity as the transfer source") {
            val sourceId = ForgeCardId(7)
            val intent =
                ZoneMoveLedger
                    .fold(
                        listOf(
                            ZoneMove(
                                order = 0,
                                cardId = cardId,
                                from = Zone.Library,
                                to = Zone.Graveyard,
                                cause = ZoneMoveCause(sourceId, 91, 90, "Mill", false, stackAbilityForgeId = 92),
                            ),
                        ),
                        emptyList(),
                    ).single()

            assertSoftly {
                intent.sourceCardId shouldBe sourceId
                intent.sourceAbilityForgeId shouldBe 91
                intent.rootAbilityForgeId shouldBe 90
                intent.stackAbilityForgeId shouldBe 92
            }
        }
    })

private fun move(
    cardId: ForgeCardId,
    from: Zone,
    to: Zone,
    api: String? = null,
    order: Int = 0,
): ZoneMove =
    ZoneMove(
        order = order,
        cardId = cardId,
        from = from,
        to = to,
        cause = api?.let { ZoneMoveCause(ForgeCardId(7), 91, 90, it, false) },
    )

private fun Zone.protocolZoneId(): Int =
    when (this) {
        Zone.Hand -> ZoneIds.P1_HAND
        Zone.Library -> ZoneIds.P1_LIBRARY
        Zone.Graveyard -> ZoneIds.P1_GRAVEYARD
        Zone.Battlefield -> ZoneIds.BATTLEFIELD
        Zone.Exile -> ZoneIds.EXILE
        Zone.Stack -> ZoneIds.STACK
        Zone.Command -> ZoneIds.COMMAND
        Zone.Sideboard -> ZoneIds.P1_SIDEBOARD
        Zone.Other -> ZoneIds.SUPPRESSED
    }

private fun Zone.protoType(): ZoneType =
    when (this) {
        Zone.Hand -> ZoneType.Hand
        Zone.Library -> ZoneType.Library
        Zone.Graveyard -> ZoneType.Graveyard
        Zone.Battlefield -> ZoneType.Battlefield
        Zone.Exile -> ZoneType.Exile
        Zone.Stack -> ZoneType.Stack
        Zone.Command -> ZoneType.Command
        Zone.Sideboard -> ZoneType.Sideboard
        Zone.Other -> ZoneType.Suppressed
    }
