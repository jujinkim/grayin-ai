package ai.grayin.core.retrieval

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCapability
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class EvidenceEventSelectorTest {
    @Test
    fun noTokenMatchFallsBackOnlyToRequiredDomain() {
        val location = event("location", DerivedMemoryEventKind.PLACE_VISIT, "2026-07-14T00:00:00Z")
        val newerLocalFile = event("file", DerivedMemoryEventKind.LOCAL_FILE_INDEX, "2026-07-15T00:00:00Z")
        val plan = DefaultQueryPlanner().plan(
            query = "Where did I go?",
            availableCapabilities = setOf(
                MemoryCapability.HAS_TIME,
                MemoryCapability.HAS_LOCATION,
                MemoryCapability.HAS_TEXT,
            ),
        )

        val selected = EvidenceEventSelector.select(
            query = "Where did I go?",
            plan = plan,
            events = listOf(newerLocalFile, location),
            limit = 8,
        )

        assertEquals(listOf(location), selected)
    }

    private fun event(id: String, kind: DerivedMemoryEventKind, time: String): DerivedMemoryEvent {
        val instant = Instant.parse(time)
        return DerivedMemoryEvent(
            id = id,
            kind = kind,
            sourceReferenceIds = listOf("source:$id"),
            summary = id,
            confidence = ConfidenceLevel.MEDIUM,
            createdAt = instant,
        )
    }
}
