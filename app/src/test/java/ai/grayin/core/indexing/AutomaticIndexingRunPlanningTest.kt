package ai.grayin.core.indexing

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AutomaticIndexingRunPlanningTest {
    @Test
    fun `decision reasons map to stable queue skip reasons`() {
        val expected = mapOf(
            AutomaticIndexingDecisionReason.INVALID_LOW_USAGE_WINDOW to IndexingSkipReason.INVALID_LOW_USAGE_WINDOW,
            AutomaticIndexingDecisionReason.REQUIRES_CHARGING to IndexingSkipReason.NOT_CHARGING,
            AutomaticIndexingDecisionReason.OUTSIDE_LOW_USAGE_WINDOW to IndexingSkipReason.OUTSIDE_LOW_USAGE_WINDOW,
            AutomaticIndexingDecisionReason.BATTERY_LEVEL_UNKNOWN to IndexingSkipReason.BATTERY_LEVEL_UNKNOWN,
            AutomaticIndexingDecisionReason.BATTERY_BELOW_MINIMUM to IndexingSkipReason.BATTERY_BELOW_MINIMUM,
            AutomaticIndexingDecisionReason.THERMAL_STATE_HOT to IndexingSkipReason.THERMAL_STATE_HOT,
            AutomaticIndexingDecisionReason.THERMAL_STATE_CRITICAL to IndexingSkipReason.THERMAL_STATE_CRITICAL,
        )

        assertEquals(
            expected,
            expected.keys.associateWith(AutomaticIndexingRunPlanning::skipReasonFor),
        )
        try {
            AutomaticIndexingRunPlanning.skipReasonFor(AutomaticIndexingDecisionReason.ALLOWED)
            fail("Allowed decisions must not produce a skip reason.")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `cross midnight window uses the date on which the window started`() {
        val zone = ZoneId.of("Asia/Seoul")
        val window = LowUsageWindow(LocalTime.of(22, 0), LocalTime.of(2, 0))

        val beforeMidnight = AutomaticIndexingRunPlanning.windowKey(
            Instant.parse("2026-07-15T14:00:00Z"),
            zone,
            window,
        )
        val afterMidnight = AutomaticIndexingRunPlanning.windowKey(
            Instant.parse("2026-07-15T16:00:00Z"),
            zone,
            window,
        )

        assertEquals("2026-07-15:2200-0200:Asia/Seoul", beforeMidnight)
        assertEquals(beforeMidnight, afterMidnight)
    }

    @Test
    fun `processed task replaces its enqueued state in summary`() {
        val pending = item("calendar", IndexingQueueState.PENDING)
        val completed = runningItem("calendar").copy(
            state = IndexingQueueState.COMPLETED,
            completedAt = COMPLETED_AT,
            leaseOwner = null,
            leaseUntil = null,
            indexedEventCount = 3,
        )

        val status = AutomaticIndexingRunPlanning.summarize(
            checkedAt = CHECKED_AT,
            startedAt = STARTED_AT,
            completedAt = COMPLETED_AT,
            enqueuedItems = listOf(pending),
            processedItems = listOf(completed),
        )

        assertEquals(AutomaticIndexingOutcome.COMPLETED, status.lastOutcome)
        assertEquals(3, status.lastIndexedEventCount)
        assertEquals(COMPLETED_AT, status.lastCompletedAt)
    }

    @Test
    fun `live task takes precedence and leaves run completion unset`() {
        val failed = runningItem("photos").copy(
            state = IndexingQueueState.FAILED,
            completedAt = COMPLETED_AT,
            leaseOwner = null,
            leaseUntil = null,
            failureCode = IndexingFailureCode.CONNECTOR_SCAN_FAILED,
        )

        val status = AutomaticIndexingRunPlanning.summarize(
            checkedAt = CHECKED_AT,
            startedAt = STARTED_AT,
            completedAt = COMPLETED_AT,
            enqueuedItems = listOf(item("calendar", IndexingQueueState.PENDING), failed),
            processedItems = emptyList(),
        )

        assertEquals(AutomaticIndexingOutcome.RUNNING, status.lastOutcome)
        assertEquals(null, status.lastCompletedAt)
        assertEquals(null, status.lastFailureCode)
    }

    @Test
    fun `terminal precedence is failed then completed then skipped`() {
        val skipped = runningItem("calendar").copy(
            state = IndexingQueueState.SKIPPED,
            completedAt = COMPLETED_AT,
            leaseOwner = null,
            leaseUntil = null,
            skipReason = IndexingSkipReason.NO_INDEXABLE_DATA,
        )
        val completed = runningItem("photos").copy(
            state = IndexingQueueState.COMPLETED,
            completedAt = COMPLETED_AT,
            leaseOwner = null,
            leaseUntil = null,
            indexedEventCount = 2,
        )
        val failed = runningItem("app_usage").copy(
            state = IndexingQueueState.FAILED,
            completedAt = COMPLETED_AT,
            leaseOwner = null,
            leaseUntil = null,
            failureCode = IndexingFailureCode.STORE_WRITE_FAILED,
        )

        val failedStatus = summary(listOf(skipped, completed, failed))
        val completedStatus = summary(listOf(skipped, completed))
        val skippedStatus = summary(listOf(skipped))
        val emptyStatus = summary(emptyList())

        assertEquals(AutomaticIndexingOutcome.FAILED, failedStatus.lastOutcome)
        assertEquals(IndexingFailureCode.STORE_WRITE_FAILED, failedStatus.lastFailureCode)
        assertEquals(2, failedStatus.lastIndexedEventCount)
        assertEquals(AutomaticIndexingOutcome.COMPLETED, completedStatus.lastOutcome)
        assertEquals(AutomaticIndexingOutcome.SKIPPED, skippedStatus.lastOutcome)
        assertEquals(IndexingSkipReason.NO_INDEXABLE_DATA, skippedStatus.lastSkipReason)
        assertEquals(IndexingSkipReason.NO_INDEXABLE_DATA, emptyStatus.lastSkipReason)
    }

    private fun summary(items: List<IndexingQueueItem>): AutomaticIndexingRuntimeStatus {
        return AutomaticIndexingRunPlanning.summarize(
            checkedAt = CHECKED_AT,
            startedAt = STARTED_AT,
            completedAt = COMPLETED_AT,
            enqueuedItems = items,
            processedItems = emptyList(),
        )
    }

    private fun item(connectorId: String, state: IndexingQueueState): IndexingQueueItem {
        require(state == IndexingQueueState.PENDING)
        return IndexingQueueItem(
            task = IndexingTask(
                id = "task:$connectorId",
                connectorId = connectorId,
                trigger = IndexingTrigger.AUTOMATIC,
                requestedAt = CHECKED_AT,
                automaticWindowKey = "window",
                automaticGeneration = 1L,
            ),
        )
    }

    private fun runningItem(connectorId: String): IndexingQueueItem {
        return IndexingQueueItem(
            task = IndexingTask(
                id = "task:$connectorId",
                connectorId = connectorId,
                trigger = IndexingTrigger.AUTOMATIC,
                requestedAt = CHECKED_AT,
                automaticWindowKey = "window",
                automaticGeneration = 1L,
            ),
            state = IndexingQueueState.RUNNING,
            attemptCount = 1,
            lastAttemptAt = STARTED_AT,
            startedAt = STARTED_AT,
            leaseOwner = "worker",
            leaseUntil = STARTED_AT.plusSeconds(60),
        )
    }

    private companion object {
        val CHECKED_AT: Instant = Instant.parse("2026-07-15T00:00:00Z")
        val STARTED_AT: Instant = CHECKED_AT.plusSeconds(1)
        val COMPLETED_AT: Instant = STARTED_AT.plusSeconds(2)
    }
}
