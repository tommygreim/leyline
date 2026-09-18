package leyline.game.bundle

import leyline.bridge.handoff.ReplacementWindowValue
import leyline.game.mapping.PromptIds
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.ReplacementEffect
import wotc.mtgo.gre.external.messaging.Messages.SelectReplacementReq
import wotc.mtgo.gre.external.messaging.Messages.SelectReplacementsType

/** Value-only GRE preparation for one settled competing-replacement window. */
internal class ReplacementWindowMaterializer {
    fun prepare(
        context: SettledPromptMaterializationContext,
        window: ReplacementWindowValue,
    ): SettledPromptMaterialization {
        val rows = window.options.map { row(it, context.requiredInstanceId(it.hostForgeCardId, "Replacement host")) }
        require(rows.map { it.replacementEffectId }.distinct().size == rows.size) {
            "Replacement rows have colliding request-local effect ids"
        }
        val request =
            SelectReplacementReq
                .newBuilder()
                .addAllReplacements(rows)
                .apply { if (window.allDredge) setReplacementsType(SelectReplacementsType.AllDredge) }
                .build()
        val state =
            context.gameState
                .toBuilder()
                .setPendingMessageCount(1)
                .build()
        val messages =
            listOf(
                context.message(GREMessageType.GameStateMessage_695e) {
                    it.gameStateMessage = state
                },
                context.message(GREMessageType.SelectReplacementReq_695e) {
                    it.selectReplacementReq = request
                    it.prompt = Prompt.newBuilder().setPromptId(PromptIds.SELECT_REPLACEMENT).build()
                    it.allowCancel = AllowCancel.No_a526
                    it.allowUndo = true
                },
            )
        return context.prepared(messages, awaitedRequest = messages.last())
    }

    private fun row(
        option: leyline.bridge.handoff.ReplacementOptionValue,
        instanceId: Int,
    ): ReplacementEffect =
        ReplacementEffect
            .newBuilder()
            .setObjectInstance(instanceId)
            .setUniqueAbilityId(option.uniqueAbilityId)
            .setAbilityGrpId(option.abilityGrpId)
            .setAffectedObject(instanceId)
            .setReplacementEffectId(REPLACEMENT_EFFECT_ID_BASE + option.originalOptionIndex)
            .build()

    private companion object {
        const val REPLACEMENT_EFFECT_ID_BASE = 9000
    }
}
