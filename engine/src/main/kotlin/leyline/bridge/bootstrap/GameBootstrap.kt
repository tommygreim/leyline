package leyline.bridge.bootstrap

import forge.ai.LobbyPlayerAi
import forge.card.CardDb
import forge.deck.Deck
import forge.game.Game
import forge.game.GameRules
import forge.game.GameStage
import forge.game.GameType
import forge.game.Match
import forge.game.phase.PhaseType
import forge.game.player.RegisteredPlayer
import forge.gui.GuiBase
import forge.gui.interfaces.IGuiGame
import forge.localinstance.properties.ForgePreferences.FPref
import forge.model.FModel
import forge.player.GamePlayerUtil
import forge.player.LobbyPlayerHuman
import forge.player.PlayerControllerHuman
import forge.util.Lang
import forge.util.Localizer
import leyline.bridge.forge.HeadlessGuiBase
import leyline.bridge.types.SeatId
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/** True when game rules indicate a puzzle (either primary type or applied variant). */
val Game.isPuzzle: Boolean
    get() = rules.gameType == GameType.Puzzle || rules.hasAppliedVariant(GameType.Puzzle)

/** True when game rules indicate Commander. */
val Game.isCommander: Boolean
    get() = COMMANDER_GAME_TYPES.any { rules.gameType == it || rules.hasAppliedVariant(it) }

private val COMMANDER_GAME_TYPES = setOf(GameType.Commander, GameType.Brawl, GameType.Oathbreaker)

/** Commander-family game type slugs (used for deck validation, game creation). */
val COMMANDER_VARIANTS = setOf("commander", "brawl", "oathbreaker")

/** Arena Brawl starting life (vs 40 for Commander/EDH, 20 for Standard). */
const val BRAWL_STARTING_LIFE = 25

fun isCommanderVariant(gameType: String): Boolean = gameType.lowercase() in COMMANDER_VARIANTS

object GameBootstrap {
    private var initialized = false
    private val cardDbLatch = CountDownLatch(1)

    @Volatile private var cardDatabaseInitialized = false

    @Volatile private var cardDbInitError: Throwable? = null

    fun createGame(): Game {
        ensureLocalization()
        ensureGuiBase()

        val players = mutableListOf<RegisteredPlayer>()
        val deck = Deck()

        // Human + AI so startGameLoop creates bridges for the human seat.
        players.add(RegisteredPlayer(deck).setPlayer(LobbyPlayerHuman("player1")))
        players.add(RegisteredPlayer(deck).setPlayer(LobbyPlayerAi("player2", null)))

        val rules = GameRules(GameType.Constructed)
        val match = Match(rules, players, "Forge Web")
        val game = Game(players, rules, match)

        // Attach headless GUI so PCHuman callbacks don't NPE
        val humanController = game.players.firstOrNull()?.controller
        if (humanController is PlayerControllerHuman && humanController.gui == null) {
            humanController.gui = headlessGuiGame()
        }

        val activePlayer = game.players.first()
        game.age = GameStage.Play
        game.phaseHandler.devModeSet(PhaseType.MAIN1, activePlayer)
        game.phaseHandler.onStackResolved()

        game.updateTurnForView()
        game.updatePhaseForView()
        game.updatePlayerTurnForView()

        return game
    }

    /** Lightweight placeholder for lobby rooms — two AI players, no GUI init. */
    fun createLobbyPlaceholder(): Game {
        ensureLocalization()
        val deck = Deck()
        val players = mutableListOf<RegisteredPlayer>()
        players.add(RegisteredPlayer(deck).setPlayer(LobbyPlayerAi("p1", null)))
        players.add(RegisteredPlayer(deck).setPlayer(LobbyPlayerAi("p2", null)))
        val rules = GameRules(GameType.Constructed)
        val match = Match(rules, players, "Lobby")
        return Game(players, rules, match)
    }

    fun createPuzzleGame(controlledSeat: SeatId = SeatId(1)): Game {
        ensureLocalization()
        require(controlledSeat.value in 1..2) { "Puzzle controlled seat must be 1 or 2" }
        val players = mutableListOf<RegisteredPlayer>()
        val deck = Deck()

        val human =
            RegisteredPlayer(deck)
                .setPlayer(GamePlayerUtil.getGuiPlayer())
        human.startingHand = 0

        val ai =
            RegisteredPlayer(deck)
                .setPlayer(LobbyPlayerAi("AI", null))
        ai.startingHand = 0

        if (controlledSeat.value == 1) {
            players.add(human)
            players.add(ai)
        } else {
            players.add(ai)
            players.add(human)
        }

        val rules = GameRules(GameType.Puzzle)
        val match = Match(rules, players, "Forge Web")
        val game = Game(players, rules, match)

        // Puzzle setup relies on a GUI-tagged human controller. In headless mode,
        // attach a no-op GUI so controller callbacks (mana pool/current player) don't crash.
        val humanController = game.players.firstOrNull { it.lobbyPlayer !is LobbyPlayerAi }?.controller
        if (humanController is PlayerControllerHuman && humanController.gui == null) {
            humanController.gui = headlessGuiGame()
        }

        return game
    }

