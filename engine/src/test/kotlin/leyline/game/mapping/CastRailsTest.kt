package leyline.game.mapping

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.data.KeywordAbilityIds
import leyline.game.snapshot.AltCostBinding
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Pins for the [CastRails] table. Smoke-level row coverage — verifies each
 * row's [resolveAltGrpId] dispatch agrees with the lookup mode it declares.
 * The richer SA-predicate contract is exercised by the per-keyword
 * conformance tests (KeywordCastShapeIntegrationTest, PlotActionTest,
 * ForetellActionTest, DisturbActionTest, EscapeActionTest, SneakActionTest).
 */
class CastRailsTest :
    FunSpec({
        tags(UnitTag)

        val warpRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.WARP,
                abilityGrpId = 9001,
                manaCost = listOf(ManaColor.Red_afc9 to 1, ManaColor.Generic to 2),
            )
        val foretellRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.FORETELL,
                abilityGrpId = 8001,
                // Cast cost on the bound row — the hand SA pays the constant {2} action
                // cost, so cost-aware lookup misses on purpose.
                manaCost = listOf(ManaColor.Red_afc9 to 1),
            )
        val escapeRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.ESCAPE,
                abilityGrpId = 7001,
                manaCost = listOf(ManaColor.Generic to 3),
            )
        val flashbackRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.FLASHBACK,
                abilityGrpId = 7101,
                manaCost = listOf(ManaColor.Generic to 2, ManaColor.Blue_afc9 to 1),
            )
        val harmonizeRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.HARMONIZE,
                abilityGrpId = 7301,
                manaCost = listOf(ManaColor.Generic to 4, ManaColor.Blue_afc9 to 1),
            )
        val disturbRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.DISTURB,
                abilityGrpId = 7201,
                manaCost = listOf(ManaColor.Generic to 4, ManaColor.Blue_afc9 to 1),
            )
        val plotRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.PLOT,
                abilityGrpId = 6001,
                manaCost = listOf(ManaColor.Red_afc9 to 1, ManaColor.Generic to 1),
            )
        val cleaveRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.CLEAVE,
                abilityGrpId = 6101,
                manaCost = listOf(ManaColor.Generic to 1, ManaColor.Blue_afc9 to 2),
            )
        val overloadRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.OVERLOAD,
                abilityGrpId = 6201,
                manaCost = listOf(ManaColor.Generic to 3, ManaColor.Red_afc9 to 3),
            )
        val spectacleRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.SPECTACLE,
                abilityGrpId = 6401,
                manaCost = listOf(ManaColor.Generic to 1, ManaColor.Black_afc9 to 2),
            )
        val surgeRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.SURGE,
                abilityGrpId = 6501,
                manaCost = listOf(ManaColor.Generic to 3, ManaColor.Blue_afc9 to 2),
            )
        val jumpStartRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.JUMP_START,
                abilityGrpId = 170,
                manaCost = emptyList(),
            )
        val impendingRow =
            AltCostBinding(
                keywordBaseId = KeywordAbilityIds.IMPENDING,
                abilityGrpId = 6301,
                manaCost = listOf(ManaColor.Generic to 2, ManaColor.White_afc9 to 2),
            )
        val altCosts =
            listOf(
                warpRow,
                foretellRow,
                escapeRow,
                flashbackRow,
                harmonizeRow,
                disturbRow,
                plotRow,
                cleaveRow,
                overloadRow,
                spectacleRow,
                surgeRow,
                jumpStartRow,
                impendingRow,
            )

        test("Plot exile rail returns universal-149 regardless of altCosts contents") {
            val plotExile = CastRails.fromExile.first { it.kind == AltCostKind.PLOT }
            resolveAltGrpId(plotExile, altCosts, payCostPairs = emptyList()) shouldBe 149
            resolveAltGrpId(plotExile, emptyList(), payCostPairs = emptyList()) shouldBe 149
        }

        test("Foretell exile rail is cost-agnostic — returns row even when payCostPairs disagree") {
            val foretellExile = CastRails.fromExile.first { it.kind == AltCostKind.FORETELL }
            // Action SA pays {2} (different from row's {R}), but cost-agnostic still hits.
            val payAction = listOf(ManaColor.Generic to 2)
            resolveAltGrpId(foretellExile, altCosts, payAction) shouldBe foretellRow.abilityGrpId
        }

        test("Flashback / Disturb / Escape / Harmonize graveyard rails are cost-agnostic per current behavior") {
            val flashback = CastRails.fromGraveyard.first { it.kind == AltCostKind.FLASHBACK }
            val disturb = CastRails.fromGraveyard.first { it.kind == AltCostKind.DISTURB }
            val escape = CastRails.fromGraveyard.first { it.kind == AltCostKind.ESCAPE }
            val jumpStart = CastRails.fromGraveyard.first { it.kind == AltCostKind.JUMP_START }
            val harmonize = CastRails.fromGraveyard.first { it.kind == AltCostKind.HARMONIZE }
            // Empty payCostPairs still resolves — graveyard rails default to cost-agnostic.
            assertSoftly {
                resolveAltGrpId(flashback, altCosts, payCostPairs = emptyList()) shouldBe flashbackRow.abilityGrpId
                resolveAltGrpId(disturb, altCosts, payCostPairs = emptyList()) shouldBe disturbRow.abilityGrpId
                resolveAltGrpId(escape, altCosts, payCostPairs = emptyList()) shouldBe escapeRow.abilityGrpId
                resolveAltGrpId(jumpStart, altCosts, payCostPairs = emptyList()) shouldBe jumpStartRow.abilityGrpId
                resolveAltGrpId(harmonize, altCosts, payCostPairs = emptyList()) shouldBe harmonizeRow.abilityGrpId
            }
        }

        test("graveyard rails declare client-visible source projection") {
            val flashback = CastRails.fromGraveyard.first { it.kind == AltCostKind.FLASHBACK }
            val disturb = CastRails.fromGraveyard.first { it.kind == AltCostKind.DISTURB }
            val escape = CastRails.fromGraveyard.first { it.kind == AltCostKind.ESCAPE }

            assertSoftly {
                flashback.emitAlternativeSourceZcid shouldBe true
                flashback.abilityGrpIdMode shouldBe AbilityGrpIdMode.None
                flashback.omitGrpIdAndFacetId shouldBe false
                flashback.grpIdMode shouldBe ZoneCastGrpIdMode.Source

                disturb.emitAlternativeSourceZcid shouldBe true
                disturb.echoAlternativeOnMana shouldBe true
                disturb.omitGrpIdAndFacetId shouldBe false
                disturb.grpIdMode shouldBe ZoneCastGrpIdMode.OtherSide

                escape.emitAlternativeSourceZcid shouldBe false
                escape.omitGrpIdAndFacetId shouldBe true
            }
        }

        test("Warp hand rail is cost-aware — matches on mana cost multiset") {
            val warp = CastRails.handWithAltCost.first { it.kind == AltCostKind.WARP }
            val matching = listOf(ManaColor.Red_afc9 to 1, ManaColor.Generic to 2)
            resolveAltGrpId(warp, altCosts, matching) shouldBe warpRow.abilityGrpId
            // Cost mismatch → row not selected.
            val mismatched = listOf(ManaColor.Blue_afc9 to 1)
            resolveAltGrpId(warp, altCosts, mismatched) shouldBe 0
        }

        test("Foretell hand rail is cost-agnostic — handles the {2} action vs cast-cost mismatch") {
            val foretellHand = CastRails.handWithAltCost.first { it.kind == AltCostKind.FORETELL }
            val payAction = listOf(ManaColor.Generic to 2)
            resolveAltGrpId(foretellHand, altCosts, payAction) shouldBe foretellRow.abilityGrpId
        }

        test("Plot hand rail is cost-aware") {
            val plotHand = CastRails.handWithAltCost.first { it.kind == AltCostKind.PLOT }
            val matching = listOf(ManaColor.Red_afc9 to 1, ManaColor.Generic to 1)
            resolveAltGrpId(plotHand, altCosts, matching) shouldBe plotRow.abilityGrpId
        }

        test("Cleave hand rail is cost-aware") {
            val cleaveHand = CastRails.handWithAltCost.first { it.kind == AltCostKind.CLEAVE }
            val matching = listOf(ManaColor.Generic to 1, ManaColor.Blue_afc9 to 2)
            resolveAltGrpId(cleaveHand, altCosts, matching) shouldBe cleaveRow.abilityGrpId
        }

        test("Overload hand rail is cost-agnostic") {
            val overloadHand = CastRails.handWithAltCost.first { it.kind == AltCostKind.OVERLOAD }
            val matching = listOf(ManaColor.Generic to 3, ManaColor.Red_afc9 to 3)
            val reduced = listOf(ManaColor.Generic to 2, ManaColor.Red_afc9 to 3)
            assertSoftly {
                resolveAltGrpId(overloadHand, altCosts, matching) shouldBe overloadRow.abilityGrpId
                resolveAltGrpId(overloadHand, altCosts, reduced) shouldBe overloadRow.abilityGrpId
            }
        }

        test("Spectacle hand rail is cost-agnostic") {
            val spectacleHand = CastRails.handWithAltCost.first { it.kind == AltCostKind.SPECTACLE }
            val matching = listOf(ManaColor.Generic to 1, ManaColor.Black_afc9 to 2)
            val reduced = listOf(ManaColor.Black_afc9 to 2)
            assertSoftly {
                resolveAltGrpId(spectacleHand, altCosts, matching) shouldBe spectacleRow.abilityGrpId
                resolveAltGrpId(spectacleHand, altCosts, reduced) shouldBe spectacleRow.abilityGrpId
            }
        }

        test("Surge hand rail is cost-agnostic") {
            val surgeHand = CastRails.handWithAltCost.first { it.kind == AltCostKind.SURGE }
            val matching = listOf(ManaColor.Generic to 3, ManaColor.Blue_afc9 to 2)
            val reduced = listOf(ManaColor.Generic to 2, ManaColor.Blue_afc9 to 2)
            assertSoftly {
                resolveAltGrpId(surgeHand, altCosts, matching) shouldBe surgeRow.abilityGrpId
                resolveAltGrpId(surgeHand, altCosts, reduced) shouldBe surgeRow.abilityGrpId
            }
        }

        test("Impending hand rail is cost-agnostic") {
            val impendingHand = CastRails.handWithAltCost.first { it.kind == AltCostKind.IMPENDING }
            val matching = listOf(ManaColor.Generic to 2, ManaColor.White_afc9 to 2)
            val reduced = listOf(ManaColor.Generic to 1, ManaColor.White_afc9 to 2)
            assertSoftly {
                resolveAltGrpId(impendingHand, altCosts, matching) shouldBe impendingRow.abilityGrpId
                resolveAltGrpId(impendingHand, altCosts, reduced) shouldBe impendingRow.abilityGrpId
            }
        }

        test("Rails inventory covers AltCostKind values without overlap loss") {
            assertSoftly {
                CastRails.fromExile.map { it.kind } shouldContainExactly
                    listOf(AltCostKind.PLOT, AltCostKind.FORETELL, AltCostKind.PARADIGM, AltCostKind.AIRBEND)
                CastRails.fromGraveyard.map { it.kind } shouldContainExactly
                    listOf(
                        AltCostKind.FLASHBACK,
                        AltCostKind.DISTURB,
                        AltCostKind.ESCAPE,
                        AltCostKind.JUMP_START,
                        AltCostKind.RETRACE,
                        AltCostKind.HARMONIZE,
                    )
                CastRails.handWithAltCost.map { it.kind } shouldContainExactly
                    listOf(
                        AltCostKind.GENERIC,
                        AltCostKind.WARP,
                        AltCostKind.SNEAK,
                        AltCostKind.WEB_SLINGING,
                        AltCostKind.MUTATE,
                        AltCostKind.PLOT,
                        AltCostKind.FORETELL,
                        AltCostKind.DISGUISE,
                        AltCostKind.CLEAVE,
                        AltCostKind.OVERLOAD,
                        AltCostKind.EVOKE,
                        AltCostKind.BLITZ,
                        AltCostKind.DASH,
                        AltCostKind.EMERGE,
                        AltCostKind.SPECTACLE,
                        AltCostKind.SURGE,
                        AltCostKind.IMPENDING,
                    )
            }
        }

        test("Universal-149 source is explicit on Plot and Paradigm exile legs") {
            val universal149Rails =
                CastRails.all.filter { rail ->
                    rail is ZoneCastRail && rail.altGrpIdSource is AltGrpIdSource.Universal149
                }
            universal149Rails.map { it.kind } shouldContainExactly listOf(AltCostKind.PLOT, AltCostKind.PARADIGM)
        }

        test("Every AltCostKind appears in at least one rail") {
            val kindsCovered = CastRails.all.map { it.kind }.toSortedSet()
            kindsCovered shouldContainExactly AltCostKind.entries.toSortedSet()
        }
    })
