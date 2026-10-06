package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolutionAbilityShape
import leyline.bridge.handoff.ResolutionRouteInput
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto

enum class ChooseSingleEntityRoutePolicy {
    MutateTopCard,
    ActiveReveal,
    AutoReturnFirst,
    Prompt,
}

data class ChooseSingleEntityContext(
    val sa: SpellAbility?,
    val isOptional: Boolean,
    val hasDelayedReveal: Boolean,
    val optionCount: Int,
    val candidateRefs: List<PromptCandidateRefDto>,
    val activeReveal: Boolean,
    val allCandidatesProjectable: Boolean,
)

data class ChooseSingleEntityPlan(
    val routePolicy: ChooseSingleEntityRoutePolicy,
    val semantic: PromptSemantic,
    val min: Int,
    val max: Int,
    val candidateRefsPolicy: CandidateRefsPolicy,
    val sourceIdPolicy: SourceIdPolicy,
    val isSearch: Boolean,
    val isLearn: Boolean,
    val isLegendRule: Boolean,
    val resolutionRouteInput: ResolutionRouteInput?,
)

object ChooseSingleEntityPlanner {
    fun plan(context: ChooseSingleEntityContext): ChooseSingleEntityPlan {
        val isLegendRule = context.sa?.api == ApiType.InternalLegendaryRule
        val isLearn = context.sa?.api == ApiType.Learn
        val allOptionsAreCards =
            context.candidateRefs.size == context.optionCount &&
                context.candidateRefs.all { it.kind == PromptCandidateKind.Card }
        val soleCardIsAlreadyTargeted =
            context.optionCount == 1 &&
                allOptionsAreCards &&
                context.sa
                    ?.targets
                    ?.targetCards
                    ?.any { target -> target.id == context.candidateRefs.single().entityId } == true
        val resolutionInput =
            resolutionRouteInput(
                context.candidateRefs,
                context.optionCount,
                if (context.sa?.api == ApiType.Dig) ResolutionAbilityShape.Dig else ResolutionAbilityShape.Other,
                context.allCandidatesProjectable,
            )
        val isSearch =
            (context.sa?.api == ApiType.ChangeZone && resolutionInput.isCompleteLibraryCardChoice) ||
                context.hasDelayedReveal
        val semantic =
            when {
                isLegendRule -> PromptSemantic.SelectNLegendRule
                isLearn -> PromptSemantic.LearnLesson
                isSearch -> PromptSemantic.Search
                context.sa?.api == ApiType.ManifestDread -> PromptSemantic.ManifestDread
                else -> PromptSemantic.SelectNResolution
            }
        val route =
            when {
                context.sa?.isMutate == true -> ChooseSingleEntityRoutePolicy.MutateTopCard
                context.activeReveal && allOptionsAreCards -> ChooseSingleEntityRoutePolicy.ActiveReveal
                soleCardIsAlreadyTargeted && !context.isOptional -> ChooseSingleEntityRoutePolicy.AutoReturnFirst
                context.optionCount == 1 && !context.isOptional && !allOptionsAreCards -> ChooseSingleEntityRoutePolicy.AutoReturnFirst
                else -> ChooseSingleEntityRoutePolicy.Prompt
            }

        return ChooseSingleEntityPlan(
            routePolicy = route,
            semantic = semantic,
            min = if (context.isOptional) 0 else 1,
            max = 1,
            candidateRefsPolicy =
                if (route == ChooseSingleEntityRoutePolicy.Prompt) {
                    if (semantic == PromptSemantic.SelectNResolution && !resolutionInput.isHiddenLibraryCardChoice) {
                        CandidateRefsPolicy.SelectableAndUnfilteredForResolution
                    } else {
                        CandidateRefsPolicy.Selectable
                    }
                } else {
                    CandidateRefsPolicy.None
                },
            sourceIdPolicy =
                if (isLearn ||
                    context.sa?.api == ApiType.Clone ||
                    semantic == PromptSemantic.ManifestDread ||
                    resolutionInput.isHiddenLibraryCardChoice
                ) {
                    SourceIdPolicy.HostCard
                } else {
                    SourceIdPolicy.None
                },
            isSearch = isSearch,
            isLearn = isLearn,
            isLegendRule = isLegendRule,
            resolutionRouteInput = resolutionInput.takeIf { semantic == PromptSemantic.SelectNResolution },
        )
    }
}
