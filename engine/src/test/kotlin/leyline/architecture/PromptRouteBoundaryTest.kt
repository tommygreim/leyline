package leyline.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.architecture.EngineArchitecture.member
import leyline.architecture.EngineArchitecture.named
import leyline.bridge.handoff.ManaSourcePaymentKind
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import kotlin.reflect.KClass

/**
 * Enforces the coordinator-owned prompt boundary, one row per prompt-route family.
 *
 * Every family is built the same way: a window materializer and its handoff
 * values are pure values, any match-layer response handler holds no live Forge
 * or [leyline.game.state.GameBridge] handle, [leyline.game.bundle.BundleBuilder]
 * owns the single window-preparation entry point that only that family's
 * coordinator runtime may call, and the family's semantics resolve to its route.
 *
 * Adding a family is adding a row. Each row emits its own test so a failure
 * names the family that broke.
 */
class PromptRouteBoundaryTest :
    FunSpec({

        tags(UnitTag)

        val classes = EngineArchitecture.mainClasses
        val bundleBuilder = "leyline.game.bundle.BundleBuilder"
        val gameBridge = named("leyline.game.state.GameBridge")
        val promptBridge = "leyline.bridge.handoff.InteractivePromptBridge"

        promptRouteFamilies.forEach { family ->
            test("${family.name} prompt route is value-only and coordinator-owned") {
                family.valueScope?.let { scope ->
                    noClasses()
                        .that()
                        .haveNameMatching(scope)
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage("forge..")
                        .because("${family.name} windows are materialized from immutable values")
                        .check(classes)

                    noClasses()
                        .that()
                        .haveNameMatching(scope)
                        .should()
                        .dependOnClassesThat()
                        .haveNameMatching(gameBridge)
                        .because("the live bridge stays in the shell that materializes ${family.name} facts")
                        .check(classes)
                }

                family.handler?.let { handler ->
                    family.handlerForgeBan.forEach { forgePackage ->
                        noClasses()
                            .that()
                            .haveNameMatching(named(handler))
                            .should()
                            .dependOnClassesThat()
                            .resideInAPackage(forgePackage)
                            .because("${family.name} responses carry wire handles, not Forge objects")
                            .check(classes)
                    }

                    // Handlers keep a bridge handle to reach their coordinator runtime;
                    // what they must not do is look entities back up out of live state.
                    noClasses()
                        .that()
                        .haveNameMatching(named(handler))
                        .should()
                        .callMethodWhere(liveEntityLookupOnGameBridge)
                        .because("${family.name} responses resolve against the published window, not live state")
                        .check(classes)
                }

                family.requiredHandlerDependency?.let { required ->
                    classes()
                        .that()
                        .haveFullyQualifiedName(family.handler!!)
                        .should()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName(required)
                        .because("${family.name} responses consume the frozen plan value")
                        .check(classes)
                }

                family.prepareMethod?.let { prepare ->
                    methods()
                        .that()
                        .areDeclaredInClassesThat()
                        .haveNameMatching(named(bundleBuilder))
                        .and()
                        .haveNameMatching(member(prepare))
                        .should()
                        .onlyBeCalled()
                        .byClassesThat()
                        .haveNameMatching(named(bundleBuilder, family.runtime!!))
                        .because("${family.name} windows are prepared once, by their coordinator runtime")
                        .check(classes)
                }

                family.routeType?.let { routeType ->
                    classes()
                        .that()
                        .haveFullyQualifiedName(promptBridge)
                        .should()
                        .dependOnClassesThat()
                        .haveFullyQualifiedName(routeType.java.name)
                        .because("the prompt bridge dispatches ${family.name} on its bound route")
                        .check(classes)

                    family.semantics.forEach { semantic ->
                        withClue("PromptSemantic.$semantic must resolve to ${routeType.simpleName}") {
                            PromptRouteResolver.resolve(semantic)::class shouldBe routeType
                        }
                    }
                }
            }
        }

        test("mana-source PayCosts routes are the only ones carrying an iterative payment kind") {
            val manaSourceSemantics =
                PromptSemantic.entries.filter { semantic ->
                    semantic != PromptSemantic.TapPaymentCost &&
                        (PromptRouteResolver.resolve(semantic) as? ResolvedPromptRoute.PayCosts)
                            ?.descriptor
                            ?.manaSourcePayment != null
                }

            manaSourceSemantics shouldBe
                listOf(
                    PromptSemantic.ConvokeCost,
                    PromptSemantic.ImproviseCost,
                    PromptSemantic.WaterbendCost,
                )
            ManaSourcePaymentKind.entries.map { it.name } shouldBe listOf("Convoke", "Improvise", "Waterbend")
        }
    })

/**
 * One prompt-route family. `null` columns mean the family has no such seam —
 * a materializer with no match-layer handler, or a plan value with no window.
 */