    /**
     * Create a constructed game (human vs AI) with decks on [RegisteredPlayer].
     *
     * Zones are populated by the engine via [Match.startGame] when
     * [leyline.bridge.coord.GameLoopController.start] is called.
     */
    fun createConstructedGame(
        humanDeck: Deck,
        aiDeck: Deck,
    ): Game {
        ensureLocalization()

        val players = mutableListOf<RegisteredPlayer>()

        val human =
            RegisteredPlayer(humanDeck)
                .setPlayer(GamePlayerUtil.getGuiPlayer())
        players.add(human)

        val ai =
            RegisteredPlayer(aiDeck)
                .setPlayer(LobbyPlayerAi("AI", null))
        players.add(ai)

        val rules = GameRules(GameType.Constructed)
        val match = Match(rules, players, "Forge Web")
        val game = Game(players, rules, match)

        // Attach headless GUI to human controller (same as puzzle flow)
        val humanController = game.players.firstOrNull()?.controller
        if (humanController is PlayerControllerHuman && humanController.gui == null) {
            humanController.gui = headlessGuiGame()
        }

        return game
    }

    /** Constructed or Commander game with two independent GUI-compatible human controllers. */
    fun createHumanVsHumanGame(
        deck1: Deck,
        deck2: Deck,
        variant: String? = null,
    ): Game {
        ensureLocalization()
        val gameType = variant?.let(::resolveCommanderVariant) ?: GameType.Constructed
        val players =
            listOf(deck1, deck2).mapIndexed { index, deck ->
                val registered = if (variant == null) RegisteredPlayer(deck) else RegisteredPlayer.forCommander(deck)
                registered.setPlayer(LobbyPlayerHuman("Player ${index + 1}"))
                if (gameType == GameType.Brawl) registered.startingLife = BRAWL_STARTING_LIFE
                registered
            }
        val rules = GameRules(gameType)
        if (variant != null) rules.addAppliedVariant(gameType)
        val match = Match(rules, players, "Local two-player match")
        return Game(players, rules, match).also { game ->
            game.players.forEach { player ->
                (player.controller as? PlayerControllerHuman)?.gui = headlessGuiGame()
            }
        }
    }

    fun createCommanderGame(
        humanDeck: Deck,
        aiDeck: Deck,
        variant: String = "commander",
    ): Game {
        ensureLocalization()

        val gameType = resolveCommanderVariant(variant)
        val isBrawl = gameType == GameType.Brawl
        val players = mutableListOf<RegisteredPlayer>()

        val human =
            RegisteredPlayer
                .forCommander(humanDeck)
                .setPlayer(GamePlayerUtil.getGuiPlayer())
        if (isBrawl) human.startingLife = BRAWL_STARTING_LIFE
        players.add(human)

        val ai =
            RegisteredPlayer
                .forCommander(aiDeck)
                .setPlayer(LobbyPlayerAi("AI", null))
        if (isBrawl) ai.startingLife = BRAWL_STARTING_LIFE
        players.add(ai)

        val rules = GameRules(gameType)
        rules.addAppliedVariant(gameType)
        val match = Match(rules, players, "Forge Web")
        val game = Game(players, rules, match)

        // Attach headless GUI to human controller
        val humanController = game.players.firstOrNull()?.controller
        if (humanController is PlayerControllerHuman && humanController.gui == null) {
            humanController.gui = headlessGuiGame()
        }

        return game
    }

    /**
     * Create a constructed game between two AI players (mirror mode).
     * No human controller, no headless GUI — both seats get the engine's
     * default [forge.ai.PlayerControllerAi].
     */
    fun createAiVsAiGame(
        deck1: Deck,
        deck2: Deck,
    ): Game {
        ensureLocalization()

        val players = mutableListOf<RegisteredPlayer>()
        players.add(RegisteredPlayer(deck1).setPlayer(LobbyPlayerAi("AI 1", null)))
        players.add(RegisteredPlayer(deck2).setPlayer(LobbyPlayerAi("AI 2", null)))

        val rules = GameRules(GameType.Constructed)
        val match = Match(rules, players, "Forge Web")
        return Game(players, rules, match)
    }

