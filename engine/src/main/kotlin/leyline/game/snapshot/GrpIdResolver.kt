package leyline.game.snapshot

import forge.ImageKeys
import forge.card.GamePieceType
import forge.game.card.Card
import leyline.DevCheck
import leyline.game.data.CardRepository
import leyline.game.state.GameBridge
import leyline.game.state.TokenIdentityRegistry
import org.slf4j.LoggerFactory

/**
 * Single resolution path from a live Forge [Card] to its client grpId.
 *
 * One chain, four cases — handled exhaustively here so callers don't reimplement
 * the branches:
 *
 * 1. [GamePieceType.EFFECT] — engine-bookkeeping surrogates (Puzzle Goal, Monarch,
 *    The Ring, Radiation, City's Blessing, DetachedCardEffect, keywordEffect) have
 *    no client-DB row. Return 0; the projection layer drops them via
 *    `(gamePieceType==EFFECT && grpId==0)`.
 * 2. Token piece-typed cards (`isToken==true`):
 *    - Cached entry in [TokenIdentityRegistry] wins (stable across diff ticks).
 *    - Prepared-spell exile copy ([PreparedSpell.isCopy]) — TOKEN-piece-typed but
 *      represents a normal castable spell; resolve by current-state name.
 *    - `card.copiedPermanent != null` — copy permanent uses source's grpId.
 *    - Standard token via `tokenSpawningAbility.hostCard`'s
 *      `AbilityIdToLinkedTokenGrpId` mapping.
 *    - Token-name fallback for generated tokens whose source mapping is gone.
 * 3. Face-down alternate-state cards (Foretell / Disguise) — `card.name` is
 *    empty while face-down. Resolve via the Original state's name.
 * 4. Standard non-token — `findGrpIdByName` (primary) → `findGrpIdByNameAnyFace`
 *    (DFC back faces with `IsPrimaryCard=0`).
 *
 * Token grpIds get registered in [TokenIdentityRegistry] on first resolve so the
 * chain self-stabilizes across reallocation of `Card.id` through zone moves.
 *
 * Used by [SnapshotCapture.captureCard] (every observable card, once per tick) and
 * by [GameBridge.resolveGrpId] (live-Forge consumers in the runtime path —
 * action handling that holds a Forge [Card] without a [CardSnapshot] in scope).
 */
object GrpIdResolver {
    private val log = LoggerFactory.getLogger(GrpIdResolver::class.java)

    /**
     * Names already reported as missing from the client card DB. Snapshots run
     * 100s of times per game and `card.name` is stable across them, so without
     * dedup a single missing card emits an error per tick (observed: 1631
     * errors / 71s game when a Forge-only card name is in the deck).
     *
     * **Scope: JVM-static (per resolver-singleton lifetime).** In a multi-game
     * batch (e.g. simclient batch run) the same missing card name only logs
     * ERROR once across the whole batch — downstream telemetry that counts
     * per-game ERROR events will under-report missing cards in games 2..N.
     * This is intentional: a missing card is a deck/DB drift problem, not a
     * per-game one, and a single ERROR suffices to surface it.
     *
     * Tests that need fresh dedup state across runs can call
     * [resetReportedMissingCardNamesForTest].
     *
     * Soft cap [MAX_REPORTED_MISSING] bounds memory if a runaway deck contains
     * many missing names; further misses past the cap go unreported.
     */
    private val reportedMissingCardNames: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()
    private val reportedUnsupportedCardNames: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()
    private const val MAX_REPORTED_MISSING = 256

    /** Test-only: clear the dedup set so a new batch starts fresh. */
    @Suppress("unused")
    fun resetReportedMissingCardNamesForTest() {
        reportedMissingCardNames.clear()
        reportedUnsupportedCardNames.clear()
    }

    private fun reportMissingOnce(
        kind: String,
        name: String,
        forgeId: Int,
    ) {
        if (reportedMissingCardNames.size >= MAX_REPORTED_MISSING) return
        if (reportedMissingCardNames.add(name)) {
            log.error("$kind grpId=0 for card '{}' (forgeId={}): not in client card DB", name, forgeId)
        }
    }

    private fun reportUnsupportedOnce(
        kind: String,
        name: String,
        forgeId: Int,
    ) {
        if (reportedUnsupportedCardNames.size >= MAX_REPORTED_MISSING) return
        val key = "$kind:$name"
        if (reportedUnsupportedCardNames.add(key)) {
            log.warn("$kind uses unsupported card '{}' (forgeId={}); projecting fallback grpId", name, forgeId)
        }
    }

