package leyline.tooling.simclient

import leyline.bridge.handoff.PromptCallStatus
import leyline.bridge.handoff.PromptRecord
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolvedPromptRoute
import leyline.tooling.simclient.PromptRouteFinding
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

data class PromptRouteAudit(
    val requestsByKind: Map<String, Int>,
    val samplesByKind: Map<String, String>,
    val findings: List<PromptRouteFinding>,
) {
    companion object {
        val Empty = PromptRouteAudit(emptyMap(), emptyMap(), emptyList())
    }
}

object PromptRouteAuditor {
    const val SAME_GRE_ROUTE_UNVERIFIED = "same_gre_route_unverified"

    fun audit(
        history: List<PromptRecord>,
        promptHistogram: Map<GREMessageType, Int>,
    ): PromptRouteAudit {
        if (history.isEmpty()) return PromptRouteAudit.Empty

        val requestsByKind = history.groupingBy { it.kindKey() }.eachCount().toSortedMap()
        val samplesByKind =
            history
                .groupBy { it.kindKey() }
                .mapValues { (_, records) -> records.first().message.compactSample() }
                .toSortedMap()
        val emittedByGreType = promptHistogram.toRouteNames()
        val expectedRoutes = history.mapNotNull { record -> record.expectedRoute()?.let { it to record } }
        val expectedByGreType = expectedRoutes.groupingBy { it.first.expectedGreType }.eachCount()
        val routeKeysByGreType =
            expectedRoutes
                .groupBy { it.first.expectedGreType }
                .mapValues { (_, routes) -> routes.map { it.first.routeKey }.toSet() }
        val findings =
            expectedRoutes
                .groupBy { it.first.routeKey }
                .values
                .mapNotNull { routeRecords ->
                    val route = routeRecords.first().first
                    val records = routeRecords.map { it.second }
                    val emitted = emittedByGreType[route.expectedGreType] ?: 0
                    val outcomes = records.groupingBy { it.outcome.name }.eachCount().toSortedMap()
                    val expectedForType = expectedByGreType.getValue(route.expectedGreType)
                    val bucket =
                        when {
                            emitted < expectedForType ->
                                classifyBucket(expectedCount = records.size, emittedCount = emitted, outcomeCounts = outcomes)
                            routeKeysByGreType.getValue(route.expectedGreType).size > 1 -> SAME_GRE_ROUTE_UNVERIFIED
                            else -> return@mapNotNull null
                        }
                    PromptRouteFinding(
                        bucket = bucket,
                        routeKey = route.routeKey,
                        enginePromptType = route.enginePromptType,
                        semantic = route.semantic.name,
                        expectedGreType = route.expectedGreType,
                        expectedCount = records.size,
                        emittedCount = emitted,
                        outcomeCounts = outcomes,
                        sampleMessage = records.first().message.compactSample(),
                    )
                }.sortedWith(compareBy<PromptRouteFinding> { it.bucket }.thenBy { it.routeKey })
        return PromptRouteAudit(requestsByKind, samplesByKind, findings)
    }

    private fun PromptRecord.expectedRoute(): ExpectedRoute? {
        val expectedGreType = route.expectedGreType() ?: return null
        return ExpectedRoute(
            routeKey = kindKey(),
            enginePromptType = promptType,
            semantic = semantic,
            expectedGreType = expectedGreType,
        )
    }

    private fun ResolvedPromptRoute.expectedGreType(): String? =
        when (this) {
            is ResolvedPromptRoute.Grouping -> "GroupReq"
            is ResolvedPromptRoute.ModalChoice -> "CastingTimeOptionsReq"
            is ResolvedPromptRoute.UnclassifiedEntityChoice -> "SelectNReq"
            is ResolvedPromptRoute.CardSelect -> "SelectNReq"
            is ResolvedPromptRoute.StaticChoice -> "SelectNReq"
            is ResolvedPromptRoute.RevealChoice -> "SelectNReq"
            is ResolvedPromptRoute.PayCosts -> "PayCostsReq"
            is ResolvedPromptRoute.Search ->
                if (semantic == PromptSemantic.GroupedSearch) "SearchFromGroupsReq" else "SearchReq"
            is ResolvedPromptRoute.SelectReplacement -> "SelectReplacementReq"
            is ResolvedPromptRoute.Order -> "OrderReq"
            is ResolvedPromptRoute.OrderTriggers -> "SelectNReq"
            is ResolvedPromptRoute.Distribution -> "DistributionReq"
            is ResolvedPromptRoute.Targeting -> "SelectTargetsReq"
            is ResolvedPromptRoute.CompatibilityCostSelection -> "SelectTargetsReq"
            is ResolvedPromptRoute.AutoResolve -> null
        }

    private fun classifyBucket(
        expectedCount: Int,
        emittedCount: Int,
        outcomeCounts: Map<String, Int>,
    ): String =
        when {
            outcomeCounts.getOrDefault(PromptCallStatus.TIMEOUT.name, 0) > 0 -> "defaulted_timeout"
            emittedCount == 0 && outcomeCounts.getOrDefault(PromptCallStatus.RESPONDED.name, 0) == expectedCount ->
                "swallowed_auto_resolve"
            else -> "wrong_req"
        }

    private fun PromptRecord.kindKey(): String = "$promptType|${semantic.name}"

    private fun Map<GREMessageType, Int>.toRouteNames(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for ((type, count) in this) {
            out.merge(type.routeName(), count) { a, b -> a + b }
        }
        return out.toSortedMap()
    }

    private fun GREMessageType.routeName(): String =
        when (val base = name.replace(ENUM_TAG_SUFFIX, "")) {
            "SelectNreq" -> "SelectNReq"
            else -> base
        }

    private fun String.compactSample(): String = replace(Regex("\\s+"), " ").take(160)

    private data class ExpectedRoute(
        val routeKey: String,
        val enginePromptType: String,
        val semantic: PromptSemantic,
        val expectedGreType: String,
    )

    private val ENUM_TAG_SUFFIX = Regex("_[a-f0-9]{4}$")
}
