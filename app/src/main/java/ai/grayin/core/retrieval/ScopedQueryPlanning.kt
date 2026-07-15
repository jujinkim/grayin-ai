package ai.grayin.core.retrieval

import ai.grayin.core.model.DerivedMemoryEvent
import java.time.Instant

data class ScopedQueryPlanningResult(
    val plan: QueryPlan,
    val events: List<DerivedMemoryEvent>,
)

object ScopedQueryPlanning {
    fun resolve(
        query: String,
        events: List<DerivedMemoryEvent>,
        planner: QueryPlanner,
        now: Instant = Instant.now(),
    ): ScopedQueryPlanningResult {
        val initialPlan = planner.plan(
            query = query,
            availableCapabilities = MemoryCapabilityResolver.forEvents(events),
            now = now,
        )
        val scopedEvents = events.filter { event ->
            val timeRange = initialPlan.timeRange ?: return@filter true
            val occurredAt = event.startedAt ?: event.createdAt
            occurredAt >= timeRange.startInclusive && occurredAt < timeRange.endExclusive
        }
        val scopedPlan = planner.plan(
            query = query,
            availableCapabilities = MemoryCapabilityResolver.forEvents(scopedEvents),
            now = now,
        )
        return ScopedQueryPlanningResult(scopedPlan, scopedEvents)
    }
}
