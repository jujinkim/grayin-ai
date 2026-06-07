package ai.grayin.core.store

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

