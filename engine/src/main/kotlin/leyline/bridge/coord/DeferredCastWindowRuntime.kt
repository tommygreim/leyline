package leyline.bridge.coord

import leyline.bridge.handoff.DeferredCastCostPlan
import leyline.bridge.handoff.PromptSideEffect
import leyline.bridge.types.SeatId
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.LogicalSequencePlanner
import leyline.game.state.ProjectionState
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionType
import wotc.mtgo.gre.external.messaging.Messages.CastingTimeOptionsReq
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

internal data class DeferredCastOptionResponse(
    val ctoId: Int,
    val manaColor: ManaColor?,
)

internal data class DeferredCastResponse(
    val gameStateId: Int,
    val ctoId: Int,
    val selectedCtoId: Int?,
    val options: List<DeferredCastOptionResponse>,
)

internal class DeferredCastReceipt internal constructor(
    internal val actionId: String,
    internal val token: Long,
)

internal enum class DeferredCastRejection {
    Stale,
    Duplicate,
    WrongOption,
}

internal sealed interface DeferredCastAdmission {
    data class Rejected(
        val reason: DeferredCastRejection,
    ) : DeferredCastAdmission

    data object Optional : DeferredCastAdmission

    data class Hybrid(
        val receipt: DeferredCastReceipt,
    ) : DeferredCastAdmission

    data object Alternate : DeferredCastAdmission
}

internal interface DeferredCastActionOwner {
    fun completeDeferredClaim(
        claim: MatchActionWindowRuntime.ActionClaim,
        childToken: Long? = null,
    ): Boolean

    fun isDeferredClaim(claim: MatchActionWindowRuntime.ActionClaim): Boolean

    fun reopenDeferredClaim(claim: MatchActionWindowRuntime.ActionClaim): Boolean

    fun seatFor(actionId: String): SeatId
}

