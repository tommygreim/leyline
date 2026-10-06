package leyline.bridge.handoff

import forge.game.card.Card
import leyline.bridge.types.ForgeCardId

/**
 * Publishes coordinator-owned numeric interactions for the three
 * `chooseNumber` override sites that share it (range, range+params, list-overload).
 *
 * Mirrors [OptionalActionGate] — a `Cost$ X` triggered/activated ability lands in
 * Forge's `PlayerController.chooseNumber`, the override publishes the value and blocks
 * until the response handler submits the answer.
 *
 * Threading: [await] runs on the Forge engine thread. It blocks until the Netty
 * session thread completes the future via the response handler. On timeout, returns
 * [defaultOnTimeout] (`0` is the conventional default — payment of 0 is always safe).
 */
class NumericInputGate(
    private val actionBridge: GameActionBridge?,
    private val interactionRuntime: BlockingInteractionRuntime,
) {
    @Suppress("UnusedParameter")
    fun await(
        sourceCard: Card?,
        min: Int,
        max: Int,
        defaultOnTimeout: Int,
        logContext: String,
        presentation: BlockingInteraction.NumericPresentation = BlockingInteraction.NumericPresentation.Generic,
    ): Int {
        if (sourceCard == null) return min
        return interactionRuntime.awaitNumeric(
            BlockingInteraction.Numeric(
                sourceId = ForgeCardId(sourceCard.id),
                min = min,
                max = max,
                defaultValue = defaultOnTimeout,
                presentation = presentation,
            ),
            timeoutMs = actionBridge?.getTimeoutMs(),
        )
    }
}
