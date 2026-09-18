package leyline.tooling.headless

import forge.game.card.Card
import forge.model.FModel
import leyline.game.InMemoryCardRepository
import leyline.game.codes.SlotKind
import leyline.game.data.AbilityInfo
import leyline.game.data.CardData
import leyline.game.data.ModalAbilityInfo
import leyline.game.data.TestCardFixtures
import org.slf4j.LoggerFactory

/**
 * Joining layer between [TestCardFixtures] (client identity) and Forge
 * (rules data). The single entry point test setup paths use to register
 * a card by name into an [InMemoryCardRepository].
 *
 * Lives in the harness source set with the rest of the fixture/test
 * tooling: nothing in production consumes it — runtime card data comes
 * from [leyline.game.data.ClientCardDatabase].
 *
 * Source of truth for grpId, ability ids, token map, linked faces is the
 * YAML fixture under `engine/src/test/resources/test-cards/`. For Slim
 * fixtures (rules absent), rules data is derived from Forge's `CardRules`.
 * For Full fixtures (tokens, Alchemy), data is self-contained in YAML.
 *
 * Errors loudly when a fixture is missing — no synthetic-grpId fallback.
 * If a test needs a card without a fixture, generate one with the
 * `card-fixtures emit "<Card Name>"` tool.
 */
object FixtureCardLoader {
    @Suppress("UnusedPrivateProperty")
    private val log = LoggerFactory.getLogger(FixtureCardLoader::class.java)

    /**
     * Register [cardName] (and its closure: linked faces, produced tokens)
     * into [repo]. Idempotent — if [cardName] already resolves in [repo],
     * returns its grpId without re-registering.
     *
     * Synchronized: Forge's `StaticData.attemptToLoadCard` mutates static
     * state (used by [forgeHas] and [loadForgeCard]). Concurrent Kotest
     * specs would race on it without this lock.
     */
    @Synchronized
    fun ensureCardRegistered(
        repo: InMemoryCardRepository,
        cardName: String,
    ): Int {
        repo.findGrpIdByName(cardName)?.let { return it }

        val closure = findClosure(cardName)
        if (closure.isEmpty()) {
            // No fixture. If Forge has no entry either, treat as engine-internal
            // (Puzzle Goal, DetachedCardEffect, etc.) — return 0 quietly. If
            // Forge knows the name, the card is real and a fixture is missing.
            if (forgeHas(cardName)) {
                error(
                    "No fixture for '$cardName' under engine/src/test/resources/test-cards/. " +
                        "Generate via `card-fixtures emit \"$cardName\"`.",
                )
            }
            log.debug("Skipping '{}' — not in fixtures or Forge (engine-internal)", cardName)
            return 0
        }

        for (f in closure) {
            // Skip if a closure entry has already been registered (e.g. shared
            // tokens across saga chapters, or token-producer + token registered
            // separately by sibling tests).
            if (repo.findByGrpId(f.identity.grpId) != null) continue
            register(repo, f)
        }

        return repo.findGrpIdByName(cardName)
            ?: TestCardFixtures
                .findFixture(cardName)
                ?.identity
                ?.name
                ?.let(repo::findGrpIdByName)
            ?: error("Closure for '$cardName' resolved but did not register the named card itself")
    }

    /**
     * The named card plus its closure (linked faces, produced tokens),
     * walked breadth-first through fixture `linkedFaces` and `tokens`.
     */
    private fun findClosure(name: String): List<TestCardFixtures.Fixture> {
        val root = TestCardFixtures.findFixture(name) ?: return emptyList()
        val seen = mutableSetOf<Int>()
        val ordered = mutableListOf<TestCardFixtures.Fixture>()
        val stack = ArrayDeque<Int>()
        stack.add(root.identity.grpId)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            val f = TestCardFixtures.findFixtureByGrpId(id) ?: continue
            ordered += f
            stack.addAll(f.identity.linkedFaces)
            stack.addAll(f.identity.tokens.values)
        }
        return ordered
    }

    /** Build CardData and register a single fixture. Slim ⇒ thread Forge for rules. */
    private fun register(
        repo: InMemoryCardRepository,
        fixture: TestCardFixtures.Fixture,
    ) {
        val data =
            if (fixture.rules != null) {
                buildFromFixtureRules(fixture)
            } else {
                CardDataDeriver.fromForgeCardWithIdentity(loadForgeCard(fixture.identity.name), fixture.identity)
            }
        repo.registerData(data, fixture.identity.name)
        registerAbilityMetadata(repo, fixture.identity)
    }

    private fun buildFromFixtureRules(fixture: TestCardFixtures.Fixture): CardData {
        val identity = fixture.identity
        val rules = requireNotNull(fixture.rules)
        val abilityIds = identity.abilities.map { it.id to it.textId }
        val abilityKinds = identity.abilities.map { ab -> SlotKind.fromAbilityInfo(ab.category, ab.subCategory) }
        val abilityCategories = identity.abilities.map { it.category }
        return CardData(
            grpId = identity.grpId,
            titleId = identity.titleId,
            power = rules.power,
            toughness = rules.toughness,
            colors = rules.colors,
            types = rules.types,
            subtypes = rules.subtypes,
            supertypes = rules.supertypes,
            abilityIds = abilityIds,
            abilityKinds = abilityKinds,
            abilityCategories = abilityCategories,
            manaCost = rules.manaCost,
            tokenGrpIds = identity.tokens,
            linkedFaceType = identity.linkedFaceType,
            linkedFaceGrpIds = identity.linkedFaces,
        )
    }

    /** Register the [AbilityInfo] and [ModalAbilityInfo] entries from the fixture's ability list. */
    private fun registerAbilityMetadata(
        repo: InMemoryCardRepository,
        identity: TestCardFixtures.Identity,
    ) {
        for (ab in identity.abilities + identity.hiddenAbilities) {
            repo.registerAbilityInfo(ab.id, AbilityInfo(ab.baseId, ab.activationMana, ab.category, ab.subCategory))
            if (ab.modalChildren.isNotEmpty()) {
                repo.registerModalOptions(identity.grpId, ModalAbilityInfo(ab.id, ab.modalChildren))
            }
        }
    }

    private fun forgeHas(cardName: String): Boolean {
        val db = FModel.getMagicDb()?.commonCards ?: return false
        return db.getCard(cardName) != null ||
            run {
                forge.StaticData.instance().attemptToLoadCard(cardName)
                db.getCard(cardName) != null
            }
    }

    private fun loadForgeCard(cardName: String): Card {
        val db =
            FModel.getMagicDb()?.commonCards
                ?: error("Forge card DB not initialized — call GameBootstrap.initializeCardDatabase first")
        val paperCard =
            db.getCard(cardName)
                ?: run {
                    forge.StaticData.instance().attemptToLoadCard(cardName)
                    db.getCard(cardName)
                }
                ?: error(
                    "Slim fixture for '$cardName' but Forge has no entry. " +
                        "Either the fixture should be Full (regenerate with `card-fixtures emit`) " +
                        "or the card name has drifted between the client and Forge.",
                )
        return Card.fromPaperCard(paperCard, null)
    }
}
