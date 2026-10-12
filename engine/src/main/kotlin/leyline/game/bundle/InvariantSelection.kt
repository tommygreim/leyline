package leyline.game.bundle

/** Named checks understood by [InvariantChecker]. */
enum class InvariantCheck(
    val id: String,
) {
    MsgIdMonotonicity("msgid_monotonicity"),
    MsgIdUnique("msgid_unique"),
    GsIdMonotonicity("gsid_monotonicity"),
    GsIdUnique("gsid_unique"),
    GsIdPrevKnown("gsid_prev_unknown"),
    GsIdNoSelfRef("gsid_self_ref"),
    AnnotationSequentiality("annotation_seq"),
    AnnotationOrdering("annotation_ordering"),
    PhaseFirst("phase_first"),
    ResolutionTransferOrdering("resolution_transfer_ordering"),
    AidAffector("aid_affector"),
    PendingMessageCount("pending_count"),
    ActionInstanceIds("action_iid"),
    ZoneObjects("zone_object"),
    AnnotationReferences("annotation_ref"),
    ClientAnnotationReferences("client_annotation_ref"),
}

/**
 * Selects which checks are active for a validator run.
 *
 * [protocolFacts] is the default: client-compatible checks safe to treat as
 * hard failures. [diagnostics] keeps the older strict shape checks available
 * for focused tests and troubleshooting, but those checks are intentionally
 * not protocol facts.
 */
class InvariantSelection private constructor(
    private val enabled: Set<InvariantCheck>,
    val relaxationReason: String?,
) {
    fun includes(check: InvariantCheck): Boolean = check in enabled

    internal fun includes(checkId: String): Boolean = enabled.any { it.id == checkId }

    fun isEmpty(): Boolean = enabled.isEmpty()

    companion object {
        private val PROTOCOL_FACTS =
            setOf(
                InvariantCheck.GsIdMonotonicity,
                InvariantCheck.GsIdUnique,
                InvariantCheck.GsIdNoSelfRef,
                InvariantCheck.AidAffector,
                InvariantCheck.ClientAnnotationReferences,
            )

        fun protocolFacts(): InvariantSelection = InvariantSelection(PROTOCOL_FACTS, relaxationReason = null)

        fun protocolFactsExcept(
            because: String,
            vararg checks: InvariantCheck,
        ): InvariantSelection = InvariantSelection(PROTOCOL_FACTS - checks.toSet(), relaxationReason = because)

        fun diagnostics(): InvariantSelection = InvariantSelection(InvariantCheck.entries.toSet(), relaxationReason = null)

        fun all(): InvariantSelection = diagnostics()

        fun none(because: String): InvariantSelection = InvariantSelection(emptySet(), relaxationReason = because)

        fun only(
            because: String,
            vararg checks: InvariantCheck,
        ): InvariantSelection = InvariantSelection(checks.toSet(), relaxationReason = because)

        fun except(
            because: String,
            vararg checks: InvariantCheck,
        ): InvariantSelection = InvariantSelection(InvariantCheck.entries.toSet() - checks.toSet(), relaxationReason = because)
    }
}
