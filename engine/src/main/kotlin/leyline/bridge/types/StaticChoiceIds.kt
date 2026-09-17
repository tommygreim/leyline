package leyline.bridge.types

import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.SubType

/** Arena static-list ids used by enum-domain SelectN prompts. */
object StaticChoiceIds {
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

    fun colorIdForMask(mask: Byte): Int? = WubrgColorMapping.staticIdForMagicMask(mask)

    fun colorIdForName(name: String): Int? = WubrgColorMapping.staticIdForName(name)

    fun parityIdForName(name: String): Int? =
        when (normalize(name)) {
            "even", "evens" -> 0
            "odd", "odds" -> 1
            else -> null
        }

    fun subtypeIdFor(typeName: String): Int? = subtypeByKey[normalize(typeName)]

    /** Forge's `CoreType` name ("Creature", "Kindred", …) to the proto `CardType` id. */
    fun cardTypeIdFor(typeName: String): Int? = cardTypeByKey[normalize(typeName)]

    private fun normalize(value: String): String = value.filter { it.isLetterOrDigit() }.lowercase()
}
