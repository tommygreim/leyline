package leyline.acceptance

import leyline.game.data.KeywordAbilityIds

data class AcceptanceSuite(
    val name: String,
    val description: String?,
    val scenarios: List<AcceptanceScenario>,
)

data class AcceptanceScenario(
    val id: String,
    val puzzle: String?,
    val deckList: String?,
    val opponentDeckList: String?,
    val run: String?,
    val expect: String?,
    val steps: List<AcceptanceStep>,
    /** Headless-only control for scenarios that explicitly respond to their own stack. */
    val fullControl: Boolean = false,
)

sealed interface AcceptanceStep {
    val label: String
}

data class WaitStep(
    val conditions: List<AcceptanceCondition>,
) : AcceptanceStep {
    override val label: String = "wait"
}

data class ExpectStep(
    val conditions: List<AcceptanceCondition>,
) : AcceptanceStep {
    override val label: String = "expect"
}

data class PassUntilStep(
    val conditions: List<AcceptanceCondition>,
    val maxPasses: Int = 20,
) : AcceptanceStep {
    override val label: String = "pass_until"
}

data class ActivateStep(
    val card: String,
    val zone: AcceptanceZone = AcceptanceZone.Battlefield,
    val abilityIndex: Int = 0,
    val abilityGrpId: Int? = null,
) : AcceptanceStep {
    override val label: String = "activate $card"
}

data class ChooseStep(
    val optionalCost: AcceptanceCastingTimeOption?,
    val ctoId: Int?,
) : AcceptanceStep {
    override val label: String = optionalCost?.let { "choose ${it.yamlName}" } ?: "choose cto $ctoId"
}

data class ManaTypeChoicesStep(
    val choices: List<AcceptanceManaTypeChoice>,
) : AcceptanceStep {
    override val label: String = "mana_type_choices ${choices.joinToString { it.yamlName }}"
}

data class ModalChoiceStep(
    val index: Int,
) : AcceptanceStep {
    override val label: String = "modal_choice $index"
}

data class StaticChoiceStep(
    val id: Int,
) : AcceptanceStep {
    override val label: String = "static_choice $id"
}

data class OptionalActionStep(
    val accept: Boolean,
) : AcceptanceStep {
    override val label: String = if (accept) "optional_action accept" else "optional_action decline"
}

data object CancelActionStep : AcceptanceStep {
    override val label: String = "cancel_action"
}

data class TargetStep(
    val target: AcceptanceTargetSpec,
) : AcceptanceStep {
    override val label: String = "target ${target.label}"
}

data class TargetsStep(
    val targets: List<AcceptanceTargetSpec>,
) : AcceptanceStep {
    override val label: String = "targets ${targets.joinToString { it.label }}"
}

data class DistributionAssignment(
    val side: AcceptanceSide,
    val card: String,
    val amount: Int,
)

data class DistributeStep(
    val assignments: List<DistributionAssignment>,
) : AcceptanceStep {
    override val label: String = "distribute ${assignments.joinToString { "${it.card}=${it.amount}" }}"
}

data class BlockStep(
    val blocker: String,
    val attacker: String,
) : AcceptanceStep {
    override val label: String = "block $attacker with $blocker"
}

data class AttackStep(
    val cards: List<String>,
    val altCost: AcceptanceAltCost? = null,
    val target: AcceptanceTargetSpec? = null,
) : AcceptanceStep {
    override val label: String = "attack ${cards.joinToString()}${target?.let { " at ${it.label}" } ?: ""}"
}

data class TurnFaceUpStep(
    val card: String,
) : AcceptanceStep {
    override val label: String = "turn_face_up $card"
}

data class PlayLandStep(
    val card: String,
) : AcceptanceStep {
    override val label: String = "play_land $card"
}

data class PlayMdfcStep(
    val card: String,
) : AcceptanceStep {
    override val label: String = "play_mdfc $card"
}

data class CastStep(
    val card: String,
    val zone: AcceptanceZone = AcceptanceZone.Hand,
    val altCost: AcceptanceAltCost? = null,
) : AcceptanceStep {
    override val label: String = "cast $card"
}

data class CastMdfcStep(
    val card: String,
) : AcceptanceStep {
    override val label: String = "cast_mdfc $card"
}

data class CastAdventureStep(
    val card: String,
) : AcceptanceStep {
    override val label: String = "cast_adventure $card"
}

