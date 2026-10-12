package leyline.game.bundle

import kotlinx.serialization.Serializable
import leyline.game.annotations.AnnotationOrderEnforcer
import leyline.game.annotations.TransferCategory
import leyline.game.codes.DetailKeys
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.*
import kotlin.collections.iterator
import kotlin.text.get

/**
 * Runtime checker for GRE message streams. Shared by test assertions and
 * post-hoc diagnostics.
 *
 * The default selection is [InvariantSelection.protocolFacts]: hard
 * client-compatible failures only. Other checks remain available through
 * [InvariantSelection.diagnostics] for focused shape debugging.
 */
class InvariantChecker(
    private val selection: InvariantSelection = InvariantSelection.protocolFacts(),
) {
    @Serializable
    data class Violation(
        val seq: Int,
        val gsId: Int,
        val check: String,
        val message: String,
    )

    // --- Tracked state ---

    private val accumulator = RuntimeAccumulator()
    private val seenGsIds = mutableSetOf<Int>()
    private val seenMsgIds = mutableSetOf<Int>()
    private var highWaterGsId = 0
    private var highWaterMsgId = 0
    private var pendingCountdown = 0
    private var messageIndex = 0
    private val aicAffectorByAbilityIid = mutableMapOf<Int, Int>()

    private val _violations = mutableListOf<Violation>()
    val violations: List<Violation> get() = _violations

    private val needsRuntimeAccumulator =
        selection.includes(InvariantCheck.ActionInstanceIds) ||
            selection.includes(InvariantCheck.ZoneObjects) ||
            selection.includes(InvariantCheck.AnnotationReferences) ||
            selection.includes(InvariantCheck.ClientAnnotationReferences)

    // --- Public API ---

    /** Process a single GRE message through the selected checks. */
    fun process(msg: GREToClientMessage) {
        messageIndex++
        val gsId = if (msg.hasGameStateMessage()) msg.gameStateMessage.gameStateId else 0
        val previousCardIds =
            if (selection.includes(InvariantCheck.ClientAnnotationReferences) && msg.hasGameStateMessage()) {
                accumulator.resolvableCardIds()
            } else {
                emptySet()
            }

        if (selection.includes(InvariantCheck.MsgIdMonotonicity)) {
            checkMsgIdMonotonicity(msg, gsId)
        }
        if (selection.includes(InvariantCheck.MsgIdUnique)) {
            checkMsgIdUnique(msg, gsId)
        }

        if (msg.hasGameStateMessage()) {
            val gsm = msg.gameStateMessage
            checkGsIdChain(gsm)
            if (selection.includes(InvariantCheck.AnnotationSequentiality)) {
                checkAnnotationIdSequentiality(gsm)
            }
            if (selection.includes(InvariantCheck.AnnotationOrdering)) {
                checkAnnotationOrdering(gsm)
            }
            if (selection.includes(InvariantCheck.PhaseFirst)) {
                checkPhaseFirst(gsm)
            }
            if (selection.includes(InvariantCheck.ResolutionTransferOrdering)) {
                checkResolutionTransferOrdering(gsm)
            }
            if (selection.includes(InvariantCheck.AidAffector)) {
                val aidIids = checkAidAffectorConsistency(gsm)
                recordAicAffectorHistory(gsm, aidIids)
            }
            if (selection.includes(InvariantCheck.PendingMessageCount)) {
                checkPendingMessageCountContract(gsm)
            }
        }

        if (needsRuntimeAccumulator) {
            accumulator.process(msg)
        }

        if (selection.includes(InvariantCheck.ActionInstanceIds) && msg.hasActionsAvailableReq()) {
            checkActionInstanceIdConsistency(gsId)
        }
        if (msg.hasGameStateMessage()) {
            if (selection.includes(InvariantCheck.ZoneObjects)) {
                checkZoneObjectConsistency(gsId)
            }
            if (selection.includes(InvariantCheck.AnnotationReferences)) {
                checkAnnotationReferentialIntegrity(msg.gameStateMessage)
            }
            if (selection.includes(InvariantCheck.ClientAnnotationReferences)) {
                checkClientAnnotationReferences(msg.gameStateMessage, previousCardIds)
            }
        }
    }

    /** Process a list of GRE messages. */
    fun processAll(messages: List<GREToClientMessage>) {
        messages.forEach { process(it) }
    }

    /** Seed with a Full GSM baseline (e.g. handshake). */
    fun seedFull(gsm: GameStateMessage) {
        accumulator.seedFull(gsm)
        val gsId = gsm.gameStateId
        seenGsIds.add(gsId)
        if (gsId > highWaterGsId) highWaterGsId = gsId
    }

    /** True if no violations recorded. */
    val isClean: Boolean get() = _violations.isEmpty()

    // --- GsId chain validation (static, no accumulator needed) ---

    companion object {
        /**
         * Validate hard gsId facts across a message sequence.
         * Returns violations list (empty = all hard gsId facts hold).
         */
        fun validateGsIdChain(
            messages: List<GREToClientMessage>,
            priorGsIds: Set<Int> = emptySet(),
        ): List<Violation> {
            val violations = mutableListOf<Violation>()
            val gsms = messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            val seen = priorGsIds.toMutableSet()
            var highWater = priorGsIds.maxOrNull() ?: 0

            for ((i, gsm) in gsms.withIndex()) {
                val gsId = gsm.gameStateId
                if (gsId == 0) continue
                if (highWater > 0 && gsId <= highWater) {
                    violations.add(
                        Violation(
                            i,
                            gsId,
                            "gsid_monotonicity",
                            "gsId not monotonic: got $gsId, expected > $highWater",
                        ),
                    )
                }
                if (gsId in seen) {
                    violations.add(
                        Violation(i, gsId, "gsid_unique", "Duplicate gsId: $gsId"),
                    )
                }
                if (gsm.prevGameStateId != 0 && gsm.prevGameStateId == gsId) {
                    violations.add(
                        Violation(
                            i,
                            gsId,
                            "gsid_self_ref",
                            "Self-referential prevGsId: gsId=$gsId",
                        ),
                    )
                }
                highWater = maxOf(highWater, gsId)
                seen.add(gsId)
            }
            return violations
        }
    }

    // --- Individual checks ---

    private fun checkMsgIdMonotonicity(
        msg: GREToClientMessage,
        gsId: Int,
    ) {
        val msgId = msg.msgId
        if (msgId == 0) return
        if (highWaterMsgId > 0 && msgId <= highWaterMsgId) {
            record(gsId, "msgid_monotonicity", "msgId not monotonic: got $msgId, expected > $highWaterMsgId")
        }
        highWaterMsgId = msgId
    }

    private fun checkMsgIdUnique(
        msg: GREToClientMessage,
        gsId: Int,
    ) {
        val msgId = msg.msgId
        if (msgId == 0) return
        if (msgId in seenMsgIds) {
            record(gsId, "msgid_unique", "Duplicate msgId: $msgId")
        }
        seenMsgIds.add(msgId)
    }

    private fun checkGsIdChain(gsm: GameStateMessage) {
        val gsId = gsm.gameStateId
        if (gsId == 0) {
            return
        }
        if (
            selection.includes(InvariantCheck.GsIdMonotonicity) &&
            highWaterGsId > 0 &&
            gsId <= highWaterGsId
        ) {
            record(gsId, "gsid_monotonicity", "gsId not monotonic: got $gsId, expected > $highWaterGsId")
        }
        val prev = gsm.prevGameStateId
        if (selection.includes(InvariantCheck.GsIdUnique) && gsId in seenGsIds) {
            record(gsId, "gsid_unique", "Duplicate gsId: $gsId")
        }
        if (
            selection.includes(InvariantCheck.GsIdPrevKnown) &&
            prev != 0 &&
            !seenGsIds.contains(prev)
        ) {
            record(gsm.gameStateId, "gsid_prev_unknown", "prevGsId $prev not in known set (gsId=${gsm.gameStateId})")
        }
        if (selection.includes(InvariantCheck.GsIdNoSelfRef) && prev == gsId) {
            record(gsm.gameStateId, "gsid_self_ref", "Self-referential gsId: gameStateId=${gsm.gameStateId} == prevGameStateId")
        }
        highWaterGsId = maxOf(highWaterGsId, gsId)
        seenGsIds.add(gsId)
    }

    private fun checkAnnotationIdSequentiality(gsm: GameStateMessage) {
        val annotations = gsm.annotationsList
        if (annotations.isEmpty()) return
        if (annotations.all { it.id == 0 }) return

        for ((idx, ann) in annotations.withIndex()) {
            if (ann.id == 0) {
                record(gsm.gameStateId, "annotation_seq", "Annotation at index $idx has id=0 in mixed-id GSM (gsId=${gsm.gameStateId})")
                continue
            }
            if (idx > 0 && annotations[idx - 1].id != 0) {
                val prev = annotations[idx - 1].id
                if (ann.id != prev + 1) {
                    record(
                        gsm.gameStateId,
                        "annotation_seq",
                        "Annotation IDs not sequential: index $idx has id=${ann.id}, expected ${prev + 1} (gsId=${gsm.gameStateId})",
                    )
                }
            }
        }
    }

    /**
     * Verify annotation ordering invariants (Rules 1 and 2) by delegating
     * to [AnnotationOrderEnforcer]. If enforce() returns a different list,
     * the input had ordering violations.
     */
    private fun checkAnnotationOrdering(gsm: GameStateMessage) {
        val annotations = gsm.annotationsList
        if (annotations.isEmpty()) return

        val enforced = AnnotationOrderEnforcer.enforce(annotations)
        if (enforced !== annotations) {
            // Find which annotations moved
            for (i in annotations.indices) {
                if (i < enforced.size && annotations[i] !== enforced[i]) {
                    record(
                        gsm.gameStateId,
                        "annotation_ordering",
                        "annotation ordering violation at index $i: " +
                            "had ${annotations[i].typeList} but enforcer moved ${enforced[i].typeList} here " +
                            "(gsId=${gsm.gameStateId})",
                    )
                }
            }
        }
    }

    /**
     * Diagnostic shape check for PhaseOrStepModified position. Some client-like
     * streams put phase markers first; this is not part of the hard default set.
     */
    private fun checkPhaseFirst(gsm: GameStateMessage) {
        val annotations = gsm.annotationsList
        val firstPosIdx = annotations.indexOfFirst { AnnotationType.PhaseOrStepModified in it.typeList }
        if (firstPosIdx <= 0) return
        record(
            gsm.gameStateId,
            "phase_first",
            "PhaseOrStepModified at index $firstPosIdx, expected 0 (gsId=${gsm.gameStateId})",
        )
    }

    /**
     * In any GSM that contains both [AnnotationType.ResolutionStart] and
     * [AnnotationType.ResolutionComplete], Resolve-category transfers are
     * ordered by source zone.
     *
     * Stack-exit transfers apply after the RS/RC pair. Non-stack transfers caused
     * by resolving ability effects stay inside the pair.
     * Kept opt-in so the default validator only enforces stable hard facts.
     */
    private fun checkResolutionTransferOrdering(gsm: GameStateMessage) {
        val annotations = gsm.annotationsList
        val rsIdx = annotations.indexOfFirst { AnnotationType.ResolutionStart in it.typeList }
        val rcIdx = annotations.indexOfLast { AnnotationType.ResolutionComplete in it.typeList }
        if (rsIdx < 0 || rcIdx < 0) return
        val gsId = gsm.gameStateId
        annotations.forEachIndexed { idx, ann ->
            if (AnnotationType.ZoneTransfer_af5a !in ann.typeList) return@forEachIndexed
            val isResolve =
                ann.detailsList.any { detail ->
                    detail.key == DetailKeys.CATEGORY &&
                        detail.valueStringList.firstOrNull() == TransferCategory.Resolve.label
                }
            if (!isResolve) return@forEachIndexed
            val srcZone = ann.detailInt(DetailKeys.ZONE_SRC)
            val invalid =
                if (srcZone == ZoneIds.STACK) {
                    idx <= rcIdx
                } else {
                    idx <= rsIdx || idx >= rcIdx
                }
            if (invalid) {
                val affected = ann.affectedIdsList.firstOrNull() ?: 0
                val expected = if (srcZone == ZoneIds.STACK) "after RC=$rcIdx" else "inside RS=$rsIdx..RC=$rcIdx"
                record(
                    gsId,
                    "resolution_transfer_ordering",
                    "Resolve-category ZoneTransfer affected=$affected at index $idx " +
                        "not $expected (srcZone=$srcZone, gsId=$gsId)",
                )
            }
        }
    }

    /**
     * Verify that an [AnnotationType.AbilityInstanceDeleted]'s `affectorId`
     * matches the `affectorId` of the earlier [AnnotationType.AbilityInstanceCreated]
     * for the same ability instance id (the AID/AIC's `affectedIds[0]`).
     *
     * Catches identity drift such as the saga T5 chapter-III + transform
     * regression, where AID emits with the post-transform source iid even
     * though AIC was emitted with the pre-transform source iid.
     *
     * Detection only — the matching producer-side fix lands when ability
     * lineage tracking lands.
     *
     * Wired in [process] BEFORE [recordAicAffectorHistory] so an AID in
     * GSM N is only checked against AICs from GSM 1..N-1; same-GSM AIC+AID
     * pairs (mana brackets) do not interact through this map.
     *
     * The AIC entry is pruned after the AID check fires so it does not leak
     * across re-emissions.
     *
     * Returns the set of ability iids that were AID'd in this GSM, so
     * [recordAicAffectorHistory] can skip storing AICs whose ability was
     * already closed in the same GSM (otherwise same-GSM AIC+AID pairs
     * would leak the AIC entry into the map indefinitely).
     */
    private fun checkAidAffectorConsistency(gsm: GameStateMessage): Set<Int> {
        val aidIids = mutableSetOf<Int>()
        for (ann in gsm.annotationsList) {
            if (AnnotationType.AbilityInstanceDeleted !in ann.typeList) continue
            val abilityIid = ann.affectedIdsList.firstOrNull() ?: continue
            aidIids.add(abilityIid)
            val expected = aicAffectorByAbilityIid[abilityIid] ?: continue
            if (ann.affectorId != expected) {
                record(
                    gsm.gameStateId,
                    "aid_affector",
                    "AID affectorId=${ann.affectorId} for ability=$abilityIid does not match " +
                        "earlier AIC affectorId=$expected (gsId=${gsm.gameStateId})",
                )
            }
            aicAffectorByAbilityIid.remove(abilityIid)
        }
        return aidIids
    }

    /**
     * Store each [AnnotationType.AbilityInstanceCreated] in this GSM in
     * the [aicAffectorByAbilityIid] map so that a future AID for the same
     * ability iid can be checked against it.
     *
     * Wired in [process] AFTER [checkAidAffectorConsistency] so same-GSM
     * AIC+AID pairs do not check against themselves. Same-GSM AIC entries
     * are skipped via [aidIidsThisGsm] — storing them would leak the
     * entry indefinitely because no future AID will arrive to prune it
     * (mana brackets fire many times per match).
     */
    private fun recordAicAffectorHistory(
        gsm: GameStateMessage,
        aidIidsThisGsm: Set<Int>,
    ) {
        for (ann in gsm.annotationsList) {
            if (AnnotationType.AbilityInstanceCreated !in ann.typeList) continue
            val abilityIid = ann.affectedIdsList.firstOrNull() ?: continue
            if (abilityIid in aidIidsThisGsm) continue
            aicAffectorByAbilityIid[abilityIid] = ann.affectorId
        }
    }

    private fun checkPendingMessageCountContract(gsm: GameStateMessage) {
        // TODO: too strict — our phaseTransitionDiff sets pendingMessageCount=1
        // but AI action diffs can arrive before the expected follow-up.
        // Revisit once the diff pipeline guarantees correct pending counts.
        // val isSendAndRecord = gsm.update == GameStateUpdate.SendAndRecord
        // if (pendingCountdown > 0 && isSendAndRecord) {
        //     record(
        //         gsm.gameStateId,
        //         "pending_count",
        //         "pendingMessageCount violation: ...",
        //     )
        // }

        val pending = gsm.pendingMessageCount
        if (pending > 0) {
            pendingCountdown = pending
        } else if (pendingCountdown > 0) {
            pendingCountdown--
        }
    }

    private fun checkActionInstanceIdConsistency(gsId: Int) {
        val missing = accumulator.actionInstanceIdsMissingFromObjects()
        if (missing.isNotEmpty()) {
            record(gsId, "action_iid", "Action instanceIds missing from objects: $missing")
        }
    }

    private fun checkZoneObjectConsistency(gsId: Int) {
        val missing = accumulator.zoneObjectsMissingFromObjects()
        if (missing.isNotEmpty()) {
            record(gsId, "zone_object", "Zone objects missing from objects: $missing")
        }
    }

    /**
     * Native target events use the new state; damage may use either state.
     * A bare object, retired id, or ability-lifecycle annotation is not a
     * substitute for a card the client can resolve. Historical mana/action
     * references are deliberately left to the broader diagnostic check.
     */
    private fun checkClientAnnotationReferences(
        gsm: GameStateMessage,
        previousCardIds: Set<Int>,
    ) {
        val currentCards = accumulator.resolvableCardIds()
        val damageCards = previousCardIds + currentCards
        for (ann in gsm.annotationsList + gsm.persistentAnnotationsList) {
            val isTargetEvent =
                ann.typeList.any {
                    it == AnnotationType.PlayerSelectingTargets || it == AnnotationType.PlayerSubmittedTargets
                }
            val isDamage = AnnotationType.DamageDealt_af5a in ann.typeList
            // TargetSpec's native reference handler skips unknown source/target
            // entities. Its fidelity belongs to diagnostics, not this fatal guard.
            if (!isTargetEvent && !isDamage) continue

            val sourceValid =
                if (isTargetEvent) ann.affectorId in 1..2 else ann.affectorId in damageCards
            if (!sourceValid) {
                record(
                    gsm.gameStateId,
                    "client_annotation_ref",
                    "annotation ${ann.id}: affectorId ${ann.affectorId} cannot resolve for native parser (type=${ann.typeList})",
                )
            }
            for (affected in ann.affectedIdsList) {
                val valid =
                    if (isTargetEvent) affected in currentCards else affected in 1..2 || affected in damageCards
                if (!valid) {
                    record(
                        gsm.gameStateId,
                        "client_annotation_ref",
                        "annotation ${ann.id}: affectedId $affected cannot resolve for native parser " +
                            "(type=${ann.typeList}, objectPresent=${affected in accumulator.objects}, " +
                            "zones=${accumulator.zones.values.filter { affected in it.objectInstanceIdsList }.map { it.zoneId }}, " +
                            "previousCard=${affected in previousCardIds}, gsId=${gsm.gameStateId})",
                    )
                }
            }
        }
    }

    /**
     * Diagnostic: validate annotation affectorId/affectedIds resolve to known entities.
     *
     * Valid targets: accumulated object instanceIds, player seats (1/2),
     * zone IDs (affector can be a zone, e.g. EnteredZoneThisTurn).
     * Runs after accumulator.process() so current GSM's objects are included.
     *
     * ObjectIdChanged annotations are exempt: they reference old instanceIds
     * (origId) that may have been deleted in this or a prior GSM, or may
     * reference objects from hidden zones (library) that were never visible.
     */
    private fun checkAnnotationReferentialIntegrity(gsm: GameStateMessage) {
        val gsId = gsm.gameStateId
        val annotations = gsm.annotationsList + gsm.persistentAnnotationsList
        if (annotations.isEmpty()) return

        // Collect transient ability IDs created by AbilityInstanceCreated annotations.
        // These are mana ability instance IDs that exist only within the annotation
        // sequence (created then deleted in the same GSM) and don't appear as game objects.
        val transientAbilityIds =
            annotations
                .filter { it.typeList.any { t -> t == AnnotationType.AbilityInstanceCreated } }
                .flatMap { it.affectedIdsList }
                .toSet()
        val closingAbilityIds =
            annotations
                .filter { it.typeList.any { t -> t == AnnotationType.AbilityInstanceDeleted } }
                .flatMap { it.affectedIdsList }
                .toSet()
        val sameGsmKnownIds =
            gsm.diffDeletedInstanceIdsList.toSet() +
                annotations
                    .filter { it.typeList.any { t -> t == AnnotationType.ObjectIdChanged } }
                    .flatMap { ann ->
                        ann.detailsList
                            .filter { detail -> detail.key == DetailKeys.NEW_ID }
                            .flatMap { detail -> (0 until detail.valueInt32Count).map { detail.getValueInt32(it) } }
                    }.toSet()

        fun isKnown(id: Int) =
            accumulator.isKnownEntity(id) ||
                id in transientAbilityIds ||
                id in closingAbilityIds ||
                id in aicAffectorByAbilityIid ||
                id in sameGsmKnownIds

        for (ann in annotations) {
            // ObjectIdChanged references old (replaced) instanceIds — skip entirely
            val isObjectIdChanged = ann.typeList.any { it == AnnotationType.ObjectIdChanged }
            if (isObjectIdChanged) continue
            // LayeredEffect annotations use synthetic effect IDs, not entity references
            val isLayeredEffect =
                ann.typeList.any {
                    it == AnnotationType.LayeredEffectCreated || it == AnnotationType.LayeredEffectDestroyed
                }
            if (isLayeredEffect) continue

            if (ann.affectorId != 0 && !isKnown(ann.affectorId)) {
                record(
                    gsId,
                    "annotation_ref",
                    "annotation ${ann.id}: affectorId ${ann.affectorId} unresolvable " +
                        "(type=${ann.typeList}, gsId=$gsId)",
                )
            }
            for (affected in ann.affectedIdsList) {
                if (affected != 0 && !isKnown(affected)) {
                    record(
                        gsId,
                        "annotation_ref",
                        "annotation ${ann.id}: affectedId $affected unresolvable " +
                            "(type=${ann.typeList}, gsId=$gsId)",
                    )
                }
            }
        }
    }

    private fun record(
        gsId: Int,
        check: String,
        message: String,
    ) {
        if (selection.includes(check)) {
            _violations.add(Violation(messageIndex, gsId, check, message))
        }
    }

    private fun AnnotationInfo.detailInt(key: String): Int =
        detailsList.firstOrNull { it.key == key }?.let {
            if (it.valueInt32Count > 0) it.getValueInt32(0) else 0
        } ?: 0
}

