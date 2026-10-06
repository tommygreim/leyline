package leyline.bridge.forge

import forge.StaticData
import forge.ai.PlayerControllerAi
import forge.card.ICardFace
import forge.game.Game
import forge.game.card.CardCollectionView
import forge.game.card.CardView
import forge.game.player.Player
import forge.game.player.PlayerView
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.RevealZone
import leyline.bridge.types.SeatId
import java.util.function.Predicate

/** Native AI controller that also publishes reveals visible to its tracked viewer. */
class RevealTrackingAiController(
    private val trackedGame: Game,
    player: Player,
    private val promptBridge: InteractivePromptBridge,
    private val viewerSeatId: SeatId,
    private val currentSourceCardId: () -> ForgeCardId? = {
        trackedGame.stack
            .peekAbility()
            ?.hostCard
            ?.id
            ?.takeIf { it > 0 }
            ?.let(::ForgeCardId)
    },
) : PlayerControllerAi(trackedGame, player, player.lobbyPlayer) {
    /**
     * Forge's generic AI fallback ignores the effect's name predicate. On the engine
     * thread, preserve legal strategic choices and replace illegal ones with legal
     * known cards, falling back to the full catalog rather than battlefield-only names.
     */
    override fun chooseCardName(
        sa: SpellAbility,
        predicate: Predicate<ICardFace>,
        valid: String,
        message: String?,
    ): String {
        val cards = StaticData.instance().commonCards
        val proposed = super.chooseCardName(sa, predicate, valid, message)
        if (cards.getFaceByName(proposed)?.let(predicate::test) == true) return proposed
        return trackedGame.cardsInGame
            .mapNotNull { cards.getFaceByName(it.name) }
            .firstOrNull(predicate::test)
            ?.name
            ?: cards
                .streamAllFaces()
                .filter(predicate)
                .sorted()
                .findFirst()
                .orElseThrow()
                .name
    }

    override fun reveal(
        cards: CardCollectionView,
        zone: ZoneType,
        owner: Player,
        messagePrefix: String?,
        addSuffix: Boolean,
    ) {
        super.reveal(cards, zone, owner, messagePrefix, addSuffix)
        record(cards.map { ForgeCardId(it.id) }, zone, seatOf(owner))
    }

    override fun reveal(
        cards: List<CardView>,
        zone: ZoneType,
        owner: PlayerView,
        messagePrefix: String?,
        addSuffix: Boolean,
    ) {
        super.reveal(cards, zone, owner, messagePrefix, addSuffix)
        val ownerSeat =
            trackedGame.players
                .indexOfFirst { owner.isLobbyPlayer(it.lobbyPlayer) }
                .takeIf { it >= 0 }
                ?.let { SeatId(it + 1) } ?: return
        record(cards.map { ForgeCardId(it.id) }, zone, ownerSeat)
    }

    private fun record(
        cardIds: List<ForgeCardId>,
        zone: ZoneType,
        ownerSeatId: SeatId,
    ) {
        promptBridge.recordReveal(cardIds, ownerSeatId, viewerSeatId, revealZone(zone), currentSourceCardId())
    }

    private fun seatOf(owner: Player): SeatId = SeatId(trackedGame.players.indexOf(owner) + 1)

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun revealZone(zone: ZoneType): RevealZone? =
        when (zone) {
            ZoneType.Hand -> RevealZone.HAND
            ZoneType.Library -> RevealZone.LIBRARY
            ZoneType.Sideboard -> RevealZone.SIDEBOARD
            ZoneType.Graveyard -> RevealZone.GRAVEYARD
            ZoneType.Battlefield -> RevealZone.BATTLEFIELD
            ZoneType.Exile -> RevealZone.EXILE
            ZoneType.Command -> RevealZone.COMMAND
            ZoneType.Stack -> RevealZone.STACK
            else -> null
        }
}
