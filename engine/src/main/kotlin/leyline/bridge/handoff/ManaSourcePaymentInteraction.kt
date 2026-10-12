package leyline.bridge.handoff

import leyline.bridge.types.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/** One immutable candidate in a coordinator-owned mana-source payment window. */
data class ManaSourcePaymentCandidateValue(
    val originalOptionIndex: Int,
    val forgeCardId: ForgeCardId,
    val grpId: Int,
    val fromCreature: Boolean,
    val paymentColor: ManaColor,
    val costColor: ManaColor,
)

/** One accepted source payment retained across iterative PayCosts presentations. */
data class ManaSourcePaymentSelectionValue(
    val originalOptionIndex: Int,
    val forgeCardId: ForgeCardId,
    val paymentColor: ManaColor,
    val costColor: ManaColor,
)

/** Immutable materialization input for one iterative mana-source payment presentation. */
data class ManaSourcePaymentWindowValue(
    val kind: ManaSourcePaymentKind,
    val candidates: List<ManaSourcePaymentCandidateValue>,
    val selections: List<ManaSourcePaymentSelectionValue>,
    val manaCost: List<Pair<ManaColor, Int>>,
    val sourceForgeCardId: ForgeCardId?,
    val sourceGrpId: Int,
    val sourceAbilityGrpId: Int,
    val costString: String?,
    val defaultOptionIndex: Int,
    val maxSelection: Int,
)

/** Current client-correlated identity of an iterative mana-source payment window. */
data class PublishedManaSourcePaymentInteraction(
    val interactionId: String,
    val gameStateId: Int,
    val kind: ManaSourcePaymentKind,
)

data class ManaSourcePaymentCommandReceipt(
    val interactionId: String,
    val completed: Boolean,
    val deliveryToken: Long? = null,
)

/** Exact engine result, including the shard selected for each original option. */
data class ManaSourcePaymentResult(
    val optionIndices: List<Int>,
    val shards: List<ManaSourcePaymentShardValue>,
    /** Abort the entire payment, distinct from completing with no reducer sources. */
    val cancelled: Boolean = false,
) : List<Int> by optionIndices

data class ManaSourcePaymentShardValue(
    val originalOptionIndex: Int,
    val costColor: ManaColor,
)

/** Final engine payment truth used to replace any provisional presentation facts. */
data class FinalManaSourcePaymentValue(
    val kind: ManaSourcePaymentKind,
    val sourceForgeCardId: ForgeCardId,
    val payments: List<FinalManaSourcePaymentEntryValue>,
)

data class FinalManaSourcePaymentEntryValue(
    val paymentForgeCardId: ForgeCardId,
    val paymentColor: ManaColor,
)

class ManaSourcePaymentTimeoutException : RuntimeException("Mana-source payment timed out")
