package ai.grayin.core.retrieval

import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCapability

object MemoryCapabilityResolver {
    fun forEvents(events: Iterable<DerivedMemoryEvent>): Set<MemoryCapability> {
        return events.flatMapTo(linkedSetOf()) { event -> forEvent(event) }
    }

    fun forEvent(event: DerivedMemoryEvent): Set<MemoryCapability> {
        return when (event.kind) {
            DerivedMemoryEventKind.PLACE_VISIT,
            DerivedMemoryEventKind.PLACE_CLUSTER,
            -> setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_LOCATION)

            DerivedMemoryEventKind.CALENDAR_EVENT -> setOf(
                MemoryCapability.HAS_TIME,
                MemoryCapability.HAS_CALENDAR,
            )

            DerivedMemoryEventKind.PHOTO_INDEX,
            DerivedMemoryEventKind.PHOTO_CLUSTER,
            -> setOf(
                MemoryCapability.HAS_TIME,
                MemoryCapability.HAS_MEDIA,
            )

            DerivedMemoryEventKind.PAYMENT -> setOf(
                MemoryCapability.HAS_TIME,
                MemoryCapability.HAS_PAYMENT,
            )

            DerivedMemoryEventKind.DELIVERY -> setOf(
                MemoryCapability.HAS_TIME,
                MemoryCapability.HAS_DELIVERY,
            )

            DerivedMemoryEventKind.RESERVATION -> setOf(
                MemoryCapability.HAS_TIME,
                MemoryCapability.HAS_RESERVATION,
            )

            DerivedMemoryEventKind.TRANSPORT -> setOf(
                MemoryCapability.HAS_TIME,
                MemoryCapability.HAS_TRANSPORT,
            )

            DerivedMemoryEventKind.APP_USAGE -> setOf(
                MemoryCapability.HAS_TIME,
                MemoryCapability.HAS_APP_USAGE,
            )

            DerivedMemoryEventKind.LOCAL_FILE_INDEX,
            DerivedMemoryEventKind.DAILY_SUMMARY,
            DerivedMemoryEventKind.INFERRED_CONTEXT,
            -> setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_TEXT)
        }
    }
}
