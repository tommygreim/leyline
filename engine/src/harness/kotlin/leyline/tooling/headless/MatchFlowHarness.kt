package leyline.tooling.headless

import forge.ai.LobbyPlayerAi
import forge.game.Game
import forge.game.card.Card
import forge.game.player.Player
import forge.game.spellability.SpellAbility
import forge.game.zone.ZoneType
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.coord.GameLoopPoller
import leyline.bridge.getNonManaActivatedAbilities
import leyline.bridge.getPlayableManaAbilities
import leyline.bridge.handoff.PendingActionKind
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.config.EngineSettings
import leyline.config.RuntimeMatchConfig
import leyline.config.RuntimeMatchConfigRegistry
import leyline.domain.deck.DeckSource
import leyline.game.bundle.InvariantSelection
import leyline.game.data.BasicLandAbilities
import leyline.game.data.CardRepository
import leyline.game.generator.PuzzleSource
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GsmSnapshot
import leyline.game.state.GameBridge
import leyline.infra.ListMessageSink
import leyline.infra.MatchOutput
import leyline.infra.MessageSink
import leyline.match.MatchConnection
import leyline.match.MatchRegistry
import leyline.match.MatchSession
import leyline.match.isGameplayResponse
import wotc.mtgo.gre.external.messaging.Messages.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Test harness driving a real match through [MatchConnection].
 *
 * Client responses enter through the same transport-neutral connection used by
 * network handlers. Engine handles remain available for state-oriented probes.
 *
 * @param validating when true (default), wraps the sink in [ValidatingMessageSink]
 *                   to get automatic invariant checking on every message
 */
