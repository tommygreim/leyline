package leyline.bridge

import forge.ai.ComputerUtilMana
import forge.card.mana.ManaCost
import forge.game.card.Card
import forge.game.card.CardCollection
import forge.game.cost.CostAdjustment
import forge.game.mana.ManaCostBeingPaid
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import leyline.bridge.handoff.StrictPromptRefusalException
import leyline.bridge.types.ManaColorMapping
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.ManaCostSpecType
import wotc.mtgo.gre.external.messaging.Messages.ManaRequirement
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Shared mana-cost support for action emission.
 *
 * Owns affordability helpers, two-generic hybrid fallback, effective-cost
 * calculation, and proto mana-requirement conversion. Cost adjustment may
 * temporarily seed Forge activation context; [computeEffectiveCost] restores
 * those fields before returning.
 */
internal object ActionManaCosts {
    data class ManaPaymentPreview(
        val cost: ManaCost,
        val sources: List<SpellAbility>,
    )

    /**
     * Predict a payment without choosing or consuming the player's resources.
     * Emerge and Offering require a hypothetical sacrifice before Forge can
     * plan the residual mana payment. Keep that choice inside the same
     * probe and restore it afterwards; the displayed cost stays pre-choice.
     */
    fun predictManaPayment(
        displayedCost: ManaCost,
        sa: SpellAbility,
        player: Player,
    ): ManaPaymentPreview? =
        affordabilityProbe(
            probe = {
                preservingPaymentProbeState(sa, player) {
                    NonInteractiveScope.bestEffort {
                        val needsSacrifice =
                            (sa.isEmerge && sa.sacrificedAsEmerge == null) ||
                                (sa.isOffering && sa.sacrificedAsOffering == null)
                        val toPay =
                            if (needsSacrifice) {
                                ComputerUtilMana.calculateManaCost(sa.payCosts, sa, player, true, 0, false)
                            } else {
                                ManaCostBeingPaid(displayedCost)
                            }
                        val cost = toPay.toManaCost()
                        ComputerUtilMana
                            .getManaPaymentPlan(toPay, sa, player, false)
                            ?.let { ManaPaymentPreview(cost, it.toList()) }
                    }
                }
            },
            fallback = { null },
        )

    fun canPayManaCost(
        sa: SpellAbility,
        player: Player,
    ): Boolean =
        affordabilityProbe(
            probe = {
                // Forge's payment probe re-runs cost adjustment, which consults
                // the controller for payment-time reductions (Delve, Convoke,
                // Waterbend). Payability wants the best case the board allows,
                // answered deterministically — never a prompt.
                preservingPaymentProbeState(sa, player) {
                    NonInteractiveScope.bestEffort { ComputerUtilMana.canPayManaCost(sa, player, 0, false) }
                } ||
                    canPayOrTwoGenericManaCost(sa, player)
            },
            fallback = { canPayOrTwoGenericManaCost(sa, player) },
        )

    internal fun <T> affordabilityProbe(
        probe: () -> T,
        fallback: () -> T,
    ): T =
        try {
            probe()
        } catch (refusal: StrictPromptRefusalException) {
            throw refusal
        } catch (_: Exception) {
            fallback()
        }

    /**
     * Harmonize lets the caster tap an untapped creature to reduce the cost by its power.
     * Forge asks for that amount only once the cast is under way, so the plain payability
     * probe never sees it and a graveyard Harmonize card looks uncastable whenever the lands
     * alone fall short.
     */
    fun canPayWithHarmonizeReduction(
        sa: SpellAbility,
        player: Player,
    ): Boolean {
        val reduction =
            player
                .getZone(ForgeZoneType.Battlefield)
                .cards
                .filter { !it.isTapped && it.isCreature }
                .maxOfOrNull { it.netPower }
                ?.takeIf { it > 0 } ?: return false
        val cost = computeEffectiveCost(sa, player) ?: return false
        val toPay = ManaCostBeingPaid(cost)
        toPay.decreaseGenericMana(reduction.coerceAtMost(cost.genericCost))
        return affordabilityProbe(
            probe = {
                preservingPaymentProbeState(sa, player) {
                    NonInteractiveScope.bestEffort { ComputerUtilMana.canPayManaCost(toPay, sa, player, false) }
                }
            },
            fallback = { false },
        )
    }

