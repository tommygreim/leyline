package leyline.bridge

import forge.game.Game
import forge.game.GameActionUtil
import forge.game.GameEntity
import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.game.spellability.OptionalCost
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import leyline.bridge.handoff.Target
import leyline.bridge.types.ForgeCardId

internal val searchableZones =
    listOf(
        ZoneType.Hand,
        ZoneType.Battlefield,
        ZoneType.Graveyard,
        ZoneType.Exile,
        ZoneType.Library,
        ZoneType.Command,
        ZoneType.Stack,
    )

fun findCard(
    game: Game,
    cardId: ForgeCardId,
): Card? = game.getCardsIn(searchableZones).firstOrNull { it.id == cardId.value }

internal fun resolveTarget(
    game: Game,
    target: Target,
): forge.game.GameObject? =
    when (target) {
        is Target.Card -> findCard(game, target.cardId)
        is Target.Player -> game.getPlayer(target.playerId.value)
    }

internal fun resolveAttackDefender(
    game: Game,
    attackingPlayer: Player,
    defender: Target?,
): GameEntity? =
    when (defender) {
        is Target.Card -> {
            val card = findCard(game, defender.cardId)
            if (card != null && card.isPlaneswalker && card.controller.isOpponentOf(attackingPlayer)) card else null
        }
        is Target.Player -> {
            val playerDefender = game.getPlayer(defender.playerId.value)
            if (playerDefender != null && playerDefender.isOpponentOf(attackingPlayer)) playerDefender else null
        }
        null -> attackingPlayer.opponents.firstOrNull()
    }

/**
 * All castable spell abilities for a card, including alternative costs
 * (Overload, Flashback, Escape, etc.). Stable ordering: base ability first,
 * then alt costs in engine order.
 *
 * [checkTiming] false skips the legality/timing filter — for embedded action
 * lists built while another player holds the turn, where the same ability
 * selection must yield the same displayed cost as the checked path.
 */
fun getAllCastableAbilities(
    card: Card,
    player: Player,
    checkTiming: Boolean = true,
): List<SpellAbility> {
    // Face-down cards in exile expose no current-state spells. Recover the
    // printed spells only for Foretell or an actual MayPlay grant (such as
    // Flameshape's Wizard-dependent permission); the normal Forge legality
    // filter below still decides whether each recovered SA is castable.
    val baseAbilities =
        if (card.isFaceDown &&
            card.isInZone(ZoneType.Exile) &&
            card.getSpells().isEmpty() &&
            (card.isForetold || card.mayPlay(player).isNotEmpty())
        ) {
            card.getOriginalState(forge.card.CardStateName.Original)?.nonManaAbilities?.filter { it.isSpell }
                ?: emptyList()
        } else {
            card.getSpells()
        }

    // No early-return for empty baseAbilities: the keyword-handSA appendage
    // (Plot/Foretell) and the Room unlock-SA appendage below add zone-specific
    // SAs that aren't in `card.getSpells()`. Bailing here would drop those
    // entire categories.
    val expanded = mutableListOf<SpellAbility>()
    val withAddCosts = mutableListOf<SpellAbility>()
    for (sa in baseAbilities) {
        sa.setActivatingPlayer(player)
        withAddCosts.addAll(GameActionUtil.getAdditionalCostSpell(sa))
    }
    for (sa in withAddCosts) {
        sa.setActivatingPlayer(player)
        val altCosts = GameActionUtil.getAlternativeCosts(sa, player, false)
        val (priority, other) =
            altCosts.partition { altSa ->
                sa.payCosts != null &&
                    sa.payCosts.isOnlyManaCost &&
                    altSa.payCosts != null &&
                    altSa.payCosts.isOnlyManaCost &&
                    sa.payCosts.totalMana.compareTo(altSa.payCosts.totalMana) == 1
            }
        expanded.addAll(priority)
        expanded.add(sa)
        expanded.addAll(other)

        for (optional in GameActionUtil.getOptionalCostValues(sa)) {
            if (optional.type != OptionalCost.Jumpstart && optional.type != OptionalCost.Retrace) continue
            val optionalSa = GameActionUtil.addOptionalCosts(sa, listOf(optional))
            optionalSa.setActivatingPlayer(player)
            expanded.add(optionalSa)
        }
    }

    // Plot and Foretell's hand SAs are added by Forge as KeywordInstance abilities
    // (CardFactoryUtil K:Plot: / K:Foretell:) and aren't returned by card.getSpells()
    // — append directly from card.spellAbilities so they're reachable through the
    // same index space the cast pathway uses (action emit, alt-cost resolution,
    // SpellExecutor.castSpell).
    //
    // Disguise's hand SA is similarly KeywordInstance-attached: Forge's
    // `abilityCastFaceDown(card, intrinsic, "Disguise")` builds a face-down
    // Spell with `setCastFaceDown(true)`. The face-up turn-face-up SA uses the
    // activation path below, not the castable-spell index space.
    //
    // Dedup by SA reference: `card.getSpells()` may already return the
    // disguise face-down SA on some printings (KeywordInstance.addSpellAbility
    // can propagate into the host card's intrinsic SA list), in which case
    // the SA appears once via `baseAbilities` and once via the keyword scan.
    // Without the `!in expanded` filter the cast offer surfaces twice.
    appendKeywordHandSAs(card, expanded)
    appendMdfcBackFaceSAs(card, player, expanded)

    // Room door-unlock SAs aren't in card.getSpells() — Forge stores them on
    // Card.unlockAbilities[CardStateName] keyed by LeftSplit / RightSplit. Append
    // each locked door's unlock SA so CastLeftRoom / CastRightRoom share the
    // same index space as the regular cast pathway. From hand both doors are
    // locked; from battlefield only the side(s) not yet unlocked surface here.
    appendRoomDoorSAs(card, player, expanded)

    if (!checkTiming) return expanded
    return expanded.filter { it.canPlay() && it.canCastTiming(player) }
}

