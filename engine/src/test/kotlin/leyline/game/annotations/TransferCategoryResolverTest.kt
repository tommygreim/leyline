package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.DestructionCause
import leyline.game.event.GameEvent
import leyline.game.event.Zone

class TransferCategoryResolverTest :
    FunSpec({
        tags(UnitTag)

        val cardId = ForgeCardId(42)
        val seatId = SeatId(1)

        data class Case(
            val name: String,
            val event: GameEvent,
            val expected: TransferCategory,
        )

        listOf(
            Case("land play", GameEvent.LandPlayed(cardId, seatId), TransferCategory.PlayLand),
            Case("cast", GameEvent.SpellCast(cardId, seatId), TransferCategory.CastSpell),
            Case("resolve", GameEvent.SpellResolved(cardId, false), TransferCategory.Resolve),
            Case("counter", GameEvent.SpellResolved(cardId, true), TransferCategory.Countered),
            Case("legend rule", GameEvent.LegendRuleDeath(cardId, seatId), TransferCategory.SbaLegendRule),
            Case("surveil", GameEvent.CardSurveiled(cardId, seatId), TransferCategory.Surveil),
            Case("exile", GameEvent.CardExiled(cardId, seatId), TransferCategory.Exile),
            Case("bounce", GameEvent.CardBounced(cardId, seatId), TransferCategory.Bounce),
            Case("discard", GameEvent.CardDiscarded(cardId, seatId), TransferCategory.Discard),
            Case("mill", GameEvent.CardMilled(cardId, seatId), TransferCategory.Mill),
            Case("sacrifice", GameEvent.CardSacrificed(cardId, seatId), TransferCategory.Sacrifice),
            Case("destroy", GameEvent.CardDestroyed(cardId, seatId), TransferCategory.Destroy),
            Case(
                "lethal damage death",
                GameEvent.CardDestroyed(cardId, seatId, destruction = DestructionCause.LethalDamage),
                TransferCategory.SbaDamage,
            ),
            Case(
                "deathtouch death",
                GameEvent.CardDestroyed(cardId, seatId, destruction = DestructionCause.Deathtouch),
                TransferCategory.SbaDeathtouch,
            ),
        ).forEach { case ->
            test("resolves ${case.name} fallback from its operation event") {
                TransferCategoryResolver.categoryFromEvents(cardId, listOf(case.event)) shouldBe case.expected
            }
        }

        test("operation precedence is deterministic") {
            assertSoftly {
                TransferCategoryResolver.categoryFromEvents(
                    cardId,
                    listOf(GameEvent.CardMilled(cardId, seatId), GameEvent.CardSurveiled(cardId, seatId)),
                ) shouldBe TransferCategory.Surveil
                TransferCategoryResolver.categoryFromEvents(
                    cardId,
                    listOf(GameEvent.CardDestroyed(cardId, seatId), GameEvent.CardSacrificed(cardId, seatId)),
                ) shouldBe TransferCategory.Sacrifice
                TransferCategoryResolver.categoryFromEvents(
                    cardId,
                    listOf(GameEvent.CardSacrificed(cardId, seatId), GameEvent.CardExiled(cardId, seatId)),
                ) shouldBe TransferCategory.Exile
            }
        }

        test("ignores unrelated and activated-ability events") {
            assertSoftly {
                TransferCategoryResolver
                    .categoryFromEvents(
                        cardId,
                        listOf(GameEvent.LandPlayed(ForgeCardId(99), seatId)),
                    ).shouldBeNull()
                TransferCategoryResolver
                    .categoryFromEvents(
                        cardId,
                        listOf(GameEvent.SpellCast(cardId, seatId, isAbility = true)),
                    ).shouldBeNull()
                TransferCategoryResolver.categoryFromEvents(cardId, emptyList()).shouldBeNull()
            }
        }

        test("a card put from hand onto the library is a Put") {
            // Arena labels the mulligan bottoming "Put" (capture gs 6, hand 31 -> library 32).
            // "ZoneTransfer" is not a member of the client's ZoneTransferReason enum, so the
            // reason degrades to Invalid and the move animates as nothing in particular.
            TransferCategoryResolver.categoryFromZonePair(Zone.Hand, Zone.Library) shouldBe TransferCategory.Put
        }

        test("extracts source context only for the affected card") {
            val sourceId = ForgeCardId(7)
            val events =
                listOf(
                    GameEvent.CardMilled(cardId, seatId, sourceId),
                    GameEvent.CardSurveiled(ForgeCardId(99), seatId, ForgeCardId(8)),
                )

            assertSoftly {
                TransferCategoryResolver.affectorSourceFromEvents(cardId, events) shouldBe sourceId
                TransferCategoryResolver.affectorSourceFromEvents(ForgeCardId(99), events) shouldBe ForgeCardId(8)
                TransferCategoryResolver.affectorSourceFromEvents(ForgeCardId(100), events).shouldBeNull()
            }
        }
    })
