package ai.grayin.core.indexing

import java.time.Instant

enum class AutomaticIndexingOutcome {
    RUNNING,
    COMPLETED,
    SKIPPED,
    FAILED,
}

data class AutomaticIndexingControl(
    val enabled: Boolean,
    val generation: Long,
    val settingsKey: String,
) {
    init {
        require(generation >= 0L) { "Automatic indexing generation must not be negative." }
        require(settingsKey.isNotBlank()) { "Automatic indexing settings key must not be blank." }
    }
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
    suspend fun loadAutomaticIndexingControl(): AutomaticIndexingControl

    suspend fun synchronizeAutomaticIndexingControl(
        enabled: Boolean,
        settingsKey: String,
        changedAt: Instant,
    ): AutomaticIndexingControl

    suspend fun loadAutomaticIndexingRuntime(): AutomaticIndexingRuntimeStatus

    /** Returns false when the expected control generation is no longer current. */
    suspend fun saveAutomaticIndexingRuntime(
        status: AutomaticIndexingRuntimeStatus,
        expectedGeneration: Long,
    ): Boolean
}
