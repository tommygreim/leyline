package leyline.bridge.coord

import forge.game.card.Card
import forge.game.keyword.Keyword
import forge.game.keyword.KeywordInterface
import forge.game.replacement.ReplacementEffect
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ReplacementOptionValue
import leyline.bridge.handoff.ReplacementWindowValue
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ActivatedActionEmitter

/** Captures a projectable, keyword-backed replacement window on the engine thread. */
internal class ReplacementWindowCapture(
    private val owner: MatchCutCoordinator,
) {
    data class Initial(
        val value: ReplacementWindowValue,
        val handlesByOption: Map<Int, ReplacementEffect>,
    )

    @Suppress("ReturnCount")
    fun initial(
        request: PromptRequest,
        possibleReplacers: List<ReplacementEffect>,
    ): Initial? {
        if (request.route !is ResolvedPromptRoute.SelectReplacement) return null
        if (possibleReplacers.size < 2 || possibleReplacers.size != request.options.size) return null
        // All options must be the same supported keyword — a mixed window (e.g. a
        // Madness replacement competing with a Dredge one) isn't a scenario this
        // card pool can produce, so it falls through to Forge's own auto-pick
        // rather than guessing at an ordering/UX for an unverified shape.
        var sharedKeyword: Keyword? = null
        val options =
            possibleReplacers.mapIndexed { index, effect ->
                val host = effect.hostCard ?: return null
                val keywordInstance = ownerKeyword(effect, host) ?: return null
                val keyword = keywordInstance.keyword
                if (keyword != Keyword.MADNESS && keyword != Keyword.DREDGE) return null
                if (sharedKeyword == null) {
                    sharedKeyword = keyword
                } else if (sharedKeyword != keyword) {
                    return null
                }
                val hostId = ForgeCardId(host.id)
                val instanceId = owner.bridge.peekInstanceId(hostId) ?: return null
                if (instanceId.value == 0) return null
                val grpId = owner.bridge.resolveGrpId(host)
                if (grpId == 0) return null
                val cardData = owner.bridge.cardRepository.findByGrpId(grpId) ?: return null
                // A keyword granted onto other cards by a static ability (e.g. The
                // Necrobloom's "land cards in your graveyard have dredge 2") carries
                // its per-amount ability row on the granting card, not on the
                // affected host's own printed data.
                val grantingCard = keywordInstance.getStatic()?.hostCard
                val abilitySourceGrpId = grantingCard?.let { owner.bridge.resolveGrpId(it) } ?: grpId
                if (abilitySourceGrpId == 0) return null
                val abilityGrpId =
                    owner.bridge.cardRepository.findKeywordAbilityGrpId(abilitySourceGrpId, keywordAbilityId(keyword)) ?: return null
                val uniqueAbilityId =
                    ActivatedActionEmitter.uniqueAbilityIdFor(
                        cardData,
                        abilityGrpId,
                        fallbackWhenUnmapped = grantingCard != null,
                    ) ?: return null
                ReplacementOptionValue(index, hostId, uniqueAbilityId, abilityGrpId)
            }
        if (options.map { it.hostForgeCardId }.distinct().size != options.size) return null
        val defaultIndex = request.defaultIndex.takeIf { it in options.indices } ?: return null
        return Initial(
            ReplacementWindowValue(options, defaultIndex, allDredge = sharedKeyword == Keyword.DREDGE),
            possibleReplacers.mapIndexed { index, effect -> index to effect }.toMap(),
        )
    }

    private fun ownerKeyword(
        effect: ReplacementEffect,
        host: Card,
    ): KeywordInterface? = host.keywords.firstOrNull { it.replacements.any { replacement -> replacement === effect } }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun keywordAbilityId(keyword: Keyword): Int =
        when (keyword) {
            Keyword.MADNESS -> KeywordAbilityIds.MADNESS
            Keyword.DREDGE -> KeywordAbilityIds.DREDGE
            else -> error("Unsupported replacement keyword: $keyword")
        }
}
