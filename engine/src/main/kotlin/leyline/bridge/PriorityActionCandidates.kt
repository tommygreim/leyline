package leyline.bridge

import forge.game.Game
import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.LandAbility
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType

/** One Forge-owned traversal of candidates available during a priority window. */
class PriorityActionCandidates private constructor(
    private val byCardId: Map<Int, CardCandidates>,
) {
    data class CardCandidates(
        val card: Card,
        val casts: List<SpellAbility>,
        val activations: List<SpellAbility>,
        val manaAbilities: List<SpellAbility>,
        val landAbility: LandAbility?,
        val mdfcLandAbility: LandAbility?,
    )

    fun forCard(card: Card): CardCandidates =
        byCardId[card.id]
            ?: CardCandidates(card, emptyList(), emptyList(), emptyList(), null, null)

    fun hasLegalNonManaAction(
        player: Player,
        isOwnTurn: Boolean = player.game.phaseHandler.playerTurn == player,
    ): Boolean =
        byCardId.values.any { candidate ->
            candidate.casts.any { ActionAvailability.canExecute(it, player) } ||
                candidate.activations.any { ActionAvailability.canExecute(it, player) } ||
                (isOwnTurn && candidate.landAbility?.let { player.canPlayLand(candidate.card, false, it) } == true) ||
                (isOwnTurn && candidate.mdfcLandAbility?.canPlay() == true)
        }

    companion object {
        fun query(
            game: Game,
            player: Player,
        ): PriorityActionCandidates {
            val handIds = player.getZone(ZoneType.Hand).cards.mapTo(mutableSetOf()) { it.id }
            val battlefieldIds = player.getCardsIn(ZoneType.Battlefield).mapTo(mutableSetOf()) { it.id }
            return PriorityActionCandidates(
                candidateCards(game, player).associate { card ->
                    card.id to buildCandidate(card, player, handIds, battlefieldIds)
                },
            )
        }

        fun hasLegalNonManaAction(
            game: Game,
            player: Player,
            isOwnTurn: Boolean = game.phaseHandler.playerTurn == player,
        ): Boolean {
            val handIds = player.getZone(ZoneType.Hand).cards.mapTo(mutableSetOf()) { it.id }
            val battlefieldIds = player.getCardsIn(ZoneType.Battlefield).mapTo(mutableSetOf()) { it.id }
            return candidateCards(game, player).any { card ->
                val candidate = buildCandidate(card, player, handIds, battlefieldIds)
                candidate.casts.any { ActionAvailability.canExecute(it, player) } ||
                    candidate.activations.any { ActionAvailability.canExecute(it, player) } ||
                    (isOwnTurn && candidate.landAbility?.let { player.canPlayLand(card, false, it) } == true) ||
                    (isOwnTurn && candidate.mdfcLandAbility?.canPlay() == true)
            }
        }

        private fun candidateCards(
            game: Game,
            player: Player,
        ): List<Card> =
            (
                player.getZone(ZoneType.Hand).cards +
                    player.getCardsIn(ZoneType.Battlefield) +
                    game.getCardsIn(
                        listOf(
                            ZoneType.Graveyard,
                            ZoneType.Exile,
                            ZoneType.Command,
                        ),
                    )
            ).distinctBy { it.id }

        private fun buildCandidate(
            card: Card,
            player: Player,
            handIds: Set<Int>,
            battlefieldIds: Set<Int>,
        ): CardCandidates {
            // A land's MayPlay option must travel with the LandAbility.  A
            // bare LandAbility retains Forge's Hand restriction and makes a
            // legal graveyard/exile land look non-executable.
            val landAbility =
                if (card.id in handIds || card.mayPlay(player).any { it.grantsZonePermissions() }) {
                    buildLandPlayAbility(card, player)
                } else {
                    null
                }
            val mdfcLandAbility =
                if (card.id in handIds || card.mayPlay(player).any { it.grantsZonePermissions() }) {
                    buildMdfcBackLandAbility(card, player)
                } else {
                    null
                }
            return CardCandidates(
                card = card,
                casts = getAllCastableAbilities(card, player),
                activations = getNonManaActivatedAbilities(card, player),
                manaAbilities = if (card.id in battlefieldIds) getPlayableManaAbilities(card, player) else emptyList(),
                landAbility = landAbility,
                mdfcLandAbility = mdfcLandAbility,
            )
        }
    }
}
