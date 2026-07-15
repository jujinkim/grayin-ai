package ai.grayin.core.store

import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.DailyMemorySummary
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.SourceReference
import java.time.Instant

interface ConnectorReconsentGate {
    suspend fun isConnectorReconsentRequired(connectorId: String): Boolean

    /** Clears one imported-data barrier after an explicit connector consent action. */
    suspend fun markConnectorReconsented(connectorId: String): Boolean
}

interface LocalMemoryStore : ConnectorReconsentGate {
    /** Persists one connector scan as a single validated transaction. */
    suspend fun saveConnectorScan(scanResult: ConnectorScanResult): StoreWriteResult

    suspend fun deleteConnectorData(
        connectorId: String,
        requireReconsent: Boolean = false,
    ): StoreDeleteResult

    suspend fun invalidateIndexesAfterDelete(request: IndexInvalidationRequest): IndexInvalidationResult

    suspend fun loadSourceReferences(connectorId: String? = null): List<SourceReference>

    suspend fun loadDerivedMemoryEvents(): List<DerivedMemoryEvent>

    suspend fun loadCitations(): List<MemoryCitation>

    suspend fun loadDailySummaries(): List<DailyMemorySummary>

    suspend fun loadPlaceClusters(): List<PlaceCluster>

    suspend fun loadAppUsageSummaries(): List<AppUsageSummary>

    suspend fun loadConnectorScanStatuses(): List<ConnectorScanStatus>

    /** Reads every derived-memory section from one consistent database snapshot. */
    suspend fun loadSnapshot(): LocalMemorySnapshot

    /**
     * Replaces all seven derived-memory sections in one transaction after validating the
     * detached import snapshot. Runtime queue/control state is not imported.
     */
    suspend fun replaceDerivedDataFromImport(
        snapshot: LocalMemorySnapshot,
        trustedConnectorIds: Set<String>,
        importedAt: Instant,
    ): StoreImportResult
}
