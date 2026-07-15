package ai.grayin.app

import ai.grayin.core.indexing.AutomaticIndexingOutcome
import ai.grayin.core.indexing.AutomaticIndexingRuntimeStatus
import ai.grayin.core.indexing.IndexingFailureCode
import ai.grayin.core.indexing.IndexingQueueItem
import ai.grayin.core.indexing.IndexingQueueSnapshot
import ai.grayin.core.indexing.IndexingQueueState
import ai.grayin.core.indexing.IndexingSkipReason
import ai.grayin.core.indexing.IndexingTask
import ai.grayin.core.indexing.IndexingTrigger
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexingStatusUiStateTest {
    @Test
    fun mapperUsesLocalizedSourceNamesAndBoundsRecentMetadata() {
        val pending = pendingItem("photos", IndexingTrigger.AUTOMATIC)
        val running = runningItem("calendar")
        val failed = runningItem("app_usage").copy(
            state = IndexingQueueState.FAILED,
            completedAt = COMPLETED_AT,
            leaseOwner = null,
            leaseUntil = null,
            failureCode = IndexingFailureCode.CONNECTOR_SCAN_FAILED,
        )
        val queue = IndexingQueueSnapshot(
            items = listOf(pending, running, failed),
            queueDepth = 1,
            runningConnectorIds = setOf("photos", "calendar"),
            lastCompletedAt = COMPLETED_AT,
            observedAt = STARTED_AT,
        )
        val runtime = AutomaticIndexingRuntimeStatus(
            lastCheckedAt = CHECKED_AT,
            lastStartedAt = STARTED_AT,
            lastCompletedAt = COMPLETED_AT,
            lastOutcome = AutomaticIndexingOutcome.FAILED,
            lastFailureCode = IndexingFailureCode.CONNECTOR_SCAN_FAILED,
        )

        val mapped = IndexingStatusUiMapper.map(
            queue = queue,
            runtime = runtime,
            sourceName = { id -> "name:$id" },
            recentLimit = 2,
        )

        assertEquals(1, mapped.queueDepth)
        assertEquals(listOf("name:calendar", "name:photos"), mapped.runningSourceNames)
        assertEquals(AutomaticIndexingOutcome.FAILED, mapped.lastAutomaticOutcome)
        assertEquals(IndexingFailureCode.CONNECTOR_SCAN_FAILED, mapped.lastAutomaticFailureCode)
        assertEquals(listOf("name:photos", "name:calendar"), mapped.recentTasks.map { it.sourceName })
    }

    @Test
    fun allStableReasonsHaveLocalizedPresentationWithoutEnumNames() {
        val languages = GrayinLanguageOption.entries.map(GrayinText::forOption)
        languages.forEach { strings ->
            IndexingSkipReason.entries.forEach { reason ->
                val status = IndexingStatusUiState.empty().copy(
                    lastAutomaticCheckedAt = CHECKED_AT,
                    lastAutomaticCompletedAt = COMPLETED_AT,
                    lastAutomaticOutcome = AutomaticIndexingOutcome.SKIPPED,
                    lastAutomaticSkipReason = reason,
                )
                val rows = strings.indexingStatusRows(status, ZoneOffset.UTC)
                assertTrue(rows.any { row -> reason.name !in row && row.contains("·") })
                assertFalse(rows.joinToString().contains(reason.name))
            }
            IndexingFailureCode.entries.forEach { code ->
                val task = RecentIndexingTaskUiState(
                    sourceName = "Calendar",
                    trigger = IndexingTrigger.AUTOMATIC,
                    state = RecentIndexingTaskState.FAILED,
                    completedAt = COMPLETED_AT,
                    skipReason = null,
                    failureCode = code,
                    indexedEventCount = 0,
                )
                val row = strings.recentIndexingTaskRow(task, ZoneOffset.UTC)
                assertTrue(row.isNotBlank())
                assertFalse(row.contains(code.name))
            }
        }
    }

    @Test
    fun allOutcomesTriggersAndPresentationStatesAreMappedWithoutInternalNames() {
        GrayinLanguageOption.entries.map(GrayinText::forOption).forEach { strings ->
            AutomaticIndexingOutcome.entries.forEach { outcome ->
                val rows = strings.indexingStatusRows(
                    IndexingStatusUiState.empty().copy(lastAutomaticOutcome = outcome),
                    ZoneOffset.UTC,
                )
                assertFalse(rows.joinToString().contains(outcome.name))
            }
            IndexingTrigger.entries.forEach { trigger ->
                val row = strings.recentIndexingTaskRow(
                    RecentIndexingTaskUiState(
                        sourceName = "Calendar",
                        trigger = trigger,
                        state = RecentIndexingTaskState.COMPLETED,
                        completedAt = COMPLETED_AT,
                        skipReason = null,
                        failureCode = null,
                        indexedEventCount = 1,
                    ),
                    ZoneOffset.UTC,
                )
                assertFalse(row.contains(trigger.name))
            }
            RecentIndexingTaskState.entries.forEach { state ->
                val row = strings.recentIndexingTaskRow(
                    RecentIndexingTaskUiState(
                        sourceName = "Calendar",
                        trigger = IndexingTrigger.MANUAL,
                        state = state,
                        completedAt = null,
                        skipReason = null,
                        failureCode = null,
                        indexedEventCount = 0,
                    ),
                    ZoneOffset.UTC,
                )
                assertFalse(row.contains(state.name))
            }
        }
    }

    @Test
    fun timestampsUseInjectedZoneAndEmptyStatusIsExplicit() {
        val english = GrayinText.forOption(GrayinLanguageOption.ENGLISH)
        val emptyRows = english.indexingStatusRows(IndexingStatusUiState.empty(), ZoneOffset.UTC)
        val completedRows = english.indexingStatusRows(
            IndexingStatusUiState.empty().copy(
                lastQueueCompletionAt = COMPLETED_AT,
                lastAutomaticCheckedAt = CHECKED_AT,
                lastAutomaticCompletedAt = COMPLETED_AT,
                lastAutomaticOutcome = AutomaticIndexingOutcome.COMPLETED,
                lastAutomaticIndexedEventCount = 4,
            ),
            ZoneOffset.UTC,
        )

        assertTrue(emptyRows.any { it.contains("No record") })
        assertTrue(completedRows.any { it.contains("2026-07-15 01:00") })
        assertTrue(completedRows.any { it.contains("2026-07-15 01:02") })
        assertTrue(completedRows.any { it.endsWith("4") })
    }

    @Test
    fun expiredRunningLeaseIsPresentedAsRecoveryPending() {
        val queue = IndexingQueueSnapshot(
            items = listOf(runningItem("calendar")),
            queueDepth = 0,
            runningConnectorIds = emptySet(),
            lastCompletedAt = null,
            observedAt = STARTED_AT.plusSeconds(61),
        )

        val mapped = IndexingStatusUiMapper.map(
            queue = queue,
            runtime = AutomaticIndexingRuntimeStatus(),
            sourceName = { it },
        )

        assertEquals(RecentIndexingTaskState.RECOVERY_PENDING, mapped.recentTasks.single().state)
        val english = GrayinText.forOption(GrayinLanguageOption.ENGLISH)
        assertTrue(english.recentIndexingTaskRow(mapped.recentTasks.single(), ZoneOffset.UTC).contains("Recovery pending"))
    }

    private fun pendingItem(connectorId: String, trigger: IndexingTrigger): IndexingQueueItem {
        return IndexingQueueItem(
            task = IndexingTask(
                id = "task:$connectorId:$trigger",
                connectorId = connectorId,
                trigger = trigger,
                requestedAt = CHECKED_AT,
                automaticWindowKey = if (trigger == IndexingTrigger.AUTOMATIC) "window" else null,
                automaticGeneration = if (trigger == IndexingTrigger.AUTOMATIC) 1L else null,
            ),
        )
    }

    private fun runningItem(connectorId: String): IndexingQueueItem {
        return pendingItem(connectorId, IndexingTrigger.MANUAL).copy(
            state = IndexingQueueState.RUNNING,
            attemptCount = 1,
            lastAttemptAt = STARTED_AT,
            startedAt = STARTED_AT,
            leaseOwner = "worker",
            leaseUntil = STARTED_AT.plusSeconds(60),
        )
    }

    private companion object {
        val CHECKED_AT: Instant = Instant.parse("2026-07-15T01:00:00Z")
        val STARTED_AT: Instant = CHECKED_AT.plusSeconds(1)
        val COMPLETED_AT: Instant = CHECKED_AT.plusSeconds(120)
    }
}
