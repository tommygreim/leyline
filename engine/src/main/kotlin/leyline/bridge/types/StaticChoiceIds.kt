package leyline.bridge.types

import wotc.mtgo.gre.external.messaging.Messages.CardColor
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor
import wotc.mtgo.gre.external.messaging.Messages.SubType
import wotc.mtgo.gre.external.messaging.Messages.CounterType as MessagesCounterType

/** Arena static-list ids used by enum-domain SelectN prompts. */
object StaticChoiceIds {
    private val cardColorByKey: Map<String, Int> =
        CardColor
            .values()
            .asSequence()
            .filter { it.name != "UNRECOGNIZED" }
            .associateBy(
                keySelector = { normalize(it.name.substringBefore('_')) },
                valueTransform = { it.number },
            )

    private val subtypeByKey: Map<String, Int> =
        SubType
            .values()
            .asSequence()
            .filter { it.name != "UNRECOGNIZED" }
            .filter { it.number > 0 }
            .filterNot { it.name.startsWith("PlaceholderSubType") }
            .associateBy(
                keySelector = { normalize(it.name.substringBefore('_')) },
                valueTransform = { it.number },
            )

    private val cardTypeByKey: Map<String, Int> =
        CardType
            .values()
            .asSequence()
            .filter { it.name != "UNRECOGNIZED" }
            .filter { it.number > 0 }
            .associateBy(
                keySelector = { normalize(it.name.substringBefore('_')) },
                valueTransform = { it.number },
            )

    private val counterTypeByKey: Map<String, Int> =
        buildMap {
            MessagesCounterType
                .values()
                .asSequence()
                .filter { it.name != "UNRECOGNIZED" }
                .filter { it.number > 0 }
                .forEach { put(normalize(it.name.substringBefore('_')), it.number) }
            // Forge uses display names for the two power/toughness counters;
            // Arena's wire enum retains their canonical P1P1/M1M1 names.
            put("p1p1", MessagesCounterType.P1P1.number)
            put("m1m1", MessagesCounterType.M1M1.number)
        }

    private val basicLandTypeByKey: Map<String, Int> =
        mapOf(
            "plains" to ManaColor.White_afc9.number,
            "island" to ManaColor.Blue_afc9.number,
            "swamp" to ManaColor.Black_afc9.number,
            "mountain" to ManaColor.Red_afc9.number,
            "forest" to ManaColor.Green_afc9.number,
        )

    fun colorIdForMask(mask: Byte): Int? = WubrgColorMapping.staticIdForMagicMask(mask)

    fun colorIdForName(name: String): Int? = WubrgColorMapping.staticIdForName(name)

    /** `StaticList.CardColors` includes Colorless, unlike the WUBRG-only Colors list. */
    fun cardColorIdForName(name: String): Int? = cardColorByKey[normalize(name)]

    fun parityIdForName(name: String): Int? =
        when (normalize(name)) {
            "even", "evens" -> 0
            "odd", "odds" -> 1
            else -> null
        }

    fun subtypeIdFor(typeName: String): Int? = subtypeByKey[normalize(typeName)]

    /** Forge's `CoreType` name ("Creature", "Kindred", …) to the proto `CardType` id. */
    fun cardTypeIdFor(typeName: String): Int? = cardTypeByKey[normalize(typeName)]

    /** Arena's ManaColors list uses the same stable WUBRG numbers as Colors. */
    fun manaColorIdForMask(mask: Byte): Int? = WubrgColorMapping.staticIdForMagicMask(mask)

    fun manaColorIdForName(name: String): Int? =
        ManaColor
            .values()
            .firstOrNull { it.name != "UNRECOGNIZED" && normalize(it.name.substringBefore('_')) == normalize(name) }
            ?.number

    /** SelectBasicLandWorkflow submits the WUBRG ManaColor values (1..5). */
    fun basicLandTypeIdFor(typeName: String): Int? = basicLandTypeByKey[normalize(typeName)]

    /** Forge exposes custom counter names; only protocol enum values are representable. */
    fun counterTypeIdFor(typeName: String): Int? =
        when (typeName.replace(" ", "")) {
            "+1/+1" -> MessagesCounterType.P1P1.number
            "-1/-1" -> MessagesCounterType.M1M1.number
            else -> counterTypeByKey[normalize(typeName)]
        }

    private fun normalize(value: String): String = value.filter { it.isLetterOrDigit() }.lowercase()
}
