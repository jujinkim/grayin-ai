package ai.grayin.core.store

import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.DailyMemorySummary
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.SourceReference

interface LocalMemoryStore {
    /** Persists one connector scan as a single validated transaction. */
    suspend fun saveConnectorScan(scanResult: ConnectorScanResult): StoreWriteResult

    suspend fun saveDailySummaries(summaries: List<DailyMemorySummary>): StoreWriteResult

    suspend fun savePlaceClusters(clusters: List<PlaceCluster>): StoreWriteResult

    suspend fun saveAppUsageSummaries(summaries: List<AppUsageSummary>): StoreWriteResult

    suspend fun deleteConnectorData(connectorId: String): StoreDeleteResult

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
}
