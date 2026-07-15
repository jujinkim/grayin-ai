package ai.grayin.core.indexing

import java.time.Instant

enum class IndexingTrigger {
    MANUAL,
    AUTOMATIC,
}

enum class IndexingQueueState {
    PENDING,
    RUNNING,
    COMPLETED,
    SKIPPED,
    FAILED,
}

enum class IndexingSkipReason {
    AUTOMATIC_INDEXING_DISABLED,
    INVALID_LOW_USAGE_WINDOW,
    OUTSIDE_LOW_USAGE_WINDOW,
    NOT_CHARGING,
    BATTERY_BELOW_MINIMUM,
    BATTERY_LEVEL_UNKNOWN,
    THERMAL_STATE_HOT,
    THERMAL_STATE_CRITICAL,
    SOURCE_DISABLED,
    MISSING_PERMISSION,
    NOT_BACKGROUND_ELIGIBLE,
    NO_INDEXABLE_DATA,
}

enum class IndexingFailureCode {
    CONNECTOR_NOT_FOUND,
    CONNECTOR_SCAN_FAILED,
    STORE_WRITE_FAILED,
    LEASE_EXPIRED,
    ATTEMPT_LIMIT_REACHED,
    INTERNAL_ERROR,
}

data class IndexingTask(
    val id: String,
    val connectorId: String,
    val trigger: IndexingTrigger,
    val requestedAt: Instant,
    val from: Instant? = null,
    val until: Instant? = null,
    val forceRefresh: Boolean = false,
    val automaticWindowKey: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Task id must not be blank." }
        require(connectorId.isNotBlank()) { "Connector id must not be blank." }
        IndexingQueueValidator.requireValidDateRange(from, until)
        when (trigger) {
            IndexingTrigger.MANUAL -> require(automaticWindowKey == null) {
                "Manual tasks must not have an automatic window key."
            }

            IndexingTrigger.AUTOMATIC -> require(!automaticWindowKey.isNullOrBlank()) {
                "Automatic tasks require a non-blank automatic window key."
            }
        }
    }
}

data class IndexingQueueItem(
    val task: IndexingTask,
    val state: IndexingQueueState = IndexingQueueState.PENDING,
    val attemptCount: Int = 0,
    val indexedEventCount: Int = 0,
    val lastAttemptAt: Instant? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val leaseOwner: String? = null,
    val leaseUntil: Instant? = null,
    val skipReason: IndexingSkipReason? = null,
    val failureCode: IndexingFailureCode? = null,
) {
    val id: String
        get() = task.id

    val connectorId: String
        get() = task.connectorId

    init {
        require(attemptCount >= 0) { "Attempt count must not be negative." }
        require(indexedEventCount >= 0) { "Indexed event count must not be negative." }
        require((leaseOwner == null) == (leaseUntil == null)) {
            "Lease owner and expiry must be set together."
        }
        require(leaseOwner == null || leaseOwner.isNotBlank()) { "Lease owner must not be blank." }
        require((attemptCount == 0) == (lastAttemptAt == null)) {
            "Last attempt timestamp must match whether an attempt exists."
        }
        require(startedAt == null || !startedAt.isBefore(task.requestedAt)) {
            "Start timestamp must not precede the request."
        }
        require(lastAttemptAt == null || !lastAttemptAt.isBefore(task.requestedAt)) {
            "Last attempt timestamp must not precede the request."
        }
        require(completedAt == null || !completedAt.isBefore(task.requestedAt)) {
            "Completion timestamp must not precede the request."
        }
        require(startedAt == null || completedAt == null || !completedAt.isBefore(startedAt)) {
            "Completion timestamp must not precede the start."
        }

        when (state) {
            IndexingQueueState.PENDING -> {
                require(startedAt == null && completedAt == null && leaseOwner == null)
                require(skipReason == null && failureCode == null && indexedEventCount == 0)
            }

            IndexingQueueState.RUNNING -> {
                require(attemptCount > 0 && startedAt != null && completedAt == null)
                require(leaseOwner != null && leaseUntil!!.isAfter(startedAt))
                require(skipReason == null && failureCode == null && indexedEventCount == 0)
            }

            IndexingQueueState.COMPLETED -> {
                require(attemptCount > 0 && startedAt != null && completedAt != null)
                require(leaseOwner == null && skipReason == null && failureCode == null)
            }

            IndexingQueueState.SKIPPED -> {
                require(completedAt != null && leaseOwner == null)
                require(skipReason != null && failureCode == null && indexedEventCount == 0)
            }

            IndexingQueueState.FAILED -> {
                require(completedAt != null && leaseOwner == null)
                require(skipReason == null && failureCode != null && indexedEventCount == 0)
            }
        }
    }
}

