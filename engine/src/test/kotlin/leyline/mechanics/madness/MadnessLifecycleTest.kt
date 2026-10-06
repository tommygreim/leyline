package leyline.mechanics.madness

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.SeatId
import leyline.game.bundle.InvariantCheck
import leyline.game.bundle.InvariantSelection
import leyline.testkit.MatchFlowHarness
import leyline.testkit.TestCardRegistry
import leyline.testkit.detail
import leyline.testkit.detailInt
import leyline.testkit.detailString
import leyline.testkit.hasDetail
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Three sub-cases from the spec:
 *  - **cast path**: discard via Tormenting Voice → Hand→Exile (Discard) →
 *    madness trigger → Exile→Stack (CastSpell) with persistent
 *    CastingTimeOption type=13 → Stack→GY (Resolve) with damage to opponent.
 *  - **decline path**: same setup but pass priority on the madness offer →
 *    Exile→GY (Put) with the ability resolving as "no cast".
 *  - **hardcast path**: cast the madness card from hand for its regular mana
 *    cost — no Exile detour, no CastingTimeOption.
 *
 * Card setup uses Tormenting Voice (1R, additional cost: discard 1, draw 2)
 * as the discard outlet driving the Madness trigger on Fiery Temper (1RR
 * instant, deal 3, Madness {R}).
 */
private val MADNESS_PUZZLE =
    """
    [metadata]
    Name:Madness — Tormenting Voice into Fiery Temper
    Goal:Discard Fiery Temper via Tormenting Voice, then cast it for madness.
    Turns:5
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Tormenting Voice;Fiery Temper
    humanbattlefield=Mountain;Mountain;Mountain;Mountain
    humanlibrary=Plains;Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

private val HARDCAST_PUZZLE =
    """
    [metadata]
    Name:Madness — Fiery Temper hardcast
    Goal:Cast Fiery Temper from hand for its regular mana cost (no madness path).
    Turns:3
    Difficulty:Easy

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=20
    AILife=20

    humanhand=Fiery Temper
    humanbattlefield=Mountain;Mountain;Mountain;Mountain
    humanlibrary=Plains;Plains;Plains
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

