package ai.grayin.core.indexing

import ai.grayin.core.connector.ConnectorIndexingMode
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.connector.MemoryConnector
import ai.grayin.core.connector.MemoryConnectorRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

sealed interface ConnectorScanCommitResult {
    data class Committed(val item: IndexingQueueItem) : ConnectorScanCommitResult

    data object LeaseLost : ConnectorScanCommitResult
}

fun interface ConnectorScanWriter {
    /** Atomically fences the lease, stores the derived scan, and completes the queue item. */
    suspend fun commitClaimedConnectorScan(
        scanResult: ConnectorScanResult,
        itemId: String,
        leaseOwner: String,
        attemptCount: Int,
    ): ConnectorScanCommitResult
}

class IndexingCommandExecutor(
    private val connectorRegistry: MemoryConnectorRegistry,
    private val queue: IndexingQueue,
    private val scanWriter: ConnectorScanWriter,
    private val clock: Clock = Clock.systemUTC(),
    private val taskIdFactory: (connectorId: String) -> String = { connectorId ->
        "indexing:$connectorId:${UUID.randomUUID()}"
    },
    private val leaseDuration: Duration = DEFAULT_LEASE_DURATION,
    private val connectorOperationTimeout: Duration = leaseDuration.minus(DEFAULT_TIMEOUT_MARGIN),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(!leaseDuration.isZero && !leaseDuration.isNegative) {
            "Lease duration must be positive."
        }
        require(!connectorOperationTimeout.isZero && !connectorOperationTimeout.isNegative) {
            "Connector operation timeout must be positive."
        }
        require(connectorOperationTimeout < leaseDuration) {
            "Connector operation timeout must be shorter than the queue lease."
        }
        require(maxAttempts > 0) { "Maximum attempts must be positive." }
    }

    suspend fun enqueue(
        command: IndexingCommand,
        trigger: IndexingTrigger,
        automaticWindowKey: String? = null,
        automaticGeneration: Long? = null,
    ): List<IndexingQueueItem> {
        requireValidAutomaticGeneration(trigger, automaticGeneration)
        require(
            (trigger == IndexingTrigger.AUTOMATIC && !automaticWindowKey.isNullOrBlank()) ||
                (trigger == IndexingTrigger.MANUAL && automaticWindowKey == null),
        ) {
            "Automatic tasks require a window key and manual tasks must not have one."
        }

        val connectorIds = connectorIdsFor(command, trigger)
        val requestedAt = clock.instant()
        val (from, until) = when (command) {
            is IndexDateRange -> command.from to command.until
            else -> null to null
        }
        val tasks = connectorIds.map { connectorId ->
            IndexingTask(
                id = taskIdFactory(connectorId),
                connectorId = connectorId,
                trigger = trigger,
                requestedAt = requestedAt,
                from = from,
                until = until,
                forceRefresh = trigger == IndexingTrigger.MANUAL,
                automaticWindowKey = automaticWindowKey,
                automaticGeneration = automaticGeneration,
            )
        }
        return queue.enqueue(tasks)
    }

    suspend fun executeNext(
        trigger: IndexingTrigger,
        leaseOwner: String,
        automaticGeneration: Long? = null,
    ): IndexingQueueItem? {
        require(leaseOwner.isNotBlank()) { "Lease owner must not be blank." }
        requireValidAutomaticGeneration(trigger, automaticGeneration)
        val claimedAt = clock.instant()
        queue.recoverExpiredLeases(now = claimedAt, maxAttempts = maxAttempts)
        val item = queue.claimNextAtomically(
            leaseOwner = leaseOwner,
            claimedAt = claimedAt,
            leaseUntil = claimedAt.plus(leaseDuration),
            trigger = trigger,
            automaticGeneration = automaticGeneration,
        ) ?: return null

        val connector = connectorRegistry.find(item.connectorId)
            ?: return fail(item, IndexingFailureCode.CONNECTOR_NOT_FOUND)
        if (!supportsTrigger(connector, trigger)) {
            return skip(item, IndexingSkipReason.NOT_BACKGROUND_ELIGIBLE)
        }

        val ready = try {
            withTimeout(connectorOperationTimeout.toMillis()) {
                connectorReadiness(connector, item)
            }
        } catch (_: TimeoutCancellationException) {
            return fail(item, IndexingFailureCode.CONNECTOR_OPERATION_TIMED_OUT)
        }
        currentCoroutineContext().ensureActive()
        if (ready != null) return ready

        val scanResult = try {
            withTimeout(connectorOperationTimeout.toMillis()) {
                connector.scan(
                    ConnectorScanScope(
                        from = item.task.from,
                        until = item.task.until,
                        forceRefresh = item.task.forceRefresh,
                    ),
                )
            }
        } catch (_: TimeoutCancellationException) {
            return fail(item, IndexingFailureCode.CONNECTOR_OPERATION_TIMED_OUT)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return fail(item, IndexingFailureCode.CONNECTOR_SCAN_FAILED)
        }
        currentCoroutineContext().ensureActive()

        if (scanResult.connectorId != item.connectorId) {
            return fail(item, IndexingFailureCode.CONNECTOR_SCAN_FAILED)
        }

        if (!scanResult.hasIndexableOutput()) {
            return skip(item, IndexingSkipReason.NO_INDEXABLE_DATA)
        }
        currentCoroutineContext().ensureActive()

        val commitResult = try {
            scanWriter.commitClaimedConnectorScan(
                scanResult = scanResult,
                itemId = item.id,
                leaseOwner = checkNotNull(item.leaseOwner),
                attemptCount = item.attemptCount,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return fail(item, IndexingFailureCode.STORE_WRITE_FAILED)
        }
        val completed = when (commitResult) {
            is ConnectorScanCommitResult.Committed -> commitResult.item
            ConnectorScanCommitResult.LeaseLost -> return null
        }
        try {
            connector.onScanStored(scanResult)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The encrypted derived-memory commit and durable queue completion are authoritative.
            // Connector UI checkpoints are best-effort and never make committed data look failed.
        }
        return completed
    }

    suspend fun drain(
        trigger: IndexingTrigger,
        leaseOwner: String,
        maxTasks: Int = DEFAULT_MAX_DRAIN_TASKS,
        automaticGeneration: Long? = null,
    ): List<IndexingQueueItem> {
        require(maxTasks > 0) { "Maximum drain task count must be positive." }
        requireValidAutomaticGeneration(trigger, automaticGeneration)
        return buildList {
            repeat(maxTasks) {
                val item = executeNext(
                    trigger = trigger,
                    leaseOwner = leaseOwner,
                    automaticGeneration = automaticGeneration,
                ) ?: return@buildList
                add(item)
            }
        }
    }

    private fun requireValidAutomaticGeneration(
        trigger: IndexingTrigger,
        automaticGeneration: Long?,
    ) {
        when (trigger) {
            IndexingTrigger.MANUAL -> require(automaticGeneration == null) {
                "Manual indexing must not have an automatic generation."
            }

            IndexingTrigger.AUTOMATIC -> require(
                automaticGeneration != null && automaticGeneration >= 0L,
            ) {
                "Automatic indexing requires a non-negative generation."
            }
        }
    }

    private fun connectorIdsFor(
        command: IndexingCommand,
        trigger: IndexingTrigger,
    ): List<String> {
        return when (command) {
            IndexNow -> connectorRegistry.all
                .filter { connector -> supportsTrigger(connector, trigger) }
                .map { connector -> connector.metadata.connectorId }

            is IndexConnector -> listOf(command.connectorId)
            is IndexDateRange -> command.connectorId?.let(::listOf)
                ?: connectorRegistry.all
                    .filter { connector -> supportsTrigger(connector, trigger) }
                    .map { connector -> connector.metadata.connectorId }
        }
    }

    private fun supportsTrigger(
        connector: MemoryConnector,
        trigger: IndexingTrigger,
    ): Boolean {
        return when (trigger) {
            IndexingTrigger.MANUAL -> connector.metadata.indexingMode != ConnectorIndexingMode.EVENT_DRIVEN
            IndexingTrigger.AUTOMATIC -> {
                connector.metadata.indexingMode == ConnectorIndexingMode.BACKGROUND_SCANNABLE
            }
        }
    }

    private suspend fun connectorReadiness(
        connector: MemoryConnector,
        item: IndexingQueueItem,
    ): IndexingQueueItem? {
        val state = try {
            connector.currentState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return fail(item, IndexingFailureCode.CONNECTOR_SCAN_FAILED)
        }
        val permissionState = try {
            connector.permissionState()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return fail(item, IndexingFailureCode.CONNECTOR_SCAN_FAILED)
        }
        return when {
            !permissionState.permissionGranted -> skip(item, IndexingSkipReason.MISSING_PERMISSION)
            !state.enabled -> skip(item, IndexingSkipReason.SOURCE_DISABLED)
            else -> null
        }
    }

    private suspend fun skip(
        item: IndexingQueueItem,
        reason: IndexingSkipReason,
    ): IndexingQueueItem {
        currentCoroutineContext().ensureActive()
        return queue.skip(
            itemId = item.id,
            leaseOwner = checkNotNull(item.leaseOwner),
            attemptCount = item.attemptCount,
            completedAt = completionTime(item),
            reason = reason,
        )
    }

    private suspend fun fail(
        item: IndexingQueueItem,
        code: IndexingFailureCode,
    ): IndexingQueueItem {
        currentCoroutineContext().ensureActive()
        return queue.fail(
            itemId = item.id,
            leaseOwner = checkNotNull(item.leaseOwner),
            attemptCount = item.attemptCount,
            completedAt = completionTime(item),
            code = code,
        )
    }

    private fun completionTime(item: IndexingQueueItem): Instant {
        val now = clock.instant()
        val startedAt = checkNotNull(item.startedAt)
        return if (now.isBefore(startedAt)) startedAt else now
    }

    private fun ConnectorScanResult.hasIndexableOutput(): Boolean {
        return sourceReferences.isNotEmpty() ||
            derivedEvents.isNotEmpty() ||
            citations.isNotEmpty() ||
            placeClusters.isNotEmpty() ||
            appUsageSummaries.isNotEmpty()
    }

    private companion object {
        val DEFAULT_LEASE_DURATION: Duration = Duration.ofMinutes(15)
        val DEFAULT_TIMEOUT_MARGIN: Duration = Duration.ofMinutes(1)
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_MAX_DRAIN_TASKS = 32
    }
}
