package leyline.game.annotations

import leyline.DevCheck
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.GrpId
import leyline.bridge.types.InstanceId
import leyline.game.state.TargetSpec
import leyline.game.state.TargetSpecKind
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * TargetSpec annotations: one persistent [TargetSpecKind] pAnn per targeted
 * spell/ability target group on the stack.
 * Pure and persistent-only — no transient stream output and no effect-id
 * allocation, so its invocation position is order-independent. Groups remain
 * active while their common affector is on the stack and expire together when
 * that spell or ability resolves.
 *
 * Reads the pending target picks via a non-consuming snapshot; the spine keeps
 * its own snapshot for the consumed-spec records that flow into bridge mutations.
 */
object TargetSpecContributor : AnnotationContributor {
    override val rank: Int = 20

    override fun contribute(ctx: AnnotationContext): Contribution {
        val pending = ctx.promptFacts.targetSpecs.map { it.spec }
        return Contribution(persistent = mapOf(TargetSpecKind to buildTargetSpec(pending, ctx)))
    }

    private fun buildTargetSpec(
        pending: List<TargetSpec>,
        ctx: AnnotationContext,
    ): List<AnnotationInfo> {
        // Read target picks after Forge completes chooseTargetsFor.
        // The spell may have already resolved by now (auto-pass), so we can't
        // rely on scanning game.getStack() — the stack is often empty.
        if (pending.isEmpty()) return emptyList()

        val frameIds = ctx.frameIds
        return pending.mapNotNull { spec ->
            // Use the iid recorded at target-pick time for non-triggers (see
            // PendingTarget KDoc for the multi-target-spell rationale).
            // Stack abilities defer to emission-time resolution via the SA id —
            // TargetingCoordinator always populates spec.forgeAbilityId when
            // spec.isStackAbility=true, so that branch's fallback is
            // structurally unreachable and crashes under DevCheck.strict.
            val affectorIid =
                if (spec.affectorInstanceIdAtRecord != 0) {
                    // A spell's target is chosen while the card still has its
                    // hand/exile iid.  The same GSM then reallocates that card
                    // onto the stack.  TargetSpec must follow that reallocation
                    // or Arena cannot attach the targeting line to the visible
                    // stack object.
                    ctx.transferResult
                        ?.idReallocations
                        .orEmpty()
                        .fold(InstanceId(spec.affectorInstanceIdAtRecord)) { current, reallocation ->
                            if (reallocation.old == current) reallocation.new else current
                        }
                } else if (spec.isStackAbility) {
                    if (spec.forgeAbilityId != 0) {
                        ctx.targetSpecStackAbilityIid(spec)
                    } else {
                        DevCheck.fail {
                            "PendingTarget for ${spec.spellName} marked isStackAbility but missing forgeAbilityId; " +
                                "every stack-ability target spec must carry the SA id since stack-ability iids " +
                                "are SA-id-keyed"
                        }
                        // Emit 0 rather than the source-card-keyed iid — that
                        // would point at a non-existent stack object since
                        // ZoneMapper now mints via the SA-id-keyed surrogate.
                        // 0 surfaces visibly in invariant checks rather than
                        // routing the TargetSpec to a stale iid.
                        InstanceId(0)
                    }
                } else {
                    frameIds.cardIid(ForgeCardId(spec.spellForgeCardId))
                }
            val targetIids =
                spec.affectees.mapNotNull { affectee ->
                    when {
                        affectee.targetForgeCardId != null -> frameIds.cardIid(ForgeCardId(affectee.targetForgeCardId))
                        affectee.targetSeatId != null -> InstanceId(affectee.targetSeatId)
                        else -> null
                    }
                }
            if (targetIids.isEmpty()) return@mapNotNull null
            val distributions = spec.affectees.mapNotNull { it.distribution }
            val alignedDistributions = distributions.takeIf { it.size == targetIids.size }.orEmpty()
            val abilityGrpId = ctx.targetSpecAbilityGrpId(spec)
            AnnotationBuilder.targetSpec(
                instanceIds = targetIids,
                affectorId = affectorIid,
                abilityGrpId = GrpId(abilityGrpId),
                index = spec.index,
                promptId = spec.promptId ?: leyline.game.mapping.PromptIds.SELECT_TARGETS,
                promptParameters = affectorIid.value,
                distributions = alignedDistributions,
            )
        }
    }
}
