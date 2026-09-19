package leyline.board.mana

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.BoardTest
import leyline.testkit.ofType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType

/**
 * `ManaColor.AnyColor` — "add one mana of any color" sources previously
 * tagged their production as `Generic` (`ManaColorMapping.fromProduced`'s
 * "ANY" branch), the same tag used for a spell's generic mana cost slot.
 * The client's own `AnyColorSelection` class expects the dedicated
 * `AnyColor` tag and expands it into a real WUBRG picker client-side; the
 * mistag also happened to satisfy this engine's own auto-tap/payability
 * source-color wildcard checks by accident (they only special-cased
 * `Generic`), so fixing the tag alone would have broken those checks unless
 * they also recognize `AnyColor`. See ISSUES.md I43.
 */
class CreatureManaTest :
    BoardTest({
        test("Birds of Paradise's mana ability tags AnyColor, not Generic") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Birds of Paradise", human, ZoneType.Battlefield)
                }

            val manaAction =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .ofType(ActionType.ActivateMana)
                    .single()

            assertSoftly {
                manaAction.manaPaymentOptionsList shouldHaveSize 1
                val mana =
                    manaAction.manaPaymentOptionsList
                        .single()
                        .manaList
                        .single()
                mana.color shouldBe ManaColor.AnyColor
                mana.specsList.map { it.type } shouldBe listOf(ManaSpecType.Predictive)
            }
        }

        test("Birds of Paradise can still auto-tap to pay a colored cost") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Birds of Paradise", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }

            val cast =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .ofType(ActionType.Cast)
            cast.shouldHaveSize(1)

            val a = cast[0]
            assertSoftly {
                a.hasAutoTapSolution().shouldBeTrue()
                a.autoTapSolution.autoTapActionsCount shouldBeGreaterThan 0
                // The solution echoes each SOURCE's own color (AnyColor for
                // Birds, Green for the Forest), not the requirement each
                // satisfies — the client resolves that, same as the
                // interactive picker. The solver picks Birds (untapped
                // first) for the colored G requirement and Forest for the
                // generic 1 — confirms canPayColor/canPayRequirement now
                // recognize AnyColor as a wildcard instead of only Generic
                // (see ManaColorMapping.kt's "ANY" fix and the four call
                // sites updated alongside it).
                a.autoTapSolution.autoTapActionsList
                    .flatMap { it.manaPaymentOption.manaList }
                    .map { it.color } shouldBe listOf(ManaColor.AnyColor, ManaColor.Green_afc9)
            }
        }

        test("Mox Amber exposes the legendary permanent's live color for manual mana") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Mox Amber", human, ZoneType.Battlefield)
                    addCard("Ragavan, Nimble Pilferer", human, ZoneType.Battlefield)
                }

            val mox =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Mox Amber" }
            val action =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .ofType(ActionType.ActivateMana)
                    .single { it.instanceId == board.instanceId(mox.id) }

            action.manaSelectionsList
                .single()
                .optionsList
                .map { it.selectedColor } shouldBe listOf(ManaColor.Red_afc9)
        }

        test("Reflecting Pool exposes the live colors its lands can produce for manual mana") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Reflecting Pool", human, ZoneType.Battlefield)
                    addCard("Breeding Pool", human, ZoneType.Battlefield)
                    addCard("Stomping Ground", human, ZoneType.Battlefield)
                    addCard("Steam Vents", human, ZoneType.Battlefield)
                }

            val pool =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single { it.name == "Reflecting Pool" }
            val action =
                ActionMapper
                    .buildFromSnapshot(1, GsmSnapshot.capture(board.game, board.bridge, "test", 0), board.bridge)
                    .ofType(ActionType.ActivateMana)
                    .single { it.instanceId == board.instanceId(pool.id) }

            action.manaSelectionsList
                .single()
                .optionsList
                .map { it.selectedColor }
                .shouldContainExactlyInAnyOrder(
                    ManaColor.Green_afc9,
                    ManaColor.Blue_afc9,
                    ManaColor.Red_afc9,
                )
        }
    })
