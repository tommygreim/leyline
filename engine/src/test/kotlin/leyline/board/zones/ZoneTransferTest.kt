package leyline.board.zones

import forge.game.ability.AbilityKey
import forge.game.card.CardView
import forge.game.card.CounterEnumType
import forge.game.event.GameEventCardDamaged
import forge.game.event.GameEventSpellResolved
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.FrameIdResolver
import leyline.game.mapping.ZoneIds
import leyline.game.seedDiffBaseline
import leyline.testkit.BoardTest
import leyline.testkit.annotation
import leyline.testkit.detailInt
import leyline.testkit.detailIntList
import leyline.testkit.findZoneTransfer
import leyline.testkit.gsm
import leyline.testkit.hasEnteredZoneThisTurn
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Zone transfer subsystem tests — every zone pair the Arena client expects.
 *
 * Covers: Destroy, Sacrifice, Exile, Bounce, Draw, Discard, Mill, Return,
 * SBA death paths, spell-forced discard, counter annotations, shuffle identity remapping.
 *
 * For PlayLand (Hand→BF), see LandManaTest.
 * For CastSpell/Resolve/Countered (Hand→Stack, Stack→BF/GY), see StackCastResolveTest.
 */
class ZoneTransferTest :
    BoardTest({

        // ===================================================================
        // Battlefield exits
        // ===================================================================

        test("Battlefield → Graveyard (Destroy)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val (gsm, newId) =
                board.transferCard("Grizzly Bears") { card, g ->
                    destroy(card, g)
                }
            val zt = checkNotNull(gsm.findZoneTransfer(newId))
            assertSoftly {
                zt.category shouldBe "Destroy"
                zt.zoneSrc shouldBe ZoneIds.BATTLEFIELD
                zt.zoneDest shouldBe ZoneIds.P1_GRAVEYARD
            }
            gsm.hasEnteredZoneThisTurn(newId).shouldBeTrue()
        }

        test("Battlefield → Graveyard (Sacrifice)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val (gsm, newId) =
                board.transferCard("Grizzly Bears") { card, g ->
                    g.fireEvent(forge.game.event.GameEventCardSacrificed(CardView.get(card)))
                    g.action.moveToGraveyard(card, null)
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Sacrifice"
        }

        test("Battlefield → Exile") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val (gsm, newId) =
                board.transferCard("Grizzly Bears") { card, g ->
                    exile(card, g)
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Exile"
        }

        test("Battlefield → Hand (Bounce)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val (gsm, newId) =
                board.transferCard("Grizzly Bears") { card, g ->
                    g.action.moveToHand(card, null)
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Bounce"
        }

        // ===================================================================
        // Library exits
        // ===================================================================

        test("Library → Hand (Draw)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Library)
                }
            val (gsm, newId) =
                board.transferCard("Forest") { _, g ->
                    g.humanPlayer.drawCard()
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Draw"
        }

        test("Library → Graveyard (Mill)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Library)
                }
            val (gsm, newId) =
                board.transferCard("Forest") { card, g ->
                    g.action.moveToGraveyard(card, null)
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Mill"
        }

        test("Library → Graveyard (Surveil) — category and affectorId") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Library)
                    addCard("Wary Thespian", human, ZoneType.Battlefield)
                }
            val source =
                board.game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.name == "Wary Thespian" }
            // Pre-register the source card's ability instanceId (normally done when ability goes on stack)
            val abilityForgeId = FrameIdResolver.stackAbilityForgeId(ForgeCardId(source.id))
            board.bridge.getOrAllocInstanceId(abilityForgeId)

            val (gsm, newId) =
                board.transferCard("Grizzly Bears") { card, g ->
                    // Order matters: moveToGraveyard fires CardMilled, then CardSurveiled
                    // overwrites. Matches production order in Player.surveil().
                    g.action.moveToGraveyard(card, null)
                    g.fireEvent(forge.game.event.GameEventCardSurveiled(card, source))
                }
            val zt = checkNotNull(gsm.findZoneTransfer(newId))
            zt.category shouldBe "Surveil"
            // TODO: affectorId should be source card's ability instance, but at board
            // level the detector can't resolve it (forgeCardId lookup mismatch after
            // realloc). Covered by session-tier surveil flow. Fix when adding more
            // affectorId tests to ZoneTransferTest.
        }

        test("Library → Exile") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Library)
                }
            val (gsm, newId) =
                board.transferCard("Forest") { card, g ->
                    exile(card, g)
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Exile"
        }

        // ===================================================================
        // Hand exits
        // ===================================================================

        test("Hand → Graveyard (Discard)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val (gsm, newId) =
                board.transferCard("Lightning Bolt") { card, g ->
                    g.humanPlayer.discard(card, null, false, AbilityKey.newMap())
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Discard"
            board.game.humanPlayer
                .getZone(ZoneType.Graveyard)
                .cards
                .any { it.name == "Lightning Bolt" }
                .shouldBeTrue()
        }

        test("Hand → Exile") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                }
            val (gsm, newId) =
                board.transferCard("Forest") { card, g ->
                    exile(card, g)
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Exile"
        }

        // ===================================================================
        // Return paths
        // ===================================================================

        test("Exile → Battlefield (Return + EnteredZoneThisTurn)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Exile)
                }
            val (gsm, newId) =
                board.transferCard("Grizzly Bears") { card, g ->
                    moveToBattlefield(card, g)
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Return"
            gsm.hasEnteredZoneThisTurn(newId).shouldBeTrue()
        }

        test("Graveyard → Battlefield (Return + EnteredZoneThisTurn)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Graveyard)
                }
            val (gsm, newId) =
                board.transferCard("Grizzly Bears") { card, g ->
                    moveToBattlefield(card, g)
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Return"
            gsm.hasEnteredZoneThisTurn(newId).shouldBeTrue()
        }

        test("Graveyard → Hand (Return)") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Graveyard)
                }
            val (gsm, newId) =
                board.transferCard("Grizzly Bears") { card, g ->
                    g.action.moveToHand(card, null)
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Return"
        }

        // ===================================================================
        // SBA death paths
        // ===================================================================

        test("SBA: zero toughness → Destroy") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val (gsm, newId) =
                board.transferCard("Grizzly Bears", checkSba = true) { card, _ ->
                    card.baseToughness = 0
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "Destroy"
        }

        test("SBA: lethal damage → SBA_Damage") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val (gsm, newId) =
                board.transferCard("Grizzly Bears", checkSba = true) { card, _ ->
                    card.damage = card.netToughness
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "SBA_Damage"
        }

        test("SBA: deathtouch damage → SBA_Deathtouch") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val (gsm, newId) =
                board.transferCard("Grizzly Bears", checkSba = true) { card, g ->
                    card.damage = 1
                    card.setHasBeenDealtDeathtouchDamage(true)
                    g.fireEvent(
                        GameEventCardDamaged(
                            CardView.get(card),
                            CardView.get(card),
                            1,
                            GameEventCardDamaged.DamageType.Deathtouch,
                            false,
                        ),
                    )
                }
            checkNotNull(gsm.findZoneTransfer(newId)).category shouldBe "SBA_Deathtouch"
        }

        // ===================================================================
        // Multi-card & contamination
        // ===================================================================

        test("multiple discards all produce Discard category") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                    addCard("Giant Growth", human, ZoneType.Hand)
                }
            val hand =
                board.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .toList()

            val gsm =
                board.snapshotDiff {
                    for (card in hand) board.game.humanPlayer.discard(card, null, false, AbilityKey.newMap())
                }

            for (card in hand) {
                checkNotNull(gsm.findZoneTransfer(board.instanceId(card.id))).category shouldBe "Discard"
            }
        }

        test("SpellResolved does not contaminate exile category") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Swords to Plowshares", human, ZoneType.Hand)
                }
            val creature =
                board.game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val spell =
                board.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()

            val gsm =
                board.snapshotDiff {
                    board.game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, false))
                    exile(creature, board.game)
                }

            checkNotNull(gsm.findZoneTransfer(board.instanceId(creature.id))).category shouldBe "Exile"
        }

        test("SpellResolved does not contaminate discard category") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Mind Rot", human, ZoneType.Hand)
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val hand =
                board.game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .toList()
            val spell = hand[0]
            val target = hand[1]

            val gsm =
                board.snapshotDiff {
                    board.game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, false))
                    board.game.humanPlayer.discard(target, null, false, AbilityKey.newMap())
                }

            checkNotNull(gsm.findZoneTransfer(board.instanceId(target.id))).category shouldBe "Discard"
        }

        // ===================================================================
        // Counter annotations
        // ===================================================================

        test("counter added — type and amount") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val creature =
                board.game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }

            val gsm =
                board.snapshotDiff {
                    creature.addCounterInternal(CounterEnumType.P1P1, 2, board.game.humanPlayer, true, null, AbilityKey.newMap())
                }

            val ann = gsm.annotation(AnnotationType.CounterAdded)
            ann.detailInt("counter_type") shouldBe 1 // P1P1 — client reads this detail as int32
            ann.detailInt("transaction_amount") shouldBe 2
        }

        test("counter removed — amount") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val creature =
                board.game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }

            creature.addCounterInternal(CounterEnumType.P1P1, 3, board.game.humanPlayer, true, null, AbilityKey.newMap())
            board.bridge.seedDiffBaseline(board.game, board.counter.currentGsId())
            board.bridge.closeFrame()

            val gsm =
                board.snapshotDiff {
                    creature.subtractCounter(CounterEnumType.P1P1, 2, board.game.humanPlayer)
                }

            gsm.annotation(AnnotationType.CounterRemoved).detailInt("transaction_amount") shouldBe 2
        }

        // ===================================================================
        // Shuffle
        // ===================================================================

        test("shuffle — remaps library identities") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Library)
                    addCard("Forest", human, ZoneType.Library)
                }

            val gsm = board.snapshotDiff { board.game.humanPlayer.shuffle(null) }

            val shuffle = gsm.annotation(AnnotationType.Shuffle)
            val oldIds = shuffle.detailIntList("OldIds")
            val newIds = shuffle.detailIntList("NewIds")
            val projectedLibraryIds =
                board.game.humanPlayer
                    .getZone(ZoneType.Library)
                    .cards
                    .map { board.bridge.instanceId(it.id) }
            assertSoftly {
                shuffle.affectorId shouldBe 0
                oldIds.size shouldBe 2
                newIds.size shouldBe 2
                oldIds.toSet().intersect(newIds.toSet()) shouldBe emptySet()
                projectedLibraryIds shouldBe newIds
            }
        }
    })