    fun canPayWithPaymentSourceReducer(
        sa: SpellAbility,
        player: Player,
        artifacts: Boolean,
        creatures: Boolean,
    ): Boolean {
        val cost = computeEffectiveCost(sa, player) ?: return false
        val sourceColors = availableManaSourceColors(player, sa).toMutableList()
        val reducerColors =
            player
                .getZone(ForgeZoneType.Battlefield)
                .cards
                .mapNotNull { card ->
                    val eligible = !card.isTapped && ((artifacts && card.isArtifact) || (creatures && card.isCreature))
                    if (eligible) card.color.toProtoManaColors() else null
                }.toMutableList()

        fun canPayColor(color: ManaColor): Boolean {
            val index = sourceColors.indexOfFirst { ManaColor.Generic in it || ManaColor.AnyColor in it || color in it }
            if (index >= 0) {
                sourceColors.removeAt(index)
                return true
            }
            if (!creatures) return false
            val convokeIndex = reducerColors.indexOfFirst { color in it }
            if (convokeIndex < 0) return false
            reducerColors.removeAt(convokeIndex)
            return true
        }

        for (shard in cost) {
            val color = ManaColorMapping.fromShard(shard) ?: continue
            if (color != ManaColor.Generic && !canPayColor(color)) return false
        }
        return cost.genericCost <= sourceColors.size + reducerColors.size
    }

    private fun forge.card.ColorSet.toProtoManaColors(): Set<ManaColor> =
        buildSet {
            if (hasWhite()) add(ManaColor.White_afc9)
            if (hasBlue()) add(ManaColor.Blue_afc9)
            if (hasBlack()) add(ManaColor.Black_afc9)
            if (hasRed()) add(ManaColor.Red_afc9)
            if (hasGreen()) add(ManaColor.Green_afc9)
        }

    fun canPayManaCostPairsWithGenericReduction(
        cost: List<Pair<ManaColor, Int>>,
        player: Player,
        genericReduction: Int,
    ): Boolean {
        val sourceColors = availableManaSourceColors(player).toMutableList()

        fun canPayColor(color: ManaColor): Boolean {
            val index = sourceColors.indexOfFirst { ManaColor.Generic in it || ManaColor.AnyColor in it || color in it }
            if (index < 0) return false
            sourceColors.removeAt(index)
            return true
        }

        var genericCost = 0
        for ((color, count) in cost) {
            if (color == ManaColor.Generic) {
                genericCost += count
            } else {
                repeat(count) {
                    if (!canPayColor(color)) return false
                }
            }
        }
        return (genericCost - genericReduction).coerceAtLeast(0) <= sourceColors.size
    }

    @Suppress("CyclomaticComplexMethod")
    private fun canPayOrTwoGenericManaCost(
        sa: SpellAbility,
        player: Player,
    ): Boolean {
        val cost = computeEffectiveCost(sa, player) ?: return false
        val hybridColors = cost.mapNotNull { ManaColorMapping.fromOrTwoGenericShard(it) }
        val choiceRequirements = cost.map(ManaColorMapping::standardHybridColors).filter { it.size > 1 }

        val coloredRequirements =
            cost.mapNotNull { shard ->
                if (ManaColorMapping.fromOrTwoGenericShard(shard) == null &&
                    !shard.isPhyrexian() &&
                    ManaColorMapping.standardHybridColors(shard).isEmpty()
                ) {
                    ManaColorMapping.fromShard(shard)
                } else {
                    null
                }
            }
        val phyrexianRequirements = cost.filter { it.isPhyrexian() }
        val sourceColors = availableManaSourceColors(player, sa)

        fun canPayColor(
            sourceIndex: Int,
            color: ManaColor,
        ): Boolean =
            ManaColor.Generic in sourceColors[sourceIndex] ||
                ManaColor.AnyColor in sourceColors[sourceIndex] ||
                color in sourceColors[sourceIndex]

        fun payGeneric(
            needed: Int,
            used: BooleanArray,
            then: () -> Boolean,
        ): Boolean {
            if (needed == 0) return then()
            for (i in sourceColors.indices) {
                if (used[i]) continue
                used[i] = true
                if (payGeneric(needed - 1, used, then)) return true
                used[i] = false
            }
            return false
        }

        fun payPhyrexian(
            index: Int,
            used: BooleanArray,
        ): Boolean {
            if (index == phyrexianRequirements.size) {
                return payGeneric(cost.genericCost, used) { true }
            }
            val colors = ManaColorMapping.paymentColors(phyrexianRequirements[index])
            for (color in colors) {
                for (i in sourceColors.indices) {
                    if (used[i] || !canPayColor(i, color)) continue
                    used[i] = true
                    if (payPhyrexian(index + 1, used)) return true
                    used[i] = false
                }
            }
            return if (player.canPayLife(2, false, sa)) payPhyrexian(index + 1, used) else false
        }

        fun payHybrids(
            index: Int,
            used: BooleanArray,
        ): Boolean {
            if (index == hybridColors.size) {
                return payPhyrexian(0, used)
            }
            val color = hybridColors[index]
            for (i in sourceColors.indices) {
                if (used[i] || !canPayColor(i, color)) continue
                used[i] = true
                if (payHybrids(index + 1, used)) return true
                used[i] = false
            }
            return payGeneric(2, used) { payHybrids(index + 1, used) }
        }

        fun payChoices(
            index: Int,
            used: BooleanArray,
        ): Boolean {
            if (index == choiceRequirements.size) return payHybrids(0, used)
            for (color in choiceRequirements[index]) {
                for (i in sourceColors.indices) {
                    if (used[i] || !canPayColor(i, color)) continue
                    used[i] = true
                    if (payChoices(index + 1, used)) return true
                    used[i] = false
                }
            }
            return false
        }

        fun payColored(
            index: Int,
            used: BooleanArray,
        ): Boolean {
            if (index == coloredRequirements.size) return payChoices(0, used)
            val color = coloredRequirements[index]
            for (i in sourceColors.indices) {
                if (used[i] || !canPayColor(i, color)) continue
                used[i] = true
                if (payColored(index + 1, used)) return true
                used[i] = false
            }
            return false
        }

        return payColored(0, BooleanArray(sourceColors.size))
    }

