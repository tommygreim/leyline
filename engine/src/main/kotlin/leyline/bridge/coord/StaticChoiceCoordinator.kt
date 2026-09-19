package leyline.bridge.coord

import forge.card.ColorSet
import forge.game.player.PlayerController.BinaryChoiceType
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.StaticChoiceIds
import leyline.game.codes.KeywordGrpIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.StaticList

/** Routes Forge static enum choices through static-list SelectN prompts. */
class StaticChoiceCoordinator(
    private val bridge: InteractivePromptBridge,
) {
    fun confirmAction(
        message: String,
        options: List<String>,
        sourceEntityId: Int?,
    ): Boolean {
        val parityIds = parityOptionIds(options)
        val result =
            requestChoice(
                PromptRequest(
                    promptType = "confirm",
                    message = message,
                    options = options,
                    min = 1,
                    max = 1,
                    defaultIndex = 0,
                    route =
                        PromptRouteResolver.resolve(
                            if (parityIds !=
                                null
                            ) {
                                PromptSemantic.StaticParityChoice
                            } else {
                                PromptSemantic.Generic
                            },
                        ),
                    sourceEntityId = sourceEntityId?.takeIf { it > 0 },
                    staticList = if (parityIds != null) StaticList.Parities else null,
                    staticOptionIds = parityIds.orEmpty(),
                ),
            )
        return result.firstOrNull() == 0
    }

    fun chooseBinary(
        sa: SpellAbility?,
        question: String?,
        kindOfChoice: BinaryChoiceType?,
        defaultVal: Boolean?,
    ): Boolean {
        val labels = binaryLabels(kindOfChoice)
        val parityIds = parityOptionIds(labels)
        val result =
            requestChoice(
                PromptRequest(
                    promptType = "confirm",
                    message = question ?: "Choose one",
                    options = labels,
                    min = 1,
                    max = 1,
                    defaultIndex = if (defaultVal != false) 0 else 1,
                    route =
                        PromptRouteResolver.resolve(
                            if (parityIds !=
                                null
                            ) {
                                PromptSemantic.StaticParityChoice
                            } else {
                                PromptSemantic.Generic
                            },
                        ),
                    sourceEntityId = sourceEntityId(sa),
                    staticList = if (parityIds != null) StaticList.Parities else null,
                    staticOptionIds = parityIds.orEmpty(),
                ),
            )
        return result.firstOrNull() == 0
    }

    fun chooseColor(
        message: String,
        sa: SpellAbility?,
        colors: ColorSet,
    ): Byte {
        val cntColors = colors.countColors()
        if (cntColors == 0) return 0
        if (cntColors == 1) return colors.color
        if (sa?.isManaAbility() == true) return colors.orderedColors.first().colorMask

        val colorChoices = colors.orderedColors.toList()
        val colorOptions = colorChoices.map { it.translatedName }
        log.debug("chooseColor: options={}", colorOptions)
        val indices =
            requestChoice(
                PromptRequest(
                    promptType = "choose_one",
                    message = message,
                    options = colorOptions,
                    min = 1,
                    max = 1,
                    defaultIndex = 0,
                    route = PromptRouteResolver.resolve(PromptSemantic.StaticColorChoice),
                    sourceEntityId = sourceEntityId(sa),
                    staticList = StaticList.Colors,
                    staticOptionIds = colorChoices.mapNotNull { StaticChoiceIds.colorIdForMask(it.colorMask) },
                ),
            )
        val idx = indices.firstOrNull() ?: return 0
        if (idx >= colorOptions.size) return 0
        return colorChoices[idx].colorMask
    }

    fun chooseColors(
        message: String,
        sa: SpellAbility?,
        min: Int,
        max: Int,
        options: ColorSet,
    ): ColorSet {
        if (options.countColors() == 0) return ColorSet.fromMask(0)
        if (options.countColors() == min && min == max) return options

        val colorChoices = options.orderedColors.toList()
        val indices =
            requestChoice(
                PromptRequest(
                    promptType = "choose_colors",
                    message = message,
                    options = colorChoices.map { it.translatedName },
                    min = min,
                    max = max,
                    defaultIndex = 0,
                    route = PromptRouteResolver.resolve(PromptSemantic.StaticColorChoice),
                    sourceEntityId = sourceEntityId(sa),
                    staticList = StaticList.Colors,
                    staticOptionIds = colorChoices.mapNotNull { StaticChoiceIds.colorIdForMask(it.colorMask) },
                ),
            )
        val mask = indices.fold(0) { acc, idx -> acc or (colorChoices.getOrNull(idx)?.colorMask?.toInt() ?: 0) }
        return ColorSet.fromMask(mask)
    }

    /** Choose one protection quality that is representable by Arena's CardColors static list. */
    fun chooseProtectionType(
        sa: SpellAbility,
        options: List<String>,
    ): String {
        if (options.size <= 1) return options.firstOrNull().orEmpty()
        val choices = options.mapNotNull { option -> StaticChoiceIds.cardColorIdForName(option)?.let { option to it } }
        if (choices.size != options.size) {
            log.warn("chooseProtectionType: unsupported mixed protection options {}; using first option", options)
            return options.first()
        }

        val selected =
            bridge
                .requestStaticChoice(
                    PromptRequest(
                        promptType = "choose_one",
                        message = "Choose a protection quality",
                        options = choices.map { it.first },
                        min = 1,
                        max = 1,
                        defaultIndex = 0,
                        route = PromptRouteResolver.resolve(PromptSemantic.StaticCardColorChoice),
                        sourceEntityId = sourceEntityId(sa),
                        staticList = StaticList.CardColors,
                        staticOptionIds = choices.map { it.second },
                    ),
                ).firstOrNull()
        return selected?.let { choices.getOrNull(it)?.first } ?: choices.first().first
    }

    fun chooseSomeType(
        kindOfType: String,
        sa: SpellAbility?,
        validTypes: Collection<String>,
        isOptional: Boolean,
    ): String? {
        // "Card" (ChooseTypeEffect's Type$ Card — Artifact/Creature/Land/...) is
        // a different id domain from every other kindOfType (creature/land/
        // artifact subtypes, all part of the same SubType space): subtypeIdFor
        // never matches a card-type name, so before this every "Card" choice
        // silently fell through to the no-choices branch below and picked the
        // first valid type with no prompt at all. Everything else keeps using
        // SubTypes — creature-type names genuinely are Forge subtypes, so that
        // domain is already correct for them.
        val isCardType = kindOfType == "Card"
        val staticList = if (isCardType) StaticList.CardTypes else StaticList.SubTypes
        val idFor: (String) -> Int? = if (isCardType) StaticChoiceIds::cardTypeIdFor else StaticChoiceIds::subtypeIdFor
        val semantic = if (isCardType) PromptSemantic.StaticCardTypeChoice else PromptSemantic.StaticSubtypeChoice
        val choices =
            validTypes
                .sorted()
                .mapNotNull { type -> idFor(type)?.let { id -> type to id } }
        if (choices.isEmpty()) return if (isOptional) null else validTypes.firstOrNull()

        val idx =
            requestChoice(
                PromptRequest(
                    promptType = "choose_type",
                    message = "Choose a ${kindOfType.lowercase()} type",
                    options = choices.map { it.first },
                    min = if (isOptional) 0 else 1,
                    max = 1,
                    defaultIndex = 0,
                    route = PromptRouteResolver.resolve(semantic),
                    sourceEntityId = sourceEntityId(sa),
                    staticList = staticList,
                    staticOptionIds = choices.map { it.second },
                ),
            ).firstOrNull()
        return idx?.let { choices.getOrNull(it)?.first } ?: if (isOptional) null else choices.first().first
    }

    /** Choose one Forge pump keyword through Arena's keyword static-list domain. */
    fun chooseKeywordForPump(
        options: List<String>,
        sa: SpellAbility,
        prompt: String,
    ): String {
        if (options.size <= 1) return options.firstOrNull().orEmpty()
        val choices = options.mapNotNull { keyword -> KeywordGrpIds.forKeyword(keyword)?.let { keyword to it } }
        if (choices.size != options.size) {
            log.warn("chooseKeywordForPump: unmapped keyword options {}; using first option", options)
            return options.first()
        }

        val selected =
            bridge
                .requestStaticChoice(
                    PromptRequest(
                        promptType = "choose_one",
                        message = prompt,
                        options = choices.map { it.first },
                        min = 1,
                        max = 1,
                        defaultIndex = 0,
                        route = PromptRouteResolver.resolve(PromptSemantic.StaticKeywordChoice),
                        sourceEntityId = sourceEntityId(sa),
                        staticList = StaticList.Keywords,
                        staticOptionIds = choices.map { it.second },
                    ),
                ).firstOrNull()
        return selected?.let { choices.getOrNull(it)?.first } ?: choices.first().first
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun binaryLabels(kindOfChoice: BinaryChoiceType?): List<String> =
        when (kindOfChoice) {
            BinaryChoiceType.HeadsOrTails -> listOf("Heads", "Tails")
            BinaryChoiceType.TapOrUntap -> listOf("Tap", "Untap")
            BinaryChoiceType.OddsOrEvens -> listOf("Odds", "Evens")
            BinaryChoiceType.UntapOrLeaveTapped -> listOf("Untap", "Leave Tapped")
            BinaryChoiceType.PlayOrDraw -> listOf("Play", "Draw")
            BinaryChoiceType.LeftOrRight -> listOf("Left", "Right")
            BinaryChoiceType.AddOrRemove -> listOf("Add Counter", "Remove Counter")
            BinaryChoiceType.IncreaseOrDecrease -> listOf("Increase", "Decrease")
            else -> listOf("Yes", "No")
        }

    private fun parityOptionIds(labels: List<String>): List<Int>? {
        if (labels.size != 2) return null
        val ids = labels.map { StaticChoiceIds.parityIdForName(it) ?: return null }
        return ids.takeIf { it.toSet().size == 2 }
    }

    private fun sourceEntityId(sa: SpellAbility?): Int? = sa?.hostCard?.id?.takeIf { it > 0 }

    private fun requestChoice(request: PromptRequest): List<Int> =
        if (request.route is ResolvedPromptRoute.StaticChoice) {
            bridge.requestStaticChoice(request)
        } else {
            bridge.requestChoice(request)
        }

    companion object {
        private val log = LoggerFactory.getLogger(StaticChoiceCoordinator::class.java)
    }
}