data class CastOmenStep(
    val card: String,
) : AcceptanceStep {
    override val label: String = "cast_omen $card"
}

data class SelectCostStep(
    val side: AcceptanceSide = AcceptanceSide.Ours,
    val zone: AcceptanceZone,
    val cards: List<String>,
) : AcceptanceStep {
    override val label: String = "select_cost ${cards.joinToString()}"
}

data class SelectCardStep(
    val side: AcceptanceSide = AcceptanceSide.Ours,
    val zone: AcceptanceZone,
    val card: String,
) : AcceptanceStep {
    override val label: String = "select_card $card"
}

data class SelectCardsStep(
    val side: AcceptanceSide = AcceptanceSide.Ours,
    val zone: AcceptanceZone,
    val cards: List<String>,
) : AcceptanceStep {
    override val label: String = "select_cards ${cards.joinToString()}"
}

data class SearchCardsStep(
    val side: AcceptanceSide = AcceptanceSide.Ours,
    val cards: List<String>,
) : AcceptanceStep {
    override val label: String = "search_cards ${cards.joinToString()}"
}

data class OrderCardsStep(
    val cards: List<String>,
) : AcceptanceStep {
    override val label: String = "order_cards ${cards.joinToString()}"
}

data object ResolveStackStep : AcceptanceStep {
    override val label: String = "resolve_stack"
}

data object AttackAllStep : AcceptanceStep {
    override val label: String = "attack_all"
}

sealed interface AcceptanceCondition {
    val label: String
}

data class ActionAvailableCondition(
    val type: AcceptanceActionType,
    val card: String,
    val altCost: AcceptanceAltCost? = null,
    val abilityGrpId: Int? = null,
) : AcceptanceCondition {
    override val label: String = "action ${type.yamlName} $card${altCost?.let { " via ${it.yamlName}" } ?: ""}"
}

data class ActionUnavailableCondition(
    val type: AcceptanceActionType,
    val card: String,
    val altCost: AcceptanceAltCost? = null,
    val abilityGrpId: Int? = null,
) : AcceptanceCondition {
    override val label: String = "action ${type.yamlName} $card unavailable${altCost?.let { " via ${it.yamlName}" } ?: ""}"
}

data class ZoneContainsCondition(
    val side: AcceptanceSide,
    val zone: AcceptanceZone,
    val card: String,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} ${zone.yamlName} contains $card"
}

data class ZoneNotContainsCondition(
    val side: AcceptanceSide,
    val zone: AcceptanceZone,
    val card: String,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} ${zone.yamlName} lacks $card"
}

data class ZoneCountAtLeastCondition(
    val side: AcceptanceSide,
    val zone: AcceptanceZone,
    val count: Int,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} ${zone.yamlName} count at least $count"
}

data class LifeTotalCondition(
    val side: AcceptanceSide,
    val value: Int,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} life is $value"
}

data class WinnerCondition(
    val side: AcceptanceSide,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} wins"
}

data class LoserCondition(
    val side: AcceptanceSide,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} loses"
}

data class BattlefieldStatsAtLeastCondition(
    val side: AcceptanceSide,
    val card: String,
    val power: Int,
    val toughness: Int,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} battlefield $card stats at least $power/$toughness"
}

data class BattlefieldStatsCondition(
    val side: AcceptanceSide,
    val card: String,
    val power: Int,
    val toughness: Int,
) : AcceptanceCondition {
    override val label: String = "${side.yamlName} battlefield $card stats $power/$toughness"
}

data class PhaseCondition(
    val phase: String,
) : AcceptanceCondition {
    override val label: String = "phase is $phase"
}

data class PromptCondition(
    val prompt: String,
    val promptId: Int? = null,
) : AcceptanceCondition {
    override val label: String = "prompt $prompt${promptId?.let { "#$it" } ?: ""} seen"
}

data class AnnotationSeenCondition(
    val type: String,
    val details: Map<String, String> = emptyMap(),
) : AcceptanceCondition {
    override val label: String = "annotation $type${if (details.isEmpty()) "" else " $details"} seen"
}

data class AnnotationSeenInPhaseCondition(
    val type: String,
    val phase: String,
) : AcceptanceCondition {
    override val label: String = "annotation $type seen in $phase"
}

data object StackEmptyCondition : AcceptanceCondition {
    override val label: String = "stack empty"
}

