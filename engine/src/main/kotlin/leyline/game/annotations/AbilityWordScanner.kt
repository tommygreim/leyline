package leyline.game.annotations

import forge.game.ability.AbilityUtils
import forge.game.card.Card
import forge.game.card.CardUtil
import forge.game.card.CounterEnumType
import forge.game.keyword.Keyword
import forge.game.player.Player
import forge.game.trigger.TriggerType
import forge.game.zone.ZoneType
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.game.state.AbilityRegistry
import kotlin.collections.iterator

/**
 * Scans battlefield permanents for ability word conditions and produces
 * [AbilityWordEntry] records for persistent annotation emission.
 *
 * Detection: checks Forge StaticAbility `Condition$` param and Trigger/SpellAbility
 * named params (`Threshold$`, `Morbid$`, etc.) from CardTraitBase.meetsCommonRequirements.
 *
 * Value computation: delegates to Player predicates (same as Forge's own condition checks).
 * AbilityGrpId resolution: delegates to AbilityRegistry.
 */
object AbilityWordScanner {
    private val STATION_THRESHOLD = Regex("counters_GE(\\d+)_CHARGE")

    /** One ability word annotation to emit. */
    data class AbilityWordEntry(
        val instanceId: Int,
        val abilityWordName: String,
        val value: Int? = null,
        val threshold: Int? = null,
        val abilityGrpId: Int? = null,
        /** Actual W/U/B/R/G mana-color ordinals spent for cast-payment state. */
        val colors: List<Int>? = null,
        val hasBlessing: Boolean = false,
        /** Card-backed entry whose iid must follow this frame's zone reallocation. */
        val forgeCardId: ForgeCardId? = null,
        /** Override affectorId (default = instanceId). */
        val affectorId: Int? = null,
        /** Per-player entries: all permanent iids with this ability word for that player. */
        val affectedIds: List<Int> = emptyList(),
        val affectedForgeCardIds: List<ForgeCardId> = emptyList(),
    )

    private data class ConditionSpec(
        val threshold: Int? = null,
        /** Value lambda. Second param is any battlefield card (Forge src arg). */
        val value: ((Player, Card) -> Int)? = null,
        /** When true: emit one pAnn per player (affectorId=seatId), not per permanent. */
        val perPlayer: Boolean = false,
        /** When true: suppress value/threshold from pAnn details (boolean-only wire shape). */
        val booleanOnly: Boolean = false,
    )

    /**
     * Maps Forge `Condition$` name → emission shape for the ability-word badge. Key
     * doubles as the `AbilityWordName` detail value on the emitted annotation.
     *
     * VERIFIED entries are exercised by a puzzle fixture under `data/puzzles/` plus a
     * `BoardTag` test asserting the annotation contents. UNVERIFIED entries
     * have a Forge contract but no fixture asserting the emission contents; their
     * value/threshold/perPlayer shape is inferred from the Threshold/Morbid pattern
     * and may need adjustment when a fixture exercises one.
     *
     * Trigger-pattern ability words without a `Condition$` row (Flurry, Infusion,
     * Raid, Coven, …) are handled by [AbilityWordTriggerRecognizers] instead.
     */
    private val CONDITIONS =
        mapOf(
            // VERIFIED.
            "Threshold" to ConditionSpec(threshold = 7, value = { p, _ -> p.getZone(ZoneType.Graveyard).size() }),
            // UNVERIFIED.
            "Metalcraft" to
                ConditionSpec(threshold = 3, value = {
                    p,
                    _,
                    ->
                    p.getCardsIn(ZoneType.Battlefield).toList().count { it.isArtifact }
                }),
            // VERIFIED.
            "Delirium" to
                ConditionSpec(threshold = 4, value = {
                    p,
                    _,
                    ->
                    AbilityUtils.countCardTypesFromList(p.getCardsIn(ZoneType.Graveyard), false)
                }),
            // UNVERIFIED.
            "Ferocious" to ConditionSpec(),
            "Hellbent" to ConditionSpec(),
            "Desert" to ConditionSpec(),
            // VERIFIED.
            "Morbid" to
                ConditionSpec(
                    perPlayer = true,
                    booleanOnly = true,
                    value = { p, src ->
                        CardUtil.getThisTurnEntered(ZoneType.Graveyard, ZoneType.Battlefield, "Creature", src, null, p).size
                    },
                ),
        )

