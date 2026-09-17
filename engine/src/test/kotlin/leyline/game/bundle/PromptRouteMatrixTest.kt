package leyline.game.bundle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.handoff.CardSelectKind
import leyline.bridge.handoff.CardSelectPromptRoute
import leyline.bridge.handoff.DistributionRouteKind
import leyline.bridge.handoff.ManaSourcePaymentKind
import leyline.bridge.handoff.OrderRouteKind
import leyline.bridge.handoff.PayCostsPromptRoute
import leyline.bridge.handoff.PayCostsRouteKind
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolutionAbilityShape
import leyline.bridge.handoff.ResolutionRouteInput
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.SelectNPromptRoute
import leyline.bridge.handoff.SelectNShape
import leyline.bridge.handoff.StaticChoiceKind
import leyline.bridge.handoff.StaticChoicePromptRoute
import leyline.bridge.handoff.TapPaymentDescriptor
import leyline.bridge.handoff.TapPaymentKind
import leyline.bridge.types.PromptCandidateKind
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType

class PromptRouteMatrixTest :
    FunSpec({
        tags(UnitTag)

        test("every semantic has one characterized prompt route") {
            val expected =
                mapOf(
                    PromptSemantic.Generic to ResolvedPromptRoute.AutoResolve(PromptSemantic.Generic),
                    PromptSemantic.TargetSelection to ResolvedPromptRoute.Targeting(PromptSemantic.TargetSelection),
                    PromptSemantic.GroupingSurveil to
                        ResolvedPromptRoute.Grouping(PromptSemantic.GroupingSurveil, GroupingContext.Surveil),
                    PromptSemantic.GroupingScry to
                        ResolvedPromptRoute.Grouping(PromptSemantic.GroupingScry, GroupingContext.Scry_a0f6),
                    PromptSemantic.ModalChoice to ResolvedPromptRoute.ModalChoice(PromptSemantic.ModalChoice),
                    PromptSemantic.SelectNLegendRule to
                        cardSelect(PromptSemantic.SelectNLegendRule, CardSelectKind.LegendRule),
                    PromptSemantic.SelectNDiscard to
                        cardSelect(PromptSemantic.SelectNDiscard, CardSelectKind.Discard, sentiment = 1),
                    PromptSemantic.SelectNDiscardEffect to
                        cardSelect(PromptSemantic.SelectNDiscardEffect, CardSelectKind.DiscardEffect, sentiment = 1),
                    PromptSemantic.Search to ResolvedPromptRoute.Search(PromptSemantic.Search),
                    PromptSemantic.GroupedSearch to ResolvedPromptRoute.Search(PromptSemantic.GroupedSearch),
                    PromptSemantic.SelectReplacement to
                        ResolvedPromptRoute.SelectReplacement(PromptSemantic.SelectReplacement),
                    PromptSemantic.OrderForBottom to
                        ResolvedPromptRoute.Order(PromptSemantic.OrderForBottom, OrderRouteKind.Bottom),
                    PromptSemantic.OrderForTop to ResolvedPromptRoute.Order(PromptSemantic.OrderForTop, OrderRouteKind.Top),
                    PromptSemantic.DividedAllocationDamage to
                        ResolvedPromptRoute.Distribution(PromptSemantic.DividedAllocationDamage, DistributionRouteKind.Damage),
                    PromptSemantic.DividedAllocationCounters to
                        ResolvedPromptRoute.Distribution(PromptSemantic.DividedAllocationCounters, DistributionRouteKind.Counters),
                    PromptSemantic.RevealChoose to
                        ResolvedPromptRoute.RevealChoice(PromptSemantic.RevealChoose),
                    PromptSemantic.SelectNResolution to selectN(PromptSemantic.SelectNResolution),
                    PromptSemantic.ManifestDread to
                        cardSelect(PromptSemantic.ManifestDread, CardSelectKind.ManifestDread),
                    PromptSemantic.SuspectChoice to
                        cardSelect(PromptSemantic.SuspectChoice, CardSelectKind.Suspect, sentiment = 2),
                    PromptSemantic.SelectNLibraryPutback to
                        cardSelect(PromptSemantic.SelectNLibraryPutback, CardSelectKind.LibraryPutback),
                    PromptSemantic.SelectNSacrificeEffect to
                        cardSelect(PromptSemantic.SelectNSacrificeEffect, CardSelectKind.SacrificeEffect, sentiment = 1),
                    PromptSemantic.SelectNCostSacrifice to
                        payCosts(PromptSemantic.SelectNCostSacrifice, PayCostsRouteKind.Sacrifice, "sacrifice"),
                    PromptSemantic.SelectNCostExileFromGrave to
                        payCosts(
                            PromptSemantic.SelectNCostExileFromGrave,
                            PayCostsRouteKind.SelectCostExileFromGrave,
                            "exile-from-grave",
                        ),
                    PromptSemantic.SelectNCostCollectEvidence to
                        payCosts(PromptSemantic.SelectNCostCollectEvidence, PayCostsRouteKind.CollectEvidence, "collect-evidence"),
                    PromptSemantic.EnlistCost to payCosts(PromptSemantic.EnlistCost, PayCostsRouteKind.EnlistCost, "enlist"),
                    PromptSemantic.TapPaymentCost to
                        payCosts(
                            PromptSemantic.TapPaymentCost,
                            PayCostsRouteKind.TapPayment,
                            "tap-payment",
                            tapPayment = checkNotNull(TapPaymentDescriptor.grounded(TapPaymentKind.TotalPower, 2)),
                        ),
                    PromptSemantic.StationTapCost to payCosts(PromptSemantic.StationTapCost, PayCostsRouteKind.StationTapCost, "station"),
                    PromptSemantic.ReturnUnblockedAttackerCost to
                        payCosts(
                            PromptSemantic.ReturnUnblockedAttackerCost,
                            PayCostsRouteKind.SelectCostReturnAttacker,
                            "return-unblocked-attacker",
                        ),
                    PromptSemantic.ConvokeCost to
                        payCosts(
                            PromptSemantic.ConvokeCost,
                            PayCostsRouteKind.ConvokeCost,
                            "convoke",
                            ManaSourcePaymentKind.Convoke,
                        ),
                    PromptSemantic.ImproviseCost to
                        payCosts(
                            PromptSemantic.ImproviseCost,
                            PayCostsRouteKind.ImproviseCost,
                            "improvise",
                            ManaSourcePaymentKind.Improvise,
                        ),
                    PromptSemantic.WaterbendCost to
                        payCosts(
                            PromptSemantic.WaterbendCost,
                            PayCostsRouteKind.WaterbendCost,
                            "waterbend",
                            ManaSourcePaymentKind.Waterbend,
                        ),
                    PromptSemantic.MutateTopBottom to
                        cardSelect(PromptSemantic.MutateTopBottom, CardSelectKind.MutateTopBottom),
                    PromptSemantic.LearnLesson to
                        cardSelect(PromptSemantic.LearnLesson, CardSelectKind.Learn),
                    PromptSemantic.StaticColorChoice to staticChoice(PromptSemantic.StaticColorChoice, StaticChoiceKind.Color),
                    PromptSemantic.StaticSubtypeChoice to staticChoice(PromptSemantic.StaticSubtypeChoice, StaticChoiceKind.Subtype),
                    PromptSemantic.StaticParityChoice to
                        staticChoice(PromptSemantic.StaticParityChoice, StaticChoiceKind.Parity),
                    PromptSemantic.StaticKeywordChoice to
                        staticChoice(PromptSemantic.StaticKeywordChoice, StaticChoiceKind.Keyword),
                    PromptSemantic.StaticCardTypeChoice to
                        staticChoice(PromptSemantic.StaticCardTypeChoice, StaticChoiceKind.CardType),
                )

            PromptSemantic.entries
                .associateWith { semantic ->
                    PromptRouteResolver.resolve(
                        semantic,
                        tapPayment =
                            TapPaymentDescriptor
                                .grounded(TapPaymentKind.TotalPower, 2)
                                .takeIf { semantic == PromptSemantic.TapPaymentCost },
                    )
                }.shouldContainExactly(expected)
        }

        test("Generic fallback resolves once from candidate presence") {
            listOf(
                PromptRouteResolver.resolve(PromptSemantic.Generic, hasCandidateRefs = true)::class,
                PromptRouteResolver.resolve(PromptSemantic.Generic, hasCandidateRefs = false)::class,
            ) shouldBe
                listOf(
                    ResolvedPromptRoute.CompatibilityCostSelection::class,
                    ResolvedPromptRoute.AutoResolve::class,
                )
        }

        test("Resolution route uses candidate kind, zone, and ability shape") {
            val projected =
                ResolutionRouteInput(
                    optionCount = 2,
                    candidateCount = 2,
                    candidateKinds = setOf(PromptCandidateKind.Card),
                    candidateZones = setOf("Library"),
                    abilityShape = ResolutionAbilityShape.Dig,
                    allCandidatesProjectable = false,
                )
            val visibleCards = projected.copy(candidateZones = setOf("Battlefield"), allCandidatesProjectable = true)
            val mixed = projected.copy(candidateKinds = setOf(PromptCandidateKind.Card, PromptCandidateKind.Player))
            val incomplete = projected.copy(candidateCount = 1)
            val otherAbility = projected.copy(abilityShape = ResolutionAbilityShape.Other)

            listOf(
                PromptRouteResolver.resolve(PromptSemantic.SelectNResolution, resolutionInput = projected)::class,
                PromptRouteResolver.resolve(PromptSemantic.SelectNResolution, resolutionInput = visibleCards)::class,
                PromptRouteResolver.resolve(PromptSemantic.SelectNResolution, resolutionInput = mixed)::class,
                PromptRouteResolver.resolve(PromptSemantic.SelectNResolution, resolutionInput = incomplete)::class,
                PromptRouteResolver.resolve(PromptSemantic.SelectNResolution, resolutionInput = otherAbility)::class,
            ) shouldBe
                listOf(
                    ResolvedPromptRoute.CardSelect::class,
                    ResolvedPromptRoute.CardSelect::class,
                    ResolvedPromptRoute.UnclassifiedEntityChoice::class,
                    ResolvedPromptRoute.UnclassifiedEntityChoice::class,
                    ResolvedPromptRoute.UnclassifiedEntityChoice::class,
                )

            (
                PromptRouteResolver.resolve(PromptSemantic.SelectNResolution, resolutionInput = visibleCards) as
                    ResolvedPromptRoute.CardSelect
            ).descriptor.kind shouldBe CardSelectKind.ResolutionMapped
        }
    })

private val dynamicShape =
    SelectNShape(SelectionContext.Resolution_a163, SelectionListType.Dynamic, OptionContext.Resolution_a9d7)

private fun selectN(
    semantic: PromptSemantic,
    shape: SelectNShape = dynamicShape,
): ResolvedPromptRoute.UnclassifiedEntityChoice = ResolvedPromptRoute.UnclassifiedEntityChoice(SelectNPromptRoute(semantic, shape))

private fun cardSelect(
    semantic: PromptSemantic,
    kind: CardSelectKind,
    sentiment: Int? = null,
): ResolvedPromptRoute.CardSelect = ResolvedPromptRoute.CardSelect(CardSelectPromptRoute(semantic, kind, choiceResultSentiment = sentiment))

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
