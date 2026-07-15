package ai.grayin.core.retrieval

import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SourceAvailability
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectorScanMissingResolverTest {
    @Test
    fun mergeAddsOnlyRelevantScanIssuesAndDeduplicates() {
        val relevant = missing(MemoryCapability.HAS_TEXT, "local_document:page_limit")
        val irrelevant = missing(MemoryCapability.HAS_LOCATION, "location:no_sample")
        val plan = QueryPlan(
            query = "what was in the document",
            intent = QueryIntent.GENERAL_MEMORY_RECALL,
            requiredCapabilities = setOf(MemoryCapability.HAS_TEXT),
            optionalCapabilities = emptySet(),
            availableCapabilities = setOf(MemoryCapability.HAS_TEXT),
            missingRequiredCapabilities = emptySet(),
            missingOptionalCapabilities = emptySet(),
            missingSources = emptyList(),
        )
        val status = ConnectorScanStatus(
            connectorId = "local_files",
            processingState = ProcessingState.COMPLETED,
            missingSources = listOf(relevant, relevant, irrelevant),
            scannedAt = Instant.parse("2026-07-15T05:00:00Z"),
        )

        val merged = ConnectorScanMissingResolver.merge(
            plan = plan,
            plannedMissingSources = listOf(relevant),
            scanStatuses = listOf(status),
        )

        assertEquals(listOf(relevant), merged)
    }

    @Test
    fun mergeUsesScopedStatusOnlyWhenItContainsTheQueryRange() {
        val missing = missing(MemoryCapability.HAS_TEXT, "local_document:no_text_in_range")
        val queryStart = Instant.parse("2026-07-10T00:00:00Z")
        val queryEnd = Instant.parse("2026-07-11T00:00:00Z")
        val plan = QueryPlan(
            query = "what was in the document yesterday",
            intent = QueryIntent.GENERAL_MEMORY_RECALL,
            timeRange = ApproximateTimeRange(
                label = "yesterday",
                startInclusive = queryStart,
                endExclusive = queryEnd,
                isFuture = false,
            ),
            requiredCapabilities = setOf(MemoryCapability.HAS_TEXT),
            optionalCapabilities = emptySet(),
            availableCapabilities = emptySet(),
            missingRequiredCapabilities = setOf(MemoryCapability.HAS_TEXT),
            missingOptionalCapabilities = emptySet(),
            missingSources = emptyList(),
        )
        val containing = status(
            missing = missing,
            from = queryStart.minusSeconds(60),
            until = queryEnd.plusSeconds(60),
        )
        val unrelated = status(
            missing = missing.copy(explanation = "unrelated"),
            from = queryEnd.plusSeconds(60),
            until = queryEnd.plusSeconds(120),
        )

        val merged = ConnectorScanMissingResolver.merge(
            plan = plan,
            plannedMissingSources = emptyList(),
            scanStatuses = listOf(containing, unrelated),
        )

        assertEquals(listOf(missing), merged)
    }

    @Test
    fun mergeDoesNotApplyScopedStatusToAnUnboundedQuery() {
        val missing = missing(MemoryCapability.HAS_TEXT, "local_document:no_text_in_range")
        val plan = QueryPlan(
            query = "what was in the document",
            intent = QueryIntent.GENERAL_MEMORY_RECALL,
            requiredCapabilities = setOf(MemoryCapability.HAS_TEXT),
            optionalCapabilities = emptySet(),
            availableCapabilities = emptySet(),
            missingRequiredCapabilities = setOf(MemoryCapability.HAS_TEXT),
            missingOptionalCapabilities = emptySet(),
            missingSources = emptyList(),
        )

        val merged = ConnectorScanMissingResolver.merge(
            plan = plan,
            plannedMissingSources = emptyList(),
            scanStatuses = listOf(
                status(
                    missing = missing,
                    from = Instant.parse("2026-07-10T00:00:00Z"),
                    until = Instant.parse("2026-07-11T00:00:00Z"),
                ),
            ),
        )

        assertEquals(emptyList<MissingSource>(), merged)
    }

    private fun status(
        missing: MissingSource,
        from: Instant,
        until: Instant,
    ): ConnectorScanStatus {
        return ConnectorScanStatus(
            connectorId = "local_files",
            processingState = ProcessingState.SKIPPED,
            missingSources = listOf(missing),
            scopeFrom = from,
            scopeUntil = until,
            scannedAt = until,
        )
    }

    private fun missing(capability: MemoryCapability, explanation: String): MissingSource {
        return MissingSource(
            capability = capability,
            availability = SourceAvailability.UNSUPPORTED,
            explanation = explanation,
            connectorId = "local_files",
        )
    }
}