private data class PromptRouteFamily(
    val name: String,
    val materializers: List<String> = emptyList(),
    /** Regex fragment matching the family's handoff value types. */
    val handoffValues: String? = null,
    val handler: String? = null,
    /** Forge package patterns the response handler must not depend on. */
    val handlerForgeBan: List<String> = listOf("forge.."),
    val requiredHandlerDependency: String? = null,
    val prepareMethod: String? = null,
    val runtime: String? = null,
    val routeType: KClass<out ResolvedPromptRoute>? = null,
    val semantics: List<PromptSemantic> = emptyList(),
) {
    /** Regex over every pure value type the family owns, or `null` when it owns none. */
    val valueScope: String?
        get() {
            val alternatives = materializers.map(Regex::escape) + listOfNotNull(handoffValues)
            return if (alternatives.isEmpty()) null else "(" + alternatives.joinToString("|") + ")(\\\$.*)?"
        }
}

/**
 * Live entity lookups on the bridge. A response handler that calls one of these
 * is resolving against current engine state instead of the published window.
 */
private val liveEntityLookupOnGameBridge =
    object : DescribedPredicate<JavaMethodCall>("call a live entity lookup on GameBridge") {
        private val lookups = setOf("findCard", "findById", "getZone", "getForgeCardId", "getGame", "getPlayer")

        override fun test(call: JavaMethodCall): Boolean =
            call.targetOwner.name == "leyline.game.state.GameBridge" && call.target.name in lookups
    }

private const val HANDOFF = "leyline.bridge.handoff"
private const val MATERIALIZERS = "leyline.game.bundle"
private const val COORD = "leyline.bridge.coord"
private const val MATCH = "leyline.match"

