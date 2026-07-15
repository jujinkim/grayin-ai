package ai.grayin.app

import ai.grayin.core.indexing.AutomaticIndexingOutcome
import ai.grayin.core.indexing.AutomaticIndexingRuntimeStatus
import ai.grayin.core.indexing.IndexingFailureCode
import ai.grayin.core.indexing.IndexingQueueSnapshot
import ai.grayin.core.indexing.IndexingQueueState
import ai.grayin.core.indexing.IndexingSkipReason
import ai.grayin.core.indexing.IndexingTrigger
import java.time.Instant

data class RecentIndexingTaskUiState(
    val sourceName: String,
    val trigger: IndexingTrigger,
    val state: RecentIndexingTaskState,
    val completedAt: Instant?,
    val skipReason: IndexingSkipReason?,
    val failureCode: IndexingFailureCode?,
    val indexedEventCount: Int,
)

enum class RecentIndexingTaskState {
    PENDING,
    RUNNING,
    RECOVERY_PENDING,
    COMPLETED,
    SKIPPED,
    FAILED,
}

data class IndexingStatusUiState(
    val queueDepth: Int,
    val runningSourceNames: List<String>,
    val lastQueueCompletionAt: Instant?,
    val lastAutomaticCheckedAt: Instant?,
    val lastAutomaticCompletedAt: Instant?,
    val lastAutomaticOutcome: AutomaticIndexingOutcome?,
    val lastAutomaticSkipReason: IndexingSkipReason?,
    val lastAutomaticFailureCode: IndexingFailureCode?,
    val lastAutomaticIndexedEventCount: Int,
    val recentTasks: List<RecentIndexingTaskUiState>,
) {
    companion object {
        fun empty(): IndexingStatusUiState {
            return IndexingStatusUiState(
                queueDepth = 0,
                runningSourceNames = emptyList(),
                lastQueueCompletionAt = null,
                lastAutomaticCheckedAt = null,
                lastAutomaticCompletedAt = null,
                lastAutomaticOutcome = null,
                lastAutomaticSkipReason = null,
                lastAutomaticFailureCode = null,
                lastAutomaticIndexedEventCount = 0,
                recentTasks = emptyList(),
            )
        }
    }
}

object IndexingStatusUiMapper {
    fun map(
        queue: IndexingQueueSnapshot,
        runtime: AutomaticIndexingRuntimeStatus,
        sourceName: (connectorId: String) -> String,
        recentLimit: Int = 5,
    ): IndexingStatusUiState {
        require(recentLimit >= 0) { "Recent indexing limit must not be negative." }
        return IndexingStatusUiState(
            queueDepth = queue.queueDepth,
            runningSourceNames = queue.runningConnectorIds.map(sourceName).sorted(),
            lastQueueCompletionAt = queue.lastCompletedAt,
            lastAutomaticCheckedAt = runtime.lastCheckedAt,
            lastAutomaticCompletedAt = runtime.lastCompletedAt,
            lastAutomaticOutcome = runtime.lastOutcome,
            lastAutomaticSkipReason = runtime.lastSkipReason,
            lastAutomaticFailureCode = runtime.lastFailureCode,
            lastAutomaticIndexedEventCount = runtime.lastIndexedEventCount,
            recentTasks = queue.items.take(recentLimit).map { item ->
                RecentIndexingTaskUiState(
                    sourceName = sourceName(item.connectorId),
                    trigger = item.task.trigger,
                    state = item.presentationState(queue.observedAt),
                    completedAt = item.completedAt,
                    skipReason = item.skipReason,
                    failureCode = item.failureCode,
                    indexedEventCount = item.indexedEventCount,
                )
            },
        )
    }

    private fun ai.grayin.core.indexing.IndexingQueueItem.presentationState(
        observedAt: Instant,
    ): RecentIndexingTaskState {
        return when (state) {
            IndexingQueueState.PENDING -> RecentIndexingTaskState.PENDING
            IndexingQueueState.RUNNING -> if (leaseUntil?.isAfter(observedAt) == true) {
                RecentIndexingTaskState.RUNNING
            } else {
                RecentIndexingTaskState.RECOVERY_PENDING
            }
            IndexingQueueState.COMPLETED -> RecentIndexingTaskState.COMPLETED
            IndexingQueueState.SKIPPED -> RecentIndexingTaskState.SKIPPED
            IndexingQueueState.FAILED -> RecentIndexingTaskState.FAILED
        }
    }
}
