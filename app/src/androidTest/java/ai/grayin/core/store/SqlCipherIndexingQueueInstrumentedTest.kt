package ai.grayin.core.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.indexing.AutomaticIndexingOutcome
import ai.grayin.core.indexing.AutomaticIndexingRuntimeStatus
import ai.grayin.core.indexing.ConnectorScanCommitResult
import ai.grayin.core.indexing.IndexingFailureCode
import ai.grayin.core.indexing.IndexingQueueState
import ai.grayin.core.indexing.IndexingSkipReason
import ai.grayin.core.indexing.IndexingTask
import ai.grayin.core.indexing.IndexingTrigger
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlCipherIndexingQueueInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseNames = mutableSetOf<String>()

    @After
    fun deleteTestDatabases() {
        databaseNames.forEach(context::deleteDatabase)
        databaseNames.clear()
    }

    @Test
    fun queuePersistsAcrossStoreReopen() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T00:00:00Z")
        val task = manualTask(id = "task-persist", requestedAt = requestedAt)

        store(databaseName).enqueue(listOf(task))
        val reopenedSnapshot = store(databaseName).snapshot()

        assertEquals(1, reopenedSnapshot.queueDepth)
        assertEquals(listOf(task), reopenedSnapshot.items.map { it.task })
        assertEquals(IndexingQueueState.PENDING, reopenedSnapshot.items.single().state)
    }

    @Test
    fun concurrentClaimReturnsOneLeaseOwner() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T01:00:00Z")
        store(databaseName).enqueue(listOf(manualTask(id = "task-claim", requestedAt = requestedAt)))
        val start = CompletableDeferred<Unit>()
        val claimedAt = requestedAt.plusSeconds(1)

        val claims = listOf("worker-a", "worker-b").map { owner ->
            async(Dispatchers.IO) {
                start.await()
                store(databaseName).claimNextAtomically(
                    leaseOwner = owner,
                    claimedAt = claimedAt,
                    leaseUntil = claimedAt.plusSeconds(60),
                )
            }
        }
        start.complete(Unit)
        val results = claims.awaitAll()

        assertEquals(1, results.count { it != null })
        assertEquals(1, results.mapNotNull { it?.leaseOwner }.distinct().size)
        val winningClaim = checkNotNull(results.single { it != null })
        expectIllegalArgument {
            store(databaseName).complete(
                itemId = winningClaim.id,
                leaseOwner = "stale-worker",
                attemptCount = winningClaim.attemptCount,
                completedAt = claimedAt.plusSeconds(2),
                indexedEventCount = 1,
            )
        }
        assertEquals(IndexingQueueState.RUNNING, store(databaseName).snapshot().items.single().state)
    }

    @Test
    fun triggerFilteredClaimLeavesOtherTriggerPending() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T01:30:00Z")
        val queue = store(databaseName)
        val generation = enableAutomatic(queue, requestedAt.minusSeconds(1))
        val manual = manualTask(id = "manual-first", requestedAt = requestedAt)
        val automatic = automaticTask(
            id = "automatic-second",
            requestedAt = requestedAt.plusSeconds(1),
            windowKey = "2026-07-15:02-05",
            generation = generation,
        )
        queue.enqueue(listOf(manual, automatic))

        val claimedAt = requestedAt.plusSeconds(2)
        val claimed = queue.claimNextAtomically(
            leaseOwner = "automatic-worker",
            claimedAt = claimedAt,
            leaseUntil = claimedAt.plusSeconds(60),
            trigger = IndexingTrigger.AUTOMATIC,
            automaticGeneration = generation,
        )

        assertEquals(automatic.id, checkNotNull(claimed).id)
        val snapshot = queue.snapshot()
        assertEquals(IndexingQueueState.PENDING, snapshot.items.single { it.id == manual.id }.state)
        assertEquals(IndexingQueueState.RUNNING, snapshot.items.single { it.id == automatic.id }.state)
    }

    @Test
    fun staleLeaseCannotCommitDerivedRows() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T01:45:00Z")
        val queue = store(
            databaseName = databaseName,
            clock = Clock.fixed(requestedAt.plusSeconds(15), ZoneOffset.UTC),
        )
        queue.enqueue(listOf(manualTask(id = "fenced-commit", requestedAt = requestedAt)))

        val firstClaimAt = requestedAt.plusSeconds(1)
        val first = checkNotNull(
            queue.claimNextAtomically(
                leaseOwner = "stale-worker",
                claimedAt = firstClaimAt,
                leaseUntil = firstClaimAt.plusSeconds(10),
                trigger = IndexingTrigger.MANUAL,
            ),
        )
        queue.recoverExpiredLeases(now = firstClaimAt.plusSeconds(11), maxAttempts = 3)
        val secondClaimAt = firstClaimAt.plusSeconds(12)
        val second = checkNotNull(
            queue.claimNextAtomically(
                leaseOwner = "current-worker",
                claimedAt = secondClaimAt,
                leaseUntil = secondClaimAt.plusSeconds(10),
                trigger = IndexingTrigger.MANUAL,
            ),
        )

        val staleCommit = queue.commitClaimedConnectorScan(
            scanResult = scanResult("stale"),
            itemId = first.id,
            leaseOwner = checkNotNull(first.leaseOwner),
            attemptCount = first.attemptCount,
        )

        assertEquals(ConnectorScanCommitResult.LeaseLost, staleCommit)
        assertEquals(emptyList<DerivedMemoryEvent>(), queue.loadDerivedMemoryEvents())

        expectIllegalArgument {
            queue.commitClaimedConnectorScan(
                scanResult = scanResult("mismatch", connectorId = "different.connector"),
                itemId = second.id,
                leaseOwner = checkNotNull(second.leaseOwner),
                attemptCount = second.attemptCount,
            )
        }
        assertEquals(emptyList<DerivedMemoryEvent>(), queue.loadDerivedMemoryEvents())
        assertEquals(IndexingQueueState.RUNNING, queue.snapshot().items.single().state)

        val liveCommit = queue.commitClaimedConnectorScan(
            scanResult = scanResult("current"),
            itemId = second.id,
            leaseOwner = checkNotNull(second.leaseOwner),
            attemptCount = second.attemptCount,
        )

        assertEquals(IndexingQueueState.COMPLETED, (liveCommit as ConnectorScanCommitResult.Committed).item.state)
        assertEquals(listOf("event:$TEST_CONNECTOR_ID:current"), queue.loadDerivedMemoryEvents().map { it.id })
    }

    @Test
    fun disablingAutomaticIndexingFencesRunningTaskAndRuntime() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T01:55:00Z")
        val queue = store(databaseName)
        val generation = enableAutomatic(queue, requestedAt.minusSeconds(1))
        val task = automaticTask(
            id = "automatic-disable",
            requestedAt = requestedAt,
            windowKey = "2026-07-15:02-05",
            generation = generation,
        )
        queue.enqueue(listOf(task))
        val claimedAt = requestedAt.plusSeconds(1)
        val claimed = checkNotNull(
            queue.claimNextAtomically(
                leaseOwner = "automatic-worker",
                claimedAt = claimedAt,
                leaseUntil = claimedAt.plusSeconds(60),
                trigger = IndexingTrigger.AUTOMATIC,
                automaticGeneration = generation,
            ),
        )
        assertTrue(
            queue.saveAutomaticIndexingRuntime(
                AutomaticIndexingRuntimeStatus(
                    lastCheckedAt = requestedAt,
                    lastStartedAt = claimedAt,
                    lastOutcome = AutomaticIndexingOutcome.RUNNING,
                ),
                expectedGeneration = generation,
            ),
        )

        val disabledAt = claimedAt.plusSeconds(2)
        val disabledControl = queue.synchronizeAutomaticIndexingControl(
            enabled = false,
            settingsKey = "v1:test-disabled",
            changedAt = disabledAt,
        )
        val status = queue.loadAutomaticIndexingRuntime()

        assertTrue(disabledControl.generation > generation)
        assertEquals(AutomaticIndexingOutcome.SKIPPED, status.lastOutcome)
        assertEquals(IndexingSkipReason.AUTOMATIC_INDEXING_DISABLED, status.lastSkipReason)
        assertEquals(status, queue.loadAutomaticIndexingRuntime())
        val skipped = queue.snapshot().items.single()
        assertEquals(IndexingQueueState.SKIPPED, skipped.state)
        assertEquals(IndexingSkipReason.AUTOMATIC_INDEXING_DISABLED, skipped.skipReason)
        assertNull(skipped.leaseOwner)
        val staleCommit = queue.commitClaimedConnectorScan(
            scanResult = scanResult("disabled-stale"),
            itemId = claimed.id,
            leaseOwner = checkNotNull(claimed.leaseOwner),
            attemptCount = claimed.attemptCount,
        )
        assertEquals(ConnectorScanCommitResult.LeaseLost, staleCommit)
        assertEquals(emptyList<DerivedMemoryEvent>(), queue.loadDerivedMemoryEvents())
    }

    @Test
    fun expiredLeaseRequeuesThenFailsAtAttemptLimit() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T02:00:00Z")
        val queue = store(databaseName)
        queue.enqueue(listOf(manualTask(id = "task-recovery", requestedAt = requestedAt)))

        val firstClaimAt = requestedAt.plusSeconds(1)
        val firstClaim = queue.claimNextAtomically(
            leaseOwner = "worker-first",
            claimedAt = firstClaimAt,
            leaseUntil = firstClaimAt.plusSeconds(10),
        )
        assertNotNull(firstClaim)

        val firstRecovery = queue.recoverExpiredLeases(
            now = firstClaimAt.plusSeconds(11),
            maxAttempts = 2,
        )
        assertEquals(1, firstRecovery.requeuedCount)
        assertEquals(0, firstRecovery.failedCount)
        val requeued = queue.snapshot().items.single()
        assertEquals(IndexingQueueState.PENDING, requeued.state)
        assertEquals(1, requeued.attemptCount)
        assertNull(requeued.leaseOwner)

        val secondClaimAt = firstClaimAt.plusSeconds(12)
        val secondClaim = queue.claimNextAtomically(
            leaseOwner = "worker-second",
            claimedAt = secondClaimAt,
            leaseUntil = secondClaimAt.plusSeconds(10),
        )
        assertEquals(2, checkNotNull(secondClaim).attemptCount)
        expectIllegalArgument {
            queue.complete(
                itemId = secondClaim.id,
                leaseOwner = checkNotNull(secondClaim.leaseOwner),
                attemptCount = 1,
                completedAt = secondClaimAt.plusSeconds(1),
                indexedEventCount = 1,
            )
        }
        assertEquals(IndexingQueueState.RUNNING, queue.snapshot().items.single().state)

        val secondRecoveryAt = secondClaimAt.plusSeconds(11)
        val secondRecovery = queue.recoverExpiredLeases(
            now = secondRecoveryAt,
            maxAttempts = 2,
        )
        assertEquals(0, secondRecovery.requeuedCount)
        assertEquals(1, secondRecovery.failedCount)
        val failed = queue.snapshot().items.single()
        assertEquals(IndexingQueueState.FAILED, failed.state)
        assertEquals(IndexingFailureCode.ATTEMPT_LIMIT_REACHED, failed.failureCode)
        assertEquals(secondRecoveryAt, failed.completedAt)
        assertNull(failed.leaseOwner)
    }

    @Test
    fun expiredLeaseCannotBeAcknowledgedBeforeRecovery() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T02:30:00Z")
        val queue = store(databaseName)
        queue.enqueue(listOf(manualTask(id = "expired-terminal", requestedAt = requestedAt)))
        val claimedAt = requestedAt.plusSeconds(1)
        val claimed = checkNotNull(
            queue.claimNextAtomically(
                leaseOwner = "slow-worker",
                claimedAt = claimedAt,
                leaseUntil = claimedAt.plusSeconds(10),
                trigger = IndexingTrigger.MANUAL,
            ),
        )

        expectIllegalArgument {
            queue.fail(
                itemId = claimed.id,
                leaseOwner = checkNotNull(claimed.leaseOwner),
                attemptCount = claimed.attemptCount,
                completedAt = claimedAt.plusSeconds(11),
                code = IndexingFailureCode.CONNECTOR_OPERATION_TIMED_OUT,
            )
        }

        assertEquals(IndexingQueueState.RUNNING, queue.snapshot().items.single().state)
    }

    @Test
    fun automaticWindowEnqueueReturnsExistingTask() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T03:00:00Z")
        val queue = store(databaseName)
        val generation = enableAutomatic(queue, requestedAt.minusSeconds(1))
        val first = automaticTask(
            id = "automatic-first",
            requestedAt = requestedAt,
            windowKey = "2026-07-15:02-05",
            generation = generation,
        )
        val duplicate = automaticTask(
            id = "automatic-duplicate",
            requestedAt = requestedAt.plusSeconds(30),
            windowKey = first.automaticWindowKey!!,
            generation = generation,
        )

        val enqueued = queue.enqueue(listOf(first, duplicate))

        assertEquals(2, enqueued.size)
        assertEquals(enqueued[0], enqueued[1])
        assertEquals(first.id, enqueued[1].id)
        assertEquals(1, queue.snapshot().items.size)
    }

    @Test
    fun staleAutomaticGenerationCannotEnqueueOrOverwriteDisabledRuntime() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T03:30:00Z")
        val queue = store(databaseName)
        val generation = enableAutomatic(queue, requestedAt.minusSeconds(1))
        val disabledAt = requestedAt.plusSeconds(1)
        queue.synchronizeAutomaticIndexingControl(
            enabled = false,
            settingsKey = "v1:test-disabled",
            changedAt = disabledAt,
        )

        val enqueued = queue.enqueue(
            listOf(
                automaticTask(
                    id = "stale-after-disable",
                    requestedAt = requestedAt.plusSeconds(2),
                    windowKey = "2026-07-15:02-05",
                    generation = generation,
                ),
            ),
        )
        val wroteRuntime = queue.saveAutomaticIndexingRuntime(
            AutomaticIndexingRuntimeStatus(
                lastCheckedAt = requestedAt.plusSeconds(2),
                lastCompletedAt = requestedAt.plusSeconds(2),
                lastOutcome = AutomaticIndexingOutcome.COMPLETED,
            ),
            expectedGeneration = generation,
        )

        assertEquals(emptyList<Any>(), enqueued)
        assertFalse(wroteRuntime)
        assertEquals(0, queue.snapshot().queueDepth)
        assertEquals(AutomaticIndexingOutcome.SKIPPED, queue.loadAutomaticIndexingRuntime().lastOutcome)
    }

    @Test
    fun reenableCanEnqueueSameWindowInANewGeneration() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T03:45:00Z")
        val queue = store(databaseName)
        val firstGeneration = enableAutomatic(queue, requestedAt.minusSeconds(1))
        queue.enqueue(
            listOf(
                automaticTask(
                    id = "automatic-before-toggle",
                    requestedAt = requestedAt,
                    windowKey = "2026-07-15:02-05",
                    generation = firstGeneration,
                ),
            ),
        )
        queue.synchronizeAutomaticIndexingControl(
            enabled = false,
            settingsKey = "v1:test-disabled",
            changedAt = requestedAt.plusSeconds(1),
        )
        val reenabled = queue.synchronizeAutomaticIndexingControl(
            enabled = true,
            settingsKey = ENABLED_SETTINGS_KEY,
            changedAt = requestedAt.plusSeconds(2),
        )

        val second = queue.enqueue(
            listOf(
                automaticTask(
                    id = "automatic-after-toggle",
                    requestedAt = requestedAt.plusSeconds(3),
                    windowKey = "2026-07-15:02-05",
                    generation = reenabled.generation,
                ),
            ),
        )

        assertTrue(reenabled.generation > firstGeneration)
        assertEquals(1, second.size)
        val items = queue.snapshot().items
        assertEquals(2, items.size)
        assertEquals(
            IndexingQueueState.SKIPPED,
            items.single { it.id == "automatic-before-toggle" }.state,
        )
        assertEquals(
            IndexingQueueState.PENDING,
            items.single { it.id == "automatic-after-toggle" }.state,
        )
        assertEquals(
            IndexingSkipReason.AUTOMATIC_INDEXING_CONFIGURATION_CHANGED,
            queue.loadAutomaticIndexingRuntime().lastSkipReason,
        )
    }

    @Test
    fun enabledConfigurationChangeFencesRunningGenerationButNotManualWork() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T03:55:00Z")
        val queue = store(databaseName)
        val generation = enableAutomatic(queue, requestedAt.minusSeconds(1))
        val automatic = automaticTask(
            id = "automatic-old-settings",
            requestedAt = requestedAt,
            windowKey = "2026-07-15:02-05",
            generation = generation,
        )
        val manual = manualTask(id = "manual-unrelated", requestedAt = requestedAt)
        queue.enqueue(listOf(automatic, manual))
        val claimedAt = requestedAt.plusSeconds(1)
        val claimed = checkNotNull(
            queue.claimNextAtomically(
                leaseOwner = "automatic-worker",
                claimedAt = claimedAt,
                leaseUntil = claimedAt.plusSeconds(60),
                trigger = IndexingTrigger.AUTOMATIC,
                automaticGeneration = generation,
            ),
        )

        val changed = queue.synchronizeAutomaticIndexingControl(
            enabled = true,
            settingsKey = "v1:test-enabled:new-window",
            changedAt = claimedAt.plusSeconds(2),
        )
        val staleCommit = queue.commitClaimedConnectorScan(
            scanResult = scanResult("stale-settings"),
            itemId = claimed.id,
            leaseOwner = checkNotNull(claimed.leaseOwner),
            attemptCount = claimed.attemptCount,
        )

        assertTrue(changed.generation > generation)
        assertEquals(ConnectorScanCommitResult.LeaseLost, staleCommit)
        val items = queue.snapshot().items
        assertEquals(IndexingQueueState.SKIPPED, items.single { it.id == automatic.id }.state)
        assertEquals(IndexingQueueState.PENDING, items.single { it.id == manual.id }.state)
        assertEquals(
            IndexingSkipReason.AUTOMATIC_INDEXING_CONFIGURATION_CHANGED,
            queue.loadAutomaticIndexingRuntime().lastSkipReason,
        )
    }

    @Test
    fun automaticRuntimeStatusRoundTripsAcrossStoreReopen() = runBlocking {
        val databaseName = newDatabaseName()
        val checkedAt = Instant.parse("2026-07-15T04:00:00Z")
        val generation = enableAutomatic(store(databaseName), checkedAt.minusSeconds(1))
        val status = AutomaticIndexingRuntimeStatus(
            lastCheckedAt = checkedAt,
            lastStartedAt = checkedAt.plusSeconds(1),
            lastCompletedAt = checkedAt.plusSeconds(5),
            lastOutcome = AutomaticIndexingOutcome.COMPLETED,
            lastIndexedEventCount = 7,
        )

        assertTrue(
            store(databaseName).saveAutomaticIndexingRuntime(
                status = status,
                expectedGeneration = generation,
            ),
        )

        assertEquals(status, store(databaseName).loadAutomaticIndexingRuntime())
    }

    private fun manualTask(id: String, requestedAt: Instant): IndexingTask {
        return IndexingTask(
            id = id,
            connectorId = TEST_CONNECTOR_ID,
            trigger = IndexingTrigger.MANUAL,
            requestedAt = requestedAt,
            forceRefresh = true,
        )
    }

    private fun automaticTask(
        id: String,
        requestedAt: Instant,
        windowKey: String,
        generation: Long,
    ): IndexingTask {
        return IndexingTask(
            id = id,
            connectorId = TEST_CONNECTOR_ID,
            trigger = IndexingTrigger.AUTOMATIC,
            requestedAt = requestedAt,
            automaticWindowKey = windowKey,
            automaticGeneration = generation,
        )
    }

    private suspend fun enableAutomatic(
        store: SqlCipherLocalMemoryStore,
        changedAt: Instant,
    ): Long {
        return store.synchronizeAutomaticIndexingControl(
            enabled = true,
            settingsKey = ENABLED_SETTINGS_KEY,
            changedAt = changedAt,
        ).generation
    }

    private fun scanResult(
        suffix: String,
        connectorId: String = TEST_CONNECTOR_ID,
    ): ConnectorScanResult {
        val sourceId = "source:$connectorId:$suffix"
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
                    id = "event:$connectorId:$suffix",
                    kind = DerivedMemoryEventKind.LOCAL_FILE_INDEX,
                    sourceReferenceIds = listOf(sourceId),
                    summary = "Derived instrumented event",
                    createdAt = Instant.parse("2026-07-15T01:45:00Z"),
                ),
            ),
            scannedAt = Instant.parse("2026-07-15T01:45:00Z"),
        )
    }

    private fun newDatabaseName(): String {
        return "grayin-indexing-test-${UUID.randomUUID()}.db".also { databaseName ->
            context.deleteDatabase(databaseName)
            databaseNames += databaseName
        }
    }

    private fun store(
        databaseName: String,
        clock: Clock = Clock.systemUTC(),
    ): SqlCipherLocalMemoryStore {
        return SqlCipherLocalMemoryStore(
            context = context,
            passphraseProvider = FixedPassphraseProvider,
            databaseName = databaseName,
            clock = clock,
        )
    }

    private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected an IllegalArgumentException.")
        } catch (_: IllegalArgumentException) {
        }
    }

    private object FixedPassphraseProvider : StorePassphraseProvider {
        override fun getPassphrase(context: Context): String = TEST_PASSPHRASE
    }

    private companion object {
        const val TEST_CONNECTOR_ID = "connector.instrumented-test"
        const val TEST_PASSPHRASE = "grayin-instrumented-test-passphrase"
        const val ENABLED_SETTINGS_KEY = "v1:test-enabled"
    }
}
