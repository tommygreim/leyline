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
        val ordered = AnnotationOrderEnforcer.enforce(dedupePhaseSteps(annotations))
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
            seen.add(annotation.stepDetail(DetailKeys.PHASE) to annotation.stepDetail(DetailKeys.STEP))
        }
    }

    private fun AnnotationInfo.stepDetail(key: String): Int =
        detailsList
            .firstOrNull { it.key == key }
            ?.let { if (it.valueInt32Count > 0) it.getValueInt32(0) else null }
            ?: -1
}
