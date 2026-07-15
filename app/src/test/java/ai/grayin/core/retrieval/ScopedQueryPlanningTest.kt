package ai.grayin.core.retrieval

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedQueryPlanningTest {
    @Test
    fun resolvesCapabilitiesInsideRequestedTimeRange() {
        val yesterdayLocation = event(
            id = "location:yesterday",
            kind = DerivedMemoryEventKind.PLACE_VISIT,
            occurredAt = Instant.parse("2026-07-14T03:00:00Z"),
        )
        val todayPhoto = event(
            id = "photo:today",
            kind = DerivedMemoryEventKind.PHOTO_INDEX,
            occurredAt = Instant.parse("2026-07-15T03:00:00Z"),
        )

        val result = ScopedQueryPlanning.resolve(
            query = "어제 어디에 갔어?",
            events = listOf(todayPhoto, yesterdayLocation),
            planner = DefaultQueryPlanner(ApproximateTimeParser(java.time.ZoneId.of("Asia/Seoul"))),
            now = Instant.parse("2026-07-15T03:00:00Z"),
        )

        assertEquals(listOf(yesterdayLocation), result.events)
        assertTrue(result.plan.missingRequiredCapabilities.isEmpty())
    }

    private fun event(
        id: String,
        kind: DerivedMemoryEventKind,
        occurredAt: Instant,
    ): DerivedMemoryEvent {
        return DerivedMemoryEvent(
            id = id,
            kind = kind,
            sourceReferenceIds = listOf("source:$id"),
            summary = id,
            startedAt = occurredAt,
            confidence = ConfidenceLevel.MEDIUM,
            createdAt = occurredAt,
        )
    }
}
