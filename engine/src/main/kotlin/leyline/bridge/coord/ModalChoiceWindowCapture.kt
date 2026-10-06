package leyline.bridge.coord

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.ModalChoiceAiContext
import leyline.bridge.handoff.ModalChoiceOptionValue
import leyline.bridge.handoff.ModalChoiceWindowValue
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.manaTokenToPair
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/** Engine-thread capture for one immutable modal choice window. */
internal class ModalChoiceWindowCapture(
    private val owner: MatchCutCoordinator,
) {
    data class Initial(
        val value: ModalChoiceWindowValue,
        val handlesByOptionIndex: Map<Int, AbilitySub>,
        val aiContext: ModalChoiceAiContext,
    )

    fun capture(
        request: PromptRequest,
        possible: List<AbilitySub>,
        sourceCard: Card,
        sourceAbility: SpellAbility,
    ): Initial {
        check(request.route is ResolvedPromptRoute.ModalChoice) { "ModalChoice route required" }
        check(possible.isNotEmpty()) { "ModalChoice requires at least one possible option" }
        check(request.options.size == possible.size) { "ModalChoice labels must match Forge handles" }
        check(request.min in 0..request.max) { "Invalid ModalChoice cardinality" }
        check(request.defaultIndex in possible.indices) { "Invalid ModalChoice default option" }

        val sourceForgeCardId = ForgeCardId(sourceCard.id)
        val sourceCardGrpId =
            owner.bridge.cardRepository.findGrpIdByName(sourceCard.name)
                ?: error("ModalChoice source card '${sourceCard.name}' has no grpId")
        val modalInfo =
            owner.bridge.cardRepository.lookupModalOptions(sourceCardGrpId)
                ?: error("ModalChoice source card grpId=$sourceCardGrpId has no modal options")
        val fullList = sourceAbility.getAdditionalAbilityList("Choices")
        val possibleSet = possible.toSet()
        val possibleOptions =
            possible.mapIndexed { optionIndex, sub ->
                val fullIndex =
                    if (fullList == null) {
                        optionIndex
                    } else {
                        fullList.indexOf(sub).also { index ->
                            check(index >= 0) { "ModalChoice option is not present in Forge Choices" }
                        }
                    }
                check(fullIndex in modalInfo.childGrpIds.indices) {
                    "ModalChoice option index=$fullIndex is outside child grpId list"
                }
                ModalChoiceOptionValue(
                    fullIndex = fullIndex,
                    grpId = modalInfo.childGrpIds[fullIndex],
                    cost = parseModeCost(sub.getParam("ModeCost")),
                )
            }
        val excludedOptions =
            fullList
                ?.mapIndexedNotNull { fullIndex, sub ->
                    if (sub in possibleSet) return@mapIndexedNotNull null
                    ModalChoiceOptionValue(
                        fullIndex = fullIndex,
                        grpId = modalInfo.childGrpIds.getOrNull(fullIndex) ?: return@mapIndexedNotNull null,
                        cost = parseModeCost(sub.getParam("ModeCost")),
                    )
                }.orEmpty()
        return Initial(
            ModalChoiceWindowValue(
                sourceForgeCardId = sourceForgeCardId,
                sourceCardGrpId = sourceCardGrpId,
                sourceForgeAbilityId = sourceAbility.id,
                parentGrpId = modalInfo.parentGrpId,
                ctoGrpId = if (sourceAbility.isTrigger) modalInfo.parentGrpId else sourceCardGrpId,
                ctoId = 2,
                min = request.min,
                max = request.max,
                defaultOptionIndex = request.defaultIndex,
                allowRepeat = request.allowRepeat,
                possible = possibleOptions,
                excluded = excludedOptions,
                triggered = sourceAbility.isTrigger,
                isVote = request.route.semantic == PromptSemantic.VoteChoice,
                allowCancel =
                    request.route.semantic != PromptSemantic.VoteChoice &&
                        sourceAbility.api != ApiType.GenericChoice &&
                        sourceAbility.api != ApiType.VillainousChoice,
            ),
            handlesByOptionIndex = possible.indices.associateWith(possible::get),
            aiContext = ModalChoiceAiContext(sourceAbility, possible.toList(), possibleOptions.map { it.fullIndex }),
        )
    }

    private fun parseModeCost(text: String?): List<Pair<ManaColor, Int>> {
        if (text.isNullOrBlank()) return emptyList()
        val counts = mutableMapOf<ManaColor, Int>()
        text.trim().split(Regex("\\s+")).forEach { token ->
            if (token == "0") {
                counts[ManaColor.Generic] = 0
            } else {
                manaTokenToPair(token)?.let { counts.merge(it.first, it.second, Int::plus) }
            }
        }
        return counts.toList()
    }
}