data class LeaseRecoveryResult(
    val requeuedCount: Int,
    val failedCount: Int,
) {
    init {
        require(requeuedCount >= 0) { "Requeued count must not be negative." }
        require(failedCount >= 0) { "Failed count must not be negative." }
    }
}

data class IndexingQueueSnapshot(
    val items: List<IndexingQueueItem>,
    val queueDepth: Int,
    val runningConnectorIds: Set<String>,
    val lastCompletedAt: Instant?,
) {
    init {
        require(queueDepth >= 0) { "Queue depth must not be negative." }
        require(runningConnectorIds.none(String::isBlank)) {
            "Running connector ids must not be blank."
        }
    }
}

object IndexingQueueValidator {
    fun isValidDateRange(from: Instant?, until: Instant?): Boolean {
        if (from == null && until == null) return true
        if (from == null || until == null) return false
        return from.isBefore(until)
    }

    fun requireValidDateRange(from: Instant?, until: Instant?) {
        require(isValidDateRange(from, until)) {
            "Date range must be absent or have a start strictly before its end."
        }
    }

    fun canTransition(
        from: IndexingQueueState,
        to: IndexingQueueState,
    ): Boolean {
        return when (from) {
            IndexingQueueState.PENDING -> to in setOf(
                IndexingQueueState.RUNNING,
            )

            IndexingQueueState.RUNNING -> to in setOf(
                IndexingQueueState.PENDING,
                IndexingQueueState.COMPLETED,
                IndexingQueueState.SKIPPED,
                IndexingQueueState.FAILED,
            )

            IndexingQueueState.COMPLETED,
            IndexingQueueState.SKIPPED,
            IndexingQueueState.FAILED,
            -> false
        }
    }

    fun requireValidTransition(
        from: IndexingQueueState,
        to: IndexingQueueState,
    ) {
        require(canTransition(from, to)) { "Invalid indexing state transition: $from -> $to." }
    }
}

interface IndexingQueue {
    suspend fun enqueue(tasks: List<IndexingTask>): List<IndexingQueueItem>

    /**
     * Atomically claims the oldest eligible pending task, increments its attempt count, and
     * installs the supplied lease before returning it.
     */
    suspend fun claimNextAtomically(
        leaseOwner: String,
        claimedAt: Instant,
        leaseUntil: Instant,
    ): IndexingQueueItem?

    suspend fun complete(
        itemId: String,
        leaseOwner: String,
        attemptCount: Int,
        completedAt: Instant,
        indexedEventCount: Int,
    ): IndexingQueueItem

    suspend fun skip(
        itemId: String,
        leaseOwner: String,
        attemptCount: Int,
        completedAt: Instant,
        reason: IndexingSkipReason,
    ): IndexingQueueItem

    suspend fun fail(
        itemId: String,
        leaseOwner: String,
        attemptCount: Int,
        completedAt: Instant,
        code: IndexingFailureCode,
    ): IndexingQueueItem

    /** Requeues expired leases below the limit and fails those that exhausted it. */
    suspend fun recoverExpiredLeases(
        now: Instant,
        maxAttempts: Int,
    ): LeaseRecoveryResult

    suspend fun snapshot(limit: Int = 100): IndexingQueueSnapshot

    suspend fun pruneTerminalItems(
        completedBefore: Instant,
        keepLatest: Int = 100,
    ): Int
}
