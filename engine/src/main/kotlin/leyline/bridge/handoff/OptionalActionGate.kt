package leyline.bridge.handoff

import forge.game.card.Card
import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.CardMechanicType

/**
 * Narrow access surface exposed by [PlayerController] to coordinators and helpers.
 *
 * Coordinators receive an `OwnerContext` rather than a full [PlayerController]
 * reference. A full reference would let any coordinator reach any override, any
 * private helper, any PCHuman-inherited method — and the boundary would erode
 * within two refactors. Keeping the surface narrow is a compile-time fence: if a
 * coordinator needs a field or callback not on this interface, that is a signal
 * to discuss adding it rather than to widen access.
 *
 * Grows one field at a time, driven by actual coordinator need. Do not
 * pre-populate with fields a coordinator might want someday.
 *
 * See the [PlayerController] class KDoc for the state-ownership rules that
 * decide what belongs here vs. on the class vs. on [InteractivePromptBridge].
 */
interface OwnerContext {
    /** Invoke the `onStateChanged` callback so the session layer can ship updated state. */
    fun notifyStateChanged()
}

/**
 * Publishes coordinator-owned optional interaction values for the override
 * sites that share it (`confirmAction`, `confirmTrigger`,
 * `confirmReplacementEffect`, `playSaFromPlayEffect`, `payCostToPreventEffect`).
 *
 * Threading: [await] runs on the Forge engine thread. It blocks that thread
 * until the Netty session thread completes the future via
 * [leyline.match.OptionalActionHandler.onOptionalActionResp].
 */
class OptionalActionGate(
    private val actionBridge: GameActionBridge?,
    private val interactionRuntime: BlockingInteractionRuntime,
) {
    /**
     * Post a pending optional-action prompt, block the engine thread until the
     * client responds or the action timeout elapses, and return the accept/decline
     * decision.
     *
     * @param hostCard the card the prompt is about (null when unknown)
     * @param forceSnapshotBeforePrompt when true, the coordinator emits a full
     *   GSM before the prompt — needed for mid-resolution prompts where the
     *   client has not yet seen the pre-prompt state transition
     * @param defaultOnTimeout the value to return if the future times out (true for
     *   sites where auto-accepting is the safe fallback, false where auto-declining is)
     * @param logContext human-readable tag for timeout log lines (e.g. the override name)
     * @param mechanicType client workflow hint (`OptionalActionMessage.optionalActionTypes`) —
     *   set only when the caller knows which specialized browser this confirmation should
     *   route to (e.g. Explore); null leaves the message untagged, same as before this
     *   parameter existed.
     */
    @Suppress("UnusedParameter", "LongParameterList")
    fun await(
        hostCard: Card?,
        forceSnapshotBeforePrompt: Boolean = false,
        defaultOnTimeout: Boolean,
        logContext: String,
        customPromptId: Int? = null,
        commanderReturn: CommanderReturnPromptContext? = null,
        freeCast: BlockingInteraction.FreeCast? = null,
        etbPayLifeReplacement: Boolean = false,
        mechanicType: CardMechanicType? = null,
        costText: String? = null,
    ): Boolean {
        if (hostCard == null) return true
        return interactionRuntime.awaitOptional(
            BlockingInteraction.Optional(
                sourceId = ForgeCardId(hostCard.id),
                forceSnapshotBeforePrompt = forceSnapshotBeforePrompt,
                customPromptId = customPromptId,
                commanderReturn = commanderReturn,
                freeCast = freeCast,
                etbPayLifeReplacement = etbPayLifeReplacement,
                mechanicType = mechanicType,
                costText = costText,
            ),
            sourceCard = hostCard,
            timeoutMs = actionBridge?.getTimeoutMs(),
            defaultOnTimeout = defaultOnTimeout,
        )
    }
}
