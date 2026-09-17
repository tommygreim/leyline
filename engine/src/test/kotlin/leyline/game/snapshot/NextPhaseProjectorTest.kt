package leyline.game.snapshot

import forge.game.phase.PhaseType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag

/**
 * Pinned to the unmodified-client capture
 * (`runtime/reference-captures/20260916T051920Z-unmodified-standard-2026-09-16`).
 * Its 211 turnInfo objects contain 14 distinct phase -> nextPhase pairs; each case
 * below names how many times Arena sent it.
 */
class NextPhaseProjectorTest :
    FunSpec({

        tags(UnitTag)

        fun project(
            current: PhaseType?,
            skipDraw: Boolean = false,
            skipCombat: Boolean = false,
            skipDamageSteps: Boolean = false,
        ) = NextPhaseProjector.project(current, skipDraw, skipCombat, skipDamageSteps, phasesReversed = false)

        test("the ordinary turn walks phase by phase, as Arena does") {
            // Upkeep -> Draw (18x), Draw -> Main1 (18x), Main1 -> BeginCombat (48x),
            // BeginCombat -> DeclareAttack (20x), EndCombat -> Main2 (18x),
            // Main2 -> End (18x), End -> Cleanup (26x).
            assertSoftly {
                project(PhaseType.UPKEEP) shouldBe PhaseType.DRAW
                project(PhaseType.DRAW) shouldBe PhaseType.MAIN1
                project(PhaseType.MAIN1) shouldBe PhaseType.COMBAT_BEGIN
                project(PhaseType.COMBAT_BEGIN) shouldBe PhaseType.COMBAT_DECLARE_ATTACKERS
                project(PhaseType.COMBAT_END) shouldBe PhaseType.MAIN2
                project(PhaseType.MAIN2) shouldBe PhaseType.END_OF_TURN
                project(PhaseType.END_OF_TURN) shouldBe PhaseType.CLEANUP
            }
        }

        test("turn one skips the draw, so upkeep points at the first main phase") {
            // Arena: Upkeep -> Main1, 2x.
            project(PhaseType.UPKEEP, skipDraw = true) shouldBe PhaseType.MAIN1
        }

        test("declare attackers points past the damage steps when nobody attacked") {
            // Arena: DeclareAttack -> EndCombat 17x (no attackers), -> DeclareBlock 6x.
            assertSoftly {
                project(PhaseType.COMBAT_DECLARE_ATTACKERS, skipDamageSteps = true) shouldBe PhaseType.COMBAT_END
                project(PhaseType.COMBAT_DECLARE_ATTACKERS) shouldBe PhaseType.COMBAT_DECLARE_BLOCKERS
            }
        }

        test("blocks lead through first strike damage even with no first strikers") {
            // Arena never points DeclareBlock straight at CombatDamage: DeclareBlock ->
            // FirstStrikeDamage 6x, FirstStrikeDamage -> CombatDamage 5x,
            // CombatDamage -> EndCombat 6x.
            assertSoftly {
                project(PhaseType.COMBAT_DECLARE_BLOCKERS) shouldBe PhaseType.COMBAT_FIRST_STRIKE_DAMAGE
                project(PhaseType.COMBAT_FIRST_STRIKE_DAMAGE) shouldBe PhaseType.COMBAT_DAMAGE
                project(PhaseType.COMBAT_DAMAGE) shouldBe PhaseType.COMBAT_END
            }
        }

        test("a player skipping combat goes from the first main phase to end of combat") {
            // Forge skips COMBAT_BEGIN and COMBAT_DECLARE_ATTACKERS outright; the damage
            // steps then fall away with no combat, leaving COMBAT_END as the first
            // phase that actually happens.
            project(PhaseType.MAIN1, skipCombat = true, skipDamageSteps = true) shouldBe PhaseType.COMBAT_END
        }

        test("no phase is projected before the game has one") {
            // Arena's three pre-game turnInfo objects carry neither field.
            project(null) shouldBe null
        }

        test("cleanup wraps to the next turn's untap") {
            project(PhaseType.CLEANUP) shouldBe PhaseType.UNTAP
        }
    })
