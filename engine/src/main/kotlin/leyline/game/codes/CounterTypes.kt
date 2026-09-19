package leyline.game.codes

import wotc.mtgo.gre.external.messaging.Messages.CounterType

/**
 * Forge → proto [CounterType] name resolution.
 *
 * Forge's `CounterEnumType.getName()` returns display names (`"+1/+1"`, `"LOYAL"`)
 * which differ from both the Java enum constant (`P1P1`, `LOYALTY`) and the
 * proto enum name. This object indexes both proto names and known Forge display
 * names so builders can resolve a counter type by Forge-facing string.
 */
object CounterTypes {
    private val forgeNameToProtoNumber: Map<String, Int> by lazy {
        val map = mutableMapOf<String, Int>()
        for (ct in CounterType.entries) {
            if (ct == CounterType.UNRECOGNIZED) continue
            val base = ct.name.removeSuffix("_a40e").uppercase()
            map[base] = ct.number
        }
        // Forge display names that differ from proto enum names
        map["+1/+1"] = CounterType.P1P1.number
        map["-1/-1"] = CounterType.M1M1.number
        map["LOYAL"] = CounterType.Loyalty_a40e.number
        // The checked-in proto predates the client enum entry, but the current
        // client accepts Plan at this stable protocol value.
        map["PLAN"] = 211
        map
    }

    /** Resolve a Forge counter name to its proto [CounterType] number. Unknown names return 0. */
    fun counterTypeId(forgeName: String): Int = forgeNameToProtoNumber[forgeName.uppercase()] ?: 0
}
