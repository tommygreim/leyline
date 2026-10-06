package leyline.game.annotations

import leyline.bridge.types.GrpId
import leyline.game.mapping.FrameIdResolver
import leyline.game.state.AbilityExhaustionFacts
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/** Maps shell-materialized ability-exhaustion rows to tentative frame identities. */
internal fun buildAbilityExhaustedAnnotations(
    facts: AbilityExhaustionFacts,
    frameIds: FrameIdResolver,
): List<AnnotationInfo> =
    facts.rows.map { row ->
        AnnotationBuilder.abilityExhausted(
            instanceId = frameIds.cardIid(row.sourceForgeCardId),
            abilityGrpId = GrpId(row.abilityGrpId),
            usesRemaining = row.usesRemaining,
            uniqueAbilityId = row.uniqueAbilityId,
            exhaustedGrpIds = row.exhaustedGrpIds,
        )
    }