/** Owns exact deferred cast prompt correlation and retained cost choices. */
internal class DeferredCastWindowRuntime(
    private val owner: MatchCutCoordinator,
    private val actions: DeferredCastActionOwner,
) {
    internal var beforeMaterialization: (() -> Unit)? = null
    internal var beforeInstall: (() -> Unit)? = null

    private sealed interface Prompt {
        val actionClaim: MatchActionWindowRuntime.ActionClaim
        val promptGameStateId: Int
        var adopted: Boolean

        data class Optional(
            override val actionClaim: MatchActionWindowRuntime.ActionClaim,
            override val promptGameStateId: Int,
            val costCtoIds: List<Int>,
            val additionalCostGrpIdsByCtoId: Map<Int, Int>,
            val keywordCostsByCtoId: Map<Int, String>,
            override var adopted: Boolean = false,
        ) : Prompt

        data class HybridManaType(
            override val actionClaim: MatchActionWindowRuntime.ActionClaim,
            override val promptGameStateId: Int,
            val ctoIds: List<Int>,
            val promptColors: List<ManaColor>,
            val paymentColors: List<ManaColor>,
            override var adopted: Boolean = false,
        ) : Prompt

        data class AlternateCostChoice(
            override val actionClaim: MatchActionWindowRuntime.ActionClaim,
            override val promptGameStateId: Int,
            val choicesByCtoId: Map<Int, DeferredCastCostPlan.AlternateCostChoice>,
            override var adopted: Boolean = false,
        ) : Prompt
    }

    private sealed interface Publication {
        val claim: MatchActionWindowRuntime.ActionClaim
        val request: CastingTimeOptionsReq

        data class Hybrid(
            override val claim: MatchActionWindowRuntime.ActionClaim,
            override val request: CastingTimeOptionsReq,
            val ctoIds: List<Int>,
            val promptColors: List<ManaColor>,
            val paymentColors: List<ManaColor>,
        ) : Publication

        data class Optional(
            override val claim: MatchActionWindowRuntime.ActionClaim,
            override val request: CastingTimeOptionsReq,
            val ctoIds: List<Int>,
            val clearHybridStash: Boolean,
        ) : Publication

        data class Alternate(
            override val claim: MatchActionWindowRuntime.ActionClaim,
            override val request: CastingTimeOptionsReq,
            val ctoIds: List<Int>,
        ) : Publication
    }

    private var prompt: Prompt? = null

    fun publishHybrid(
        claim: MatchActionWindowRuntime.ActionClaim,
        request: CastingTimeOptionsReq,
        ctoIds: List<Int>,
        promptColors: List<ManaColor>,
        paymentColors: List<ManaColor>,
    ) = publishClaimed(Publication.Hybrid(claim, request, ctoIds, promptColors, paymentColors))

    fun publishOptional(
        claim: MatchActionWindowRuntime.ActionClaim,
        request: CastingTimeOptionsReq,
        ctoIds: List<Int>,
        preserveHybridStash: Boolean = false,
    ) = publishClaimed(Publication.Optional(claim, request, ctoIds, clearHybridStash = !preserveHybridStash))

    fun publishOptional(
        receipt: DeferredCastReceipt,
        request: CastingTimeOptionsReq,
        ctoIds: List<Int>,
        preserveHybridStash: Boolean,
    ): Boolean = publishAdoptedOptional(receipt, request, ctoIds, clearHybridStash = !preserveHybridStash)

    fun publishAlternate(
        claim: MatchActionWindowRuntime.ActionClaim,
        request: CastingTimeOptionsReq,
        ctoIds: List<Int>,
    ) = publishClaimed(Publication.Alternate(claim, request, ctoIds))

    fun deferredCostPlan(receipt: DeferredCastReceipt): DeferredCastCostPlan? =
        synchronized(owner.feedLock) {
            val pending = prompt ?: return@synchronized null
            if (pending.actionClaim.actionId != receipt.actionId ||
                pending.actionClaim.token != receipt.token ||
                !pending.adopted
            ) {
                return@synchronized null
            }
            pending.actionClaim.deferredCostPlan
        }

    fun hasPrompt(): Boolean = synchronized(owner.feedLock) { prompt != null }

    fun discard() =
        synchronized(owner.feedLock) {
            prompt?.actionClaim?.deferredCostPlan?.sourceCardId?.let { cardId ->
                owner.bridge.setSelectedChosenCostPromptId(cardId, null)
            }
            prompt = null
        }

    fun close(actionId: String) {
        synchronized(owner.feedLock) {
            val pending = prompt?.takeIf { it.actionClaim.actionId == actionId }
            pending?.actionClaim?.deferredCostPlan?.sourceCardId?.let { cardId ->
                owner.bridge.setSelectedChosenCostPromptId(cardId, null)
            }
            if (pending != null) prompt = null
        }
    }

    fun admit(response: DeferredCastResponse): DeferredCastAdmission =
        synchronized(owner.feedLock) {
            val pending =
                prompt ?: return@synchronized DeferredCastAdmission.Rejected(
                    DeferredCastRejection.Stale,
                )
            if (response.gameStateId != pending.promptGameStateId) {
                return@synchronized DeferredCastAdmission.Rejected(
                    DeferredCastRejection.Stale,
                )
            }
            if (pending.adopted) {
                return@synchronized DeferredCastAdmission.Rejected(
                    DeferredCastRejection.Duplicate,
                )
            }
            val receipt = DeferredCastReceipt(pending.actionClaim.actionId, pending.actionClaim.token)
            when (pending) {
                is Prompt.Optional -> admitOptional(pending, response)
                is Prompt.HybridManaType -> admitHybrid(pending, response, receipt)
                is Prompt.AlternateCostChoice -> admitAlternate(pending, response)
            }
        }

    fun complete(receipt: DeferredCastReceipt): Boolean =
        synchronized(owner.feedLock) {
            val pending = prompt ?: return@synchronized false
            if (!pending.adopted ||
                pending.actionClaim.actionId != receipt.actionId ||
                pending.actionClaim.token != receipt.token
            ) {
                return@synchronized false
            }
            actions.completeDeferredClaim(pending.actionClaim).also { if (it) prompt = null }
        }

    fun cancel(promptGameStateId: Int): Boolean =
        synchronized(owner.feedLock) {
            val pending = prompt ?: return@synchronized false
            if (pending.promptGameStateId != promptGameStateId || !actions.isDeferredClaim(pending.actionClaim)) {
                return@synchronized false
            }
            val claim = pending.actionClaim
            prompt = null
            claim.deferredCostPlan?.sourceCardId?.let { cardId ->
                owner.bridge.setSelectedSpellGrpId(cardId, null)
                owner.bridge.setSelectedChosenCostPromptId(cardId, null)
            }
            owner.bridge
                .seat(actions.seatFor(claim.actionId))
                .prompt.journal
                .clearHybridManaStash()
            actions.reopenDeferredClaim(claim)
        }

    private fun admitOptional(
        pending: Prompt.Optional,
        response: DeferredCastResponse,
    ): DeferredCastAdmission {
        val chosen = response.ctoId
        val accepted = chosen != 0 && chosen in pending.costCtoIds
        val valid = chosen == 0 || accepted || chosen in pending.keywordCostsByCtoId
        if (!valid) {
            return DeferredCastAdmission.Rejected(
                DeferredCastRejection.WrongOption,
            )
        }
        pending.adopted = true
        val acceptedIndices = if (accepted && chosen !in pending.keywordCostsByCtoId) listOf(chosen - 1) else emptyList()
        val decisions = pending.keywordCostsByCtoId.values.associateWith { it == pending.keywordCostsByCtoId[chosen] }
        val seatBridge = owner.bridge.seat(actions.seatFor(pending.actionClaim.actionId))
        seatBridge.prompt.journal.record(PromptSideEffect.OptionalCostStash(acceptedIndices))
        pending.additionalCostGrpIdsByCtoId[chosen]?.let { grpId ->
            pending.actionClaim.deferredCostPlan
                ?.sourceCardId
                ?.let { cardId -> owner.bridge.setSelectedAdditionalCostGrpId(cardId, grpId) }
        }
        if (decisions.isNotEmpty()) seatBridge.prompt.journal.record(PromptSideEffect.KeywordCostStash(decisions))
        check(actions.completeDeferredClaim(pending.actionClaim)) { "Deferred optional action claim did not complete" }
        prompt = null
        return DeferredCastAdmission.Optional
    }

    private fun admitHybrid(
        pending: Prompt.HybridManaType,
        response: DeferredCastResponse,
        receipt: DeferredCastReceipt,
    ): DeferredCastAdmission {
        val seen = response.options.map { it.ctoId }
        if (seen.any { it != 0 && it !in pending.ctoIds } || seen.filter { it != 0 }.distinct().size != seen.count { it != 0 }) {
            return DeferredCastAdmission.Rejected(DeferredCastRejection.WrongOption)
        }
        val byCtoId = response.options.associateBy { it.ctoId }
        val promptChoices =
            pending.ctoIds.mapIndexed { index, ctoId ->
                byCtoId[ctoId]?.manaColor ?: response.options.getOrNull(index)?.manaColor
                    ?: pending.promptColors.getOrNull(index) ?: ManaColor.TwoGeneric
            }
        val choices = reorderHybridChoices(promptChoices, pending.promptColors, pending.paymentColors)
        pending.adopted = true
        owner.bridge
            .seat(actions.seatFor(pending.actionClaim.actionId))
            .prompt.journal
            .record(PromptSideEffect.HybridManaStash(choices))
        return DeferredCastAdmission.Hybrid(receipt)
    }

    private fun admitAlternate(
        pending: Prompt.AlternateCostChoice,
        response: DeferredCastResponse,
    ): DeferredCastAdmission {
        val selected = response.selectedCtoId ?: response.ctoId
        val choice =
            pending.choicesByCtoId[selected]
                ?: return DeferredCastAdmission.Rejected(
                    DeferredCastRejection.WrongOption,
                )
        pending.adopted = true
        val sourceCardId = pending.actionClaim.deferredCostPlan?.sourceCardId
        sourceCardId?.let { cardId ->
            owner.bridge.setSelectedChosenCostPromptId(cardId, choice.chosenCostPromptId)
        }
        val completed = actions.completeDeferredClaim(pending.actionClaim, choice.runtimeToken)
        if (!completed) {
            sourceCardId?.let { cardId -> owner.bridge.setSelectedChosenCostPromptId(cardId, null) }
        }
        check(completed) { "Deferred alternate action claim did not complete" }
        prompt = null
        return DeferredCastAdmission.Alternate
    }

    private fun optionalPrompt(
        claim: MatchActionWindowRuntime.ActionClaim,
        promptGameStateId: Int,
        ctoIds: List<Int>,
    ): Prompt.Optional {
        val entries =
            claim.deferredCostPlan
                ?.optional
                ?.entries
                .orEmpty()
        check(entries.size == ctoIds.size) { "Deferred optional-cost catalog no longer matches the action plan" }
        return Prompt.Optional(
            claim,
            promptGameStateId,
            ctoIds.toList(),
            entries
                .mapIndexedNotNull { index, entry ->
                    // OptionalCost.type-based entries (Kicker excepted) stash their
                    // abilityGrpId here regardless of which CastingTimeOptionType they
                    // render as (AdditionalCost, Bargain, ...) — keyword-based entries
                    // (keywordName != null) use keywordCostsByCtoId below instead.
                    entry.abilityGrpId
                        .takeIf { entry.keywordName == null && entry.type != CastingTimeOptionType.Kicker }
                        ?.let { ctoIds[index] to it }
                }.toMap(),
            entries.mapIndexedNotNull { index, entry -> entry.keywordName?.let { ctoIds[index] to it } }.toMap(),
        )
    }

    private fun publishClaimed(publication: Publication) {
        owner.beforePublicationLock?.invoke()
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            checkClaim(publication.claim)
            validate(publication)
            val seatId = actions.seatFor(publication.claim.actionId)
            owner.registerViewer(seatId)
            val routes = owner.viewerRoutes(seatId)
            val prior = owner.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            prepareAndInstallLocked(routes, publication, prior, planner)
        }
    }

    private fun publishAdoptedOptional(
        receipt: DeferredCastReceipt,
        request: CastingTimeOptionsReq,
        ctoIds: List<Int>,
        clearHybridStash: Boolean,
    ): Boolean {
        synchronized(owner.feedLock) {
            if (adoptedHybrid(receipt) == null) return false
        }
        owner.beforePublicationLock?.invoke()
        synchronized(owner.feedLock) {
            owner.ensureOpen()
            val pending = adoptedHybrid(receipt) ?: return false
            checkClaim(pending.actionClaim)
            val publication = Publication.Optional(pending.actionClaim, request, ctoIds, clearHybridStash)
            validate(publication)
            val seatId = actions.seatFor(pending.actionClaim.actionId)
            owner.registerViewer(seatId)
            val routes = owner.viewerRoutes(seatId)
            val prior = owner.bridge.projectionStateSnapshot()
            val planner = LogicalSequencePlanner(prior.sequence)
            prepareAndInstallLocked(routes, publication, prior, planner)
            return true
        }
    }

    private fun adoptedHybrid(receipt: DeferredCastReceipt): Prompt.HybridManaType? {
        val pending = prompt as? Prompt.HybridManaType ?: return null
        return pending.takeIf {
            it.adopted && it.actionClaim.actionId == receipt.actionId && it.actionClaim.token == receipt.token
        }
    }

    private fun validate(publication: Publication) {
        when (publication) {
            is Publication.Hybrid ->
                check(publication.ctoIds.size == publication.promptColors.size) {
                    "Deferred hybrid catalog no longer matches the action plan"
                }
            is Publication.Optional -> {
                val entries =
                    publication.claim.deferredCostPlan
                        ?.optional
                        ?.entries
                        .orEmpty()
                check(entries.size == publication.ctoIds.size) { "Deferred optional-cost catalog no longer matches the action plan" }
            }
            is Publication.Alternate -> {
                val tokens =
                    publication.claim.deferredCostPlan
                        ?.alternate
                        ?.choices
                        .orEmpty()
                check(tokens.size == publication.ctoIds.size) { "Deferred alternate-cost catalog no longer matches the action plan" }
            }
        }
    }

    private fun prepareAndInstallLocked(
        routes: List<BundleBuilder.ViewerRoute>,
        publication: Publication,
        prior: ProjectionState,
        planner: LogicalSequencePlanner,
    ) {
        val prepared = materialize(routes, publication.request, planner)
        val player = prepared.player
        val gameStateId = checkNotNull(player.bundle.actionGameStateId)
        val nextPrompt =
            when (publication) {
                is Publication.Hybrid ->
                    Prompt.HybridManaType(
                        publication.claim,
                        gameStateId,
                        publication.ctoIds.toList(),
                        publication.promptColors.toList(),
                        publication.paymentColors.toList(),
                    )
                is Publication.Optional -> optionalPrompt(publication.claim, gameStateId, publication.ctoIds)
                is Publication.Alternate -> {
                    val choices =
                        publication.claim.deferredCostPlan
                            ?.alternate
                            ?.choices
                            .orEmpty()
                    Prompt.AlternateCostChoice(publication.claim, gameStateId, publication.ctoIds.zip(choices).toMap())
                }
            }
        install(prepared, nextPrompt, publication, prior, planner)
    }

    private fun materialize(
        routes: List<BundleBuilder.ViewerRoute>,
        request: CastingTimeOptionsReq,
        planner: LogicalSequencePlanner,
    ): BundleBuilder.PreparedViewerCut<BundleBuilder.ActionWindowPrepared> =
        try {
            beforeMaterialization?.invoke()
            val game = owner.bridge.getGame() ?: error("Game unavailable")
            val playerRoute = routes.single { it.viewer.role == leyline.game.state.ProjectionViewerRole.Player }
            playerRoute.builder.prepareCastingTimeOptions(game, planner, request, routes)
        } catch (ex: Exception) {
            owner.fail(ex)
        }

    private fun install(
        prepared: BundleBuilder.PreparedViewerCut<BundleBuilder.ActionWindowPrepared>,
        nextPrompt: Prompt,
        publication: Publication,
        prior: ProjectionState,
        planner: LogicalSequencePlanner,
    ) {
        owner.cutInstaller.install(
            PreparedCut.prepareForViewers(
                prior = prior,
                planner = planner,
                outputs = prepared.viewers.map { PreparedViewerOutput(it.seatId, it.batches) },
                projection = prepared.transition,
                closesPlaybackFrame = prepared.closesPlaybackFrame,
                playbackOwnerSeatId = actions.seatFor(publication.claim.actionId).takeIf { prepared.closesPlaybackFrame },
            ),
            CutInstallHooks(beforeInstall = beforeInstall),
            onInstalled = {
                prompt = nextPrompt
                when (publication) {
                    is Publication.Hybrid ->
                        owner.bridge
                            .seat(actions.seatFor(publication.claim.actionId))
                            .prompt.journal
                            .clearHybridManaStash()
                    is Publication.Optional -> clearCostStashes(publication.claim, publication.clearHybridStash)
                    is Publication.Alternate -> Unit
                }
            },
        ) { ex -> owner.fail(ex) }
    }

    private fun clearCostStashes(
        claim: MatchActionWindowRuntime.ActionClaim,
        clearHybrid: Boolean,
    ) {
        val journal =
            owner.bridge
                .seat(actions.seatFor(claim.actionId))
                .prompt.journal
        journal.clearKeywordCostStash()
        if (clearHybrid) journal.clearHybridManaStash()
        journal.clearCollectEvidenceCost()
    }

    private fun checkClaim(claim: MatchActionWindowRuntime.ActionClaim) {
        check(actions.isDeferredClaim(claim)) { "Deferred cast prompt is not owned by its action claim" }
    }

    private fun reorderHybridChoices(
        promptChoices: List<ManaColor>,
        promptColors: List<ManaColor>,
        paymentColors: List<ManaColor>,
    ): List<ManaColor> {
        val used = BooleanArray(promptChoices.size)
        return paymentColors.map { paymentColor ->
            val promptIndex = promptColors.indices.firstOrNull { index -> !used[index] && promptColors[index] == paymentColor }
            if (promptIndex == null) {
                paymentColor
            } else {
                used[promptIndex] = true
                promptChoices.getOrNull(promptIndex) ?: paymentColor
            }
        }
    }
}
