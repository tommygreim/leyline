package leyline.tooling.headless

import forge.card.CardType.CoreType
import forge.card.CardType.Supertype
import forge.game.card.Card
import leyline.bridge.types.ManaColorMapping
import leyline.game.codes.SlotKind
import leyline.game.data.CardData
import leyline.game.data.TestCardFixtures

/**
 * Derives [CardData] from Forge's in-memory `CardRules`, stamped with client
 * identity from a YAML fixture under
 * `engine/src/test/resources/test-cards/`.
 *
 * Lives in the harness source set with the rest of the fixture/test
 * tooling: it backs [FixtureCardLoader], which nothing in production
 * consumes.
 *
 * Forge owns the rules data (P/T, types, subtypes, supertypes, colors, mana
 * cost). The fixture supplies the client identity (grpId, ability ids paired
 * with category/baseId, token map, linked faces). [FixtureCardLoader] is the
 * normal entry point; tests that need to re-derive after a card gains a
 * player context (planeswalker abilities only populate after
 * `TestCardInjector.inject`) call [fromForgeCard] directly with the card
 * name.
 *
 * Saga chapters resolve through [CardData.abilityCategories]: chapter
 * abilities are trigger rows (Category = 2) in chapter order, and
 * `ZoneMapper.chapterGrpIdFromCardData` filters to those rows — matching the
 * prod `SqliteCardRepository` shape, including read-ahead sagas whose
 * leading "Read ahead" static row (Category = 3) must be skipped.
 */
object CardDataDeriver {
    /**
     * Derive [CardData] from a Forge [Card]; client identity is looked up
     * from the named fixture. Errors loudly when no fixture exists.
     */
    fun fromForgeCard(
        card: Card,
        cardName: String,
    ): CardData {
        val identity =
            TestCardFixtures.findFixture(cardName)?.identity
                ?: error("No fixture for '$cardName' under engine/src/test/resources/test-cards/.")
        return fromForgeCardWithIdentity(card, identity)
    }

    /**
     * Identity-already-in-hand entry — used by [FixtureCardLoader] when
     * walking a fixture closure. Most callers should prefer [fromForgeCard].
     */
    internal fun fromForgeCardWithIdentity(
        card: Card,
        identity: TestCardFixtures.Identity,
    ): CardData {
        val type = card.type
        val rules = card.rules

        val types = type.coreTypes.mapNotNull { CORE_TYPE_MAP[it] }
        val supertypes = type.supertypes.mapNotNull { SUPERTYPE_MAP[it] }
        val subtypes = type.subtypes.mapNotNull { SUBTYPE_MAP[it.lowercase()] }

        val colorSet = rules.color
        val colors = mutableListOf<Int>()
        if (colorSet.hasWhite()) colors.add(1)
        if (colorSet.hasBlue()) colors.add(2)
        if (colorSet.hasBlack()) colors.add(3)
        if (colorSet.hasRed()) colors.add(4)
        if (colorSet.hasGreen()) colors.add(5)

        val power = if (type.isCreature) rules.intPower.let { if (it == Integer.MAX_VALUE) "0" else it.toString() } else ""
        val toughness = if (type.isCreature) rules.intToughness.let { if (it == Integer.MAX_VALUE) "0" else it.toString() } else ""

        val manaCost = ManaColorMapping.deriveManaCost(rules.manaCost)

        val abilityIds = identity.abilities.map { it.id to it.textId }
        val abilityKinds = identity.abilities.map { ab -> SlotKind.fromAbilityInfo(ab.category, ab.subCategory) }
        val abilityCategories = identity.abilities.map { it.category }

        return CardData(
            grpId = identity.grpId,
            titleId = identity.titleId,
            power = power,
            toughness = toughness,
            colors = colors,
            types = types,
            subtypes = subtypes,
            supertypes = supertypes,
            abilityIds = abilityIds,
            hiddenAbilityIds = identity.hiddenAbilities.map { it.id to it.textId },
            abilityKinds = abilityKinds,
            abilityCategories = abilityCategories,
            manaCost = manaCost,
            tokenGrpIds = identity.tokens,
            linkedFaceType = identity.linkedFaceType,
            linkedFaceGrpIds = identity.linkedFaces,
        )
    }

