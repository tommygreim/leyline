package leyline.game.annotations

import leyline.game.codes.DetailKeys
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

data class FinalizedAnnotationFrame(
    val annotations: List<AnnotationInfo>,
    val nextId: Int,
)

/** Applies same-GSM ordering and transient IDs after every frame rider is known. */
object AnnotationFrameFinalizer {
    fun finalize(
        annotations: List<AnnotationInfo>,
        firstId: Int,
    ): FinalizedAnnotationFrame {
        require(firstId > 0) { "Transient annotation IDs must be positive" }
        val ordered = AnnotationOrderEnforcer.enforce(dedupePhaseSteps(followFrameIdChanges(annotations)))
        val numbered = ordered.mapIndexed { index, annotation -> annotation.toBuilder().setId(firstId + index).build() }
        return FinalizedAnnotationFrame(numbered, firstId + numbered.size)
    }

    /**
     * Keep one PhaseOrStepModified per distinct phase/step in a frame.
     *
     * The frame's own PhaseChanged events and a PhaseTransition supplement both
     * emit one, so a single step could arrive two or three times over. Arena
     * sends one annotation per step actually traversed — a frame crossing into a
     * new turn reads [(End, Cleanup), (Beginning, Untap), (Beginning, Upkeep)] —
     * and never repeats a pair.
     */
    private fun dedupePhaseSteps(annotations: List<AnnotationInfo>): List<AnnotationInfo> {
        val seen = mutableSetOf<Pair<Int, Int>>()
        return annotations.filter { annotation ->
            if (AnnotationType.PhaseOrStepModified !in annotation.typeList) return@filter true
            seen.add((annotation.intDetail(DetailKeys.PHASE) ?: -1) to (annotation.intDetail(DetailKeys.STEP) ?: -1))
        }
    }

    /**
     * Point target annotations at the instance this frame's own ObjectIdChanged installs.
     *
     * The client resolves PlayerSelectingTargets and PlayerSubmittedTargets against the new
     * state only (`PlayerSelectingOrSubmittedTargetsAnnotationParser.GetAffectedCard`), and a
     * null result throws in the translator — costing the whole update, prompt included. A
     * frame that retires a spell's id while another annotation still names the old one is
     * exactly that case, and the id change riding the same frame tells us the replacement.
     */
    private fun followFrameIdChanges(annotations: List<AnnotationInfo>): List<AnnotationInfo> {
        val replacements = mutableMapOf<Int, Int>()
        for (annotation in annotations) {
            if (AnnotationType.ObjectIdChanged !in annotation.typeList) continue
            val original = annotation.intDetail(DetailKeys.ORIG_ID) ?: continue
            val replacement = annotation.intDetail(DetailKeys.NEW_ID) ?: continue
            if (original != replacement) replacements[original] = replacement
        }
        if (replacements.isEmpty()) return annotations
        return annotations.map { annotation ->
            if (!annotation.namesATarget()) return@map annotation
            val followed = annotation.affectedIdsList.map { replacements[it] ?: it }
            if (followed == annotation.affectedIdsList) {
                annotation
            } else {
                annotation
                    .toBuilder()
                    .clearAffectedIds()
                    .addAllAffectedIds(followed)
                    .build()
            }
        }
    }

    private fun AnnotationInfo.namesATarget(): Boolean =
        AnnotationType.PlayerSelectingTargets in typeList || AnnotationType.PlayerSubmittedTargets in typeList

    private fun AnnotationInfo.intDetail(key: String): Int? =
        detailsList
            .firstOrNull { it.key == key && it.valueInt32Count > 0 }
            ?.getValueInt32(0)
}