enum class AcceptanceActionType(
    val yamlName: String,
) {
    PlayLand("play_land"),
    PlayMdfc("play_mdfc"),
    Cast("cast"),
    CastAdventure("cast_adventure"),
    CastOmen("cast_omen"),
    CastMdfc("cast_mdfc"),
    Activate("activate"),
    ;

    companion object {
        fun parse(value: String): AcceptanceActionType =
            entries.firstOrNull { it.yamlName == value } ?: error("unknown action type: $value")
    }
}

enum class AcceptanceSide(
    val yamlName: String,
) {
    Ours("ours"),
    Opponent("opponent"),
    ;

    companion object {
        fun parse(value: String): AcceptanceSide = entries.firstOrNull { it.yamlName == value } ?: error("unknown side: $value")
    }
}

enum class AcceptanceZone(
    val yamlName: String,
) {
    Battlefield("battlefield"),
    Hand("hand"),
    Graveyard("graveyard"),
    Exile("exile"),
    Library("library"),
    Sideboard("sideboard"),
    Stack("stack"),
    ;

    companion object {
        fun parse(value: String): AcceptanceZone = entries.firstOrNull { it.yamlName == value } ?: error("unknown zone: $value")
    }
}

enum class AcceptanceCastingTimeOption(
    val yamlName: String,
) {
    Done("done"),
    Kicker("kicker"),
    AdditionalCost("additional_cost"),
    Bargain("bargain"),
    Casualty("casualty"),
    Cleave("cleave"),
    Overload("overload"),
    Blight("blight"),
    ;

    companion object {
        fun parse(value: String): AcceptanceCastingTimeOption =
            entries.firstOrNull { it.yamlName == value } ?: error("unknown casting time option: $value")
    }
}

enum class AcceptanceManaTypeChoice(
    val yamlName: String,
) {
    TwoGeneric("two_generic"),
    White("white"),
    Blue("blue"),
    Black("black"),
    Red("red"),
    Green("green"),
    ;

    companion object {
        fun parse(value: String): AcceptanceManaTypeChoice =
            entries.firstOrNull { it.yamlName == value } ?: error("unknown mana type choice: $value")
    }
}

enum class AcceptanceAltCost(
    val yamlName: String,
    val keywordAbilityId: Int,
) {
    Cleave("cleave", KeywordAbilityIds.CLEAVE),
    Disguise("disguise", KeywordAbilityIds.DISGUISE),
    Overload("overload", KeywordAbilityIds.OVERLOAD),
    Spectacle("spectacle", KeywordAbilityIds.SPECTACLE),
    Surge("surge", KeywordAbilityIds.SURGE),
    Evoke("evoke", KeywordAbilityIds.EVOKE),
    Blitz("blitz", KeywordAbilityIds.BLITZ),
    Dash("dash", KeywordAbilityIds.DASH),
    Emerge("emerge", KeywordAbilityIds.EMERGE),
    Escape("escape", KeywordAbilityIds.ESCAPE),
    Harmonize("harmonize", KeywordAbilityIds.HARMONIZE),
    Foretell("foretell", KeywordAbilityIds.FORETELL),
    Impending("impending", KeywordAbilityIds.IMPENDING),
    JumpStart("jump_start", KeywordAbilityIds.JUMP_START),
    Retrace("retrace", KeywordAbilityIds.RETRACE),
    Plot("plot", KeywordAbilityIds.PLOT),
    Warp("warp", KeywordAbilityIds.WARP),
    Sneak("sneak", KeywordAbilityIds.SNEAK),
    Enlist("enlist", KeywordAbilityIds.ENLIST),
    Airbend("airbend", KeywordAbilityIds.AIRBEND),
    ;

    companion object {
        fun parse(value: String): AcceptanceAltCost = entries.firstOrNull { it.yamlName == value } ?: error("unknown alt cost: $value")
    }
}

sealed interface AcceptanceTargetSpec {
    val label: String
}

data class PlayerTargetSpec(
    val side: AcceptanceSide,
) : AcceptanceTargetSpec {
    override val label: String = side.yamlName
}

data class CardTargetSpec(
    val side: AcceptanceSide,
    val zone: AcceptanceZone,
    val card: String,
) : AcceptanceTargetSpec {
    override val label: String = "${side.yamlName} ${zone.yamlName} $card"
}