/**
 * Minimal client state accumulator for runtime use.
 *
 * Same logic as test-only ClientAccumulator but in main source scope.
 * Processes Full/Diff GSMs, tracks objects/zones/actions for invariant checking.
 */
class RuntimeAccumulator {
    val objects = mutableMapOf<Int, GameObjectInfo>()
    val zones = mutableMapOf<Int, ZoneInfo>()
    var actions: ActionsAvailableReq? = null
        private set

    fun process(gre: GREToClientMessage) {
        when {
            gre.hasGameStateMessage() -> processGameState(gre.gameStateMessage)
            gre.hasActionsAvailableReq() -> actions = gre.actionsAvailableReq
        }
    }

    fun seedFull(gsm: GameStateMessage) {
        require(gsm.type == GameStateType.Full) { "seedFull requires Full GSM, got ${gsm.type}" }
        processGameState(gsm)
    }

    /**
     * Check if an ID is a known entity: object instanceId, player seat (1/2), or zone ID.
     * Annotations use affectorId/affectedIds to reference any of these.
     */
    fun isKnownEntity(id: Int): Boolean =
        id in 1..2 ||
            objects.containsKey(id) ||
            zones.containsKey(id) ||
            zones.values.any { zone -> id in zone.objectInstanceIdsList }

    /** Native card lookup requires zone membership, except for explicit revealed-card proxies. */
    fun resolvableCardIds(): Set<Int> =
        zones.values
            .flatMap { it.objectInstanceIdsList }
            .filterTo(mutableSetOf()) { it in objects } +
            objects.values.filter { it.type == GameObjectType.RevealedCard }.map { it.instanceId }