    /**
     * Resolve grpId for [card]. See class KDoc for the chain.
     *
     * @param instanceId client instanceId for [TokenIdentityRegistry] cache lookups.
     *   `0` skips the cache (use when no instance has been allocated yet).
     */
    @Suppress(
        // Each branch is a distinct case in the chain (EFFECT, token cache, prepared
        // copy, copy permanent, standard token, foretold, regular) — collapsing
        // them into a single tail expression would lose the registry-write side
        // effects per branch.
        "ReturnCount",
        "CyclomaticComplexMethod",
    )
    fun resolve(
        card: Card,
        cards: CardRepository,
        instanceId: Int = 0,
        tokenRegistry: TokenIdentityRegistry = TokenIdentityRegistry(),
    ): Int {
        if (card.gamePieceType == GamePieceType.EFFECT) return 0

        if (card.isToken) {
            // 1. Registry cache — stable across diff ticks
            tokenRegistry.resolve(instanceId)?.let { return it }

            // 2. Prepared-spell copy — Forge marks the alt face copy as TOKEN-piece
            // typed, but it represents a normal castable spell. Resolve by name on
            // the current face, which survives the Forge `Card.id` reallocation
            // that happens when the copy moves Exile → Stack on cast.
            //
            // This branch must run BEFORE the token-spawning-ability path
            // (a prepared copy is `isToken==true` but doesn't have a
            // `tokenSpawningAbility.hostCard`, so the standard token resolution would
            // fall through to `DevCheck.fail`).
            PreparedSpell.resolveCopyGrpId(card, cards)?.let { preparedGrpId ->
                if (instanceId != 0) tokenRegistry.register(instanceId, preparedGrpId)
                return preparedGrpId
            }

            // 3. Copy token — use source permanent's grpId
            val copiedPermanent = card.copiedPermanent
            if (copiedPermanent != null) {
                val sourceGrpId =
                    resolveCopiedPermanentGrpId(copiedPermanent, cards)
                        ?: run {
                            reportMissingOnce("copy token", copiedPermanent.name, card.id)
                            return GameBridge.FALLBACK_GRPID
                        }
                if (instanceId != 0) tokenRegistry.register(instanceId, sourceGrpId)
                return sourceGrpId
            }

            // 4. Standard token — AbilityIdToLinkedTokenGrpId lookup
            val tokenGrpId = resolveTokenGrpId(card, cards)
            if (tokenGrpId != null) {
                if (instanceId != 0) tokenRegistry.register(instanceId, tokenGrpId)
                return tokenGrpId
            }

            resolveTokenByName(card, cards)?.let { nameGrpId ->
                if (instanceId != 0) tokenRegistry.register(instanceId, nameGrpId)
                return nameGrpId
            }
            reportMissingOnce("token", card.name, card.id)
            DevCheck.fail { "token grpId=0 for '${card.name}' (forgeId=${card.id})" }
            return GameBridge.FALLBACK_GRPID
        }

        // Foretold cards are face-down in exile — Forge's `card.name` is "" while
        // face-down, which would crash the strict resolver. Look up via the
        // Original state's name (the underlying card identity) instead.
        if (Foretell.isForetold(card)) {
            val originalName =
                card.getOriginalState(forge.card.CardStateName.Original)?.name ?: card.name
            return resolveByName(originalName, cards)
                ?: GameBridge.FALLBACK_GRPID
        }

        // Supported face-down cards retain their underlying grpId while their
        // visible identity is suppressed by ObjectMapper.
        if (FaceDown.kind(card) != null) {
            val originalName =
                card.getOriginalState(forge.card.CardStateName.Original)?.name ?: card.name
            return resolveByName(originalName, cards)
                ?: GameBridge.FALLBACK_GRPID
        }

        val secondary = forge.card.CardStateName.Secondary
        val isLinkedSecondarySpell = card.isAdventureCard || (card.hasState(secondary) && card.getState(secondary).type.hasSubtype("Omen"))
        if ((card.isInZone(forge.game.zone.ZoneType.Stack) && (card.isSplitCard || isLinkedSecondarySpell)) || card.isSpecialized) {
            val parentName = card.getOriginalState(forge.card.CardStateName.Original)?.name
            val parentGrpId = parentName?.let(cards::findGrpIdByName)
            parentGrpId
                ?.let(cards::findLinkedFaces)
                ?.singleOrNull { cards.findNameByGrpId(it) == card.name }
                ?.let { return it }
        }

        // Rooms (split enchantments with two doors) carry the parent grpId
        // everywhere except on the stack — Forge's active state flips to
        // LeftSplit / RightSplit when a door unlocks, but the projected card
        // identity must remain the parent (Original) so the client renders the
        // full room with both door names + abilities. Look up via Original
        // state's name when off-stack; let the stack branch fall through to
        // the active-state name for the per-door face grpId.
        if (card.isRoom && !card.isInZone(forge.game.zone.ZoneType.Stack)) {
            val originalName =
                card.getOriginalState(forge.card.CardStateName.Original)?.name?.takeIf { it.isNotEmpty() }
                    ?: card.name
            resolveByName(originalName, cards)?.let { return it }
        }

        // A transformed DFC's back-face name can occur in more than one print
        // family. A plain any-face lookup is therefore not stable: it can turn
        // a normal Clive into the borderless Ifrit printing merely because that
        // row happens to be returned first. Resolve the original face first,
        // then follow *that exact printing's* linked-face relationship.
        resolvePairedTransformFace(card, cards)?.let { return it }

        // Primary-face lookup, falling back to any-face for DFC back faces
        // (e.g. saga transforms to Echo of Death's Wail — the back face lives in
        // the Arena DB under a non-primary flag; findGrpIdByName misses it).
        return resolveCloneSourceGrpId(card, cards)
            ?: resolveByCardName(card, cards)
            ?: unsupportedNonDbFallback(card)
            ?: run {
                reportMissingOnce("standard", card.name, card.id)
                DevCheck.fail { "grpId=0 for '${card.name}' (forgeId=${card.id}): not in client card DB" }
                GameBridge.FALLBACK_GRPID
            }
    }

