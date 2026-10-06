package leyline.acceptance

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

object AcceptanceSuiteLoader {
    fun load(name: String): AcceptanceSuite = loadFromFile(resolveSuitePath(name))

    fun loadFromFile(path: Path): AcceptanceSuite = loadFromText(Files.readString(path))

    fun loadFromText(text: String): AcceptanceSuite {
        val root = Yaml().load<Any?>(text).asMap("suite")
        return AcceptanceSuite(
            name = root.requiredString("name", "suite"),
            description = root.optionalString("description", "suite"),
            scenarios = root.requiredList("scenarios", "suite").mapIndexed(::parseScenario),
        )
    }

    private fun resolveSuitePath(name: String): Path {
        val fileName = if (name.endsWith(".yaml")) name else "$name.yaml"
        return AcceptancePaths.resolve("data/puzzles/sets/$fileName", notFoundMessage = "suite not found: $fileName")
    }

    /** Every suite YAML in the acceptance suite catalog, for static validation of the full set. */
    internal fun suiteFiles(): List<Path> =
        Files.list(suiteSetsDirectory()).use { stream ->
            stream.filter { it.toString().endsWith(".yaml") }.sorted().toList()
        }

    private fun suiteSetsDirectory(): Path =
        AcceptancePaths.resolve("data/puzzles/sets", notFoundMessage = "suite sets directory not found", exists = Files::isDirectory)

    /** Whether a scenario's `puzzle` reference resolves to an existing fixture. */
    internal fun puzzleExists(puzzle: String): Boolean {
        val fileName = if (puzzle.endsWith(".pzl")) puzzle else "$puzzle.pzl"
        return AcceptancePaths.resolveOrNull("data/puzzles/$fileName") != null
    }

