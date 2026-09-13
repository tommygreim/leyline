package leyline.native.matchmaking

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.DeckCard
import leyline.domain.MatchInfo
import leyline.domain.PlayerId
import leyline.domain.deck.DeckCards
import leyline.domain.deck.DeckSource
import leyline.native.NativeTag

class LocalPairingServiceTest :
    FunSpec({
        tags(NativeTag)

        val alice = PlayerId("alice")
        val bob = PlayerId("bob")
        val deck = DeckCards(listOf(DeckCard(1, 60)))
        var configs = RuntimeMatchConfigRegistry()
        var clock = 0L
        var nextMatch = 0
        lateinit var pairing: LocalPairingService
        val assigned = mutableListOf<PairedSeat>()

        beforeEach {
            configs = RuntimeMatchConfigRegistry()
            assigned.clear()
            clock = 0
            nextMatch = 0
            pairing = LocalPairingService(configs, { event -> MatchInfo("match-${++nextMatch}", "localhost", 30003, event) }, { clock })
        }

        fun queue(
            player: PlayerId,
            cards: DeckCards = deck,
            event: String = "Play",
        ) {
            pairing.queue("fd-${player.value}", player, player.value, event, cards, assigned::add)
        }

        fun pair() {
            queue(alice)
            queue(bob)
        }

        test("two queued profiles receive one room with distinct human seats and frozen decks") {
            val cards = mutableListOf(DeckCard(10, 60))
            queue(alice, DeckCards(cards, commandZone = listOf(DeckCard(20, 1))))
            assigned shouldBe emptyList()
            cards.clear()
            queue(bob, DeckCards(listOf(DeckCard(30, 60))))
            val config = configs.get("match-1")!!
            assertSoftly {
                assigned.map { it.match.matchId } shouldBe listOf("match-1", "match-1")
                assigned.map { it.yourSeat } shouldBe listOf(1, 2)
                assigned[0].players shouldBe assigned[1].players
                assigned[0].players.map { it.playerId } shouldBe listOf(alice, bob)
                assigned[0].players[0].commanderGrpIds shouldBe listOf(20)
                config.humanVsHuman shouldBe true
                (config.seat1 as DeckSource.Cards).cards.mainDeck shouldBe listOf(DeckCard(10, 60))
                (config.seat2 as DeckSource.Cards).cards.mainDeck shouldBe listOf(DeckCard(30, 60))
            }
        }

        test("a repeated queue cannot pair one profile with itself") {
            queue(alice)
            queue(alice)
            shouldThrow<IllegalArgumentException> {
                pairing.queue("other-fd", alice, "Alice", "Play", deck, assigned::add)
            }
            assigned shouldBe emptyList()
            queue(bob)
            assigned.map { it.yourSeat } shouldBe listOf(1, 2)
        }

        test("event mismatch leaves the first player queued for the matching event") {
            queue(alice)
            shouldThrow<IllegalArgumentException> { queue(bob, event = "Other") }
            queue(bob)
            assigned.size shouldBe 2
        }

        test("leaving the queue or an unclaimed room allows a fresh pairing") {
            queue(alice)
            pairing.leave("fd-alice")
            queue(bob)
            assigned shouldBe emptyList()
            queue(alice)
            pairing.leave("fd-alice")
            configs.get("match-1").shouldBeNull()
            pair()
            assigned.takeLast(2).map { it.match.matchId } shouldBe listOf("match-2", "match-2")
        }

        test("admission rejects unknown match outsider swapped seat and human Familiar without consuming reservations") {
            pair()
            shouldThrow<IllegalArgumentException> { pairing.claim("unknown", alice, 1, false, "md-a") }
            shouldThrow<IllegalArgumentException> { pairing.claim("match-1", PlayerId("outsider"), 1, false, "md-a") }
            shouldThrow<IllegalArgumentException> { pairing.claim("match-1", alice, 2, false, "md-a") }
            shouldThrow<IllegalArgumentException> { pairing.claim("match-1", alice, 2, true, "md-a") }
            val first = pairing.claim("match-1", alice, 0, false, "md-a")
            val second = pairing.claim("match-1", bob, 2, false, "md-b")
            assertSoftly {
                first.seatId.value shouldBe 1
                second.seatId.value shouldBe 2
                first.players shouldBe second.players
                first.humanVsHuman shouldBe true
                second.familiar shouldBe false
            }
        }

        test("a second connection cannot replace an occupied human seat") {
            pair()
            val claim = pairing.claim("match-1", alice, 1, false, "md-a")
            shouldThrow<IllegalArgumentException> { pairing.claim("match-1", alice, 1, false, "duplicate") }
            pairing.claim("match-1", alice, 1, false, "md-a") shouldBe claim
            pairing.claim("match-1", bob, 2, false, "md-b").seatId.value shouldBe 2
        }

        test("bot rooms admit only the owning profile and its Familiar companion") {
            pairing.registerBot(MatchInfo("bot", "localhost", 30003), alice, "Alice")
            shouldThrow<IllegalArgumentException> { pairing.claim("bot", bob, 2, true, "md-b") }
            val player = pairing.claim("bot", alice, 1, false, "md-a")
            val familiar = pairing.claim("bot", alice, 2, true, "md-f")
            assertSoftly {
                player.playerId shouldBe "alice"
                player.humanVsHuman shouldBe false
                familiar.playerId shouldBe "alice_Familiar"
                familiar.familiar shouldBe true
            }
        }

        test("active rooms block another match until the matching room is completed") {
            pair()
            shouldThrow<IllegalArgumentException> { queue(alice) }
            pairing.complete("unrelated")
            shouldThrow<IllegalArgumentException> { queue(alice) }
            pairing.complete("match-1")
            configs.get("match-1").shouldBeNull()
            pair()
            assigned.takeLast(2).map { it.match.matchId } shouldBe listOf("match-2", "match-2")
        }

        test("unclaimed reservations expire but connected games survive queue departure and expiry") {
            pair()
            clock = 300_000
            shouldThrow<IllegalArgumentException> { pairing.claim("match-1", alice, 1, false, "md-a") }
            configs.get("match-1").shouldBeNull()
            pair()
            pairing.claim("match-2", alice, 1, false, "md-a")
            pairing.leave("fd-alice")
            clock = 900_000
            pairing.claim("match-2", bob, 2, false, "md-b").matchId shouldBe "match-2"
        }
    })
