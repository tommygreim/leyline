package leyline.game.annotations

import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
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
        val allowed = targetReferenceFilter(gsm)
        val transient = gsm.annotationsList.filter(allowed)
        val persistent = gsm.persistentAnnotationsList.filter(allowed)
        val removed = gsm.persistentAnnotationsList.map { it.id }.filterNot(persistent.map { it.id }.toSet()::contains)
        return Result(transient, persistent, removed)
    }

    /** Bind the frame's reference policy before ordering/numbering assigns transient IDs. */
    fun targetReferenceFilter(gsm: GameStateMessage): (AnnotationInfo) -> Boolean {
        val objectIds = gsm.gameObjectsList.mapTo(mutableSetOf()) { it.instanceId }
        val resolvableCards =
            buildSet {
                gsm.zonesList.forEach { zone ->
                    addAll(zone.objectInstanceIdsList.filter { it in objectIds })
                }
                addAll(gsm.gameObjectsList.filter { it.type == GameObjectType.RevealedCard }.map { it.instanceId })
            }
        val resolvableEntities = resolvableCards + setOf(1, 2)

        fun valid(annotation: AnnotationInfo): Boolean =
            if (AnnotationType.TargetSpec in annotation.typeList) {
                annotation.affectorId in resolvableCards && annotation.affectedIdsList.all(resolvableEntities::contains)
            } else {
                annotation.affectorId in 1..2 && annotation.affectedIdsList.all(resolvableCards::contains)
            }

        return { annotation -> !isTarget(annotation) || valid(annotation) }
    }

    private fun isTarget(annotation: AnnotationInfo): Boolean =
        annotation.typeList.any { type ->
            type == AnnotationType.PlayerSelectingTargets ||
                type == AnnotationType.PlayerSubmittedTargets ||
                type == AnnotationType.TargetSpec
        }
}
