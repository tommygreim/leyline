package leyline.bridge

import forge.ai.ComputerUtilAbility
import forge.game.ability.ApiType
import forge.game.ability.effects.CharmEffect
import forge.game.keyword.Keyword
import forge.game.player.Player
import forge.game.spellability.SpellAbility

/** Shared executable-action checks for priority decisions and active action offers. */
internal object ActionAvailability {
    fun canExecute(
        sa: SpellAbility,
        player: Player,
    ): Boolean =
        ActionManaCosts.affordabilityProbe(
            probe = {
                NonInteractiveScope.bestEffort {
                    sa.activatingPlayer = player
                    sa.canPlay() &&
                        sa.payCosts?.canPay(sa, player, false) != false &&
                        hasLegalTargetsAndModes(sa) &&
                        canPayMana(sa, player)
                }
            },
            fallback = { false },
        )

    fun hasLegalTargetsAndModes(sa: SpellAbility): Boolean =
        ComputerUtilAbility.isFullyTargetable(sa) &&
            (sa.api != ApiType.Charm || CharmEffect.makePossibleOptions(sa).isNotEmpty())

    private fun canPayMana(
        sa: SpellAbility,
        player: Player,
    ): Boolean {
        val host = sa.hostCard
        val convoke = host?.hasKeyword(Keyword.CONVOKE) == true
        val improvise = host?.hasKeyword(Keyword.IMPROVISE) == true
        return if (convoke || improvise) {
            ActionManaCosts.canPayWithPaymentSourceReducer(sa, player, artifacts = improvise, creatures = convoke)
        } else {
            ActionManaCosts.canPayManaCost(sa, player) ||
                (sa.alternativeCost?.name == "Harmonize" && ActionManaCosts.canPayWithHarmonizeReduction(sa, player))
        }
    }
}
