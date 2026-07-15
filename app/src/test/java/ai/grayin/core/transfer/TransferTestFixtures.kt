package ai.grayin.core.transfer

import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.model.AppUsageCategory
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DailyMemorySummary
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import ai.grayin.core.store.LocalMemorySnapshot
import java.time.Instant
import java.time.LocalDate

internal object TransferTestFixtures {
    val observedAt: Instant = Instant.parse("2026-07-15T01:02:03Z")
    const val localHmac = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    fun payload(): TransferPayload {
        return TransferPayload(
            createdAt = observedAt,
            producer = TransferProducerMetadata(
                applicationId = "ai.grayin",
                versionCode = 1,
                storeSchemaVersion = 6,
            ),
            snapshot = snapshot(),
        )
    }

    fun snapshot(): LocalMemorySnapshot {
        val sources = listOf(
            source(
                connectorId = "location",
                suffix = "location-one",
                kind = SourceKind.LOCATION,
                pointer = "location-provider:gps",
            ),
            source(
                connectorId = "photos",
                suffix = "photo-one",
                kind = SourceKind.PHOTO,
                pointer = "content://media/photo/1",
            ),
            source(
                connectorId = "calendar",
                suffix = "calendar-one",
                kind = SourceKind.CALENDAR,
                pointer = "content://calendar/events/1",
                appIdentifier = "Personal",
            ),
            source(
                connectorId = "notification",
                suffix = "notification-one",
                kind = SourceKind.NOTIFICATION,
                appIdentifier = "com.example.pay",
            ),
            source(
                connectorId = "app_usage",
                suffix = "usage-one",
                kind = SourceKind.APP_USAGE,
                appIdentifier = "com.example.app",
            ),
            SourceReference(
                id = "source:local_files:$localHmac",
                connectorId = "local_files",
                sourceKind = SourceKind.LOCAL_FILE,
                localPointer = "content://documents/private-note.txt",
                hmacHash = localHmac,
                observedAt = observedAt,
                modifiedAt = observedAt,
                sensitivity = SensitivityLevel.HIGH,
            ),
        )
        val events = listOf(
            event("location", "location-one", DerivedMemoryEventKind.PLACE_VISIT, "Location visit indexed."),
            event("photos", "photo-one", DerivedMemoryEventKind.PHOTO_INDEX, "Photo metadata indexed."),
            event("calendar", "calendar-one", DerivedMemoryEventKind.CALENDAR_EVENT, "Calendar event indexed."),
            event("notification", "notification-one", DerivedMemoryEventKind.PAYMENT, "Payment signal indexed."),
            event("app_usage", "usage-one", DerivedMemoryEventKind.APP_USAGE, "App usage indexed."),
            DerivedMemoryEvent(
                id = "event:local_files:$localHmac",
                kind = DerivedMemoryEventKind.LOCAL_FILE_INDEX,
                sourceReferenceIds = listOf("source:local_files:$localHmac"),
                summary = "Local text file indexed with 3 non-empty line(s). Signals: memory.",
                startedAt = observedAt,
                keywords = listOf("memory"),
                labels = listOf("local-file", "text"),
                confidence = ConfidenceLevel.MEDIUM,
                sensitivity = SensitivityLevel.HIGH,
                citationIds = listOf("citation:local_files:$localHmac"),
                createdAt = observedAt,
            ),
        )
        val citations = listOf(
            citation("location", "location-one", "Location sample"),
            citation("photos", "photo-one", "Photo metadata"),
            citation("calendar", "calendar-one", "Calendar event"),
            citation("notification", "notification-one", "Notification signal"),
            citation("app_usage", "usage-one", "App usage"),
            MemoryCitation(
                id = "citation:local_files:$localHmac",
                sourceReferenceId = "source:local_files:$localHmac",
                derivedMemoryEventId = "event:local_files:$localHmac",
                label = "Local text document",
                observedAt = observedAt,
                confidence = ConfidenceLevel.MEDIUM,
            ),
        )
        val cluster = PlaceCluster(
            id = "place-cluster:location:home",
            label = "Home area",
            regionLabel = "Seoul",
            centroidLatitude = 37.5,
            centroidLongitude = 127.0,
            radiusMeters = 120.0,
            firstSeenAt = observedAt,
            lastSeenAt = observedAt,
            visitCount = 1,
            sourceReferenceIds = listOf("source:location:location-one"),
            confidence = ConfidenceLevel.MEDIUM,
        )
        val usage = AppUsageSummary(
            id = "app-usage:app_usage:usage-one",
            sourceReferenceIds = listOf("source:app_usage:usage-one"),
            date = LocalDate.parse("2026-07-15"),
            packageName = "com.example.app",
            appAlias = "Example",
            category = AppUsageCategory.WORK,
            totalDurationMinutes = 30,
            launchCount = 2,
            activeTimeBucketLabels = listOf("morning"),
            confidence = ConfidenceLevel.MEDIUM,
        )
        val daily = DailyMemorySummary(
            id = "daily:2026-07-15",
            date = LocalDate.parse("2026-07-15"),
            summary = "A bounded daily summary.",
            derivedMemoryEventIds = events.map(DerivedMemoryEvent::id),
            placeClusterIds = listOf(cluster.id),
            appUsageSummaryIds = listOf(usage.id),
            confidence = ConfidenceLevel.MEDIUM,
        )
        val statuses = listOf(
            "location",
            "photos",
            "calendar",
            "notification",
            "app_usage",
            "local_files",
        ).map { connectorId ->
            ConnectorScanStatus(
                connectorId = connectorId,
                processingState = ProcessingState.COMPLETED,
                missingSources = emptyList(),
                scannedAt = observedAt,
            )
        }
        return LocalMemorySnapshot(
            sourceReferences = sources,
            derivedMemoryEvents = events,
            citations = citations,
            dailySummaries = listOf(daily),
            placeClusters = listOf(cluster),
            appUsageSummaries = listOf(usage),
            connectorScanStatuses = statuses,
        )
    }

    private fun source(
        connectorId: String,
        suffix: String,
        kind: SourceKind,
        pointer: String? = null,
        appIdentifier: String? = null,
    ): SourceReference {
        return SourceReference(
            id = "source:$connectorId:$suffix",
            connectorId = connectorId,
            sourceKind = kind,
            localPointer = pointer,
            externalIdHash = "$suffix-hash",
            sourceAppIdentifier = appIdentifier,
            observedAt = observedAt,
            modifiedAt = observedAt,
            sensitivity = SensitivityLevel.HIGH,
        )
    }

    private fun event(
        connectorId: String,
        suffix: String,
        kind: DerivedMemoryEventKind,
        summary: String,
    ): DerivedMemoryEvent {
        return DerivedMemoryEvent(
            id = "event:$connectorId:$suffix",
            kind = kind,
            sourceReferenceIds = listOf("source:$connectorId:$suffix"),
            summary = summary,
            startedAt = observedAt,
            keywords = listOf("indexed"),
            labels = listOf(connectorId),
            confidence = ConfidenceLevel.MEDIUM,
            sensitivity = SensitivityLevel.HIGH,
            citationIds = listOf("citation:$connectorId:$suffix"),
            createdAt = observedAt,
        )
    }

    private fun citation(connectorId: String, suffix: String, label: String): MemoryCitation {
        return MemoryCitation(
            id = "citation:$connectorId:$suffix",
            sourceReferenceId = "source:$connectorId:$suffix",
            derivedMemoryEventId = "event:$connectorId:$suffix",
            label = label,
            observedAt = observedAt,
            confidence = ConfidenceLevel.MEDIUM,
        )
    }
}