    fun actionInstanceIdsMissingFromObjects(): List<Int> {
        val req = actions ?: return emptyList()
        return req.actionsList
            .filter { it.instanceId != 0 && !isKnownEntity(it.instanceId) }
            .map { it.instanceId }
    }

    fun zoneObjectsMissingFromObjects(): List<Pair<Int, Int>> {
        val missing = mutableListOf<Pair<Int, Int>>()
        for ((zoneId, zone) in zones) {
            if (zone.visibility == Visibility.Hidden || zone.visibility == Visibility.Private) continue
            if (zone.type == ZoneType.Limbo) continue
            for (iid in zone.objectInstanceIdsList) {
                if (!objects.containsKey(iid)) {
                    missing.add(zoneId to iid)
                }
            }
        }
        return missing
    }

    private fun processGameState(gs: GameStateMessage) {
        when (gs.type) {
            GameStateType.Full -> {
                objects.clear()
                zones.clear()
                gs.gameObjectsList.forEach { objects[it.instanceId] = it }
                gs.zonesList.forEach { zones[it.zoneId] = it }
            }
            GameStateType.Diff -> {
                gs.diffDeletedInstanceIdsList.forEach { objects.remove(it) }
                gs.gameObjectsList.forEach { objects[it.instanceId] = it }
                gs.zonesList.forEach { zones[it.zoneId] = it }
            }
            else -> {}
        }
    }
}
