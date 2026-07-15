package ai.grayin.core.retrieval

import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.MissingSource

object ConnectorScanMissingResolver {
    fun merge(
        plan: QueryPlan,
        plannedMissingSources: List<MissingSource>,
        scanStatuses: List<ConnectorScanStatus>,
        issueExplanation: (ConnectorScanIssueCode) -> String = { code -> code.defaultEnglish },
    ): List<MissingSource> {
        val relevantCapabilities = plan.requiredCapabilities + plan.optionalCapabilities
        val scanMissingSources = scanStatuses
            .filter { status -> status.appliesTo(plan.timeRange) }
            .flatMap { status -> status.missingSources }
            .filter { missing -> missing.capability in relevantCapabilities }
            .map { missing ->
                missing.issueCode?.let { issueCode ->
                    missing.copy(explanation = issueExplanation(issueCode))
                } ?: missing
            }
        return (plannedMissingSources + scanMissingSources).distinctBy { missing ->
            listOf(
                missing.capability.name,
                missing.availability.name,
                missing.connectorId.orEmpty(),
                missing.issueCode?.storageKey ?: missing.explanation,
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
