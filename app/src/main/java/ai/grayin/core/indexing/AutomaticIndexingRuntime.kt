package ai.grayin.core.indexing

import java.time.Instant

enum class AutomaticIndexingOutcome {
    RUNNING,
    COMPLETED,
    SKIPPED,
    FAILED,
}

data class AutomaticIndexingRuntimeStatus(
    val lastCheckedAt: Instant? = null,
    val lastStartedAt: Instant? = null,
    val lastCompletedAt: Instant? = null,
    val lastOutcome: AutomaticIndexingOutcome? = null,
    val lastSkipReason: IndexingSkipReason? = null,
    val lastFailureCode: IndexingFailureCode? = null,
    val lastIndexedEventCount: Int = 0,
) {
    init {
        require(lastIndexedEventCount >= 0) { "Indexed event count must not be negative." }
        require(lastOutcome == AutomaticIndexingOutcome.SKIPPED || lastSkipReason == null) {
            "Only a skipped automatic run may have a skip reason."
        }
        require(lastOutcome == AutomaticIndexingOutcome.FAILED || lastFailureCode == null) {
            "Only a failed automatic run may have a failure code."
        }
    }
}

interface AutomaticIndexingRuntimeStore {
    suspend fun loadAutomaticIndexingRuntime(): AutomaticIndexingRuntimeStatus

    suspend fun saveAutomaticIndexingRuntime(status: AutomaticIndexingRuntimeStatus)
}
