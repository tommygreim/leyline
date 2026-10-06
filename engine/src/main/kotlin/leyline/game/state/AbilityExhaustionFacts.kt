package leyline.game.state

import leyline.bridge.types.ForgeCardId
import kotlin.ConsistentCopyVisibility

/**
 * Immutable final display rows for exhausted abilities at one projection cut.
 *
 * The shell resolves live ability state, activation limits, and registry
 * mappings once. Projection only assigns the source card's tentative client
 * identity and builds persistent annotations from these values.
 */
@ConsistentCopyVisibility
data class AbilityExhaustionFacts private constructor(
    val rows: List<Row>,
) {
    companion object {
        operator fun invoke(rows: List<Row> = emptyList()): AbilityExhaustionFacts =
            AbilityExhaustionFacts(java.util.Collections.unmodifiableList(rows.toList()))
    }

    data class Row(
        val sourceForgeCardId: ForgeCardId,
        val abilityGrpId: Int,
        val usesRemaining: Int,
        val uniqueAbilityId: Int,
        /** For modal abilities, the parent followed by the unavailable child modes. */
        val exhaustedGrpIds: List<Int> = listOf(abilityGrpId),
    )
}
