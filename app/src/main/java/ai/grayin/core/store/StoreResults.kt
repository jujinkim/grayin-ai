package ai.grayin.core.store

import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.DailyMemorySummary
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.SourceReference
import java.time.Instant

data class StoreWriteResult(
    val insertedCount: Int,
    val updatedCount: Int,
    val skippedCount: Int = 0,
    val completedAt: Instant,
)

data class StoreDeleteResult(
    val connectorId: String,
    val deletedSourceReferenceIds: List<String> = emptyList(),
    val deletedDerivedMemoryEventIds: List<String> = emptyList(),
    val deletedCitationIds: List<String> = emptyList(),
    val deletedSummaryIds: List<String> = emptyList(),
    val deletedPlaceClusterIds: List<String> = emptyList(),
    val deletedAppUsageSummaryIds: List<String> = emptyList(),
    val completedAt: Instant,
)

data class LocalMemorySnapshot(
    val sourceReferences: List<SourceReference>,
    val derivedMemoryEvents: List<DerivedMemoryEvent>,
    val citations: List<MemoryCitation>,
    val dailySummaries: List<DailyMemorySummary>,
    val placeClusters: List<PlaceCluster>,
    val appUsageSummaries: List<AppUsageSummary>,
    val connectorScanStatuses: List<ConnectorScanStatus>,
)

data class StoreImportResult(
    val sourceReferenceCount: Int,
    val derivedMemoryEventCount: Int,
    val citationCount: Int,
    val dailySummaryCount: Int,
    val placeClusterCount: Int,
    val appUsageSummaryCount: Int,
    val connectorScanStatusCount: Int,
    val connectorsRequiringReconsent: Set<String>,
    val completedAt: Instant,
)

data class IndexInvalidationRequest(
    val connectorId: String,
    val sourceReferenceIds: List<String> = emptyList(),
    val derivedMemoryEventIds: List<String> = emptyList(),
)

data class IndexInvalidationResult(
    val connectorId: String,
    val invalidatedIndexIds: List<String> = emptyList(),
    val completedAt: Instant,
)
