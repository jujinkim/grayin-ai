package ai.grayin.core.indexing

import ai.grayin.core.connector.ConnectorDeleteRequest
import ai.grayin.core.connector.ConnectorDeleteResult
import ai.grayin.core.connector.ConnectorIndexingMode
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.connector.ConnectorPermissionState
import ai.grayin.core.connector.ConnectorRevokeResult
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.connector.MemoryConnector
import ai.grayin.core.connector.MemoryConnectorRegistry
import ai.grayin.core.model.ConnectorState
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class IndexingCommandExecutorTest {
    @Test
    fun `index now expands only modes eligible for its trigger`() = runBlocking {
        val background = FakeConnector("background", ConnectorIndexingMode.BACKGROUND_SCANNABLE)
        val foreground = FakeConnector("foreground", ConnectorIndexingMode.FOREGROUND_ONLY)
        val eventDriven = FakeConnector("event", ConnectorIndexingMode.EVENT_DRIVEN)
        val queue = RecordingQueue()
        var nextId = 0
        val executor = executor(
            connectors = listOf(background, foreground, eventDriven),
            queue = queue,
            taskIdFactory = { connectorId -> "task:${nextId++}:$connectorId" },
        )

        val manual = executor.enqueue(IndexNow, IndexingTrigger.MANUAL)
        val automatic = executor.enqueue(
            command = IndexNow,
            trigger = IndexingTrigger.AUTOMATIC,
            automaticWindowKey = "2026-07-15:02-05",
            automaticGeneration = 7L,
        )

        assertEquals(listOf("background", "foreground"), manual.map { it.connectorId })
        assertEquals(listOf("background"), automatic.map { it.connectorId })
        assertEquals(listOf(true, true), manual.map { it.task.forceRefresh })
        assertFalse(automatic.single().task.forceRefresh)
        assertEquals(7L, automatic.single().task.automaticGeneration)
    }

    @Test
    fun `successful execution preserves range and commits in order`() = runBlocking {
        val operations = mutableListOf<String>()
        val connector = FakeConnector(
            id = "calendar",
            mode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            operations = operations,
            scanResult = successfulScan("calendar"),
        )
        val queue = RecordingQueue(operations)
        val from = NOW.minusSeconds(3_600)
        val until = NOW.plusSeconds(3_600)
        var written: ConnectorScanResult? = null
        val executor = executor(
            connectors = listOf(connector),
            queue = queue,
            writer = ConnectorScanWriter { result, itemId, leaseOwner, attemptCount ->
                operations += "write"
                written = result
                ConnectorScanCommitResult.Committed(
                    queue.complete(
                        itemId = itemId,
                        leaseOwner = leaseOwner,
                        attemptCount = attemptCount,
                        completedAt = NOW,
                        indexedEventCount = result.derivedEvents.size,
                    ),
                )
            },
        )
        executor.enqueue(
            command = IndexDateRange(from = from, until = until, connectorId = connector.id),
            trigger = IndexingTrigger.MANUAL,
        )

        val completed = executor.executeNext(IndexingTrigger.MANUAL, leaseOwner = "manual-ui")

        assertNotNull(written)
        assertEquals(IndexingQueueState.COMPLETED, completed?.state)
        assertEquals(1, completed?.attemptCount)
        assertEquals(1, completed?.indexedEventCount)
        assertEquals(from, connector.lastScope?.from)
        assertEquals(until, connector.lastScope?.until)
        assertEquals(true, connector.lastScope?.forceRefresh)
        assertEquals(
            listOf("recover", "claim:MANUAL", "state", "permission", "scan", "write", "complete", "hook"),
            operations,
        )
        assertEquals("manual-ui", queue.lastTerminalLeaseOwner)
        assertEquals(1, queue.lastTerminalAttemptCount)
    }

    @Test
    fun `unknown and ineligible connectors finish with stable codes`() = runBlocking {
        val foreground = FakeConnector("location", ConnectorIndexingMode.FOREGROUND_ONLY)
        val queue = RecordingQueue()
        val executor = executor(listOf(foreground), queue)

        executor.enqueue(IndexConnector("missing"), IndexingTrigger.MANUAL)
        val missing = executor.executeNext(IndexingTrigger.MANUAL, "manual-ui")
        executor.enqueue(
            command = IndexConnector(foreground.id),
            trigger = IndexingTrigger.AUTOMATIC,
            automaticWindowKey = "2026-07-15:02-05",
            automaticGeneration = 7L,
        )
        val ineligible = executor.executeNext(
            trigger = IndexingTrigger.AUTOMATIC,
            leaseOwner = "automatic-worker",
            automaticGeneration = 7L,
        )

        assertEquals(IndexingFailureCode.CONNECTOR_NOT_FOUND, missing?.failureCode)
        assertEquals(IndexingSkipReason.NOT_BACKGROUND_ELIGIBLE, ineligible?.skipReason)
        assertNull(foreground.lastScope)
    }

    @Test
    fun `automatic generation is propagated to queue claims`() = runBlocking {
        val connector = FakeConnector(
            id = "calendar",
            mode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
        )
        val queue = RecordingQueue()
        val executor = executor(listOf(connector), queue)
        executor.enqueue(
            command = IndexConnector(connector.id),
            trigger = IndexingTrigger.AUTOMATIC,
            automaticWindowKey = "2026-07-15:02-05",
            automaticGeneration = 11L,
        )

        executor.executeNext(
            trigger = IndexingTrigger.AUTOMATIC,
            leaseOwner = "automatic-worker",
            automaticGeneration = 11L,
        )

        assertEquals(11L, queue.lastClaimAutomaticGeneration)
    }

    @Test
    fun `permission and empty scans produce stable skip reasons`() = runBlocking {
        val denied = FakeConnector(
            id = "photos",
            mode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            permissionGranted = false,
        )
        val empty = FakeConnector(
            id = "calendar",
            mode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            scanResult = ConnectorScanResult(
                connectorId = "calendar",
                processingState = ProcessingState.SKIPPED,
                scannedAt = NOW,
            ),
        )
        val queue = RecordingQueue()
        val executor = executor(listOf(denied, empty), queue)

        executor.enqueue(IndexConnector(denied.id), IndexingTrigger.MANUAL)
        val deniedResult = executor.executeNext(IndexingTrigger.MANUAL, "manual-ui")
        executor.enqueue(IndexConnector(empty.id), IndexingTrigger.MANUAL)
        val emptyResult = executor.executeNext(IndexingTrigger.MANUAL, "manual-ui")

        assertEquals(IndexingSkipReason.MISSING_PERMISSION, deniedResult?.skipReason)
        assertEquals(IndexingSkipReason.NO_INDEXABLE_DATA, emptyResult?.skipReason)
        assertNull(denied.lastScope)
        assertNotNull(empty.lastScope)
    }

    @Test
    fun `scan and store exceptions use stable failure codes`() = runBlocking {
        val scanFailure = FakeConnector(
            id = "scan-failure",
            mode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            scanError = IllegalStateException("raw scan detail must not persist"),
        )
        val queue = RecordingQueue()
        val scanExecutor = executor(listOf(scanFailure), queue)
        scanExecutor.enqueue(IndexConnector(scanFailure.id), IndexingTrigger.MANUAL)

        val scanResult = scanExecutor.executeNext(IndexingTrigger.MANUAL, "manual-ui")

        assertEquals(IndexingFailureCode.CONNECTOR_SCAN_FAILED, scanResult?.failureCode)

        val storeFailure = FakeConnector(
            id = "store-failure",
            mode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            scanResult = successfulScan("store-failure"),
        )
        val storeQueue = RecordingQueue()
        val storeExecutor = executor(
            connectors = listOf(storeFailure),
            queue = storeQueue,
            writer = ConnectorScanWriter { _, _, _, _ ->
                throw IllegalStateException("raw database detail must not persist")
            },
        )
        storeExecutor.enqueue(IndexConnector(storeFailure.id), IndexingTrigger.MANUAL)

        val storeResult = storeExecutor.executeNext(IndexingTrigger.MANUAL, "manual-ui")

        assertEquals(IndexingFailureCode.STORE_WRITE_FAILED, storeResult?.failureCode)
    }

    @Test
    fun `connector operation timeout fails without committing scan output`() = runBlocking {
        var writerCalled = false
        val connector = FakeConnector(
            id = "slow-scan",
            mode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            scanResult = successfulScan("slow-scan"),
            afterScan = { delay(500) },
        )
        val queue = RecordingQueue()
        val executor = executor(
            connectors = listOf(connector),
            queue = queue,
            writer = ConnectorScanWriter { _, _, _, _ ->
                writerCalled = true
                ConnectorScanCommitResult.LeaseLost
            },
            connectorOperationTimeout = Duration.ofMillis(50),
        )
        executor.enqueue(IndexConnector(connector.id), IndexingTrigger.MANUAL)

        val result = executor.executeNext(IndexingTrigger.MANUAL, "manual-ui")

        assertEquals(IndexingFailureCode.CONNECTOR_OPERATION_TIMED_OUT, result?.failureCode)
        assertFalse(writerCalled)
    }

    @Test
    fun `connector cannot commit output under another connector id`() = runBlocking {
        val connector = FakeConnector(
            id = "expected",
            mode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            scanResult = successfulScan("different"),
        )
        val queue = RecordingQueue()
        var writerCalled = false
        val executor = executor(
            connectors = listOf(connector),
            queue = queue,
            writer = ConnectorScanWriter { _, _, _, _ ->
                writerCalled = true
                ConnectorScanCommitResult.LeaseLost
            },
        )
        executor.enqueue(IndexConnector(connector.id), IndexingTrigger.MANUAL)

        val result = executor.executeNext(IndexingTrigger.MANUAL, "manual-ui")

        assertEquals(IndexingFailureCode.CONNECTOR_SCAN_FAILED, result?.failureCode)
        assertFalse(writerCalled)
    }

    @Test
    fun `cancellation is rethrown and leaves the lease for recovery`() = runBlocking {
        val connector = FakeConnector(
            id = "cancelled",
            mode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            scanError = CancellationException("cancelled"),
        )
        val queue = RecordingQueue()
        val executor = executor(listOf(connector), queue)
        executor.enqueue(IndexConnector(connector.id), IndexingTrigger.MANUAL)

        try {
            executor.executeNext(IndexingTrigger.MANUAL, "manual-ui")
            fail("Expected cancellation to be rethrown.")
        } catch (_: CancellationException) {
        }

        val item = queue.items.single()
        assertEquals(IndexingQueueState.RUNNING, item.state)
        assertNull(item.failureCode)
        assertEquals(3, queue.lastRecoveryMaxAttempts)
    }

    @Test
    fun `cancellation raised by a non cooperative scan is checked before commit`() = runBlocking {
        var writerCalled = false
        val connector = FakeConnector(
            id = "late-cancel",
            mode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            scanResult = successfulScan("late-cancel"),
            afterScan = {
                currentCoroutineContext().cancel(CancellationException("stopped after scan"))
            },
        )
        val queue = RecordingQueue()
        val executor = executor(
            connectors = listOf(connector),
            queue = queue,
            writer = ConnectorScanWriter { _, _, _, _ ->
                writerCalled = true
                ConnectorScanCommitResult.LeaseLost
            },
        )
        executor.enqueue(IndexConnector(connector.id), IndexingTrigger.MANUAL)

        val execution = async {
            executor.executeNext(IndexingTrigger.MANUAL, "manual-ui")
        }
        try {
            execution.await()
            fail("Expected cancellation before commit.")
        } catch (_: CancellationException) {
        }

        assertFalse(writerCalled)
        assertEquals(IndexingQueueState.RUNNING, queue.items.single().state)
    }

    @Test
    fun `lost lease stops without a terminal write or connector checkpoint`() = runBlocking {
        val operations = mutableListOf<String>()
        val connector = FakeConnector(
            id = "lease-lost",
            mode = ConnectorIndexingMode.BACKGROUND_SCANNABLE,
            operations = operations,
            scanResult = successfulScan("lease-lost"),
        )
        val queue = RecordingQueue(operations)
        val executor = executor(
            connectors = listOf(connector),
            queue = queue,
            writer = ConnectorScanWriter { _, _, _, _ ->
                operations += "fenced-commit"
                ConnectorScanCommitResult.LeaseLost
            },
        )
        executor.enqueue(IndexConnector(connector.id), IndexingTrigger.MANUAL)

        val result = executor.executeNext(IndexingTrigger.MANUAL, "manual-ui")

        assertNull(result)
        assertEquals(IndexingQueueState.RUNNING, queue.items.single().state)
        assertEquals(
            listOf("recover", "claim:MANUAL", "state", "permission", "scan", "fenced-commit"),
            operations,
        )
    }

    private fun executor(
        connectors: List<MemoryConnector>,
        queue: RecordingQueue,
        writer: ConnectorScanWriter? = null,
        taskIdFactory: (String) -> String = { connectorId -> "task:$connectorId" },
        connectorOperationTimeout: Duration = Duration.ofMinutes(4),
    ): IndexingCommandExecutor {
        return IndexingCommandExecutor(
            connectorRegistry = MemoryConnectorRegistry(connectors),
            queue = queue,
            scanWriter = writer ?: ConnectorScanWriter { result, itemId, leaseOwner, attemptCount ->
                ConnectorScanCommitResult.Committed(
                    queue.complete(
                        itemId = itemId,
                        leaseOwner = leaseOwner,
                        attemptCount = attemptCount,
                        completedAt = NOW,
                        indexedEventCount = result.derivedEvents.size,
                    ),
                )
            },
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            taskIdFactory = taskIdFactory,
            leaseDuration = Duration.ofMinutes(5),
            connectorOperationTimeout = connectorOperationTimeout,
            maxAttempts = 3,
        )
    }

    private class FakeConnector(
        val id: String,
        mode: ConnectorIndexingMode,
        private val enabled: Boolean = true,
        private val permissionGranted: Boolean = true,
        private val operations: MutableList<String> = mutableListOf(),
        private val scanResult: ConnectorScanResult = successfulScan(id),
        private val scanError: Exception? = null,
        private val afterScan: suspend () -> Unit = {},
    ) : MemoryConnector {
        override val metadata = ConnectorMetadata(
            connectorId = id,
            displayName = id,
            sourceKinds = setOf(SourceKind.LOCAL_FILE),
            connectorCapabilities = emptySet(),
            memoryCapabilities = emptySet(),
            indexingMode = mode,
            sensitivity = SensitivityLevel.MEDIUM,
        )
        var lastScope: ConnectorScanScope? = null
            private set

        override suspend fun currentState(): ConnectorState {
            operations += "state"
            return ConnectorState(
                connectorId = id,
                displayName = id,
                enabled = enabled,
                availability = if (enabled) SourceAvailability.AVAILABLE else SourceAvailability.DISABLED,
                permissionGranted = permissionGranted,
                capabilities = emptySet(),
                sensitivity = SensitivityLevel.MEDIUM,
                processingState = ProcessingState.PENDING,
            )
        }

        override suspend fun permissionState(): ConnectorPermissionState {
            operations += "permission"
            return ConnectorPermissionState(
                connectorId = id,
                availability = if (permissionGranted) {
                    SourceAvailability.AVAILABLE
                } else {
                    SourceAvailability.MISSING_PERMISSION
                },
                permissionGranted = permissionGranted,
                canRequestPermission = true,
            )
        }

        override suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult {
            operations += "scan"
            lastScope = scope
            scanError?.let { throw it }
            afterScan()
            return scanResult
        }

        override suspend fun onScanStored(scanResult: ConnectorScanResult) {
            operations += "hook"
        }

        override suspend fun revoke(): ConnectorRevokeResult {
            return ConnectorRevokeResult(
                connectorId = id,
                revokedAt = NOW,
                permissionState = permissionState(),
                deleteRequest = ConnectorDeleteRequest(id),
            )
        }

        override suspend fun deleteDerivedData(request: ConnectorDeleteRequest): ConnectorDeleteResult {
            return ConnectorDeleteResult(connectorId = id, completedAt = NOW)
        }
    }

    private class RecordingQueue(
        private val operations: MutableList<String> = mutableListOf(),
    ) : IndexingQueue {
        val items = mutableListOf<IndexingQueueItem>()
        var lastTerminalLeaseOwner: String? = null
        var lastTerminalAttemptCount: Int? = null
        var lastRecoveryMaxAttempts: Int? = null
        var lastClaimAutomaticGeneration: Long? = null

        override suspend fun enqueue(tasks: List<IndexingTask>): List<IndexingQueueItem> {
            return tasks.map { task -> IndexingQueueItem(task) }.also(items::addAll)
        }

        override suspend fun claimNextAtomically(
            leaseOwner: String,
            claimedAt: Instant,
            leaseUntil: Instant,
            trigger: IndexingTrigger?,
            automaticGeneration: Long?,
        ): IndexingQueueItem? {
            operations += "claim:$trigger"
            lastClaimAutomaticGeneration = automaticGeneration
            val index = items.indexOfFirst { item ->
                item.state == IndexingQueueState.PENDING &&
                    (trigger == null || item.task.trigger == trigger) &&
                    (
                        automaticGeneration == null ||
                            item.task.automaticGeneration == automaticGeneration
                    )
            }
            if (index < 0) return null
            val pending = items[index]
            return pending.copy(
                state = IndexingQueueState.RUNNING,
                attemptCount = pending.attemptCount + 1,
                lastAttemptAt = claimedAt,
                startedAt = claimedAt,
                leaseOwner = leaseOwner,
                leaseUntil = leaseUntil,
            ).also { running -> items[index] = running }
        }

        override suspend fun complete(
            itemId: String,
            leaseOwner: String,
            attemptCount: Int,
            completedAt: Instant,
            indexedEventCount: Int,
        ): IndexingQueueItem {
            operations += "complete"
            return terminal(itemId, leaseOwner, attemptCount) { item ->
                item.copy(
                    state = IndexingQueueState.COMPLETED,
                    completedAt = completedAt,
                    leaseOwner = null,
                    leaseUntil = null,
                    indexedEventCount = indexedEventCount,
                )
            }
        }

        override suspend fun skip(
            itemId: String,
            leaseOwner: String,
            attemptCount: Int,
            completedAt: Instant,
            reason: IndexingSkipReason,
        ): IndexingQueueItem {
            operations += "skip"
            return terminal(itemId, leaseOwner, attemptCount) { item ->
                item.copy(
                    state = IndexingQueueState.SKIPPED,
                    completedAt = completedAt,
                    leaseOwner = null,
                    leaseUntil = null,
                    skipReason = reason,
                )
            }
        }

        override suspend fun fail(
            itemId: String,
            leaseOwner: String,
            attemptCount: Int,
            completedAt: Instant,
            code: IndexingFailureCode,
        ): IndexingQueueItem {
            operations += "fail"
            return terminal(itemId, leaseOwner, attemptCount) { item ->
                item.copy(
                    state = IndexingQueueState.FAILED,
                    completedAt = completedAt,
                    leaseOwner = null,
                    leaseUntil = null,
                    failureCode = code,
                )
            }
        }

        override suspend fun recoverExpiredLeases(now: Instant, maxAttempts: Int): LeaseRecoveryResult {
            operations += "recover"
            lastRecoveryMaxAttempts = maxAttempts
            return LeaseRecoveryResult(0, 0)
        }

        override suspend fun snapshot(limit: Int): IndexingQueueSnapshot {
            val visible = items.take(limit)
            return IndexingQueueSnapshot(
                items = visible,
                queueDepth = items.count { it.state == IndexingQueueState.PENDING },
                runningConnectorIds = items.filter { it.state == IndexingQueueState.RUNNING }
                    .mapTo(mutableSetOf()) { it.connectorId },
                lastCompletedAt = items.mapNotNull { it.completedAt }.maxOrNull(),
                observedAt = NOW,
            )
        }

        override suspend fun pruneTerminalItems(completedBefore: Instant, keepLatest: Int): Int = 0

        private fun terminal(
            itemId: String,
            leaseOwner: String,
            attemptCount: Int,
            transform: (IndexingQueueItem) -> IndexingQueueItem,
        ): IndexingQueueItem {
            val index = items.indexOfFirst { it.id == itemId }
            val current = items[index]
            require(current.leaseOwner == leaseOwner && current.attemptCount == attemptCount)
            lastTerminalLeaseOwner = leaseOwner
            lastTerminalAttemptCount = attemptCount
            return transform(current).also { terminal -> items[index] = terminal }
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-15T05:00:00Z")

        fun successfulScan(connectorId: String): ConnectorScanResult {
            val sourceId = "source:$connectorId:1"
            return ConnectorScanResult(
                connectorId = connectorId,
                processingState = ProcessingState.COMPLETED,
                sourceReferences = listOf(
                    SourceReference(
                        id = sourceId,
                        connectorId = connectorId,
                        sourceKind = SourceKind.LOCAL_FILE,
                    ),
                ),
                derivedEvents = listOf(
                    DerivedMemoryEvent(
                        id = "event:$connectorId:1",
                        kind = DerivedMemoryEventKind.LOCAL_FILE_INDEX,
                        sourceReferenceIds = listOf(sourceId),
                        summary = "Derived test event",
                        createdAt = NOW,
                    ),
                ),
                scannedAt = NOW,
            )
        }
    }
}
