package ai.grayin.core.store

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.grayin.connectors.notification.NotificationConsentCoordinator
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.indexing.AutomaticIndexingOutcome
import ai.grayin.core.indexing.AutomaticIndexingRuntimeStatus
import ai.grayin.core.indexing.ConnectorScanCommitResult
import ai.grayin.core.indexing.IndexingFailureCode
import ai.grayin.core.indexing.IndexingQueueState
import ai.grayin.core.indexing.IndexingSkipReason
import ai.grayin.core.indexing.IndexingTask
import ai.grayin.core.indexing.IndexingTrigger
import ai.grayin.core.model.AppUsageCategory
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.DailyMemorySummary
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
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
    fun claimsSerializeLiveWorkForTheSameConnector() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T01:10:00Z")
        val queue = store(databaseName)
        val firstTask = manualTask(id = "same-first", requestedAt = requestedAt)
        val secondTask = manualTask(id = "same-second", requestedAt = requestedAt.plusSeconds(1))
        val otherTask = manualTask(
            id = "other",
            requestedAt = requestedAt.plusSeconds(2),
            connectorId = "other.connector",
        )
        queue.enqueue(listOf(firstTask, secondTask, otherTask))

        val first = checkNotNull(
            queue.claimNextAtomically(
                leaseOwner = "worker-a",
                claimedAt = requestedAt.plusSeconds(3),
                leaseUntil = requestedAt.plusSeconds(60),
            ),
        )
        val other = checkNotNull(
            queue.claimNextAtomically(
                leaseOwner = "worker-b",
                claimedAt = requestedAt.plusSeconds(4),
                leaseUntil = requestedAt.plusSeconds(60),
            ),
        )

        assertEquals(firstTask.id, first.id)
        assertEquals(otherTask.id, other.id)
        assertNull(
            queue.claimNextAtomically(
                leaseOwner = "worker-c",
                claimedAt = requestedAt.plusSeconds(5),
                leaseUntil = requestedAt.plusSeconds(60),
            ),
        )

        queue.complete(
            itemId = first.id,
            leaseOwner = checkNotNull(first.leaseOwner),
            attemptCount = first.attemptCount,
            completedAt = requestedAt.plusSeconds(6),
            indexedEventCount = 1,
        )
        val second = checkNotNull(
            queue.claimNextAtomically(
                leaseOwner = "worker-c",
                claimedAt = requestedAt.plusSeconds(7),
                leaseUntil = requestedAt.plusSeconds(60),
            ),
        )
        assertEquals(secondTask.id, second.id)
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
        assertTrue(queue.loadConnectorScanStatuses().isEmpty())

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
    fun runningConnectorSummaryExcludesExpiredLease() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T02:45:00Z")
        val claimedAt = requestedAt.plusSeconds(1)
        val liveStore = store(
            databaseName = databaseName,
            clock = Clock.fixed(claimedAt.plusSeconds(5), ZoneOffset.UTC),
        )
        liveStore.enqueue(listOf(manualTask(id = "running-summary", requestedAt = requestedAt)))
        liveStore.claimNextAtomically(
            leaseOwner = "summary-worker",
            claimedAt = claimedAt,
            leaseUntil = claimedAt.plusSeconds(10),
            trigger = IndexingTrigger.MANUAL,
        )

        assertEquals(setOf(TEST_CONNECTOR_ID), liveStore.snapshot().runningConnectorIds)
        val expiredStore = store(
            databaseName = databaseName,
            clock = Clock.fixed(claimedAt.plusSeconds(11), ZoneOffset.UTC),
        )
        assertEquals(emptySet<String>(), expiredStore.snapshot().runningConnectorIds)
        assertEquals(IndexingQueueState.RUNNING, expiredStore.snapshot().items.single().state)
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

    @Test
    fun emptyClaimedScanPersistsMissingStatusAndSkipsAtomically() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T05:00:00Z")
        val completedAt = requestedAt.plusSeconds(5)
        val scopeFrom = requestedAt.minusSeconds(86_400)
        val scopeUntil = requestedAt
        val queue = store(
            databaseName = databaseName,
            clock = Clock.fixed(completedAt, ZoneOffset.UTC),
        )
        queue.enqueue(listOf(manualTask(id = "empty-status", requestedAt = requestedAt)))
        val claimed = checkNotNull(
            queue.claimNextAtomically(
                leaseOwner = "status-worker",
                claimedAt = requestedAt.plusSeconds(1),
                leaseUntil = requestedAt.plusSeconds(60),
                trigger = IndexingTrigger.MANUAL,
            ),
        )
        val missing = MissingSource(
            capability = MemoryCapability.HAS_TEXT,
            availability = SourceAvailability.UNSUPPORTED,
            explanation = ConnectorScanIssueCode.NO_EXTRACTABLE_TEXT.defaultEnglish,
            connectorId = TEST_CONNECTOR_ID,
            issueCode = ConnectorScanIssueCode.NO_EXTRACTABLE_TEXT,
        )

        val committed = queue.commitClaimedConnectorScan(
            scanResult = ConnectorScanResult(
                connectorId = TEST_CONNECTOR_ID,
                processingState = ProcessingState.SKIPPED,
                missingSources = listOf(missing),
                scopeFrom = scopeFrom,
                scopeUntil = scopeUntil,
                scannedAt = completedAt,
            ),
            itemId = claimed.id,
            leaseOwner = checkNotNull(claimed.leaseOwner),
            attemptCount = claimed.attemptCount,
        ) as ConnectorScanCommitResult.Committed

        assertEquals(IndexingQueueState.SKIPPED, committed.item.state)
        assertEquals(IndexingSkipReason.NO_INDEXABLE_DATA, committed.item.skipReason)
        val status = queue.loadSnapshot().connectorScanStatuses.single()
        assertEquals(ProcessingState.SKIPPED, status.processingState)
        assertEquals(listOf(missing), status.missingSources)
        assertEquals(scopeFrom, status.scopeFrom)
        assertEquals(scopeUntil, status.scopeUntil)
        assertEquals(completedAt, status.scannedAt)
    }

    @Test
    fun replacementScanRemovesStaleRowsAndDeleteClearsStatus() = runBlocking {
        val databaseName = newDatabaseName()
        val memoryStore = store(databaseName)
        memoryStore.saveConnectorScan(scanResult("old"))
        memoryStore.saveConnectorScan(scanResult("other", connectorId = "other.connector"))

        memoryStore.saveConnectorScan(
            scanResult("new").copy(replaceExistingConnectorData = true),
        )

        assertEquals(
            setOf("event:$TEST_CONNECTOR_ID:new", "event:other.connector:other"),
            memoryStore.loadDerivedMemoryEvents().mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(
            setOf(TEST_CONNECTOR_ID, "other.connector"),
            memoryStore.loadConnectorScanStatuses().mapTo(mutableSetOf()) { it.connectorId },
        )

        memoryStore.deleteConnectorData(TEST_CONNECTOR_ID)

        assertEquals(listOf("event:other.connector:other"), memoryStore.loadDerivedMemoryEvents().map { it.id })
        assertEquals(listOf("other.connector"), memoryStore.loadConnectorScanStatuses().map { it.connectorId })
    }

    @Test
    fun emptyReplacementScanRemovesOnlyTheTargetConnectorSnapshot() = runBlocking {
        val databaseName = newDatabaseName()
        val memoryStore = store(databaseName)
        memoryStore.saveConnectorScan(scanResult("old"))
        memoryStore.saveConnectorScan(scanResult("other", connectorId = "other.connector"))
        val scannedAt = Instant.parse("2026-07-15T06:00:00Z")

        memoryStore.saveConnectorScan(
            ConnectorScanResult(
                connectorId = TEST_CONNECTOR_ID,
                processingState = ProcessingState.SKIPPED,
                replaceExistingConnectorData = true,
                scannedAt = scannedAt,
            ),
        )

        assertEquals(listOf("event:other.connector:other"), memoryStore.loadDerivedMemoryEvents().map { it.id })
        val statuses = memoryStore.loadConnectorScanStatuses().associateBy { it.connectorId }
        assertEquals(setOf(TEST_CONNECTOR_ID, "other.connector"), statuses.keys)
        assertEquals(ProcessingState.SKIPPED, statuses.getValue(TEST_CONNECTOR_ID).processingState)
        assertEquals(scannedAt, statuses.getValue(TEST_CONNECTOR_ID).scannedAt)
    }

    @Test
    fun locationClusterMergeIsIdempotentInsideTheConnectorTransaction() = runBlocking {
        val databaseName = newDatabaseName()
        val memoryStore = store(databaseName)
        val firstAt = Instant.parse("2026-07-14T12:00:00Z")
        val lastAt = Instant.parse("2026-07-15T12:00:00Z")
        val first = locationScanResult("first", firstAt, "Old region")
        val second = locationScanResult("second", lastAt, "New region")

        memoryStore.saveConnectorScan(first)
        memoryStore.saveConnectorScan(first)
        assertEquals(1, memoryStore.loadPlaceClusters().single().visitCount)

        memoryStore.saveConnectorScan(second)
        memoryStore.saveConnectorScan(second)

        val cluster = memoryStore.loadSnapshot().placeClusters.single()
        assertEquals("New region", cluster.regionLabel)
        assertEquals(firstAt, cluster.firstSeenAt)
        assertEquals(lastAt, cluster.lastSeenAt)
        assertEquals(2, cluster.visitCount)
        assertEquals(
            listOf("source:location:first", "source:location:second"),
            cluster.sourceReferenceIds,
        )
    }

    @Test
    fun connectorDeleteFencesRunningAndPendingTasksBeforeStaleCommit() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T06:10:00Z")
        val deletedAt = requestedAt.plusSeconds(10)
        val memoryStore = store(databaseName, Clock.fixed(deletedAt, ZoneOffset.UTC))
        memoryStore.enqueue(
            listOf(
                manualTask("local-running", requestedAt, connectorId = "local_files"),
                manualTask("local-pending", requestedAt.plusSeconds(1), connectorId = "local_files"),
                manualTask("other-pending", requestedAt.plusSeconds(2), connectorId = "other.connector"),
            ),
        )
        val claimed = checkNotNull(
            memoryStore.claimNextAtomically(
                leaseOwner = "delete-race-worker",
                claimedAt = requestedAt.plusSeconds(3),
                leaseUntil = requestedAt.plusSeconds(60),
            ),
        )

        memoryStore.deleteConnectorData("local_files")

        val staleCommit = memoryStore.commitClaimedConnectorScan(
            scanResult = ConnectorScanResult(
                connectorId = "local_files",
                processingState = ProcessingState.SKIPPED,
                replaceExistingConnectorData = true,
                scannedAt = deletedAt,
            ),
            itemId = claimed.id,
            leaseOwner = checkNotNull(claimed.leaseOwner),
            attemptCount = claimed.attemptCount,
        )
        assertEquals(ConnectorScanCommitResult.LeaseLost, staleCommit)
        val items = memoryStore.snapshot().items.associateBy { item -> item.id }
        listOf("local-running", "local-pending").forEach { itemId ->
            assertEquals(IndexingQueueState.SKIPPED, items.getValue(itemId).state)
            assertEquals(IndexingSkipReason.SOURCE_DATA_DELETED, items.getValue(itemId).skipReason)
        }
        assertEquals(IndexingQueueState.PENDING, items.getValue("other-pending").state)
        assertTrue(memoryStore.loadSourceReferences("local_files").isEmpty())
        assertTrue(memoryStore.loadConnectorScanStatuses().none { it.connectorId == "local_files" })
    }

    @Test
    fun importReplacesEveryDerivedSectionAndFencesDeviceRuntimeState() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T06:20:00Z")
        val importedAt = requestedAt.plusSeconds(10)
        val memoryStore = store(databaseName, Clock.fixed(importedAt, ZoneOffset.UTC))
        memoryStore.saveConnectorScan(scanResult("destination-old"))
        val generation = enableAutomatic(memoryStore, requestedAt.minusSeconds(1))
        memoryStore.saveAutomaticIndexingRuntime(
            status = AutomaticIndexingRuntimeStatus(
                lastCheckedAt = requestedAt,
                lastCompletedAt = requestedAt,
                lastOutcome = AutomaticIndexingOutcome.COMPLETED,
                lastIndexedEventCount = 1,
            ),
            expectedGeneration = generation,
        )
        memoryStore.enqueue(
            listOf(
                manualTask("import-running", requestedAt),
                manualTask("import-pending", requestedAt.plusSeconds(1)),
            ),
        )
        val claimed = checkNotNull(
            memoryStore.claimNextAtomically(
                leaseOwner = "pre-import-worker",
                claimedAt = requestedAt.plusSeconds(2),
                leaseUntil = requestedAt.plusSeconds(60),
                trigger = IndexingTrigger.MANUAL,
            ),
        )
        val imported = completeImportedSnapshot("imported", connectorId = TEST_CONNECTOR_ID)
        val trustedConnectorIds = setOf(TEST_CONNECTOR_ID, "other.connector")

        val result = memoryStore.replaceDerivedDataFromImport(
            snapshot = imported,
            trustedConnectorIds = trustedConnectorIds,
            importedAt = importedAt,
        )

        assertEquals(1, result.sourceReferenceCount)
        assertEquals(1, result.derivedMemoryEventCount)
        assertEquals(1, result.citationCount)
        assertEquals(1, result.dailySummaryCount)
        assertEquals(1, result.placeClusterCount)
        assertEquals(1, result.appUsageSummaryCount)
        assertEquals(1, result.connectorScanStatusCount)
        assertEquals(trustedConnectorIds, result.connectorsRequiringReconsent)
        assertEquals(importedAt, result.completedAt)
        val stored = memoryStore.loadSnapshot()
        assertEquals(imported.sourceReferences, stored.sourceReferences)
        assertEquals(imported.derivedMemoryEvents, stored.derivedMemoryEvents)
        assertEquals(imported.citations, stored.citations)
        assertEquals(imported.dailySummaries, stored.dailySummaries)
        assertEquals(imported.placeClusters, stored.placeClusters)
        assertEquals(imported.appUsageSummaries, stored.appUsageSummaries)
        assertEquals(imported.connectorScanStatuses, stored.connectorScanStatuses)
        assertFalse(stored.derivedMemoryEvents.any { it.id.contains("destination-old") })

        assertTrue(memoryStore.snapshot().items.isEmpty())
        assertEquals(AutomaticIndexingRuntimeStatus(), memoryStore.loadAutomaticIndexingRuntime())
        val control = memoryStore.loadAutomaticIndexingControl()
        assertFalse(control.enabled)
        assertEquals(generation + 1L, control.generation)
        assertTrue(memoryStore.isConnectorReconsentRequired(TEST_CONNECTOR_ID))
        assertTrue(memoryStore.isConnectorReconsentRequired("other.connector"))
        assertTrue(store(databaseName).isConnectorReconsentRequired(TEST_CONNECTOR_ID))

        val staleCommit = memoryStore.commitClaimedConnectorScan(
            scanResult = scanResult("stale-after-import"),
            itemId = claimed.id,
            leaseOwner = checkNotNull(claimed.leaseOwner),
            attemptCount = claimed.attemptCount,
        )
        assertEquals(ConnectorScanCommitResult.LeaseLost, staleCommit)
        expectIllegalState { memoryStore.saveConnectorScan(scanResult("blocked")) }

        assertTrue(memoryStore.markConnectorReconsented(TEST_CONNECTOR_ID))
        assertFalse(memoryStore.markConnectorReconsented(TEST_CONNECTOR_ID))
        assertFalse(memoryStore.isConnectorReconsentRequired(TEST_CONNECTOR_ID))
        assertTrue(memoryStore.isConnectorReconsentRequired("other.connector"))
        memoryStore.saveConnectorScan(scanResult("after-reconsent"))
        assertTrue(memoryStore.loadDerivedMemoryEvents().any { it.id.endsWith(":after-reconsent") })
    }

    @Test
    fun invalidImportRollsBackBeforeReplacingDataOrRuntimeState() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T06:25:00Z")
        val memoryStore = store(databaseName)
        memoryStore.saveConnectorScan(scanResult("preserved"))
        memoryStore.enqueue(listOf(manualTask("preserved-task", requestedAt)))
        val invalid = completeImportedSnapshot("invalid").let { snapshot ->
            snapshot.copy(
                dailySummaries = snapshot.dailySummaries.map { summary ->
                    summary.copy(derivedMemoryEventIds = listOf("event:$TEST_CONNECTOR_ID:missing"))
                },
            )
        }

        expectIllegalArgument {
            memoryStore.replaceDerivedDataFromImport(
                snapshot = invalid,
                trustedConnectorIds = setOf(TEST_CONNECTOR_ID),
                importedAt = requestedAt.plusSeconds(1),
            )
        }

        assertEquals(listOf("event:$TEST_CONNECTOR_ID:preserved"), memoryStore.loadDerivedMemoryEvents().map { it.id })
        assertEquals(listOf("preserved-task"), memoryStore.snapshot().items.map { it.id })
        assertFalse(memoryStore.isConnectorReconsentRequired(TEST_CONNECTOR_ID))
    }

    @Test
    fun importRejectsDeviceLocalPointersWithoutMutatingDestination() = runBlocking {
        val databaseName = newDatabaseName()
        val memoryStore = store(databaseName)
        memoryStore.saveConnectorScan(scanResult("pointer-preserved"))
        val invalid = completeImportedSnapshot("pointer-invalid").let { snapshot ->
            snapshot.copy(
                sourceReferences = snapshot.sourceReferences.map { source ->
                    source.copy(localPointer = "content://provider/device-only/42")
                },
            )
        }

        expectIllegalArgument {
            memoryStore.replaceDerivedDataFromImport(
                snapshot = invalid,
                trustedConnectorIds = setOf(TEST_CONNECTOR_ID),
                importedAt = Instant.parse("2026-07-15T06:27:00Z"),
            )
        }

        assertEquals(
            listOf("event:$TEST_CONNECTOR_ID:pointer-preserved"),
            memoryStore.loadDerivedMemoryEvents().map { it.id },
        )
        assertFalse(memoryStore.isConnectorReconsentRequired(TEST_CONNECTOR_ID))
    }

    @Test
    fun importRejectsOversizedSectionsBeforeMutatingDestination() = runBlocking {
        val databaseName = newDatabaseName()
        val memoryStore = store(databaseName)
        memoryStore.saveConnectorScan(scanResult("bounds-preserved"))
        val oversized = completeImportedSnapshot("bounds-invalid").copy(
            sourceReferences = List(50_001) { index ->
                SourceReference(
                    id = "source:$TEST_CONNECTOR_ID:oversized-$index",
                    connectorId = TEST_CONNECTOR_ID,
                    sourceKind = SourceKind.LOCATION,
                )
            },
        )

        expectIllegalArgument {
            memoryStore.replaceDerivedDataFromImport(
                snapshot = oversized,
                trustedConnectorIds = setOf(TEST_CONNECTOR_ID),
                importedAt = Instant.parse("2026-07-15T06:28:00Z"),
            )
        }

        assertEquals(
            listOf("event:$TEST_CONNECTOR_ID:bounds-preserved"),
            memoryStore.loadDerivedMemoryEvents().map { it.id },
        )
        assertFalse(memoryStore.isConnectorReconsentRequired(TEST_CONNECTOR_ID))
    }

    @Test
    fun revokeDeleteSetsBarrierInTheSameTransaction() = runBlocking {
        val databaseName = newDatabaseName()
        val memoryStore = store(databaseName)
        memoryStore.saveConnectorScan(scanResult("before-revoke"))

        memoryStore.deleteConnectorData(TEST_CONNECTOR_ID, requireReconsent = true)

        assertTrue(memoryStore.loadDerivedMemoryEvents().isEmpty())
        assertTrue(memoryStore.isConnectorReconsentRequired(TEST_CONNECTOR_ID))
        expectIllegalState { memoryStore.saveConnectorScan(scanResult("blocked-after-revoke")) }
        assertTrue(memoryStore.markConnectorReconsented(TEST_CONNECTOR_ID))
        memoryStore.saveConnectorScan(scanResult("after-revoke-reconsent"))
        assertEquals(
            listOf("event:$TEST_CONNECTOR_ID:after-revoke-reconsent"),
            memoryStore.loadDerivedMemoryEvents().map { it.id },
        )
    }

    @Test
    fun notificationConsentLockSerializesInFlightWriteBeforeRevokeBarrier() = runBlocking {
        val databaseName = newDatabaseName()
        val memoryStore = store(databaseName)
        val connectorId = "notification"
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val revokeAttempted = CompletableDeferred<Unit>()

        val writer = async(Dispatchers.IO) {
            NotificationConsentCoordinator.withExclusiveAccess {
                writerEntered.complete(Unit)
                releaseWriter.await()
                memoryStore.saveConnectorScan(scanResult("notification-before-revoke", connectorId))
            }
        }
        writerEntered.await()
        val revoke = async(Dispatchers.IO) {
            revokeAttempted.complete(Unit)
            NotificationConsentCoordinator.withExclusiveAccess {
                memoryStore.deleteConnectorData(connectorId, requireReconsent = true)
            }
        }
        revokeAttempted.await()
        assertFalse(revoke.isCompleted)

        releaseWriter.complete(Unit)
        writer.await()
        revoke.await()

        assertTrue(memoryStore.loadDerivedMemoryEvents().isEmpty())
        assertTrue(memoryStore.isConnectorReconsentRequired(connectorId))
        var readOriginal = false
        NotificationConsentCoordinator.withExclusiveAccess {
            if (!memoryStore.isConnectorReconsentRequired(connectorId)) {
                readOriginal = true
                memoryStore.saveConnectorScan(scanResult("notification-stale", connectorId))
            }
        }
        assertFalse(readOriginal)
        assertTrue(memoryStore.loadDerivedMemoryEvents().isEmpty())
    }

    @Test
    fun reconsentBarrierRejectsAnOtherwiseLiveClaimedCommit() = runBlocking {
        val databaseName = newDatabaseName()
        val requestedAt = Instant.parse("2026-07-15T06:30:00Z")
        val memoryStore = store(
            databaseName,
            Clock.fixed(requestedAt.plusSeconds(5), ZoneOffset.UTC),
        )
        memoryStore.replaceDerivedDataFromImport(
            snapshot = completeImportedSnapshot("barrier-baseline"),
            trustedConnectorIds = setOf(TEST_CONNECTOR_ID),
            importedAt = requestedAt,
        )
        memoryStore.enqueue(listOf(manualTask("barrier-live-claim", requestedAt.plusSeconds(1))))
        val claimed = checkNotNull(
            memoryStore.claimNextAtomically(
                leaseOwner = "barrier-worker",
                claimedAt = requestedAt.plusSeconds(2),
                leaseUntil = requestedAt.plusSeconds(60),
                trigger = IndexingTrigger.MANUAL,
            ),
        )

        val result = memoryStore.commitClaimedConnectorScan(
            scanResult = scanResult("must-not-commit"),
            itemId = claimed.id,
            leaseOwner = checkNotNull(claimed.leaseOwner),
            attemptCount = claimed.attemptCount,
        )

        assertEquals(ConnectorScanCommitResult.LeaseLost, result)
        assertTrue(memoryStore.isConnectorReconsentRequired(TEST_CONNECTOR_ID))
        assertEquals(
            listOf("event:$TEST_CONNECTOR_ID:barrier-baseline"),
            memoryStore.loadDerivedMemoryEvents().map { it.id },
        )
        val unchangedClaim = checkNotNull(memoryStore.snapshot().items.single())
        assertEquals(IndexingQueueState.RUNNING, unchangedClaim.state)
        assertEquals("barrier-worker", unchangedClaim.leaseOwner)
    }

    @Test
    fun v5MigrationPurgesLegacyLocalFileRawIdentityOnly() = runBlocking {
        val databaseName = newDatabaseName()
        val memoryStore = store(databaseName)
        memoryStore.loadSnapshot()
        memoryStore.saveConnectorScan(scanResult("unrelated", connectorId = "other.connector"))
        val databasePath = context.getDatabasePath(databaseName).absolutePath
        val at = "2026-07-15T06:30:00Z"
        val sourceId = "source:local_files:legacy"
        val eventId = "event:local_files:legacy"
        val citationId = "citation:local_files:legacy"
        SQLiteDatabase.openOrCreateDatabase(databasePath, TEST_PASSPHRASE, null, null).use { database ->
            database.insertOrThrow(
                "source_references",
                null,
                ContentValues().apply {
                    put("id", sourceId)
                    put("connector_id", "local_files")
                    put("source_kind", SourceKind.LOCAL_FILE.name)
                    put("local_pointer", "content://provider/private/secret-note.md")
                    put("external_id_hash", "legacy-unkeyed-sha")
                    put("observed_at", at)
                    put("sensitivity", "HIGH")
                },
            )
            database.insertOrThrow(
                "derived_memory_events",
                null,
                ContentValues().apply {
                    put("id", eventId)
                    put("kind", DerivedMemoryEventKind.LOCAL_FILE_INDEX.name)
                    put("source_reference_ids", JSONArray().put(sourceId).toString())
                    put("summary", "Legacy derived summary")
                    put("keywords", JSONArray().toString())
                    put("labels", JSONArray().toString())
                    put("entities", JSONArray().toString())
                    put("confidence", "MEDIUM")
                    put("sensitivity", "HIGH")
                    put("citation_ids", JSONArray().put(citationId).toString())
                    put("created_at", at)
                },
            )
            database.insertOrThrow(
                "citations",
                null,
                ContentValues().apply {
                    put("id", citationId)
                    put("source_reference_id", sourceId)
                    put("derived_memory_event_id", eventId)
                    put("label", "Local file: secret-note.md")
                    put("observed_at", at)
                    put("confidence", "MEDIUM")
                },
            )
            database.insertOrThrow(
                "daily_summaries",
                null,
                ContentValues().apply {
                    put("id", "daily:legacy-local-files")
                    put("date", "2026-07-15")
                    put("summary", "Daily summary referencing legacy local file")
                    put("derived_memory_event_ids", JSONArray().put(eventId).toString())
                    put("place_cluster_ids", JSONArray().toString())
                    put("app_usage_summary_ids", JSONArray().toString())
                    put("confidence", "MEDIUM")
                    put("missing_sources", JSONArray().toString())
                },
            )
            database.insertOrThrow(
                "connector_scan_status",
                null,
                ContentValues().apply {
                    put("connector_id", "local_files")
                    put("processing_state", ProcessingState.COMPLETED.name)
                    put("missing_sources", JSONArray().toString())
                    put("scanned_at", at)
                },
            )
            database.execSQL("PRAGMA user_version = 5")
        }

        val migrated = store(databaseName).loadSnapshot()

        assertTrue(migrated.sourceReferences.none { it.connectorId == "local_files" })
        assertTrue(migrated.derivedMemoryEvents.none { it.id.startsWith("event:local_files:") })
        assertTrue(migrated.citations.none { it.id.startsWith("citation:local_files:") })
        assertTrue(migrated.dailySummaries.none { it.id == "daily:legacy-local-files" })
        assertTrue(migrated.connectorScanStatuses.none { it.connectorId == "local_files" })
        assertTrue(migrated.sourceReferences.any { it.connectorId == "other.connector" })
        SQLiteDatabase.openOrCreateDatabase(databasePath, TEST_PASSPHRASE, null, null).use { database ->
            val version = database.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals(SqlCipherLocalMemoryStore.CURRENT_SCHEMA_VERSION, version)
        }
        assertFalse(store(databaseName).isConnectorReconsentRequired("other.connector"))
    }

    @Test
    fun v7MigrationPurgesOpenConnectorRecordsAndFencesQueuedRescans() = runBlocking {
        val databaseName = newDatabaseName()
        val migratedAt = Instant.parse("2026-07-15T08:00:00Z")
        val memoryStore = store(databaseName, Clock.fixed(migratedAt, ZoneOffset.UTC))
        memoryStore.saveConnectorScan(scanResult("preserved"))
        val connectorKinds = linkedMapOf(
            "app_usage" to SourceKind.APP_USAGE,
            "calendar" to SourceKind.CALENDAR,
            "location" to SourceKind.LOCATION,
            "notification" to SourceKind.NOTIFICATION,
            "photos" to SourceKind.PHOTO,
        )
        memoryStore.enqueue(
            connectorKinds.keys.map { connectorId ->
                manualTask(
                    id = "legacy-$connectorId-rescan",
                    requestedAt = migratedAt.minusSeconds(60),
                    connectorId = connectorId,
                )
            },
        )
        val runningBeforeMigration = memoryStore.claimNextAtomically(
            leaseOwner = "legacy-worker",
            claimedAt = migratedAt.minusSeconds(30),
            leaseUntil = migratedAt.plusSeconds(3_600),
            trigger = IndexingTrigger.MANUAL,
        )
        assertNotNull(runningBeforeMigration)
        assertEquals(IndexingQueueState.RUNNING, runningBeforeMigration?.state)
        val databasePath = context.getDatabasePath(databaseName).absolutePath
        val at = migratedAt.minusSeconds(120).toString()
        val eventKinds = mapOf(
            "app_usage" to DerivedMemoryEventKind.APP_USAGE,
            "calendar" to DerivedMemoryEventKind.CALENDAR_EVENT,
            "location" to DerivedMemoryEventKind.PLACE_VISIT,
            "notification" to DerivedMemoryEventKind.PAYMENT,
            "photos" to DerivedMemoryEventKind.PHOTO_INDEX,
        )
        SQLiteDatabase.openOrCreateDatabase(databasePath, TEST_PASSPHRASE, null, null).use { database ->
            connectorKinds.forEach { (connectorId, sourceKind) ->
                val sourceId = "source:$connectorId:legacy"
                val eventId = "event:$connectorId:legacy"
                val citationId = "citation:$connectorId:legacy"
                database.insertOrThrow(
                    "source_references",
                    null,
                    ContentValues().apply {
                        put("id", sourceId)
                        put("connector_id", connectorId)
                        put("source_kind", sourceKind.name)
                        put("source_app_identifier", "raw.provider.identifier")
                        put("observed_at", at)
                        put("sensitivity", "HIGH")
                    },
                )
                database.insertOrThrow(
                    "derived_memory_events",
                    null,
                    ContentValues().apply {
                        put("id", eventId)
                        put("kind", eventKinds.getValue(connectorId).name)
                        put("source_reference_ids", JSONArray().put(sourceId).toString())
                        put("summary", "Raw $connectorId provider-derived text")
                        put("keywords", JSONArray().put("raw-provider-token").toString())
                        put("labels", JSONArray().put("raw-provider-label").toString())
                        put("entities", JSONArray().put("raw.provider.identifier").toString())
                        put("confidence", ConfidenceLevel.MEDIUM.name)
                        put("sensitivity", "HIGH")
                        put("citation_ids", JSONArray().put(citationId).toString())
                        put("created_at", at)
                    },
                )
                database.insertOrThrow(
                    "citations",
                    null,
                    ContentValues().apply {
                        put("id", citationId)
                        put("source_reference_id", sourceId)
                        put("derived_memory_event_id", eventId)
                        put("label", "Raw $connectorId provider citation")
                        put("observed_at", at)
                        put("confidence", ConfidenceLevel.MEDIUM.name)
                    },
                )
                database.insertOrThrow(
                    "connector_scan_status",
                    null,
                    ContentValues().apply {
                        put("connector_id", connectorId)
                        put("processing_state", ProcessingState.COMPLETED.name)
                        put("missing_sources", JSONArray().toString())
                        put("scanned_at", at)
                    },
                )
            }
            database.insertOrThrow(
                "place_clusters",
                null,
                ContentValues().apply {
                    put("id", "place-cluster:location:legacy")
                    put("label", "Raw precise place label")
                    put("region_label", "Raw provider region")
                    put("centroid_latitude", 37.5665)
                    put("centroid_longitude", 126.9780)
                    put("radius_meters", 15.0)
                    put("first_seen_at", at)
                    put("last_seen_at", at)
                    put("visit_count", 1)
                    put("source_reference_ids", JSONArray().put("source:location:legacy").toString())
                    put("confidence", ConfidenceLevel.MEDIUM.name)
                },
            )
            database.insertOrThrow(
                "app_usage_summaries",
                null,
                ContentValues().apply {
                    put("id", "app-usage:app_usage:legacy")
                    put("source_reference_ids", JSONArray().put("source:app_usage:legacy").toString())
                    put("date", "2026-07-15")
                    put("package_name", "raw.provider.identifier")
                    put("app_alias", "Raw private application alias")
                    put("category", AppUsageCategory.OTHER.name)
                    put("total_duration_minutes", 30)
                    put("launch_count", 1)
                    put("active_time_bucket_labels", JSONArray().put("raw-time-bucket").toString())
                    put("confidence", ConfidenceLevel.MEDIUM.name)
                },
            )
            database.insertOrThrow(
                "daily_summaries",
                null,
                ContentValues().apply {
                    put("id", "daily:legacy-open-connectors")
                    put("date", "2026-07-15")
                    put("summary", "Summary containing every legacy connector graph")
                    put(
                        "derived_memory_event_ids",
                        JSONArray(connectorKinds.keys.map { connectorId -> "event:$connectorId:legacy" }).toString(),
                    )
                    put("place_cluster_ids", JSONArray().put("place-cluster:location:legacy").toString())
                    put("app_usage_summary_ids", JSONArray().put("app-usage:app_usage:legacy").toString())
                    put("confidence", ConfidenceLevel.MEDIUM.name)
                    put("missing_sources", JSONArray().toString())
                },
            )
            database.execSQL("PRAGMA user_version = 7")
        }

        val migratedStore = store(databaseName, Clock.fixed(migratedAt, ZoneOffset.UTC))
        val migrated = migratedStore.loadSnapshot()

        assertTrue(migrated.sourceReferences.none { it.connectorId in connectorKinds.keys })
        assertTrue(
            migrated.derivedMemoryEvents.none { event ->
                connectorKinds.keys.any { connectorId -> event.id.startsWith("event:$connectorId:") }
            },
        )
        assertTrue(
            migrated.citations.none { citation ->
                connectorKinds.keys.any { connectorId -> citation.id.startsWith("citation:$connectorId:") }
            },
        )
        assertTrue(migrated.placeClusters.none { it.id == "place-cluster:location:legacy" })
        assertTrue(migrated.appUsageSummaries.none { it.id == "app-usage:app_usage:legacy" })
        assertTrue(migrated.dailySummaries.none { it.id == "daily:legacy-open-connectors" })
        assertTrue(migrated.connectorScanStatuses.none { it.connectorId in connectorKinds.keys })
        assertTrue(migrated.sourceReferences.any { it.connectorId == TEST_CONNECTOR_ID })
        val fencedItems = migratedStore.snapshot().items
            .filter { item -> item.task.connectorId in connectorKinds.keys }
        assertEquals(connectorKinds.size, fencedItems.size)
        fencedItems.forEach { item ->
                assertEquals(IndexingQueueState.SKIPPED, item.state)
                assertEquals(IndexingSkipReason.SOURCE_DATA_DELETED, item.skipReason)
                assertNull(item.leaseOwner)
                assertNull(item.leaseUntil)
            }
        SQLiteDatabase.openOrCreateDatabase(databasePath, TEST_PASSPHRASE, null, null).use { database ->
            val version = database.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals(SqlCipherLocalMemoryStore.CURRENT_SCHEMA_VERSION, version)
        }
    }

    @Test
    fun v6MigrationCreatesEmptyReconsentTableWithoutChangingDerivedData() = runBlocking {
        val databaseName = newDatabaseName()
        val memoryStore = store(databaseName)
        memoryStore.saveConnectorScan(scanResult("v6-preserved"))
        val databasePath = context.getDatabasePath(databaseName).absolutePath
        SQLiteDatabase.openOrCreateDatabase(databasePath, TEST_PASSPHRASE, null, null).use { database ->
            database.execSQL("DROP TABLE connector_reconsent")
            database.execSQL("PRAGMA user_version = 6")
        }

        val migrated = store(databaseName)

        assertEquals(
            listOf("event:$TEST_CONNECTOR_ID:v6-preserved"),
            migrated.loadDerivedMemoryEvents().map { it.id },
        )
        assertFalse(migrated.isConnectorReconsentRequired(TEST_CONNECTOR_ID))
        SQLiteDatabase.openOrCreateDatabase(databasePath, TEST_PASSPHRASE, null, null).use { database ->
            val version = database.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
            assertEquals(SqlCipherLocalMemoryStore.CURRENT_SCHEMA_VERSION, version)
            val tableExists = database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'connector_reconsent'",
                null,
            ).use { cursor -> cursor.moveToFirst() }
            assertTrue(tableExists)
        }
    }

    @Test
    fun v4ScanStatusMigrationDropsFreeFormTextAndPreservesKnownMeaning() = runBlocking {
        val databaseName = newDatabaseName()
        val memoryStore = store(databaseName)
        memoryStore.loadSnapshot()
        val databasePath = context.getDatabasePath(databaseName).absolutePath
        val scannedAt = "2026-07-15T07:00:00Z"
        val legacyRows = listOf(
            "calendar" to "No calendar events found in the indexed time window.",
            "unknown.connector" to "RAW_SENTINEL parser detail",
        )
        SQLiteDatabase.openOrCreateDatabase(databasePath, TEST_PASSPHRASE, null, null).use { database ->
            legacyRows.forEach { (connectorId, explanation) ->
                val encodedMissing = JSONArray().put(
                    JSONArray()
                        .put(MemoryCapability.HAS_TEXT.name)
                        .put(SourceAvailability.UNSUPPORTED.name)
                        .put(explanation)
                        .put(connectorId)
                        .toString(),
                ).toString()
                val values = ContentValues().apply {
                    put("connector_id", connectorId)
                    put("processing_state", ProcessingState.SKIPPED.name)
                    put("missing_sources", encodedMissing)
                    put("scanned_at", scannedAt)
                }
                database.insertWithOnConflict(
                    "connector_scan_status",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            database.execSQL("PRAGMA user_version = 4")
        }

        val statuses = store(databaseName).loadConnectorScanStatuses().associateBy { it.connectorId }

        assertEquals(
            ConnectorScanIssueCode.NO_CALENDAR_EVENTS_IN_RANGE,
            statuses.getValue("calendar").missingSources.single().issueCode,
        )
        assertEquals(
            ConnectorScanIssueCode.SOURCE_UNAVAILABLE,
            statuses.getValue("unknown.connector").missingSources.single().issueCode,
        )
        assertFalse(statuses.values.any { status ->
            status.missingSources.any { missing -> missing.explanation.contains("RAW_SENTINEL") }
        })
        SQLiteDatabase.openOrCreateDatabase(databasePath, TEST_PASSPHRASE, null, null).use { database ->
            val encodedStatuses = database.rawQuery(
                "SELECT missing_sources FROM connector_scan_status",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertFalse(encodedStatuses.any { it.contains("RAW_SENTINEL") })
            assertTrue(encodedStatuses.all { it.contains("connector-scan-issue-v1") })
        }
    }

    private fun completeImportedSnapshot(
        suffix: String,
        connectorId: String = TEST_CONNECTOR_ID,
    ): LocalMemorySnapshot {
        val observedAt = Instant.parse("2026-07-14T12:00:00Z")
        val sourceId = "source:$connectorId:$suffix"
        val eventId = "event:$connectorId:$suffix"
        val citationId = "citation:$connectorId:$suffix"
        val placeClusterId = "place-cluster:$connectorId:$suffix"
        val appUsageId = "app-usage:$connectorId:$suffix"
        return LocalMemorySnapshot(
            sourceReferences = listOf(
                SourceReference(
                    id = sourceId,
                    connectorId = connectorId,
                    sourceKind = SourceKind.LOCATION,
                    externalIdHash = "hash-$suffix",
                    observedAt = observedAt,
                    modifiedAt = observedAt,
                ),
            ),
            derivedMemoryEvents = listOf(
                DerivedMemoryEvent(
                    id = eventId,
                    kind = DerivedMemoryEventKind.PLACE_VISIT,
                    sourceReferenceIds = listOf(sourceId),
                    summary = "Imported derived event",
                    startedAt = observedAt,
                    keywords = listOf("imported"),
                    labels = listOf("location"),
                    confidence = ConfidenceLevel.MEDIUM,
                    citationIds = listOf(citationId),
                    createdAt = observedAt,
                ),
            ),
            citations = listOf(
                MemoryCitation(
                    id = citationId,
                    sourceReferenceId = sourceId,
                    derivedMemoryEventId = eventId,
                    label = "Imported location",
                    observedAt = observedAt,
                    confidence = ConfidenceLevel.MEDIUM,
                ),
            ),
            dailySummaries = listOf(
                DailyMemorySummary(
                    id = "daily:$suffix",
                    date = LocalDate.parse("2026-07-14"),
                    summary = "Imported daily summary",
                    derivedMemoryEventIds = listOf(eventId),
                    placeClusterIds = listOf(placeClusterId),
                    appUsageSummaryIds = listOf(appUsageId),
                    confidence = ConfidenceLevel.MEDIUM,
                ),
            ),
            placeClusters = listOf(
                PlaceCluster(
                    id = placeClusterId,
                    regionLabel = "Imported region",
                    centroidLatitude = 37.5,
                    centroidLongitude = 127.0,
                    firstSeenAt = observedAt,
                    lastSeenAt = observedAt,
                    visitCount = 1,
                    sourceReferenceIds = listOf(sourceId),
                    confidence = ConfidenceLevel.MEDIUM,
                ),
            ),
            appUsageSummaries = listOf(
                AppUsageSummary(
                    id = appUsageId,
                    sourceReferenceIds = listOf(sourceId),
                    date = LocalDate.parse("2026-07-14"),
                    packageName = "ai.grayin.imported",
                    category = AppUsageCategory.OTHER,
                    totalDurationMinutes = 15,
                    confidence = ConfidenceLevel.MEDIUM,
                ),
            ),
            connectorScanStatuses = listOf(
                ConnectorScanStatus(
                    connectorId = connectorId,
                    processingState = ProcessingState.COMPLETED,
                    missingSources = emptyList(),
                    scannedAt = observedAt,
                ),
            ),
        )
    }

    private fun manualTask(
        id: String,
        requestedAt: Instant,
        connectorId: String = TEST_CONNECTOR_ID,
    ): IndexingTask {
        return IndexingTask(
            id = id,
            connectorId = connectorId,
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

    private fun locationScanResult(
        suffix: String,
        observedAt: Instant,
        regionLabel: String,
    ): ConnectorScanResult {
        val sourceId = "source:location:$suffix"
        val eventId = "event:location:$suffix"
        val citationId = "citation:location:$suffix"
        return ConnectorScanResult(
            connectorId = "location",
            processingState = ProcessingState.COMPLETED,
            sourceReferences = listOf(
                SourceReference(
                    id = sourceId,
                    connectorId = "location",
                    sourceKind = SourceKind.LOCATION,
                    externalIdHash = suffix,
                    observedAt = observedAt,
                    modifiedAt = observedAt,
                    sensitivity = ai.grayin.core.model.SensitivityLevel.HIGH,
                ),
            ),
            derivedEvents = listOf(
                DerivedMemoryEvent(
                    id = eventId,
                    kind = DerivedMemoryEventKind.PLACE_VISIT,
                    sourceReferenceIds = listOf(sourceId),
                    summary = "Location visit indexed.",
                    startedAt = observedAt,
                    confidence = ConfidenceLevel.MEDIUM,
                    sensitivity = ai.grayin.core.model.SensitivityLevel.HIGH,
                    citationIds = listOf(citationId),
                    createdAt = observedAt,
                ),
            ),
            citations = listOf(
                MemoryCitation(
                    id = citationId,
                    sourceReferenceId = sourceId,
                    derivedMemoryEventId = eventId,
                    label = "Location sample",
                    observedAt = observedAt,
                    confidence = ConfidenceLevel.MEDIUM,
                ),
            ),
            placeClusters = listOf(
                PlaceCluster(
                    id = "place-cluster:location:0123456789abcdef0123456789abcdef",
                    regionLabel = regionLabel,
                    centroidLatitude = 37.566,
                    centroidLongitude = 126.978,
                    firstSeenAt = observedAt,
                    lastSeenAt = observedAt,
                    visitCount = 1,
                    sourceReferenceIds = listOf(sourceId),
                    confidence = ConfidenceLevel.MEDIUM,
                ),
            ),
            scannedAt = observedAt,
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

    private suspend fun expectIllegalState(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected an IllegalStateException.")
        } catch (_: IllegalStateException) {
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