    /**
     * Create a commander-variant game between two AI players (mirror mode).
     * Same as [createAiVsAiGame] but with Commander/Brawl/Oathbreaker rules.
     */
    fun createAiVsAiCommanderGame(
        deck1: Deck,
        deck2: Deck,
        variant: String = "commander",
    ): Game {
        ensureLocalization()

        val gameType = resolveCommanderVariant(variant)
        val isBrawl = gameType == GameType.Brawl
        val players = mutableListOf<RegisteredPlayer>()
        val p1 = RegisteredPlayer.forCommander(deck1).setPlayer(LobbyPlayerAi("AI 1", null))
        val p2 = RegisteredPlayer.forCommander(deck2).setPlayer(LobbyPlayerAi("AI 2", null))
        if (isBrawl) {
            p1.startingLife = BRAWL_STARTING_LIFE
            p2.startingLife = BRAWL_STARTING_LIFE
        }
        players.add(p1)
        players.add(p2)

        val rules = GameRules(gameType)
        rules.addAppliedVariant(gameType)
        val match = Match(rules, players, "Forge Web")
        return Game(players, rules, match)
    }

    /**
     * Finalize a pre-initialized game (puzzle/sandbox) for the game loop.
     * Sets age to Play and preserves the phase/player/turn set by
     * [Puzzle.applyGameOnThread] (via `ActivePhase` in the .pzl file).
     * Only defaults to MAIN1 if the puzzle didn't specify a phase.
     *
     * NOT used for constructed/commander — those go through
     * [Match.startGame] via [leyline.bridge.coord.GameLoopController.start].
     */
    fun finalizeForPuzzle(game: Game) {
        game.age = GameStage.Play

        // Puzzle.applyGameOnThread calls devModeSet with ActivePhase.
        // Only default to MAIN1 if the puzzle didn't specify a phase.
        if (game.phaseHandler.phase == null) {
            val activePlayer = game.players.first()
            game.phaseHandler.devModeSet(PhaseType.MAIN1, activePlayer, false, 1)
        }

        game.updateTurnForView()
        game.updatePhaseForView()
        game.updatePlayerTurnForView()
    }

    fun initializeLocalization() {
        ensureLocalization()
    }

    fun initializeCardDatabase(
        quiet: Boolean = false,
        lazyCards: Boolean = false,
    ) {
        if (quiet) CardDb.quietInit = true
        ensureCardDatabaseLoaded(lazyCards)
    }

    private fun ensureLocalization() {
        if (initialized) {
            return
        }

        // GameType enum uses Localizer during static init; headless boot must initialize first.
        Lang.createInstance("en-US")
        Localizer.getInstance().initialize("en-US", resolveLanguagesDir().toString())
        initialized = true
    }

    private fun resolveLanguagesDir(): Path = resolveForgeResource("forge-gui/res/languages") { Files.isDirectory(it) }

    private fun ensureGuiBase() {
        if (GuiBase.getInterface() == null) {
            GuiBase.setInterface(HeadlessGuiBase(resolveAssetsDir().toString()))
        }
    }

    private fun ensureCardDatabaseLoaded(lazyCards: Boolean) {
        if (cardDatabaseInitialized) {
            awaitAndRethrow()
            return
        }

        synchronized(this) {
            if (cardDatabaseInitialized) {
                awaitAndRethrow()
                return
            }

            try {
                ensureGuiBase()

                FModel.initialize(null) { preferences ->
                    preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, lazyCards)
                    preferences.setPref(FPref.UI_LANGUAGE, "en-US")
                    preferences.setPref(FPref.DECKGEN_CARDBASED, false)
                    null
                }
            } catch (e: Throwable) {
                cardDbInitError = e
                throw e
            } finally {
                cardDatabaseInitialized = true
                cardDbLatch.countDown()
            }
        }
    }

    /** Wait for background init and rethrow if it failed. */
    private fun awaitAndRethrow() {
        cardDbLatch.await()
        cardDbInitError?.let { throw IllegalStateException("Card DB init failed on background thread", it) }
    }

    private fun resolveAssetsDir(): Path = resolveForgeResource("forge-gui") { Files.isDirectory(it.resolve("res")) }

    private fun headlessGuiGame(): IGuiGame {
        ensureGuiBase()
        return GuiBase.getInterface().getNewGuiGame()
    }

    /** Map a lowercase variant string to the engine [GameType]. */
    private fun resolveCommanderVariant(variant: String): GameType =
        when (variant.lowercase()) {
            "commander" -> GameType.Commander
            "brawl" -> GameType.Brawl
            "oathbreaker" -> GameType.Oathbreaker
            else -> GameType.Commander
        }
}
