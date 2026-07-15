package ai.grayin.core.retrieval

import ai.grayin.core.model.MemoryCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingEvidenceResolverTest {
    private val planner = DefaultQueryPlanner()

    @Test
    fun reportsQueriedCapabilityWhenNoEvidenceMatches() {
        val plan = planner.plan(
            query = "How much app usage was there?",
            availableCapabilities = setOf(
                MemoryCapability.HAS_TIME,
                MemoryCapability.HAS_APP_USAGE,
            ),
        )

        val missing = MissingEvidenceResolver.resolve(
            plan = plan,
            hasEvidence = false,
            noMatchingEvidenceExplanation = "No matching indexed evidence.",
        )

        assertEquals(
            setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_APP_USAGE),
            missing.mapTo(hashSetOf()) { source -> source.capability },
        )
        assertTrue(missing.all { source -> source.connectorId == null })
    }

    @Test
    fun preservesPlannerMissingSourcesWhenEvidenceExists() {
        val plan = planner.plan(
            query = "Where did I go yesterday?",
            availableCapabilities = setOf(MemoryCapability.HAS_TIME),
        )

        assertEquals(
            plan.missingSources,
            MissingEvidenceResolver.resolve(plan, true, "unused"),
        )
    }
}