class MadnessLifecycleTest :
    FunSpec({
        tags(IntegrationTag)

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
        }

        test("madness cast path: discard outlet → exile → cast for {R} → resolve") {
            val h =
                MatchFlowHarness(
                    validation =
                        InvariantSelection.protocolFactsExcept(
                            "Madness cast path currently emits AID with post-move affector (follow-up validator blocker)",
                            InvariantCheck.AidAffector,
                        ),
                )
            try {
                h.connectAndKeepPuzzleText(MADNESS_PUZZLE)
                val player = h.bridge.getPlayer(SeatId(1))!!

                // Capture Fiery Temper's grpId before it moves.
                val fieryTemperGrpId = h.bridge.cardRepository.findGrpIdByName("Fiery Temper")!!
                val madnessAbilityGrpId =
                    h.bridge.cardRepository.findKeywordAbilityGrpId(
                        fieryTemperGrpId,
                        leyline.game.data.KeywordAbilityIds.MADNESS,
                    )
                val resolvedMadnessAbilityGrpId = madnessAbilityGrpId.shouldNotBeNull()
                resolvedMadnessAbilityGrpId shouldBeGreaterThan 0

                // Cast Tormenting Voice. Forge issues a SelectNReq for the
                // additional-cost discard before the spell goes on stack.
                h.castSpellByName("Tormenting Voice").shouldBeTrue()

                // Pull the discardable id from the live SelectNReq — instanceIds
                // here may differ from the hand-side iid due to discard realloc.
                val selectNReq =
                    h.allMessages
                        .asReversed()
                        .firstOrNull { it.hasSelectNReq() }
                        ?.selectNReq
                val discardChoiceId =
                    selectNReq
                        .shouldNotBeNull()
                        .idsList
                        .firstOrNull()
                        .shouldNotBeNull()
                h.respondToSelectN(listOf(discardChoiceId))

                // Tormenting Voice on stack with discard cost paid; Fiery Temper
                // exiled via madness replacement; the madness trigger resolves
                // and calls playSaFromPlayEffect → WPC emits OptionalActionMessage
                // (shortcut for client's ActionsAvailableReq Cast:1+Pass:1 flow; see
                // PlayerController.playSaFromPlayEffect comment). The harness's
                // autoRespondToOptionalAction auto-accepts on each drainSink, which
                // drives the cast through super.playSaFromPlayEffect.
                // Fiery Temper landed on stack via madness — it MUST prompt for a
                // target (ValidTgts$ Any). A missing prompt is a regression worth
                // catching, so hard-assert rather than conditionally skipping.
                h
                    .passUntil(maxPasses = 8) {
                        allMessages.any { it.hasSelectTargetsReq() }
                    }.shouldBeTrue()
                // AI player's seatId = 2; that's a valid "Any" target for Fiery Temper.
                h.selectTargets(listOf(2))

                // Drain until the stack empties or we run out of the passes budget.
                assertSoftly {
                    h
                        .passUntil(maxPasses = 6) {
                            h.bridge
                                .getGame()!!
                                .stack.isEmpty
                        }.shouldBeTrue()
                    h.bridge
                        .getGame()!!
                        .stack.isEmpty shouldBe true
                }

                // Fiery Temper resolves into Graveyard with damage to opponent.
                player
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Fiery Temper" }
                    .shouldBeTrue()

                // Wire-shape assertions across the GSM stream this slice collected.
                val allGsms = h.allMessages.mapNotNull { msgGsm(it) }

                // (1) Persistent CastingTimeOption type=13 attached to the
                //     stack-staged Fiery Temper, with the madness ability grpId.
                //     This is the alt-cost signal: client renders the cast as
                //     having gone through the madness path.
                val cto =
                    allGsms
                        .flatMap { it.persistentAnnotationsList }
                        .firstOrNull { it.typeList.contains(AnnotationType.CastingTimeOption) }
                assertSoftly {
                    cto.shouldNotBeNull()
                    cto.detailInt("type") shouldBe 13
                    cto.detailInt("alternateCostGrpId") shouldBe madnessAbilityGrpId
                    cto.detailInt("castAbilityGrpId") shouldBe madnessAbilityGrpId
                }

                // (2) UserActionTaken on the cast carries alternativeGrpId = madness ability.
                val castUat =
                    allGsms
                        .flatMap { it.annotationsList }
                        .filter { it.typeList.contains(AnnotationType.UserActionTaken) }
                        .firstOrNull {
                            it.detail("alternativeGrpId")?.getValueInt32(0) == madnessAbilityGrpId
                        }
                castUat.shouldNotBeNull()

                // (3) Fiery Temper resolved (deals 3 damage to AI player).
                val resolveZt =
                    allGsms
                        .flatMap { it.annotationsList }
                        .filter { it.typeList.contains(AnnotationType.ZoneTransfer_af5a) }
                        .firstOrNull { it.detailString("category") == "Resolve" }
                resolveZt.shouldNotBeNull()

                // (4) OptionalActionMessage was emitted for the madness choice.
                //     SHORTCUT — the production client can render this moment from
                //     a one-Cast/one-Pass action prompt. Leyline currently
                //     shortcuts via OptionalActionMessage ("Take Action / Decline")
                //     because the existing plumbing is ready. See
                //     PlayerController.playSaFromPlayEffect for rationale +
                //     migration path.
                val optionalPrompt =
                    h.allMessages
                        .firstOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
                optionalPrompt.shouldNotBeNull()

                // Known gap: Hand→Exile ZoneTransfer category is currently mis-tagged
                // (CastSpell instead of Discard) because Forge fires a SpellCast for
                // Fiery Temper during super.playSaFromPlayEffect, and the
                // TransferCategoryResolver.categoryFromEvents short-circuits on SpellCast before
                // considering the CardDiscarded event (from hasDiscardReplacementKeyword).
                // L1.5 follow-up: scope SpellCast matching by zone-pair, not just forgeCardId.
            } finally {
                h.shutdown()
            }
        }

        test("madness hardcast: regular cast from hand omits CastingTimeOption + alternativeGrpId") {
            val h = MatchFlowHarness()
            try {
                h.connectAndKeepPuzzleText(HARDCAST_PUZZLE)
                val player = h.bridge.getPlayer(SeatId(1))!!

                // Cast Fiery Temper directly from hand (1RR).
                h.castSpellByName("Fiery Temper").shouldBeTrue()
                h.selectTargets(listOf(2)) // AI player
                h.passPriority()

                player
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .any { it.name == "Fiery Temper" }
                    .shouldBeTrue()

                val allGsms = h.allMessages.mapNotNull { msgGsm(it) }

                // No persistent CastingTimeOption emitted for hardcast.
                val cto =
                    allGsms
                        .flatMap { it.persistentAnnotationsList }
                        .firstOrNull { it.typeList.contains(AnnotationType.CastingTimeOption) }
                cto shouldBe null

                // No alternativeGrpId on any UAT (regular cast).
                val anyAltUat =
                    allGsms
                        .flatMap { it.annotationsList }
                        .filter { it.typeList.contains(AnnotationType.UserActionTaken) }
                        .any { it.detailsList.any { d -> d.key == "alternativeGrpId" } }
                anyAltUat shouldBe false

                // Hand→Stack direct (no Exile detour).
                val handToStack =
                    allGsms
                        .flatMap { it.annotationsList }
                        .filter { it.typeList.contains(AnnotationType.ZoneTransfer_af5a) }
                        .firstOrNull {
                            it.detailString("category") == "CastSpell" &&
                                it.detailInt("zone_src") == leyline.game.mapping.ZoneIds.P1_HAND
                        }
                handToStack shouldNotBe null
            } finally {
                h.shutdown()
            }
        }

        test("madness decline: reject the optional cast → Exile→Graveyard Put") {
            val h = MatchFlowHarness()
            try {
                h.connectAndKeepPuzzleText(MADNESS_PUZZLE)
                val player = h.bridge.getPlayer(SeatId(1))!!

                // Pre-seed the decline — the next OptionalActionMessage (the madness
                // "cast or decline?" prompt) will be responded to with CancelNo
                // instead of the default auto-accept.
                h.declineNextOptionalAction()

                // Cast Tormenting Voice, pay discard cost on Fiery Temper.
                h.castSpellByName("Tormenting Voice").shouldBeTrue()
                val selectNReq =
                    h.allMessages
                        .asReversed()
                        .firstOrNull { it.hasSelectNReq() }
                        ?.selectNReq
                val discardChoiceId =
                    selectNReq?.idsList?.firstOrNull()
                        ?: error("No SelectNReq for discard cost")
                h.respondToSelectN(listOf(discardChoiceId))

                // Drain through the trigger resolution + decline + Tormenting Voice resolve.
                h
                    .passUntil(maxPasses = 6) {
                        h.bridge
                            .getGame()!!
                            .stack.isEmpty
                    }.shouldBeTrue()

                // Fiery Temper went to graveyard via the declined madness branch,
                // NOT via cast — should not be in exile either.
                assertSoftly {
                    player
                        .getZone(ZoneType.Graveyard)
                        .cards
                        .any { it.name == "Fiery Temper" }
                        .shouldBeTrue()
                    player
                        .getZone(ZoneType.Exile)
                        .cards
                        .none { it.name == "Fiery Temper" }
                        .shouldBeTrue()
                }

                val allGsms = h.allMessages.mapNotNull { msgGsm(it) }

                // Wire: card transitions from Exile to Graveyard on the decline
                // branch. Correct category is `Put`, but our dispatcher currently
                // mis-tags it as `Resolve` — the
                // madness ability's SpellResolved event fires with hostCard=
                // FieryTemper and TransferCategoryResolver.categoryFromEvents short-circuits
                // on it before considering the zone-pair. Known gap (same root as the
                // Hand→Exile mis-tag); tracked under the dispatcher-scope TODO in
                // TransferCategoryResolver.categoryFromEvents. For now we assert only the
                // zone transition fires at all — the category assertion is the
                // headline regression this gap blocks.
                val exileToGyZt =
                    allGsms
                        .flatMap { it.annotationsList }
                        .filter { it.typeList.contains(AnnotationType.ZoneTransfer_af5a) }
                        .firstOrNull {
                            it.detailInt("zone_src") == leyline.game.mapping.ZoneIds.EXILE &&
                                it.detailInt("zone_dest") == leyline.game.mapping.ZoneIds.P1_GRAVEYARD
                        }
                exileToGyZt shouldNotBe null
                // TODO: exileToGyZt.detailString("category") shouldBe "Put" — blocked
                //   on SpellResolved dispatcher scoping (see AnnotationBuilder.kt TODO).

                // Decline branch means no cast fired — no UAT alternativeGrpId present.
                val anyAltUat =
                    allGsms
                        .flatMap { it.annotationsList }
                        .filter { it.typeList.contains(AnnotationType.UserActionTaken) }
                        .any { it.hasDetail("alternativeGrpId") }
                anyAltUat shouldBe false
            } finally {
                h.shutdown()
            }
        }
    })

private fun msgGsm(msg: GREToClientMessage) = if (msg.hasGameStateMessage()) msg.gameStateMessage else null
