package ai.grayin.core.store

import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.DailyMemorySummary
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.SourceReference

interface LocalMemoryStore {
    suspend fun saveSourceReferences(sourceReferences: List<SourceReference>): StoreWriteResult

    suspend fun saveDerivedMemoryEvents(events: List<DerivedMemoryEvent>): StoreWriteResult

    suspend fun saveCitations(citations: List<MemoryCitation>): StoreWriteResult

    suspend fun saveDailySummaries(summaries: List<DailyMemorySummary>): StoreWriteResult

    suspend fun savePlaceClusters(clusters: List<PlaceCluster>): StoreWriteResult

    suspend fun saveAppUsageSummaries(summaries: List<AppUsageSummary>): StoreWriteResult

    suspend fun deleteConnectorData(connectorId: String): StoreDeleteResult

    suspend fun invalidateIndexesAfterDelete(request: IndexInvalidationRequest): IndexInvalidationResult
}

