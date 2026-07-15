package ai.grayin.core.model

import java.time.Instant
import java.time.LocalDate

data class DerivedMemoryEvent(
    val id: String,
    val kind: DerivedMemoryEventKind,
    val sourceReferenceIds: List<String>,
    val summary: String,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val keywords: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val entities: List<String> = emptyList(),
    val confidence: ConfidenceLevel = ConfidenceLevel.UNKNOWN,
    val sensitivity: SensitivityLevel = SensitivityLevel.MEDIUM,
    val citationIds: List<String> = emptyList(),
    val createdAt: Instant,
)

/**
 * Reserved for transfer/schema compatibility. Schema v8 has no canonical producer and requires
 * this section to be empty on export and import.
 */
data class DailyMemorySummary(
    val id: String,
    val date: LocalDate,
    val summary: String,
    val derivedMemoryEventIds: List<String> = emptyList(),
    val placeClusterIds: List<String> = emptyList(),
    val appUsageSummaryIds: List<String> = emptyList(),
    val confidence: ConfidenceLevel,
    val missingSources: List<MissingSource> = emptyList(),
)

data class PlaceCluster(
    val id: String,
    val label: String? = null,
    val regionLabel: String? = null,
    val centroidLatitude: Double? = null,
    val centroidLongitude: Double? = null,
    val radiusMeters: Double? = null,
    val firstSeenAt: Instant? = null,
    val lastSeenAt: Instant? = null,
    val visitCount: Int = 0,
    val sourceReferenceIds: List<String> = emptyList(),
    val confidence: ConfidenceLevel = ConfidenceLevel.UNKNOWN,
)

data class PlaceVisit(
    val id: String,
    val placeClusterId: String? = null,
    val sourceReferenceIds: List<String>,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val durationMinutes: Long? = null,
    val regionLabel: String? = null,
    val confidence: ConfidenceLevel,
    val citationIds: List<String> = emptyList(),
)

data class PhotoMemoryIndex(
    val id: String,
    val sourceReferenceId: String,
    val takenAt: Instant? = null,
    val approximateLocationLabel: String? = null,
    val keywords: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val clusterId: String? = null,
    val derivedCaption: String? = null,
    val confidence: ConfidenceLevel = ConfidenceLevel.UNKNOWN,
    val citationIds: List<String> = emptyList(),
)

data class NotificationDerivedEvent(
    val id: String,
    val sourceReferenceId: String,
    val kind: NotificationDerivedEventKind,
    val sourcePackageName: String,
    val occurredAt: Instant,
    val derivedTitle: String,
    val derivedSummary: String? = null,
    val amountMinor: Long? = null,
    val currencyCode: String? = null,
    val entityAliases: List<String> = emptyList(),
    val confidence: ConfidenceLevel,
    val citationIds: List<String> = emptyList(),
)

/**
 * Reserved for transfer/schema compatibility. Schema v8 persists App Usage as canonical
 * per-session events and requires this aggregate section to be empty on scans, export, and import.
 */
data class AppUsageSummary(
    val id: String,
    val sourceReferenceIds: List<String>,
    val date: LocalDate,
    val packageName: String,
    val appAlias: String? = null,
    val category: AppUsageCategory = AppUsageCategory.OTHER,
    val totalDurationMinutes: Long,
    val launchCount: Int? = null,
    val activeTimeBucketLabels: List<String> = emptyList(),
    val confidence: ConfidenceLevel = ConfidenceLevel.UNKNOWN,
)
