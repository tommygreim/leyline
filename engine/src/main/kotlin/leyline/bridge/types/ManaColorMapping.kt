package leyline.bridge.types

import forge.card.mana.ManaCost
import forge.card.mana.ManaCostShard
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Single source of truth for Forge mana representation → client [ManaColor] mapping.
 *
 * Two entry points:
 * - [fromShard] — [ManaCostShard] enum (used by card cost derivation)
 * - [fromProduced] — string like "W", "G", "Any" (used by mana ability / action mapping)
 *
 * Also provides [deriveManaCost] for converting a Forge [forge.card.mana.ManaCost]
 * into the `List<Pair<ManaColor, Int>>` format used by [leyline.game.data.CardData].
 */
object ManaColorMapping {
    private val WUBRG_SHARDS =
        listOf(
            ManaCostShard.WHITE,
            ManaCostShard.BLUE,
            ManaCostShard.BLACK,
            ManaCostShard.RED,
            ManaCostShard.GREEN,
        )

    /** ManaCostShard → proto ManaColor. Choice-bearing shards are handled by [requirementColors]. */
    val SHARD_MAP: Map<ManaCostShard, ManaColor> =
        mapOf(
            ManaCostShard.WHITE to ManaColor.White_afc9,
            ManaCostShard.BLUE to ManaColor.Blue_afc9,
            ManaCostShard.BLACK to ManaColor.Black_afc9,
            ManaCostShard.RED to ManaColor.Red_afc9,
            ManaCostShard.GREEN to ManaColor.Green_afc9,
            ManaCostShard.COLORLESS to ManaColor.Colorless_afc9,
            ManaCostShard.S to ManaColor.Snow_afc9,
            ManaCostShard.X to ManaColor.X,
        )

    /** Map Forge's produced-mana string (e.g. "G", "W", "Any") to proto ManaColor. */
    fun fromProduced(produced: String): ManaColor? =
        when (produced.uppercase().trim()) {
            "W" -> ManaColor.White_afc9
            "U" -> ManaColor.Blue_afc9
            "B" -> ManaColor.Black_afc9
            "R" -> ManaColor.Red_afc9
            "G" -> ManaColor.Green_afc9
            "C" -> ManaColor.Colorless_afc9
            // The client's own AnyColorSelection expands a single AnyColor tag
            // into a real WUBRG picker client-side — it does not need this
            // server to enumerate five ManaPaymentOptions itself. Previously
            // tagged Generic, which collapsed an "add one mana of any color"
            // source (Birds of Paradise, ...) into what looks like a plain
            // colorless/generic-only producer. See ISSUES.md I43.
            "ANY" -> ManaColor.AnyColor
            else -> null
        }

    /** Map a [ManaCostShard] to proto ManaColor, or null if unmapped (hybrid, etc.). */
    fun fromShard(shard: ManaCostShard): ManaColor? = SHARD_MAP[shard]

    /**
     * The colours that identify a Phyrexian pip on the wire.  The client
     * represents `{B/P}` as `[Black, Phyrexian]` (and the two-colour form as
     * `[Black, Green, Phyrexian]`), rather than as a single opaque colour.
     */
    fun phyrexianColors(shard: ManaCostShard): List<ManaColor> =
        buildList {
            if (shard.isWhite) add(ManaColor.White_afc9)
            if (shard.isBlue) add(ManaColor.Blue_afc9)
            if (shard.isBlack) add(ManaColor.Black_afc9)
            if (shard.isRed) add(ManaColor.Red_afc9)
            if (shard.isGreen) add(ManaColor.Green_afc9)
            add(ManaColor.Phyrexian_afc9)
        }

    /** Alternative selector used by the cast-time ManaType prompt. */
    fun manaTypeAlternative(shard: ManaCostShard): ManaColor? =
        when {
            shard.isPhyrexian() -> ManaColor.Phyrexian_afc9
            shard.isOr2Generic() && shard.isMonoColor -> ManaColor.TwoGeneric
            standardHybridColors(shard).size > 1 -> standardHybridColors(shard)[1]
            else -> null
        }

    /** Colours accepted when a choice-bearing pip is paid with mana. */
    fun paymentColors(shard: ManaCostShard): List<ManaColor> =
        when {
            shard.isPhyrexian() -> phyrexianColors(shard).dropLast(1)
            shard.isOr2Generic() && shard.isMonoColor -> listOf(fromOrTwoGenericShard(shard)!!)
            standardHybridColors(shard).size > 1 -> standardHybridColors(shard)
            else -> emptyList()
        }

    /** Full ManaRequirement colour list for one Forge shard. */
    fun requirementColors(shard: ManaCostShard): List<ManaColor> =
        when {
            shard.isPhyrexian() -> phyrexianColors(shard)
            shard.isOr2Generic() && shard.isMonoColor -> listOf(ManaColor.TwoGeneric, fromOrTwoGenericShard(shard)!!)
            standardHybridColors(shard).size > 1 -> standardHybridColors(shard)
            else -> fromShard(shard)?.let(::listOf).orEmpty()
        }