@Suppress("LargeClass") // Test harness grows linearly with prompt-type coverage; refactor is its own task.
class MatchFlowHarness(
    private val seed: Long = 42L,
    private val deckList: String? = null,
    private val opponentDeckList: String? = null,
    validating: Boolean = true,
    private val validation: InvariantSelection = defaultValidation(validating),
    private val validationStrict: Boolean = true,
    private val engineSettings: EngineSettings =
        EngineSettings(
            aiSpeed = 0.0,
            // Fail fast in tests. Local gameplay leaves the human bridge
            // timeout disabled; here the engine
            // Candidate projection can traverse a full action set under suite
            // load; this remains short enough to surface a stalled game loop.
            bridgeTimeoutMs = 30_000L,
            aiTurnWaitMs = 2_000L,
            mulliganWaitMs = 2_000L,
        ),
    private val variant: String? = null,
    /**
     * Bypass the YAML-fixture-backed [TestCardRegistry] when set. The harness
     * walks the deck list, asks Forge to load each card by name (failing
     * loudly if any card is unknown), and uses the supplied repository for
     * card-identity lookups instead of the fixture-derived in-memory store.
     *
     * Default null preserves the existing fixture-pinned behavior the
     * conformance suite relies on. SimClient passes a SQLite-backed repo so
     * arbitrary decks work without per-card fixtures.
     */
    private val cardRepositoryOverride: CardRepository? = null,
    val responseMode: HeadlessResponseMode = HeadlessResponseMode.AutoForTests,
    private val fullControl: Boolean = false,
) {
    companion object {
        private const val DEFAULT_DECK = """
            20 Llanowar Elves
            4 Elvish Mystic
            4 Giant Growth
            32 Forest
        """

        fun defaultValidation(validating: Boolean): InvariantSelection =
            if (validating) {
                InvariantSelection.protocolFacts()
            } else {
                InvariantSelection.none("legacy disabled validation flag")
            }
    }

    private val matchId = "test-match"
    private val seatId = SeatId(1)
    private val opponentSeatId = SeatId(2)

    private val registry = MatchRegistry()
    val sink = ListMessageSink()

    /** Validating decorator — null only when the selected validation set is empty. */
    val validatingSink: ValidatingMessageSink? =
        if (!validation.isEmpty()) ValidatingMessageSink(sink, strict = validationStrict, selection = validation) else null

    /** The [MessageSink] receiving local-seat connection output. */
    private val effectiveSink get() = validatingSink ?: sink

    val accumulator = ClientAccumulator()
    val allMessages = mutableListOf<GREToClientMessage>()
    private val messageLog = MatchFlowMessageLog(allMessages)

    private val combatDriver =
        MatchFlowCombatDriver(
            seatId = seatId,
            bridge = { bridge },
            submitOperation = { message, description, completeWhen ->
                submitAndAwaitClientResult(message, description, completeWhen = completeWhen)
            },
            submitAndAwaitPromptOperation = { message, description, predicate, completeWhen ->
                submitAndAwaitClientResult(message, description, predicate, completeWhen = completeWhen)
            },
            messageSnapshot = { messageLog.snapshot() },
            messagesSince = { snapshot -> messageLog.since(snapshot) },
            submitWithGsId = { msg -> submitWithGsId(msg) },
        )

    /** All raw messages (SettingsResp, MatchCompleted, etc.) sent via [MessageSink.sendRaw]. */
    val allRawMessages = mutableListOf<MatchServiceToClientMessage>()

    /** When set, the next auto-accepted optional-action prompt declines instead.
     *  Use [declineNextOptionalAction] to set this. Cleared after one use. */
    private var nextOptionalResponse: OptionResponse? = null
    private var holdNextOptionalResponse = false
    private var nextNumericInputValue: Int? = null
    private lateinit var localConnection: MatchConnection
    private lateinit var localOutput: SinkMatchOutput
    private var familiarConnection: MatchConnection? = null

    lateinit var bridge: GameBridge
        private set
    private lateinit var gameRef: Game

    /**
     * Bring the match up in whichever mode the caller described, then advance
     * to the first action phase.
     *
     * At most one source may be non-null. All null starts a normal game
     * (mulligan + keep) from the harness's deck lists.
     *
     * @param puzzleText full `.pzl` text (metadata + state)
     * @param puzzleResource shared identity path or test-private classpath resource, e.g. `data/puzzles/bolt-face.pzl`
     */
    fun connect(
        puzzleText: String? = null,
        puzzleResource: String? = null,
        aiScript: List<ScriptedAction>? = null,
    ) {
        require(puzzleText == null || puzzleResource == null) {
            "Give at most one puzzle source: inline text or classpath resource"
        }
        when {
            puzzleText != null -> connectAndKeepPuzzleText(puzzleText, aiScript)
            puzzleResource != null -> connectAndKeepPuzzle(puzzleResource, aiScript)
            else -> connectAndKeep(aiScript)
        }
        cacheSeatPlayers()
    }

    /**
     * Resolve both seat handles while the game is still standing.
     *
     * [human] and [ai] memoize on first read, and `GameBridge.shutdown` clears
     * the seat map, so a spec that first touches a seat after the game has ended
     * — a lethal-damage assertion, a turn-limit loss — would resolve against an
     * empty map. Binding both at connect keeps the handles valid for the whole
     * test regardless of when it reads them.
     */
    private fun cacheSeatPlayers() {
        humanRef = bridge.getPlayer(seatId)
        aiRef = bridge.getPlayer(opponentSeatId)
    }

    /** Start game, keep hand, advance to first real-action phase through MatchConnection. */
    fun connectAndKeep(aiScript: List<ScriptedAction>? = null) {
        GameBootstrap.initializeCardDatabase(quiet = true)
        val repo = cardRepositoryForConstructedDecks()
        val runtimeConfigs = runtimeConfigs(puzzle = null)
        localOutput = SinkMatchOutput(effectiveSink)
        localConnection = newConnection(repo, runtimeConfigs, localOutput)
        familiarConnection = newConnection(repo, runtimeConfigs, SinkMatchOutput(ListMessageSink()))

        authenticateAndConnect(localConnection, seatId.value, "headless-player")
        authenticateAndConnect(checkNotNull(familiarConnection), opponentSeatId.value, "headless-player_Familiar")
        bindEngineHandles()
        if (aiScript != null) installScriptedAi(aiScript)
        drainSink()

        val mulliganPrompt = allMessages.last { it.type == GREMessageType.MulliganReq_aa0d }
        val outputStart = messageSnapshot()
        localConnection.submitGREMessage(
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(seatId.value)
                .setType(ClientMessageType.MulliganResp_097b)
                .setGameStateId(mulliganPrompt.gameStateId)
                .setRespId(mulliganPrompt.msgId)
                .setMulliganResp(MulliganResp.newBuilder().setDecision(MulliganOption.AcceptHand))
                .build(),
        )
        drainSink()
        val humanMain1: (GREToClientMessage) -> Boolean = { message ->
            message.hasActionsAvailableReq() &&
                accumulator.turnInfo?.activePlayer == seatId.value &&
                accumulator.turnInfo?.phase == Phase.Main1_a549
        }
        if (messagesSince(outputStart).none(humanMain1)) {
            GameLoopPoller.awaitCondition(timeoutMs = 20_000L) {
                drainSink()
                val pending = bridge.actionBridge(seatId).getPending()
                val humanMain1Pending =
                    phase() == "MAIN1" &&
                        !isAiTurn() &&
                        pending?.state?.kind == PendingActionKind.PRIORITY
                messagesSince(outputStart).any(humanMain1) ||
                    humanMain1Pending
            }
        }
        bridge.actionBridge(seatId).getPending()?.let { pending ->
            if (pending.state.kind == PendingActionKind.PRIORITY && phase() == "MAIN1" && !isAiTurn()) {
                awaitPendingActionHorizon(pending, outputStart)
            }
        }
        if (fullControl) updateSettings(SettingsMessage.newBuilder().setAutoPassOption(AutoPassOption.FullControl).build())
    }

    /** Start puzzle game from classpath resource, advance to first action phase. */
    fun connectAndKeepPuzzle(
        resourcePath: String,
        aiScript: List<ScriptedAction>? = null,
    ) {
        GameBootstrap.initializeCardDatabase(quiet = true)
        startPuzzleConnection(PuzzleSource.definitionFromResource(resourcePath).content, aiScript)
    }

    /**
     * Start puzzle game from inline `.pzl` text, advance to first action phase.
     *
     * Faster than [connectAndKeep]: skips mulligan + turn advancement.
     * Board state is defined declaratively — no multi-turn setup loops.
     *
     * @param aiScript optional scripted actions for the AI — installed before
     *                 the first engine-owned runtime horizon is delivered.
     */
    fun connectAndKeepPuzzleText(
        puzzleText: String,
        aiScript: List<ScriptedAction>? = null,
    ) {
        GameBootstrap.initializeCardDatabase(quiet = true)
        startPuzzleConnection(puzzleText, aiScript)
    }

    private fun startPuzzleConnection(
        puzzleText: String,
        aiScript: List<ScriptedAction>?,
    ) {
        val repo = cardRepositoryForPuzzle()
        localOutput = SinkMatchOutput(effectiveSink)
        val beforeRuntimeStart: (GameBridge) -> Unit =
            { puzzleBridge: GameBridge ->
                if (fullControl) {
                    puzzleBridge.priorityPolicy.submit(SettingsMessage.newBuilder().setAutoPassOption(AutoPassOption.FullControl).build())
                }
                aiScript?.let { script ->
                    val game = checkNotNull(puzzleBridge.getGame())
                    val aiPlayer = game.players.first { it.lobbyPlayer is LobbyPlayerAi }
                    aiPlayer.addController(
                        Long.MAX_VALUE,
                        aiPlayer,
                        ScriptedPlayerController(game, aiPlayer, script),
                        false,
                    )
                }
            }
        localConnection =
            newConnection(
                repo,
                runtimeConfigs(PuzzleSource.definitionFromText(puzzleText, matchId)),
                localOutput,
                beforeRuntimeStart,
            )
        val outputEpoch = localOutput.snapshot()
        val outputStart = messageSnapshot()
        authenticateAndConnect(localConnection, seatId.value, "headless-player")
        bindEngineHandles()
        drainSink()
        val pendingKind =
            bridge
                .actionBridge(seatId)
                .getPending()
                ?.state
                ?.kind
        awaitNamedOutput(outputEpoch, outputStart, "initial puzzle prompt") { message ->
            if (bridge.hasPendingNonActionInteraction()) return@awaitNamedOutput true
            when (pendingKind) {
                PendingActionKind.DECLARE_ATTACKERS -> message.hasDeclareAttackersReq()
                PendingActionKind.DECLARE_BLOCKERS -> message.hasDeclareBlockersReq()
                PendingActionKind.PRIORITY -> message.hasActionsAvailableReq()
                PendingActionKind.SYNC_ONLY,
                null,
                -> message.hasActionsAvailableReq() || message.hasDeclareAttackersReq() || message.hasDeclareBlockersReq()
            }
        }
    }

    private fun runtimeConfigs(puzzle: leyline.config.PuzzleDefinition?): RuntimeMatchConfigRegistry =
        RuntimeMatchConfigRegistry().apply {
            put(
                RuntimeMatchConfig(
                    matchId = matchId,
                    seat1 = DeckSource.ForgeText(deckList ?: DEFAULT_DECK.trimIndent()),
                    seat2 = opponentDeckList?.let(DeckSource::ForgeText),
                    gameVariant = variant,
                    puzzleDefinition = puzzle,
                ),
            )
        }

    private fun newConnection(
        repo: CardRepository,
        runtimeConfigs: RuntimeMatchConfigRegistry,
        output: MatchOutput,
        beforePuzzleRuntimeStart: ((GameBridge) -> Unit)? = null,
    ): MatchConnection =
        MatchConnection(
            registry = registry,
            output = output,
            engineSettings = effectiveMatchConfig,
            puzzleLibrary = leyline.game.generator.PuzzleLibrary(Path.of("data/puzzles")),
            cardRepository = repo,
            runtimeMatchConfigs = runtimeConfigs,
            beforePuzzleRuntimeStart = beforePuzzleRuntimeStart,
        )

    private fun authenticateAndConnect(
        connection: MatchConnection,
        systemSeatId: Int,
        clientId: String,
    ) {
        connection.receive(
            serviceMessage(
                ClientToMatchServiceMessageType.AuthenticateRequest_f487,
                AuthenticateRequest
                    .newBuilder()
                    .setClientId(clientId)
                    .setPlayerName(clientId)
                    .build()
                    .toByteString(),
            ),
        )
        val connect =
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(systemSeatId)
                .setType(ClientMessageType.ConnectReq_097b)
                .setConnectReq(ConnectReq.newBuilder())
                .build()
        connection.receive(
            serviceMessage(
                ClientToMatchServiceMessageType.ClientToMatchDoorConnectRequest_f487,
                ClientToMatchDoorConnectRequest
                    .newBuilder()
                    .setMatchId(matchId)
                    .setClientToGreMessageBytes(connect.toByteString())
                    .build()
                    .toByteString(),
            ),
        )
    }

    private fun serviceMessage(
        type: ClientToMatchServiceMessageType,
        payload: com.google.protobuf.ByteString,
    ): ClientToMatchServiceMessage =
        ClientToMatchServiceMessage
            .newBuilder()
            .setClientToMatchServiceMessageType(type)
            .setPayload(payload)
            .build()

    private fun bindEngineHandles() {
        bridge = checkNotNull(registry.getMatch(matchId)).bridge
        gameRef = checkNotNull(bridge.getGame())
        cacheSeatPlayers()
    }

    private fun cardRepositoryForConstructedDecks(): CardRepository =
        if (cardRepositoryOverride != null) {
            ensureForgeKnowsDeck(deckList)
            ensureForgeKnowsDeck(opponentDeckList)
            cardRepositoryOverride
        } else {
            TestCardRegistry.ensureRegistered()
            if (deckList != null) TestCardRegistry.ensureDeckRegistered(deckList)
            if (opponentDeckList != null) TestCardRegistry.ensureDeckRegistered(opponentDeckList)
            TestCardRegistry.repo
        }

    private fun cardRepositoryForPuzzle(): CardRepository =
        if (cardRepositoryOverride != null) {
            cardRepositoryOverride
        } else {
            // Pre-register the fixture catalog so GameBridge's puzzle-card
            // lookups resolve client identity directly from the one in-memory
            // repository — no synthetic ids, no source-selection wrapper.
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureFixtureCatalogRegistered()
            TestCardRegistry.repo
        }

    private val effectiveMatchConfig: EngineSettings = engineSettings.copy(seed = seed)

    /**
     * Play a land from hand. Returns true if successful.
     *
     * @param name optional preferred land name. When set, plays that named
     *             land if present in hand; otherwise falls back to any land.
     *             Specifying intent here matters: tests that follow up with
     *             a coloured spell cast (e.g. Raging Goblin needs {R}) must
     *             play a same-coloured basic. Without intent, this picks the
     *             first land in hand, which is shuffle-dependent — and shuffle
     *             order shifts with upstream forge edition data because
     *             PaperCard.hashCode is printing-specific. A wrong-coloured
     *             land then makes the follow-up spell uncastable, runtime
     *             continuation advances through phases unblocked, and the turn counter
     *             skips past T1 before the test asserts.
     */
    fun playLand(name: String? = null): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val handCards = player.getZone(ZoneType.Hand).cards
        val land =
            (if (name != null) handCards.firstOrNull { it.isLand && it.name.equals(name, ignoreCase = true) } else null)
                ?: handCards.firstOrNull { it.isLand }
                ?: return false
        val pending = bridge.actionBridge(seatId).getPending()?.takeIf { it.state.kind == PendingActionKind.PRIORITY } ?: return false
        val instanceId = bridge.instanceId(land)
        val action =
            allMessages
                .asReversed()
                .firstOrNull { it.hasActionsAvailableReq() && it.gameStateId == pending.promptGameStateId }
                ?.actionsAvailableReq
                ?.actionsList
                ?.singleOrNull { it.actionType == ActionType.Play_add3 && it.instanceId == instanceId }
                ?: return false

        submitAndAwaitClientResult(submitWithGsId(performAction(action)), "land play")
        return true
    }

    /** Cast a creature from hand. Returns true if successful. */
    fun castCreature(): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val creature =
            player
                .getZone(ZoneType.Hand)
                .cards
                .firstOrNull { it.isCreature } ?: return false

        val msg =
            performAction {
                actionType = ActionType.Cast
                instanceId = bridge.instanceId(creature)
                grpId = bridge.cardRepository.findGrpIdByName(creature.name) ?: 0
            }

        submitAndAwaitClientResult(submitWithGsId(msg), "creature cast")
        return true
    }

    /** Activate a battlefield mana ability and drain the resulting state update. */
    fun activateMana(
        cardName: String,
        abilityIndex: Int = 0,
        selectedColor: ManaColor? = null,
    ): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val card =
            player
                .getZone(ZoneType.Battlefield)
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false
        val ability = getPlayableManaAbilities(card, player).getOrNull(abilityIndex) ?: return false
        val priorProjection = bridge.projectionStateSnapshot()
        val (identityAndOffer, nextProjection) =
            bridge.editProjection(priorProjection) {
                val iid = bridge.instanceId(card)
                val grpId = bridge.resolveGrpId(card, iid)
                val cardData = bridge.cardRepository.findByGrpId(grpId)
                val abilityGrpId =
                    bridge.abilityRegistryFor(card, cardData)?.forSpellAbility(ability)
                        ?: basicLandAbilityGrpId(card, ability)
                val offer =
                    ActionMapper
                        .buildFromSnapshot(seatId.value, GsmSnapshot.capture(game(), bridge, "activateMana", 0), bridge)
                        .actionsList
                        .firstOrNull { action ->
                            action.actionType == ActionType.ActivateMana &&
                                action.instanceId == iid &&
                                action.abilityGrpId == abilityGrpId
                        }
                iid to offer
            }
        val (_, offer) = identityAndOffer
        if (offer == null) return false
        val action =
            if (selectedColor == null) {
                offer
            } else {
                val paymentOption =
                    offer.manaPaymentOptionsList.firstOrNull { option ->
                        option.manaList.any { it.color == selectedColor }
                    } ?: return false
                offer
                    .toBuilder()
                    .clearManaPaymentOptions()
                    .addManaPaymentOptions(paymentOption)
                    .build()
            }
        bridge.commitProjection(leyline.game.state.ProjectionTransition(priorProjection.revision, nextProjection))

        val msg =
            performAction {
                mergeFrom(action)
            }
        submitAndAwaitClientResult(submitWithGsId(msg), "mana activation")
        return true
    }

    private fun basicLandAbilityGrpId(
        card: Card,
        ability: SpellAbility,
    ): Int =
        BasicLandAbilities.byTypeDerivedManaAbility(card, ability)
            ?: BasicLandAbilities.byForgeSubtypeNames(card.type.subtypes)
            ?: 0

    /** Advance one exact priority or state-only synchronization stop. */
    fun passPriority() {
        passPriorityUntil(messageSnapshot(), "priority horizon")
    }

    /** Submit an offered action without rebuilding or dropping action fields. */
    fun submitAction(action: Action) {
        submitAndAwaitClientResult(submitWithGsId(performAction(action)), "offered action")
    }

    /** Route one typed response without consuming the emitted output. */
    fun send(message: ClientToGREMessage) {
        localConnection.submitGREMessage(message)
    }

    /** Apply client settings through SetSettingsReq. */
    fun updateSettings(settings: SettingsMessage) {
        send(
            clientMessage(ClientMessageType.SetSettingsReq_097b) {
                setSetSettingsReq(SetSettingsReq.newBuilder().setSettings(settings))
            },
        )
        drainSink()
    }

    fun concede() {
        localConnection.submitGREMessage(
            ClientToGREMessage
                .newBuilder()
                .setSystemSeatId(seatId.value)
                .setType(ClientMessageType.ConcedeReq_097b)
                .build(),
        )
        drainSink()
    }

    /**
     * Advance through whichever default client response is appropriate for
     * the current stop. Priority uses Pass; combat declaration prompts and
     * selection prompts need their own submit messages.
     *
     * The cleanup discard is the case worth naming: holding more than seven
     * cards at end of turn produces a [SelectNReq] and no priority window, so
     * passing priority answers nothing and the turn never ends. A pass loop
     * that ignores it spins to its budget with the game frozen in CLEANUP.
     */
    fun advance() {
        advance { false }
    }

    private fun advance(completeWhen: () -> Boolean): Boolean {
        val unanswered = unansweredSelectN()
        val prompt = messageLog.latestPrompt()
        val pendingKind =
            bridge
                .actionBridge(seatId)
                .getPending()
                ?.state
                ?.kind
        when {
            unanswered != null ->
                submitPromptResponse(
                    selectNResp(ids = unanswered.ids.take(unanswered.count)),
                    "selection response",
                    completeWhen,
                )
            pendingKind == PendingActionKind.DECLARE_ATTACKERS -> declareNoAttackers(completeWhen)
            pendingKind == PendingActionKind.DECLARE_BLOCKERS -> declareNoBlockers(completeWhen)
            pendingKind == PendingActionKind.PRIORITY || pendingKind == PendingActionKind.SYNC_ONLY ->
                passPriorityUntil(
                    messageSnapshot(),
                    "priority horizon",
                    stopAtInteraction = true,
                    completeWhen = completeWhen,
                )
            prompt?.hasPayCostsReq() == true ->
                submitAndAwaitClientResult(
                    submitWithGsId(
                        performAction { actionType = ActionType.Pass }
                            .toBuilder()
                            .setGameStateId(latestPromptGsId())
                            .build(),
                    ),
                    "cost cancellation",
                    completeWhen = completeWhen,
                )
            else ->
                passPriorityUntil(
                    messageSnapshot(),
                    "priority pass",
                    stopAtInteraction = true,
                    completeWhen = completeWhen,
                )
        }
        return completeWhen()
    }

    /**
     * The trailing [SelectNReq] when it is still open — nothing the engine sent
     * afterwards implies it was consumed. Returns the minimum legal selection,
     * which for a discard is "exactly as many as the rules demand".
     */
    private fun unansweredSelectN(): PendingSelectN? {
        val index = allMessages.indexOfLast { it.hasSelectNReq() }
        if (index < 0) return null
        val laterPrompts =
            allMessages.drop(index + 1).any {
                it.hasActionsAvailableReq() || it.hasSelectNReq() || it.hasDeclareAttackersReq() || it.hasDeclareBlockersReq()
            }
        if (laterPrompts) return null
        val req = allMessages[index].selectNReq
        return PendingSelectN(ids = req.idsList.map { it.toInt() }, count = req.minSel.coerceAtLeast(0))
    }

    private data class PendingSelectN(
        val ids: List<Int>,
        val count: Int,
    )

    /**
     * Keep passing until [stopWhen] becomes true, the game ends, or [maxPasses] is hit.
     *
     * Returns true when [stopWhen] was observed before the pass budget ran out.
     * Prefer this over fixed `repeat(N) { passPriority() }` loops in integration tests.
     */
    fun passUntil(
        maxPasses: Int = 20,
        stopWhen: MatchFlowHarness.() -> Boolean,
    ): Boolean = advanceUntil(maxPasses) { isGameOver() || stopWhen() }

    private fun advanceUntil(
        maxPasses: Int,
        stopWhen: () -> Boolean,
    ): Boolean {
        repeat(maxPasses) {
            if (stopWhen()) return true
            if (advance(stopWhen)) return true
        }
        return stopWhen()
    }

    /**
     * Pass priority until the stack is empty. Use after cast + target to resolve.
     *
     * An already resolved stack needs no pass. A required choice stops this
     * helper so its caller can submit the typed response.
     */
    fun passUntilResolved(maxPasses: Int = 10) {
        repeat(maxPasses) {
            if (isGameOver() || bridge.hasPendingNonActionInteraction()) return
            val retainedSynchronization =
                bridge
                    .actionBridge(seatId)
                    .getPending()
                    ?.state
                    ?.kind == PendingActionKind.SYNC_ONLY
            if (game().stackZone.size() == 0 && !retainedSynchronization) return
            passPriority()
        }
    }

    /**
     * Keep passing until a target turn is reached (or game over / max iterations).
     *
     * Each pass response reaches one engine-owned horizon. This helper submits
     * those responses until the observed game state reaches the requested turn.
     */
    fun passUntilTurn(
        targetTurn: Int,
        maxPasses: Int = 30,
    ) {
        advanceUntil(maxPasses) { turn() >= targetTurn || isGameOver() }
    }

    /**
     * Pass priority through remaining combat until the turn advances or game ends.
     * Replaces the verbose `repeat(15) { if (gameOver/nextTurn) return@repeat; passPriority() }` pattern.
     */
    fun passThroughCombat(
        startTurn: Int = turn(),
        maxPasses: Int = 15,
    ) {
        advanceUntil(maxPasses) { isGameOver() || turn() > startTurn }
    }

    internal fun awaitNextClientOutput(
        description: String = "client output",
        predicate: (GREToClientMessage) -> Boolean = { true },
    ) {
        val epoch = localOutput.snapshot()
        val messageStart = messageSnapshot()
        awaitNamedOutput(epoch, messageStart, description, predicate)
    }

    internal fun pendingActionHorizonPublished(
        pending: leyline.bridge.handoff.GameActionBridge.PendingAction,
        messageStart: Int,
    ): Boolean {
        check(pending.state.kind != PendingActionKind.SYNC_ONLY)
        collectSinkMessages()
        return pendingHorizonVisible(pending, messagesSince(messageStart))
    }

    internal fun awaitPendingActionHorizon(
        pending: leyline.bridge.handoff.GameActionBridge.PendingAction,
        messageStart: Int,
    ) {
        check(pending.state.kind != PendingActionKind.SYNC_ONLY)
        val epoch = localOutput.snapshot()
        collectSinkMessages()
        if (pendingHorizonVisible(pending, messagesSince(messageStart))) return
        awaitNamedOutput(epoch, messageStart, "${pending.state.kind} delivery") { message ->
            pendingHorizonVisible(pending, listOf(message))
        }
    }

    private fun submitAndAwaitClientResult(
        message: ClientToGREMessage,
        description: String,
        expected: ((GREToClientMessage) -> Boolean)? = null,
        autoRespond: Boolean = true,
        preserveRespId: Boolean = false,
        completeWhen: () -> Boolean = { false },
    ) = awaitClientOperation(
        description,
        complete = completeWhen,
        response = {
            val builder = message.toBuilder().clearGameStateId()
            if (!preserveRespId) builder.clearRespId()
            submitWithGsId(
                builder.build(),
            )
        },
        expected = expected,
        autoRespond = autoRespond,
    )

    private fun awaitClientOperation(
        description: String,
        complete: () -> Boolean = { false },
        response: () -> ClientToGREMessage?,
        expected: ((GREToClientMessage) -> Boolean)? = null,
        continueAfterSubmit: Boolean = false,
        autoRespond: Boolean = true,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            collectSinkMessages()
            if (isGameOver()) return false
            var epoch = 0L
            var messageStart = 0
            var submitted = false
            var completed = false
            withSessionLock {
                collectSinkMessages()
                if (complete()) {
                    completed = true
                    return@withSessionLock
                }
                epoch = localOutput.snapshot()
                messageStart = messageSnapshot()
                response()?.let {
                    localConnection.submitGREMessage(it)
                    submitted = true
                }
            }
            if (completed) return true
            if (submitted && !continueAfterSubmit) {
                if (expected == null) {
                    val remainingNanos = deadline - System.nanoTime()
                    check(
                        remainingNanos > 0 &&
                            localOutput.awaitAfter(epoch, TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1),
                    ) { "Timed out waiting for $description" }
                    collectSinkMessages()
                } else {
                    awaitNamedOutput(epoch, messageStart, description, expected)
                }
                if (autoRespond && responseMode == HeadlessResponseMode.AutoForTests) drainAutomaticResponses()
                return false
            }
            val remainingNanos = deadline - System.nanoTime()
            check(
                remainingNanos > 0 && localOutput.awaitAfter(epoch, TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1),
            ) { "Timed out waiting for $description" }
        }
    }

    private fun submitTargetingResponse(
        description: String,
        buildResponse: (GREToClientMessage) -> ClientToGREMessage,
        expected: ((GREToClientMessage) -> Boolean)? = null,
    ) = awaitClientOperation(
        description,
        response = {
            val current = bridge.cutCoordinator.targeting.current()
            val prompt =
                allMessages.asReversed().firstOrNull {
                    it.hasSelectTargetsReq() && it.gameStateId == current?.gameStateId
                }
            prompt?.let {
                buildResponse(it)
                    .toBuilder()
                    .setGameStateId(it.gameStateId)
                    .setRespId(it.msgId)
                    .build()
            }
        },
        expected = expected,
    )

    private fun passPriorityUntil(
        messageStart: Int,
        description: String,
        expectedPrompt: ((GREToClientMessage) -> Boolean)? = null,
        stopAtInteraction: Boolean = false,
        completeWhen: () -> Boolean = { false },
    ) = awaitClientOperation(
        description,
        complete = {
            completeWhen() ||
                (stopAtInteraction && bridge.hasPendingNonActionInteraction()) ||
                (expectedPrompt != null && messagesSince(messageStart).any(expectedPrompt))
        },
        response = {
            if (bridge.hasPendingNonActionInteraction()) {
                check(expectedPrompt != null) { "The current prompt requires its typed response, not a priority pass" }
                return@awaitClientOperation null
            }
            val pending = bridge.actionBridge(seatId).getPending()
            when (pending?.state?.kind) {
                PendingActionKind.SYNC_ONLY -> null
                PendingActionKind.DECLARE_ATTACKERS, PendingActionKind.DECLARE_BLOCKERS ->
                    error("A declaration horizon requires a declaration response, not a priority pass")
                PendingActionKind.PRIORITY -> {
                    val prompt =
                        allMessages.asReversed().firstOrNull {
                            it.hasActionsAvailableReq() && it.gameStateId == pending.promptGameStateId
                        }
                    val pass = prompt?.actionsAvailableReq?.actionsList?.singleOrNull { it.actionType == ActionType.Pass }
                    if (pass == null) {
                        null
                    } else {
                        performAction(pass)
                            .toBuilder()
                            .setGameStateId(prompt.gameStateId)
                            .setRespId(prompt.msgId)
                            .build()
                    }
                }
                else -> null
            }
        },
        continueAfterSubmit = expectedPrompt != null,
        autoRespond = false,
    )

    private inline fun <T> withSessionLock(block: () -> T): T {
        val session = localConnection.session as? MatchSession ?: error("Match session is not connected")
        return synchronized(session.connection.sessionLock) { block() }
    }

    private fun awaitNamedOutput(
        epoch: Long,
        messageStart: Int,
        description: String,
        predicate: (GREToClientMessage) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var deliveredEpoch = epoch
        collectSinkMessages()
        while (messagesSince(messageStart).none(predicate)) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0 ||
                !localOutput.awaitAfter(deliveredEpoch, TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1)
            ) {
                collectSinkMessages()
                check(messagesSince(messageStart).any(predicate)) { "Timed out waiting for $description" }
                return
            }
            deliveredEpoch = localOutput.snapshot()
            collectSinkMessages()
        }
    }

    /**
     * Replace the AI seat's controller with a [ScriptedPlayerController].
     * Call after [connectAndKeep] — the AI player must already exist.
     * Returns the scripted controller for inspection.
     */
    fun installScriptedAi(script: List<ScriptedAction>): ScriptedPlayerController {
        val game = game()
        val aiPlayer =
            bridge.getPlayer(SeatId(2))
                ?: error("No AI player found")
        val controller = ScriptedPlayerController(game, aiPlayer, script)
        // Use highest timestamp so this controller takes priority over the default AI
        aiPlayer.addController(Long.MAX_VALUE, aiPlayer, controller, false)
        return controller
    }

    // --- Phase-precise advancement ---

    /** Advance to a specific phase through serialized client operations. */
    fun advanceToPhase(
        phase: String,
        turn: Int? = null,
    ): leyline.bridge.handoff.GameActionBridge.PendingAction {
        drainSink()
        val pendingAtStart = bridge.actionBridge(seatId).getPending()
        val messageStart = messageLog.snapshot()
        var pending: leyline.bridge.handoff.GameActionBridge.PendingAction? = null

        fun captureTarget(): Boolean {
            val current = bridge.actionBridge(seatId).getPending()
            if (current?.state?.let { it.phase == phase && (turn == null || it.turn == turn) } != true) return false
            pending = current
            return true
        }

        advanceUntil(50, ::captureTarget)
        val reached = checkNotNull(pending) { "Timed out advancing to $phase${turn?.let { " on turn $it" }.orEmpty()}" }
        drainSink()
        val alreadyVisible =
            pendingHorizonVisible(reached, allMessages.subList(0, messageStart)) &&
                (reached.state.kind != PendingActionKind.SYNC_ONLY || pendingAtStart?.actionId == reached.actionId)
        if (!alreadyVisible && !pendingHorizonVisible(reached, messagesSince(messageStart))) {
            awaitNamedOutput(localOutput.snapshot(), messageStart, "${reached.state.kind} delivery") { message ->
                pendingHorizonVisible(reached, listOf(message))
            }
        }
        return reached
    }

    private fun pendingHorizonVisible(
        pending: leyline.bridge.handoff.GameActionBridge.PendingAction,
        messages: List<GREToClientMessage>,
    ): Boolean {
        val gameStateId = pending.promptGameStateId
        return messages.asReversed().any { message ->
            (pending.state.kind == PendingActionKind.SYNC_ONLY || message.gameStateId == gameStateId) &&
                when (pending.state.kind) {
                    PendingActionKind.PRIORITY -> message.hasActionsAvailableReq()
                    PendingActionKind.DECLARE_ATTACKERS -> message.hasDeclareAttackersReq()
                    PendingActionKind.DECLARE_BLOCKERS -> message.hasDeclareBlockersReq()
                    PendingActionKind.SYNC_ONLY -> message.hasGameStateMessage()
                }
        }
    }

    /** Advance to Main1 via bridge. */
    fun advanceToMain1() = advanceToPhase("MAIN1")

    /** Advance to COMBAT_DECLARE_ATTACKERS via bridge. */
    fun advanceToCombat(turn: Int? = null) = advanceToPhase("COMBAT_DECLARE_ATTACKERS", turn)

    /** Advance to MAIN2 via bridge. */
    fun advanceToMain2(turn: Int? = null) = advanceToPhase("MAIN2", turn)

    // --- Combat helpers ---

    /** Human's creatures on the battlefield: (instanceId, cardName). */
    fun humanBattlefieldCreatures(): List<Pair<Int, String>> = combatDriver.humanBattlefieldCreatures()

    /**
     * Declare attackers by instanceId using the two-phase Arena protocol:
     * 1. Send [DeclareAttackersResp] with selection (iterative update)
     * 2. Send [SubmitAttackersReq] to finalize (the "Done" button)
     */
    fun declareAttackers(attackerInstanceIds: List<Int>) = combatDriver.declareAttackers(attackerInstanceIds)

    fun declareAttackers(
        attackerInstanceIds: List<Int>,
        damageRecipients: Map<Int, DamageRecipient>,
    ) = combatDriver.declareAttackers(attackerInstanceIds, damageRecipients)

    /** Declare no attackers (skip combat). Sends empty selection then submits. */
    fun declareNoAttackers() {
        declareNoAttackers { false }
    }

    private fun declareNoAttackers(completeWhen: () -> Boolean): Boolean {
        if (bridge
                .actionBridge(seatId)
                .getPending()
                ?.state
                ?.kind != PendingActionKind.DECLARE_ATTACKERS
        ) {
            passPriorityUntil(
                messageSnapshot(),
                "priority horizon",
                stopAtInteraction = true,
                completeWhen = completeWhen,
            )
            if (completeWhen()) return true
            if (bridge
                    .actionBridge(seatId)
                    .getPending()
                    ?.state
                    ?.kind != PendingActionKind.DECLARE_ATTACKERS
            ) {
                return false
            }
        }
        combatDriver.declareNoAttackers(completeWhen)
        return completeWhen()
    }

    /**
     * Send only the iterative DeclareAttackersResp (no Submit) — simulates an Arena
     * attacker-option toggle. Returns messages produced by the echo-back.
     */
    fun toggleAttackers(
        attackerInstanceIds: List<Int>,
        attackerAlternatives: Map<Int, Int> = emptyMap(),
        damageRecipients: Map<Int, DamageRecipient> = emptyMap(),
    ): List<GREToClientMessage> = combatDriver.toggleAttackers(attackerInstanceIds, attackerAlternatives, damageRecipients)

    fun deselectAttackers(attackerInstanceIds: List<Int>): List<GREToClientMessage> = combatDriver.deselectAttackers(attackerInstanceIds)

    /**
     * Send SubmitAttackersReq (type=31, no payload) — the reference client's "Done" button.
     *
     * In the two-phase combat protocol, iterative creature toggles send
     * [DeclareAttackersResp] (type=30) with selection state, while the final
     * confirmation sends [SubmitAttackersReq] (type=31) which is **type-only,
     * no payload**. The server must use the last known selection.
     */
    fun submitAttackers() = combatDriver.submitAttackers()

    /**
     * Send DeclareAttackersResp with auto_declare=true — the "Attack All" button.
     *
     * In Arena, this is the iterative update that selects all qualified attackers
     * targeting the specified damage recipient. Should be followed by [submitAttackers].
     */
    fun declareAllAttackers() = combatDriver.declareAllAttackers()

    /**
     * Declare blockers with assignments using two-phase Arena protocol:
     * 1. Send [DeclareBlockersResp] with assignments (iterative update)
     * 2. Send [SubmitBlockersReq] to finalize
     *
     * Each entry means "this blocker blocks that attacker."
     */
    fun declareBlockers(assignments: Map<Int, Int>) = combatDriver.declareBlockers(assignments)

    /** Declare no blockers (let all attackers through). Sends SubmitBlockersReq directly. */
    fun declareNoBlockers() {
        declareNoBlockers { false }
    }

    private fun declareNoBlockers(completeWhen: () -> Boolean): Boolean {
        if (bridge
                .actionBridge(seatId)
                .getPending()
                ?.state
                ?.kind != PendingActionKind.DECLARE_BLOCKERS
        ) {
            passPriorityUntil(
                messageSnapshot(),
                "priority horizon",
                stopAtInteraction = true,
                completeWhen = completeWhen,
            )
            if (completeWhen()) return true
            if (bridge
                    .actionBridge(seatId)
                    .getPending()
                    ?.state
                    ?.kind != PendingActionKind.DECLARE_BLOCKERS
            ) {
                return false
            }
        }
        combatDriver.declareNoBlockers(completeWhen)
        return completeWhen()
    }

    /**
     * Send only the iterative DeclareBlockersResp (no Submit) — simulates a single
     * blocker assignment click. Returns messages produced by the echo-back.
     */
    fun toggleBlockers(assignments: Map<Int, Int>): List<GREToClientMessage> = combatDriver.toggleBlockers(assignments)

    /**
     * Send DeclareBlockersResp deselecting a single blocker (wire shape: Blocker
     * entry with blockerInstanceId and empty selectedAttackerInstanceIds).
     */
    fun deselectBlocker(blockerInstanceId: Int): List<GREToClientMessage> = combatDriver.deselectBlocker(blockerInstanceId)

    /**
     * Send SubmitBlockersReq (type-only, no payload) — the reference client's "Done" button.
     */
    fun submitBlockers() = combatDriver.submitBlockers()

    // --- Damage assignment helpers ---

    /**
     * Send AssignDamageResp with damage assignments.
     *
     * @param assigners list of (attackerInstanceId, assignments) where assignments
     *                  is a list of (blockerOrDefenderInstanceId, damage)
     */
    fun assignDamage(assigners: List<Pair<Int, List<Pair<Int, Int>>>>) = combatDriver.assignDamage(assigners)

    // --- Targeting helpers ---

    /**
     * Full two-phase target selection: SelectTargetsResp (phase 1) + SubmitTargetsReq (phase 2).
     *
     * Convenience wrapper — sends both messages so existing tests don't need to change.
     * Use [selectTargetsIterative] + [submitTargets] for phase-by-phase control.
     */
    fun selectTargets(targetInstanceIds: List<Int>) {
        selectTargets(mapOf(currentTargetIndex() to targetInstanceIds))
    }

    /** Select one correlated target response for each request group, then submit. */
    fun selectTargets(targetGroups: Map<Int, List<Int>>) {
        require(targetGroups.isNotEmpty()) { "targetGroups must not be empty" }
        targetGroups.forEach { (targetIdx, targetInstanceIds) ->
            val before = allMessages.size
            submitTargetingResponse(
                "target selection",
                { selectTargetsResp(targets = targetInstanceIds, targetIdx = targetIdx) },
                GREToClientMessage::hasSelectTargetsReq,
            )
            // Each iterative response echoes a fresh prompt. This helper owns
            // those intermediate prompts so the caller only sees the final
            // submitted interaction.
            for (i in before until allMessages.size) {
                val msg = allMessages[i]
                if (msg.hasSelectTargetsReq()) consumedPromptMsgIds += msg.msgId
            }
        }
        submitTargetingResponse(
            "target submission",
            { submitTargetsReq() },
        )
    }

    /** Prompt msgIds answered inside a multi-phase responder; drained by the caller. */
    internal fun takeConsumedPromptMsgIds(): List<Int> {
        val taken = consumedPromptMsgIds.toList()
        consumedPromptMsgIds.clear()
        return taken
    }

    /**
     * Phase 1 only: send SelectTargetsResp without SubmitTargetsReq.
     * Use to inspect the echo-back re-prompt before confirming.
     */
    fun selectTargetsIterative(targetInstanceIds: List<Int>) {
        submitTargetingResponse(
            "target selection",
            {
                val targetIdx =
                    it.selectTargetsReq.targetsList
                        .singleOrNull()
                        ?.targetIdx ?: 1
                selectTargetsResp(targets = targetInstanceIds, targetIdx = targetIdx)
            },
            GREToClientMessage::hasSelectTargetsReq,
        )
    }

    private val consumedPromptMsgIds = mutableSetOf<Int>()

    private fun currentTargetIndex(): Int =
        allMessages
            .lastOrNull { it.hasSelectTargetsReq() }
            ?.selectTargetsReq
            ?.targetsList
            ?.singleOrNull()
            ?.targetIdx
            ?: 1

    /** Phase 2: send SubmitTargetsReq — the client's "Done" button. */
    fun submitTargets() {
        submitTargetingResponse(
            "target submission",
            { submitTargetsReq() },
        )
    }

    /** Cancel a pending targeting action (backs out of spell cast). */
    fun cancelAction() {
        submitAndAwaitClientResult(
            submitWithGsId(cancelActionReq()),
            "action cancellation",
        )
    }

    /**
     * Respond to a GroupReq (surveil/scry). Places specified instanceIds into the
     * "away" group (graveyard for surveil, bottom for scry). Remaining cards stay on top.
     *
     * @param awayInstanceIds cards to put into the away zone (group 1)
     * @param allInstanceIds all card instanceIds from the GroupReq (for the keep group)
     */
    fun respondToGroupReq(
        awayInstanceIds: List<Int>,
        allInstanceIds: List<Int>,
    ) {
        val keepIds = allInstanceIds.filter { it !in awayInstanceIds }
        val msg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.GroupResp_097b)
                .setGroupResp(
                    GroupResp
                        .newBuilder()
                        .addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(keepIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                                .setSubZoneType(SubZoneType.Top),
                        ).addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(awayInstanceIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Graveyard)
                                .setSubZoneType(SubZoneType.None_a455),
                        ).setGroupType(GroupType.Ordered),
                ).build()
        submitPromptResponse(msg, "group response")
    }

    /**
     * Respond to a GroupReq for scry. Places specified instanceIds on the bottom
     * of library. Remaining cards stay on top.
     */
    fun respondToScry(
        bottomInstanceIds: List<Int>,
        allInstanceIds: List<Int>,
    ) {
        val topIds = allInstanceIds.filter { it !in bottomInstanceIds }
        val msg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.GroupResp_097b)
                .setGroupResp(
                    GroupResp
                        .newBuilder()
                        .addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(topIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                                .setSubZoneType(SubZoneType.Top),
                        ).addGroups(
                            Group
                                .newBuilder()
                                .addAllIds(bottomInstanceIds)
                                .setZoneType(wotc.mtgo.gre.external.messaging.Messages.ZoneType.Library)
                                .setSubZoneType(SubZoneType.Bottom),
                        ).setGroupType(GroupType.Ordered),
                ).build()
        submitPromptResponse(msg, "scry response")
    }

    /**
     * Cast a spell by card name from the given [zone] (default: Hand).
     * For flashback/escape, use `zone = ZoneType.Graveyard`.
     * Returns false if card not found in the zone.
     */
    fun castSpellByName(
        cardName: String,
        zone: ZoneType = ZoneType.Hand,
        alternativeGrpId: Int = 0,
    ): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val card =
            player
                .getZone(zone)
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false

        val msg =
            performAction {
                actionType = ActionType.Cast
                instanceId = bridge.instanceId(card)
                grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: 0
                if (alternativeGrpId != 0) this.alternativeGrpId = alternativeGrpId
            }

        submitAndAwaitClientResult(submitWithGsId(msg), "$cardName cast")
        return true
    }

    /** Alias for `castSpellByName(cardName, ZoneType.Graveyard)`. */
    fun castFromGraveyard(cardName: String): Boolean = castSpellByName(cardName, zone = ZoneType.Graveyard)

    /** Alias for `castSpellByName(cardName, ZoneType.Exile)`. */
    fun castFromExile(cardName: String): Boolean = castSpellByName(cardName, zone = ZoneType.Exile)

    /**
     * Cast a spell and wait for its stack resolution.
     *
     * Use only for spells that do not require an interactive client response
     * (no targeting, grouping, modal, or SelectN prompt).
     */
    fun resolveSpell(cardName: String): Boolean {
        if (!castSpellByName(cardName)) return false
        passUntilResolved()
        return true
    }

    /**
     * Cast a spell, run any required follow-up advancement, and return the
     * latest prompt message matching [extract].
     *
     * Keeps flow tests focused on protocol assertions instead of the repeated
     * cast -> advance -> scan message log sequence.
     */
    fun <T> castSpellUntil(
        cardName: String,
        promptName: String,
        advanceAfterCast: MatchFlowHarness.() -> Unit = {},
        extract: (GREToClientMessage) -> T?,
    ): T {
        check(castSpellByName(cardName)) { "Could not cast $cardName" }
        advanceAfterCast()
        return allMessages.asReversed().firstNotNullOfOrNull(extract)
            ?: error("Expected $promptName after casting $cardName")
    }

    fun castSpellUntilGroupReq(
        cardName: String,
        advanceAfterCast: MatchFlowHarness.() -> Unit = {
            var found = false
            repeat(8) {
                if (!found) {
                    found =
                        runCatching {
                            GameLoopPoller.awaitCondition(timeoutMs = 500) {
                                drainSink()
                                allMessages.any { it.hasGroupReq() }
                            }
                        }.isSuccess
                    if (!found) passPriority()
                }
            }
        },
    ): GroupReq =
        castSpellUntil(cardName, promptName = "GroupReq", advanceAfterCast = advanceAfterCast) { msg ->
            if (msg.hasGroupReq()) msg.groupReq else null
        }

    fun castSpellUntilSelectNReq(
        cardName: String,
        advanceAfterCast: (MatchFlowHarness.() -> Unit)? = null,
    ): SelectNReq =
        castSpellUntilPriorityPrompt(cardName, "SelectNReq", advanceAfterCast) { msg ->
            if (msg.hasSelectNReq()) msg.selectNReq else null
        }

    fun castSpellUntilSelectTargetsReq(cardName: String): SelectTargetsReq =
        castSpellUntilPriorityPrompt(cardName, "SelectTargetsReq", null) { msg ->
            if (msg.hasSelectTargetsReq()) msg.selectTargetsReq else null
        }

    fun castSpellUntilSearchReq(cardName: String): SearchReq =
        castSpellUntilPriorityPrompt(cardName, "SearchReq", null) { msg ->
            if (msg.hasSearchReq()) msg.searchReq else null
        }

    fun castSpellUntilOrderReq(
        cardName: String,
        advanceAfterCast: (MatchFlowHarness.() -> Unit)? = null,
    ): OrderReq =
        castSpellUntilPriorityPrompt(cardName, "OrderReq", advanceAfterCast) { msg ->
            if (msg.hasOrderReq()) msg.orderReq else null
        }

    fun castSpellUntilCastingTimeOptionsReq(
        cardName: String,
        advanceAfterCast: (MatchFlowHarness.() -> Unit)? = null,
    ): CastingTimeOptionsReq =
        castSpellUntilPriorityPrompt(cardName, "CastingTimeOptionsReq", advanceAfterCast) { msg ->
            if (msg.hasCastingTimeOptionsReq()) msg.castingTimeOptionsReq else null
        }

    private fun <T> castSpellUntilPriorityPrompt(
        cardName: String,
        promptName: String,
        advanceAfterCast: (MatchFlowHarness.() -> Unit)?,
        extract: (GREToClientMessage) -> T?,
    ): T {
        val messageStart = messageSnapshot()
        check(castSpellByName(cardName)) { "Could not cast $cardName" }
        if (advanceAfterCast == null) {
            passPriorityUntil(messageStart, promptName, expectedPrompt = { extract(it) != null })
        } else {
            advanceAfterCast()
        }
        return messagesSince(messageStart).asReversed().firstNotNullOfOrNull(extract)
            ?: error("Expected $promptName after casting $cardName")
    }

    /**
     * Activate a non-mana ability on a battlefield card by name and ability index.
     *
     * @param cardName name of the card on the battlefield
     * @param abilityIndex 0-based index into the card's non-mana activated abilities
     *                     (e.g., planeswalker: 0=first loyalty, 1=second, 2=ultimate)
     * @return true if the card was found and action sent
     */
    fun activateAbility(
        cardName: String,
        abilityIndex: Int = 0,
    ): Boolean = activateAbilityInZone(cardName, ZoneType.Battlefield, abilityIndex)

    /** Activate an ability on a card in the player's hand (Channel, Cycling, etc.). */
    fun activateAbilityFromHand(
        cardName: String,
        abilityIndex: Int = 0,
    ): Boolean = activateAbilityInZone(cardName, ZoneType.Hand, abilityIndex)

    /** Activate an ability on a card in the player's graveyard (Unearth, Embalm, Eternalize). */
    fun activateAbilityFromGraveyard(
        cardName: String,
        abilityIndex: Int = 0,
    ): Boolean = activateAbilityInZone(cardName, ZoneType.Graveyard, abilityIndex)

    private fun activateAbilityInZone(
        cardName: String,
        zone: ZoneType,
        abilityIndex: Int,
    ): Boolean {
        val player = bridge.getPlayer(seatId) ?: return false
        val card =
            player
                .getZone(zone)
                .cards
                .firstOrNull { it.name.equals(cardName, ignoreCase = true) } ?: return false
        return submitActivateAction(card, abilityIndex)
    }

    /** Common Activate_add3 submission for both battlefield and hand cards. */
    private fun submitActivateAction(
        card: forge.game.card.Card,
        abilityIndex: Int,
    ): Boolean {
        val iid = bridge.instanceId(card)
        val grpId = bridge.cardRepository.findGrpIdByName(card.name) ?: 0
        val cardData = bridge.cardRepository.findByGrpId(grpId)
        val ability = bridge.getPlayer(seatId)?.let { getNonManaActivatedAbilities(card, it).getOrNull(abilityIndex) }
        val abilityGrpId =
            if (cardData != null && ability != null) {
                bridge.abilityRegistryFor(card, cardData)?.forSpellAbility(ability) ?: 0
            } else {
                0
            }

        val msg =
            performAction {
                actionType = ActionType.Activate_add3
                instanceId = iid
                this.grpId = grpId
                this.abilityGrpId = abilityGrpId
            }
        submitAndAwaitClientResult(submitWithGsId(msg), "ability activation")
        return true
    }

    // --- SelectN helpers ---

    /**
     * Respond to a SelectNReq (legend rule, "choose N" prompts) with selected instanceIds.
     *
     * @param selectedInstanceIds the instanceIds the player chose (e.g. the legendary to keep)
     */
    fun respondToSelectN(
        selectedInstanceIds: List<Int>,
        useArbitrary: OrderingType? = null,
    ) {
        submitPromptResponse(selectNResp(ids = selectedInstanceIds, useArbitrary = useArbitrary), "selection response")
    }

    fun respondToOrder(orderedInstanceIds: List<Int>) {
        submitPromptResponse(orderResp(ids = orderedInstanceIds), "order response")
    }

    fun respondToDistribution(amounts: List<Pair<Int, Int>>) {
        submitPromptResponse(distributionResp(amounts), "distribution response")
    }

    fun respondToSearch(itemsFound: List<Int>) {
        submitPromptResponse(searchResp(itemsFound), "search response")
    }

    fun respondToSelectReplacement(replacement: ReplacementEffect) {
        submitPromptResponse(
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.SelectReplacementResp_097b)
                .setSelectReplacementResp(SelectReplacementResp.newBuilder().setReplacement(replacement))
                .build(),
            "replacement response",
        )
    }

    fun respondToGroupedSearch(
        groupId: Int,
        ids: List<Int>,
        maxSelect: Int,
    ) {
        submitPromptResponse(groupedSearchResp(groupId, ids, maxSelect), "grouped search response")
    }

    fun respondToGroupedSearchFail() {
        submitPromptResponse(groupedSearchFailResp(), "grouped search fail response")
    }

    fun respondToEffectCost(selectedInstanceIds: List<Int>) {
        submitAndAwaitClientResult(submitWithGsId(effectCostResp(selectedInstanceIds)), "cost response")
    }

    fun respondToGatherCounters(gatherings: List<Pair<Int, Int>>) {
        submitPromptResponse(gatherCountersResp(gatherings), "counter response")
    }

    private fun submitPromptResponse(
        message: ClientToGREMessage,
        description: String,
        completeWhen: () -> Boolean = { false },
    ): Boolean {
        val requestMsgId = checkNotNull(messageLog.latestPrompt()).msgId
        return submitAndAwaitClientResult(
            message.toBuilder().setRespId(requestMsgId).build(),
            description,
            preserveRespId = true,
            completeWhen = completeWhen,
        )
    }

    // --- Modal helpers ---

    /** Respond to a CastingTimeOptionsReq (modal choice) with selected grpIds. */
    fun respondModalChoice(selectedGrpIds: List<Int>) {
        submitAndAwaitClientResult(
            submitWithGsId(castingTimeOptionsResp(selectedGrpIds = selectedGrpIds)),
            "modal response",
        )
    }

    // --- Optional cost helpers ---

    /** Respond to a CastingTimeOptionsReq with the given ctoId (kicker, buyback). */
    fun respondToOptionalCost(ctoId: Int) {
        submitAndAwaitClientResult(submitWithGsId(optionalCostResp(ctoId)), "optional cost response")
    }

    /** Respond to a required ChooseX option embedded in CastingTimeOptionsReq. */
    fun respondToCastingTimeX(
        ctoId: Int,
        value: Int,
    ) {
        submitAndAwaitClientResult(submitWithGsId(castingTimeXResp(ctoId, value)), "X cost response")
    }

    /** Respond to a required alternate-additional-cost CastingTimeOptionsReq. */
    fun respondToAlternateCost(
        ctoId: Int,
        optionIndex: Int,
    ) {
        submitAndAwaitClientResult(submitWithGsId(alternateCostResp(ctoId, optionIndex)), "alternate cost response")
    }

    fun respondToManaTypeChoices(choicesByCtoId: List<Pair<Int, ManaColor>>) {
        submitAndAwaitClientResult(submitWithGsId(manaTypeResp(choicesByCtoId)), "mana type response")
    }
    // --- Message inspection ---

    /** Snapshot current message count for later comparison with [messagesSince]. */
    fun messageSnapshot(): Int = messageLog.snapshot()

    /** Get all messages since a snapshot point. */
    fun messagesSince(snapshot: Int): List<GREToClientMessage> = messageLog.since(snapshot)

    /** Get all game-state messages since a snapshot point. */
    fun gameStateMessagesSince(snapshot: Int): List<GameStateMessage> = messageLog.gameStateMessagesSince(snapshot)

    /** Get all annotations from game-state messages since a snapshot point. */
    fun annotationsSince(snapshot: Int): List<AnnotationInfo> = messageLog.annotationsSince(snapshot)

    /** Most recent [SelectNReq] the harness has drained. */
    fun lastSelectNReq(): SelectNReq = allMessages.last { it.hasSelectNReq() }.selectNReq

    /** Most recent [GroupReq] the harness has drained. */
    fun lastGroupReq(): GroupReq = allMessages.last { it.hasGroupReq() }.groupReq

    /** Most recent [CastingTimeOptionsReq] the harness has drained. */
    fun lastCastingTimeOptionsReq(): CastingTimeOptionsReq = allMessages.last { it.hasCastingTimeOptionsReq() }.castingTimeOptionsReq

    // --- State queries ---

    fun phase(): String? = game().phaseHandler.phase?.name

    fun turn(): Int = game().phaseHandler.turn

    fun isAiTurn(): Boolean {
        val human = bridge.getPlayer(seatId) ?: return false
        return game().phaseHandler.playerTurn != human
    }

    fun isGameOver(): Boolean {
        val game = bridge.getGame()
        if (game != null) return game.isGameOver

        if (
            allMessages.any {
                it.hasGameStateMessage() &&
                    it.gameStateMessage.hasGameInfo() &&
                    it.gameStateMessage.gameInfo.stage == GameStage.GameOver
            }
        ) {
            return true
        }

        return allRawMessages.any {
            it.hasMatchGameRoomStateChangedEvent() &&
                it.matchGameRoomStateChangedEvent.gameRoomInfo.stateType ==
                MatchGameRoomStateType.MatchCompleted
        }
    }

    fun game(): Game = bridge.getGame() ?: gameRef

    // --- Seat handles ---
    //
    // Resolved once and held. Forge `Player` identity is stable for the whole
    // game, but the bridge clears its seat map when the match closes — and a
    // game that reaches its end during a test still has a readable final board
    // that specs assert on. Caching keeps those assertions working.

    private var humanRef: Player? = null
    private var aiRef: Player? = null

    /** Human player — seat 1. */
    val human: Player get() = humanRef ?: resolveSeat(seatId).also { humanRef = it }

    /** AI / opponent player — seat 2. */
    val ai: Player get() = aiRef ?: resolveSeat(opponentSeatId).also { aiRef = it }

    /** Seat handle if one has been resolved or is still reachable, else null. */
    internal fun seatPlayerOrNull(seat: SeatId): Player? =
        when (seat) {
            seatId -> humanRef
            opponentSeatId -> aiRef
            else -> null
        } ?: if (::bridge.isInitialized) bridge.getPlayer(seat) else null

    private fun resolveSeat(seat: SeatId): Player =
        bridge.getPlayer(seat) ?: error("No player at seat ${seat.value} — call connect() first")

    // --- Instance probe DSL ---

    /** Battlefield zone of [this] player as a probe handle. */
    val Player.battlefield: PlayerZone get() = PlayerZone(this, ZoneType.Battlefield)

    /** Hand zone of [this] player as a probe handle. */
    val Player.hand: PlayerZone get() = PlayerZone(this, ZoneType.Hand)

    /** Graveyard zone of [this] player as a probe handle. */
    val Player.graveyard: PlayerZone get() = PlayerZone(this, ZoneType.Graveyard)

    /** Exile zone of [this] player as a probe handle. */
    val Player.exile: PlayerZone get() = PlayerZone(this, ZoneType.Exile)

    /** Library zone of [this] player as a probe handle. */
    val Player.library: PlayerZone get() = PlayerZone(this, ZoneType.Library)

    /**
     * Resolve a card by name within this (player, zone) handle to its
     * instanceId, so call sites read like a path:
     * `human.battlefield.iid("Walking Corpse")`.
     */
    fun PlayerZone.iid(cardName: String): Int = iidVia(bridge, cardName)

    /**
     * Resolve a [Card] already held by the caller to its instanceId. The zone
     * is informational only — the bridge keys by Forge card id — but keeping
     * the call shape uniform makes probe sites read the same way.
     */
    fun PlayerZone.iid(card: Card): Int = bridge.instanceId(card)

    /** Resolve several cards by name — `human.battlefield.iids("A", "B", "C")`. */
    fun PlayerZone.iids(vararg cardNames: String): List<Int> = cardNames.map { iid(it) }

    /** Find a card in the zone by name. */
    fun PlayerZone.card(name: String): Card = cardIn(player, zone, name)

    /**
     * Resolve a card by name in [player]'s [zone]. Prefer the probe DSL; use
     * this when the zone or player is computed at runtime.
     */
    fun instanceIdOf(
        cardName: String,
        player: Player = human,
        zone: ZoneType = ZoneType.Battlefield,
    ): Int = PlayerZone(player, zone).iidVia(bridge, cardName)

    /**
     * Resolve an instanceId back to its Forge [Card], or null when the mapping
     * is stale (card zoned out or the instanceId was reallocated).
     */
    fun cardByIid(iid: Int): Card? {
        val cardId = bridge.getForgeCardId(InstanceId(iid)) ?: return null
        return game().findById(cardId.value)
    }

    /** Resolve an instanceId to its card name. Fails clearly when unmapped. */
    fun cardName(instanceId: Int): String =
        cardByIid(instanceId)?.name
            ?: error("No card for instanceId $instanceId")

    /** Find the instanceId naming [name] among [candidateIds]. */
    fun findInstanceId(
        candidateIds: List<Int>,
        name: String,
    ): Int =
        candidateIds.firstOrNull { cardName(it) == name }
            ?: error("Card '$name' not found in candidates: $candidateIds")

    /**
     * True when the seat's [GameActionBridge] has a pending action awaiting
     * the client's response. False means the engine isn't blocked on us — any
     * submit we make will trigger
     * `WARN ActionPerformer: PerformActionResp but no pending action` and a
     * spurious state resync. Use as a guard before submitting an action
     * in long-running drivers (simclient) where runtime horizons frequently
     * advances past priority windows between observe and submit.
     */
    fun hasPendingAction(seat: SeatId = seatId): Boolean = bridge.actionBridge(seat).getPending() != null

    fun shutdown() {
        if (::localConnection.isInitialized) localConnection.disconnected()
    }

    // --- Real-client gsId reflection ---
    //
    // A real client reflects the gsId of the latest prompt-bearing GRE it
    // has received on every response it sends. The harness used to leave
    // `gameStateId` at proto default 0, which short-circuited the production
    // staleness check (`clientGsId != 0 && ...`) and meant no testGate test
    // exercised it.
    //
    // [submitWithGsId] fills the field by scanning [allMessages] — the
    // drained record of what the harness has *seen*, mirroring a real
    // client's TCP receive view. Reading from `bridge.messageCounter.
    // lastPromptGsId()` directly would race against the engine thread:
    // the engine can emit a new prompt between the harness's read and the
    // session's processing of the response, leaving the response stamped
    // with an old gsId that the staleness predicate then rejects (observed
    // on CI under load, never reproduces locally because the engine drains
    // synchronously fast enough to hide the race).
    //
    // Tests that need to send an explicit (or stale) gsId can pass a
    // non-zero `gameStateId` on the inbound message; the wrapper leaves
    // those untouched.

    /**
     * gsId of the most recent prompt-bearing GRE the harness has drained.
     * 0 pre-handshake or before any prompt has been received.
     *
     * Walks [allMessages] in reverse — that's the harness's view of what
     * the "client" has seen. For an action response, the coordinator's exact
     * pending window is authoritative because another seat may have advanced
     * the shared message counter in the meantime.
     */
    fun latestPromptGsId(): Int = messageLog.latestPromptGsId()

    fun latestPromptMsgId(): Int = messageLog.latestPromptMsgId()

    fun hasPendingSelectNPrompt(): Boolean = bridge.cutCoordinator.cardSelect.current() != null

    /**
     * Reflect the latest prompt ids onto a client response before it enters
     * the session. Explicit non-zero ids remain unchanged so tests can submit
     * stale values deliberately.
     *
     * gsId is stamped from the latest *prompt*, not the latest *state*; the two
     * coincide for iterative prompts (the echo is both). Guards tested through
     * this harness must not assume a real client stamps prompt-fidelity gsIds.
     *
     * `internal` so cross-package drivers in the same module can use the same
     * response envelope as the local connection.
     */
    internal fun submitWithGsId(msg: ClientToGREMessage): ClientToGREMessage {
        val prompt = messageLog.latestPrompt() ?: return msg
        val builder = msg.toBuilder()
        val pendingActionGsId =
            when (msg.type) {
                ClientMessageType.PerformActionResp_097b,
                ClientMessageType.DeclareAttackersResp_097b,
                ClientMessageType.SubmitAttackersReq,
                ClientMessageType.DeclareBlockersResp_097b,
                ClientMessageType.SubmitBlockersReq,
                -> bridge.actionBridge(seatId).getPending()?.promptGameStateId
                else -> null
            }
        if (msg.gameStateId == 0) builder.gameStateId = pendingActionGsId ?: prompt.gameStateId
        if (msg.respId == 0 && msg.type in leyline.match.CORRELATED_CLIENT_MESSAGE_TYPES) {
            builder.respId = prompt.msgId
        }
        return builder.build()
    }

    /**
     * Walk a deck list and ask Forge's static data to load every unique card
     * by name. Required when [cardRepositoryOverride] is in play — without
     * the YAML-fixture path, nothing else routes through
     * [forge.StaticData.attemptToLoadCard], and the engine fails opaquely
     * when a deck contains a card it has never seen.
     *
     * Fails loudly with the offending card name when Forge cannot resolve it,
     * matching the simclient policy: a deck either runs end-to-end or it
     * doesn't run at all.
     *
     * **simclient-only contract.** The simclient tool runs rows serially and
     * Forge's static `MyRandom` is not safe for concurrent games. Do NOT reuse
     * this override path from a parallelised Gradle test task; the lock would
     * still hold but threads would silently serialise on it. If a non-simclient
     * caller needs SQLite-backed card resolution, factor a thread-safe resolver
     * out first.
     */
    private fun ensureForgeKnowsDeck(deckList: String?) {
        val list = deckList ?: return
        val sectionHeader = Regex("""^\[.+]$|^(Deck|Sideboard|Maybeboard|Commander|Companion)\s*$""", RegexOption.IGNORE_CASE)
        val names =
            list
                .trim()
                .lines()
                .filter { it.isNotBlank() }
                .map { it.trim() }
                .filter { !sectionHeader.matches(it) }
                // Strip leading `<count> ` and trailing Arena-export suffix
                // ` (SET) NNN` (e.g. `4 Diregraf Ghoul (FDN) 171` → `Diregraf Ghoul`).
                // Mirrors Forge's own `DeckRecognizer` stripping (see `DeckLoader`) —
                // without this, the validator looks up `Diregraf Ghoul (FDN) 171`
                // verbatim and every Arena-export deck spuriously fails to resolve.
                .map {
                    it
                        .replaceFirst(Regex("^\\d+\\s+"), "")
                        .replace(Regex("""\s*\([A-Z0-9]+\)\s*\d*\s*$"""), "")
                        .trim()
                }.distinct()
        val db =
            forge.model.FModel
                .getMagicDb()
                ?.commonCards
                ?: error("Forge card DB not initialised — call GameBootstrap.initializeCardDatabase first")
        val missing = mutableListOf<String>()
        synchronized(forge.StaticData.instance()) {
            for (name in names) {
                if (db.getCard(name) != null) continue
                forge.StaticData.instance().attemptToLoadCard(name)
                if (db.getCard(name) == null) missing.add(name)
            }
        }
        check(missing.isEmpty()) {
            "Forge cannot resolve cards in deck list: ${missing.joinToString()}. " +
                "Either the names are mistyped or Forge's card DB does not include them."
        }
    }

    private fun collectSinkMessages() {
        synchronized(sink) {
            allMessages.addAll(sink.messages)
            allRawMessages.addAll(sink.rawMessages)
            accumulator.processAll(sink.messages)
            sink.clear()
        }
    }

    internal fun drainSink() {
        collectSinkMessages()

        if (responseMode != HeadlessResponseMode.AutoForTests) return

        drainAutomaticResponses()
    }

    private fun drainAutomaticResponses() {
        // Auto-respond to engine-initiated prompts so the engine can continue.
        // Loops because chained prompts (e.g. Wildborn Preserver: optional
        // accept → numeric input → potentially another optional on the next
        // resolution step) need every step responded to within one drain.
        // Each helper returns whether it actually fired; loop while any did.
        do {
            val acted = autoRespondToOptionalAction() || autoRespondToNumericInput()
        } while (acted)
    }

    /** Submit an already encoded gameplay response through the production dispatcher. */
    internal fun submitGameplayResponse(message: ClientToGREMessage): Boolean {
        if (!isGameplayResponse(message.type)) return false
        submitAndAwaitClientResult(submitWithGsId(message), message.type.name)
        return true
    }

    private fun autoRespondToOptionalAction(): Boolean {
        bridge.cutCoordinator
            .currentBlockingInteraction()
            ?.takeIf { it.interaction is leyline.bridge.handoff.BlockingInteraction.Optional }
            ?: return false
        val msg = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e } ?: return false
        if (holdNextOptionalResponse) {
            return false
        }

        // If a test pre-seeded a one-shot response via [declineNextOptionalAction],
        // use it and clear the slot. Otherwise default to AllowYes to keep existing
        // tests unblocked.
        val response = nextOptionalResponse ?: OptionResponse.AllowYes
        nextOptionalResponse = null

        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.OptionalActionResp)
                .setGameStateId(msg.gameStateId)
                .setRespId(msg.msgId)
                .setOptionalResp(
                    OptionalResp
                        .newBuilder()
                        .setResponse(response),
                ).build()
        submitAndAwaitClientResult(greMsg, "optional action response", autoRespond = false)
        return true
    }

    /**
     * Pre-seed the next auto-accepted optional-action prompt to be declined instead.
     * One-shot; cleared after the next OptionalActionMessage is auto-responded to.
     * Use when exercising decline branches (e.g. Madness "put in graveyard" path).
     */
    fun declineNextOptionalAction() {
        nextOptionalResponse = OptionResponse.CancelNo
    }

    /** Leave the next OptionalActionMessage pending so the test can call [respondToOptionalAction]. */
    fun holdNextOptionalAction() {
        holdNextOptionalResponse = true
    }

    private fun autoRespondToNumericInput(): Boolean {
        bridge.cutCoordinator
            .currentBlockingInteraction()
            ?.takeIf { it.interaction is leyline.bridge.handoff.BlockingInteraction.Numeric }
            ?: return false
        val msg = allMessages.lastOrNull { it.type == GREMessageType.NumericInputReq_695e } ?: return false

        val value = nextNumericInputValue ?: 0
        nextNumericInputValue = null

        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.NumericInputResp_097b)
                .setGameStateId(msg.gameStateId)
                .setRespId(msg.msgId)
                .setNumericInputResp(
                    NumericInputResp
                        .newBuilder()
                        .setNumericInputValue(value),
                ).build()
        submitAndAwaitClientResult(greMsg, "numeric input response", autoRespond = false)
        return true
    }

    /**
     * Pre-seed the next auto-responded NumericInputReq with [value].
     * One-shot; cleared after the next response. Default (no pre-seed) is `0`.
     */
    fun nextNumericInput(value: Int) {
        nextNumericInputValue = value
    }

    /**
     * Respond to a NumericInputReq with [value] explicitly.
     * For tests that need direct control over the numeric pick.
     */
    fun respondToNumericInput(value: Int) {
        val msg = allMessages.lastOrNull { it.type == GREMessageType.NumericInputReq_695e }
        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.NumericInputResp_097b)
                .setGameStateId(msg?.gameStateId ?: 0)
                .setRespId(msg?.msgId ?: 0)
                .setNumericInputResp(
                    NumericInputResp
                        .newBuilder()
                        .setNumericInputValue(value),
                ).build()
        submitAndAwaitClientResult(greMsg, "numeric input response")
    }

    /**
     * Respond to an OptionalActionMessage with Accept or Decline.
     * For tests that need explicit control over the optional decision.
     */
    fun respondToOptionalAction(accept: Boolean) {
        holdNextOptionalResponse = false
        val msg = allMessages.lastOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
        val greMsg =
            ClientToGREMessage
                .newBuilder()
                .setType(ClientMessageType.OptionalActionResp)
                .setGameStateId(msg?.gameStateId ?: 0)
                .setRespId(msg?.msgId ?: 0)
                .setOptionalResp(
                    OptionalResp
                        .newBuilder()
                        .setResponse(if (accept) OptionResponse.AllowYes else OptionResponse.CancelNo),
                ).build()
        submitAndAwaitClientResult(greMsg, "optional action response")
    }
}

private class SinkMatchOutput(
    private val sink: MessageSink,
) : MatchOutput {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val monitor = java.lang.Object()
    private var deliveredEpoch = 0L

    override fun send(message: MatchServiceToClientMessage) {
        sink.sendRaw(message)
        if (!message.hasGreToClientEvent()) return
        val greMessages = message.greToClientEvent.greToClientMessagesList
        synchronized(monitor) {
            sink.send(greMessages)
            deliveredEpoch++
            monitor.notifyAll()
        }
    }

    override fun close() = Unit

    fun snapshot(): Long = synchronized(monitor) { deliveredEpoch }

    fun awaitAfter(
        epoch: Long,
        timeoutMs: Long = 5_000L,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        synchronized(monitor) {
            while (deliveredEpoch <= epoch) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return false
                TimeUnit.NANOSECONDS.timedWait(monitor, remainingNanos)
            }
            return true
        }
    }
}
