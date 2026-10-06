package leyline.game.bundle

import leyline.bridge.handoff.ManaSourcePaymentKind
import leyline.bridge.handoff.ManaSourcePaymentWindowValue
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import leyline.game.state.ProjectionState
import leyline.game.state.ProjectionTransition
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.ActionsAvailableReq
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.ManaCostSpecType
import wotc.mtgo.gre.external.messaging.Messages.ManaInfo
import wotc.mtgo.gre.external.messaging.Messages.ManaPaymentOption
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement
import wotc.mtgo.gre.external.messaging.Messages.ManaSpecType
import wotc.mtgo.gre.external.messaging.Messages.OptionContext
import wotc.mtgo.gre.external.messaging.Messages.ParameterType
import wotc.mtgo.gre.external.messaging.Messages.PayCostsReq
import wotc.mtgo.gre.external.messaging.Messages.Prompt
import wotc.mtgo.gre.external.messaging.Messages.PromptParameter
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq
import wotc.mtgo.gre.external.messaging.Messages.SelectionContext
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.SelectionValidationType

/** Value-only GRE preparation for coordinator-owned iterative mana-source payments. */
internal class ManaSourcePaymentMaterializer(
    private val seatId: Int,
) {
    data class Prepared(
        val bundle: BundleBuilder.BundleResult,
        val transition: ProjectionTransition,
        val closesPlaybackFrame: Boolean,
    )

    fun prepare(
        gameState: GameStateMessage,
        gameStateId: Int,
        counter: LogicalSequencePlanner,
        projection: ProjectionState,
        transition: ProjectionTransition,
        window: ManaSourcePaymentWindowValue,
    ): Prepared {
        val request = payCostsRequest(window, projection)
        val messages =
            listOf(
                makeGRE(GREMessageType.GameStateMessage_695e, gameStateId, counter.nextMsgId()) {
                    it.gameStateMessage = gameState
                },
                makeGRE(GREMessageType.PayCostsReq_695e, gameStateId, counter.nextMsgId()) {
                    it.payCostsReq = request
                    it.prompt = paymentPrompt(window)
                    it.allowCancel = AllowCancel.Abort
                    it.allowUndo = true
                },
            )
        return Prepared(
            BundleBuilder.BundleResult(messages, actionGameStateId = gameStateId),
            transition,
            closesPlaybackFrame = true,
        )
    }

    private fun payCostsRequest(
        window: ManaSourcePaymentWindowValue,
        projection: ProjectionState,
    ): PayCostsReq {
        val sourceInstanceId = window.sourceForgeCardId?.let { projection.requireInstanceId(it) } ?: 0
        return PayCostsReq
            .newBuilder()
            .addAllManaCost(
                window.manaCost.map { (color, count) ->
                    ManaRequirement
                        .newBuilder()
                        .addColor(color)
                        .setCount(count)
                        .apply {
                            if (window.kind == ManaSourcePaymentKind.Waterbend) {
                                addSpecs(ManaCostSpecType.Waterbend)
                            }
                        }.setObjectId(sourceInstanceId)
                        .apply {
                            if (window.kind == ManaSourcePaymentKind.Waterbend && window.sourceAbilityGrpId != 0) {
                                abilityGrpId = window.sourceAbilityGrpId
                            }
                        }.build()
                },
            ).setPaymentActions(paymentActions(window, projection))
            .setPaymentSelection(
                SelectNReq
                    .newBuilder()
                    .setContext(SelectionContext.ManaPool)
                    .setOptionContext(OptionContext.Payment)
                    .setListType(SelectionListType.Dynamic)
                    .setIdType(IdType.ManaId)
                    .setValidationType(SelectionValidationType.NonRepeatable)
                    .setMinWeight(Int.MIN_VALUE)
                    .setMaxWeight(Int.MAX_VALUE),
            ).build()
    }

    private fun paymentActions(
        window: ManaSourcePaymentWindowValue,
        projection: ProjectionState,
    ): ActionsAvailableReq =
        ActionsAvailableReq
            .newBuilder()
            .apply {
                window.candidates.forEachIndexed { index, candidate ->
                    val instanceId = projection.requireInstanceId(candidate.forgeCardId)
                    val mana =
                        ManaInfo
                            .newBuilder()
                            .setManaId(MANA_SOURCE_MANA_ID_BASE + index)
                            .setColor(candidate.paymentColor)
                            .setSrcInstanceId(instanceId)
                            .addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.Predictive))
                            .apply {
                                if (candidate.fromCreature || window.kind == ManaSourcePaymentKind.Convoke) {
                                    addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.FromCreature))
                                }
                            }.addSpecs(ManaInfo.Spec.newBuilder().setType(ManaSpecType.ManaSubstitution))
                            .setAbilityGrpId(window.kind.paymentAbilityGrpId())
                            .setCount(1)
                            .build()
                    addActions(
                        Action
                            .newBuilder()
                            .setActionType(ActionType.MakePayment)
                            .setGrpId(candidate.grpId)
                            .setInstanceId(instanceId)
                            .setFacetId(instanceId)
                            .setAbilityGrpId(window.kind.paymentAbilityGrpId())
                            .addManaPaymentOptions(ManaPaymentOption.newBuilder().addMana(mana)),
                    )
                }
            }.build()

    private fun paymentPrompt(window: ManaSourcePaymentWindowValue): Prompt =
        Prompt
            .newBuilder()
            .setPromptId(PromptIds.PAY_COSTS)
            .apply {
                window.costString?.let { cost ->
                    addParameters(
                        PromptParameter
                            .newBuilder()
                            .setParameterName("Cost")
                            .setType(ParameterType.NonLocalizedString)
                            .setStringValue(cost),
                    )
                }
            }.build()

    private fun ManaSourcePaymentKind.paymentAbilityGrpId(): Int =
        when (this) {
            ManaSourcePaymentKind.Convoke -> KeywordAbilityIds.CONVOKE_PAYMENT
            ManaSourcePaymentKind.Improvise -> KeywordAbilityIds.IMPROVISE
            ManaSourcePaymentKind.Waterbend -> WATERBEND_PAYMENT_ABILITY_GRP_ID
        }

    private fun ProjectionState.requireInstanceId(cardId: leyline.bridge.types.ForgeCardId): Int =
        identities.forgeIdToInstanceId[cardId]?.value ?: error("Mana-source card ${cardId.value} has no projected instance id")

    private fun makeGRE(
        type: GREMessageType,
        gameStateId: Int,
        msgId: Int,
        configure: (GREToClientMessage.Builder) -> Unit,
    ): GREToClientMessage =
        GREToClientMessage
            .newBuilder()
            .setType(type)
            .setMsgId(msgId)
            .setGameStateId(gameStateId)
            .addSystemSeatIds(seatId)
            .also(configure)
            .build()

    private companion object {
        const val MANA_SOURCE_MANA_ID_BASE = 50_000
        const val WATERBEND_PAYMENT_ABILITY_GRP_ID = 384
    }
}
