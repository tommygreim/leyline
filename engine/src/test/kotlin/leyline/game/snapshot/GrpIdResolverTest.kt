package leyline.game.snapshot

import forge.card.CardStateName
import forge.card.CardType
import forge.card.GamePieceType
import forge.game.card.Card
import forge.game.card.CardCloneStates
import forge.game.zone.Zone
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.InMemoryCardRepository
import leyline.game.data.CardData
import leyline.game.data.CardRepository
import leyline.game.state.GameBridge
import leyline.game.state.TokenIdentityRegistry

class GrpIdResolverTest :
    FunSpec({
        tags(UnitTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("resolves Forge flavor-name variants by display name") {
            val repo = InMemoryCardRepository()
            repo.register(12345, "The Terminus of Return")

            val card = Card(1, null, null)
            card.name = "The Soul Stone"
            card.currentState.flavorName = "The Terminus of Return"

            GrpIdResolver.resolve(card, repo) shouldBe 12345
        }

        test("resolves standard token names through token-only lookup") {
            val repo = InMemoryCardRepository()
            repo.register(87484, "Map")

            val card = Card(1, null, null)
            card.name = "Map Token"
            card.setGamePieceType(GamePieceType.TOKEN)

            GrpIdResolver.resolve(card, repo) shouldBe 87484
        }

        test("resolves copy tokens whose copied permanent is also a token") {
            val repo = InMemoryCardRepository()
            repo.register(104161, "Rabbit")
            val source = Card(1, null, null)
            source.name = "Rabbit Token"
            source.setGamePieceType(GamePieceType.TOKEN)
            val copy = Card(2, null, null)
            copy.name = "Rabbit Token"
            copy.setGamePieceType(GamePieceType.TOKEN)
            copy.setCopiedPermanent(source)

            GrpIdResolver.resolve(copy, repo) shouldBe 104161
        }

        test("resolves cloned cards through their clone origin") {
            val repo = InMemoryCardRepository()
            repo.register(12345, "Doomsday Excruciator")
            val source = Card(1, null, null)
            source.name = "Doomsday Excruciator"
            val clone = Card(2, null, null)
            clone.name = "Unsupported Override Name"
            clone.setCloneOrigin(source)

            GrpIdResolver.resolve(clone, repo) shouldBe 12345
        }

        test("falls back for unmapped clone override names") {
            val repo = InMemoryCardRepository()
            val clone = Card(1, null, null)
            clone.name = "Unsupported Override Name"
            clone.cloneStates[1L] = CardCloneStates(clone, null)

            GrpIdResolver.resolve(clone, repo) shouldBe GameBridge.FALLBACK_GRPID
        }

        test("falls back for unmapped face-down original names") {
            val repo = InMemoryCardRepository()
            val card = Card(1, null, null)
            card.name = "Unsupported Face-Down Name"
            card.turnFaceDownNoUpdate()

            GrpIdResolver.resolve(card, repo) shouldBe GameBridge.FALLBACK_GRPID
        }

        test("resolves prepared-spell copies as castable spells, not tokens") {
            val repo = NameOnlyRepository()
            repo.register(12345, "Stormchaser Drake")

            val copy = Card(1, null, null)
            copy.setGamePieceType(GamePieceType.TOKEN)
            copy.addAlternateState(CardStateName.PreparedSpell, false)
            copy.setState(CardStateName.PreparedSpell, false)
            copy.name = "Stormchaser Drake"

            // The copy is TOKEN-piece-typed but represents a normal castable
            // spell: it must resolve through the prepared-spell branch, not
            // the token-name fallback (disabled here to pin the branch).
            GrpIdResolver.resolve(copy, repo) shouldBe 12345
        }

        test("resolves unmapped active faces via the original face name") {
            val repo = InMemoryCardRepository()
            repo.register(12345, "The Terminus of Return")

            val card = Card(1, null, null)
            card.addAlternateState(CardStateName.Flipped, false)
            card.setState(CardStateName.Flipped, false)
            card.name = "Unmapped Active Face"
            card.getOriginalState(CardStateName.Original)?.setName("The Terminus of Return")

            GrpIdResolver.resolve(card, repo) shouldBe 12345
        }

        test("transformed face follows its original printing's linked-face family") {
            val repo = InMemoryCardRepository()
            // Both back faces have the same localized name. The normal front
            // belongs to 95998; a direct any-face lookup could otherwise pick
            // the alternate-style 96946 row instead.
            repo.registerData(faceData(95997, 928151, listOf(95998)), "Clive, Ifrit's Dominant")
            repo.registerData(faceData(95998, 928154, listOf(95997)), "Ifrit, Warden of Inferno")
            repo.registerData(faceData(96945, 928151, listOf(96946)), "Clive, Ifrit's Dominant (alternate)")
            repo.registerData(faceData(96946, 928154, listOf(96945)), "Ifrit, Warden of Inferno (alternate)")

            val card = Card(1, null, null)
            card.name = "Clive, Ifrit's Dominant"
            card.addAlternateState(CardStateName.Backside, false)
            card.getState(CardStateName.Backside)?.name = "Ifrit, Warden of Inferno"
            card.setState(CardStateName.Backside, false)

            GrpIdResolver.resolve(card, repo) shouldBe 95998
        }

        test("secondary stack face follows its original printing's linked-face family") {
            val repo = InMemoryCardRepository()
            repo.registerData(faceData(100, 10, listOf(101)), "Linked Hero")
            repo.registerData(faceData(101, 11, listOf(100)), "Linked Spell")
            repo.registerData(faceData(201, 11, listOf(200)), "Linked Spell")

            val card = Card(1, null, null)
            card.name = "Linked Hero"
            card.addAlternateState(CardStateName.Secondary, false)
            card.getState(CardStateName.Secondary)?.name = "Linked Spell"
            card.getState(CardStateName.Secondary)?.setType(CardType.parse("Sorcery Adventure", false))
            card.setState(CardStateName.Secondary, false)
            card.setZone(Zone(ZoneType.Stack, null))

            GrpIdResolver.resolve(card, repo) shouldBe 101
        }

        test("caches token grpIds per instanceId and reuses them without re-resolution") {
            val repo = InMemoryCardRepository()
            repo.register(87484, "Map")
            val registry = TokenIdentityRegistry()

            val token = Card(1, null, null)
            token.name = "Map Token"
            token.setGamePieceType(GamePieceType.TOKEN)

            GrpIdResolver.resolve(token, repo, instanceId = 7, tokenRegistry = registry) shouldBe 87484

            // First resolution registered the grpId; the cache wins even when
            // a fresh resolution would fail (repo emptied, name changed).
            repo.clear()
            token.name = "Map Token Gone"
            GrpIdResolver.resolve(token, repo, instanceId = 7, tokenRegistry = registry) shouldBe 87484

            // The cache is per-instance: a fresh instance re-resolves and falls back.
            GrpIdResolver.resolve(token, repo, instanceId = 8, tokenRegistry = registry) shouldBe GameBridge.FALLBACK_GRPID
        }
    })

/**
 * Minimal repository with only name lookup — the token-name and any-face
 * fallbacks stay at their interface defaults, so token resolution can only
 * succeed through a spawning-ability mapping (not exercised here) or the
 * prepared-spell branch.
 */
private class NameOnlyRepository : CardRepository {
    private val nameToGrpId = mutableMapOf<String, Int>()
    private val grpIdToName = mutableMapOf<Int, String>()

    fun register(
        grpId: Int,
        name: String,
    ) {
        nameToGrpId[name] = grpId
        grpIdToName[grpId] = name
    }

    override fun findByGrpId(grpId: Int): CardData? = null

    override fun findNameByGrpId(grpId: Int): String? = grpIdToName[grpId]

    override fun findGrpIdByName(name: String): Int? = nameToGrpId[name]

    override fun findAllGrpIds(): List<Int> = grpIdToName.keys.toList()
}

private fun faceData(
    grpId: Int,
    titleId: Int,
    linkedFaceGrpIds: List<Int>,
): CardData =
    CardData(
        grpId = grpId,
        titleId = titleId,
        power = "",
        toughness = "",
        colors = emptyList(),
        types = emptyList(),
        subtypes = emptyList(),
        supertypes = emptyList(),
        abilityIds = emptyList(),
        manaCost = emptyList(),
        linkedFaceGrpIds = linkedFaceGrpIds,
    )
