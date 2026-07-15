package ai.grayin.core.connector

import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SourceReference
import java.time.Instant

data class ConnectorScanResult(
    val connectorId: String,
    val processingState: ProcessingState,
    val sourceReferences: List<SourceReference> = emptyList(),
    val derivedEvents: List<DerivedMemoryEvent> = emptyList(),
    val citations: List<MemoryCitation> = emptyList(),
    val placeClusters: List<PlaceCluster> = emptyList(),
    val appUsageSummaries: List<AppUsageSummary> = emptyList(),
    val missingSources: List<MissingSource> = emptyList(),
    val scannedAt: Instant,
)

data class ConnectorRevokeResult(
    val connectorId: String,
    val revokedAt: Instant,
    val permissionState: ConnectorPermissionState,
    val deleteRequest: ConnectorDeleteRequest,
)

data class ConnectorDeleteResult(
    val connectorId: String,
    val deletedSourceReferenceIds: List<String> = emptyList(),
    val deletedDerivedMemoryEventIds: List<String> = emptyList(),
    val invalidatedIndexIds: List<String> = emptyList(),
    val completedAt: Instant,
)