private fun appendKeywordHandSAs(
    card: Card,
    expanded: MutableList<SpellAbility>,
) {
    val keywordHandSAs =
        card.spellAbilities.filter {
            (it.isPlotting || it.isForetelling || it.isCastFaceDown) &&
                expanded.none { existing -> existing === it }
        }
    expanded.addAll(keywordHandSAs)
}

private fun appendMdfcBackFaceSAs(
    card: Card,
    player: Player,
    expanded: MutableList<SpellAbility>,
) {
    if (!card.isModal || !card.hasState(forge.card.CardStateName.Backside)) return
    for (sa in card.getState(forge.card.CardStateName.Backside).spellAbilities) {
        if (sa.isSpell && !sa.isLandAbility && expanded.none { existing -> existing === sa }) {
            sa.setActivatingPlayer(player)
            // Forge applies a MayPlay grant by cloning a spell ability with
            // the permission attached. Merely appending the printed backside
            // SA leaves its Hand-only restriction in place in exile/graveyard.
            if (!card.isInZone(ZoneType.Hand) && card.mayPlay(player).isNotEmpty()) {
                GameActionUtil
                    .getAlternativeCosts(sa, player, false)
                    .onEach { it.setActivatingPlayer(player) }
                    .forEach(expanded::add)
            }
            expanded.add(sa)
        }
    }
}

private fun appendRoomDoorSAs(
    card: Card,
    player: Player,
    expanded: MutableList<SpellAbility>,
) {
    val printedRoom = card.getOriginalState(forge.card.CardStateName.Original)?.type?.hasSubtype("Room") == true
    if (!card.isRoom && !(printedRoom && card.isFaceDown && card.isInZone(ZoneType.Exile))) return
    for (lockedState in card.lockedRooms) {
        val unlockSa = card.getUnlockAbility(lockedState) ?: continue
        unlockSa.setActivatingPlayer(player)
        expanded.add(unlockSa)
    }
}

fun buildMdfcBackLandAbility(
    card: Card,
    player: Player? = null,
): LandAbility? {
    if (!card.isModal || !card.hasState(forge.card.CardStateName.Backside)) return null
    val backState = card.getState(forge.card.CardStateName.Backside)
    if (!backState.type.isLand) return null
    return LandAbility(card, backState).also { ability ->
        player?.let { p ->
            ability.activatingPlayer = p
            card.mayPlay(p).firstOrNull { it.grantsZonePermissions() }?.let(ability::setMayPlay)
        }
    }
}

/**
 * Build a land-play ability in the same permission context Forge uses for a
 * card played outside its owner's hand.
 *
 * [LandAbility] starts with a Hand restriction.  Forge normally replaces that
 * restriction with the [forge.game.card.CardPlayOption] attached to a
 * `MayPlay` static ability.  Constructing a bare `LandAbility` for a land in
 * a graveyard/exile therefore makes `Player.canPlayLand` reject it even when
 * the card has a live "you may play" permission (Tablet of Discovery is one
 * example).  Carrying the zone-granting option is required both while
 * advertising an action and when executing it.
 */
fun buildLandPlayAbility(
    card: Card,
    player: Player,
): LandAbility? {
    val originalState = card.getOriginalState(forge.card.CardStateName.Original)
    val mayPlay = card.mayPlay(player).firstOrNull { it.grantsZonePermissions() }
    val ability =
        if (card.isLand) {
            LandAbility(card, card.currentState)
        } else if (card.isFaceDown && card.isInZone(ZoneType.Exile) && mayPlay != null && originalState?.type?.isLand == true) {
            // A face-down exiled land has no Land type in its current state,
            // but a MayPlay grant can still let its controller play the
            // printed face (for example, Flameshape with a Wizard in play).
            LandAbility(card, originalState)
        } else {
            buildMdfcBackLandAbility(card) ?: return null
        }
    ability.activatingPlayer = player
    mayPlay?.let(ability::setMayPlay)
    return ability
}

