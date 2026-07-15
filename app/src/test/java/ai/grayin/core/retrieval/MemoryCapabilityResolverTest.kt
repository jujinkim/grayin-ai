package ai.grayin.core.retrieval

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCapability
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCapabilityResolverTest {
    @Test
    fun resolvesCapabilitiesAcrossEveryIndexedSourceKind() {
        val events = listOf(
            event(DerivedMemoryEventKind.PLACE_VISIT),
            event(DerivedMemoryEventKind.PHOTO_INDEX),
            event(DerivedMemoryEventKind.CALENDAR_EVENT),
            event(DerivedMemoryEventKind.PAYMENT),
            event(DerivedMemoryEventKind.APP_USAGE),
            event(DerivedMemoryEventKind.LOCAL_FILE_INDEX),
        )

        val capabilities = MemoryCapabilityResolver.forEvents(events)

        assertTrue(MemoryCapability.HAS_LOCATION in capabilities)
        assertTrue(MemoryCapability.HAS_MEDIA in capabilities)
        assertTrue(MemoryCapability.HAS_CALENDAR in capabilities)
        assertTrue(MemoryCapability.HAS_PAYMENT in capabilities)
        assertTrue(MemoryCapability.HAS_APP_USAGE in capabilities)
        assertTrue(MemoryCapability.HAS_TEXT in capabilities)
        assertTrue(MemoryCapability.HAS_TIME in capabilities)
        assertTrue(MemoryCapability.HAS_VISUAL_LABEL !in capabilities)
    }

    @Test
    fun assignsDomainCapabilitiesToSingleEvidenceEvent() {
        assertEquals(
            setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_TRANSPORT),
            MemoryCapabilityResolver.forEvent(event(DerivedMemoryEventKind.TRANSPORT)),
        )
    }

    private fun event(kind: DerivedMemoryEventKind): DerivedMemoryEvent {
        return DerivedMemoryEvent(
            id = "event:$kind",
            kind = kind,
            sourceReferenceIds = listOf("source:$kind"),
            summary = kind.name,
            confidence = ConfidenceLevel.MEDIUM,
            createdAt = Instant.parse("2026-07-15T00:00:00Z"),
        )
    }
}