    private fun availableManaSourceColors(
        player: Player,
        payingAbility: SpellAbility? = null,
    ): List<Set<ManaColor>> =
        player
            .getZone(ForgeZoneType.Battlefield)
            .cards
            .filterNot { it.isTapped }
            // A source whose tap is part of the ability's own activation cost
            // cannot also supply mana for that cost. Forge's primary payment
            // probe excludes it; keep this fallback probe equally strict.
            .filterNot { card -> payingAbility?.payCosts?.hasTapCost() == true && card === payingAbility.hostCard }
            .mapNotNull { card ->
                getPlayableManaAbilities(card, player)
                    .flatMap { sa ->
                        val mana = sa.manaPart ?: return@flatMap emptyList()
                        if (payingAbility != null && !mana.meetsManaRestrictions(payingAbility)) {
                            return@flatMap emptyList()
                        }
                        val produced = if (mana.isComboMana) mana.getComboColors(sa) else mana.origProduced
                        produced.split(" ").mapNotNull { producedToManaColor(it) }
                    }.toSet()
                    .takeIf { it.isNotEmpty() }
            }

    fun computeEffectiveCost(
        sa: SpellAbility,
        player: Player,
    ): ManaCost? {
        val baseCost = sa.payCosts ?: return null
        val hostCard = sa.hostCard
        val originalActivator = sa.activatingPlayer
        if (originalActivator == null) sa.setActivatingPlayer(player)
        val originalCastFrom = hostCard.castFrom
        val originalState = hostCard.currentStateName
        val castState = sa.cardStateName
        // Forge switches a card to the chosen spell face before paying its
        // cost. Do the same for this quiet display probe: otherwise a static
        // reducer printed only on the creature face also reduces its Adventure
        // (or another linked spell face) while the card remains in hand.
        val switchedState =
            !hostCard.isInPlay && castState != null && castState != originalState && hostCard.hasState(castState)
        if (switchedState) hostCard.setState(castState, false)
        val seededCastFrom =
            hostCard.isCommander &&
                originalCastFrom == null &&
                hostCard.zone?.zoneType == ForgeZoneType.Command
        if (seededCastFrom) hostCard.setCastFrom(hostCard.zone)
        try {
            // Quiet scope: cost adjustment consults the controller for
            // payment-time reductions (Delve, Convoke, Waterbend, Offering).
            // The effective cost is the cost after state-derived
            // modifications only — payment choices belong to the payment
            // prompt, so every one answers "nothing chosen" here.
            return preservingPaymentProbeState(sa, player) {
                NonInteractiveScope.quiet {
                    val adjusted = CostAdjustment.adjust(baseCost, sa, false)
                    val manaCost = adjusted.totalMana ?: return@quiet null
                    if (manaCost.isNoCost) return@quiet null
                    val beingPaid = ManaCostBeingPaid(manaCost)
                    CostAdjustment.adjust(beingPaid, sa, player, null, true, false)
                    beingPaid.toManaCost()
                }
            }
        } finally {
            if (switchedState) hostCard.setState(originalState, false)
            if (seededCastFrom) hostCard.setCastFrom(originalCastFrom)
            if (originalActivator == null) sa.setActivatingPlayer(null)
        }
    }

