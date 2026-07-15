package ai.grayin.core.retrieval

import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.model.MissingSource

object ConnectorScanMissingResolver {
    fun merge(
        plan: QueryPlan,
        plannedMissingSources: List<MissingSource>,
        scanStatuses: List<ConnectorScanStatus>,
    ): List<MissingSource> {
        val relevantCapabilities = plan.requiredCapabilities + plan.optionalCapabilities
        val scanMissingSources = scanStatuses
            .filter { status -> status.appliesTo(plan.timeRange) }
            .flatMap { status -> status.missingSources }
            .filter { missing -> missing.capability in relevantCapabilities }
        return (plannedMissingSources + scanMissingSources).distinctBy { missing ->
            listOf(
                missing.capability.name,
                missing.availability.name,
                missing.connectorId.orEmpty(),
                missing.explanation,
            ).joinToString("|")
        }
    }

    private fun ConnectorScanStatus.appliesTo(timeRange: ApproximateTimeRange?): Boolean {
        if (scopeFrom == null && scopeUntil == null) return true
        if (timeRange == null) return false
        return (scopeFrom == null || !timeRange.startInclusive.isBefore(scopeFrom)) &&
            (scopeUntil == null || !timeRange.endExclusive.isAfter(scopeUntil))
    }
}
