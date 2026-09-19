package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto

class ChooseCardsForEffectPlannerTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("renamed host with ChooseCard TriggeredCards and ChosenCard suspect subability plans SuspectChoice") {
            val sa = suspectChoiceSa(hostName = "Different Card Name")

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeTrue()

            val plan = ChooseCardsForEffectPlanner.plan(context(sa))
            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.SuspectChoice
                forcePrompt.shouldBeTrue()
                candidateRefsPolicy shouldBe CandidateRefsPolicy.Selectable
                sourceIdPolicy shouldBe SourceIdPolicy.HostCard
                mandatoryChoicePolicy shouldBe MandatoryChoicePolicy.PromptWhenSatisfied
            }
        }

        test("qualified TriggeredCards token and Suspected attribute plans SuspectChoice") {
            val sa =
                suspectChoiceSa(
                    definedCards = "TriggeredCards.Creature",
                    attributes = "Suspected",
                )

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeTrue()
            ChooseCardsForEffectPlanner
                .plan(context(sa))
                .semantic shouldBe PromptSemantic.SuspectChoice
        }

        test("case-insensitive ChosenCard and Suspect tokens plan SuspectChoice") {
            val sa =
                chooseCardSa(
                    definedCards = "triggeredcards.artifact",
                    subAbility = alterAttributeSa(mapOf("Defined" to "chosencard", "Attributes" to "suspect")),
                )

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeTrue()
            ChooseCardsForEffectPlanner
                .plan(context(sa))
                .semantic shouldBe PromptSemantic.SuspectChoice
        }

        test("triggered ChooseCard without ChosenCard suspect subability is a real resolution choice") {
            val sa =
                chooseCardSa(
                    subAbility = alterAttributeSa(mapOf("Defined" to "ChosenCard", "Attributes" to "Flying")),
                )

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeFalse()

            val plan = ChooseCardsForEffectPlanner.plan(context(sa))
            plan.semantic shouldBe PromptSemantic.SelectNResolution
            plan.mandatoryChoicePolicy shouldBe MandatoryChoicePolicy.AutoResolveWhenSatisfied
        }

        test("non-ChooseCard suspect effect is a real resolution choice") {
            val sa = alterAttributeSa(mapOf("Defined" to "ChosenCard", "Attributes" to "Suspect"))

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeFalse()

            val plan = ChooseCardsForEffectPlanner.plan(context(sa))
            plan.semantic shouldBe PromptSemantic.SelectNResolution
            plan.forcePrompt.shouldBeFalse()
        }

        test("ChooseCard suspecting self instead of ChosenCard is a real resolution choice") {
            val sa =
                chooseCardSa(
                    subAbility = alterAttributeSa(mapOf("Defined" to "Self", "Attributes" to "Suspected")),
                )

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeFalse()
            ChooseCardsForEffectPlanner
                .plan(context(sa))
                .semantic shouldBe PromptSemantic.SelectNResolution
        }

        test("AlterAttribute deactivating Suspect on ChosenCard is a real resolution choice") {
            val sa =
                chooseCardSa(
                    subAbility =
                        alterAttributeSa(
                            mapOf("Defined" to "ChosenCard", "Attributes" to "Suspect", "Activate" to "False"),
                        ),
                )

            SpellAbilityShapes.isSuspectChoice(sa).shouldBeFalse()
            ChooseCardsForEffectPlanner
                .plan(context(sa))
                .semantic shouldBe PromptSemantic.SelectNResolution
        }

        test("chooseCardsForEffect without a spell ability still prompts, but a forced single choice auto-resolves") {
            val plan = ChooseCardsForEffectPlanner.plan(context(sa = null))

            assertSoftly(plan) {
                semantic shouldBe PromptSemantic.SelectNResolution
                forcePrompt shouldBe false
                candidateRefsPolicy shouldBe CandidateRefsPolicy.SelectableAndUnfilteredForResolution
                sourceIdPolicy shouldBe SourceIdPolicy.HostCard
                mandatoryChoicePolicy shouldBe MandatoryChoicePolicy.AutoResolveWhenSatisfied
            }
        }

        test("ChangeZone cards-for-effect uses Search only for hidden library selections") {
            assertSoftly {
                ChooseCardsForEffectPlanner.plan(context(changeZoneSa(), candidateRefs = libraryRefs)).let { plan ->
                    plan.semantic shouldBe PromptSemantic.Search
                    plan.candidateRefsPolicy shouldBe CandidateRefsPolicy.Selectable
                    plan.sourceIdPolicy shouldBe SourceIdPolicy.HostCard
                }
                ChooseCardsForEffectPlanner
                    .plan(context(changeZoneSa(), optionCount = libraryRefs.size + 1, candidateRefs = libraryRefs))
                    .semantic shouldBe PromptSemantic.SelectNResolution
                ChooseCardsForEffectPlanner.plan(context(changeZoneSa())).let { plan ->
                    plan.semantic shouldBe PromptSemantic.SelectNResolution
                    plan.candidateRefsPolicy shouldBe CandidateRefsPolicy.SelectableAndUnfilteredForResolution
                    plan.sourceIdPolicy shouldBe SourceIdPolicy.HostCard
                }
            }
        }
    })

private fun context(
    sa: SpellAbility?,
    optionCount: Int = handRefs.size,
    candidateRefs: List<PromptCandidateRefDto> = handRefs,
    activeReveal: Boolean = false,
): ChooseCardsForEffectContext =
    ChooseCardsForEffectContext(
        sa = sa,
        optionCount = optionCount,
        candidateRefs = candidateRefs,
        activeReveal = activeReveal,
        allCandidatesProjectable = candidateRefs.all { it.zone != "Library" },
    )

private val handRefs = listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, 10, "Hand"))
private val libraryRefs = handRefs.map { it.copy(zone = "Library") }

private fun suspectChoiceSa(
    hostName: String = "Host",
    definedCards: String = "TriggeredCards",
    attributes: String = "Suspect",
): SpellAbility =
    chooseCardSa(
        hostName = hostName,
        definedCards = definedCards,
        subAbility = alterAttributeSa(mapOf("Defined" to "ChosenCard", "Attributes" to attributes)),
    )

private fun chooseCardSa(
    hostName: String = "Host",
    definedCards: String = "TriggeredCards",
    subAbility: AbilitySub,
): SpellAbility =
    abilitySub(
        api = ApiType.ChooseCard,
        hostName = hostName,
        params = mapOf("DefinedCards" to definedCards),
    ).also { it.setSubAbility(subAbility) }

private fun alterAttributeSa(params: Map<String, String>): AbilitySub = abilitySub(api = ApiType.AlterAttribute, params = params)

private fun changeZoneSa(): SpellAbility = abilitySub(api = ApiType.ChangeZone)

private fun abilitySub(
    api: ApiType,
    hostName: String = "Host",
    params: Map<String, String> = emptyMap(),
): AbilitySub = AbilitySub(api, Card(1, null).also { it.name = hostName }, null, params)
