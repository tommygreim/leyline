package leyline.game.snapshot

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.spellability.SpellAbility
import forge.game.spellability.SpellAbilityStackInstance
import forge.game.trigger.WrappedAbility
import leyline.bridge.types.AbilityDefinitionRef
import leyline.bridge.types.ForgeCardId
import leyline.game.data.KeywordAbilityIds
import leyline.game.mapping.ZoneMapper
import leyline.game.state.AbilityRegistry
import leyline.game.state.GameBridge
import org.jetbrains.annotations.VisibleForTesting

internal object StackAbilityGrpIdResolver {
    /**
     * Resolve the **ability** grpId for a stack entry — the row in the Arena
     * `Abilities` table that describes this trigger / activated SA. Resolution
     * order:
     *  1. Saga chapter (trigger-row grpId from CardData; see
     *     `ZoneMapper.chapterGrpIdFromCardData`).
     *  2. Runtime-keyed identity recorded by the event lifecycle.
     *  3. Typed definition lookup for entries that bypassed that lifecycle.
     *  4. Explicit mechanic fallbacks for stack-only synthetic entries.
     *  5. Unresolved identity → 0.  A source-card fallback is never valid for
     *     an Ability object: the client treats `grpId` as the Abilities-table
     *     identity and may cache the source card's complete rules text.
     *
     * Returns 0 when the identity is not resolved yet. The stack projection
     * suppresses that transient object until a later capture has the real
     * ability row instead of publishing a misleading card row.
     */
    @VisibleForTesting
    internal fun resolveEntryAbilityGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
        sourceCardGrpId: Int,
        bridge: GameBridge,
    ): Int =
        SpeedEffectIdentity.ABILITY_GRP_ID.takeIf {
            entry.isTrigger && entry.spellAbility?.api == ApiType.ChangeSpeed && SpeedEffectIdentity.matches(sourceCard)
        }
            ?: resolveChapterGrpId(entry, sourceCardGrpId, bridge)
            ?: resolveParadigmDelayedGrpId(entry, sourceCard)
            ?: pendingIdentityGrpId(entry, bridge)
            ?: resolveStructuredIdentityGrpId(entry, sourceCard, bridge)
            ?: decayedTriggerGrpId(entry, sourceCard, sourceCardGrpId, bridge)
            ?: cascadeOrTrainingGrpId(entry, sourceCard)
            ?: resolveDiscoverGrpId(entry, sourceCardGrpId, bridge)
            ?: 0

    private fun pendingIdentityGrpId(
        entry: SpellAbilityStackInstance,
        bridge: GameBridge,
    ): Int? {
        val sourceCard = entry.sourceCard
        // A modal trigger is selected after Forge creates the stack item.  The
        // original stack identity therefore remains the parent trigger row;
        // prefer the response keyed by this exact SA id so the public Ability
        // object shows only the chosen child (e.g. Technopathy — Draw a card).
        if (sourceCard != null) {
            bridge
                .selectedModalAbilityGrpId(ForgeCardId(sourceCard.id), entry.spellAbility?.id ?: 0)
                ?.takeIf { it != 0 }
                ?.let { return it }
        }
        return entry.spellAbility
            ?.id
            ?.let { bridge.stackAbilityIdentity(it)?.abilityGrpId }
            ?.takeIf { it != 0 }
    }

    private fun resolveParadigmDelayedGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
    ): Int? = KeywordAbilityIds.PARADIGM_DELAYED_TRIGGER.takeIf { entry.isTrigger && isParadigmDelayedTrigger(entry, sourceCard) }

    private fun resolveStructuredIdentityGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
        bridge: GameBridge,
    ): Int? {
        val ability = entry.spellAbility ?: return null
        // The stack can expose a trigger wrapper before the event collector has
        // recorded its runtime identity.  Resolving only that wrapper as a
        // SpellAbility is lossy: its definition id belongs to the wrapped
        // effect, while the trigger row is identified by Trigger.definitionId.
        // Try the wrapper, its wrapped/root/original abilities, and their
        // explicit definitions before allowing the caller to treat the source
        // card as the ability.  This keeps the first GSM from publishing an
        // Ability object with the card grpId (which the client caches as the
        // ability's text).
        val candidates =
            buildList {
                var current: SpellAbility? = ability
                repeat(8) {
                    val candidate = current ?: return@repeat
                    if (any { it === candidate }) {
                        current = null
                        return@repeat
                    }
                    add(candidate)
                    current =
                        when {
                            candidate is WrappedAbility -> candidate.wrappedAbility
                            candidate.rootAbility !== candidate -> candidate.rootAbility
                            candidate.originalAbility != null -> candidate.originalAbility
                            else -> null
                        }
                }
            }

        candidates.forEach { candidate ->
            bridge
                .resolveAbilityIdentity(sourceCard, candidate)
                ?.abilityGrpId
                ?.takeIf { it != 0 }
                ?.let { return it }
            candidateDefinitions(candidate).forEach { definition ->
                bridge
                    .resolveAbilityIdentity(sourceCard, definition)
                    ?.abilityGrpId
                    ?.takeIf { it != 0 }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun candidateDefinitions(ability: SpellAbility): List<AbilityDefinitionRef> =
        buildList {
            ability.trigger?.definitionId?.let { add(AbilityDefinitionRef.Trigger(it)) }
            ability.sourceTriggerDefinitionId.takeIf { it > 0 }?.let { add(AbilityDefinitionRef.Trigger(it)) }
            add(AbilityDefinitionRef.SpellAbility(ability.definitionId))
        }.distinct()

    /** Discover (Forge `DB$ Discover | Num$ N`): per-card ability row. */
    private fun resolveDiscoverGrpId(
        entry: SpellAbilityStackInstance,
        sourceCardGrpId: Int,
        bridge: GameBridge,
    ): Int? {
        if (!entry.isTrigger || entry.spellAbility?.api != ApiType.Discover || sourceCardGrpId == 0) return null
        val cardData = bridge.cardRepository.findByGrpId(sourceCardGrpId) ?: return null
        return cardData
            .abilityIds
            .firstOrNull { (id, _) -> bridge.cardRepository.findAbilityInfo(id)?.category == AbilityRegistry.TRIGGER_CATEGORY }
            ?.first
    }

    private fun cascadeOrTrainingGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
    ): Int? =
        when {
            sourceCard.hasKeyword("Cascade") -> KeywordAbilityIds.CASCADE
            entry.spellAbility?.hasParam("Training") == true && entry.spellAbility?.api == ApiType.PutCounter -> KeywordAbilityIds.TRAINING
            else -> null
        }

    private fun isParadigmDelayedTrigger(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
    ): Boolean =
        entry.spellAbility?.trigger?.getParam("Execute") == "ParadigmCopy" &&
            sourceCard.effectSource?.hasKeyword("Paradigm") == true

    private fun decayedTriggerGrpId(
        entry: SpellAbilityStackInstance,
        sourceCard: Card,
        sourceCardGrpId: Int,
        bridge: GameBridge,
    ): Int? {
        if (!sourceCard.hasKeyword("Decayed")) return null
        val sa = entry.spellAbility ?: return null
        if (sa.api == ApiType.DelayedTrigger && sa.trigger?.getParam("Mode") == "Attacks") {
            return KeywordAbilityIds.DECAYED
        }
        if (sa.api == ApiType.Sacrifice && sa.trigger?.getParam("Phase") == "EndCombat") {
            return bridge.cardRepository.findHiddenTriggeredAbilityGrpId(sourceCardGrpId)
        }
        return null
    }

    /** If [entry] is a Saga chapter trigger, return the chapter-specific ability grpId. */
    private fun resolveChapterGrpId(
        entry: SpellAbilityStackInstance,
        sourceCardGrpId: Int,
        bridge: GameBridge,
    ): Int? {
        if (!entry.isTrigger) return null
        val sa = entry.spellAbility ?: return null
        val trigger = sa.trigger ?: return null
        val chapterParam = trigger.getParam("Chapter") ?: return null
        val chapterIdx = chapterParam.toIntOrNull()?.takeIf { it >= 1 } ?: return null
        // Resolve chapter rows from the entry's own printing: a name→primary
        // lookup could pick a different printing whose chapter rows differ.
        val cardData = bridge.cardRepository.findByGrpId(sourceCardGrpId) ?: return null
        return ZoneMapper.chapterGrpIdFromCardData(cardData, chapterIdx)
    }
}