    /** Resolve token grpId via source card's AbilityIdToLinkedTokenGrpId mapping. */
    private fun resolveTokenGrpId(
        card: Card,
        cards: CardRepository,
    ): Int? {
        val sourceCard = card.tokenSpawningAbility?.hostCard ?: return null
        // Try current state name first (e.g. "Pest Problem" for adventure on stack),
        // then primary face name as fallback. Token mappings in Arena DB can be on
        // either face — adventure tokens map from the adventure face grpId.
        val sourceGrpId =
            resolveByCardName(sourceCard, cards)
                ?: return null
        return cards.tokenGrpIdForCard(sourceGrpId, card.name)
    }

    private fun resolveByCardName(
        card: Card,
        cards: CardRepository,
    ): Int? =
        resolveByName(card.name, cards)
            ?: card.displayName.takeIf { it != card.name }?.let { resolveByName(it, cards) }
            ?: card.getOriginalState(forge.card.CardStateName.Original)?.name?.takeIf { it.isNotEmpty() }?.let {
                resolveByName(it, cards)
            }

    private fun resolveCloneSourceGrpId(
        card: Card,
        cards: CardRepository,
    ): Int? {
        val candidates = mutableListOf<Card>()
        activeCloneSource(card)?.let { candidates += it }
        card.cloneOrigin?.let { candidates += it }
        if (card.isCloned) {
            candidates += card.remembered.filterIsInstance<Card>()
        }
        return candidates.firstNotNullOfOrNull { source ->
            if (source.isToken) {
                resolveTokenByName(source, cards) ?: resolveByCardName(source, cards)
            } else {
                resolveByCardName(source, cards)
            }
        }
    }

    /** Remembered cards are temporary; the clone layer's origin survives their cleanup. */
    internal fun activeCloneSource(card: Card): Card? =
        card.cloneStates.entries
            .maxByOrNull { it.key }
            ?.value
            ?.origin
            ?.takeIf { it.id != card.id }

    private fun resolveCopiedPermanentGrpId(
        copiedPermanent: Card,
        cards: CardRepository,
    ): Int? =
        if (copiedPermanent.isToken) {
            resolveTokenByName(copiedPermanent, cards) ?: resolveByCardName(copiedPermanent, cards)
        } else {
            resolveByCardName(copiedPermanent, cards)
        }

    private fun unsupportedNonDbFallback(card: Card): Int? {
        val originalName = card.getOriginalState(forge.card.CardStateName.Original)?.name.orEmpty()
        if (card.isCloned && card.cloneOrigin == null && !card.remembered.iterator().hasNext()) {
            reportUnsupportedOnce("clone", card.name.ifEmpty { originalName }, card.id)
            return GameBridge.FALLBACK_GRPID
        }
        if (card.currentStateName == forge.card.CardStateName.FaceDown && card.name.isEmpty() && originalName.isNotEmpty()) {
            reportUnsupportedOnce("face-down", originalName, card.id)
            return GameBridge.FALLBACK_GRPID
        }
        return null
    }

    private fun resolveByName(
        name: String,
        cards: CardRepository,
    ): Int? =
        cards.findGrpIdByName(name)
            ?: cards.findGrpIdByNameAnyFace(name)

    private fun resolvePairedTransformFace(
        card: Card,
        cards: CardRepository,
    ): Int? {
        if (card.currentStateName != forge.card.CardStateName.Backside || !card.isDoubleFaced) return null
        val originalName = card.getOriginalState(forge.card.CardStateName.Original)?.name?.takeIf { it.isNotEmpty() } ?: return null
        val originalGrpId = cards.findGrpIdByName(originalName) ?: return null
        return cards.findLinkedFaces(originalGrpId).singleOrNull { linkedGrpId ->
            cards.findNameByGrpId(linkedGrpId) == card.name
        }
    }

    private fun resolveTokenByName(
        card: Card,
        cards: CardRepository,
    ): Int? =
        ImageKeys
            .getTokenImageName(card.imageKey)
            ?.substringBefore('|')
            ?.let(cards::findTokenGrpIdByScript)
            ?: cards.findTokenGrpIdByName(card.name)
            ?: card.displayName.takeIf { it != card.name }?.let { cards.findTokenGrpIdByName(it) }
}
