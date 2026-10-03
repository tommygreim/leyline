package leyline.bridge.coord

import forge.game.card.Card
import leyline.bridge.handoff.PromptJournal
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.bridge.handoff.RevealChoiceCandidateValue
import leyline.bridge.handoff.RevealChoiceWindowValue
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId

/** Engine-thread snapshot for one immutable reveal-backed SelectN window. */
internal object RevealChoiceWindowCapture {
    data class Initial(
        val value: RevealChoiceWindowValue,
        val revealEntry: PromptJournal.RevealEntry,
        val handlesByOption: Map<Int, Card>,
    )

    fun initial(
        request: PromptRequest,
        candidateHandles: List<Card>,
        revealEntry: PromptJournal.RevealEntry,
        journalSeatId: SeatId,
        recordExiledUnderSource: Boolean,
    ): Initial {
        check(request.route is ResolvedPromptRoute.RevealChoice) { "RevealChoice route required" }
        check(candidateHandles.size == request.options.size) { "RevealChoice handles must match options" }
        val refsByOption = request.candidateRefs.associateBy { it.index }
        check(refsByOption.size == candidateHandles.size) { "RevealChoice candidates must have distinct option indices" }
        val candidates =
            candidateHandles.mapIndexed { index, handle ->
                val ref = refsByOption[index] ?: error("Missing RevealChoice candidate for option $index")
                check(ref.isCard() && ref.entityId == handle.id) { "RevealChoice candidate does not match its exact handle" }
                RevealChoiceCandidateValue(index, ForgeCardId(handle.id))
            }
        val fullRevealCardIds = revealEntry.reveal.allHandCardIds
        check(fullRevealCardIds.isNotEmpty() && fullRevealCardIds.distinct().size == fullRevealCardIds.size) {
            "RevealChoice requires distinct revealed cards"
        }
        check(candidates.map { it.forgeCardId }.distinct().size == candidates.size) {
            "RevealChoice candidates must be distinct cards"
        }
        check(candidates.all { it.forgeCardId in fullRevealCardIds }) { "RevealChoice candidates must belong to the exact reveal" }
        check(request.min in 0..request.max && request.max <= candidates.size) { "Invalid RevealChoice cardinality" }
        check(candidates.isEmpty() || request.defaultIndex in candidates.indices) { "Invalid RevealChoice default option" }
        check(candidates.isNotEmpty() || (request.min == 0 && request.max == 0)) {
            "Empty RevealChoice requires zero cardinality"
        }
        val source = request.sourceEntityId?.let(::ForgeCardId)
        return Initial(
            value =
                RevealChoiceWindowValue(
                    candidates = candidates,
                    fullRevealCardIds = fullRevealCardIds.toList(),
                    journalSeatId = journalSeatId,
                    revealVersion = revealEntry.version,
                    revealOwnerSeatId = revealEntry.reveal.ownerSeatId,
                    sourceForgeCardId = source,
                    exileUnderSourceForgeCardId = source.takeIf { recordExiledUnderSource },
                    min = request.min,
                    max = request.max,
                    defaultOptionIndex = request.defaultIndex,
                    promptId = request.targetPromptId,
                ),
            revealEntry = revealEntry,
            handlesByOption = candidateHandles.mapIndexed { index, card -> index to card }.toMap(),
        )
    }
}
