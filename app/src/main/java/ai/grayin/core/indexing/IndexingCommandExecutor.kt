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
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(!leaseDuration.isZero && !leaseDuration.isNegative) {
            "Lease duration must be positive."
        }
        require(maxAttempts > 0) { "Maximum attempts must be positive." }
    }

    suspend fun enqueue(
        command: IndexingCommand,
        trigger: IndexingTrigger,
        automaticWindowKey: String? = null,
    ): List<IndexingQueueItem> {
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
            )
        }
        return queue.enqueue(tasks)
    }

    suspend fun executeNext(
        trigger: IndexingTrigger,
        leaseOwner: String,
    ): IndexingQueueItem? {
        require(leaseOwner.isNotBlank()) { "Lease owner must not be blank." }
        val claimedAt = clock.instant()
        queue.recoverExpiredLeases(now = claimedAt, maxAttempts = maxAttempts)
        val item = queue.claimNextAtomically(
            leaseOwner = leaseOwner,
            claimedAt = claimedAt,
            leaseUntil = claimedAt.plus(leaseDuration),
            trigger = trigger,
        ) ?: return null

        val connector = connectorRegistry.find(item.connectorId)
            ?: return fail(item, IndexingFailureCode.CONNECTOR_NOT_FOUND)
        if (!supportsTrigger(connector, trigger)) {
            return skip(item, IndexingSkipReason.NOT_BACKGROUND_ELIGIBLE)
        }

        val ready = connectorReadiness(connector, item)
        if (ready != null) return ready

        val scanResult = try {
            connector.scan(
                ConnectorScanScope(
                    from = item.task.from,
                    until = item.task.until,
                    forceRefresh = item.task.forceRefresh,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return fail(item, IndexingFailureCode.CONNECTOR_SCAN_FAILED)
        }

        if (scanResult.connectorId != item.connectorId) {
            return fail(item, IndexingFailureCode.CONNECTOR_SCAN_FAILED)
        }

        if (!scanResult.hasIndexableOutput()) {
            return skip(item, IndexingSkipReason.NO_INDEXABLE_DATA)
        }

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
    ): List<IndexingQueueItem> {
        require(maxTasks > 0) { "Maximum drain task count must be positive." }
        return buildList {
            repeat(maxTasks) {
                val item = executeNext(trigger = trigger, leaseOwner = leaseOwner) ?: return@buildList
                add(item)
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
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_MAX_DRAIN_TASKS = 32
    }
}
