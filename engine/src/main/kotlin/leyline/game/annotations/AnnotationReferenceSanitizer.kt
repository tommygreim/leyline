package leyline.game.annotations

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Removes target metadata which the Arena client cannot resolve in the new GSM.
 *
 * The target parsers run against the post-update client state.  They do not
 * treat an instance id that merely appeared in an annotation, or an id that
 * was deleted in the same frame, as a card.  In particular, a stale
 * `TargetSpec` is persistent and can otherwise be replayed on every later
 * update.  Same-frame id replacement is handled before this pass by
 * [AnnotationFrameFinalizer].
 *
 * This is deliberately protocol-shaped rather than card-shaped: all target
 * annotations use the same resolvability rule, regardless of the mechanic
 * which produced them.
 */
object AnnotationReferenceSanitizer {
    data class Result(
        val transient: List<AnnotationInfo>,
        val persistent: List<AnnotationInfo>,
        val removedPersistentIds: List<Int>,
    )

    fun sanitize(gsm: GameStateMessage): Result {
        val resolvableIds =
            buildSet {
                add(1)
                add(2)
                gsm.zonesList.forEach { zone ->
                    add(zone.zoneId)
                    addAll(zone.objectInstanceIdsList)
                }
            }

        fun valid(annotation: AnnotationInfo): Boolean =
            annotation.affectorId in resolvableIds && annotation.affectedIdsList.all(resolvableIds::contains)

        val transient = gsm.annotationsList.filter { annotation -> !isTarget(annotation) || valid(annotation) }
        val persistent = gsm.persistentAnnotationsList.filter { annotation -> !isTarget(annotation) || valid(annotation) }
        val removed = gsm.persistentAnnotationsList.map { it.id }.filterNot(persistent.map { it.id }.toSet()::contains)
        return Result(transient, persistent, removed)
    }

    private fun isTarget(annotation: AnnotationInfo): Boolean =
        annotation.typeList.any { type ->
            type == AnnotationType.PlayerSelectingTargets ||
                type == AnnotationType.PlayerSubmittedTargets ||
                type == AnnotationType.TargetSpec
        }
}
