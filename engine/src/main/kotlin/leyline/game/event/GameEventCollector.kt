package leyline.game.event

import com.google.common.eventbus.Subscribe
import forge.card.CardStateName
import forge.game.ability.AbilityKey
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.card.CardView
import forge.game.event.*
import forge.game.event.GameEventManaAbilityActivated
import forge.game.event.GameEventSpellMovedToStack
import forge.game.keyword.Keyword
import forge.game.player.Player
import forge.game.player.PlayerView
import forge.game.spellability.AlternativeCost
import forge.game.spellability.OptionalCost
import forge.game.spellability.SpellAbility
import forge.game.trigger.WrappedAbility
import forge.game.zone.ZoneType
import leyline.bridge.types.AbilityDefinitionRef
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.ManaColorMapping
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.bridge.types.SeatId
import leyline.bridge.types.WubrgColorMapping
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.PlayerMapper
import leyline.game.mapping.ZoneIds
import leyline.game.state.GameBridge
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.GroupingContext
import java.util.concurrent.ConcurrentHashMap
import forge.game.event.DamageSourceKind as ForgeDamageSourceKind

/** Immutable per-frame snapshot of game events in firing order. */
class FrameEventLog(
    events: List<GameEvent>,
    zoneMoves: List<ZoneMove> = emptyList(),
) {
    val events: List<GameEvent> = java.util.Collections.unmodifiableList(events.toList())
    val zoneMoves: List<ZoneMove> = java.util.Collections.unmodifiableList(zoneMoves.toList())

    companion object {
        val EMPTY = FrameEventLog(emptyList())
    }
}

/**
 * Subscribes to the Forge engine's Guava EventBus and converts rich Java
 * [GameEvent][forge.game.event.GameEvent] objects into protocol-oriented
 * [GameEvent] sealed variants.
 *
 * ## Frame contract
 *
 * Events accumulate in a per-frame [MutableList]. [closeFrame] returns the
 * accumulated list as an immutable [FrameEventLog] and atomically swaps in a
 * fresh empty list for the next frame. Multiple downstream consumers can each
 * call [FrameEventLog.events]`.filterIsInstance<…>()` independently — the
 * frozen list is shared safely.
 *
 * The projection shell closes the frame before calling StateMapper. A second
 * close with no intervening engine event returns an empty log. The returned log
 * is immutable; subsequent events append to the replacement frame.
 *
 * ## Event ordering
 *
 * Events fire in Forge engine execution order, which may differ from the
 * annotation ordering the client expects. [leyline.game.annotations.ZoneMoveLedger]
 * folds ordered moves with specific lifecycle events; specific operations such
 * as land play and sacrifice take precedence over the generic zone outcome.
 *
 * ## Cross-class flag consumption
 *
 * Two helper methods consume single-use flags set by
 * [PlayerController][leyline.bridge.forge.PlayerController] on the
 * [InteractivePromptBridge][leyline.bridge.handoff.InteractivePromptBridge]:
 *
 * - [isLegendRuleVictim]: drains `LegendVictim` effects from the prompt journal → emits
 *   [GameEvent.LegendRuleDeath] instead of generic ZoneChanged for
 *   BF→GY legend rule deaths. Written by `TargetingCoordinator.recordLegendVictim`.
 * - [consumeEnlistTapAffector]: drains `EnlistTapAffector` when its paid tap
 *   event arrives → associates the tapped creature with the attacker for the
 *   linked Enlist trigger. Written while `PlayerController` pays the Enlist cost.
 *
 * Both effects are written and consumed on the engine thread (events fire
 * synchronously during the engine operation that triggered the zone change).
 * Consumption removes the entry so it doesn't leak to subsequent events.
 *
 * ## Threading
 *
 * Events fire synchronously on the engine thread. Ordinary playback closes at
 * the engine's step-completion safe point. A shell path may close while the
 * engine is blocked at a controller handoff; it must never race an active
 * mutation burst. The `@Volatile` reference swap publishes the replacement
 * frame across those domains, and the returned list is never mutated past the
 * swap.
 *
 * **Adding new mechanics:** When upstream Forge events lack the granularity we need
 * (per-card IDs, zone-pair specificity), add a new event to our fork rather than
 * retroactively correlating events here. See [GameEventCardSurveiled] for the pattern:
 * fire per-card from `Player.surveil()`, handle with a simple visit override.
 *
 * @param bridge used to resolve Player → seatId, access prompt bridge flags, and
 *   allocate stack iids for copy-cast events that can resolve before a snapshot
 */
