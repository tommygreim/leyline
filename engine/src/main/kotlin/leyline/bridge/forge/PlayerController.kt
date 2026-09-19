package leyline.bridge.forge

import forge.LobbyPlayer
import forge.ai.LobbyPlayerAi
import forge.card.ColorSet
import forge.card.GamePieceType
import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import forge.game.Game
import forge.game.GameActionUtil
import forge.game.GameEntity
import forge.game.GameObject
import forge.game.ability.AbilityKey
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.card.CardCollectionView
import forge.game.card.CardLists
import forge.game.card.CardView
import forge.game.combat.Combat
import forge.game.cost.Cost
import forge.game.cost.CostBlight
import forge.game.cost.CostDecisionMakerBase
import forge.game.cost.CostDiscard
import forge.game.cost.CostEnlist
import forge.game.cost.CostExile
import forge.game.cost.CostForage
import forge.game.cost.CostPart
import forge.game.cost.CostPartMana
import forge.game.cost.CostPartWithList
import forge.game.cost.CostPayLife
import forge.game.cost.CostReturn
import forge.game.cost.CostSacrifice
import forge.game.cost.CostTapType
import forge.game.cost.CostUntapType
import forge.game.cost.CostWaterbend
import forge.game.keyword.Keyword
import forge.game.keyword.KeywordInterface
import forge.game.mana.ManaConversionMatrix
import forge.game.mana.ManaCostBeingPaid
import forge.game.player.DelayedReveal
import forge.game.player.PlaySpellAbility
import forge.game.player.Player
import forge.game.player.PlayerActionConfirmMode
import forge.game.player.PlayerView
import forge.game.replacement.ReplacementEffect
import forge.game.spellability.AbilitySub
import forge.game.spellability.OptionalCostValue
import forge.game.spellability.SpellAbility
import forge.game.spellability.SpellAbilityView
import forge.game.spellability.StackItemView
import forge.game.spellability.TargetChoices
import forge.game.staticability.StaticAbility
import forge.game.trigger.WrappedAbility
import forge.game.zone.ZoneType
import forge.player.PlayerControllerHuman
import forge.player.TargetSelectionResult
import forge.util.collect.FCollectionView
import leyline.bridge.NonInteractiveScope
import leyline.bridge.coord.CostPaymentCoordinator
import leyline.bridge.coord.PriorityLoopCoordinator
import leyline.bridge.coord.PriorityPolicyRuntime
import leyline.bridge.coord.SpellExecutor
import leyline.bridge.coord.StaticChoiceCoordinator
import leyline.bridge.coord.TargetingCoordinator
import leyline.bridge.handoff.BlockingInteraction
import leyline.bridge.handoff.BlockingInteractionRuntime
import leyline.bridge.handoff.CommanderReturnPromptContext
import leyline.bridge.handoff.CommanderZone
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.MulliganBridge
import leyline.bridge.handoff.NumericInputGate
import leyline.bridge.handoff.OptionalActionGate
import leyline.bridge.handoff.OwnerContext
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.handoff.RuntimeHorizonMode
import leyline.bridge.handoff.TargetingCandidateValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.Seating
import leyline.bridge.types.StaticChoiceIds
import leyline.bridge.types.toCandidateRefs
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PromptIds
import org.apache.commons.lang3.tuple.ImmutablePair
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.CardMechanicType
import java.util.function.Predicate

/**
 * The single integration point between Forge's rules engine and our session layer.
 * Extends [PlayerControllerHuman] so all 157 interactive methods route through
 * [InteractivePromptBridge] via [ClientGuiGame]; the ~42 methods PCHuman implements
 * with desktop-only classes (InputConfirm, InputSelectCardsFromList, FModel,
 * GuiBase) are overridden here.
 *
 * ## Single-inheritance constraint
 *
 * Forge calls `controller.chooseSpellAbilityToPlay()`, `chooseSingleEntityForEffect(...)`,
 * etc. via a [forge.game.player.PlayerController] field on [Player].
 * That field holds a concrete subclass; virtual dispatch is the only mechanism.
 * There is no composition escape hatch, no delegation annotation reaching PCHuman's
 * protected state, no handler-map registration. **The class shape is non-negotiable**:
 * every override must stay on this class. The implementation behind each override
 * is not — and should not — live inside the class.
 *
 * Composition replacement is a non-goal. PCHuman does useful work for ~130 methods
 * we do not override; breaking the chain would require re-implementing all of them.
 *
 * ## Coordinators and helpers
 *
 * An override body longer than ~5 lines moves into a **coordinator** (slice of the
 * override surface — multiple related overrides grouped by our concern) or a
 * **helper** (single shared lifecycle or pure-function cluster). Both are plain
 * classes; the override becomes a thin delegation.
 *
 * Current coordinators:
 * - [PriorityLoopCoordinator] — `chooseSpellAbilityToPlay`, combat declarations,
 *   combat-damage assignment, decision logging. Uses [GameActionBridge].
 * - [TargetingCoordinator] — single-entity and multi-entity choice, targeting,
 *   reveals, discards, sacrifices, zone ordering. Writes bridge flag-contract fields.
 * - [CostPaymentCoordinator] — convoke/improvise, keyword-cost binary, optional
 *   costs, shock-land pay-life, AI mana payment.
 * - [StaticChoiceCoordinator] — static enum selections for color, subtype,
 *   parity-like binary choices, and parity-flavoured confirmations.
 *
 * Current helpers:
 * - [SpellExecutor] — the `executeCastSpell` / `executeActivateAbility` /
 *   `executeActivateMana` / `executePlayLand` cluster. Called only from
 *   `chooseSpellAbilityToPlay` inside [PriorityLoopCoordinator].
 * - [OptionalActionGate] — publishes typed optional interactions through the
 *   match coordinator for confirm callbacks and optional costs.
 *
 * ## State ownership
 *
 * Interaction windows and live response handles belong to the match coordinator.
 * Priority policy and settings state live in [PriorityPolicyRuntime]. This
 * controller only supplies Forge's callback surface and delegates decisions to
 * the runtime owner.
 *
 * Coordinators receive the callback surface through [OwnerContext]. The priority
 * coordinator receives [PriorityPolicyRuntime] explicitly because policy state is
 * not part of that handoff. Prompt side-effects (reveal lifecycle, legend-rule
 * victims, searched-to-hand cards, optional-cost stash) flow through the typed
 * [leyline.bridge.handoff.PromptJournal] on [InteractivePromptBridge]; the priority-loop "prompt just
 * resolved" flag lives on [leyline.bridge.types.PrioritySignal].
 *
 * ## Anti-patterns (enforced)
 *
 * - **No coordinator-to-coordinator calls.** Inter-coordinator communication goes
 *   through [OwnerContext] or Forge engine state.
 * - **No reflection or dispatch tables.** Route override → coordinator via direct
 *   Kotlin calls.
 * - **No `suspend` conversion.** Engine callbacks keep their synchronous Forge contract.
 * - **No per-mechanic split** (`MadnessOverrides`, `FlashbackOverrides` etc.) —
 *   fractures the surface along the wrong axis.
 * - **No generic `ChoiceHandler<T>` abstraction.** Forge's signatures diverge too
 *   much; coordinators should look like "a class full of related methods."
 * - **No mechanical method-count splitting.** A coordinator's one-sentence purpose
 *   test is the gate.
 * - **No over-extraction.** A 2-method coordinator with no shared helpers is
 *   ceremony, not structure — the confirmation cluster and mulligan overrides
 *   were deliberately left on this class for that reason.
 *
 * ## Adding a new override
 *
 * When Forge adds a callback, or when a mechanic forces us to override a PCHuman
 * method we previously inherited:
 *
 * 1. **Trivial body (≤ 5 lines, direct `bridge.requestChoice` or `super` call)?**
 *    Keep it here. Update [PlayerControllerStructureTest]'s pinned override
 *    count in the same commit.
 * 2. **Fits an existing coordinator's concern?** Add a method there, delegate.
 * 3. **Shares a lifecycle pattern with other overrides** (e.g. a blocking interaction)?
 *    Route it through the match-scoped interaction runtime.
 * 4. **New concern that does not fit any existing coordinator?** Propose a new
 *    coordinator; justify it against the anti-patterns above.
 *
 * The structure test is the guardrail: it fails when the override count drifts
 * from its pinned value, forcing this class and the test to stay in sync.
 *
 * ## Threading
 *
 * Every override runs on the Forge engine thread. Input callbacks publish a complete
 * committed interaction before blocking; the session thread submits immutable answers.
 * Match-cut preparation and publication run under the coordinator feed lock.
 * Consequences for every coordinator:
 *
 * - A missing or slow override blocks the entire game loop.
 * - Client-visible and state-only priority stops publish their coordinator-owned
 *   batch before the engine blocks; safe direct skips publish nothing.
 * - Engine callbacks must not hold Forge mutation locks while waiting for input.
 * - Session handlers may only drain committed batches and submit answers.
 *
 * See `docs/bridge-threading.md` for ownership, lock-order, and residual-path contracts.
 */