private val promptRouteFamilies =
    listOf(
        PromptRouteFamily(
            name = "Targeting",
            materializers = listOf("$MATERIALIZERS.TargetingWindowMaterializer"),
            handoffValues = "$HANDOFF.Targeting(WindowValue|CandidateValue|CommandReceipt)",
            handler = "$MATCH.TargetingHandler",
            // TargetingHandler still probes stack emptiness to decide whether a
            // post-cast prompt is due; the ban covers the object families it must
            // never reopen — card state and spell abilities.
            handlerForgeBan = listOf("forge.game.card..", "forge.game.spellability.."),
            prepareMethod = "prepareTargetingWindow",
            runtime = "$COORD.MatchTargetingInteractionRuntime",
            routeType = ResolvedPromptRoute.Targeting::class,
            semantics = listOf(PromptSemantic.TargetSelection),
        ),
        PromptRouteFamily(
            name = "Order",
            materializers = listOf("$MATERIALIZERS.OrderWindowMaterializer"),
            handoffValues = "$HANDOFF.Order(Window|Candidate|Move)Value",
            prepareMethod = "prepareOrderWindow",
            runtime = "$COORD.MatchOrderInteractionRuntime",
            routeType = ResolvedPromptRoute.Order::class,
            semantics = listOf(PromptSemantic.OrderForBottom, PromptSemantic.OrderForTop),
        ),
        PromptRouteFamily(
            name = "TriggerOrder",
            materializers = listOf("$MATERIALIZERS.TriggerOrderWindowMaterializer"),
            handoffValues = "$HANDOFF.(TriggerOrder.*Value|PublishedTriggerOrderInteraction)",
            prepareMethod = "prepareTriggerOrderWindow",
            runtime = "$COORD.MatchTriggerOrderInteractionRuntime",
            routeType = ResolvedPromptRoute.OrderTriggers::class,
            semantics = listOf(PromptSemantic.OrderTriggers),
        ),
        PromptRouteFamily(
            name = "Distribution",
            materializers = listOf("$MATERIALIZERS.DistributionWindowMaterializer"),
            handoffValues = "$HANDOFF.DistributionWindowValue",
            prepareMethod = "prepareDistributionWindow",
            runtime = "$COORD.MatchDistributionInteractionRuntime",
            routeType = ResolvedPromptRoute.Distribution::class,
            semantics = listOf(PromptSemantic.DividedAllocationDamage, PromptSemantic.DividedAllocationCounters),
        ),
        PromptRouteFamily(
            name = "Search",
            materializers = listOf("$MATERIALIZERS.SearchWindowMaterializer"),
            handoffValues = "$HANDOFF.(Search.*Value|PublishedSearchInteraction)",
            prepareMethod = "prepareSearchWindow",
            runtime = "$COORD.MatchSearchInteractionRuntime",
            routeType = ResolvedPromptRoute.Search::class,
            semantics = listOf(PromptSemantic.Search, PromptSemantic.GroupedSearch),
        ),
        PromptRouteFamily(
            name = "SelectReplacement",
            materializers = listOf("$MATERIALIZERS.ReplacementWindowMaterializer"),
            handoffValues = "$HANDOFF.(Replacement.*Value|PublishedReplacementInteraction)",
            prepareMethod = "prepareReplacementWindow",
            runtime = "$COORD.MatchReplacementInteractionRuntime",
            routeType = ResolvedPromptRoute.SelectReplacement::class,
            semantics = listOf(PromptSemantic.SelectReplacement),
        ),
        PromptRouteFamily(
            name = "Grouping",
            materializers = listOf("$MATERIALIZERS.GroupingWindowMaterializer"),
            handoffValues = "$HANDOFF.(Grouping.*Value|PublishedGroupingInteraction)",
            prepareMethod = "prepareGroupingWindow",
            runtime = "$COORD.MatchGroupingInteractionRuntime",
            routeType = ResolvedPromptRoute.Grouping::class,
            semantics = listOf(PromptSemantic.GroupingSurveil, PromptSemantic.GroupingScry),
        ),
        PromptRouteFamily(
            name = "CardSelect",
            materializers = listOf("$MATERIALIZERS.CardSelectWindowMaterializer"),
            handoffValues = "$HANDOFF.CardSelect.*Value",
            prepareMethod = "prepareCardSelectWindow",
            runtime = "$COORD.MatchCardSelectInteractionRuntime",
            routeType = ResolvedPromptRoute.CardSelect::class,
            semantics =
                listOf(
                    PromptSemantic.SelectNLegendRule,
                    PromptSemantic.SelectNDiscard,
                    PromptSemantic.SelectNDiscardEffect,
                    PromptSemantic.SelectNSacrificeEffect,
                    PromptSemantic.SelectNLibraryPutback,
                    PromptSemantic.SuspectChoice,
                    PromptSemantic.MutateTopBottom,
                    PromptSemantic.ManifestDread,
                    PromptSemantic.LearnLesson,
                ),
        ),
        PromptRouteFamily(
            name = "StaticChoice",
            materializers = listOf("$MATERIALIZERS.StaticChoiceWindowMaterializer"),
            handoffValues = "$HANDOFF.(StaticChoice.*Value|PublishedStaticChoiceInteraction)",
            prepareMethod = "prepareStaticChoiceWindow",
            runtime = "$COORD.MatchStaticChoiceInteractionRuntime",
            routeType = ResolvedPromptRoute.StaticChoice::class,
            semantics =
                listOf(
                    PromptSemantic.StaticColorChoice,
                    PromptSemantic.StaticSubtypeChoice,
                    PromptSemantic.StaticParityChoice,
                ),
        ),
        PromptRouteFamily(
            name = "RevealChoice",
            materializers = listOf("$MATERIALIZERS.RevealChoiceWindowMaterializer"),
            handoffValues = "$HANDOFF.(RevealChoice.*Value|PublishedRevealChoiceInteraction)",
            prepareMethod = "prepareRevealChoiceWindow",
            runtime = "$COORD.MatchRevealChoiceInteractionRuntime",
            routeType = ResolvedPromptRoute.RevealChoice::class,
            semantics = listOf(PromptSemantic.RevealChoose),
        ),
        PromptRouteFamily(
            name = "ManaSourcePayment",
            materializers = listOf("$MATERIALIZERS.ManaSourcePaymentMaterializer"),
            handoffValues = "$HANDOFF.ManaSourcePayment.*Value",
            handler = "$MATCH.ManaSourcePaymentHandler",
            prepareMethod = "prepareManaSourcePayment",
            runtime = "$COORD.MatchManaSourcePaymentRuntime",
            routeType = ResolvedPromptRoute.PayCosts::class,
            semantics =
                listOf(
                    PromptSemantic.ConvokeCost,
                    PromptSemantic.ImproviseCost,
                    PromptSemantic.WaterbendCost,
                ),
        ),
        PromptRouteFamily(
            name = "OneShotPayCosts",
            materializers =
                listOf(
                    "$MATERIALIZERS.OneShotPayCostsMaterializer",
                    "$MATERIALIZERS.GatherCountersWindowMaterializer",
                ),
            handoffValues = "$HANDOFF.OneShotPayCosts.*Value",
            prepareMethod = "prepareOneShotPayCosts",
            runtime = "$COORD.MatchOneShotPayCostsRuntime",
            routeType = ResolvedPromptRoute.PayCosts::class,
            semantics =
                listOf(
                    PromptSemantic.SelectNCostSacrifice,
                    PromptSemantic.SelectNCostExileFromGrave,
                    PromptSemantic.SelectNCostCollectEvidence,
                    PromptSemantic.EnlistCost,
                    PromptSemantic.StationTapCost,
                    PromptSemantic.ReturnUnblockedAttackerCost,
                ),
        ),
        PromptRouteFamily(
            name = "BlockingInteraction",
            materializers = listOf("$MATERIALIZERS.BlockingInteractionMaterializer"),
        ),
        PromptRouteFamily(
            name = "DeferredCastCost",
            handoffValues = "$HANDOFF.DeferredCastCostPlan",
            handler = "$MATCH.DeferredCastCostInteractionHandler",
            requiredHandlerDependency = "$HANDOFF.DeferredCastCostPlan",
        ),
    )
