package leyline.bridge.coord

import forge.card.ColorSet
import forge.card.ICardFace
import forge.game.card.Card
import forge.game.card.CounterType
import forge.game.player.PlayerController.BinaryChoiceType
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import forge.game.trigger.WrappedAbility
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.types.StaticChoiceIds
import leyline.game.codes.KeywordGrpIds
import leyline.game.mapping.PromptIds
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.StaticList
import java.util.function.Predicate
import java.util.stream.Collectors

/** Routes Forge static enum choices through static-list SelectN prompts. */
class StaticChoiceCoordinator(
    private val bridge: InteractivePromptBridge,
) {
    /** Keep exact Forge vote handles without treating the choice as a casting mode. */
    fun vote(
        sa: SpellAbility,
        message: String,
        options: List<Any>,
        optional: Boolean,
    ): Any? {
        if (options.isEmpty()) return null
        val choices = options.map { it as AbilitySub }
        val request =
            PromptRequest(
                promptType = "choose_one",
                message = message,
                options = choices.map { it.description ?: it.toString() },
                min = if (optional) 0 else 1,
                max = 1,
                route = PromptRouteResolver.resolve(PromptSemantic.VoteChoice),
                sourceEntityId = sa.hostCard.id,
            )
        return bridge.requestModalChoice(request, choices, sa.hostCard, sa).firstOrNull()
    }

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
        val binary = kindOfChoice == BinaryChoiceType.TapOrUntap || kindOfChoice == BinaryChoiceType.AddOrRemove
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
                            when {
                                binary -> PromptSemantic.StaticBinaryChoice
                                parityIds != null -> PromptSemantic.StaticParityChoice
                                else -> PromptSemantic.Generic
                            },
                        ),
                    sourceEntityId = sourceEntityId(sa),
                    staticList = if (parityIds != null) StaticList.Parities else null,
                    staticOptionIds = if (binary) listOf(0, 1) else parityIds.orEmpty(),
                    promptParameterIds =
                        when (kindOfChoice) {
                            // Native prompts 15854/15855 say "target creature";
                            // this callback also controls lands, artifacts and
                            // whole groups. Use the supported literal labels.
                            BinaryChoiceType.TapOrUntap -> emptyList()
                            BinaryChoiceType.AddOrRemove -> listOf(PromptIds.ADD_COUNTER, PromptIds.REMOVE_COUNTER)
                            else -> emptyList()
                        },
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

        val colorChoices = colors.orderedColors.toList()
        val colorOptions = colorChoices.map { it.translatedName }
        val isManaChoice = sa?.isManaAbility() == true
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
                    route =
                        PromptRouteResolver.resolve(
                            if (isManaChoice) PromptSemantic.StaticManaColorChoice else PromptSemantic.StaticColorChoice,
                        ),
                    sourceEntityId = sourceEntityId(sa),
                    staticList = if (isManaChoice) StaticList.ManaColors else StaticList.Colors,
                    staticOptionIds =
                        colorChoices.mapNotNull {
                            if (isManaChoice) {
                                StaticChoiceIds.manaColorIdForMask(it.colorMask)
                            } else {
                                StaticChoiceIds.colorIdForMask(it.colorMask)
                            }
                        },
                ),
            )
        val idx = indices.firstOrNull() ?: return 0
        if (idx >= colorOptions.size) return 0
        return colorChoices[idx].colorMask
    }

    /** Choose a mana color from a colored subset plus Arena's Colorless entry. */
    fun chooseColorAllowColorless(
        message: String,
        card: Card,
        colors: ColorSet,
    ): Byte {
        if (colors.isColorless) return 0

        val choices =
            colors.orderedColors.map { it.getName().replaceFirstChar(Char::uppercase) to it.colorMask } +
                ("Colorless" to 0.toByte())
        val selected =
            bridge
                .requestStaticChoice(
                    PromptRequest(
                        promptType = "choose_one",
                        message = message,
                        options = choices.map { it.first },
                        min = 1,
                        max = 1,
                        defaultIndex = 0,
                        route = PromptRouteResolver.resolve(PromptSemantic.StaticCardColorChoice),
                        sourceEntityId = card.id.takeIf { it > 0 },
                        staticList = StaticList.CardColors,
                        staticOptionIds = choices.map { StaticChoiceIds.cardColorIdForName(it.first)!! },
                    ),
                ).firstOrNull()
        return selected?.let { choices.getOrNull(it)?.second } ?: choices.first().second
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
        // first valid type with no prompt at all. Creature-type names genuinely
        // are Forge subtypes, while Basic Land is Arena's separate full-list
        // workflow and must not be collapsed into SubTypes.
        val isCardType = kindOfType.equals("Card", ignoreCase = true)
        val isBasicLandType = kindOfType.filter(Char::isLetter).equals("basicland", ignoreCase = true)
        val staticList =
            when {
                isCardType -> StaticList.CardTypes
                isBasicLandType -> StaticList.BasicLandTypes
                else -> StaticList.SubTypes
            }
        val idFor: (String) -> Int? =
            when {
                isCardType -> StaticChoiceIds::cardTypeIdFor
                isBasicLandType -> StaticChoiceIds::basicLandTypeIdFor
                else -> StaticChoiceIds::subtypeIdFor
            }
        val semantic =
            when {
                isCardType -> PromptSemantic.StaticCardTypeChoice
                isBasicLandType -> PromptSemantic.StaticBasicLandTypeChoice
                else -> PromptSemantic.StaticSubtypeChoice
            }
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

    /**
     * Forge's counter chooser is a real decision point (for example, when a
     * spell removes one of several counter kinds).  Arena has a dedicated
     * CounterTypes static list, so do not inherit PCHuman's first-option
     * fallback.  Custom Forge counters are not present in the GRE enum; retain
     * Forge's deterministic fallback for that explicitly unsupported protocol
     * case and make it visible in the server log.
     */
    fun chooseCounterType(
        options: List<CounterType>,
        sa: SpellAbility?,
        prompt: String?,
        _params: MutableMap<String, Any>?,
    ): CounterType? {
        if (options.size <= 1) return options.firstOrNull()
        val choices = options.mapNotNull { option -> StaticChoiceIds.counterTypeIdFor(option.name)?.let { option to it } }
        if (choices.size != options.size || choices.map { it.second }.distinct().size != choices.size) {
            log.warn("chooseCounterType: unsupported custom counter options {}; retaining Forge fallback", options.map { it.name })
            return options.first()
        }
        val selected =
            bridge
                .requestStaticChoice(
                    PromptRequest(
                        promptType = "choose_counter_type",
                        message = prompt ?: "Choose a counter type",
                        options = choices.map { it.first.name },
                        min = 1,
                        max = 1,
                        defaultIndex = 0,
                        route = PromptRouteResolver.resolve(PromptSemantic.StaticCounterTypeChoice),
                        sourceEntityId = sourceEntityId(sa),
                        staticList = StaticList.CounterTypes,
                        staticOptionIds = choices.map { it.second },
                    ),
                ).firstOrNull()
        return selected?.let { choices.getOrNull(it)?.first } ?: choices.first().first
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

    /**
     * Route Forge's open-ended name-a-card callback through Arena's searchable
     * CardNames selector. The protocol values are title IDs, deliberately not
     * GRP/printing IDs (see StaticCardNameTranslation in the client).
     */
    fun chooseCardName(
        sa: SpellAbility,
        predicate: Predicate<ICardFace>,
        message: String,
    ): String = chooseCardFace(sa, allFaces().filter(predicate::test), message)?.name.orEmpty()

    fun chooseCardName(
        sa: SpellAbility,
        faces: List<ICardFace>,
        message: String,
    ): String = chooseCardFace(sa, faces, message)?.name.orEmpty()

    fun chooseSingleCardFace(
        sa: SpellAbility,
        predicate: Predicate<ICardFace>,
        message: String,
    ): ICardFace? = chooseCardFace(sa, allFaces().filter(predicate::test), message)

    fun chooseSingleCardFace(
        sa: SpellAbility,
        faces: List<ICardFace>,
        message: String,
    ): ICardFace? =
        if (faces.isNotEmpty() && faces.all { it.type.isDungeon }) {
            chooseDungeonFace(sa, faces, message)
        } else {
            chooseCardFace(sa, faces, message)
        }

    /** Arena's dungeon picker is keyed by printing grpIds, not CardNames title ids. */
    private fun chooseDungeonFace(
        sa: SpellAbility,
        faces: List<ICardFace>,
        message: String,
    ): ICardFace? {
        val choices =
            faces
                .mapNotNull { face -> bridge.resolveCardGrpIdByName(face.name)?.let { face to it } }
                .distinctBy { it.second }
                .sortedBy { it.first.name }
        if (choices.isEmpty()) {
            log.warn("chooseDungeonFace: no Arena grpIds for {} Forge dungeon faces from {}", faces.size, sa.hostCard.name)
            return faces.firstOrNull()
        }
        val index =
            bridge
                .requestStaticChoice(
                    PromptRequest(
                        promptType = "choose_dungeon",
                        message = message,
                        options = choices.map { it.first.name },
                        min = 1,
                        max = 1,
                        defaultIndex = 0,
                        route = PromptRouteResolver.resolve(PromptSemantic.StaticDungeonChoice),
                        sourceEntityId = sourceEntityId(sa),
                        staticList = StaticList.None_a56d,
                        staticOptionIds = choices.map { it.second },
                    ),
                ).firstOrNull()
        return index?.let { choices.getOrNull(it)?.first } ?: choices.first().first
    }

    /** Route Venture's room ability choice through IdType.AbilityGrpId. */
    fun chooseDungeonRoom(
        sa: SpellAbility,
        options: List<SpellAbility>,
        message: String,
    ): SpellAbility? {
        val choices =
            options
                .mapNotNull { option ->
                    val room = option.getParamOrDefault("RoomName", option.description ?: option.toString())
                    val ability = (option as? WrappedAbility)?.wrappedAbility ?: option
                    bridge
                        .resolveAbilityIdentity(ability)
                        ?.abilityGrpId
                        ?.takeIf { it != 0 }
                        ?.let { option to (room to it) }
                }
        if (choices.size != options.size || choices.isEmpty()) {
            log.warn("chooseDungeonRoom: unable to resolve room ability ids for {} options", options.size)
            return options.firstOrNull()
        }
        val index =
            bridge
                .requestStaticChoice(
                    PromptRequest(
                        promptType = "choose_dungeon_room",
                        message = message,
                        options = choices.map { it.second.first },
                        min = 1,
                        max = 1,
                        defaultIndex = 0,
                        route = PromptRouteResolver.resolve(PromptSemantic.StaticDungeonRoomChoice),
                        sourceEntityId = sourceEntityId(sa),
                        staticList = StaticList.None_a56d,
                        staticOptionIds = choices.map { it.second.second },
                    ),
                ).firstOrNull()
        return index?.let { choices.getOrNull(it)?.first } ?: choices.first().first
    }

    private fun chooseCardFace(
        sa: SpellAbility,
        faces: List<ICardFace>,
        message: String,
    ): ICardFace? {
        val titleIds =
            bridge.resolveCardTitleIds(
                faces
                    .asSequence()
                    .map(ICardFace::getName)
                    .distinct()
                    .asIterable(),
            )
        val choices =
            faces
                .asSequence()
                .mapNotNull { face -> titleIds[face.name]?.let { titleId -> face to titleId } }
                // Multiple printings and some related Forge faces share one Arena
                // title. The client selector cannot distinguish those, so retain
                // the first face exactly as Forge's name-choice semantics do.
                .distinctBy { it.second }
                .sortedBy { it.first.name }
                .toList()
        if (choices.isEmpty()) {
            log.warn("chooseCardFace: no Arena title IDs for {} Forge faces from {}", faces.size, sa.hostCard.name)
            return faces.firstOrNull()
        }

        val index =
            bridge
                .requestStaticChoice(
                    PromptRequest(
                        promptType = "choose_card_name",
                        message = message,
                        options = choices.map { it.first.name },
                        min = 1,
                        max = 1,
                        defaultIndex = 0,
                        route = PromptRouteResolver.resolve(PromptSemantic.StaticCardNameChoice),
                        sourceEntityId = sourceEntityId(sa),
                        staticList = StaticList.CardNames,
                        staticOptionIds = choices.map { it.second },
                    ),
                ).firstOrNull()
        return index?.let { choices.getOrNull(it)?.first } ?: choices.first().first
    }

    private fun allFaces(): List<ICardFace> {
        forge.StaticData.instance().ensureAllCardsLoaded()
        return forge.StaticData
            .instance()
            .commonCards
            .streamAllFaces()
            .collect(Collectors.toList())
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