@Suppress("LargeClass") // Forge dispatches via single inheritance; the override surface lives on this class.
class PlayerController(
    game: Game,
    player: Player,
    lobbyPlayer: LobbyPlayer,
    private val bridge: InteractivePromptBridge,
    private val seating: Seating,
    private val actionBridge: GameActionBridge? = null,
    private val mulliganBridge: MulliganBridge? = null,
    priorityPolicy: PriorityPolicyRuntime = PriorityPolicyRuntime(),
    private val runtimeHorizonMode: RuntimeHorizonMode = RuntimeHorizonMode.Direct,
    private val onStateChanged: (() -> Unit)? = null,
    val smartPhaseSkip: Boolean = true,
    interactionRuntime: BlockingInteractionRuntime,
) : PlayerControllerHuman(game, player, lobbyPlayer),
    OwnerContext {
    private val optionalActionGate = OptionalActionGate(actionBridge, interactionRuntime)
    private val numericInputGate = NumericInputGate(actionBridge, interactionRuntime)
    private val spellExecutor = SpellExecutor(game, player, bridge)
    private val targetingCoordinator =
        TargetingCoordinator(
            bridge,
            seating,
            viewerSeatId = seating.seatOf(player.id, player.lobbyPlayer is LobbyPlayerAi),
            currentSourceEntityId = ::currentSourceEntityId,
            isCastingSpell = { activeSourceIsSpell },
            currentStackAbilityId = {
                game.stack
                    .firstOrNull()
                    ?.takeIf { it.isAbility }
                    ?.spellAbility
                    ?.id
            },
        )
    private val costPaymentCoordinator = CostPaymentCoordinator(bridge, player, optionalActionGate)
    private val staticChoiceCoordinator = StaticChoiceCoordinator(bridge)
    private var activeSpellSourceId: Int? = null
    private var activeSourceIsSpell: Boolean = false
    private var activeStackTargetingAbility: SpellAbility? = null
    private var activeDividedAllocationAbility: SpellAbility? = null
    private var simultaneousAbilities: List<SpellAbility> = emptyList()
    private val priorityLoopCoordinator: PriorityLoopCoordinator? =
        actionBridge?.let { ab ->
            PriorityLoopCoordinator(
                owner = this,
                game = game,
                player = player,
                actionBridge = ab,
                priorityPolicy = priorityPolicy,
                runtimeHorizonMode = runtimeHorizonMode,
                smartPhaseSkip = smartPhaseSkip,
                spellExecutor = spellExecutor,
                interactionRuntime = interactionRuntime,
            )
        }

    init {
        setGui(
            ClientGuiGame(
                bridge,
                currentStackSourceId = {
                    activeStackTargetingAbility?.let(::targetingSourceCardId)
                },
                stackTargetingActive = { activeStackTargetingAbility != null },
                currentStackTargetingAbility = { activeStackTargetingAbility },
                currentStackTargetIndex = {
                    activeStackTargetingAbility?.let(targetingCoordinator::targetGroupIndex) ?: 1
                },
                currentStackTargetPromptId = {
                    activeStackTargetingAbility?.let(targetingCoordinator::effectiveTargetPromptId)
                },
                playerSeatOf = { target ->
                    seating.seatOf(target.id, target.lobbyPlayer is LobbyPlayerAi).value
                },
                playerViewSeatOf = { target ->
                    game.players
                        .firstOrNull { it.id == target.id }
                        ?.let {
                            seating.seatOf(it.id, it.lobbyPlayer is LobbyPlayerAi).value
                        }
                },
                stackTargetCandidate = ::stackTargetCandidate,
                currentDividedAllocationAbility = { activeDividedAllocationAbility },
                beforeDividedAllocation = targetingCoordinator::recordCompletedTargetSpec,
                currentSimultaneousAbilities = { simultaneousAbilities },
            ),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(PlayerController::class.java)

        /** Forge's title for Harmonize's power question; see [chooseHarmonizeTapPower]. */
        private const val HARMONIZE_TAP_TITLE = "Choose power of creature to tap"
    }

    private var pendingManaColorChoice: Byte? = null

    /** Creature chosen for the current Harmonize cast, consumed by its tap-cost payment. */
    private var harmonizeTap: Card? = null

    fun <T> withManaColorChoice(
        colorMask: Byte?,
        block: () -> T,
    ): T {
        if (colorMask == null) return block()
        val previous = pendingManaColorChoice
        pendingManaColorChoice = colorMask
        return try {
            block()
        } finally {
            pendingManaColorChoice = previous
        }
    }

    override fun isAI(): Boolean = false

    // ═══════════════════════════════════════════════════════════════════
    // Overrides for PCHuman methods that use desktop-only classes.
    // Methods using only getGui() calls are inherited and work via ClientGuiGame.
    // ═══════════════════════════════════════════════════════════════════

    // -- Scry / Surveil ------------------------------------------------
    // PCHuman uses FModel.getPreferences + GuiBase + InputConfirm

    override fun arrangeForScry(topN: CardCollection): ImmutablePair<CardCollection, CardCollection> =
        targetingCoordinator.arrangeForScry(topN)

    override fun arrangeForSurveil(topN: CardCollection): ImmutablePair<CardCollection, CardCollection> =
        targetingCoordinator.arrangeForSurveil(topN)

    override fun reveal(
        cards: CardCollectionView,
        zone: ZoneType,
        owner: Player,
        messagePrefix: String?,
        addMsgSuffix: Boolean,
    ) {
        targetingCoordinator.captureReveal(cards, zone, owner)
    }

    override fun reveal(
        cards: List<CardView>,
        zone: ZoneType,
        owner: PlayerView,
        messagePrefix: String?,
        addMsgSuffix: Boolean,
    ) {
        targetingCoordinator.captureReveal(cards, zone, owner, game.players)
    }

    // -- Sacrifice / Destroy ----------------------------------------------
    // PCHuman uses InputSelectCardsFromList

    override fun choosePermanentsToSacrifice(
        sa: SpellAbility?,
        min: Int,
        max: Int,
        validTargets: CardCollectionView,
        message: String?,
    ): CardCollectionView {
        // Optional sacrifice reductions answer from the active policy.
        // Mandatory sacrifices fall through so the refusal surfaces at the bridge.
        if (min == 0) {
            NonInteractiveScope.active?.let { policy ->
                return NonInteractiveAnswers.permanentsToSacrifice(policy, sa, validTargets)
            }
        }
        return targetingCoordinator.choosePermanentsToSacrifice(sa, min, max, validTargets, message)
    }

    override fun choosePermanentsToDestroy(
        sa: SpellAbility?,
        min: Int,
        max: Int,
        validTargets: CardCollectionView,
        message: String?,
    ): CardCollectionView = targetingCoordinator.choosePermanentsToDestroy(min, max, validTargets, message)

    // -- Discard -----------------------------------------------------------
    // PCHuman uses InputSelectCardsFromList

    override fun chooseCardsToDiscardFrom(
        p: Player,
        sa: SpellAbility?,
        validCards: CardCollection,
        min: Int,
        max: Int,
    ): CardCollection = targetingCoordinator.chooseCardsToDiscardFrom(sa, validCards, min, max)

    override fun chooseCardsToDiscardFrom(
        p: Player,
        sa: SpellAbility?,
        validCards: CardCollection,
        min: Int,
        max: Int,
        visibleToChooser: CardCollectionView,
    ): CardCollectionView = targetingCoordinator.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser)

    override fun chooseCardsToDiscardToMaximumHandSize(nDiscard: Int): CardCollection =
        targetingCoordinator.chooseCardsToDiscardToMaximumHandSize(nDiscard, player.getZone(ZoneType.Hand).cards)

    override fun chooseCardsToRevealFromHand(
        min: Int,
        max: Int,
        valid: CardCollectionView,
    ): CardCollectionView = targetingCoordinator.chooseCardsToRevealFromHand(min, max, valid)

    // -- Generic choose cards for effect -----------------------------------
    // PCHuman uses useSelectCardsInput → InputSelectCardsFromList

    override fun chooseCardsForEffect(
        sourceList: CardCollectionView,
        sa: SpellAbility?,
        title: String?,
        min: Int,
        max: Int,
        isOptional: Boolean,
        params: MutableMap<String, Any>?,
    ): CardCollectionView = targetingCoordinator.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional)

    // -- Choose single entity ----------------------------------------------
    // PCHuman uses useSelectCardsInput → InputSelectEntitiesFromList

    override fun <T : GameEntity> chooseSingleEntityForEffect(
        optionList: FCollectionView<T>,
        delayedReveal: DelayedReveal?,
        sa: SpellAbility?,
        title: String?,
        isOptional: Boolean,
        targetedPlayer: Player?,
        params: MutableMap<String, Any>?,
    ): T? {
        if (delayedReveal != null) reveal(delayedReveal)
        return targetingCoordinator.chooseSingleEntity(
            optionList,
            sa,
            title,
            isOptional,
            hasDelayedReveal = delayedReveal != null,
        )
    }

    // chooseSingleCardForZoneChange — inherited from PCHuman, which delegates
    // to our overridden chooseSingleEntityForEffect. No override needed.

    // -- Choose multiple entities ------------------------------------------
    // PCHuman uses useSelectCardsInput → InputSelectEntitiesFromList

    override fun <T : GameEntity> chooseEntitiesForEffect(
        optionList: FCollectionView<T>,
        min: Int,
        max: Int,
        delayedReveal: DelayedReveal?,
        sa: SpellAbility?,
        title: String?,
        targetedPlayer: Player?,
        params: MutableMap<String, Any>?,
    ): List<T> {
        if (delayedReveal != null) reveal(delayedReveal)
        return targetingCoordinator.chooseEntities(optionList, min, max, title, sa)
    }

    // -- Targeting ---------------------------------------------------------
    // Seam 2: chooseTargetsFor is inherited from PCHuman, which uses
    // TargetSelection → selectTargetsInteractively() (overridden below
    // in the ADR-010 Seam overrides section). This gives us MustTarget
    // filtering, divided-as-you-choose, multi-part targeting recursion,
    // random targets, and auto-target for single-candidate triggers.

    // -- Confirm -----------------------------------------------------------
    // PCHuman uses InputConfirm (desktop-only)

    override fun confirmAction(
        sa: SpellAbility?,
        mode: PlayerActionConfirmMode?,
        message: String?,
        options: MutableList<String>?,
        cardToShow: Card?,
        params: MutableMap<String, Any>?,
    ): Boolean {
        if (isParadigmCopyCast(sa) || isParadigmCopyCard(cardToShow)) return true

        val hostCard = cardToShow ?: sa?.hostCard
        if (mode == PlayerActionConfirmMode.ChangeZoneToAltDestination && hostCard?.isRealCommander == true) {
            return awaitCommanderReturn(hostCard, sa, "confirmAction:Commander")
        }

        // Endure: binary mode pick at trigger resolution. Yes → +1/+1 counters
        // (engine adds counters when confirmAction returns true); No → Spirit
        // token (engine creates the token in the else branch). Rides the same
        // OptionalActionMessage gate as confirmTrigger, with a counters-flavoured
        // promptId so the client renders a Yes/No prompt over the source creature.
        if (sa?.api == ApiType.Endure) {
            return optionalActionGate.await(
                hostCard = hostCard,
                defaultOnTimeout = true,
                logContext = "confirmAction:Endure",
                customPromptId = PromptIds.ENDURE_PUT_COUNTERS,
            )
        }

        val displayMessage = message ?: "Confirm action?"
        val displayOptions =
            if (options.isNullOrEmpty()) {
                listOf("Yes", "No")
            } else {
                options.toList()
            }
        // A two-option "even/odd" call (Devil's Play-style) is the one shape
        // staticChoiceCoordinator.confirmAction already routes through a real
        // interactive prompt (ResolvedPromptRoute.StaticChoice); keep it as is.
        if (isParityChoice(displayOptions)) {
            return staticChoiceCoordinator.confirmAction(displayMessage, displayOptions, (cardToShow ?: sa?.hostCard)?.id)
        }
        // Every other shape here used to fall through staticChoiceCoordinator.confirmAction
        // to a bare Generic-semantic PromptRequest, which resolves to
        // ResolvedPromptRoute.AutoResolve — a synchronous default answer with no
        // prompt ever reaching the client (PromptRequest.policyDefault). That is
        // confirmAction's fallback for every "you may" confirmation modeled as a
        // spell/ability EFFECT rather than a triggered ability — ScryEffect,
        // SurveilEffect, DrawEffect, FightEffect, DestroyAllEffect, and 40+ more
        // Forge effect classes, across 1,100+ cards scripted with `Optional$ True`
        // — always silently answering "Yes", never asking. confirmTrigger already
        // routes the equivalent triggered "you may" through OptionalActionGate;
        // do the same here.
        //
        // Explore ("reveal top card; if nonland, put a +1/+1 counter or you may
        // put it in the graveyard") tags CardMechanicType.Explore so the client
        // picks its dedicated ExploreWorkflow instead of the generic fallback
        // (confirmed by reading OptionalActionTranslation.Translate in the
        // decompiled client — it dispatches on this exact tag; see ISSUES.md I42).
        return optionalActionGate.await(
            hostCard = hostCard,
            defaultOnTimeout = true,
            logContext = "confirmAction",
            customPromptId = PromptIds.OPTIONAL_ACTION,
            mechanicType = if (sa?.api == ApiType.Explore) CardMechanicType.Explore else null,
        )
    }

    private fun isParityChoice(options: List<String>): Boolean {
        if (options.size != 2) return false
        val ids = options.map { StaticChoiceIds.parityIdForName(it) }
        return ids.all { it != null } && ids.toSet().size == 2
    }

    override fun confirmTrigger(wrapper: WrappedAbility): Boolean {
        if (wrapper.isMandatory) return true
        if (isParadigmDelayedTrigger(wrapper)) return true
        // Route through the coordinator-owned OptionalActionMessage interaction.
        val accepted =
            optionalActionGate.await(
                hostCard = wrapper.hostCard,
                defaultOnTimeout = true,
                logContext = "confirmTrigger",
                customPromptId = optionalTriggerPromptId(wrapper),
            )
        return accepted
    }

    private fun optionalTriggerPromptId(wrapper: WrappedAbility): Int {
        val scriptedCost =
            wrapper
                .takeIf { it.hasParam("Cost") }
                ?.getParam("Cost")
                ?.trim()
                .orEmpty()
        return when {
            scriptedCost.startsWith("Discard<") -> PromptIds.DISCARD_OPTIONAL
            scriptedCost == "X" -> PromptIds.OPTIONAL_PAY_X
            else -> PromptIds.OPTIONAL_ACTION
        }
    }

    /**
     * "Do you want to cast this spell that was given to you?" — fires from
     * Forge's PlayEffect for Madness, Discover, Cascade-into-cast, and similar
     * optional-cast paths. The default inherited behavior (PlayerControllerHuman)
     * routes through PlaySpellAbility which bypasses the client entirely;
     * we need to surface the choice through the client. Cascade and Discover use
     * a free-cast action window; other play effects retain the optional action UI.
     * On Accept, delegate to
     * `super.playSaFromPlayEffect(tgtSA)` which drives the real cast flow via
     * PlaySpellAbility (targeting, mana payment, stack placement — our alt-cost
     * rail emits CastingTimeOption + UAT alternativeGrpId along the way). On
     * Decline, return false so Forge's PlayEffect owns its fallback destination.
     *
     */
    override fun playSaFromPlayEffect(tgtSA: SpellAbility): Boolean {
        if (isParadigmCopyCast(tgtSA)) return super.playSaFromPlayEffect(tgtSA)

        val hostCard = tgtSA.hostCard
        val castingPermission = castingPermission(hostCard)
        castingPermission?.let(bridge.journal::record)
        val freeCast =
            castingPermission?.let {
                BlockingInteraction.FreeCast(
                    cardGrpId = hostCard?.let(bridge::resolveCardGrpId) ?: 0,
                    abilityGrpId = it.castAbilityGrpId,
                    sourceAbilityForgeId = it.sourceAbilityForgeId,
                    alternativeSourceForgeCardId = it.sourceForgeCardId,
                )
            }
        log.info(
            "playSaFromPlayEffect: prompting for optional cast of {} (alt-cost={})",
            hostCard?.name,
            tgtSA.getAlternativeCost(),
        )
        // Decline on timeout — safer than surprise-casting. On accept, super drives
        // the real cast flow (targeting, mana payment, stack placement).
        val accepted =
            optionalActionGate.await(
                hostCard = hostCard,
                forceSnapshotBeforePrompt = true,
                defaultOnTimeout = false,
                logContext = "playSaFromPlayEffect",
                freeCast = freeCast,
            )
        if (!accepted) {
            castingPermission?.let(bridge.journal::clearCastingPermission)
            return false
        }
        return try {
            super.playSaFromPlayEffect(tgtSA).also { played ->
                if (!played) castingPermission?.let(bridge.journal::clearCastingPermission)
            }
        } catch (error: Throwable) {
            castingPermission?.let(bridge.journal::clearCastingPermission)
            throw error
        }
    }

    private fun castingPermission(card: Card?): PromptSideEffect.CastingPermission? {
        val stackAbility = game.stack.firstOrNull()?.spellAbility ?: return null
        val rootAbility = (stackAbility as? WrappedAbility)?.wrappedAbility ?: stackAbility
        val discoverAbility = generateSequence(rootAbility) { it.subAbility }.firstOrNull { it.api == ApiType.Discover }
        val castAbilityGrpId =
            when {
                rootAbility.hostCard?.hasKeyword("Cascade") == true -> KeywordAbilityIds.CASCADE
                discoverAbility != null -> bridge.resolveAbilityIdentity(discoverAbility)?.abilityGrpId ?: 0
                else -> 0
            }
        return card
            ?.takeIf { castAbilityGrpId != 0 }
            ?.let {
                PromptSideEffect.CastingPermission(
                    ForgeCardId(it.id),
                    castAbilityGrpId,
                    stackAbility.id,
                    ForgeCardId(stackAbility.hostCard.id),
                )
            }
    }

    private fun isParadigmDelayedTrigger(wrapper: WrappedAbility): Boolean =
        wrapper.trigger?.getParam("Execute") == "ParadigmCopy" &&
            wrapper.hostCard?.effectSource?.hasKeyword("Paradigm") == true

    private fun isParadigmCopyCast(sa: SpellAbility?): Boolean =
        sa != null &&
            sa.hasParam("WithoutManaCost") &&
            (isParadigmCopyCard(sa.hostCard) || sa.hostCard?.effectSource?.hasKeyword("Paradigm") == true)

    private fun isParadigmCopyCard(card: Card?): Boolean = card?.isToken == true && card.copiedPermanent?.hasKeyword("Paradigm") == true

    override fun confirmPayment(
        costPart: CostPart?,
        question: String,
        sa: SpellAbility,
    ): Boolean {
        val activeCost =
            sa.hostCard
                ?.game
                ?.costPaymentStack
                ?.peek()
        if (
            costPart is CostSacrifice &&
            costPart.payCostFromSource() &&
            activeCost?.cost === costPart &&
            activeCost.payment.ability === sa
        ) {
            return true
        }

        // Forge asks "pay N life?" for any non-mandatory life cost, including one
        // the caster just picked as an additional-cost branch (Bitter Triumph) or
        // committed to by activating a fetch land. Arena never re-asks; only a life
        // payment chosen during resolution is a real decision.
        if (costPart is CostPayLife &&
            sa.hostCard
                ?.game
                ?.stack
                ?.isResolving == false
        ) {
            return true
        }

        // The bare PromptRequest below (no semantic/route) resolved to
        // ResolvedPromptRoute.AutoResolve — a synchronous default with no prompt
        // ever reaching the client — so every optional/alternative cost part
        // (PlaySpellAbility: "Do you want to discard your hand?", "...pay {N}?",
        // "...exile N cards from your library?", "...spend N energy counters?", and
        // more) was silently paid every time, before the caster ever saw a prompt.
        return optionalActionGate.await(
            hostCard = sa.hostCard,
            defaultOnTimeout = true,
            logContext = "confirmPayment",
            customPromptId = PromptIds.OPTIONAL_ACTION,
        )
    }

    override fun confirmReplacementEffect(
        replacementEffect: ReplacementEffect,
        sa: SpellAbility?,
        affected: GameEntity?,
        prompt: String?,
    ): Boolean {
        if (replacementEffect.hasParam("CommanderMoveReplacement")) {
            val hostCard = (affected as? Card) ?: replacementEffect.hostCard
            return awaitCommanderReturn(hostCard, sa, "confirmReplacementEffect:Commander")
        }

        // The bare PromptRequest below (no semantic/route) used to go straight to
        // bridge.requestChoice, which resolves an unclassified Generic semantic to
        // ResolvedPromptRoute.AutoResolve — a synchronous default with no prompt
        // ever reaching the client (see PromptRequest.policyDefault). That silently
        // declined every optional replacement, e.g. Superior Spider-Man's "you may
        // have it enter as a copy of a creature card in a graveyard": reported live,
        // no prompt shown, no copy made. OptionalActionGate is this override's
        // documented real interactive route (see its KDoc's consumer list).
        val message = prompt ?: replacementEffect.toString()
        return optionalActionGate.await(
            hostCard = replacementEffect.hostCard ?: sa?.hostCard,
            defaultOnTimeout = !isEnterAsCopyReplacement(message),
            logContext = "confirmReplacementEffect",
            customPromptId = if (isDredgeReplacement(replacementEffect)) PromptIds.DREDGE_THIS_CARD else PromptIds.OPTIONAL_ACTION,
        )
    }

    private fun isDredgeReplacement(effect: ReplacementEffect): Boolean =
        effect.hostCard?.keywords?.any { it.keyword == Keyword.DREDGE && it.replacements.any { replacement -> replacement === effect } } ==
            true

    override fun chooseSingleReplacementEffect(possibleReplacers: List<ReplacementEffect>): ReplacementEffect {
        val first = possibleReplacers.first()
        if (possibleReplacers.size == 1) return first
        val firstDescription = first.toString()
        if (possibleReplacers.all { it.toString() == firstDescription }) return first
        val request =
            PromptRequest(
                promptType = "select_replacement",
                message = "Choose which replacement effect applies first",
                options = possibleReplacers.map(ReplacementEffect::toString),
                min = 1,
                max = 1,
                defaultIndex = 0,
                route = PromptRouteResolver.resolve(PromptSemantic.SelectReplacement),
            )
        return bridge.requestReplacement(request, possibleReplacers)?.handle
            ?: super.chooseSingleReplacementEffect(possibleReplacers)
    }

    private fun awaitCommanderReturn(
        hostCard: Card?,
        sa: SpellAbility?,
        logContext: String,
    ): Boolean =
        optionalActionGate.await(
            hostCard = hostCard,
            forceSnapshotBeforePrompt = true,
            defaultOnTimeout = true,
            logContext = logContext,
            customPromptId = PromptIds.COMMANDER_RETURN_TO_COMMAND,
            commanderReturn = hostCard?.let { commanderReturnContext(it, sa) },
        )

    private fun isEnterAsCopyReplacement(message: String): Boolean = message.contains("enter as a copy", ignoreCase = true)

    @Suppress("UNCHECKED_CAST")
    private fun commanderReturnContext(
        card: Card,
        sa: SpellAbility?,
    ): CommanderReturnPromptContext? {
        val originalParams = sa?.getReplacingObject(AbilityKey.OriginalParams) as? Map<AbilityKey, Any?>
        val cardId = ForgeCardId(card.id)
        val oldInstanceId = bridge.forgeIidResolver?.invoke(cardId)?.value ?: return null
        val destination = originalParams?.get(AbilityKey.Destination) as? ZoneType ?: card.zone?.zoneType ?: ZoneType.Graveyard
        val origin =
            originalParams?.get(AbilityKey.Origin) as? ZoneType
                ?: bridge.trackedZoneResolver?.invoke(cardId)
                ?: destination
        val promptInstanceId = bridge.instanceIdReservoir?.invoke()?.value ?: return null
        return CommanderReturnPromptContext(
            oldInstanceId = oldInstanceId,
            promptInstanceId = promptInstanceId,
            originZone = origin.commanderZone(),
            destinationZone = destination.commanderZone(),
            ownerSeatId = seating.seatOf(card.owner.id, card.owner.lobbyPlayer is LobbyPlayerAi).value,
            transferCategory = commanderTransferCategory(origin, destination),
        )
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun ZoneType.commanderZone(): CommanderZone =
        when (this) {
            ZoneType.Battlefield -> CommanderZone.Battlefield
            ZoneType.Graveyard -> CommanderZone.Graveyard
            ZoneType.Exile -> CommanderZone.Exile
            ZoneType.Hand -> CommanderZone.Hand
            ZoneType.Library -> CommanderZone.Library
            ZoneType.Command -> CommanderZone.Command
            else -> CommanderZone.Limbo
        }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun commanderTransferCategory(
        origin: ZoneType,
        destination: ZoneType,
    ): String =
        when (destination) {
            ZoneType.Graveyard -> if (origin == ZoneType.Battlefield) "Destroy" else "Put"
            ZoneType.Exile -> "Exile"
            ZoneType.Hand -> "Bounce"
            ZoneType.Library -> "Put"
            else -> "ZoneTransfer"
        }

    override fun chooseBinary(
        sa: SpellAbility?,
        question: String?,
        kindOfChoice: BinaryChoiceType?,
        defaultVal: Boolean?,
    ): Boolean = staticChoiceCoordinator.chooseBinary(sa, question, kindOfChoice, defaultVal)

    override fun chooseColor(
        message: String,
        sa: SpellAbility?,
        colors: ColorSet,
    ): Byte {
        val cntColors = colors.countColors()
        if (cntColors == 0) return 0
        if (cntColors == 1) return colors.color
        pendingManaColorChoice?.let { selectedColor ->
            pendingManaColorChoice = null
            if (colors.orderedColors.any { it.colorMask == selectedColor }) return selectedColor
        }
        return staticChoiceCoordinator.chooseColor(message, sa, colors)
    }

    override fun chooseColors(
        message: String,
        sa: SpellAbility?,
        min: Int,
        max: Int,
        options: ColorSet,
    ): ColorSet = staticChoiceCoordinator.chooseColors(message, sa, min, max, options)

    override fun chooseSomeType(
        kindOfType: String,
        sa: SpellAbility?,
        validTypes: Collection<String>,
        isOptional: Boolean,
    ): String? = staticChoiceCoordinator.chooseSomeType(kindOfType, sa, validTypes, isOptional)

    override fun willPutCardOnTop(c: Card): Boolean {
        // The bare PromptRequest this used to build (no semantic/route) resolved to
        // ResolvedPromptRoute.AutoResolve — a synchronous default with no prompt
        // ever reaching the client — so every Clash decision was silently forced
        // to stay on top. Arena frames this as "Put that card on the bottom of
        // your library?" (verified against its card database), the inverse sense
        // of this method's return value.
        return !optionalActionGate.await(
            hostCard = c,
            defaultOnTimeout = false,
            logContext = "willPutCardOnTop",
            customPromptId = PromptIds.CLASH_PUT_ON_BOTTOM,
        )
    }

    // -- Zone ordering ----------------------------------------------------
    // PCHuman uses FModel.getPreferences + ForgeConstants

    override fun orderMoveToZoneList(
        cards: CardCollectionView,
        zone: ZoneType,
        sa: SpellAbility?,
    ): CardCollectionView = targetingCoordinator.orderMoveToZoneList(cards, zone, sa)

    // -- Mana payment ------------------------------------------------------
    // Upstream now routes cost payment through PlayerController.payManaCost /
    // applyManaToCost. We override those newer entry points below and keep one
    // auto-pay path instead of carrying older HumanPlay-era seams.

    // -- Convoke / Improvise -----------------------------------------------
    // PCHuman uses InputSelectCardsForConvokeOrImprovise (desktop-only, hangs).
    // Delegate to AI tap-selection for now.  Refs meeting 2026-02-08 Tier 1.

    override fun chooseCardsForConvokeOrImprovise(
        sa: SpellAbility,
        manaCost: ManaCost,
        untappedCards: CardCollectionView,
        artifacts: Boolean,
        creatures: Boolean,
        maxReduction: Int?,
    ): Map<Card, ManaCostShard> {
        NonInteractiveScope.active?.let { policy ->
            return NonInteractiveAnswers.cardsForConvokeOrImprovise(policy, manaCost, untappedCards, artifacts, maxReduction)
        }
        return costPaymentCoordinator.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction)
    }

    override fun chooseCardsToDelve(
        genericAmount: Int,
        grave: CardCollection,
    ): CardCollectionView {
        NonInteractiveScope.active?.let { policy ->
            return NonInteractiveAnswers.cardsToDelve(policy, genericAmount, grave)
        }
        return super.chooseCardsToDelve(genericAmount, grave)
    }

    override fun chooseNumberForCostReduction(
        sa: SpellAbility,
        min: Int,
        max: Int,
    ): Int {
        NonInteractiveScope.active?.let { policy ->
            return NonInteractiveAnswers.numberForCostReduction(policy, min, max)
        }
        return super.chooseNumberForCostReduction(sa, min, max)
    }

    override fun chooseSingleStaticAbility(possibleStatics: List<StaticAbility>): StaticAbility {
        // Reduce/set statics all apply; only the application order is chosen.
        // Non-interactive contexts take them in list order.
        if (NonInteractiveScope.active != null) return possibleStatics.first()
        return super.chooseSingleStaticAbility(possibleStatics)
    }

    override fun choosePlayerToAssistPayment(
        optionList: FCollectionView<Player>,
        sa: SpellAbility,
        title: String?,
        max: Int,
    ): Player? {
        // Assist commits another player's mana; no non-interactive context may
        // assume it.
        if (NonInteractiveScope.active != null) return null
        return super.choosePlayerToAssistPayment(optionList, sa, title, max)
    }

    override fun helpPayForAssistSpell(
        cost: ManaCostBeingPaid,
        sa: SpellAbility,
        max: Int,
        requested: Int,
    ): Boolean {
        if (NonInteractiveScope.active != null) return false
        return super.helpPayForAssistSpell(cost, sa, max, requested)
    }

    // -- Pay cost to prevent effect ----------------------------------------

    override fun payCostToPreventEffect(
        cost: Cost,
        sa: SpellAbility,
        alreadyPaid: Boolean,
        allPayers: FCollectionView<Player>,
    ): Boolean {
        // Single-part life and mana costs route to coordinator helpers;
        // everything else (non-mana echo and multi-part costs) falls through
        // to PCHuman.
        cost.costParts.singleOrNull().let { single ->
            if (single is CostPayLife) {
                val isEtbLandReplacement =
                    sa.api == ApiType.Tap &&
                        sa.hasParam("ETB") &&
                        sa.getParam("Defined") == "Self" &&
                        sa.hostCard?.isLand == true
                return costPaymentCoordinator.payOptionalLife(single, sa, isEtbLandReplacement)
            }
            if (single is CostPartMana && sa.isKeyword(Keyword.WARD)) {
                return costPaymentCoordinator.payOptionalManaCost(cost, sa, "ward")
            }
            if (single is CostPartMana && sa.isKeyword(Keyword.CUMULATIVE_UPKEEP)) {
                return costPaymentCoordinator.payOptionalManaCost(cost, sa, "cumulative-upkeep")
            }
            if (single is CostPartMana) {
                // Mana Leak-style counters, Rhystic Study, "sacrifice unless you
                // pay" lands: PCHuman would auto-tap without ever asking.
                return costPaymentCoordinator.payOptionalManaCost(cost, sa, "unless-cost", requireAffordable = true)
            }
        }
        return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers)
    }

    // -- Discard unless type -----------------------------------------------
    // PCHuman uses InputSelectEntitiesFromList (desktop-only, hangs).
    // Bridge as a card selection prompt.  Refs meeting 2026-02-08 Tier 1.

    override fun chooseCardsToDiscardUnlessType(
        min: Int,
        hand: CardCollectionView,
        param: Array<String>,
        sa: SpellAbility,
    ): CardCollectionView = targetingCoordinator.chooseCardsToDiscardUnlessType(min, hand, param, sa)

    // -- Simultaneous triggered abilities ----------------------------------

    /**
     * PCHuman decides whether an ordering prompt is due (identical triggers aren't worth one)
     * and hands the list to [ClientGuiGame.order] as views; the live abilities ride along here so
     * the prompt runtime keeps the exact handles.
     */
    override fun orderSimultaneousSa(activePlayerSAs: List<SpellAbility>): List<SpellAbility> {
        simultaneousAbilities = activePlayerSAs
        try {
            return super.orderSimultaneousSa(activePlayerSAs)
        } finally {
            simultaneousAbilities = emptyList()
        }
    }

    override fun playTrigger(
        host: Card,
        wrapperAbility: WrappedAbility,
        isMandatory: Boolean,
    ): Boolean {
        if (!wrapperAbility.usesTargeting()) return super.playTrigger(host, wrapperAbility, isMandatory)
        wrapperAbility.activatingPlayer = player
        if (!wrapperAbility.setupTargets()) return false
        return PlaySpellAbility.playSpellAbilityNoStack(this, player, wrapperAbility, true)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Active controller overrides on the current upstream surface.
    // ═══════════════════════════════════════════════════════════════════

    // -- Cost Decision -----------------------------------------------------
    // CostDecision routes interactive cost choices through the bridge.
    override fun getCostDecisionMaker(
        player: Player,
        ability: SpellAbility,
        effect: Boolean,
        prompt: String?,
    ): CostDecisionMakerBase =
        CostDecision(
            this,
            player,
            ability,
            effect,
            bridge,
            prompt,
        )

    // -- Target Selection --------------------------------------------------
    // Bridge-based interactive target selection.
    // TargetSelection validates candidates and zones; this method handles
    // the user interaction portion.

    override fun chooseTargetsFor(currentAbility: SpellAbility): Boolean {
        val previousStackTargetingAbility = activeStackTargetingAbility
        activeStackTargetingAbility =
            currentAbility
                .takeIf { it.getTargetRestrictions()?.getZone()?.singleOrNull() == ZoneType.Stack }
        val previousDividedAllocationAbility = activeDividedAllocationAbility
        activeDividedAllocationAbility = currentAbility
        val chosen =
            try {
                super.chooseTargetsFor(currentAbility)
            } finally {
                activeStackTargetingAbility = previousStackTargetingAbility
                activeDividedAllocationAbility = previousDividedAllocationAbility
            }
        if (chosen) {
            targetingCoordinator.recordCompletedTargetSpec(currentAbility)
        } else {
            targetingCoordinator.discardCompletedTargetSpec(currentAbility)
        }
        return chosen
    }

    internal fun stackTargetCandidate(
        optionIndex: Int,
        option: Any?,
    ): TargetingCandidateValue.StackObject? {
        val stackEntry =
            when (option) {
                is StackItemView -> game.stack.firstOrNull { it.id == option.id }
                is CardView -> {
                    val matches = game.stack.filter { it.sourceCard.id == option.id && it.isSpell }
                    require(matches.size <= 1) {
                        "Ambiguous stack spell for card option ${option.id}: ${matches.map { it.id }}"
                    }
                    matches.singleOrNull()
                }
                is SpellAbilityView -> {
                    val matches = game.stack.filter { it.spellAbility.id == option.id }
                    require(matches.size <= 1) {
                        "Ambiguous stack ability for option ${option.id}: ${matches.map { it.id }}"
                    }
                    matches.singleOrNull()
                }
                else -> null
            } ?: return null
        val ability = stackEntry.spellAbility
        return TargetingCandidateValue.StackObject(
            optionIndex = optionIndex,
            stackInstanceId = stackEntry.id,
            sourceForgeCardId = ForgeCardId(stackEntry.sourceCard.id),
            forgeAbilityId = ability.id,
            isSpell = stackEntry.isSpell,
            isAbility = stackEntry.isAbility,
            isTrigger = stackEntry.isTrigger,
            abilityIdentity = bridge.resolveAbilityIdentity(ability),
        )
    }

    private fun targetingSourceCardId(ability: SpellAbility): Int? {
        val host = ability.hostCard ?: return null
        return if (ability.isSpell && host.gamePieceType == GamePieceType.COPIED_SPELL) host.copiedPermanent?.id ?: host.id else host.id
    }

    override fun chooseNewTargetsFor(
        ability: SpellAbility,
        filter: Predicate<GameObject>?,
        optional: Boolean,
    ): TargetChoices? {
        val previousStackTargetingAbility = activeStackTargetingAbility
        activeStackTargetingAbility =
            ability
                .takeIf { it.getTargetRestrictions()?.getZone()?.singleOrNull() == ZoneType.Stack }
        val previousDividedAllocationAbility = activeDividedAllocationAbility
        activeDividedAllocationAbility = ability
        val selected =
            try {
                super.chooseNewTargetsFor(ability, filter, optional)
            } finally {
                activeStackTargetingAbility = previousStackTargetingAbility
                activeDividedAllocationAbility = previousDividedAllocationAbility
            }
        if (selected != null) {
            val targetAbility = if (ability is WrappedAbility) ability.wrappedAbility else ability
            targetingCoordinator.recordCompletedTargetSpec(targetAbility)
        } else {
            val targetAbility = if (ability is WrappedAbility) ability.wrappedAbility else ability
            targetingCoordinator.discardCompletedTargetSpec(targetAbility)
        }
        return selected
    }

    override fun selectTargetsInteractively(
        validTargets: List<Card>,
        sa: SpellAbility,
        mandatory: Boolean,
        numTargets: Int?,
        divisionValues: Collection<Int>?,
        filter: Predicate<GameObject>?,
        mustTargetFiltered: Boolean,
    ): TargetSelectionResult = targetingCoordinator.selectTargets(validTargets, sa, mandatory, numTargets)

    // -- Mana Payment ------------------------------------------------------
    override fun payManaCost(
        toPay: ManaCost,
        costPartMana: CostPartMana,
        sa: SpellAbility,
        prompt: String?,
        matrix: ManaConversionMatrix?,
        effect: Boolean,
    ): Boolean {
        if (costPartMana is CostWaterbend) {
            val untapped =
                CardCollection(
                    player.getCardsIn(ZoneType.Battlefield).filter { card ->
                        !card.isTapped && (card.isArtifact || card.isCreature)
                    },
                )
            val tappedForWaterbend =
                costPaymentCoordinator.chooseCardsForConvokeOrImprovise(
                    sa = sa,
                    manaCost = toPay,
                    untappedCards = untapped,
                    artifacts = true,
                    creatures = true,
                    maxReduction = toPay.genericCost,
                )
            val remaining = ManaCostBeingPaid(toPay)
            for ((card, shard) in tappedForWaterbend) {
                remaining.decreaseShard(shard, 1)
                card.tap(true, sa, player)
            }
            return PlaySpellAbility.payManaCost(this, remaining.toManaCost(), costPartMana, sa, player, prompt, matrix, effect)
        }
        return PlaySpellAbility.payManaCost(this, toPay, costPartMana, sa, player, prompt, matrix, effect)
    }

    override fun applyManaToCost(
        toPay: ManaCostBeingPaid,
        ability: SpellAbility,
        prompt: String?,
        matrix: ManaConversionMatrix?,
        effect: Boolean,
    ): Boolean = costPaymentCoordinator.applyManaToCost(toPay, ability, effect)

    override fun chooseCardsForCost(
        optionList: CardCollectionView,
        sa: SpellAbility,
        cpl: CostPart,
        amount: Int,
        isOptional: Boolean,
        prompt: String,
    ): CardCollectionView {
        if (cpl is CostTapType && sa.isHarmonize) {
            harmonizeTap?.takeIf { it in optionList }?.let { chosen ->
                harmonizeTap = null
                return CardCollection(chosen)
            }
        }
        val tapPayment = TapPaymentPolicy.exact(cpl, amount, sa)
        val semantic =
            when (cpl) {
                is CostDiscard -> PromptSemantic.SelectNDiscard
                is CostReturn ->
                    if (cpl.type.contains("attacking+unblocked") ||
                        cpl.descriptiveType.contains("unblocked attacker", ignoreCase = true)
                    ) {
                        PromptSemantic.ReturnUnblockedAttackerCost
                    } else {
                        PromptSemantic.Generic
                    }
                is CostSacrifice -> PromptSemantic.SelectNCostSacrifice
                is CostEnlist -> PromptSemantic.EnlistCost
                // Fixed-count Station taps (tapXType<1/...>) pay through the
                // exact-count seam, not chooseCardsForTapCost.
                is CostTapType ->
                    when {
                        sa.isKeyword(Keyword.STATION) -> PromptSemantic.StationTapCost
                        tapPayment != null -> PromptSemantic.TapPaymentCost
                        else -> PromptSemantic.Generic
                    }
                is CostUntapType -> if (tapPayment != null) PromptSemantic.TapPaymentCost else PromptSemantic.Generic
                is CostForage ->
                    if (optionList.all { it.isInPlay }) {
                        PromptSemantic.SelectNCostSacrifice
                    } else {
                        PromptSemantic.Generic
                    }
                else -> PromptSemantic.Generic
            }
        val selected =
            targetingCoordinator.chooseCardsViaBridge(
                cards = optionList,
                min = if (isOptional && cpl is CostDiscard) 0 else amount,
                max = amount,
                message = prompt,
                semantic = semantic,
                candidateRefs = optionList.toCandidateRefs(),
                sourceEntityId = sa.hostCard.id.takeIf { it > 0 },
                tapPayment = tapPayment?.descriptor,
                payCostsPromptSource = tapPayment?.promptSource,
                // Grounded tap rows retain their PayCosts envelope even when
                // Forge offers exactly one eligible permanent.
                forcePrompt = isOptional || tapPayment != null || cpl is CostBlight,
                // An optional cost may also be declined through the client's cancel button,
                // not only by submitting the empty selection that `min = 0` allows.
                cancellable = isOptional,
            )
        if (cpl is CostEnlist && selected.isNotEmpty()) {
            bridge.journal.record(
                PromptSideEffect.EnlistTapAffector(
                    tappedForgeCardId = ForgeCardId(selected.first().id),
                    attackerForgeCardId = ForgeCardId(sa.hostCard.id),
                ),
            )
        }
        return selected
    }

    @Suppress("LongParameterList") // mirrors the Forge hook's flat contract
    override fun chooseCardsForZoneChange(
        destination: ZoneType,
        origin: List<ZoneType>,
        sa: SpellAbility,
        fetchList: CardCollection,
        min: Int,
        max: Int,
        delayedReveal: DelayedReveal?,
        selectPrompt: String?,
        decider: Player?,
    ): List<Card> =
        if (isActiveGraveyardExileCost(destination, origin, sa)) {
            chooseExactGraveyardExileCost(fetchList, sa, max, selectPrompt.orEmpty())
        } else {
            super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider)
        }

    private fun isActiveGraveyardExileCost(
        destination: ZoneType,
        origin: List<ZoneType>,
        sa: SpellAbility,
    ): Boolean {
        val activeCost =
            sa.hostCard.game
                .costPaymentStack
                .peek()
        return destination == ZoneType.Exile &&
            origin.singleOrNull() == ZoneType.Graveyard &&
            activeCost?.cost is CostExile &&
            activeCost.payment.ability === sa
    }

    private fun chooseExactGraveyardExileCost(
        optionList: CardCollectionView,
        sa: SpellAbility,
        amount: Int,
        prompt: String,
    ): List<Card> =
        targetingCoordinator
            .chooseCardsViaBridge(
                cards = optionList,
                min = amount,
                max = amount,
                message = prompt,
                semantic = PromptSemantic.SelectNCostExileFromGrave,
                candidateRefs = optionList.toCandidateRefs(),
                sourceEntityId = sa.hostCard.id.takeIf { it > 0 },
                forcePrompt = true,
            ).toList()

    override fun chooseCardsForCollectEvidence(
        optionList: CardCollectionView,
        sa: SpellAbility,
        total: Int,
        prompt: String,
    ): CardCollectionView {
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = prompt,
                options = optionList.map { it.name },
                min = 0,
                max = optionList.size,
                defaultIndex = 0,
                candidateRefs = optionList.toCandidateRefs(),
                route = PromptRouteResolver.resolve(PromptSemantic.SelectNCostCollectEvidence),
                costSelectionWeights = optionList.map { it.getCMC().coerceAtLeast(0) },
                minSelectionWeight = total,
                sourceEntityId = sa.hostCard.id.takeIf { it > 0 },
            )
        val selected = CardCollection(bridge.requestOneShotPayCosts(request, optionList.toList()).handles)
        if (CardLists.getTotalCMC(selected) < total) {
            bridge.journal.clearCollectEvidenceCost()
        }
        return selected
    }

    override fun chooseCardsForRevealCost(
        optionList: CardCollectionView,
        sa: SpellAbility,
        cost: CostPartWithList,
        amount: Int,
        optional: Boolean,
        sameColor: Boolean,
        prompt: String,
    ): CardCollectionView = chooseCardsForCost(optionList, sa, cost, amount, optional, prompt)

    override fun chooseCardsForTapCost(
        optionList: CardCollectionView,
        sa: SpellAbility,
        cost: CostTapType,
        min: Int,
        max: Int,
        totalPowerNeeded: Int?,
        prompt: String,
    ): CardCollectionView {
        val tapPayment = totalPowerNeeded?.let { TapPaymentPolicy.totalPower(it, sa) }
        val semantic =
            when {
                sa.isKeyword(Keyword.STATION) -> PromptSemantic.StationTapCost
                tapPayment != null -> PromptSemantic.TapPaymentCost
                else -> PromptSemantic.Generic
            }
        return targetingCoordinator.chooseCardsViaBridge(
            cards = optionList,
            min = maxOf(min, 1),
            max = max,
            message = prompt,
            semantic = semantic,
            candidateRefs = optionList.toCandidateRefs(),
            sourceEntityId = sa.hostCard.id.takeIf { it > 0 },
            tapPayment = tapPayment?.descriptor,
            payCostsPromptSource = tapPayment?.promptSource,
            // Grounded total-power rows keep the one-shot PayCosts envelope,
            // while unsupported rows retain Forge's forced-list shortcut.
            forcePrompt = tapPayment != null,
            costSelectionWeights =
                if (tapPayment != null) TapPaymentPolicy.totalPowerWeights(optionList, sa) else emptyList(),
            minSelectionWeight = tapPayment?.descriptor?.required,
        )
    }

    // Aggregate exile prompts project plain min/max Generic selections;
    // no weight/threshold envelope is defined for these costs yet.
    @Suppress("LongParameterList") // mirrors the Forge hook's flat contract
    override fun chooseCardsForExileCost(
        optionList: CardCollectionView,
        sa: SpellAbility,
        cost: CostExile,
        min: Int,
        max: Int,
        aggregateHint: String?,
        aggregateGoal: Int?,
        sharedCardType: Boolean,
        cancelAllowed: Boolean,
        prompt: String,
    ): CardCollectionView =
        targetingCoordinator.chooseCardsViaBridge(
            cards = optionList,
            min = min,
            max = max,
            message = prompt,
            semantic = PromptSemantic.Generic,
            candidateRefs = optionList.toCandidateRefs(),
            sourceEntityId = sa.hostCard.id.takeIf { it > 0 },
            forcePrompt = cancelAllowed,
        )

    // -- Seam 5: chooseNumberForKeywordCost ----------------------------------
    // PCHuman uses InputConfirm.confirm() when max==1 (desktop-only, hangs on
    // web) and getGui().getInteger() for max>1 (bridged, works fine).
    // Override only the max==1 path to route through the bridge confirm prompt.

    override fun chooseNumberForKeywordCost(
        sa: SpellAbility,
        cost: Cost,
        keyword: KeywordInterface,
        prompt: String,
        max: Int,
    ): Int =
        when {
            max <= 0 -> 0
            keyword.keyword == Keyword.HARMONIZE && max == 1 -> if (harmonizeTap != null) 1 else 0
            max == 1 -> costPaymentCoordinator.chooseKeywordCostBinary(prompt, keyword.keyword?.toString())
            // max > 1: getGui().getInteger() is bridged through ClientGuiGame, safe to inherit.
            else -> super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max)
        }

    override fun chooseOptionalCosts(
        chosenSa: SpellAbility,
        optionalCosts: MutableList<OptionalCostValue>,
    ): MutableList<OptionalCostValue> = costPaymentCoordinator.chooseOptionalCosts(chosenSa, optionalCosts)

    // -- Seam 6: chooseNumber (NumericInputReq) ------------------------------
    // PCHuman routes all three overloads through Swing-backed `getGui()` calls
    // (`ClientGuiGame.getInteger` / `.one`), which silently auto-respond in
    // headless mode and don't emit a wire prompt. Override the two range-style
    // overloads to route through the numeric-input gate so the client gets a
    // real `NumericInputReq` (ChooseX). The list-of-values overload is rarer
    // and a different shape; throw to surface the call site if it ever fires.

    override fun chooseNumber(
        sa: SpellAbility,
        title: String,
        min: Int,
        max: Int,
    ): Int {
        // PCHuman short-circuits when the range is degenerate; preserve that
        // invariant so we don't ship a NumericInputReq with maxValue == minValue
        // (or worse, max < min) and wait for a pointless client roundtrip.
        if (sa.isHarmonize && title == HARMONIZE_TAP_TITLE) return chooseHarmonizeTapPower(sa)
        if (min >= max) return min
        return numericInputGate.await(
            sourceCard = sa.hostCard,
            min = min,
            max = max,
            defaultOnTimeout = min,
            logContext = "chooseNumber",
        )
    }

    /**
     * Forge asks Harmonize for "the power of the creature to tap" as a bare number, then makes
     * the caster tap a creature of exactly that power. Arena just asks which creature to tap and
     * lowers the cost by its power, so ask that up front, answer Forge's number with the chosen
     * creature's power, and hand the same creature to the tap-cost payment that follows.
     * Cancelling the prompt means no reduction.
     */
    private fun chooseHarmonizeTapPower(sa: SpellAbility): Int {
        harmonizeTap = null
        if (NonInteractiveScope.active != null) return 0
        val candidates = CardCollection(player.creaturesInPlay.filter { !it.isTapped })
        if (candidates.isEmpty()) return 0
        val tapCost = Cost("tapXType<1/Creature/creature for Harmonize>", false).costParts.first()
        val tapPayment = TapPaymentPolicy.exact(tapCost, 1, sa)
        val chosen =
            targetingCoordinator
                .chooseCardsViaBridge(
                    cards = candidates,
                    min = 1,
                    max = 1,
                    message = "Tap a creature to reduce the cost?",
                    semantic = if (tapPayment != null) PromptSemantic.TapPaymentCost else PromptSemantic.Generic,
                    candidateRefs = candidates.toCandidateRefs(),
                    sourceEntityId = sa.hostCard.id.takeIf { it > 0 },
                    tapPayment = tapPayment?.descriptor,
                    payCostsPromptSource = tapPayment?.promptSource,
                    forcePrompt = true,
                    cancellable = true,
                ).firstOrNull() ?: return 0
        harmonizeTap = chosen
        return chosen.netPower
    }

    /**
     * Params overload: currently ignores `params` by design — the no-params
     * overload above is sufficient for every site we've observed. If Forge
     * ever passes a meaningful flag here (e.g. an "is counter amount" hint
     * that should change emission semantics), thread it through
     * `NumericInputGate` via a new optional field rather than silently
     * dropping.
     */
    override fun chooseNumber(
        sa: SpellAbility,
        string: String,
        min: Int,
        max: Int,
        params: MutableMap<String, Any>?,
    ): Int {
        if (min >= max) return min
        return numericInputGate.await(
            sourceCard = sa.hostCard,
            min = min,
            max = max,
            defaultOnTimeout = min,
            logContext = "chooseNumber(params)",
        )
    }

    override fun chooseNumber(
        sa: SpellAbility,
        title: String,
        values: MutableList<Int>,
        relatedPlayer: Player?,
    ): Int =
        error(
            "chooseNumber(sa, title, values, relatedPlayer) not yet implemented for headless bridge — " +
                "list-of-values shape needs a separate emit path (likely SelectN-of-1). " +
                "sa.hostCard=${sa.hostCard?.name}, values=$values",
        )

    /**
     * Forge's `PCHuman.announceRequirements` only routes through `chooseNumber` when
     * the cost is mandatory; the optional-X branch goes straight to
     * `getGui().getInteger`, which the headless `ClientGuiGame` shim resolves to a
     * silent `0` (the choose_one path never lands a `NumericInputReq` on the wire).
     *
     * Override redirects both branches through `chooseNumber` so the gate fires
     * for "you may pay {X}" triggers (e.g. Wildborn Preserver's ImmediateTrigger).
     */
    override fun announceRequirements(
        ability: SpellAbility,
        min: Int,
        max: Int,
        announce: String,
    ): Int? {
        val host = ability.hostCard
        val cost: Cost? = ability.payCosts
        var effectiveMax = max

        if ("X" == announce && cost != null) {
            val costX = cost.getMaxForNonManaX(ability, player, false)
            val flag = forge.game.player.PlayerController.FullControlFlag.AllowPaymentStartWithMissingResources
            if (costX != null && !player.controller.isFullControl(flag)) {
                effectiveMax = minOf(effectiveMax, costX)
            }
        }

        if (min > effectiveMax) return null

        val announceTitle =
            if ("X" == announce) {
                ability.getParamOrDefault("XAnnounceTitle", announce)
            } else {
                ability.getParamOrDefault("AnnounceTitle", announce)
            }
        val title = "Choose $announceTitle for ${host?.translatedName ?: "spell"}"
        return chooseNumber(ability, title, min, effectiveMax)
    }

    // -- Play spell --------------------------------------------------------
    // PCHuman uses HumanPlay + HumanPlaySpellAbility (desktop Input classes)

    override fun playChosenSpellAbility(chosenSa: SpellAbility): Boolean {
        // Use the upstream PlaySpellAbility path so cost decisions, optional
        // costs, rollback, splice, and mana conversion all stay centralized.
        //
        // Targets may be pre-set on the outer SA when the client's Cast
        // PerformAction carries target ids — SpellExecutor.applyTargets()
        // populates them before this seam runs. When targets are NOT pre-set
        // we pass mayChooseTargets=true so the engine invokes setupTargets(),
        // which walks the SA chain (including post-makeChoices wrapper subs)
        // and routes per-link targeting through selectTargetsInteractively() →
        // InteractivePromptBridge → SelectTargetsReq/Resp.
        chosenSa.setActivatingPlayer(player)

        if (chosenSa.isLandAbility) {
            val success = chosenSa.canPlay()
            if (success) chosenSa.resolve()
            priorityLoopCoordinator?.actionCompleted(success)
            return success
        }

        // Apply optional costs (kicker, buyback, etc.) BEFORE playAbility.
        // Can't delegate to PlaySpellAbility.chooseOptionalAdditionalCosts() because
        // it calls getAbilityToPlay() which blocks on InteractivePromptBridge — deadlock
        // since the engine thread is already in this call.
        // Instead, read the stashed decision from TargetingHandler (set after client
        // responded to CastingTimeOptionsReq). Fallback: auto-accept all (test harness).
        var sa = chosenSa
        val optionalCosts =
            GameActionUtil
                .getOptionalCostValues(sa)
                .filterNot { sa.isOptionalCostPaid(it.type) }
                .toMutableList()
        if (optionalCosts.isNotEmpty()) {
            val chosen = chooseOptionalCosts(sa, optionalCosts)
            sa = GameActionUtil.addOptionalCosts(sa, chosen)
        }

        sa.hostCard?.setSplitStateToPlayAbility(sa)

        // Wrapper APIs (Charm, Effect, Repeat, …) often have a non-targeting
        // outer SA whose chosen sub-SAs target. Forge's setupTargets() walks
        // the chain post-makeChoices and only invokes chooseTargetsFor on
        // links where usesTargeting() is true, so passing mayChooseTargets=true
        // is a no-op for genuinely non-targeting chains. The only thing the
        // gate must protect is a pre-set outer-target supplied via the Cast
        // PerformAction — sa.targets.isEmpty() handles that.
        val needsTargeting = sa.targets.isEmpty()
        return withActiveSpellSource(sa) {
            val req = PlaySpellAbility(this, sa)
            req.playAbility(needsTargeting, false, false)
        }.also { priorityLoopCoordinator?.actionCompleted(it) }
    }

    private fun <T> withActiveSpellSource(
        sa: SpellAbility,
        block: () -> T,
    ): T {
        val previous = activeSpellSourceId
        val previousIsSpell = activeSourceIsSpell
        activeSpellSourceId = sa.hostCard?.id ?: previous
        activeSourceIsSpell = sa.isSpell
        return try {
            block()
        } finally {
            activeSpellSourceId = previous
            activeSourceIsSpell = previousIsSpell
        }
    }

    private fun currentSourceEntityId(): Int? =
        activeSpellSourceId
            ?: game
                .stack
                .firstOrNull()
                ?.sourceCard
                ?.id

    override fun playSpellAbilityNoStack(
        effectSA: SpellAbility,
        mayChoseNewTargets: Boolean,
    ) {
        // Forge's no-stack play helper performs target setup and pays costs
        // before resolving the complete sub-ability chain.
        PlaySpellAbility.playSpellAbilityNoStack(this, player, effectSA, !mayChoseNewTargets)
    }

    override fun chooseSaToActivateFromOpeningHand(usableFromOpeningHand: List<SpellAbility>): List<SpellAbility> =
        usableFromOpeningHand.filter(SpellAbility::isOpeningHandBattlefieldPut)

    override fun chooseModeForAbility(
        sa: SpellAbility,
        possible: MutableList<AbilitySub>,
        min: Int,
        num: Int,
        allowRepeat: Boolean,
    ): List<AbilitySub> {
        if (possible.isEmpty()) return emptyList()

        val fullList = sa.getAdditionalAbilityList("Choices")
        if (!allowRepeat && min == num && num == possible.size && (fullList == null || fullList.size == possible.size)) {
            return possible
        }

        val labels = possible.map { it.description ?: it.toString() }

        val request =
            PromptRequest(
                promptType = if (num == 1) "choose_one" else "choose_cards",
                message = "Choose mode for ${sa.hostCard.translatedName}",
                options = labels,
                min = min,
                max = num,
                allowRepeat = allowRepeat,
                defaultIndex = 0,
                route = PromptRouteResolver.resolve(PromptSemantic.ModalChoice),
                sourceEntityId = sa.hostCard.id,
                isTriggeredAbility = sa.isTrigger,
                forgeAbilityId = if (sa.isTrigger) sa.id else 0,
            )
        return bridge.requestModalChoice(request, possible, sa.hostCard, sa)
    }

    override fun chooseKeywordForPump(
        options: List<String>,
        sa: SpellAbility,
        prompt: String,
        tgtCard: Card,
    ): String = staticChoiceCoordinator.chooseKeywordForPump(options, sa, prompt)

    // -- Mulligan / starting player ----------------------------------------
    // The engine's MulliganService calls these on the game thread.
    // When a MulliganBridge is wired, they block until the client
    // submits a decision. Without a bridge (tests, AI), they auto-decide.

    override fun mulliganKeepHand(
        mulliganingPlayer: Player,
        cardsToReturn: Int,
    ): Boolean {
        val mb =
            mulliganBridge ?: run {
                log.debug("mulliganKeepHand: no bridge, auto-keep")
                return true
            }
        val keep = mb.awaitKeepDecision(player.id, cardsToReturn)
        if (!keep || cardsToReturn <= 0) return keep

        // Forge's London implementation asks for bottom cards after every redraw.
        // The tabletop rule defers that choice until the player keeps, so collect
        // and apply the tuck here after the accepted keep decision.
        val hand = CardCollection(player.getCardsIn(ZoneType.Hand))
        for (card in mb.awaitTuckDecision(player.id, cardsToReturn, hand)) {
            game.action.moveToLibrary(card, -1, null)
        }
        return true
    }

    override fun tuckCardsViaMulligan(
        hand: CardCollectionView,
        cardsToReturn: Int,
    ): CardCollectionView {
        if (cardsToReturn <= 0) return CardCollection()
        if (mulliganBridge == null) {
            log.debug("tuckCardsViaMulligan: no bridge, auto-tuck {}", cardsToReturn)
            val toReturn = CardCollection()
            for (i in 0 until cardsToReturn.coerceAtMost(hand.size)) {
                toReturn.add(hand[i])
            }
            return toReturn
        }
        // London bottoming is handled after Keep in mulliganKeepHand(). Returning
        // no cards here preserves a full seven-card hand for the next decision.
        return CardCollection()
    }

    override fun chooseStartingPlayer(isFirstGame: Boolean): Player {
        // Engine determines starting player via coin flip in GameAction.startGame().
        // This is only called in specific variants; auto-choose self.
        log.debug("chooseStartingPlayer: auto-choose self")
        return player
    }

    // ═══════════════════════════════════════════════════════════════════
    // Game-loop overrides — delegate to PriorityLoopCoordinator when the
    // action bridge is present. Fall through to PCHuman's default otherwise
    // (tests that construct GameBridge without a session layer).
    // ═══════════════════════════════════════════════════════════════════

    override fun chooseSpellAbilityToPlay(): List<SpellAbility>? {
        val coord = priorityLoopCoordinator ?: return super.chooseSpellAbilityToPlay()
        return coord.chooseSpellAbility()
    }

    override fun declareAttackers(
        attacker: Player,
        combat: Combat,
    ) {
        val coord = priorityLoopCoordinator ?: return super.declareAttackers(attacker, combat)
        coord.declareAttackers(attacker, combat)
    }

    override fun enlistAttackers(attackers: MutableList<Card>): MutableList<Card> {
        val coord = priorityLoopCoordinator ?: return super.enlistAttackers(attackers)
        val selected = coord.enlistAttackers(attackers)
        return CardCollection(selected)
    }

    override fun declareBlockers(
        defender: Player,
        combat: Combat,
    ) {
        val coord = priorityLoopCoordinator ?: return super.declareBlockers(defender, combat)
        coord.declareBlockers(defender, combat)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Static application confirmations
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Auto-decline "assign damage as though unblocked" for trample creatures.
     * The compatibility flow uses AssignDamageReq for manual damage distribution.
     * Forge's desktop UI offers this as a convenience shortcut, but we suppress it
     * to keep the prompt shape consistent.
     */
    override fun confirmStaticApplication(
        hostCard: Card,
        mode: PlayerActionConfirmMode,
        message: String,
        logic: String?,
    ): Boolean {
        if (mode == PlayerActionConfirmMode.AlternativeDamageAssignment) {
            log.info("confirmStaticApplication: auto-declining AlternativeDamageAssignment for {}", hostCard.name)
            return false
        }
        return super.confirmStaticApplication(hostCard, mode, message, logic.orEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════
    // Combat damage assignment
    // ═══════════════════════════════════════════════════════════════════

    override fun assignCombatDamage(
        attacker: Card,
        blockers: CardCollectionView,
        remaining: CardCollectionView?,
        damageDealt: Int,
        defender: GameEntity?,
        overrideOrder: Boolean,
    ): MutableMap<Card?, Int>? {
        // Single blocker, no trample → auto-assign, no UI needed.
        val needsManualAssign =
            blockers.size > 1 ||
                (attacker.hasKeyword(Keyword.TRAMPLE) && defender != null)
        val fallback: () -> MutableMap<Card?, Int>? = {
            super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder)
        }
        if (!needsManualAssign) return fallback()

        val coord = priorityLoopCoordinator ?: return fallback()
        return coord.promptForCombatDamage(attacker, blockers, damageDealt, defender, fallback)
    }

    override fun notifyStateChanged() {
        if (onStateChanged != null) {
            try {
                onStateChanged.invoke()
            } catch (ex: Exception) {
                log.debug("State notification failed: ${ex.message}")
            }
        }
    }
}

private fun SpellAbility.isOpeningHandBattlefieldPut(): Boolean =
    api == ApiType.ChangeZone &&
        hostCard?.zone?.zoneType == ZoneType.Hand &&
        getParam("Origin") == "Hand" &&
        getParam("Destination") == "Battlefield"