    private fun parseScenario(
        index: Int,
        raw: Any?,
    ): AcceptanceScenario {
        val context = "scenario[$index]"
        val map = raw.asMap(context)
        val puzzle = map.optionalString("puzzle", context)
        val deckList = map.optionalList("deck", context)?.toDeckList("$context.deck")
        require((puzzle != null) xor (deckList != null)) {
            "$context requires exactly one of puzzle or deck"
        }
        val opponentDeckList = map.optionalList("opponent_deck", context)?.toDeckList("$context.opponent_deck")
        require(opponentDeckList == null || deckList != null) { "$context.opponent_deck requires deck" }
        return AcceptanceScenario(
            id = map.requiredString("id", context),
            puzzle = puzzle,
            deckList = deckList,
            opponentDeckList = opponentDeckList,
            run = map.optionalString("run", context),
            expect = map.optionalString("expect", context),
            steps = map.optionalList("steps", context)?.mapIndexed { stepIndex, step -> parseStep(stepIndex, step) } ?: emptyList(),
            fullControl =
                map["headless"]?.asMap("$context.headless")?.get("full_control")?.asBoolean("$context.headless.full_control") ?: false,
            forgeCatalog =
                map["headless"]?.asMap("$context.headless")?.get("forge_catalog")?.asBoolean("$context.headless.forge_catalog") ?: false,
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun parseStep(
        index: Int,
        raw: Any?,
    ): AcceptanceStep {
        val context = "step[$index]"
        val map = raw.asMap(context)
        require(map.size == 1) { "$context must contain exactly one step key, got ${map.keys}" }
        val (key, value) = map.entries.single()
        return when (key) {
            "wait" -> WaitStep(parseConditions(value, "$context.wait"))
            "expect" -> ExpectStep(parseConditions(value, "$context.expect"))
            "pass_until" -> parsePassUntil(value, "$context.pass_until")
            "activate" -> parseActivate(value, "$context.activate")
            "choose" -> parseChoose(value, "$context.choose")
            "mana_type_choices" -> parseManaTypeChoices(value, "$context.mana_type_choices")
            "modal_choice" -> parseModalChoice(value, "$context.modal_choice")
            "static_choice" -> parseStaticChoice(value, "$context.static_choice")
            "optional_action" -> parseOptionalAction(value, "$context.optional_action")
            "cancel_action" -> CancelActionStep
            "target" -> TargetStep(parseTarget(value, "$context.target"))
            "targets" ->
                TargetsStep(
                    value.asList("$context.targets").mapIndexed {
                        targetIndex,
                        target,
                        ->
                        parseTarget(target, "$context.targets[$targetIndex]")
                    },
                )
            "distribute" -> parseDistribute(value, "$context.distribute")
            "select_cost" -> parseSelectCost(value, "$context.select_cost")
            "select_card" -> parseSelectCard(value, "$context.select_card")
            "select_cards" -> parseSelectCards(value, "$context.select_cards")
            "search_cards" -> parseSearchCards(value, "$context.search_cards")
            "order_cards" -> parseOrderCards(value, "$context.order_cards")
            "block" -> parseBlock(value, "$context.block")
            "attack" -> parseAttack(value, "$context.attack")
            "turn_face_up" -> TurnFaceUpStep(value.asString("$context.turn_face_up"))
            "play_land" -> PlayLandStep(value.asString("$context.play_land"))
            "play_mdfc" -> PlayMdfcStep(value.asString("$context.play_mdfc"))
            "cast" -> parseCast(value, "$context.cast")
            "cast_adventure" -> CastAdventureStep(value.asString("$context.cast_adventure"))
            "cast_omen" -> CastOmenStep(value.asString("$context.cast_omen"))
            "cast_mdfc" -> CastMdfcStep(value.asString("$context.cast_mdfc"))
            "resolve_stack" -> ResolveStackStep
            "attack_all" -> AttackAllStep
            else -> error("unknown step key: $key at $context")
        }
    }

    private fun parsePassUntil(
        raw: Any?,
        context: String,
    ): PassUntilStep {
        val map = raw.asMap(context)
        val maxPasses = map.optionalInt("max_passes", context) ?: 20
        val conditionMap = map.filterKeys { it != "max_passes" }
        return PassUntilStep(parseConditions(conditionMap, context), maxPasses)
    }

    private fun parseCast(
        raw: Any?,
        context: String,
    ): CastStep =
        when (raw) {
            is String -> CastStep(raw)
            else -> {
                val map = raw.asMap(context)
                CastStep(
                    card = map.requiredString("card", context),
                    zone = map.optionalString("zone", context)?.let(AcceptanceZone::parse) ?: AcceptanceZone.Hand,
                    altCost = map.optionalString("alt_cost", context)?.let(AcceptanceAltCost::parse),
                )
            }
        }

    private fun parseSelectCost(
        raw: Any?,
        context: String,
    ): SelectCostStep {
        val map = raw.asMap(context)
        return SelectCostStep(
            side = map.optionalString("side", context)?.let(AcceptanceSide::parse) ?: AcceptanceSide.Ours,
            zone = AcceptanceZone.parse(map.requiredString("zone", context)),
            cards = map.requiredList("cards", context).mapIndexed { index, item -> item.asString("$context.cards[$index]") },
        )
    }

    private fun parseSelectCard(
        raw: Any?,
        context: String,
    ): SelectCardStep {
        val map = raw.asMap(context)
        return SelectCardStep(
            side = map.optionalString("side", context)?.let(AcceptanceSide::parse) ?: AcceptanceSide.Ours,
            zone = AcceptanceZone.parse(map.requiredString("zone", context)),
            card = map.requiredString("card", context),
        )
    }

    private fun parseSelectCards(
        raw: Any?,
        context: String,
    ): SelectCardsStep {
        val map = raw.asMap(context)
        return SelectCardsStep(
            side = map.optionalString("side", context)?.let(AcceptanceSide::parse) ?: AcceptanceSide.Ours,
            zone = AcceptanceZone.parse(map.requiredString("zone", context)),
            cards = map.requiredList("cards", context).mapIndexed { index, item -> item.asString("$context.cards[$index]") },
        )
    }

    private fun parseSearchCards(
        raw: Any?,
        context: String,
    ): SearchCardsStep {
        val map = raw.asMap(context)
        return SearchCardsStep(
            side = map.optionalString("side", context)?.let(AcceptanceSide::parse) ?: AcceptanceSide.Ours,
            cards = map.requiredList("cards", context).mapIndexed { index, item -> item.asString("$context.cards[$index]") },
        )
    }

    private fun parseOrderCards(
        raw: Any?,
        context: String,
    ): OrderCardsStep =
        when (raw) {
            is List<*> -> OrderCardsStep(raw.mapIndexed { index, item -> item.asString("$context[$index]") })
            else -> {
                val map = raw.asMap(context)
                OrderCardsStep(
                    cards = map.requiredList("cards", context).mapIndexed { index, item -> item.asString("$context.cards[$index]") },
                )
            }
        }

    private fun parseActivate(
        raw: Any?,
        context: String,
    ): ActivateStep =
        when (raw) {
            is String -> ActivateStep(raw)
            else -> {
                val map = raw.asMap(context)
                ActivateStep(
                    card = map.requiredString("card", context),
                    zone = map.optionalString("zone", context)?.let(AcceptanceZone::parse) ?: AcceptanceZone.Battlefield,
                    abilityIndex = map.optionalInt("ability_index", context) ?: 0,
                    abilityGrpId = map.optionalInt("ability_grp_id", context),
                )
            }
        }

    private fun parseChoose(
        raw: Any?,
        context: String,
    ): ChooseStep {
        val map = raw.asMap(context)
        return ChooseStep(
            optionalCost = map.optionalString("optional_cost", context)?.let(AcceptanceCastingTimeOption::parse),
            ctoId = map.optionalInt("cto_id", context),
        )
    }

    private fun parseManaTypeChoices(
        raw: Any?,
        context: String,
    ): ManaTypeChoicesStep =
        ManaTypeChoicesStep(
            raw.asList(context).mapIndexed { index, item -> AcceptanceManaTypeChoice.parse(item.asString("$context[$index]")) },
        )

    private fun parseModalChoice(
        raw: Any?,
        context: String,
    ): ModalChoiceStep {
        val map = raw.asMap(context)
        return ModalChoiceStep(index = map.requiredInt("index", context))
    }

    private fun parseStaticChoice(
        raw: Any?,
        context: String,
    ): StaticChoiceStep {
        val map = raw.asMap(context)
        return StaticChoiceStep(id = map.requiredInt("id", context))
    }

    private fun parseOptionalAction(
        raw: Any?,
        context: String,
    ): OptionalActionStep {
        val map = raw.asMap(context)
        return OptionalActionStep(accept = map.requiredBoolean("accept", context))
    }

    private fun parseDistribute(
        raw: Any?,
        context: String,
    ): DistributeStep =
        DistributeStep(
            raw.asList(context).mapIndexed { index, item ->
                val assignmentContext = "$context[$index]"
                val map = item.asMap(assignmentContext)
                DistributionAssignment(
                    side = AcceptanceSide.parse(map.requiredString("side", assignmentContext)),
                    card = map.requiredString("card", assignmentContext),
                    amount = map.requiredInt("amount", assignmentContext),
                )
            },
        )

    private fun parseBlock(
        raw: Any?,
        context: String,
    ): BlockStep {
        val map = raw.asMap(context)
        return BlockStep(
            blocker = map.requiredString("blocker", context),
            attacker = map.requiredString("attacker", context),
        )
    }

    private fun parseAttack(
        raw: Any?,
        context: String,
    ): AttackStep {
        val map = raw.asMap(context)
        return AttackStep(
            cards = map.requiredList("cards", context).mapIndexed { index, item -> item.asString("$context.cards[$index]") },
            altCost = map.optionalString("alt_cost", context)?.let(AcceptanceAltCost::parse),
            target = map["target"]?.let { parseTarget(it, "$context.target") },
        )
    }

    private fun parseTarget(
        raw: Any?,
        context: String,
    ): AcceptanceTargetSpec =
        when (raw) {
            is String -> PlayerTargetSpec(AcceptanceSide.parse(raw))
            else -> {
                val map = raw.asMap(context)
                CardTargetSpec(
                    side = AcceptanceSide.parse(map.requiredString("side", context)),
                    zone = AcceptanceZone.parse(map.requiredString("zone", context)),
                    card = map.requiredString("card", context),
                )
            }
        }

    private fun parseConditions(
        raw: Any?,
        context: String,
    ): List<AcceptanceCondition> {
        val map = raw.asMap(context)
        require(map.isNotEmpty()) { "$context must contain at least one condition" }
        return map.flatMap { (key, value) ->
            if (key == "all") {
                value.asList("$context.all").mapIndexed { index, item ->
                    val itemMap = item.asMap("$context.all[$index]")
                    require(itemMap.size == 1) { "$context.all[$index] must contain exactly one condition key" }
                    val (itemKey, itemValue) = itemMap.entries.single()
                    parseCondition(itemKey, itemValue, "$context.all[$index]")
                }
            } else {
                listOf(parseCondition(key, value, context))
            }
        }
    }

    private fun parseCondition(
        key: String,
        value: Any?,
        context: String,
    ): AcceptanceCondition =
        when (key) {
            "action" -> parseActionAvailable(value, "$context.action")
            "action_not_available" -> parseActionUnavailable(value, "$context.action_not_available")
            "zone_contains" -> parseZoneContains(value, "$context.zone_contains")
            "zone_not_contains" -> parseZoneNotContains(value, "$context.zone_not_contains")
            "zone_count_at_least" -> parseZoneCountAtLeast(value, "$context.zone_count_at_least")
            "battlefield_stats" -> parseBattlefieldStats(value, "$context.battlefield_stats")
            "battlefield_stats_at_least" -> parseBattlefieldStatsAtLeast(value, "$context.battlefield_stats_at_least")
            "opponent_life" -> LifeTotalCondition(AcceptanceSide.Opponent, value.asInt("$context.opponent_life"))
            "our_life" -> LifeTotalCondition(AcceptanceSide.Ours, value.asInt("$context.our_life"))
            "winner" -> WinnerCondition(AcceptanceSide.parse(value.asString("$context.winner")))
            "loser" -> LoserCondition(AcceptanceSide.parse(value.asString("$context.loser")))
            "phase" -> PhaseCondition(value.asString("$context.phase"))
            "prompt" -> parsePrompt(value, "$context.prompt")
            "annotation_seen" -> parseAnnotationSeen(value, "$context.annotation_seen")
            "annotation_seen_in_phase" -> parseAnnotationSeenInPhase(value, "$context.annotation_seen_in_phase")
            "stack_empty" -> {
                require(value.asBoolean("$context.stack_empty")) { "$context.stack_empty only supports true" }
                StackEmptyCondition
            }
            else -> error("unknown condition key: $key at $context")
        }

    private fun parseActionAvailable(
        raw: Any?,
        context: String,
    ): ActionAvailableCondition {
        val map = raw.asMap(context)
        return ActionAvailableCondition(
            type = AcceptanceActionType.parse(map.requiredString("type", context)),
            card = map.requiredString("card", context),
            altCost = map.optionalString("alt_cost", context)?.let(AcceptanceAltCost::parse),
            abilityGrpId = map.optionalInt("ability_grp_id", context),
        )
    }

    private fun parseActionUnavailable(
        raw: Any?,
        context: String,
    ): ActionUnavailableCondition {
        val map = raw.asMap(context)
        return ActionUnavailableCondition(
            type = AcceptanceActionType.parse(map.requiredString("type", context)),
            card = map.requiredString("card", context),
            altCost = map.optionalString("alt_cost", context)?.let(AcceptanceAltCost::parse),
            abilityGrpId = map.optionalInt("ability_grp_id", context),
        )
    }

    private fun parseZoneContains(
        raw: Any?,
        context: String,
    ): ZoneContainsCondition {
        val map = raw.asMap(context)
        return ZoneContainsCondition(
            side = AcceptanceSide.parse(map.requiredString("side", context)),
            zone = AcceptanceZone.parse(map.requiredString("zone", context)),
            card = map.requiredString("card", context),
        )
    }

    private fun parseZoneNotContains(
        raw: Any?,
        context: String,
    ): ZoneNotContainsCondition {
        val map = raw.asMap(context)
        return ZoneNotContainsCondition(
            side = AcceptanceSide.parse(map.requiredString("side", context)),
            zone = AcceptanceZone.parse(map.requiredString("zone", context)),
            card = map.requiredString("card", context),
        )
    }

    private fun parseZoneCountAtLeast(
        raw: Any?,
        context: String,
    ): ZoneCountAtLeastCondition {
        val map = raw.asMap(context)
        return ZoneCountAtLeastCondition(
            side = AcceptanceSide.parse(map.requiredString("side", context)),
            zone = AcceptanceZone.parse(map.requiredString("zone", context)),
            count = map.requiredInt("count", context),
        )
    }

    private fun parseBattlefieldStatsAtLeast(
        raw: Any?,
        context: String,
    ): BattlefieldStatsAtLeastCondition {
        val map = raw.asMap(context)
        return BattlefieldStatsAtLeastCondition(
            side = AcceptanceSide.parse(map.requiredString("side", context)),
            card = map.requiredString("card", context),
            power = map.requiredInt("power", context),
            toughness = map.requiredInt("toughness", context),
        )
    }

    private fun parseBattlefieldStats(
        raw: Any?,
        context: String,
    ): BattlefieldStatsCondition {
        val map = raw.asMap(context)
        return BattlefieldStatsCondition(
            side = map.optionalString("side", context)?.let(AcceptanceSide::parse) ?: AcceptanceSide.Ours,
            card = map.requiredString("card", context),
            power = map.requiredInt("power", context),
            toughness = map.requiredInt("toughness", context),
        )
    }

    private fun parsePrompt(
        raw: Any?,
        context: String,
    ): PromptCondition =
        when (raw) {
            is String -> PromptCondition(raw)
            else -> {
                val map = raw.asMap(context)
                PromptCondition(
                    prompt = map.requiredString("type", context),
                    promptId = map.optionalInt("prompt_id", context),
                )
            }
        }

    private fun parseAnnotationSeenInPhase(
        raw: Any?,
        context: String,
    ): AnnotationSeenInPhaseCondition {
        val map = raw.asMap(context)
        return AnnotationSeenInPhaseCondition(
            type = map.requiredString("type", context),
            phase = map.requiredString("phase", context),
        )
    }

    private fun parseAnnotationSeen(
        raw: Any?,
        context: String,
    ): AnnotationSeenCondition {
        if (raw is String) return AnnotationSeenCondition(raw)
        val map = raw.asMap(context)
        val details =
            map["details"]
                ?.asMap("$context.details")
                ?.mapValues { (key, value) -> value.asScalarString("$context.details.$key") }
                .orEmpty()
        return AnnotationSeenCondition(
            type = map.requiredString("type", context),
            details = details,
        )
    }
}

private fun List<Any?>.toDeckList(context: String): String =
    mapIndexed { index, item -> item.asString("$context[$index]") }.joinToString("\n")

private fun Any?.asMap(context: String): Map<String, Any?> {
    val raw = this as? Map<*, *> ?: error("$context must be a map")
    return raw.mapKeys { (key, _) -> key as? String ?: error("$context has non-string key: $key") }
}

private fun Any?.asString(context: String): String = this as? String ?: error("$context must be a string")

private fun Any?.asInt(context: String): Int =
    when (this) {
        is Int -> this
        is Number -> toInt()
        else -> error("$context must be a number")
    }

private fun Any?.asBoolean(context: String): Boolean = this as? Boolean ?: error("$context must be a boolean")

private fun Any?.asScalarString(context: String): String =
    when (this) {
        is String, is Number, is Boolean -> toString()
        else -> error("$context must be a string, number, or boolean")
    }

private fun Any?.asList(context: String): List<Any?> = this as? List<Any?> ?: error("$context must be a list")

private fun Map<String, Any?>.requiredString(
    key: String,
    context: String,
): String {
    val value = this[key] ?: error("$context.$key is required")
    return value.asString("$context.$key")
}

private fun Map<String, Any?>.optionalString(
    key: String,
    context: String,
): String? = this[key]?.asString("$context.$key")

private fun Map<String, Any?>.optionalInt(
    key: String,
    context: String,
): Int? = this[key]?.asInt("$context.$key")

private fun Map<String, Any?>.requiredInt(
    key: String,
    context: String,
): Int {
    val value = this[key] ?: error("$context.$key is required")
    return value.asInt("$context.$key")
}

private fun Map<String, Any?>.requiredBoolean(
    key: String,
    context: String,
): Boolean {
    val value = this[key] ?: error("$context.$key is required")
    return value.asBoolean("$context.$key")
}

private fun Map<String, Any?>.requiredList(
    key: String,
    context: String,
): List<Any?> {
    val value = this[key] ?: error("$context.$key is required")
    return value as? List<Any?> ?: error("$context.$key must be a list")
}

private fun Map<String, Any?>.optionalList(
    key: String,
    context: String,
): List<Any?>? {
    val value = this[key] ?: return null
    return value as? List<Any?> ?: error("$context.$key must be a list")
}
