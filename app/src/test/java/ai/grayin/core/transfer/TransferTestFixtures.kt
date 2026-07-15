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
    private const val locationHash = "11111111111111111111111111111111"
    private const val photoHash = "22222222222222222222222222222222"
    private const val calendarHash = "33333333333333333333333333333333"
    private const val notificationHash = "44444444444444444444444444444444"
    private const val usageHash = "55555555555555555555555555555555"

    fun payload(): TransferPayload {
        return TransferPayload(
            createdAt = observedAt,
            producer = TransferProducerMetadata(
                applicationId = "ai.grayin",
                versionCode = 1,
                storeSchemaVersion = 8,
            ),
            snapshot = snapshot(),
        )
    }

    fun snapshot(): LocalMemorySnapshot {
        val sources = listOf(
            source(
                connectorId = "location",
                hash = locationHash,
                kind = SourceKind.LOCATION,
                pointer = "location-provider:gps",
            ),
            source(
                connectorId = "photos",
                hash = photoHash,
                kind = SourceKind.PHOTO,
                pointer = "content://media/external/images/media/1",
            ),
            source(
                connectorId = "calendar",
                hash = calendarHash,
                kind = SourceKind.CALENDAR,
                pointer = "content://com.android.calendar/events/1",
                appIdentifier = "android-calendar",
            ),
            source(
                connectorId = "notification",
                hash = notificationHash,
                kind = SourceKind.NOTIFICATION,
                appIdentifier = "com.example.pay",
                sensitivity = SensitivityLevel.VERY_HIGH,
            ),
            source(
                connectorId = "app_usage",
                hash = usageHash,
                kind = SourceKind.APP_USAGE,
                appIdentifier = "com.example.app",
                sensitivity = SensitivityLevel.VERY_HIGH,
                modifiedAt = observedAt.plusSeconds(30 * 60),
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
            event(
                connectorId = "location",
                hash = locationHash,
                kind = DerivedMemoryEventKind.PLACE_VISIT,
                summary = "Location sample indexed near Seoul at $observedAt.",
                keywords = listOf("location", "place", "gps", "Seoul"),
                labels = listOf("location", "place-visit", "gps", "Seoul"),
            ),
            event(
                connectorId = "photos",
                hash = photoHash,
                kind = DerivedMemoryEventKind.PHOTO_INDEX,
                summary = "Photo metadata indexed at $observedAt. Type: image/jpeg. Dimensions: 1920x1080.",
                keywords = listOf("photo", "jpeg", "landscape"),
                labels = listOf("photo", "jpeg", "landscape"),
            ),
            event(
                connectorId = "calendar",
                hash = calendarHash,
                kind = DerivedMemoryEventKind.CALENDAR_EVENT,
                summary = "Calendar event indexed: Team meeting, from $observedAt.",
                keywords = listOf("team", "meeting"),
                labels = listOf("calendar"),
                confidence = ConfidenceLevel.HIGH,
            ),
            event(
                connectorId = "notification",
                hash = notificationHash,
                kind = DerivedMemoryEventKind.PAYMENT,
                summary = "Notification-derived payment signal from com.example.pay at $observedAt.",
                keywords = listOf("example", "pay", "payment", "notification"),
                labels = listOf("notification", "payment"),
                entities = listOf("com.example.pay"),
                sensitivity = SensitivityLevel.VERY_HIGH,
            ),
            event(
                connectorId = "app_usage",
                hash = usageHash,
                kind = DerivedMemoryEventKind.APP_USAGE,
                summary = "App usage indexed: Example used for about 30 minute(s) between $observedAt and ${observedAt.plusSeconds(30 * 60)}.",
                keywords = listOf("example"),
                labels = listOf("app-usage", "medium-session"),
                entities = listOf("com.example.app"),
                sensitivity = SensitivityLevel.VERY_HIGH,
                endedAt = observedAt.plusSeconds(30 * 60),
            ),
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
            citation("location", locationHash, "Location sample: Seoul"),
            citation("photos", photoHash, "Photo metadata"),
            citation("calendar", calendarHash, "Calendar: Team meeting", ConfidenceLevel.HIGH),
            citation("notification", notificationHash, "Notification signal: com.example.pay"),
            citation("app_usage", usageHash, "App usage: Example"),
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
            id = "place-cluster:location:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            regionLabel = "Seoul",
            centroidLatitude = 37.5,
            centroidLongitude = 127.0,
            radiusMeters = 120.0,
            firstSeenAt = observedAt,
            lastSeenAt = observedAt,
            visitCount = 1,
            sourceReferenceIds = listOf("source:location:$locationHash"),
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
            dailySummaries = emptyList(),
            placeClusters = listOf(cluster),
            appUsageSummaries = emptyList(),
            connectorScanStatuses = statuses,
        )
    }

    fun legacyDailySummary(): DailyMemorySummary {
        return DailyMemorySummary(
            id = "daily:legacy-unowned",
            date = LocalDate.parse("2026-07-15"),
            summary = "Legacy daily summary without a schema-v8 producer.",
            confidence = ConfidenceLevel.MEDIUM,
        )
    }

    fun legacyAppUsageSummary(): AppUsageSummary {
        return AppUsageSummary(
            id = "app-usage:app_usage:legacy",
            sourceReferenceIds = listOf("source:app_usage:$usageHash"),
            date = LocalDate.parse("2026-07-15"),
            packageName = "com.example.app",
            appAlias = "Legacy aggregate",
            category = AppUsageCategory.OTHER,
            totalDurationMinutes = 30,
            launchCount = 1,
            confidence = ConfidenceLevel.MEDIUM,
        )
    }

    private fun source(
        connectorId: String,
        hash: String,
        kind: SourceKind,
        pointer: String? = null,
        appIdentifier: String? = null,
        sensitivity: SensitivityLevel = SensitivityLevel.HIGH,
        modifiedAt: Instant = observedAt,
    ): SourceReference {
        return SourceReference(
            id = "source:$connectorId:$hash",
            connectorId = connectorId,
            sourceKind = kind,
            localPointer = pointer,
            externalIdHash = hash,
            sourceAppIdentifier = appIdentifier,
            observedAt = observedAt,
            modifiedAt = modifiedAt,
            sensitivity = sensitivity,
        )
    }

    private fun event(
        connectorId: String,
        hash: String,
        kind: DerivedMemoryEventKind,
        summary: String,
        keywords: List<String>,
        labels: List<String>,
        entities: List<String> = emptyList(),
        confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
        sensitivity: SensitivityLevel = SensitivityLevel.HIGH,
        endedAt: Instant? = null,
    ): DerivedMemoryEvent {
        return DerivedMemoryEvent(
            id = "event:$connectorId:$hash",
            kind = kind,
            sourceReferenceIds = listOf("source:$connectorId:$hash"),
            summary = summary,
            startedAt = observedAt,
            endedAt = endedAt,
            keywords = keywords,
            labels = labels,
            entities = entities,
            confidence = confidence,
            sensitivity = sensitivity,
            citationIds = listOf("citation:$connectorId:$hash"),
            createdAt = observedAt,
        )
    }

    private fun citation(
        connectorId: String,
        hash: String,
        label: String,
        confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    ): MemoryCitation {
        return MemoryCitation(
            id = "citation:$connectorId:$hash",
            sourceReferenceId = "source:$connectorId:$hash",
            derivedMemoryEventId = "event:$connectorId:$hash",
            label = label,
            observedAt = observedAt,
            confidence = confidence,
        )
    }
}