    private inline fun <T> preservingPaymentProbeState(
        sa: SpellAbility,
        player: Player,
        block: () -> T,
    ): T {
        val sacrificedAsOffering = sa.sacrificedAsOffering
        val sacrificedAsEmerge = sa.sacrificedAsEmerge
        val tappedForConvoke = CardCollection(sa.tappedForConvoke)
        val host = sa.hostCard
        val delved = host?.let { CardCollection(it.delved) }
        val castFrom = host?.castFrom
        val usedToPay =
            player.game
                .getCardsIn(ForgeZoneType.Battlefield)
                .associateWith(Card::isUsedToPay)

        try {
            return block()
        } finally {
            if (sacrificedAsOffering == null) sa.resetSacrificedAsOffering() else sa.setSacrificedAsOffering(sacrificedAsOffering)
            if (sacrificedAsEmerge == null) sa.resetSacrificedAsEmerge() else sa.setSacrificedAsEmerge(sacrificedAsEmerge)
            sa.clearTappedForConvoke()
            tappedForConvoke.forEach(sa::addTappedForConvoke)
            if (host != null && delved != null) {
                host.clearDelved()
                delved.forEach(host::addDelved)
                host.setCastFrom(castFrom)
            }
            usedToPay.forEach { (card, wasUsed) -> card.setUsedToPay(wasUsed) }
        }
    }

    fun forgeManaCostToPairs(manaCost: ManaCost): List<Pair<ManaColor, Int>> = ManaColorMapping.deriveManaCostWithGenericLast(manaCost)

    fun addManaCostFromForge(
        manaCost: ManaCost,
        actionBuilder: Action.Builder,
        abilityGrpId: Int? = null,
        specs: List<ManaCostSpecType> = emptyList(),
    ) {
        forgeManaCostToRequirements(manaCost, abilityGrpId, specs).forEach(actionBuilder::addManaCost)
    }

    fun forgeManaCostToRequirements(
        manaCost: ManaCost,
        abilityGrpId: Int? = null,
        specs: List<ManaCostSpecType> = emptyList(),
    ): List<ManaRequirement> {
        if (manaCost.none { ManaColorMapping.requirementColors(it).size > 1 }) {
            return aggregatedManaRequirements(manaCost, abilityGrpId, specs)
        }
        val result = mutableListOf<ManaRequirement>()
        // Arena serializes the generic portion before colored/hybrid pips.
        // Forge's shard iteration is not stable with respect to that wire
        // order after cost adjustments (for example Eddymurk Crab commonly
        // arrives as U,U,1). Keep the requirement order canonical so every
        // action rail backed by this conversion renders the same effective cost.
        addGenericRequirement(manaCost, abilityGrpId, specs, result)
        for (shard in manaCost) {
            val colors = ManaColorMapping.requirementColors(shard)
            if (colors.isEmpty()) continue
            val req = ManaRequirement.newBuilder().setCount(1)
            req.addAllColor(colors)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            req.addAllSpecs(specs)
            result.add(req.build())
        }
        return result
    }

    private fun aggregatedManaRequirements(
        manaCost: ManaCost,
        abilityGrpId: Int?,
        specs: List<ManaCostSpecType>,
    ): List<ManaRequirement> {
        val result = mutableListOf<ManaRequirement>()
        addGenericRequirement(manaCost, abilityGrpId, specs, result)
        for ((color, count) in ManaColorMapping.colorCounts(manaCost)) {
            val req = ManaRequirement.newBuilder().addColor(color).setCount(count)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            req.addAllSpecs(specs)
            result.add(req.build())
        }
        return result
    }

    private fun addGenericRequirement(
        manaCost: ManaCost,
        abilityGrpId: Int?,
        specs: List<ManaCostSpecType>,
        result: MutableList<ManaRequirement>,
    ) {
        val generic = manaCost.genericCost
        if (generic > 0) {
            val req = ManaRequirement.newBuilder().addColor(ManaColor.Generic).setCount(generic)
            if (abilityGrpId != null) req.setAbilityGrpId(abilityGrpId)
            req.addAllSpecs(specs)
            result.add(req.build())
        }
    }

    /**
     * Cost annotations that Forge exposes as first-class cost metadata.
     * Waterbend is the only ManaCostSpecType currently represented by Forge;
     * the other protocol values have no corresponding Forge cost accessor.
     */
    fun manaCostSpecs(cost: forge.game.cost.Cost?): List<ManaCostSpecType> =
        if (cost?.getMaxWaterbend() != null) listOf(ManaCostSpecType.Waterbend) else emptyList()

    fun producedToManaColor(produced: String): ManaColor? = ManaColorMapping.fromProduced(produced)
}
