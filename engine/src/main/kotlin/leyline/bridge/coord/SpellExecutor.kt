package leyline.bridge.coord

import forge.game.Game
import forge.game.GameObject
import forge.game.cost.CostPayment
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import leyline.bridge.buildLandPlayAbility
import leyline.bridge.findCard
import leyline.bridge.forge.PlayerController
import leyline.bridge.getAllCastableAbilities
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.getPlayableManaAbilities
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.Target
import leyline.bridge.resolveTarget
import leyline.bridge.types.ForgeCardId
import org.slf4j.LoggerFactory

/**
 * Turns a [leyline.bridge.handoff.PlayerAction] into a Forge [SpellAbility] (or a mana resolution side-effect).
 *
 * One method per "actionable" `PlayerAction` variant — `CastSpell`,
 * `ActivateAbility`, `ActivateMana`, `PlayLand` — mapping to the Forge
 * representation the engine expects back from `chooseSpellAbilityToPlay`.
 *
 * `activateMana` is the one method that can emit a prompt: when a card has
 * multiple distinct mana abilities, the player must pick which to tap for.
 *
 * See [leyline.bridge.forge.PlayerController]'s KDoc for the coordinator/helper pattern this class
 * participates in.
 */
class SpellExecutor(
    private val game: Game,
    private val player: Player,
    private val bridge: InteractivePromptBridge,
) {
    private val log = LoggerFactory.getLogger(SpellExecutor::class.java)

    /**
     * Build the [SpellAbility] for a cast action, resolving target assignments.
     *
     * Picks the requested alternative by [abilityId] (Overload, Flashback, etc.)
     * when valid; otherwise falls back to the card's primary castable ability.
     */
    fun castSpell(
        cardId: ForgeCardId,
        abilityId: Int?,
        targets: List<Target>,
        offeredAbility: SpellAbility? = null,
    ): List<SpellAbility>? {
        val sa =
            if (offeredAbility != null) {
                offeredAbility
            } else {
                val card = findCard(game, cardId) ?: return null
                val candidates = getAllCastableAbilities(card, player)
                if (candidates.isEmpty()) return null
                if (abilityId != null && abilityId < candidates.size) candidates[abilityId] else candidates.first()
            }
        sa.setActivatingPlayer(player)
        applyTargets(sa, targets)
        return listOf(sa)
    }

    /** Build the [SpellAbility] for a non-mana activated ability, resolving targets. */
    fun activateAbility(
        cardId: ForgeCardId,
        abilityId: Int,
        targets: List<Target>,
        offeredAbility: SpellAbility? = null,
    ): List<SpellAbility>? {
        val sa =
            offeredAbility ?: run {
                val card = findCard(game, cardId) ?: return null
                getNonManaActivatedAbilities(card, player).getOrNull(abilityId) ?: return null
            }
        sa.setActivatingPlayer(player)
        applyTargets(sa, targets)
        return listOf(sa)
    }

    /**
     * Activate a mana ability: tap the permanent, use the selected ability when
     * provided, otherwise prompt for color if multiple abilities apply, pay the
     * cost, resolve to add mana to the pool.
     *
     * Unlike the other methods, this one fully resolves on the engine thread —
     * mana abilities do not use the stack, so the player retains priority and
     * the outer loop returns `continue` to `awaitAction`.
     */
    fun activateMana(
        cardId: ForgeCardId,
        abilityId: Int? = null,
        selectedColor: Byte? = null,
        offeredAbility: SpellAbility? = null,
    ): Boolean {
        val card = findCard(game, cardId) ?: return false
        val playableAbilities = offeredAbility?.let(::listOf) ?: getPlayableManaAbilities(card, player)
        if (playableAbilities.isEmpty()) return false
        log.debug("activateMana: {} ({} abilities)", card.name, playableAbilities.size)

        val manaAbility =
            if (abilityId != null && abilityId in playableAbilities.indices) {
                playableAbilities[abilityId]
            } else if (playableAbilities.size == 1) {
                playableAbilities.first()
            } else {
                // Multiple distinct mana abilities — prompt to pick which one.
                val labels =
                    playableAbilities.map { ability ->
                        ability.manaPart?.origProduced ?: "?"
                    }
                val optionsWithCancel = labels + "Cancel"
                val request =
                    PromptRequest(
                        promptType = "choose_one",
                        message = "Choose mana ability for ${card.name}",
                        options = optionsWithCancel,
                        min = 1,
                        max = 1,
                        defaultIndex = 0,
                    )
                val indices = bridge.requestChoice(request)
                val idx = indices.firstOrNull() ?: return false
                if (idx >= labels.size) return false // Cancel
                playableAbilities[idx]
            }

        manaAbility.setActivatingPlayer(player)

        // Pay costs via CostPayment (handles tap, sac, exile, etc.) then resolve.
        // For Combo mana (e.g. "W B"), resolve() triggers the engine's chooseColor
        // callback through HeadlessGuiBase → InteractivePromptBridge — one prompt, no duplication.
        val costs = manaAbility.payCosts
        if (costs != null) {
            val payment = CostPayment(costs, manaAbility)
            val decision = player.controller.getCostDecisionMaker(player, manaAbility, false)
            if (!payment.payCost(decision)) return false
        }
        try {
            val controller = player.controller as? PlayerController
            if (controller != null) {
                controller.withManaColorChoice(selectedColor) { manaAbility.resolve() }
            } else {
                manaAbility.resolve()
            }
        } catch (ex: Exception) {
            log.error("activateMana: resolve() failed for {}: {}", card.name, ex.message, ex)
            return false
        }
        log.debug("activateMana: {} resolved, pool={}", card.name, player.manaPool)
        return true
    }

    /** Build the [LandAbility] for a land-drop action. */
    fun playLand(
        cardId: ForgeCardId,
        selectedAbility: LandAbility? = null,
    ): List<SpellAbility>? {
        val card = findCard(game, cardId) ?: return null
        val landAbility = selectedAbility?.takeIf { it.hostCard.id == card.id } ?: buildLandPlayAbility(card, player) ?: return null
        return listOf(landAbility)
    }

    private fun applyTargets(
        sa: SpellAbility,
        targets: List<Target>,
    ) {
        if (targets.isEmpty() || !sa.usesTargeting()) return
        sa.resetTargets()
        for (t in targets) {
            val obj: GameObject? = resolveTarget(game, t)
            if (obj != null) sa.targets.add(obj)
        }
    }
}
