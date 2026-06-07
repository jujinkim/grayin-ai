package ai.grayin.core.indexing

import ai.grayin.core.model.ProcessingState
import java.time.Instant

data class IndexingQueueItem(
    val id: String,
    val command: IndexingCommand,
    val state: ProcessingState,
    val requestedAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val failureCode: String? = null,
    val failureExplanation: String? = null,
)

interface IndexingQueue {
    suspend fun enqueue(command: IndexingCommand): IndexingQueueItem

    suspend fun markState(
        itemId: String,
        state: ProcessingState,
        failureCode: String? = null,
        failureExplanation: String? = null,
    ): IndexingQueueItem

    suspend fun status(): IndexingStatus
}

