package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.AlternativeCost
import forge.game.spellability.SpellAbility
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto

class ChooseSingleEntityPlannerTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("mutate uses special route") {
            val plan = planFor(mutateSa())

            plan.routePolicy shouldBe ChooseSingleEntityRoutePolicy.MutateTopCard
        }

        test("active reveal over card options uses special route") {
            val plan = planFor(genericSa(), activeReveal = true)

            plan.routePolicy shouldBe ChooseSingleEntityRoutePolicy.ActiveReveal
        }

        test("special routes keep precedence over mandatory single-option auto-return") {
            assertSoftly {
                planFor(mutateSa(), activeReveal = true, optionCount = 1).routePolicy shouldBe
                    ChooseSingleEntityRoutePolicy.MutateTopCard
                planFor(genericSa(), activeReveal = true, optionCount = 1).routePolicy shouldBe
                    ChooseSingleEntityRoutePolicy.ActiveReveal
            }
        }

        test("mandatory single card remains an explicit selection") {
            val plan = planFor(genericSa(), optionCount = 1, isOptional = false)

            assertSoftly(plan) {
                routePolicy shouldBe ChooseSingleEntityRoutePolicy.Prompt
                candidateRefsPolicy shouldBe CandidateRefsPolicy.SelectableAndUnfilteredForResolution
            }
        }

        test("a mandatory card already selected as the spell target does not prompt again") {
            val target = Card(10, null).also { it.name = "Target" }
            val ability = genericSa().also { it.targets.add(target) }

            planFor(ability, optionCount = 1).routePolicy shouldBe ChooseSingleEntityRoutePolicy.AutoReturnFirst
            planFor(ability, optionCount = 1, isOptional = true).routePolicy shouldBe ChooseSingleEntityRoutePolicy.Prompt
        }

        test("mandatory single player retains automatic selection") {
            val candidates = listOf(PromptCandidateRefDto(index = 0, kind = PromptCandidateKind.Player, entityId = 1))
            planFor(genericSa(), optionCount = 1, candidateRefs = candidates).routePolicy shouldBe
                ChooseSingleEntityRoutePolicy.AutoReturnFirst
        }

        test("regular prompted semantics follow spell ability shape") {
            assertSoftly {
                planFor(legendRuleSa()).semantic shouldBe PromptSemantic.SelectNLegendRule
                planFor(changeZoneSa(), candidateRefs = libraryRefs).semantic shouldBe PromptSemantic.Search
                planFor(changeZoneSa(), optionCount = libraryRefs.size + 1, candidateRefs = libraryRefs).semantic shouldBe
                    PromptSemantic.SelectNResolution
                planFor(changeZoneSa()).semantic shouldBe PromptSemantic.SelectNResolution
                planFor(genericSa(), hasDelayedReveal = true).semantic shouldBe PromptSemantic.Search
                planFor(learnSa()).semantic shouldBe PromptSemantic.LearnLesson
                planFor(manifestDreadSa()).semantic shouldBe PromptSemantic.ManifestDread
                planFor(genericSa()).semantic shouldBe PromptSemantic.SelectNResolution
            }
        }

        test("only Dig over exact library Card candidates selects projected Resolution") {
            assertSoftly {
                planFor(digSa(), candidateRefs = libraryRefs).resolutionRouteInput?.isHiddenLibraryCardChoice shouldBe true
                planFor(digSa(), optionCount = libraryRefs.size + 1, candidateRefs = libraryRefs)
                    .resolutionRouteInput
                    ?.isHiddenLibraryCardChoice shouldBe false
                planFor(digSa()).resolutionRouteInput?.isHiddenLibraryCardChoice shouldBe false
                planFor(genericSa(), candidateRefs = libraryRefs).resolutionRouteInput?.isHiddenLibraryCardChoice shouldBe false
            }
        }

        test("source-aware paths include the host card id") {
            assertSoftly {
                planFor(learnSa()).sourceIdPolicy shouldBe SourceIdPolicy.HostCard
                planFor(manifestDreadSa()).sourceIdPolicy shouldBe SourceIdPolicy.HostCard
                planFor(digSa(), candidateRefs = libraryRefs).sourceIdPolicy shouldBe SourceIdPolicy.HostCard
                planFor(legendRuleSa()).sourceIdPolicy shouldBe SourceIdPolicy.None
                planFor(changeZoneSa()).sourceIdPolicy shouldBe SourceIdPolicy.None
                planFor(genericSa()).sourceIdPolicy shouldBe SourceIdPolicy.None
            }
        }

        test("Manifest Dread leaves private candidate projection to its bound route") {
            val plan = planFor(manifestDreadSa())

            assertSoftly(plan) {
                candidateRefsPolicy shouldBe CandidateRefsPolicy.Selectable
                candidateRefsPolicy.candidateRefs(refs) shouldBe refs
                candidateRefsPolicy.unfilteredRefs(refs, semantic).shouldBeEmpty()
            }
        }

        test("regular prompted paths include candidate refs") {
            val plan = planFor(genericSa())

            assertSoftly(plan) {
                routePolicy shouldBe ChooseSingleEntityRoutePolicy.Prompt
                candidateRefsPolicy shouldBe CandidateRefsPolicy.SelectableAndUnfilteredForResolution
                candidateRefsPolicy.candidateRefs(refs) shouldBe refs
                candidateRefsPolicy.unfilteredRefs(refs, semantic) shouldBe refs
            }
        }
    })

private val refs =
    listOf(
        PromptCandidateRefDto(index = 0, kind = PromptCandidateKind.Card, entityId = 10, zone = "Hand"),
        PromptCandidateRefDto(index = 1, kind = PromptCandidateKind.Card, entityId = 11, zone = "Hand"),
    )
private val libraryRefs = refs.map { it.copy(zone = "Library") }

private fun planFor(
    sa: SpellAbility,
    isOptional: Boolean = false,
    hasDelayedReveal: Boolean = false,
    optionCount: Int = refs.size,
    candidateRefs: List<PromptCandidateRefDto> = refs,
    activeReveal: Boolean = false,
): ChooseSingleEntityPlan =
    ChooseSingleEntityPlanner.plan(
        ChooseSingleEntityContext(
            sa = sa,
            isOptional = isOptional,
            hasDelayedReveal = hasDelayedReveal,
            optionCount = optionCount,
            candidateRefs = candidateRefs.take(optionCount),
            activeReveal = activeReveal,
            allCandidatesProjectable = candidateRefs.take(optionCount).all { it.zone != "Library" },
        ),
    )

private fun mutateSa(): SpellAbility = genericSa().also { it.setAlternativeCost(AlternativeCost.Mutate) }

private fun legendRuleSa(): SpellAbility = abilitySub(ApiType.InternalLegendaryRule)

private fun changeZoneSa(): SpellAbility = abilitySub(ApiType.ChangeZone)

private fun learnSa(): SpellAbility = abilitySub(ApiType.Learn)

private fun manifestDreadSa(): SpellAbility = abilitySub(ApiType.ManifestDread)

private fun genericSa(): SpellAbility = abilitySub(ApiType.ChooseCard)

private fun digSa(): SpellAbility = abilitySub(ApiType.Dig)

private fun abilitySub(api: ApiType): AbilitySub = AbilitySub(api, Card(7, null).also { it.name = "Host" }, null, emptyMap())
