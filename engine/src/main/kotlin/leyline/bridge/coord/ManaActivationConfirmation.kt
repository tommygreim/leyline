package leyline.bridge.coord

import forge.ai.ComputerUtilMana
import forge.game.card.Card
import forge.game.cost.CostDiscard
import forge.game.cost.CostExile
import forge.game.cost.CostPayLife
import forge.game.cost.CostSacrifice
import forge.game.mana.ManaCostBeingPaid
import forge.game.player.Player
import forge.game.spellability.SpellAbility

/**
 * Finds an irreversible non-mana cost in the source abilities selected by
 * Forge's automatic mana solver.
 *
 * The client protocol's `RequiresConfirmation` enum names the four resource
 * consuming families Arena treats as confirmation-worthy: sacrifice, pay
 * life, discard, and exile.  Tap/untap costs are deliberately not included;
 * those are ordinary mana-source activation state and are not irreversible
 * resource consumption.
 *
 * The solver's payment-plan projection returns the exact selected
 * SpellAbility instances. This avoids false prompts for cards such as
 * Phyrexian Tower, where Forge may select its ordinary mana ability while a
 * separate sacrifice-producing ability is also present.
 */
internal object ManaActivationConfirmation {
    fun selectedRiskySource(
        toPay: ManaCostBeingPaid,
        ability: SpellAbility,
        player: Player,
        effect: Boolean,
    ): Card? = selectedRiskyAbility(toPay, ability, player, effect)?.hostCard

    /** Classify only the exact source ability selected by Forge's dry run. */
    fun selectedRiskyAbility(
        toPay: ManaCostBeingPaid,
        ability: SpellAbility,
        player: Player,
        effect: Boolean,
    ): SpellAbility? =
        ComputerUtilMana
            .getManaPaymentPlan(ManaCostBeingPaid(toPay), ability, player, effect)
            ?.firstOrNull(::requiresConfirmation)

    fun requiresConfirmation(manaAbility: SpellAbility): Boolean =
        manaAbility.payCosts?.costParts?.any { part ->
            part is CostSacrifice ||
                part is CostPayLife ||
                part is CostDiscard ||
                part is CostExile
        } == true
}
