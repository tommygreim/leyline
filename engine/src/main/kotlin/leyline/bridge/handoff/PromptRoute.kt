package leyline.bridge.handoff

import leyline.bridge.types.PromptCandidateKind
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType

/**
 * Immutable protocol route fixed when a [PromptRequest] is created.
 *
 * The route is the sole prompt-family authority for the pending interaction.
 * Match lifecycle handlers and request builders consume it without
 * reclassifying [semantic].
 */
sealed interface ResolvedPromptRoute {
    val semantic: PromptSemantic

    data class Grouping(
        override val semantic: PromptSemantic,
        val context: GroupingContext,
    ) : ResolvedPromptRoute

    data class ModalChoice(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute

    /** Resolution choice whose exact entity domain is not yet coordinator-owned. */
    data class UnclassifiedEntityChoice(
        val descriptor: SelectNPromptRoute,
    ) : ResolvedPromptRoute {
        override val semantic: PromptSemantic = descriptor.semantic
    }

    /** Card-backed SelectN semantics owned by one exact-handle runtime. */
    data class CardSelect(
        val descriptor: CardSelectPromptRoute,
    ) : ResolvedPromptRoute {
        override val semantic: PromptSemantic = descriptor.semantic
    }

    /** Static enum SelectN semantics owned by one value-only runtime. */
    data class StaticChoice(
        val descriptor: StaticChoicePromptRoute,
    ) : ResolvedPromptRoute {
        override val semantic: PromptSemantic = descriptor.semantic
    }

    /** Reveal-backed SelectN choice owned by its exact journal entry. */
    data class RevealChoice(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute

    data class PayCosts(
        val descriptor: PayCostsPromptRoute,
    ) : ResolvedPromptRoute {
        override val semantic: PromptSemantic = descriptor.semantic
    }

    data class Search(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute

    /** Competing self-replacement choice owned by the settled replacement slot. */
    data class SelectReplacement(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute

    data class Order(
        override val semantic: PromptSemantic,
        val kind: OrderRouteKind,
    ) : ResolvedPromptRoute

    /** Fixed-total allocation across already-selected targets. */
    data class Distribution(
        override val semantic: PromptSemantic,
        val kind: DistributionRouteKind,
    ) : ResolvedPromptRoute

    data class Targeting(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute

    /** Candidate-backed SelectTargets compatibility route for residual card costs. */
    data class CompatibilityCostSelection(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute

    data class AutoResolve(
        override val semantic: PromptSemantic,
    ) : ResolvedPromptRoute
}

enum class OrderRouteKind {
    Bottom,
    Top,
}

enum class DistributionRouteKind {
    Damage,
    Counters,
}

enum class CardSelectKind {
    LegendRule,
    LibraryPutback,
    ManifestDread,
    Discard,
    DiscardEffect,
    SacrificeEffect,
    Suspect,
    MutateTopBottom,
    Resolution,
    ResolutionMapped,
    Learn,
}

enum class ResolutionAbilityShape {
    Dig,
    Other,
}

/** Candidate-domain facts fixed by the engine callback before route binding. */
data class ResolutionRouteInput(
    val optionCount: Int,
    val candidateCount: Int,
    val candidateKinds: Set<PromptCandidateKind>,
    val candidateZones: Set<String?>,
    val abilityShape: ResolutionAbilityShape,
    val allCandidatesProjectable: Boolean,
) {
    val isCompleteCardChoice: Boolean
        get() = candidateCount == optionCount && candidateKinds == setOf(PromptCandidateKind.Card)

    val isCompleteLibraryCardChoice: Boolean
        get() =
            isCompleteCardChoice &&
                candidateZones == setOf("Library")

    val isHiddenLibraryCardChoice: Boolean
        get() = isCompleteLibraryCardChoice && abilityShape == ResolutionAbilityShape.Dig

    val isMappedCardChoice: Boolean
        get() = isCompleteCardChoice && allCandidatesProjectable && !isHiddenLibraryCardChoice
}

/** Synchronous answer selected by an explicitly non-interactive route. */
data class PromptPolicyDefault(
    val indices: List<Int>,
)

fun PromptRequest.policyDefault(): PromptPolicyDefault? {
    if (route !is ResolvedPromptRoute.AutoResolve) return null
    // When the engine offers to finish targeting it is asking whether to add one
    // more optional target, and it asks again for every one taken. Defaulting to
    // the first option answers "take this one too" every time, so the sequence
    // never ends and the engine recurses until the thread runs out of stack.
    // Taking the offer to stop is both terminating and the safer read of an
    // optional target the seat never asked for.
    val finish = targetingFinishOptionIndex
    return PromptPolicyDefault(
        indices = listOf(finish ?: defaultIndex),
    )
}

data class CardSelectPromptRoute(
    val semantic: PromptSemantic,
    val kind: CardSelectKind,
    val choiceResultSentiment: Int? = null,
)

data class SelectNShape(
    val context: SelectionContext,
    val listType: SelectionListType,
    val optionContext: OptionContext,
)

enum class StaticChoiceKind {
    Color,
    Subtype,
    Parity,
    Keyword,
    CardType,
}

data class StaticChoicePromptRoute(
    val semantic: PromptSemantic,
    val kind: StaticChoiceKind,
)

data class SelectNPromptRoute(
    val semantic: PromptSemantic,
    val shape: SelectNShape,
)

enum class PayCostsRouteKind {
    Sacrifice,
    SelectCostExileFromGrave,
    SelectCostReturnAttacker,
    CollectEvidence,
    StationTapCost,
    EnlistCost,
    TapPayment,
    ConvokeCost,
    ImproviseCost,
    WaterbendCost,
}

enum class TapPaymentKind {
    TotalPower,
    TapExact,
    UntapExact,
}

/** One protocol-grounded tap-payment row bound before the prompt is published. */
data class TapPaymentDescriptor(
    val kind: TapPaymentKind,
    val required: Int,
    val promptId: Int,
) {
    init {
        require(promptId == promptId(kind, required)) { "Unsupported tap-payment row: $kind $required / $promptId" }
    }

    companion object {
        fun grounded(
            kind: TapPaymentKind,
            required: Int,
        ): TapPaymentDescriptor? = promptId(kind, required)?.let { TapPaymentDescriptor(kind, required, it) }

        private fun promptId(
            kind: TapPaymentKind,
            required: Int,
        ): Int? =
            when (kind to required) {
                TapPaymentKind.TotalPower to 1 -> 8929
                TapPaymentKind.TotalPower to 2 -> 8924
                TapPaymentKind.TotalPower to 3 -> 8925
                TapPaymentKind.TotalPower to 4 -> 8922
                TapPaymentKind.TapExact to 1 -> 183
                TapPaymentKind.TapExact to 2 -> 2595
                TapPaymentKind.TapExact to 3 -> 3579
                TapPaymentKind.UntapExact to 2 -> 8840
                else -> null
            }
    }
}

enum class ManaSourcePaymentKind {
    Convoke,
    Improvise,
    Waterbend,
}

data class PayCostsPromptRoute(
    val semantic: PromptSemantic,
    val kind: PayCostsRouteKind,
    val templateLabel: String,
    val manaSourcePayment: ManaSourcePaymentKind? = null,
    val tapPayment: TapPaymentDescriptor? = null,
)

object PromptRouteResolver {
    private val dynamicResolutionShape =
        SelectNShape(
            SelectionContext.Resolution_a163,
            SelectionListType.Dynamic,
            OptionContext.Resolution_a9d7,
        )

    @Suppress("CyclomaticComplexMethod", "CanBeNonNullable")
    fun resolve(
        semantic: PromptSemantic,
        hasCandidateRefs: Boolean = false,
        resolutionInput: ResolutionRouteInput? = null,
        tapPayment: TapPaymentDescriptor? = null,
    ): ResolvedPromptRoute =
        when (semantic) {
            PromptSemantic.Generic ->
                if (hasCandidateRefs) {
                    ResolvedPromptRoute.CompatibilityCostSelection(semantic)
                } else {
                    ResolvedPromptRoute.AutoResolve(semantic)
                }
            PromptSemantic.TargetSelection -> ResolvedPromptRoute.Targeting(semantic)
            PromptSemantic.GroupingSurveil -> ResolvedPromptRoute.Grouping(semantic, GroupingContext.Surveil)
            PromptSemantic.GroupingScry -> ResolvedPromptRoute.Grouping(semantic, GroupingContext.Scry_a0f6)
            PromptSemantic.ModalChoice -> ResolvedPromptRoute.ModalChoice(semantic)
            PromptSemantic.Search -> ResolvedPromptRoute.Search(semantic)
            PromptSemantic.GroupedSearch -> ResolvedPromptRoute.Search(semantic)
            PromptSemantic.SelectReplacement -> ResolvedPromptRoute.SelectReplacement(semantic)
            PromptSemantic.OrderForBottom -> ResolvedPromptRoute.Order(semantic, OrderRouteKind.Bottom)
            PromptSemantic.OrderForTop -> ResolvedPromptRoute.Order(semantic, OrderRouteKind.Top)
            PromptSemantic.DividedAllocationDamage -> ResolvedPromptRoute.Distribution(semantic, DistributionRouteKind.Damage)
            PromptSemantic.DividedAllocationCounters -> ResolvedPromptRoute.Distribution(semantic, DistributionRouteKind.Counters)
            PromptSemantic.SelectNLegendRule -> cardSelect(semantic, CardSelectKind.LegendRule)
            PromptSemantic.SelectNDiscard -> cardSelect(semantic, CardSelectKind.Discard, choiceResultSentiment = 1)
            PromptSemantic.SelectNDiscardEffect -> cardSelect(semantic, CardSelectKind.DiscardEffect, choiceResultSentiment = 1)
            PromptSemantic.RevealChoose -> ResolvedPromptRoute.RevealChoice(semantic)
            PromptSemantic.SelectNResolution ->
                when {
                    resolutionInput?.isHiddenLibraryCardChoice == true ->
                        cardSelect(semantic, CardSelectKind.Resolution)
                    resolutionInput?.isMappedCardChoice == true ->
                        cardSelect(semantic, CardSelectKind.ResolutionMapped)
                    else -> unclassifiedEntityChoice(semantic, dynamicResolutionShape)
                }
            PromptSemantic.ManifestDread -> cardSelect(semantic, CardSelectKind.ManifestDread)
            PromptSemantic.SuspectChoice -> cardSelect(semantic, CardSelectKind.Suspect, choiceResultSentiment = 2)
            PromptSemantic.SelectNLibraryPutback -> cardSelect(semantic, CardSelectKind.LibraryPutback)
            PromptSemantic.SelectNSacrificeEffect ->
                cardSelect(semantic, CardSelectKind.SacrificeEffect, choiceResultSentiment = 1)
            PromptSemantic.MutateTopBottom -> cardSelect(semantic, CardSelectKind.MutateTopBottom)
            PromptSemantic.LearnLesson -> cardSelect(semantic, CardSelectKind.Learn)
            PromptSemantic.StaticColorChoice ->
                staticChoice(semantic, StaticChoiceKind.Color)
            PromptSemantic.StaticSubtypeChoice ->
                staticChoice(semantic, StaticChoiceKind.Subtype)
            PromptSemantic.StaticParityChoice ->
                staticChoice(semantic, StaticChoiceKind.Parity)
            PromptSemantic.StaticKeywordChoice ->
                staticChoice(semantic, StaticChoiceKind.Keyword)
            PromptSemantic.StaticCardTypeChoice ->
                staticChoice(semantic, StaticChoiceKind.CardType)
            PromptSemantic.SelectNCostSacrifice -> payCosts(semantic, PayCostsRouteKind.Sacrifice, "sacrifice")
            PromptSemantic.SelectNCostExileFromGrave ->
                payCosts(semantic, PayCostsRouteKind.SelectCostExileFromGrave, "exile-from-grave")
            PromptSemantic.SelectNCostCollectEvidence ->
                payCosts(semantic, PayCostsRouteKind.CollectEvidence, "collect-evidence")
            PromptSemantic.EnlistCost -> payCosts(semantic, PayCostsRouteKind.EnlistCost, "enlist")
            PromptSemantic.TapPaymentCost ->
                payCosts(
                    semantic,
                    PayCostsRouteKind.TapPayment,
                    "tap-payment",
                    tapPayment = checkNotNull(tapPayment) { "TapPaymentCost requires an exact descriptor" },
                )
            PromptSemantic.StationTapCost -> payCosts(semantic, PayCostsRouteKind.StationTapCost, "station")
            PromptSemantic.ReturnUnblockedAttackerCost ->
                payCosts(semantic, PayCostsRouteKind.SelectCostReturnAttacker, "return-unblocked-attacker")
            PromptSemantic.ConvokeCost ->
                payCosts(semantic, PayCostsRouteKind.ConvokeCost, "convoke", ManaSourcePaymentKind.Convoke)
            PromptSemantic.ImproviseCost ->
                payCosts(semantic, PayCostsRouteKind.ImproviseCost, "improvise", ManaSourcePaymentKind.Improvise)
            PromptSemantic.WaterbendCost ->
                payCosts(semantic, PayCostsRouteKind.WaterbendCost, "waterbend", ManaSourcePaymentKind.Waterbend)
        }

    private fun unclassifiedEntityChoice(
        semantic: PromptSemantic,
        shape: SelectNShape,
    ): ResolvedPromptRoute.UnclassifiedEntityChoice =
        ResolvedPromptRoute.UnclassifiedEntityChoice(
            SelectNPromptRoute(semantic, shape),
        )

    private fun cardSelect(
        semantic: PromptSemantic,
        kind: CardSelectKind,
        choiceResultSentiment: Int? = null,
    ): ResolvedPromptRoute.CardSelect =
        ResolvedPromptRoute.CardSelect(CardSelectPromptRoute(semantic, kind, choiceResultSentiment = choiceResultSentiment))

    private fun staticChoice(
        semantic: PromptSemantic,
        kind: StaticChoiceKind,
    ): ResolvedPromptRoute.StaticChoice = ResolvedPromptRoute.StaticChoice(StaticChoicePromptRoute(semantic, kind))

    private fun payCosts(
        semantic: PromptSemantic,
        kind: PayCostsRouteKind,
        templateLabel: String,
        manaSourcePayment: ManaSourcePaymentKind? = null,
        tapPayment: TapPaymentDescriptor? = null,
    ): ResolvedPromptRoute.PayCosts =
        ResolvedPromptRoute.PayCosts(PayCostsPromptRoute(semantic, kind, templateLabel, manaSourcePayment, tapPayment))
}