fun chooseCastAbility(
    card: Card,
    player: Player,
    checkTiming: Boolean = true,
): SpellAbility? {
    val all = getAllCastableAbilities(card, player, checkTiming)
    if (all.isEmpty()) return null
    return all.firstOrNull { it.hasParam("WithoutManaCost") } ?: all.first()
}

/** Human-readable label for a castable ability (e.g. "Overload — {1}{R}"). */
internal fun describeCastAbility(sa: SpellAbility): String {
    val cost = sa.payCosts?.toSimpleString().orEmpty()
    val altCost = sa.alternativeCost
    return if (altCost != null) {
        "$altCost — $cost"
    } else {
        "${sa.hostCard?.name ?: "Cast"} — $cost"
    }
}

fun getNonManaActivatedAbilities(
    card: Card,
    player: Player,
): List<SpellAbility> {
    val abilities = mutableListOf<SpellAbility>()
    val sourceAbilities = card.spellAbilities.toMutableList()
    val sourceIds = sourceAbilities.map { it.id }.toMutableSet()
    for (ability in card.allSpellAbilities.orEmpty()) {
        val isNonManaActivatedAbility = ability.isActivatedAbility && !ability.isManaAbility()
        if (ability.id !in sourceIds && (isNonManaActivatedAbility || isReconfigureUnattach(ability))) {
            sourceAbilities.add(ability)
            sourceIds.add(ability.id)
        }
    }
    for (ability in card.getAllPossibleAbilities(player, false)) {
        if (ability.id !in sourceIds && ability.isTurnFaceUp) {
            sourceAbilities.add(ability)
            sourceIds.add(ability.id)
        }
    }
    for (ability in sourceAbilities) {
        ability.setActivatingPlayer(player)
        // Forge's generic `canPlay()` check does not itself keep an intrinsic
        // battlefield activation off the hand rail.  The default activation
        // zone is Battlefield; explicit `ActivationZone$` values opt into
        // Channel/cycling, Unearth, command-zone abilities, and similar
        // non-battlefield paths.  Without this guard a hand card with both an
        // Equip keyword and a discard activation can advertise Equip instead
        // of its legal hand activation (Mjölnir, Hammer of Thor).
        val activationZones =
            ability
                .getParam("ActivationZone")
                ?.takeIf { it.isNotBlank() }
                ?.let(ZoneType::listValueOf)
                ?: listOf(ZoneType.Battlefield)
        if (activationZones.none(card::isInZone)) continue
        val isSpecialTurnFaceUp =
            ability.isTurnFaceUp && card.isFaceDown && card.isInZone(ZoneType.Battlefield)
        if (!ability.isActivatedAbility && !isSpecialTurnFaceUp) continue
        if (ability.isManaAbility()) continue
        if (isReconfigureAttach(ability) && card.isAttachedToEntity) continue
        if (isReconfigureUnattach(ability) && !card.isAttachedToEntity) continue
        abilities.add(ability)
    }
    return abilities
}

private fun isReconfigureAttach(ability: SpellAbility): Boolean =
    ability.api == ApiType.Attach && ability.getParam("PrecostDesc") == "Reconfigure"

private fun isReconfigureUnattach(ability: SpellAbility): Boolean =
    ability.api == ApiType.Unattach && ability.getParam("PrecostDesc") == "Reconfigure"

fun getPlayableManaAbilities(
    card: Card,
    player: Player,
): List<SpellAbility> {
    val abilities = mutableListOf<SpellAbility>()
    for (ability in card.manaAbilities) {
        ability.setActivatingPlayer(player)
        if (ability.canPlay()) abilities.add(ability)
    }
    return abilities
}

/** Hand is always castable; other zones allowed if the card has mayPlay grants for the given player. */
internal fun canCastFromZone(
    card: Card,
    zone: ZoneType?,
    player: Player = card.controller,
): Boolean {
    if (zone == null) return false
    if (zone == ZoneType.Hand) return true
    return card.mayPlay(player).isNotEmpty()
}

internal fun extractLoyaltyCost(ability: SpellAbility): String? {
    val costStr = ability.payCosts?.toSimpleString() ?: return null
    val match = Regex("""^[+-]?\d+$""").find(costStr.trim())
    if (match != null) return costStr.trim()
    return null
}

internal fun abilityLabel(ability: SpellAbility): String {
    val description = ability.description?.trim()?.takeIf { it.isNotBlank() }
    if (description != null) return description
    val stackDescription = ability.stackDescription?.trim()?.takeIf { it.isNotBlank() }
    if (stackDescription != null) return stackDescription
    return "Activated ability"
}
