package leyline.bridge.coord

import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.TriggerOrderOptionValue
import leyline.bridge.handoff.TriggerOrderWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.snapshot.SnapshotCapture

/** Captures the pending simultaneous triggers of one controller on the engine thread. */
internal class TriggerOrderWindowCapture(
    private val owner: MatchCutCoordinator,
    private val runtimeSeat: SeatId = owner.humanSeat,
) {
    data class Initial(
        val value: TriggerOrderWindowValue,
        val handlesByOption: Map<Int, SpellAbility>,
    )

    /**
     * Null falls through to Forge's own order: the ordering UI only covers triggers the
     * runtime seat controls, and copied spells reaching `orderSimultaneousSa` are a
     * different client decision.
     */
    @Suppress("ReturnCount")
    fun initial(
        request: PromptRequest,
        abilities: List<SpellAbility>,
    ): Initial? {
        if (request.route !is ResolvedPromptRoute.OrderTriggers) return null
        if (abilities.size < 2 || abilities.size != request.options.size) return null
        val options = abilities.mapIndexed { index, ability -> option(index, ability) ?: return null }
        if (options.map { it.forgeAbilityId }.distinct().size != options.size) return null
        return Initial(
            TriggerOrderWindowValue(options),
            abilities.mapIndexed { index, ability -> index to ability }.toMap(),
        )
    }

    @Suppress("ReturnCount")
    private fun option(
        index: Int,
        ability: SpellAbility,
    ): TriggerOrderOptionValue? {
        if (!ability.isTrigger || ability.isCopied || ability.id == 0) return null
        val host = ability.hostCard ?: return null
        val bridge = owner.bridge
        val ownerSeat = bridge.seatOf(host.owner) ?: return null
        val controllerSeat = bridge.seatOf(ability.activatingPlayer ?: host.controller) ?: return null
        if (controllerSeat != runtimeSeat) return null
        val sourceCardGrpId = SnapshotCapture.resolveStackSourceCardGrpId(host, bridge.cardRepository)
        if (sourceCardGrpId == 0) return null
        // Same precedence the stack snapshot applies once the trigger is on the stack, so the
        // pre-stack object and the real one carry the same ability row.
        val abilityGrpId =
            bridge.pendingTriggerCleanupAbilityGrpId(ability.trigger?.id ?: 0)
                ?: bridge.resolveAbilityIdentity(host, ability)?.abilityGrpId?.takeIf { it != 0 }
                ?: sourceCardGrpId
        return TriggerOrderOptionValue(
            originalOptionIndex = index,
            forgeAbilityId = ability.id,
            sourceForgeCardId = ForgeCardId(host.id),
            abilityGrpId = abilityGrpId,
            sourceCardGrpId = sourceCardGrpId,
            ownerSeatId = ownerSeat,
            controllerSeatId = controllerSeat,
        )
    }
}
