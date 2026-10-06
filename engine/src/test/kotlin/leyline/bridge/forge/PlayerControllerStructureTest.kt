package leyline.bridge.forge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.forge.PlayerController

/**
 * Pins the [leyline.bridge.forge.PlayerController] override surface.
 *
 * Forge dispatches through single inheritance — the class must keep hosting every
 * override. Any addition or removal is a spec change that must update the expected
 * set and count below in the same commit.
 *
 * See [leyline.bridge.forge.PlayerController]'s KDoc for the pattern this guardrail supports.
 */
class PlayerControllerStructureTest :
    FunSpec({

        tags(UnitTag)

        // The current set of 73 PCHuman overrides.
        val expectedOverrides =
            setOf(
                "announceRequirements",
                "applyManaToCost",
                "arrangeForScry",
                "arrangeForSurveil",
                "assignCombatDamage",
                "chooseBinary",
                "chooseCardName",
                "chooseCardsForConvokeOrImprovise",
                "chooseCardsForCollectEvidence",
                "chooseCardsForCost",
                "chooseCardsForEffect",
                "chooseCardsForExileCost",
                "chooseCardsForRevealCost",
                "chooseCardsForTapCost",
                "chooseCardsForZoneChange",
                "chooseCardsToDelve",
                "chooseCardsToDiscardFrom",
                "chooseCardsToDiscardToMaximumHandSize",
                "chooseCardsToDiscardUnlessType",
                "chooseCardsToRevealFromHand",
                "chooseColor",
                "chooseColorAllowColorless",
                "chooseColors",
                "chooseCounterType",
                "chooseEntitiesForEffect",
                "chooseKeywordForPump",
                "chooseModeForAbility",
                "chooseNewTargetsFor",
                "chooseNumber",
                "chooseNumberForCostReduction",
                "chooseNumberForKeywordCost",
                "chooseOptionalCosts",
                "choosePermanentsToDestroy",
                "choosePermanentsToSacrifice",
                "choosePlayerToAssistPayment",
                "chooseProtectionType",
                "chooseSingleEntityForEffect",
                "chooseSingleCardFace",
                "chooseSingleSpellForEffect",
                "chooseSingleStaticAbility",
                "chooseSomeType",
                "chooseSpellAbilityToPlay",
                "chooseSpellAbilitiesForEffect",
                "chooseSingleReplacementEffect",
                "chooseSaToActivateFromOpeningHand",
                "chooseStartingPlayer",
                "chooseTargetsFor",
                "confirmAction",
                "confirmPayment",
                "confirmReplacementEffect",
                "confirmStaticApplication",
                "confirmTrigger",
                "declareAttackers",
                "declareBlockers",
                "enlistAttackers",
                "exertAttackers",
                "getCostDecisionMaker",
                "helpPayForAssistSpell",
                "isAI",
                "mulliganKeepHand",
                "orderMoveToZoneList",
                "orderSimultaneousSa",
                "payCostToPreventEffect",
                "payManaCost",
                "playChosenSpellAbility",
                "playSaFromPlayEffect",
                "playSpellAbilityNoStack",
                "playTrigger",
                "reveal",
                "selectTargetsInteractively",
                "tuckCardsViaMulligan",
                "willPutCardOnTop",
                "vote",
            )

        test("override count is pinned at 73") {
            expectedOverrides.size shouldBe 73
        }

        test("PlayerController declares exactly the expected overrides") {
            val clazz = PlayerController::class.java
            // Deduplicate by (name, parameterTypes) — not by name alone — so that an
            // accidental overload addition (two methods with the same name and different
            // signatures) cannot pass silently.
            val overridingMethods =
                clazz.declaredMethods
                    .asSequence()
                    .filter { !it.isSynthetic }
                    .filter {
                        !java.lang.reflect.Modifier
                            .isPrivate(it.modifiers)
                    }.filter {
                        !java.lang.reflect.Modifier
                            .isStatic(it.modifiers)
                    }.filter { it.isDeclaredInAnyAncestor(clazz.superclass) }
                    .distinctBy { m -> m.name to m.parameterTypes.toList() }
                    .toList()

            overridingMethods.map { it.name }.toSet() shouldBe expectedOverrides
            // chooseNumber has three overloads (range, range+params, list-of-values),
            // chooseCardsToDiscardFrom has two overloads (with/without visible cards),
            // and reveal has two overloads (CardCollectionView and List<CardView>),
            // chooseCardName and chooseSingleCardFace each have two overloads,
            // so the (name, paramTypes) count exceeds the unique-name count by 6.
            overridingMethods.size shouldBe expectedOverrides.size + 6
        }
    })

private fun java.lang.reflect.Method.isDeclaredInAnyAncestor(start: Class<*>?): Boolean {
    var cls: Class<*>? = start
    while (cls != null) {
        if (cls.declaredMethods.any { it.name == name && it.parameterTypes.contentEquals(parameterTypes) }) {
            return true
        }
        cls = cls.superclass
    }
    return false
}
