package leyline.acceptance

import forge.game.zone.ZoneType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

internal fun AcceptanceZone.toForgeZone(): ZoneType =
    when (this) {
        AcceptanceZone.Battlefield -> ZoneType.Battlefield
        AcceptanceZone.Hand -> ZoneType.Hand
        AcceptanceZone.Graveyard -> ZoneType.Graveyard
        AcceptanceZone.Exile -> ZoneType.Exile
        AcceptanceZone.Library -> ZoneType.Library
        AcceptanceZone.Sideboard -> ZoneType.Sideboard
        AcceptanceZone.Stack -> ZoneType.Stack
    }

internal fun AcceptanceCastingTimeOption.toProtoType(): CastingTimeOptionType =
    when (this) {
        AcceptanceCastingTimeOption.Done -> CastingTimeOptionType.Done
        AcceptanceCastingTimeOption.Kicker -> CastingTimeOptionType.Kicker
        AcceptanceCastingTimeOption.AdditionalCost -> CastingTimeOptionType.AdditionalCost
        AcceptanceCastingTimeOption.Bargain -> CastingTimeOptionType.Bargain
        AcceptanceCastingTimeOption.Casualty -> CastingTimeOptionType.Casualty
        AcceptanceCastingTimeOption.Cleave -> CastingTimeOptionType.CastThroughAbility
        AcceptanceCastingTimeOption.Overload -> CastingTimeOptionType.CastThroughAbility
        AcceptanceCastingTimeOption.Blight -> CastingTimeOptionType.ChooseOrCost
    }

/** Ordered by precedence: [promptName] reports the first type in this list that matches. */
private val PROMPT_TYPES: List<Pair<String, GREToClientMessage.() -> Boolean>> =
    listOf(
        "CastingTimeOptionsReq" to GREToClientMessage::hasCastingTimeOptionsReq,
        "DeclareAttackersReq" to GREToClientMessage::hasDeclareAttackersReq,
        "DeclareBlockersReq" to GREToClientMessage::hasDeclareBlockersReq,
        "DistributionReq" to GREToClientMessage::hasDistributionReq,
        "GroupReq" to GREToClientMessage::hasGroupReq,
        "OptionalActionMessage" to GREToClientMessage::hasOptionalActionMessage,
        "OrderReq" to GREToClientMessage::hasOrderReq,
        "PayCostsReq" to GREToClientMessage::hasPayCostsReq,
        "SearchReq" to GREToClientMessage::hasSearchReq,
        "SearchFromGroupsReq" to GREToClientMessage::hasSearchFromGroupsReq,
        "SelectNReq" to GREToClientMessage::hasSelectNReq,
        "SelectTargetsReq" to GREToClientMessage::hasSelectTargetsReq,
    )

internal fun GREToClientMessage.isPromptMessage(): Boolean = PROMPT_TYPES.any { (_, matches) -> matches() }

internal fun GREToClientMessage.promptName(): String = PROMPT_TYPES.firstOrNull { (_, matches) -> matches() }?.first ?: "UnknownPrompt"

internal fun GREToClientMessage.matchesPrompt(
    prompt: String,
    promptId: Int? = null,
): Boolean {
    val matches = PROMPT_TYPES.firstOrNull { (name, _) -> name == prompt }?.second ?: error("unknown prompt condition: $prompt")
    return matches() && (promptId == null || this.prompt.promptId == promptId)
}

internal fun String.toForgePhaseName(): String =
    when (substringBefore("_").replace("-", "").replace(" ", "").uppercase()) {
        "MAIN1", "PRECOMBATMAIN" -> "MAIN1"
        "MAIN2", "POSTCOMBATMAIN" -> "MAIN2"
        "DECLAREATTACKERS", "COMBATDECLAREATTACKERS" -> "COMBAT_DECLARE_ATTACKERS"
        "DECLAREBLOCKERS", "COMBATDECLAREBLOCKERS" -> "COMBAT_DECLARE_BLOCKERS"
        "COMBATDAMAGE" -> "COMBAT_DAMAGE"
        else -> uppercase()
    }