    // ---- Static mapping tables ----

    /** Forge CoreType → proto CardType int value. */
    private val CORE_TYPE_MAP =
        mapOf(
            CoreType.Artifact to 1,
            CoreType.Creature to 2,
            CoreType.Enchantment to 3,
            CoreType.Instant to 4,
            CoreType.Land to 5,
            CoreType.Phenomenon to 6,
            CoreType.Plane to 7,
            CoreType.Planeswalker to 8,
            CoreType.Scheme to 9,
            CoreType.Sorcery to 10,
            CoreType.Kindred to 11,
            CoreType.Vanguard to 12,
            CoreType.Dungeon to 13,
            CoreType.Battle to 14,
        )

    /** Forge Supertype → proto SuperType int value. */
    private val SUPERTYPE_MAP =
        mapOf(
            Supertype.Basic to 1,
            Supertype.Legendary to 2,
            Supertype.Ongoing to 3,
            Supertype.Snow to 4,
            Supertype.World to 5,
        )

    /**
     * Forge subtype name (lowercase) → proto SubType int value.
     * Covers the most common subtypes; unknown subtypes are silently skipped.
     * Extend on demand when tests need specific subtypes.
     */
    private val SUBTYPE_MAP =
        mapOf(
            // Basic land types
            "forest" to 29,
            "island" to 43,
            "mountain" to 49,
            "plains" to 54,
            "swamp" to 69,
            // Common creature types
            "angel" to 1,
            "archer" to 2,
            "archon" to 3,
            "artificer" to 4,
            "assassin" to 5,
            "aura" to 6,
            "basilisk" to 7,
            "bat" to 8,
            "bear" to 9,
            "beast" to 10,
            "berserker" to 11,
            "bird" to 12,
            "cat" to 14,
            "centaur" to 116,
            "cleric" to 16,
            "construct" to 17,
            "demon" to 19,
            "dinosaur" to 342,
            "djinn" to 20,
            "dragon" to 21,
            "drake" to 22,
            "druid" to 23,
            "dwarf" to 130,
            "elemental" to 25,
            "elephant" to 26,
            "elf" to 27,
            "equipment" to 28,
            "faerie" to 140,
            "fox" to 144,
            "frog" to 145,
            "giant" to 32,
            "goblin" to 34,
            "golem" to 35,
            "griffin" to 36,
            "horse" to 37,
            "human" to 39,
            "hydra" to 40,
            "illusion" to 41,
            "insect" to 42,
            "knight" to 45,
            "merfolk" to 46,
            "minotaur" to 47,
            "monk" to 48,
            "ogre" to 50,
            "ooze" to 51,
            "pegasus" to 52,
            "phoenix" to 53,
            "pirate" to 228,
            "plant" to 229,
            "rat" to 235,
            "rhino" to 55,
            "rogue" to 56,
            "scout" to 58,
            "serpent" to 59,
            "shade" to 60,
            "shaman" to 61,
            "shapeshifter" to 251,
            "skeleton" to 63,
            "snake" to 256,
            "soldier" to 64,
            "sphinx" to 66,
            "spider" to 67,
            "spirit" to 68,
            "treefolk" to 71,
            "troll" to 72,
            "vampire" to 74,
            "wall" to 76,
            "warrior" to 77,
            "wizard" to 78,
            "wolf" to 79,
            "wurm" to 80,
            "zombie" to 81,
            // Non-creature subtypes
            "vehicle" to 331,
            "saga" to 347,
            "shrine" to 253,
            "class" to 400,
            "curse" to 121,
            "trap" to 272,
            "clue" to 324,
            "food" to 363,
            "treasure" to 343,
            "blood" to 403,
            // Land subtypes
            "gate" to 31,
            "desert" to 123,
            "cave" to 419,
            // Additional common types
            "avatar" to 85,
            "devil" to 124,
            "dog" to 379,
            "horror" to 160,
            "imp" to 162,
            "ninja" to 215,
            "orc" to 221,
            "saproling" to 84,
            "squirrel" to 264,
            "thopter" to 269,
            "unicorn" to 275,
            "werewolf" to 283,
        )
}
