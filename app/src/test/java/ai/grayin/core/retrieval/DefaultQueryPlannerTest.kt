package ai.grayin.core.retrieval

import ai.grayin.core.model.MemoryCapability
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultQueryPlannerTest {
    private val planner = DefaultQueryPlanner()

    @Test
    fun locationQuestionRequiresLocationAndTime() {
        val plan = planner.plan(
            query = "Where did I go yesterday?",
            availableCapabilities = setOf(MemoryCapability.HAS_TIME),
            now = Instant.parse("2026-06-24T12:00:00Z"),
        )

        assertEquals(QueryIntent.LOCATION_RECALL, plan.intent)
        assertEquals("yesterday", plan.timeRange?.label)
        assertTrue(plan.requiredCapabilities.contains(MemoryCapability.HAS_LOCATION))
        assertTrue(plan.missingRequiredCapabilities.contains(MemoryCapability.HAS_LOCATION))
    }

    @Test
    fun localTextCanSupportGeneralRecallWithoutNetwork() {
        val plan = planner.plan(
            query = "What did I write about Seoul?",
            availableCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_TEXT),
            now = Instant.parse("2026-06-24T12:00:00Z"),
        )

        assertEquals(QueryIntent.GENERAL_MEMORY_RECALL, plan.intent)
        assertTrue(plan.missingRequiredCapabilities.isEmpty())
    }
}
