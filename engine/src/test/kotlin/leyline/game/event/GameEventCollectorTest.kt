package leyline.game.event

import forge.card.MagicColor
import forge.game.GameEntityCounterTable
import forge.game.ability.AbilityKey
import forge.game.ability.AbilityUtils
import forge.game.card.CardView
import forge.game.card.CounterEnumType
import forge.game.event.*
import forge.game.player.PlayerView
import forge.game.spellability.SpellAbilityStackInstance
import forge.game.spellability.SpellAbilityView
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.DestructionCause
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.testkit.BoardTest
import leyline.testkit.aiPlayer
import leyline.testkit.humanPlayer
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Tests for [leyline.game.event.GameEventCollector] — verifies that Forge engine events are
 * captured and converted to the correct [leyline.game.event.GameEvent] variants.
 *
 * Uses startWithBoard{} — fires events directly via game.fireEvent(),
 * then asserts on collector.closeFrame(). ~0.01s per test.
 */
class GameEventCollectorTest :
    BoardTest({

        // -- infrastructure --

        test("collector is wired after wrapGame") {
            val (b, _, _) = startWithBoard { _, _, _ -> }
            val collector = b.eventCollector.shouldNotBeNull()

            collector.closeFrame().events shouldBe emptyList()
        }

        test("drain events returns and clears") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!

            // startWithBoard fires some events during setup
            collector.closeFrame()

            // Fire a simple event
            game.fireEvent(GameEventShuffle(game.humanPlayer))
            val events1 = collector.closeFrame().events
            events1.shouldNotBeEmpty()

            val events2 = collector.closeFrame().events
            events2.shouldBeEmpty()
        }

        // -- LandPlayed --

        test("land played event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val land =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first { it.isLand }
            game.fireEvent(GameEventLandPlayed(PlayerView.get(game.humanPlayer), CardView.get(land)))

            val events = collector.closeFrame().events
            val lp = events.filterIsInstance<GameEvent.LandPlayed>()
            assertSoftly {
                lp.size shouldBe 1
                lp[0].cardId shouldBe ForgeCardId(land.id)
                lp[0].seatId shouldBe SeatId(1)
            }
        }

        // -- SpellCast --

        test("spell cast event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val spell =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(GameEventSpellAbilityCast(spell.firstSpellAbility, null, 0))

            val events = collector.closeFrame().events
            val sc = events.filterIsInstance<GameEvent.SpellCast>()
            assertSoftly {
                sc.size shouldBe 1
                sc[0].cardId shouldBe ForgeCardId(spell.id)
                sc[0].seatId shouldBe SeatId(1)
            }
        }

        test("spell cast payments retain printed and generated mana ability identities") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                    addCard("Llanowar Elves", human, ZoneType.Battlefield)
                    addCard("Bayou", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()
            val spell = game.humanPlayer.hand.card("Lightning Bolt")
            val sources = listOf("Llanowar Elves", "Bayou").map { game.humanPlayer.battlefield.card(it) }
            val payments =
                sources.map { source ->
                    val ability = source.manaAbilities.single { it.manaPart.origProduced == "G" }
                    GameEventSpellAbilityCast.ManaPaymentInfo(source.id, MagicColor.GREEN, ability.definitionId)
                }
            game.fireEvent(GameEventSpellAbilityCast(SpellAbilityView.get(spell.firstSpellAbility), null, 0, null, payments))
            val cast =
                collector
                    .closeFrame()
                    .events
                    .filterIsInstance<GameEvent.SpellCast>()
                    .single()
            cast.manaPayments.map { it.sourceCardId to it.abilityGrpId } shouldBe sources.map { ForgeCardId(it.id) to 1005 }
        }

        test("ability cast identity comes from the emitted ability rather than the stack top") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Goblin Fireslinger", human, ZoneType.Battlefield)
                    addCard("Goblin Cratermaker", human, ZoneType.Battlefield)
                }
            val source = game.humanPlayer.battlefield.card("Goblin Fireslinger")
            val sourceAbility = source.getAllSpellAbilities().first { it.isAbility && !it.isManaAbility }
            val unrelated = game.humanPlayer.battlefield.card("Goblin Cratermaker")
            val unrelatedAbility = unrelated.getAllSpellAbilities().first { it.isAbility && !it.isManaAbility }
            sourceAbility.activatingPlayer = game.humanPlayer
            unrelatedAbility.activatingPlayer = game.humanPlayer
            unrelatedAbility.targetRestrictions = null
            game.stack.addAndUnfreeze(unrelatedAbility)
            val collector = b.eventCollector!!
            collector.closeFrame()

            val expected = b.resolveAbilityIdentity(source, sourceAbility).shouldNotBeNull()
            game.fireEvent(GameEventSpellAbilityCast(sourceAbility, SpellAbilityStackInstance(sourceAbility), 0))

            val cast =
                collector
                    .closeFrame()
                    .events
                    .filterIsInstance<GameEvent.SpellCast>()
                    .single()
            assertSoftly {
                cast.abilityIdentity shouldBe expected
                cast.abilityGrpId shouldBe expected.abilityGrpId
            }
        }

        // -- SpellResolved --

        test("spell resolved event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val spell =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, false))

            val events = collector.closeFrame().events
            val sr = events.filterIsInstance<GameEvent.SpellResolved>()
            assertSoftly {
                sr.size shouldBe 1
                sr[0].cardId shouldBe ForgeCardId(spell.id)
                sr[0].hasFizzled.shouldBeFalse()
            }
        }

        test("spell resolved fizzled") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val spell =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(GameEventSpellResolved(spell.firstSpellAbility, true))

            val sr = collector.closeFrame().events.filterIsInstance<GameEvent.SpellResolved>()
            sr.size shouldBe 1
            sr[0].hasFizzled.shouldBeTrue()
        }

        // -- CardChangeZone: specific variants --

        test("BF to GY via zone change emits ZoneChanged (CardDestroyed comes from dedicated event)") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            game.fireEvent(GameEventCardChangeZone(creature, bf, gy))

            val events = collector.closeFrame().events
            // BF→GY via zone change now produces ZoneChanged (not CardDestroyed)
            val zoneChanges = events.filterIsInstance<GameEvent.ZoneChanged>()
            zoneChanges.size shouldBe 1
            zoneChanges[0].cardId shouldBe ForgeCardId(creature.id)
        }

        test("GameEventCardDestroyed emits CardDestroyed with source") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val bolt =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            game.fireEvent(GameEventCardDestroyed(creature, bolt))

            val events = collector.closeFrame().events
            val destroyed = events.filterIsInstance<GameEvent.CardDestroyed>()
            assertSoftly {
                destroyed.size shouldBe 1
                destroyed[0].cardId shouldBe ForgeCardId(creature.id)
                destroyed[0].seatId shouldBe SeatId(1)
                destroyed[0].sourceCardId shouldBe ForgeCardId(bolt.id)
            }
        }

        test("BF to Hand emits CardBounced") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
            val hand = game.humanPlayer.getZone(ZoneType.Hand)
            game.fireEvent(GameEventCardChangeZone(creature, bf, hand))

            val bounced = collector.closeFrame().events.filterIsInstance<GameEvent.CardBounced>()
            bounced.size shouldBe 1
            bounced[0].cardId shouldBe ForgeCardId(creature.id)
        }

        test("any to Exile emits CardExiled") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val bf = game.humanPlayer.getZone(ZoneType.Battlefield)
            val exile = game.humanPlayer.getZone(ZoneType.Exile)
            game.fireEvent(GameEventCardChangeZone(creature, bf, exile))

            val exiled = collector.closeFrame().events.filterIsInstance<GameEvent.CardExiled>()
            exiled.size shouldBe 1
        }

        test("Hand to GY emits CardDiscarded") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Lightning Bolt", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val card =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .first()
            val hand = game.humanPlayer.getZone(ZoneType.Hand)
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            game.fireEvent(GameEventCardChangeZone(card, hand, gy))

            val discarded = collector.closeFrame().events.filterIsInstance<GameEvent.CardDiscarded>()
            discarded.size shouldBe 1
        }

        test("Library to GY emits CardMilled") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Library)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val card =
                game.humanPlayer
                    .getZone(ZoneType.Library)
                    .cards
                    .first()
            val lib = game.humanPlayer.getZone(ZoneType.Library)
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            game.fireEvent(GameEventCardChangeZone(card, lib, gy))

            val milled = collector.closeFrame().events.filterIsInstance<GameEvent.CardMilled>()
            milled.size shouldBe 1
        }

        test("generic fallback emits ZoneChanged") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Graveyard)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val card =
                game.humanPlayer
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .first()
            val gy = game.humanPlayer.getZone(ZoneType.Graveyard)
            val lib = game.humanPlayer.getZone(ZoneType.Library)
            game.fireEvent(GameEventCardChangeZone(card, gy, lib))

            val zc = collector.closeFrame().events.filterIsInstance<GameEvent.ZoneChanged>()
            assertSoftly {
                zc.size shouldBe 1
                zc[0].from shouldBe Zone.Graveyard
                zc[0].to shouldBe Zone.Library
            }
        }

        test("every Forge zone event contributes one ordered ZoneMove with frozen cause") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Goblin Fireslinger", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()
            val source =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val moved =
                game.humanPlayer
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val cause = source.spellAbilities.first().also { it.activatingPlayer = game.humanPlayer }

            game.action.moveToStack(moved, cause)
            game.action.exile(moved, cause, AbilityKey.newMap())

            val moves = collector.closeFrame().zoneMoves
            assertSoftly {
                moves.map { it.order } shouldContainExactly listOf(0, 1)
                moves.map { it.cardId } shouldContainExactly listOf(ForgeCardId(moved.id), ForgeCardId(moved.id))
                moves.map { it.from } shouldContainExactly listOf(Zone.Hand, Zone.Stack)
                moves.map { it.to } shouldContainExactly listOf(Zone.Stack, Zone.Exile)
                moves.map { it.cause?.sourceCardId } shouldContainExactly
                    listOf(ForgeCardId(source.id), ForgeCardId(source.id))
                moves.map { it.cause?.abilityForgeId } shouldContainExactly listOf(cause.id, cause.id)
            }
        }

        test("legacy three-argument Forge zone event records an unknown-cause ZoneMove") {
            val (b, game, _) =
                startWithBoard { _, human, _ -> addCard("Grizzly Bears", human, ZoneType.Battlefield) }
            val collector = b.eventCollector!!
            collector.closeFrame()
            val card =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()

            game.fireEvent(
                GameEventCardChangeZone(
                    card,
                    game.humanPlayer.getZone(ZoneType.Battlefield),
                    game.humanPlayer.getZone(ZoneType.Graveyard),
                ),
            )

            val move = collector.closeFrame().zoneMoves.single()
            assertSoftly {
                move.cardId shouldBe ForgeCardId(card.id)
                move.cause shouldBe null
            }
        }

        // -- CardTapped --

        test("card tapped event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val land =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
            game.fireEvent(GameEventCardTapped(land, true))

            val tapped = collector.closeFrame().events.filterIsInstance<GameEvent.CardTapped>()
            assertSoftly {
                tapped.size shouldBe 1
                tapped[0].cardId shouldBe ForgeCardId(land.id)
                tapped[0].tapped.shouldBeTrue()
            }
        }

        test("card untapped event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val land =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first()
            game.fireEvent(GameEventCardTapped(land, false))

            val tapped = collector.closeFrame().events.filterIsInstance<GameEvent.CardTapped>()
            tapped.size shouldBe 1
            tapped[0].tapped.shouldBeFalse()
        }

        // -- Damage --

        test("damage dealt to card event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Serra Angel", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val cards =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isCreature }
            val source = cards[0]
            val target = cards[1]
            game.fireEvent(
                GameEventCardDamaged(CardView.get(target), CardView.get(source), 2, GameEventCardDamaged.DamageType.Normal, true),
            )

            val dmg = collector.closeFrame().events.filterIsInstance<GameEvent.DamageDealtToCard>()
            assertSoftly {
                dmg.size shouldBe 1
                dmg[0].sourceCardId shouldBe ForgeCardId(source.id)
                dmg[0].targetCardId shouldBe ForgeCardId(target.id)
                dmg[0].amount shouldBe 2
            }
        }

        test("regenerated card event preserves every Forge card id") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Serra Angel", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()
            val cards = listOf(game.humanPlayer.battlefield.card("Grizzly Bears"), game.humanPlayer.battlefield.card("Serra Angel"))

            game.fireEvent(GameEventCardRegenerated(cards.map { CardView.get(it) }))

            collector
                .closeFrame()
                .events
                .filterIsInstance<GameEvent.PermanentRegenerated>()
                .map { it.cardId } shouldBe
                cards.map { ForgeCardId(it.id) }
        }

        test("real regeneration ability emits event and shield prevents destruction") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Yavimaya Gnats", human, ZoneType.Battlefield)
                }
            val (b, game, _) = board
            val collector = b.eventCollector!!
            collector.closeFrame()
            val card = game.humanPlayer.battlefield.card("Yavimaya Gnats")
            val regeneration = card.getNonManaAbilities().first { it.api.toString() == "Regenerate" }
            regeneration.activatingPlayer = game.humanPlayer

            AbilityUtils.resolve(regeneration)
            collector.closeFrame().events.filterIsInstance<GameEvent.PermanentRegenerated>() shouldBe emptyList()
            card.shieldCount shouldBe 1

            card.addDamageAfterPrevention(1, card, null, false, GameEntityCounterTable())
            val instanceId = b.instanceId(card)
            val projected =
                board.snapshotDiff {
                    game.action.destroy(card, regeneration, true, AbilityKey.newMap())
                }
            projected.annotationsList
                .single { AnnotationType.PermanentRegenerated in it.typeList }
                .affectedIdsList shouldBe listOf(instanceId)

            card.isInPlay shouldBe true
            card.isTapped shouldBe true
            card.damage shouldBe 0
            card.shieldCount shouldBe 0
        }

        test("actual planeswalker damage reaches the card damage event stream") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Ugin, the Spirit Dragon", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()
            val source =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.isCreature }
            val target =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.isPlaneswalker }

            target.addDamageAfterPrevention(2, source, null, false, GameEntityCounterTable())

            val damage = collector.closeFrame().events.filterIsInstance<GameEvent.DamageDealtToCard>()
            assertSoftly {
                damage.size shouldBe 1
                damage.single().sourceCardId shouldBe ForgeCardId(source.id)
                damage.single().targetCardId shouldBe ForgeCardId(target.id)
                damage.single().amount shouldBe 2
            }
        }

        test("damage dealt to player event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            game.fireEvent(GameEventPlayerDamaged(PlayerView.get(game.humanPlayer), CardView.get(creature), 3, true, false))

            val dmg = collector.closeFrame().events.filterIsInstance<GameEvent.DamageDealtToPlayer>()
            assertSoftly {
                dmg.size shouldBe 1
                dmg[0].sourceCardId shouldBe ForgeCardId(creature.id)
                dmg[0].targetSeatId shouldBe SeatId(1)
                dmg[0].amount shouldBe 3
                dmg[0].sourceKind shouldBe DamageSourceKind.Combat
                dmg[0].changesLife shouldBe true
            }
        }

        test("infect damage is not classified as a life change") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()
            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }

            game.fireEvent(GameEventPlayerDamaged(PlayerView.get(game.humanPlayer), CardView.get(creature), 3, true, true))

            collector
                .closeFrame()
                .events
                .filterIsInstance<GameEvent.DamageDealtToPlayer>()
                .single()
                .changesLife shouldBe false
        }

        // -- LifeChanged --

        test("life changed event") {
            val (b, game, _) = startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.closeFrame()

            game.fireEvent(GameEventPlayerLivesChanged(game.humanPlayer, 20, 17))

            val lc = collector.closeFrame().events.filterIsInstance<GameEvent.LifeChanged>()
            assertSoftly {
                lc.size shouldBe 1
                lc[0].seatId shouldBe SeatId(1)
                lc[0].oldLife shouldBe 20
                lc[0].newLife shouldBe 17
            }
        }

        // -- CardSacrificed --

        test("card sacrificed event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            game.fireEvent(GameEventCardSacrificed(CardView.get(creature)))

            val sac = collector.closeFrame().events.filterIsInstance<GameEvent.CardSacrificed>()
            assertSoftly {
                sac.size shouldBe 1
                sac[0].cardId shouldBe ForgeCardId(creature.id)
                sac[0].seatId shouldBe SeatId(1)
            }
        }

        // -- Attachment --

        test("card attached event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    addCard("Pacifism", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val cards =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .toList()
            val aura = cards.first { !it.isCreature }
            val creature = cards.first { it.isCreature }
            game.fireEvent(GameEventCardAttachment(aura, null, creature))

            val attached = collector.closeFrame().events.filterIsInstance<GameEvent.CardAttached>()
            assertSoftly {
                attached.size shouldBe 1
                attached[0].cardId shouldBe ForgeCardId(aura.id)
                attached[0].targetCardId shouldBe ForgeCardId(creature.id)
            }
        }

        test("card detached event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Pacifism", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val aura =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { !it.isCreature }
            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            game.fireEvent(GameEventCardAttachment(aura, creature, null))

            val detached = collector.closeFrame().events.filterIsInstance<GameEvent.CardDetached>()
            detached.size shouldBe 1
            detached[0].cardId shouldBe ForgeCardId(aura.id)
        }

        // -- Counters --

        test("counters changed event") {
            val (b, game, _) =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val collector = b.eventCollector!!
            collector.closeFrame()

            val creature =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            game.fireEvent(GameEventCardCounters(creature, CounterEnumType.P1P1, 0, 2))

            val cc = collector.closeFrame().events.filterIsInstance<GameEvent.CountersChanged>()
            assertSoftly {
                cc.size shouldBe 1
                cc[0].cardId shouldBe ForgeCardId(creature.id)
                cc[0].counterType shouldBe "+1/+1"
                cc[0].oldCount shouldBe 0
                cc[0].newCount shouldBe 2
            }
        }

        // P/T deltas and DFC backside flips are now synthesized from the
        // prev/cur snap delta in [leyline.game.event.SnapDeltaSynthesizer] —
        // see SnapDeltaSynthesizerTest.

        // -- Shuffle --

        test("library shuffled event") {
            val (b, game, _) = startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.closeFrame()

            game.fireEvent(GameEventShuffle(game.humanPlayer))

            val sh = collector.closeFrame().events.filterIsInstance<GameEvent.LibraryShuffled>()
            sh.size shouldBe 1
            sh[0].seatId shouldBe SeatId(1)
        }

        // -- CombatEnded --

        test("combat ended event") {
            val (b, game, _) = startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.closeFrame()

            game.fireEvent(GameEventCombatEnded(listOf(), listOf()))

            val ce = collector.closeFrame().events.filterIsInstance<GameEvent.CombatEnded>()
            ce.size shouldBe 1
        }

        // -- AI player events get seatId=2 --

        test("AI player gets seatId 2") {
            val (b, game, _) = startWithBoard { _, _, _ -> }
            val collector = b.eventCollector!!
            collector.closeFrame()

            game.fireEvent(GameEventShuffle(game.aiPlayer))

            val sh = collector.closeFrame().events.filterIsInstance<GameEvent.LibraryShuffled>()
            sh.size shouldBe 1
            sh[0].seatId shouldBe SeatId(2)
        }
    })
