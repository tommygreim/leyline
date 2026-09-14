package leyline.game.state

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.keyword.Keyword
import forge.game.keyword.KeywordInterface
import forge.game.spellability.SpellAbility
import leyline.bridge.types.AbilityDefinitionRef
import leyline.bridge.types.AbilityKeywordFamily
import leyline.bridge.types.ResolvedAbilityIdentity
import leyline.game.codes.SlotEntry
import leyline.game.codes.SlotKind
import leyline.game.codes.SlotLayout
import leyline.game.data.BasicLandAbilities
import leyline.game.data.CardData
import leyline.game.data.KeywordAbilityIds

/**
 * Maps stable Forge definition IDs (SpellAbility, Trigger, StaticAbility) to client
 * abilityGrpId slots for a single card.
 *
 * Slot ordering follows [CardData.abilityIds] verbatim — the same shape
 * the client's `Cards.AbilityIds` column produces. Mana abilities and
 * unslotted intrinsic traits fall back to slot 0.
 */
class AbilityRegistry private constructor(
    private val saMap: Map<Int, Int>,
    private val staticMap: Map<Int, Int>,
    private val triggerMap: Map<Int, Int>,
    private val keywordFamilies: Map<AbilityDefinitionRef, AbilityKeywordFamily>,
    private val hiddenAbilityIds: List<Pair<Int, Int>> = emptyList(),
    val sourceCardGrpId: Int,
    val slotLayout: SlotLayout = SlotLayout.Companion.EMPTY,
) {
    /** Resolve a live or copied SpellAbility through its stable definition identity. */
    fun forSpellAbility(ability: SpellAbility): Int? =
        if (ability.grantorStatic != null) {
            grantedAbilityGrpId(ability)
        } else {
            forSpellAbility(ability.definitionId)
        }

    /** SpellAbility definition ID → abilityGrpId (mana + activated). */
    fun forSpellAbility(definitionId: Int): Int? = resolve(AbilityDefinitionRef.SpellAbility(definitionId))?.abilityGrpId

    /** StaticAbility definition ID → abilityGrpId. */
    fun forStaticAbility(definitionId: Int): Int? = resolve(AbilityDefinitionRef.StaticAbility(definitionId))?.abilityGrpId

    /** Trigger definition ID → abilityGrpId. */
    fun forTrigger(definitionId: Int): Int? = resolve(AbilityDefinitionRef.Trigger(definitionId))?.abilityGrpId

    fun resolve(definition: AbilityDefinitionRef): ResolvedAbilityIdentity? {
        val abilityGrpId =
            when (definition) {
                is AbilityDefinitionRef.SpellAbility -> saMap[definition.definitionId]
                is AbilityDefinitionRef.Trigger -> triggerMap[definition.definitionId]
                is AbilityDefinitionRef.StaticAbility -> staticMap[definition.definitionId]
            } ?: return null
        return ResolvedAbilityIdentity(definition, abilityGrpId, keywordFamilies[definition])
    }

    /** Resolve an ability added by a continuous `AddAbility` effect. */
    fun grantedAbilityGrpId(ability: SpellAbility): Int? {
        if (ability.grantorStatic == null || hiddenAbilityIds.size != 1) return null
        return hiddenAbilityIds.single().first
    }

    /** Stable client unique-ability slot for a generated activated ability. */
    fun grantedAbilityUniqueIndex(ability: SpellAbility): Int? = grantedAbilityGrpId(ability)?.let { 0 }

    companion object {
        /** Empty registry — no mappings. */
        val EMPTY =
            AbilityRegistry(emptyMap(), emptyMap(), emptyMap(), emptyMap(), sourceCardGrpId = 0, slotLayout = SlotLayout.Companion.EMPTY)

        /**
         * Build a registry from a live Forge [card] and its [cardData].
         * Uses [cardData.abilityIds] verbatim as the slot layout.
         */
        fun build(
            card: Card,
            cardData: CardData,
        ): AbilityRegistry {
            val abilityIds = cardData.abilityIds
            if (abilityIds.isEmpty()) {
                return AbilityRegistry(emptyMap(), emptyMap(), emptyMap(), emptyMap(), sourceCardGrpId = cardData.grpId)
            }

            val fallbackGrpId = abilityIds[0].first
            val saMap = mutableMapOf<Int, Int>()
            val staticMap = mutableMapOf<Int, Int>()
            val triggerMap = mutableMapOf<Int, Int>()
            val keywordFamilies = mutableMapOf<AbilityDefinitionRef, AbilityKeywordFamily>()

            val keywordCount = mapKeywords(card, abilityIds, saMap, staticMap, triggerMap, keywordFamilies)
            val slotKinds =
                abilityIds.mapIndexed { i, _ ->
                    when {
                        cardData.abilityKinds.size == abilityIds.size -> cardData.abilityKinds[i]
                        i < keywordCount -> SlotKind.Keyword
                        else -> SlotKind.Activated
                    }
                }
            // Indices in abilityIds that are eligible for non-mana activated SAs.
            // For Arena-sourced cards: slots whose ability metadata is Activated.
            // For data without per-slot kinds (legacy / puzzle deriver): fall back
            // to "all slots after keywords are activated" — matches old behavior.
            val activatedSlotIndices =
                if (cardData.abilityKinds.size == abilityIds.size) {
                    abilityIds.indices.filter {
                        slotKinds[it] == SlotKind.Activated
                    }
                } else {
                    (keywordCount until abilityIds.size).toList()
                }
            val manaSlotIndices =
                if (cardData.abilityKinds.size == abilityIds.size) {
                    abilityIds.indices.filter {
                        slotKinds[it] == SlotKind.Mana
                    }
                } else {
                    emptyList()
                }
            mapActivatedAbilities(card, abilityIds, activatedSlotIndices, saMap)
            mapReconfigureUnattachAbilities(card, saMap)
            mapStationThresholdStatics(card, abilityIds, staticMap)
            mapManaAbilities(card, abilityIds, manaSlotIndices, fallbackGrpId, saMap)
            mapUnclaimedIntrinsicTriggers(card, cardData, abilityIds, keywordCount, triggerMap)
            mapUnclaimedIntrinsicStatics(card, cardData, abilityIds, keywordCount, staticMap)
            mapUnclaimedLegacyStatics(card, cardData, abilityIds, keywordCount, staticMap)
            mapUnclaimedIntrinsics(card, fallbackGrpId, staticMap, triggerMap)

            // Derive SlotLayout from the same data — single source of truth.
            // Use cardData.abilityKinds when available so triggers/statics interleaved
            // among Arena's slots (e.g. Kaito at slot 0) get classified correctly.
            val virtualSlots = reconfigureUnattachSlot(card)
            val activatedCount = slotKinds.count { it == SlotKind.Activated } + virtualSlots.count { it.kind == SlotKind.Activated }
            val slots =
                abilityIds.mapIndexed { i, (grpId, textId) ->
                    SlotEntry(grpId, textId, slotKinds[i])
                } + virtualSlots
            val layout = SlotLayout(keywordCount, activatedCount, slots)

            return AbilityRegistry(saMap, staticMap, triggerMap, keywordFamilies, cardData.hiddenAbilityIds, cardData.grpId, layout)
        }

        /**
         * Station threshold rows are per-card static ability ids (e.g. 60002,
         * 60024) interleaved with granted core ability rows. Forge exposes the
         * threshold striations as static abilities whose descriptions start with
         * `STATION N+`; Arena's per-threshold rows are the matching high synthetic
         * ids in card slot order.
         */
        private fun mapStationThresholdStatics(
            card: Card,
            abilityIds: List<Pair<Int, Int>>,
            staticMap: MutableMap<Int, Int>,
        ) {
            val stationStatics =
                card.staticAbilities
                    ?.filter { it.getParam("Description")?.startsWith("STATION ") == true }
                    .orEmpty()
            if (stationStatics.isEmpty()) return

            val thresholdRows = abilityIds.map { it.first }.filter { it >= STATION_THRESHOLD_ABILITY_ID_FLOOR }
            for ((staticAbility, grpId) in stationStatics.zip(thresholdRows)) {
                staticMap[staticAbility.definitionId] = grpId
            }
        }

        /** Phase 1: Keywords occupy the first N slots. Returns keyword count. */
        private fun mapKeywords(
            card: Card,
            abilityIds: List<Pair<Int, Int>>,
            saMap: MutableMap<Int, Int>,
            staticMap: MutableMap<Int, Int>,
            triggerMap: MutableMap<Int, Int>,
            keywordFamilies: MutableMap<AbilityDefinitionRef, AbilityKeywordFamily>,
        ): Int {
            val keywordStrings =
                card.rules
                    ?.mainPart
                    ?.keywords
                    ?.toList() ?: emptyList()
            val liveKeywords = card.getKeywords() ?: emptyList()
            val claimed = mutableSetOf<KeywordInterface>()

            for ((slotIdx, kwText) in keywordStrings.withIndex()) {
                if (slotIdx >= abilityIds.size) break
                val grpId = abilityIds[slotIdx].first
                val matching =
                    liveKeywords.filter { kw ->
                        kw !in claimed && kw.isIntrinsic && matchesKeywordText(kw, kwText)
                    }
                for (kw in matching) {
                    claimed.add(kw)
                    val family = keywordFamily(kw)
                    for (sa in kw.abilities) {
                        saMap[sa.definitionId] = grpId
                        family?.let { keywordFamilies[AbilityDefinitionRef.SpellAbility(sa.definitionId)] = it }
                    }
                    for (trig in kw.triggers) {
                        triggerMap[trig.definitionId] = grpId
                        family?.let { keywordFamilies[AbilityDefinitionRef.Trigger(trig.definitionId)] = it }
                    }
                    for (st in kw.staticAbilities) {
                        staticMap[st.definitionId] = grpId
                        family?.let { keywordFamilies[AbilityDefinitionRef.StaticAbility(st.definitionId)] = it }
                    }
                }
            }
            return keywordStrings.size
        }

        /**
         * Phase 2: Map non-mana activated SAs to abilityGrpIds by walking
         * [activatedSlotIndices] in order. Each indexed slot is presumed
         * to be an activated ability per upstream classification (Arena's
         * `Abilities.Category=1` for production data; positional fallback
         * for puzzle/test paths). Skipping non-activated slots prevents
         * the off-by-one bug where Arena's interleaved trigger/static
         * slots would shift activated SAs onto the wrong grpIds.
         */
        private fun mapActivatedAbilities(
            card: Card,
            abilityIds: List<Pair<Int, Int>>,
            activatedSlotIndices: List<Int>,
            saMap: MutableMap<Int, Int>,
        ) {
            var idx = 0
            for (sa in nonManaActivatedAbilities(card)) {
                if (!sa.isActivatedAbility || sa.isManaAbility()) continue
                if (isReconfigureUnattach(sa)) {
                    saMap[sa.definitionId] = KeywordAbilityIds.RECONFIGURE_UNATTACH
                    continue
                }
                if (idx >= activatedSlotIndices.size) {
                    idx++
                    continue
                }
                val slotIdx = activatedSlotIndices[idx]
                if (slotIdx < abilityIds.size) saMap[sa.definitionId] = abilityIds[slotIdx].first
                idx++
            }
        }

        private fun nonManaActivatedAbilities(card: Card): List<SpellAbility> {
            val abilities = card.spellAbilities.toMutableList()
            val seen = abilities.map { it.definitionId }.toMutableSet()
            for (sa in card.allSpellAbilities.orEmpty()) {
                if (sa.definitionId in seen) continue
                if (sa.isActivatedAbility && !sa.isManaAbility()) {
                    abilities.add(sa)
                    seen.add(sa.definitionId)
                }
            }
            return abilities
        }

        private fun mapReconfigureUnattachAbilities(
            card: Card,
            saMap: MutableMap<Int, Int>,
        ) {
            for (sa in card.allSpellAbilities.orEmpty()) {
                if (isReconfigureUnattach(sa)) saMap[sa.definitionId] = KeywordAbilityIds.RECONFIGURE_UNATTACH
            }
        }

        /** Phase 3: Map mana abilities to mana slots, with legacy fallback to slot 0. */
        private fun mapManaAbilities(
            card: Card,
            abilityIds: List<Pair<Int, Int>>,
            manaSlotIndices: List<Int>,
            fallbackGrpId: Int,
            saMap: MutableMap<Int, Int>,
        ) {
            var idx = 0
            for (sa in card.manaAbilities ?: emptyList()) {
                if (!sa.isManaAbility() || !sa.isIntrinsic) continue
                val generatedGrpId = BasicLandAbilities.byTypeDerivedManaAbility(card, sa)
                if (generatedGrpId != null) {
                    saMap.putIfAbsent(sa.definitionId, generatedGrpId)
                    continue
                }
                val slotIdx = manaSlotIndices.getOrNull(idx)
                val grpId = if (slotIdx != null && slotIdx < abilityIds.size) abilityIds[slotIdx].first else fallbackGrpId
                saMap.putIfAbsent(sa.definitionId, grpId)
                idx++
            }
        }

        /** Map card-specific intrinsic triggers when Arena's remaining
         *  intrinsic slots line up exactly with Forge's unclaimed triggers.
         *  Mixed static+trigger layouts keep the legacy fallback until CardData
         *  carries trigger/static categories separately. */
        private fun mapUnclaimedIntrinsicTriggers(
            card: Card,
            cardData: CardData,
            abilityIds: List<Pair<Int, Int>>,
            keywordCount: Int,
            triggerMap: MutableMap<Int, Int>,
        ) {
            if (cardData.abilityKinds.size != abilityIds.size) return
            val triggers =
                card.triggers
                    ?.filter { it.isIntrinsic && it.definitionId !in triggerMap }
                    .orEmpty()
            if (triggers.isEmpty()) return
            val intrinsicSlots =
                abilityIds.indices.filter { idx ->
                    idx >= keywordCount &&
                        if (cardData.abilityCategories.size == abilityIds.size) {
                            cardData.abilityCategories[idx] == TRIGGER_CATEGORY
                        } else {
                            cardData.abilityKinds[idx] == SlotKind.Intrinsic
                        }
                }
            if (intrinsicSlots.size != triggers.size) return
            for ((trig, slotIdx) in triggers.zip(intrinsicSlots)) {
                triggerMap[trig.definitionId] = abilityIds[slotIdx].first
            }
        }

        /** Map card-specific static abilities when Arena's remaining
         *  intrinsic slots line up exactly with Forge's unclaimed statics. */
        private fun mapUnclaimedIntrinsicStatics(
            card: Card,
            cardData: CardData,
            abilityIds: List<Pair<Int, Int>>,
            keywordCount: Int,
            staticMap: MutableMap<Int, Int>,
        ) {
            if (cardData.abilityKinds.size != abilityIds.size) return
            val statics =
                card.staticAbilities
                    ?.filter { it.definitionId !in staticMap }
                    .orEmpty()
            if (statics.isEmpty()) return
            val intrinsicSlots =
                abilityIds.indices.filter { idx ->
                    idx >= keywordCount &&
                        if (cardData.abilityCategories.size == abilityIds.size) {
                            cardData.abilityCategories[idx] >= STATIC_CATEGORY_FLOOR
                        } else {
                            cardData.abilityKinds[idx] == SlotKind.Intrinsic
                        }
                }
            if (intrinsicSlots.size != statics.size) return
            for ((staticAbility, slotIdx) in statics.zip(intrinsicSlots)) {
                staticMap[staticAbility.definitionId] = abilityIds[slotIdx].first
            }
        }

        /** Legacy data without per-slot categories: map statics only when the
         * remaining post-keyword slots line up exactly after activated abilities. */
        private fun mapUnclaimedLegacyStatics(
            card: Card,
            cardData: CardData,
            abilityIds: List<Pair<Int, Int>>,
            keywordCount: Int,
            staticMap: MutableMap<Int, Int>,
        ) {
            if (cardData.abilityKinds.size == abilityIds.size) return
            val statics =
                card.staticAbilities
                    ?.filter { it.definitionId !in staticMap }
                    .orEmpty()
            if (statics.isEmpty()) return
            val activatedCount =
                card.spellAbilities
                    ?.count { it.isActivatedAbility && it.isIntrinsic && !it.isManaAbility() } ?: 0
            val staticSlots = (keywordCount until abilityIds.size).drop(activatedCount)
            if (staticSlots.size != statics.size) return
            for ((staticAbility, slotIdx) in statics.zip(staticSlots)) {
                staticMap[staticAbility.definitionId] = abilityIds[slotIdx].first
            }
        }

        /** Phase 4: Unclaimed intrinsic statics and triggers fall back to slot 0. */
        private fun mapUnclaimedIntrinsics(
            card: Card,
            fallbackGrpId: Int,
            staticMap: MutableMap<Int, Int>,
            triggerMap: MutableMap<Int, Int>,
        ) {
            for (st in card.staticAbilities ?: emptyList()) {
                if (!st.isIntrinsic) continue
                staticMap.putIfAbsent(st.definitionId, fallbackGrpId)
            }
            for (trig in card.triggers ?: emptyList()) {
                if (!trig.isIntrinsic) continue
                triggerMap.putIfAbsent(trig.definitionId, fallbackGrpId)
            }
        }

        private fun matchesKeywordText(
            kw: KeywordInterface,
            rulesText: String,
        ): Boolean {
            if (rulesText.startsWith("Reconfigure", ignoreCase = true)) return false
            if (kw.original.equals(rulesText, ignoreCase = true)) return true
            val kwName = kw.keyword.toString()
            return rulesText.startsWith(kwName, ignoreCase = true)
        }

        private fun keywordFamily(keyword: KeywordInterface): AbilityKeywordFamily? = KEYWORD_FAMILIES[keyword.keyword]

        private fun reconfigureUnattachSlot(card: Card): List<SlotEntry> =
            if (card.allSpellAbilities.orEmpty().any { isReconfigureUnattach(it) }) {
                listOf(SlotEntry(KeywordAbilityIds.RECONFIGURE_UNATTACH, 0, SlotKind.Activated))
            } else {
                emptyList()
            }

        private fun isReconfigureUnattach(sa: SpellAbility): Boolean =
            sa.api == ApiType.Unattach && sa.getParam("PrecostDesc") == "Reconfigure"

        private const val STATION_THRESHOLD_ABILITY_ID_FLOOR = 60_000
        internal const val TRIGGER_CATEGORY = 2
        private const val STATIC_CATEGORY_FLOOR = 3

        private val KEYWORD_FAMILIES =
            mapOf(
                Keyword.BACKUP to AbilityKeywordFamily.Backup,
                Keyword.MENTOR to AbilityKeywordFamily.Mentor,
            )
    }
}