@Suppress("LargeClass")
class GameEventCollector(
    private val bridge: GameBridge,
) : IGameEventVisitor.Base<Unit>() {
    private val log = LoggerFactory.getLogger(GameEventCollector::class.java)

    // Atomic frame swap: engine-thread @Subscribe handlers append; closeFrame() takes
    // the current list and installs a fresh empty one. The reference is volatile, the
    // list itself is mutated only before the swap.
    @Suppress("DoubleMutabilityForCollection")
    @Volatile
    private var frame: MutableList<GameEvent> = mutableListOf()

    @Suppress("DoubleMutabilityForCollection")
    @Volatile
    private var zoneMoves: MutableList<ZoneMove> = mutableListOf()

    private var openingHandActionWindow = true

    /**
     * Stack AbilityInstance context keyed by Forge SpellAbility id. Cast events
     * record whether the id represents a trigger or an activated ability; resolve
     * events consume the same context because SpellAbilityView doesn't expose the
     * distinction at resolution time.
     *
     * Subscriber independence: [leyline.game.GamePlayback] keeps its own pending
     * trigger map for the same saIds. Both populate at cast and drain at resolve;
     * neither can rely on the other's state.
     */
    private val pendingStackAbilities = PendingStackAbilityRegistry()

    /** Stack iids for Paradigm copy casts, keyed by Forge SpellAbility id until resolution. */
    private val pendingParadigmCopyStackIids = ConcurrentHashMap<Int, Int>()

    /** Selected spell-face identity carried from cast through resolution. */
    private val pendingSpellFaceGrpIds = ConcurrentHashMap<ForgeCardId, Int>()

    /** Enlist taps are paid before the trigger resolves; carry tapped creature → attacker across frames. */
    private val pendingEnlistAffectors = ConcurrentHashMap<ForgeCardId, ForgeCardId>()

    /** Enlist's linked trigger points at the paid tap object, not a target. */
    private val pendingEnlistedByAttacker = ConcurrentHashMap<ForgeCardId, ForgeCardId>()
    private val pendingEnlistedIidsByAttacker = ConcurrentHashMap<ForgeCardId, InstanceId>()

    /** Non-consuming check: is this SpellAbility a triggered ability currently on the stack?
     *  Used by [leyline.game.GamePlayback] to decide whether to insert a per-step diff
     *  for trigger resolutions on the local player's turn. */
    fun isTriggerResolving(saId: Int): Boolean = pendingStackAbilities.isTriggerResolving(saId)

    /**
     * Close the current frame: returns events accumulated since the last
     * close in engine firing order, and starts a fresh empty frame.
     *
     * Called by the projection shell at journal boundaries and engine safe
     * points. An immediate second call with no intervening event returns an
     * empty log.
     */
    fun closeFrame(): FrameEventLog {
        val outEvents = frame
        val outMoves = zoneMoves
        frame = mutableListOf()
        zoneMoves = mutableListOf()
        return FrameEventLog(outEvents, outMoves)
    }

    /** Peek at the current open frame without closing it (for tests). */
    fun peekEvents(): List<GameEvent> = frame.toList()

    /** True if the current frame has events accumulated. */
    fun hasEvents(): Boolean = frame.isNotEmpty()

    fun closeOpeningHandActionWindow() {
        openingHandActionWindow = false
    }

    // -- EventBus entry point --

    @Subscribe
    fun receiveGameEvent(ev: forge.game.event.GameEvent) {
        ev.visit(this)
    }

    override fun visit(ev: GameEventLandPlayed) {
        val seat = seatOf(ev.player()) ?: return
        val land = ev.land()
        val liveLand = bridge.findCard(ForgeCardId(land.id))
        val colorOrdinals =
            liveLand
                ?.let(::computeColorOrdinals)
                ?: emptyList()
        val isMdfc = liveLand?.isModal == true && liveLand.currentStateName == CardStateName.Backside
        frame.add(GameEvent.LandPlayed(ForgeCardId(land.id), seat, colorOrdinals, isMdfc = isMdfc))
        log.debug("event: LandPlayed card={} seat={} colors={} mdfc={}", land.name, seat, colorOrdinals, isMdfc)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    override fun visit(ev: GameEventSpellAbilityCast) {
        val card = ev.sa().hostCard ?: return
        val seat = seatOf(card.controller) ?: return
        val topSa =
            bridge
                .getGame()
                ?.stack
                ?.peek()
                ?.spellAbility
        val payments =
            ev.manaPayments().map { mp ->
                val sourceCardId = ForgeCardId(mp.sourceCardId())
                val abilityDefinitionId = mp.abilityDefinitionId()
                val abilityGrpId =
                    if (abilityDefinitionId != 0) {
                        bridge
                            .findCard(sourceCardId)
                            ?.let { source ->
                                bridge.resolveAbilityIdentity(
                                    source,
                                    AbilityDefinitionRef.SpellAbility(abilityDefinitionId),
                                )
                            }?.abilityGrpId
                            ?: 0
                    } else {
                        0
                    }
                GameEvent.ManaPayment(
                    sourceCardId = sourceCardId,
                    color = mp.color().toInt() and 0xFF,
                    abilityGrpId = abilityGrpId,
                )
            }
        val realCard = bridge.findCard(ForgeCardId(card.id))
        val isAdventure =
            realCard != null &&
                realCard.isAdventureCard &&
                realCard.currentStateName == CardStateName.Secondary
        val isOmen = topSa?.isOmen == true
        val isMdfc = topSa?.hostCard?.isModal == true && topSa.cardStateName == CardStateName.Backside
        // Alt-cost detection. Most keywords surface as a Forge AlternativeCost;
        // Cleave is script-level (`PrecostDesc$ Cleave`) on a non-basic spell SA.
        // ev.sa() is a SpellAbilityView snapshot which doesn't expose alt-cost.
        // Peek the live stack instead — the just-cast spell sits on top — then
        // resolve to the client ability grpId via the keyword→grpId lookup
        // (same path ActionMapper uses when offering the alt-cost cast action).
        val saAltCost =
            if (topSa != null && topSa.hostCard?.id == card.id) {
                topSa.getAlternativeCost()
            } else {
                null
            }
        val grpId = bridge.consumeSelectedSpellGrpId(ForgeCardId(card.id)) ?: bridge.cardRepository.findGrpIdByName(card.name) ?: 0
        val keywordId = castThroughAbilityKeywordId(topSa, saAltCost)
        val isParadigmCopyCast = isParadigmCopyCast(topSa)
        val castingPermission =
            bridge.allSeatIds().firstNotNullOfOrNull { seat ->
                bridge
                    .promptBridge(SeatId(seat))
                    .journal
                    .consumeCastingPermission(ForgeCardId(card.id))
            }
        val altCostAbilityGrpId =
            if (isParadigmCopyCast) {
                149
            } else if (castingPermission != null) {
                149
            } else if (topSa?.isCastFaceDown == true) {
                // Disguise / Morph face-down hand-cast SAs have no
                // AlternativeCost enum entry — they're plain Forge `Spell`s
                // with `setCastFaceDown(true)`. The CastingTimeOption pAnn
                // for these cards uses the keyword's BaseId (Disguise=307,
                // Morph=351) directly as alternateCostGrpId, not a per-card
                // ability row. Disguise is the only mechanic in v1; Morph
                // arrives later via the same path.
                KeywordAbilityIds.DISGUISE
            } else if (grpId != 0 && keywordId != null) {
                bridge.cardRepository.findKeywordAbilityGrpId(grpId, keywordId) ?: 0
            } else {
                0
            }
        val castAbilityGrpId =
            if (isParadigmCopyCast) {
                KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER
            } else if (castingPermission != null) {
                castingPermission.castAbilityGrpId
            } else {
                altCostAbilityGrpId
            }
        // Trigger / ability detection: StackItemView distinguishes spells from
        // Ability gameObjects (triggered or activated). SpellAbilityView does
        // not expose either flag.
        val isTrigger = ev.si()?.isTrigger ?: false
        val isAbility = ev.si()?.isAbility ?: false
        val colorsSpentToCast =
            if (realCard?.hasConverge() == true) {
                val colorMasks =
                    if (!isAbility && !isTrigger) {
                        payments.map { it.color }
                    } else {
                        realCard.castSA
                            ?.payingMana
                            .orEmpty()
                            .map { it.color.toInt() }
                    }
                colorMasks
                    .flatMap(WubrgColorMapping::manaColorNumbersFromMagicMask)
                    .distinct()
                    .sorted()
            } else {
                emptyList()
            }
        val cardId = ForgeCardId(card.id)
        val spellAbilityId = ev.cause()?.abilityId() ?: ev.sa()?.id ?: 0
        val rootAbilityForgeId =
            topSa
                ?.rootAbility
                ?.let { it.originalAbility ?: it }
                ?.id
                ?: ev.cause()?.rootAbilityId()
                ?: 0
        val paradigmCopyStackIid = paradigmCopyStackIid(isParadigmCopyCast, spellAbilityId, ForgeCardId(card.id))
        // The SA's Forge id is needed for both triggered and activated abilities;
        // both surface through the AbilityInstance lifecycle path keyed on it.
        val abilityForgeId = if (isTrigger || isAbility) spellAbilityId else 0
        val abilityDefinition =
            when {
                !isTrigger && !isAbility -> null
                isTrigger ->
                    topSa?.trigger?.definitionId?.let { AbilityDefinitionRef.Trigger(it) }
                        ?: ev
                            .sa()
                            .sourceTriggerDefinitionId
                            .takeIf { it > 0 }
                            ?.let { AbilityDefinitionRef.Trigger(it) }
                else -> AbilityDefinitionRef.SpellAbility(topSa?.definitionId ?: ev.sa().definitionId)
            }
        val abilityIdentity =
            if (realCard != null && abilityDefinition != null) {
                abilityIdentityFor(realCard, topSa, abilityDefinition, isTrigger)
            } else {
                null
            } ?: pendingTriggerAbilityIdentity(topSa, abilityDefinition, isTrigger)
        val abilityGrpId = abilityIdentity?.abilityGrpId ?: 0
        val paradigmSourceCardId =
            realCard
                ?.effectSource
                ?.takeIf { isTrigger && abilityGrpId == KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER }
                ?.let { ForgeCardId(it.id) }
        if ((isTrigger || isAbility) && abilityIdentity == null) {
            log.warn(
                "ability identity unresolved at cast card={} runtimeId={} definition={}",
                card.name,
                abilityForgeId,
                abilityDefinition,
            )
        }
        val forgeTriggeringCard = topSa?.getTriggeringObject(AbilityKey.Card) as? Card
        val opusTrigger =
            isTrigger && topSa?.trigger?.getParam("TriggerDescription")?.startsWith("Opus —") == true
        val opusActive = opusTrigger && (forgeTriggeringCard?.castSA?.totalManaSpent ?: 0) >= 5
        val voidTrigger =
            isTrigger && topSa?.trigger?.getParam("TriggerDescription")?.startsWith("Void —") == true
        val triggeringObjectCardId =
            when {
                !isTrigger -> null
                abilityGrpId == KeywordAbilityIds.ENLIST -> enlistTriggerObjectFor(ForgeCardId(card.id), topSa)
                else -> forgeTriggeringCard?.let { ForgeCardId(it.id) }
            }
        val triggeringObjectInstanceId =
            when {
                !isTrigger -> null
                abilityGrpId == KeywordAbilityIds.ENLIST ->
                    pendingEnlistedIidsByAttacker.remove(ForgeCardId(card.id))
                        ?: triggeringObjectCardId?.let { bridge.getOrAllocInstanceId(it) }
                else -> null
            }
        if (abilityGrpId == KeywordAbilityIds.ENLIST && triggeringObjectCardId != null) {
            pendingEnlistAffectors[triggeringObjectCardId] = ForgeCardId(card.id)
        }
        if (isTrigger && abilityForgeId != 0) {
            pendingStackAbilities.recordTrigger(
                abilityForgeId,
                ForgeCardId(card.id),
                abilityIdentity,
                paradigmSourceCardId,
            )
        } else if (isAbility && abilityForgeId != 0) {
            pendingStackAbilities.recordActivation(abilityForgeId, ForgeCardId(card.id), abilityIdentity)
        }
        abilityIdentity?.let { bridge.recordStackAbilityIdentity(abilityForgeId, it) }
        // Activation zone: only meaningful for activated abilities (cycling →
        // Hand=31; unearth → Graveyard=33; …). Triggered abilities' "source
        // zone" is wherever the source card lives, computed elsewhere.
        val activationZoneId =
            when {
                isTrigger && realCard != null && isParadigmDelayedTrigger(topSa, realCard) -> ZoneIds.STACK
                isAbility && !isTrigger -> resolveActivationZoneId(topSa, card.id, seat, evSaId = ev.sa()?.id ?: 0)
                else -> 0
            }
        val castingTimeOptionState =
            readCastingTimeOptionState(
                topSa,
                ev.si()?.optionalCostString,
                bridge.consumeSelectedAdditionalCostGrpId(ForgeCardId(card.id)),
                bridge.consumeSelectedChosenCostPromptId(ForgeCardId(card.id)),
                card,
            )
        if (!isTrigger && !isAbility) {
            pendingSpellFaceGrpIds[ForgeCardId(card.id)] = grpId
        }
        frame.add(
            GameEvent.SpellCast(
                cardId = cardId,
                seatId = seat,
                spellGrpId = grpId,
                manaPayments = payments,
                colorsSpentToCast = colorsSpentToCast,
                opusTrigger = opusTrigger,
                opusActive = opusActive,
                voidTrigger = voidTrigger,
                isAdventure = isAdventure,
                isOmen = isOmen,
                isMdfc = isMdfc,
                altCostAbilityGrpId = altCostAbilityGrpId,
                castAbilityGrpId = castAbilityGrpId,
                stackInstanceId = paradigmCopyStackIid,
                sourceInstanceIdAtCast = if (isAbility) bridge.peekInstanceId(cardId) else null,
                isAbility = isAbility,
                isTrigger = isTrigger,
                abilityForgeId = abilityForgeId,
                abilityGrpId = abilityGrpId,
                abilityIdentity = abilityIdentity,
                isActivatedDiscover = isAbility && !isTrigger && topSa?.api == ApiType.Discover,
                paradigmSourceCardId = paradigmSourceCardId,
                triggeringObjectCardId = triggeringObjectCardId,
                triggeringObjectInstanceId = triggeringObjectInstanceId,
                activationZoneId = activationZoneId,
                kickerAbilityGrpId = castingTimeOptionState.kickerAbilityGrpId,
                additionalCostGrpId = castingTimeOptionState.additionalCostGrpId,
                chosenCostPromptId = castingTimeOptionState.chosenCostPromptId,
                chosenX = castingTimeOptionState.chosenX,
                rootAbilityForgeId = rootAbilityForgeId,
                stackAbilityForgeId = ev.cause()?.stackAbilityId() ?: 0,
            ),
        )
        log.debug(
            "event: SpellCast card={} seat={} manaPayments={} adventure={} omen={} " +
                "altCost={} trigger={} abilityForgeId={} kicker={} additionalCost={} chosenX={}",
            card.name,
            seat,
            payments.size,
            isAdventure,
            isOmen,
            altCostAbilityGrpId,
            isTrigger,
            abilityForgeId,
            castingTimeOptionState.kickerAbilityGrpId,
            castingTimeOptionState.additionalCostGrpId,
            castingTimeOptionState.chosenX,
        )
    }

    private fun castThroughAbilityKeywordId(
        topSa: SpellAbility?,
        saAltCost: AlternativeCost?,
    ): Int? =
        when {
            saAltCost != null -> KeywordAbilityIds.fromForgeAltCostName(saAltCost.name)
            topSa?.isJumpstart == true -> KeywordAbilityIds.JUMP_START
            topSa?.isOptionalCostPaid(OptionalCost.Retrace) == true -> KeywordAbilityIds.RETRACE
            topSa?.hasParam("PrecostDesc") == true && topSa.getParam("PrecostDesc") == "Cleave" -> KeywordAbilityIds.CLEAVE
            else -> null
        }

    private fun abilityIdentityFor(
        card: Card,
        sa: SpellAbility?,
        definition: AbilityDefinitionRef,
        isTrigger: Boolean,
    ): ResolvedAbilityIdentity? {
        if (isTrigger &&
            sa != null &&
            card.hasKeyword("Enlist") &&
            enlistTriggerObjectFor(ForgeCardId(card.id), sa, consumePending = false) != null
        ) {
            return ResolvedAbilityIdentity(definition, KeywordAbilityIds.ENLIST)
        }
        if (sa != null) {
            specialAbilityGrpIdFor(card, sa)?.let { return ResolvedAbilityIdentity(definition, it) }
            decayedAbilityGrpIdFor(card, sa)?.let { return ResolvedAbilityIdentity(definition, it) }
        }
        return if (!isTrigger && sa != null) {
            bridge.resolveAbilityIdentity(card, sa)
        } else {
            bridge.resolveAbilityIdentity(card, definition)
        }
    }

    private fun specialAbilityGrpIdFor(
        card: Card,
        sa: SpellAbility,
    ): Int? =
        when {
            isParadigmDelayedTrigger(sa, card) -> KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER
            sa.api == ApiType.Sacrifice && sa.trigger?.getParam("ValidCard") == "Card.Self+evoked" ->
                bridge.cardRepository
                    .findGrpIdByName(card.name)
                    ?.let { bridge.cardRepository.findKeywordAbilityGrpId(it, KeywordAbilityIds.EVOKE) }
            sa.isKeyword(Keyword.STATION) -> KeywordAbilityIds.STATION
            (sa.isKeyword(Keyword.TRAINING) || sa.hasParam("Training")) && sa.api == ApiType.PutCounter ->
                KeywordAbilityIds.TRAINING
            else -> null
        }

    private fun isParadigmCopyCast(sa: SpellAbility?): Boolean {
        val host = sa?.hostCard ?: return false
        return sa.isCastFromPlayEffect &&
            sa.hasParam("WithoutManaCost") &&
            host.isToken &&
            host.copiedPermanent?.hasKeyword("Paradigm") == true
    }

    private fun paradigmCopyStackIid(
        isParadigmCopyCast: Boolean,
        spellAbilityId: Int,
        cardId: ForgeCardId,
    ): Int {
        if (!isParadigmCopyCast) return 0
        val stackIid = bridge.getOrAllocInstanceId(cardId).value
        if (spellAbilityId != 0) pendingParadigmCopyStackIids[spellAbilityId] = stackIid
        return stackIid
    }

    private fun isParadigmDelayedTrigger(
        sa: SpellAbility?,
        card: Card,
    ): Boolean =
        sa?.trigger?.getParam("Execute") == "ParadigmCopy" &&
            card.effectSource?.hasKeyword("Paradigm") == true

    private fun decayedAbilityGrpIdFor(
        card: Card,
        sa: SpellAbility,
    ): Int? {
        if (!card.hasKeyword("Decayed")) return null
        if (sa.api == ApiType.DelayedTrigger && sa.trigger?.getParam("Mode") == "Attacks") {
            return KeywordAbilityIds.DECAYED
        }
        if (sa.api == ApiType.Sacrifice && sa.trigger?.getParam("Phase") == "EndCombat") {
            val grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: return null
            return bridge.cardRepository.findHiddenTriggeredAbilityGrpId(grpId)
        }
        return null
    }

    private fun enlistedCardId(sa: SpellAbility?): ForgeCardId? =
        sa
            ?.getTriggerRemembered()
            ?.filterIsInstance<Card>()
            ?.firstOrNull()
            ?.let { ForgeCardId(it.id) }

    private fun enlistTriggerObjectFor(
        attackerForgeCardId: ForgeCardId,
        sa: SpellAbility?,
        consumePending: Boolean = true,
    ): ForgeCardId? {
        val pending =
            if (consumePending) {
                pendingEnlistedByAttacker.remove(
                    attackerForgeCardId,
                )
            } else {
                pendingEnlistedByAttacker[attackerForgeCardId]
            }
        val peek = peekEnlistedByAttacker(attackerForgeCardId)
        val remembered = enlistedCardId(sa)?.takeIf { it != attackerForgeCardId }
        return pending ?: peek ?: remembered
    }

    /**
     * Resolve the activation zone of an activated ability to a protocol ZoneId.
     *
     * Strategy (first hit wins):
     *   1. **Frame correlation** — if a `CardDiscarded` or `ZoneChanged` event
     *      for this card already fired in the current frame (cost-payment side
     *      of the activation), its `from` zone is the activation zone. Most
     *      reliable for cycling/channel/unearth — the discard or graveyard-exit
     *      event always fires before `SpellAbilityCast`.
     *   2. **Stack peek (`topSa`)** — populated for normal activate flows where
     *      the AB still lives on the stack at event time.
     *   3. **Live SA on the host card matched by id** — recovers the restriction
     *      when stack.peek() has already cleared (compressed cycling resolution).
     *   4. **Host card's `lastKnownZone`** — covers SAs without an explicit
     *      `ActivationZone$` (battlefield-implicit activated abilities).
     *
     * Returns 0 when nothing pins down the zone.
     */
    private fun resolveActivationZoneId(
        topSa: forge.game.spellability.SpellAbility?,
        cardId: Int,
        seat: SeatId,
        evSaId: Int,
    ): Int {
        // 1. Frame correlation — read this card's pre-cost zone from sibling
        //    events. CardDiscarded carries the pre-discard zone (Hand →
        //    Graveyard); ZoneChanged carries the same. For cycling/channel
        //    the discard fires before SpellAbilityCast in Forge's event order.
        val target = ForgeCardId(cardId)
        for (ev in frame.asReversed()) {
            when (ev) {
                is GameEvent.CardDiscarded -> if (ev.cardId == target) return ZoneIds.handOf(seat)
                is GameEvent.ZoneChanged ->
                    if (ev.cardId == target) {
                        return zoneToProtocolId(ev.from, seat)
                    }
                else -> {}
            }
        }
        // 2-3. SA-driven resolution.
        val candidate =
            if (topSa != null && topSa.hostCard?.id == cardId) {
                topSa
            } else {
                findLiveSaOnCard(cardId, evSaId)
            }
        if (candidate != null) {
            val restrictionZone = candidate.restrictions?.zone
            if (restrictionZone != null) return zoneTypeToProtocolId(restrictionZone, seat)
            val hostZone = candidate.hostCard?.lastKnownZone?.zoneType
            if (hostZone != null) return zoneTypeToProtocolId(hostZone, seat)
        }
        // 4. Last-resort: live host card lookup.
        val hostZone = bridge.findCard(target)?.lastKnownZone?.zoneType
        return if (hostZone != null) zoneTypeToProtocolId(hostZone, seat) else 0
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun zoneToProtocolId(
        zone: Zone,
        seat: SeatId,
    ): Int =
        when (zone) {
            Zone.Hand -> ZoneIds.handOf(seat)
            Zone.Graveyard -> ZoneIds.graveyardOf(seat)
            Zone.Battlefield -> ZoneIds.BATTLEFIELD
            Zone.Exile -> ZoneIds.EXILE
            Zone.Command -> ZoneIds.COMMAND
            Zone.Stack -> ZoneIds.STACK
            Zone.Library -> ZoneIds.libraryOf(seat)
            Zone.Sideboard -> ZoneIds.sideboardOf(seat)
            // Other / unmapped — not a wire-level zone we surface.
            else -> 0
        }

    private fun findLiveSaOnCard(
        cardId: Int,
        evSaId: Int,
    ): forge.game.spellability.SpellAbility? {
        if (evSaId == 0) return null
        val card = bridge.findCard(ForgeCardId(cardId)) ?: return null
        return card.spellAbilities.firstOrNull { it.id == evSaId }
            ?: card.allSpellAbilities?.firstOrNull { it.id == evSaId }
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun zoneTypeToProtocolId(
        zone: ZoneType,
        seat: SeatId,
    ): Int =
        when (zone) {
            ZoneType.Hand -> ZoneIds.handOf(seat)
            ZoneType.Graveyard -> ZoneIds.graveyardOf(seat)
            ZoneType.Battlefield -> ZoneIds.BATTLEFIELD
            ZoneType.Exile -> ZoneIds.EXILE
            ZoneType.Command -> ZoneIds.COMMAND
            ZoneType.Stack -> ZoneIds.STACK
            ZoneType.Library -> ZoneIds.libraryOf(seat)
            ZoneType.Sideboard -> ZoneIds.sideboardOf(seat)
            // Subgame / ExtraHand / None — not zones the AbilityInstance source_zone surfaces.
            else -> 0
        }

    private data class CastingTimeOptionState(
        val kickerAbilityGrpId: Int = 0,
        val additionalCostGrpId: Int = 0,
        val chosenCostPromptId: Int = 0,
        val chosenX: Int = 0,
    )

    /** CastingTimeOption state read from the live SA on top of the stack. */
    private fun readCastingTimeOptionState(
        topSa: forge.game.spellability.SpellAbility?,
        stackOptionalCosts: String?,
        selectedAdditionalCostGrpId: Int?,
        selectedChosenCostPromptId: Int?,
        card: forge.game.card.CardView,
    ): CastingTimeOptionState {
        val sourceSa = topSa?.takeIf { it.hostCard?.id == card.id }
        val grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: 0
        val kicker =
            if (sourceSa?.isKicked == true) {
                if (grpId != 0) {
                    bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.KICKER) ?: 0
                } else {
                    0
                }
            } else {
                0
            }
        val paidGenericCost =
            sourceSa?.isOptionalCostPaid(OptionalCost.Generic) == true ||
                stackOptionalCosts.orEmpty().split(',').any { it.trim() == "Additional" }
        val additionalCost =
            when {
                grpId == 0 -> 0
                selectedAdditionalCostGrpId != null -> selectedAdditionalCostGrpId
                sourceSa?.isOptionalCostPaid(OptionalCost.Teamwork) == true ->
                    bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.TEAMWORK) ?: 0
                paidGenericCost ->
                    bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.WATERBEND)
                        ?: bridge.cardRepository.findKeywordAbilityGrpId(grpId, KeywordAbilityIds.TEAMWORK)
                        ?: 0
                else -> 0
            }
        val x = sourceSa?.xManaCostPaid ?: 0
        return CastingTimeOptionState(
            kickerAbilityGrpId = kicker,
            additionalCostGrpId = additionalCost,
            chosenCostPromptId = selectedChosenCostPromptId ?: 0,
            chosenX = x,
        )
    }

    override fun visit(ev: GameEventSpellMovedToStack) {
        val card = ev.card()
        val seat = seatOf(card.controller) ?: return
        frame.add(GameEvent.SpellMovedToStack(ForgeCardId(card.id), seat))
        log.debug("event: SpellMovedToStack card={} seat={}", card.name, seat)
    }

    override fun visit(ev: GameEventSpellResolved) {
        val spell = ev.spell()
        val card = spell.hostCard ?: return
        val saId = ev.cause()?.abilityId() ?: spell.id
        val context = pendingStackAbilities.consume(saId)
        val isTrigger = context?.kind == PendingStackAbilityKind.Trigger
        val isAbility = context?.kind == PendingStackAbilityKind.Activation
        val bridgedIdentity = bridge.consumeStackAbilityIdentity(saId)
        val abilityIdentity = context?.identity ?: bridgedIdentity
        val abilityGrpId = abilityIdentity?.abilityGrpId ?: 0
        val cardId = ForgeCardId(card.id)
        val paradigmSourceCardId = context?.paradigmSourceCardId
        val spellGrpId = pendingSpellFaceGrpIds.remove(cardId) ?: bridge.pendingSpellCast(cardId)?.spellGrpId ?: 0
        val paradigmCopyStackIid = pendingParadigmCopyStackIids.remove(saId) ?: 0
        recordEarthbendResolution(card, saId, isTrigger || isAbility, abilityGrpId, ev.hasFizzled())
        frame.add(
            GameEvent.SpellResolved(
                cardId = cardId,
                hasFizzled = ev.hasFizzled(),
                spellGrpId = spellGrpId,
                isTrigger = isTrigger,
                isAbility = isAbility,
                abilityForgeId = if (isTrigger || isAbility) saId else 0,
                abilityGrpId = if (isTrigger || isAbility) abilityGrpId else 0,
                abilityIdentity = if (isTrigger || isAbility) abilityIdentity else null,
                paradigmSourceCardId = paradigmSourceCardId,
                isParadigmCopy = !isTrigger && !isAbility && paradigmCopyStackIid != 0,
                stackInstanceId = paradigmCopyStackIid,
                rootAbilityForgeId = ev.cause()?.rootAbilityId() ?: 0,
                stackAbilityForgeId = ev.cause()?.stackAbilityId() ?: 0,
            ),
        )
        log.debug(
            "event: SpellResolved card={} fizzled={} trigger={} ability={} abilityForgeId={}",
            card.name,
            ev.hasFizzled(),
            isTrigger,
            isAbility,
            if (isTrigger || isAbility) saId else 0,
        )
    }

    private fun recordEarthbendResolution(
        card: CardView,
        runtimeAbilityId: Int,
        isStackAbility: Boolean,
        abilityGrpId: Int,
        hasFizzled: Boolean,
    ) {
        if (hasFizzled) return
        val liveCard = bridge.findCard(ForgeCardId(card.id))
        val liveSa = findLiveSaOnCard(card.id, runtimeAbilityId)
        if (liveSa?.api != ApiType.Earthbend || liveCard == null) return
        val targetIds =
            liveSa.targets
                ?.targetCards
                ?.map { ForgeCardId(it.id) }
                .orEmpty()
        val earthbendAbilityGrpId =
            abilityGrpId.takeIf { it != 0 }
                ?: abilityIdentityFor(
                    liveCard,
                    liveSa,
                    AbilityDefinitionRef.SpellAbility(liveSa.definitionId),
                    isTrigger = false,
                )?.abilityGrpId
                ?: bridge.cardRepository.findGrpIdByName(card.name)
                ?: 0
        bridge.recordEarthbendResolution(
            sourceCardId = ForgeCardId(card.id),
            sourceAbilityGrpId = earthbendAbilityGrpId,
            abilityForgeId = runtimeAbilityId.takeIf { isStackAbility } ?: 0,
            targetCardIds = targetIds,
        )
    }

    @Suppress("CyclomaticComplexMethod") // zone-change routing inherently branchy
    override fun visit(ev: GameEventCardChangeZone) {
        val card = ev.card()
        val from = ev.from()?.zoneType
        val to = ev.to()?.zoneType
        zoneMoves.add(
            ZoneMove(
                order = zoneMoves.size,
                cardId = ForgeCardId(card.id),
                from = from?.let(Zone::fromForge) ?: Zone.Other,
                to = to?.let(Zone::fromForge) ?: Zone.Other,
                cause =
                    ev.cause()?.let { cause ->
                        ZoneMoveCause(
                            sourceCardId = cause.sourceCardId().takeIf { it != 0 }?.let(::ForgeCardId),
                            abilityForgeId = cause.abilityId(),
                            rootAbilityForgeId = cause.rootAbilityId(),
                            api = cause.api()?.name,
                            costPayment = cause.costPayment(),
                            stackAbilityForgeId = cause.stackAbilityId(),
                        )
                    },
            ),
        )
        if (to == null) return
        val seat = seatOf(card.controller)
        val exileUnderSource = consumeExileUnderSource(card.id)
        val cause = ev.cause()
        if (
            openingHandActionWindow &&
            seat != null &&
            from == ZoneType.Hand &&
            to == ZoneType.Battlefield
        ) {
            bridge.openingHandAbilityGrpId(card.name)?.let { abilityGrpId ->
                frame.add(
                    GameEvent.OpeningHandAction(
                        ForgeCardId(card.id),
                        seat,
                        cause?.abilityId()?.takeIf { it != 0 } ?: card.id,
                        abilityGrpId,
                    ),
                )
            }
        }

        // Emit the most specific variant possible based on zone pair.
        // When seat is unavailable or source zone is null (e.g. token entering
        // Command zone from nowhere), fall back to generic ZoneChanged.
        val event =
            if (seat != null && from != null) {
                when {
                    from == ZoneType.Battlefield && to == ZoneType.Graveyard && isLegendRuleVictim(card.id) ->
                        GameEvent.LegendRuleDeath(ForgeCardId(card.id), seat)
                    // BF→GY without legend rule: fall through to ZoneChanged.
                    // CardDestroyed is emitted from GameEventCardDestroyed (with activator).
                    from == ZoneType.Battlefield && (to == ZoneType.Hand || to == ZoneType.Library) ->
                        GameEvent.CardBounced(ForgeCardId(card.id), seat)
                    // Hand→Exile via the discard pipeline (Madness, Mayhem — keyword
                    // replacement effects exile-on-discard). The card has the keyword
                    // and the move originates from Hand, so still treat it as Discard
                    // rather than a generic exile.
                    from == ZoneType.Hand && to == ZoneType.Exile && hasDiscardReplacementKeyword(card) ->
                        GameEvent.CardDiscarded(ForgeCardId(card.id), seat)
                    to == ZoneType.Exile -> {
                        // A spell exiled by its own resolution (Flashback / Harmonize
                        // "then exile it") reports itself as the exile source: Forge sets
                        // exiledWith to the ChangeZone host, which is the spell card. That
                        // is not an under-card display relationship — a card cannot be
                        // shown tucked under itself — so drop the self-reference. A
                        // self-exiling spell is not displayed under any card.
                        val sourceId =
                            (exileUnderSource?.value ?: exileUnderSourceId(card))
                                ?.takeIf { it != card.id }
                        GameEvent.CardExiled(
                            ForgeCardId(card.id),
                            seat,
                            sourceId?.let { ForgeCardId(it) },
                            fromBattlefield = from == ZoneType.Battlefield,
                        )
                    }
                    from == ZoneType.Hand && to == ZoneType.Graveyard ->
                        GameEvent.CardDiscarded(ForgeCardId(card.id), seat)
                    from == ZoneType.Library && to == ZoneType.Graveyard -> {
                        val sourceId = ev.cause()?.sourceCardId()?.takeIf { it != 0 }
                        GameEvent.CardMilled(ForgeCardId(card.id), seat, sourceId?.let { ForgeCardId(it) })
                    }
                    else -> GameEvent.ZoneChanged(ForgeCardId(card.id), Zone.fromForge(from), Zone.fromForge(to))
                }
            } else {
                GameEvent.ZoneChanged(ForgeCardId(card.id), from?.let { Zone.fromForge(it) } ?: Zone.Other, Zone.fromForge(to))
            }

        frame.add(event)
        log.debug("event: {} card={} {} → {}", event::class.simpleName, card.name, from, to)

        // Emit TokenDestroyed when a token leaves the battlefield
        if (card.isToken && from == ZoneType.Battlefield && seat != null) {
            frame.add(GameEvent.TokenDestroyed(ForgeCardId(card.id), seat))
            log.debug("event: TokenDestroyed card={} seat={}", card.name, seat)
        }
    }

    override fun visit(ev: GameEventCardTapped) {
        val cardId = ForgeCardId(ev.card().id)
        val enlistAttacker = consumeEnlistTapAffector(cardId) ?: pendingEnlistAffectors.remove(cardId)
        val cause = ev.cause()?.takeIf { enlistAttacker == null }
        if (ev.tapped() && enlistAttacker != null) {
            pendingEnlistedByAttacker[enlistAttacker] = cardId
            pendingEnlistedIidsByAttacker[enlistAttacker] = bridge.getOrAllocInstanceId(cardId)
        }
        frame.add(
            GameEvent.CardTapped(
                cardId,
                ev.tapped(),
                affectorCardId = enlistAttacker,
                affectorAbilityForgeId = tapAbilityForgeId(ev, cause),
                affectorSpellCardId =
                    cause
                        ?.hostCard
                        ?.id
                        ?.takeIf { ev.spellCause() && it != 0 }
                        ?.let(::ForgeCardId),
            ),
        )
        log.debug("event: CardTapped card={} tapped={}", ev.card().name, ev.tapped())
    }

    private fun tapAbilityForgeId(
        ev: GameEventCardTapped,
        cause: forge.game.spellability.SpellAbilityView?,
    ): Int {
        if (cause == null || ev.spellCause()) return 0
        val rootAbilityId = ev.rootAbilityId()
        val stackAbility =
            bridge
                .getGame()
                ?.stack
                ?.peek()
                ?.spellAbility
        return if (stackAbility is WrappedAbility && stackAbility.wrappedAbility.rootAbility.id == rootAbilityId) {
            stackAbility.id
        } else {
            rootAbilityId
        }
    }

    override fun visit(ev: GameEventManaAbilityActivated) {
        val card = ev.source()
        val seat = seatOf(card.controller) ?: return
        frame.add(GameEvent.ManaAbilityActivated(ForgeCardId(card.id), seat, ev.produced()))
        log.debug("event: ManaAbilityActivated card={} seat={} produced={}", card.name, seat, ev.produced())
    }

    override fun visit(ev: GameEventCardDamaged) {
        frame.add(
            GameEvent.DamageDealtToCard(
                sourceCardId = ForgeCardId(ev.source().id),
                targetCardId = ForgeCardId(ev.card().id),
                amount = ev.amount(),
                deathtouch = ev.type() == GameEventCardDamaged.DamageType.Deathtouch,
                sourceKind = ev.sourceKind().toLeyline(),
            ),
        )
    }

    override fun visit(ev: GameEventPlayerDamaged) {
        val seat = seatOf(ev.target()) ?: return
        val source = ev.source() ?: return
        frame.add(
            GameEvent.DamageDealtToPlayer(
                sourceCardId = ForgeCardId(source.id),
                targetSeatId = seat,
                amount = ev.amount(),
                sourceKind = ev.sourceKind().toLeyline(),
                changesLife = !ev.infect(),
            ),
        )
    }

    override fun visit(ev: GameEventPlayerLivesChanged) {
        val seat = seatOf(ev.player()) ?: return
        frame.add(
            GameEvent.LifeChanged(
                seatId = seat,
                oldLife = ev.oldLives(),
                newLife = ev.newLives(),
            ),
        )
    }

    private fun ForgeDamageSourceKind.toLeyline(): DamageSourceKind =
        when (this) {
            ForgeDamageSourceKind.Combat -> DamageSourceKind.Combat
            ForgeDamageSourceKind.SpellOrAbility -> DamageSourceKind.SpellOrAbility
            ForgeDamageSourceKind.Fight -> DamageSourceKind.Fight
        }

    override fun visit(ev: GameEventFlipCoin) {
        val flipperView = ev.player() ?: return
        val sa = ev.sa() ?: return
        val won = ev.won()
        val flipper = seatOf(flipperView) ?: return
        val card = sa.hostCard ?: return
        val abilityContext = pendingStackAbilities.contextFor(sa.id)
        val abilityForgeId = sa.id.takeIf { abilityContext != null } ?: 0
        frame.add(
            GameEvent.CoinFlipped(
                flipperSeatId = flipper,
                sourceCardId = ForgeCardId(card.id),
                abilityForgeId = abilityForgeId,
                abilityGrpId = abilityContext?.abilityGrpId ?: 0,
                result = if (won) 1 else 0,
            ),
        )
        log.debug(
            "event: CoinFlipped card={} flipper={} abilityForgeId={} result={}",
            card.name,
            flipper,
            abilityForgeId,
            if (won) 1 else 0,
        )
    }

    override fun visit(ev: GameEventAttackersDeclared) {
        val seat = seatOf(ev.player()) ?: return
        val ids = ev.attackersMap().values().map { ForgeCardId(it.id) }
        if (ids.isNotEmpty()) {
            frame.add(GameEvent.AttackersDeclared(ids, seat))
        }
    }

    override fun visit(ev: GameEventBlockersDeclared) {
        val seat = seatOf(ev.defendingPlayer()) ?: return
        // Flatten all blocking creatures from the nested map
        val ids =
            ev.blockers().values.flatMap { multimap ->
                multimap.keys().map { ForgeCardId(it.id) }
            }
        if (ids.isNotEmpty()) {
            frame.add(GameEvent.BlockersDeclared(ids, seat))
        }
    }

    // -- Group A: zone-transition disambiguation --

    override fun visit(ev: GameEventCardSacrificed) {
        val card = ev.card()
        val seat = seatOf(card.controller) ?: return
        val cause = ev.cause()
        frame.add(
            GameEvent.CardSacrificed(
                cardId = ForgeCardId(card.id),
                seatId = seat,
                sourceCardId = cause?.sourceCardId()?.takeIf { it != 0 }?.let(::ForgeCardId),
                sourceAbilityForgeId = cause?.abilityId() ?: 0,
                costPayment = cause?.costPayment() ?: false,
            ),
        )
        log.debug("event: CardSacrificed card={} seat={}", card.name, seat)
    }

    override fun visit(ev: GameEventCardDestroyed) {
        val card = ev.card() ?: return
        val seat = seatOf(card.controller) ?: return
        val cause = ev.cause()
        val sourceId = cause?.sourceCardId()?.takeIf { it != 0 } ?: ev.activator()?.id
        frame.add(
            GameEvent.CardDestroyed(
                cardId = ForgeCardId(card.id),
                seatId = seat,
                sourceCardId = sourceId?.let(::ForgeCardId),
                sourceAbilityForgeId = cause?.abilityId() ?: 0,
                destruction = destructionCause(ev, ForgeCardId(card.id)),
            ),
        )
        log.debug("event: CardDestroyed card={} seat={} source={}", card.name, seat, sourceId?.let { ForgeCardId(it) })
    }

    /**
     * A destroy with no causing ability and no activator is the lethal-damage /
     * deathtouch state-based action — but only when damage evidence exists.
     * The state-effects pass consumes the card's deathtouch flag before the
     * destroy event fires, so the same-frame damage events are the signal:
     * damage lands and the SBA destroy fires inside one event frame. A
     * deathtouch destroy split across frames would downgrade to LethalDamage —
     * the marked-damage fallback has no cross-frame deathtouch counterpart.
     */
    private fun destructionCause(
        ev: GameEventCardDestroyed,
        cardId: ForgeCardId,
    ): DestructionCause {
        if (ev.cause() != null || ev.activator() != null) return DestructionCause.Effect
        val frameDamage =
            frame.filterIsInstance<GameEvent.DamageDealtToCard>().filter { it.targetCardId == cardId }
        return when {
            frameDamage.any { it.deathtouch } -> DestructionCause.Deathtouch
            frameDamage.isNotEmpty() || (bridge.findCard(cardId)?.damage ?: 0) > 0 ->
                DestructionCause.LethalDamage
            else -> DestructionCause.Effect
        }
    }

    // -- Group A+: attachment events --

    override fun visit(ev: GameEventCardAttachment) {
        val card = ev.equipment()
        val seat = seatOf(card.controller) ?: return
        val newTarget = ev.newTarget()
        if (newTarget != null) {
            frame.add(GameEvent.CardAttached(ForgeCardId(card.id), ForgeCardId(newTarget.id), seat))
            log.debug("event: CardAttached card={} target={} seat={}", card.name, newTarget.name, seat)
        } else {
            val oldTargetId = (ev.oldEntity() as? CardView)?.id?.let(::ForgeCardId)
            val invalidatingGrpId =
                if (isResolvingReconfigureUnattach(card.id)) {
                    KeywordAbilityIds.RECONFIGURE_UNATTACH
                } else {
                    0
                }
            frame.add(GameEvent.CardDetached(ForgeCardId(card.id), seat, oldTargetId, invalidatingGrpId))
            log.debug("event: CardDetached card={} seat={}", card.name, seat)
        }
    }

    private fun isResolvingReconfigureUnattach(cardId: Int): Boolean {
        val sa =
            bridge
                .getGame()
                ?.stack
                ?.peek()
                ?.spellAbility ?: return false
        return sa.hostCard?.id == cardId && sa.api == ApiType.Unattach && sa.getParam("PrecostDesc") == "Reconfigure"
    }

    private fun exileUnderSourceId(card: CardView): Int? {
        card.exiledWith?.id?.let { return it }
        val sa =
            bridge
                .getGame()
                ?.stack
                ?.peek()
                ?.spellAbility ?: return null
        if (sa.api != ApiType.ChangeZone || sa.getParam("Destination") != "Exile") return null
        if (sa.getParam("Duration") != "UntilHostLeavesPlay" && !sa.hasParam("IsCurse")) return null
        return sa.hostCard?.id
    }

    // -- Group B: annotation-producing events --

    override fun visit(ev: GameEventCardCounters) {
        val cardId = ForgeCardId(ev.card().id)
        val affectorAbilityForgeId = resolvingCounterTriggerAbilityIdFor(cardId) ?: 0
        frame.add(
            GameEvent.CountersChanged(
                cardId = cardId,
                counterType = ev.type().name,
                oldCount = ev.oldValue(),
                newCount = ev.newValue(),
                affectorAbilityForgeId = affectorAbilityForgeId,
                affectorCardId = cardId.takeIf { affectorAbilityForgeId != 0 },
            ),
        )
        log.debug("event: CountersChanged card={} {} {}→{}", ev.card().name, ev.type(), ev.oldValue(), ev.newValue())
    }

    private fun resolvingCounterTriggerAbilityIdFor(cardId: ForgeCardId): Int? {
        val game = bridge.getGame() ?: return null
        val card = bridge.findCard(cardId) ?: return null
        if (!game.stack.isResolving(card)) return null
        val ability = game.stack.peek()?.spellAbility ?: return null
        val context = pendingStackAbilities.contextFor(ability.id) ?: return null
        if (context.kind != PendingStackAbilityKind.Trigger || context.sourceCardId != cardId) return null
        val triggerDescription = ability.trigger?.getParam("TriggerDescription").orEmpty()
        return ability.id.takeIf {
            context.abilityGrpId == KeywordAbilityIds.TRAINING ||
                triggerDescription.startsWith("Opus —") ||
                triggerDescription.startsWith("Void —")
        }
    }

    private fun pendingTriggerAbilityIdentity(
        ability: SpellAbility?,
        definition: AbilityDefinitionRef?,
        isTrigger: Boolean,
    ): ResolvedAbilityIdentity? {
        if (!isTrigger || ability == null || definition == null) return null
        val triggerId = ability.trigger?.id ?: return null
        val abilityGrpId = bridge.pendingTriggerCleanupAbilityGrpId(triggerId) ?: return null
        return ResolvedAbilityIdentity(definition, abilityGrpId)
    }

    override fun visit(ev: GameEventPlayerPoisoned) {
        val seat = seatOf(ev.receiver()) ?: return
        val newValue = ev.oldValue() + ev.amount()
        frame.add(
            GameEvent.PlayerCountersChanged(
                seatId = seat,
                counterType = "POISON",
                oldCount = ev.oldValue(),
                newCount = newValue,
            ),
        )
        log.debug("event: PlayerCountersChanged seat={} POISON {}→{}", seat, ev.oldValue(), newValue)
    }

    override fun visit(ev: GameEventShuffle) {
        val seat = seatOf(ev.player()) ?: return
        val affectorCardId =
            ev
                .source()
                ?.hostCard
                ?.id
                ?.let(::ForgeCardId)
        frame.add(GameEvent.LibraryShuffled(seat, affectorCardId = affectorCardId))
        log.debug("event: LibraryShuffled seat={}", seat)
    }

    override fun visit(ev: GameEventScry) {
        val seat = seatOf(ev.player()) ?: return
        val arranged =
            bridge.cutCoordinator
                .promptRuntimes(seat)
                .grouping
                .pollArrangement(seat, GroupingContext.Scry_a0f6)
        frame.add(GameEvent.Scry(seat, arranged?.topIds.orEmpty(), arranged?.awayIds.orEmpty()))
        log.debug("event: Scry seat={} top={} bottom={}", seat, arranged?.topIds.orEmpty(), arranged?.awayIds.orEmpty())
    }

    override fun visit(ev: GameEventSurveil) {
        val seat = seatOf(ev.player()) ?: return
        val arranged =
            bridge.cutCoordinator
                .promptRuntimes(seat)
                .grouping
                .pollArrangement(seat, GroupingContext.Surveil)
        frame.add(GameEvent.Surveil(seat, arranged?.topIds.orEmpty(), arranged?.awayIds.orEmpty()))
        log.debug("event: Surveil seat={} lib={} gy={}", seat, arranged?.topIds.orEmpty(), arranged?.awayIds.orEmpty())
    }

    // Per-card surveil event — fired from Player.surveil() in our Forge fork
    // for each card moved to graveyard. Lets ZoneMoveLedger distinguish
    // surveil (Library→GY) from mill (Library→GY).
    override fun visit(ev: GameEventCardSurveiled) {
        val seat = seatOf(ev.card().controller) ?: return
        val sourceId = ev.causeCard()?.id
        frame.add(GameEvent.CardSurveiled(ForgeCardId(ev.card().id), seat, sourceId?.let { ForgeCardId(it) }))
        log.debug("event: CardSurveiled card={} seat={} source={}", ev.card().name, seat, sourceId?.let { ForgeCardId(it) })
    }

    override fun visit(ev: GameEventTokenCreated) {
        for (card in ev.tokens()) {
            val seat = seatOf(card.controller) ?: continue
            val spawningAbility = card.tokenSpawningAbility?.rootAbility
            val resolvingAbility =
                bridge
                    .getGame()
                    ?.stack
                    ?.peek()
                    ?.spellAbility
                    ?.takeIf { pendingStackAbilities.contextFor(it.id) != null }
            val sourceAbility =
                resolvingAbility
                    ?.takeIf { spawningAbility == null || it.hostCard?.id == spawningAbility.hostCard?.id }
                    ?: spawningAbility
            val sourceId = sourceAbility?.hostCard?.id
            val sourceAbilityId =
                sourceAbility
                    ?.takeIf { it.isAbility && !it.isSpell }
                    ?.id
                    ?: 0
            frame.add(
                GameEvent.TokenCreated(
                    ForgeCardId(card.id),
                    seat,
                    sourceId?.let { ForgeCardId(it) },
                    sourceAbilityId,
                ),
            )
            log.debug(
                "event: TokenCreated card={} seat={} source={} sourceAbilityId={}",
                card.name,
                seat,
                sourceId?.let { ForgeCardId(it) },
                sourceAbilityId,
            )
        }
    }

    override fun visit(ev: GameEventControllerChanged) {
        val card = ev.card()
        val oldSeat = seatOf(ev.oldController()) ?: return
        val newSeat = seatOf(ev.newController()) ?: return
        frame.add(GameEvent.ControllerChanged(ForgeCardId(card.id), oldSeat, newSeat))
        log.debug("event: ControllerChanged card={} {} -> {}", card.name, oldSeat, newSeat)
    }

    // -- Group B++: keyword grant events --

    override fun visit(ev: GameEventExtrinsicKeywordAdded) {
        val card = ev.card()
        frame.add(
            GameEvent.KeywordGranted(
                cardId = ForgeCardId(card.id),
                keyword = ev.keyword(),
                timestamp = ev.timestamp(),
                staticId = ev.staticId(),
            ),
        )
        log.debug("event: KeywordGranted card={} keyword={} ts={} static={}", card.name, ev.keyword(), ev.timestamp(), ev.staticId())
    }

    // -- Group C: combat enrichment --

    override fun visit(ev: GameEventCombatEnded) {
        frame.add(GameEvent.CombatEnded)
        log.debug("event: CombatEnded")
    }

    // -- Group D: phase/turn events --

    override fun visit(ev: GameEventTurnPhase) {
        val seat = seatOf(ev.playerTurn()) ?: return
        val phase = PlayerMapper.mapPhase(ev.phase()).number
        val step = PlayerMapper.mapStep(ev.phase()).number
        frame.add(GameEvent.PhaseChanged(seat, phase, step))
        log.debug("event: PhaseChanged seat={} phase={} step={}", seat, phase, step)
    }

    // -- helpers --

    private fun seatOf(player: Player?): SeatId? {
        if (player == null) return null
        return bridge.seatOf(player)
    }

    private fun seatOf(player: PlayerView?): SeatId? {
        if (player == null) return null
        return bridge.seatOf(player)
    }

    private fun consumeExileUnderSource(forgeCardId: Int): ForgeCardId? {
        val id = ForgeCardId(forgeCardId)
        for (seat in bridge.allSeatIds()) {
            bridge
                .promptBridge(SeatId(seat))
                .journal
                .consumeExiledUnderSource(id)
                ?.let { return it }
        }
        return null
    }

    /**
     * Check if a card is marked as a legend rule SBA victim.
     *
     * [TargetingCoordinator.recordLegendVictim] records [PromptSideEffect.LegendVictim]
     * events into the [PromptJournal] of the active prompt bridge. We drain all seats'
     * journals via [PromptJournal.consumeLegendVictim] so the entry doesn't leak to future SBAs.
     */
    private fun isLegendRuleVictim(forgeCardId: Int): Boolean {
        val id = ForgeCardId(forgeCardId)
        for (seat in bridge.allSeatIds()) {
            if (bridge.promptBridge(SeatId(seat)).journal.consumeLegendVictim(id)) return true
        }
        return false
    }

    private fun consumeEnlistTapAffector(forgeCardId: ForgeCardId): ForgeCardId? {
        for (seat in bridge.allSeatIds()) {
            bridge
                .promptBridge(SeatId(seat))
                .journal
                .consumeEnlistTapAffector(forgeCardId)
                ?.let { return it }
        }
        return null
    }

    private fun peekEnlistedByAttacker(attackerForgeCardId: ForgeCardId): ForgeCardId? {
        for (seat in bridge.allSeatIds()) {
            bridge
                .promptBridge(SeatId(seat))
                .journal
                .peekEnlistedByAttacker(attackerForgeCardId)
                ?.let { return it }
        }
        return null
    }

    /**
     * Compute client color ordinals from a land's mana abilities.
     * Each mana ability contributes one client ordinal per color it produces.
     * Basic lands → single entry (e.g. [2] for Island).
     * Dual/multi-lands → multiple entries (e.g. [3, 5] for Jungle Hollow).
     * Uses [AbilityManaPart.mana] which resolves Combo/Chosen/ColorID keywords,
     * then maps each color through [ManaColorMapping.fromProduced] → ManaColor
     * proto ordinal (W=1, U=2, B=3, R=4, G=5).
     */
    private fun computeColorOrdinals(card: Card): List<Int> =
        card
            .getManaAbilities()
            .flatMap { sa ->
                val mana = sa.manaPart ?: return@flatMap emptyList()
                val produced = if (mana.isComboMana) mana.getComboColors(sa) else mana.origProduced
                produced.split(" ").mapNotNull { token ->
                    ManaColorMapping.fromProduced(token)?.number
                }
            }

    /** True if the card has a discard-replacement keyword (Madness, Mayhem) — these
     *  redirect Hand→GY discards to Hand→Exile but client still tags them as Discard.
     *  Reads directly from the live Forge [Card] via [CardView.id] so we don't
     *  depend on card-DB keyword tables (the Arena DB does not expose a
     *  keyword-name map, and BaseIds for Madness/Mayhem are not
     *  yet populated — see TODO in KEYWORD_BASE_IDS). */
    private fun hasDiscardReplacementKeyword(cardView: CardView): Boolean {
        val forgeCard = bridge.findCard(ForgeCardId(cardView.id)) ?: return false
        val keywords = forgeCard.rules?.mainPart?.keywords ?: return false
        return keywords.any { kw ->
            val u = kw.uppercase()
            u.startsWith("MADNESS") || u.startsWith("MAYHEM")
        }
    }
}