    /** Ordinary two-way hybrid choices, including colorless hybrids. */
    fun standardHybridColors(shard: ManaCostShard): List<ManaColor> {
        if (shard.isPhyrexian() || shard.isOr2Generic()) return emptyList()
        val colors =
            shard
                .toShortString()
                .split('/')
                .mapNotNull(::fromProduced)
        return colors.takeIf { it.size > 1 }.orEmpty()
    }

    fun fromOrTwoGenericShard(shard: ManaCostShard): ManaColor? {
        if (!shard.isOr2Generic() || !shard.isMonoColor) return null
        return when {
            shard.isWhite -> ManaColor.White_afc9
            shard.isBlue -> ManaColor.Blue_afc9
            shard.isBlack -> ManaColor.Black_afc9
            shard.isRed -> ManaColor.Red_afc9
            shard.isGreen -> ManaColor.Green_afc9
            else -> null
        }
    }

    fun monoColorShard(color: ManaColor): ManaCostShard? =
        when {
            color == ManaColor.White_afc9 -> ManaCostShard.WHITE
            color == ManaColor.Blue_afc9 -> ManaCostShard.BLUE
            color == ManaColor.Black_afc9 -> ManaCostShard.BLACK
            color == ManaColor.Red_afc9 -> ManaCostShard.RED
            color == ManaColor.Green_afc9 -> ManaCostShard.GREEN
            else -> null
        }

    fun paymentShard(color: ManaColor): ManaCostShard? = if (color == ManaColor.Generic) ManaCostShard.GENERIC else monoColorShard(color)

    fun paymentWireColor(shard: ManaCostShard): ManaColor =
        when {
            shard == ManaCostShard.WHITE -> ManaColor.White_afc9
            shard == ManaCostShard.BLUE -> ManaColor.Blue_afc9
            shard == ManaCostShard.BLACK -> ManaColor.Black_afc9
            shard == ManaCostShard.RED -> ManaColor.Red_afc9
            shard == ManaCostShard.GREEN -> ManaColor.Green_afc9
            else -> ManaColor.Colorless_afc9
        }

    fun paymentCostColor(shard: ManaCostShard): ManaColor =
        if (shard == ManaCostShard.GENERIC) ManaColor.Generic else paymentWireColor(shard)

    fun paymentShardCounts(cost: List<Pair<ManaColor, Int>>): Map<ManaCostShard, Int> =
        buildMap {
            for ((color, count) in cost) {
                val shard = paymentShard(color) ?: continue
                put(shard, count)
            }
        }

    fun colorCounts(cost: ManaCost): Map<ManaColor, Int> =
        buildMap {
            // CostAdjustment can rebuild Forge's shard list in a different
            // order at each priority stop. Sort the client colors so a card's
            // displayed pips stay fixed while the cost itself is unchanged.
            val counts =
                cost
                    .mapNotNull { shard -> fromShard(shard) ?: ManaColor.Phyrexian_afc9.takeIf { shard.isPhyrexian() } }
                    .groupingBy { it }
                    .eachCount()
            for (color in counts.keys.sortedBy { it.number }) {
                put(color, counts.getValue(color))
            }
        }

    fun deriveManaCostWithGenericLast(cost: ManaCost): List<Pair<ManaColor, Int>> {
        val result = colorCounts(cost).map { (color, count) -> color to count }.toMutableList()
        val generic = cost.genericCost
        if (generic > 0) result.add(ManaColor.Generic to generic)
        return result
    }

    fun deriveWubrgCostWithGenericFirst(cost: ManaCost): List<Pair<ManaColor, Int>> =
        buildList {
            if (cost.genericCost > 0) add(ManaColor.Generic to cost.genericCost)
            for (shard in WUBRG_SHARDS) {
                val count = cost.getShardCount(shard)
                val color = fromShard(shard) ?: continue
                if (count > 0) add(color to count)
            }
        }

    /**
     * Derive `(ManaColor, count)` pairs from a Forge [ManaCost].
     * Shared by [PuzzleCardRegistrar] and test `CardDataDeriver`.
     */
    fun deriveManaCost(cost: ManaCost?): List<Pair<ManaColor, Int>> {
        if (cost == null || cost.isNoCost) return emptyList()
        val counts = mutableMapOf<ManaColor, Int>()
        val generic = cost.genericCost
        if (generic > 0) counts[ManaColor.Generic] = generic
        for (shard in cost) {
            val color = fromShard(shard) ?: ManaColor.Phyrexian_afc9.takeIf { shard.isPhyrexian() } ?: continue
            counts.merge(color, 1, Int::plus)
        }
        return counts.toList()
    }
}