    /** Named params checked on Triggers (Threshold$ True, etc.) — derived from CONDITIONS. */
    private val NAMED_PARAM_CONDITIONS = CONDITIONS.keys

    /**
     * Scan battlefield permanents and (optionally) hand cards for ability word conditions.
     *
     * Each card's value is computed relative to its controller's game state. The
     * `CONDITIONS` map covers Forge `Condition$`-driven ability words on resident
     * permanents (Threshold, Morbid, etc.). Hand-zone scanning is delegated to
     * [AbilityWordTriggerRecognizers] for ETB-conditional ability words (Raid,
     * Infusion-on-ETB) — those badges show on the hand card pre-cast so the
     * player can see the bonus is active before paying.
     *
     * @param battlefieldCards cards currently on the battlefield (all players)
     * @param handCards cards in any player's hand (default empty for callers that
     *   only need battlefield-resident gates)
     * @param instanceIdResolver ForgeCardId → InstanceId
     * @param registryResolver Card → AbilityRegistry? (for abilityGrpId)
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun scan(
        battlefieldCards: List<Card>,
        instanceIdResolver: (ForgeCardId) -> InstanceId,
        registryResolver: (Card) -> AbilityRegistry?,
        handCards: List<Card> = emptyList(),
    ): List<AbilityWordEntry> {
        val results = mutableListOf<AbilityWordEntry>()
        val seen = mutableSetOf<Pair<Int, String>>() // (forgeCardId, conditionName) dedup
        // Per-player accumulation: conditionName → (seatIdx → list of iids)
        val perPlayerCards = mutableMapOf<String, MutableMap<Int, MutableList<Int>>>()
        // Track a representative card per player for Forge src arg
        val perPlayerRepCard = mutableMapOf<String, MutableMap<Int, Card>>()

        for (card in battlefieldCards) {
            val controller = card.controller ?: continue
            val iid = instanceIdResolver(ForgeCardId(card.id)).value
            var registryResolved = false
            var registry: AbilityRegistry? = null

            fun registryForCard(): AbilityRegistry? {
                if (!registryResolved) {
                    registry = registryResolver(card)
                    registryResolved = true
                }
                return registry
            }

            val game = controller.game
            val seatIdx = game.registeredPlayers.indexOf(controller) + 1

            // Phase 1: StaticAbility with Condition$ param
            for (sa in card.staticAbilities ?: emptyList()) {
                val condition = sa.getParam("Condition") ?: continue
                val spec = CONDITIONS[condition] ?: continue
                if (!seen.add(card.id to condition)) continue

                if (spec.perPlayer) {
                    perPlayerCards
                        .getOrPut(condition) { mutableMapOf() }
                        .getOrPut(seatIdx) { mutableListOf() }
                        .add(iid)
                    perPlayerRepCard
                        .getOrPut(condition) { mutableMapOf() }
                        .putIfAbsent(seatIdx, card)
                } else {
                    results.add(
                        AbilityWordEntry(
                            instanceId = iid,
                            abilityWordName = condition,
                            value = spec.value?.invoke(controller, card),
                            threshold = spec.threshold,
                            abilityGrpId = registryForCard()?.forStaticAbility(sa.definitionId)?.takeIf { it > 0 },
                        ),
                    )
                }
            }

            // Phase 2: Triggers with named params (Threshold$ True) or CheckSVar$ <ConditionName>
            for (trigger in card.triggers ?: emptyList()) {
                if (trigger.mode == TriggerType.ManaExpend) {
                    val threshold = trigger.getParam("Amount")?.toIntOrNull()
                    if (threshold != null) {
                        results.add(
                            AbilityWordEntry(
                                instanceId = iid,
                                abilityWordName = "ExpendedMana",
                                value = controller.expentThisTurn,
                                threshold = threshold,
                                abilityGrpId = registryForCard()?.forTrigger(trigger.id)?.takeIf { it > 0 },
                            ),
                        )
                    }
                }

                val matchedConditions = mutableSetOf<String>()
                for (paramName in NAMED_PARAM_CONDITIONS) {
                    if (trigger.hasParam(paramName)) matchedConditions.add(paramName)
                }
                // Also detect CheckSVar$ pattern (e.g. CheckSVar$ Morbid)
                val checkSVar = trigger.getParam("CheckSVar")
                if (checkSVar != null && checkSVar in CONDITIONS) {
                    matchedConditions.add(checkSVar)
                }

                for (conditionName in matchedConditions) {
                    if (!seen.add(card.id to conditionName)) continue
                    val spec = CONDITIONS[conditionName] ?: continue

                    if (spec.perPlayer) {
                        perPlayerCards
                            .getOrPut(conditionName) { mutableMapOf() }
                            .getOrPut(seatIdx) { mutableListOf() }
                            .add(iid)
                        perPlayerRepCard
                            .getOrPut(conditionName) { mutableMapOf() }
                            .putIfAbsent(seatIdx, card)
                    } else {
                        results.add(
                            AbilityWordEntry(
                                instanceId = iid,
                                abilityWordName = conditionName,
                                value = spec.value?.invoke(controller, card),
                                threshold = spec.threshold,
                                abilityGrpId = registryForCard()?.forTrigger(trigger.definitionId)?.takeIf { it > 0 },
                            ),
                        )
                    }
                }
            }

            if (card.getKeywordMagnitude(Keyword.STATION) > 0 && card.getCounters(CounterEnumType.CHARGE) > 0) {
                results.addAll(stationThresholdEntries(card, iid, registryForCard()))
            }
        }

        // Phase 3: emit one pAnn per player for perPlayer conditions (only when condition is true)
        for ((conditionName, bySeat) in perPlayerCards) {
            val spec = CONDITIONS[conditionName] ?: continue
            for ((seatIdx, iids) in bySeat) {
                val repCard = perPlayerRepCard[conditionName]?.get(seatIdx) ?: continue
                val controller = repCard.controller ?: continue
                val conditionValue = spec.value?.invoke(controller, repCard) ?: 1
                if (conditionValue <= 0) continue // condition not met — omit pAnn

                results.add(
                    AbilityWordEntry(
                        instanceId = seatIdx, // stable key for PersistentAnnotationStore upsert
                        abilityWordName = conditionName,
                        value = if (spec.booleanOnly) null else conditionValue,
                        threshold = if (spec.booleanOnly) null else spec.threshold,
                        affectorId = seatIdx,
                        affectedIds = iids,
                    ),
                )
            }
        }

        // Phase 4: quantitative CheckSVar comparison families (Devotion, Descend, …)
        results.addAll(
            AbilityWordValueRecognizers.scan(
                battlefieldCards = battlefieldCards,
                instanceIdResolver = instanceIdResolver,
                registryResolver = registryResolver,
            ),
        )

        // Phase 5: trigger-pattern ability words (Flurry, Infusion, Raid, Coven, …)
        results.addAll(
            AbilityWordTriggerRecognizers.scan(
                battlefieldCards = battlefieldCards,
                handCards = handCards,
                instanceIdResolver = instanceIdResolver,
                registryResolver = registryResolver,
            ),
        )

        battlefieldCards
            .filter { !it.isPhasedOut && it.hasKeyword(Keyword.ASCEND) }
            .groupBy { it.controller }
            .forEach { (controller, sources) ->
                val seat = controller.game.registeredPlayers.indexOf(controller) + 1
                results.add(
                    AbilityWordEntry(
                        instanceId = seat,
                        abilityWordName = "Ascend",
                        value = battlefieldCards.count { it.controller == controller && !it.isPhasedOut },
                        threshold = 10,
                        affectorId = seat,
                        affectedForgeCardIds = sources.map { ForgeCardId(it.id) },
                        hasBlessing = controller.hasBlessing(),
                    ),
                )
            }
        return results
    }

    private fun stationThresholdEntries(
        card: Card,
        iid: Int,
        registry: AbilityRegistry?,
    ): List<AbilityWordEntry> {
        if (card.getKeywordMagnitude(Keyword.STATION) <= 0) return emptyList()
        val chargeCount = card.getCounters(CounterEnumType.CHARGE)
        if (chargeCount <= 0) return emptyList()

        return card.staticAbilities.orEmpty().mapNotNull { staticAbility ->
            val affected = staticAbility.getParam("Affected") ?: return@mapNotNull null
            val threshold =
                STATION_THRESHOLD
                    .find(affected)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?: return@mapNotNull null
            AbilityWordEntry(
                instanceId = iid,
                abilityWordName = "StationLevelThreshold",
                value = chargeCount,
                threshold = threshold,
                abilityGrpId = registry?.forStaticAbility(staticAbility.definitionId)?.takeIf { it > 0 },
            )
        }
    }
}
