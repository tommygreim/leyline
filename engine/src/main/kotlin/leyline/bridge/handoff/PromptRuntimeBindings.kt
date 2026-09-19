package leyline.bridge.handoff

/** Immutable match-scoped runtime registry installed into one human-seat prompt bridge. */
internal data class PromptRuntimeBindings(
    val targeting: TargetingInteractionRuntime? = null,
    val compatibilityCostSelection: CompatibilityCostSelectionRuntime? = null,
    val search: SearchInteractionRuntime? = null,
    val replacement: ReplacementInteractionRuntime? = null,
    val order: OrderInteractionRuntime? = null,
    val triggerOrder: TriggerOrderInteractionRuntime? = null,
    val distribution: DistributionInteractionRuntime? = null,
    val grouping: GroupingInteractionRuntime? = null,
    val cardSelect: CardSelectInteractionRuntime? = null,
    val staticChoice: StaticChoiceInteractionRuntime? = null,
    val revealChoice: RevealChoiceInteractionRuntime? = null,
    val modalChoice: ModalChoiceInteractionRuntime? = null,
    val manaSourcePayment: ManaSourcePaymentRuntime? = null,
    val oneShotPayCosts: OneShotPayCostsRuntime? = null,
)
