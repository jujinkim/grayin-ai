package ai.grayin.core.indexing

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexingQueueTest {
    @Test
    fun `date range is either absent or strictly increasing`() {
        val from = Instant.parse("2026-07-14T00:00:00Z")
        val until = Instant.parse("2026-07-15T00:00:00Z")

        assertTrue(IndexingQueueValidator.isValidDateRange(null, null))
        assertTrue(IndexingQueueValidator.isValidDateRange(from, until))
        assertFalse(IndexingQueueValidator.isValidDateRange(from, null))
        assertFalse(IndexingQueueValidator.isValidDateRange(null, until))
        assertFalse(IndexingQueueValidator.isValidDateRange(from, from))
        assertFalse(IndexingQueueValidator.isValidDateRange(until, from))
        assertThrows(IllegalArgumentException::class.java) {
            IndexingQueueValidator.requireValidDateRange(until, from)
        }
    }

    @Test
    fun `only pending and running states have valid outgoing transitions`() {
        val validTransitions = setOf(
            IndexingQueueState.PENDING to IndexingQueueState.RUNNING,
            IndexingQueueState.RUNNING to IndexingQueueState.PENDING,
            IndexingQueueState.RUNNING to IndexingQueueState.COMPLETED,
            IndexingQueueState.RUNNING to IndexingQueueState.SKIPPED,
            IndexingQueueState.RUNNING to IndexingQueueState.FAILED,
        )

        IndexingQueueState.entries.forEach { from ->
            IndexingQueueState.entries.forEach { to ->
                assertEquals(
                    "$from -> $to",
                    from to to in validTransitions,
                    IndexingQueueValidator.canTransition(from, to),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            IndexingQueueValidator.requireValidTransition(
                IndexingQueueState.COMPLETED,
                IndexingQueueState.PENDING,
            )
        }
    }

    @Test
    fun `automatic tasks require a stable window key and manual tasks reject it`() {
        val requestedAt = Instant.parse("2026-07-15T00:00:00Z")

        IndexingTask(
            id = "task-calendar-1",
            connectorId = "calendar",
            trigger = IndexingTrigger.AUTOMATIC,
            requestedAt = requestedAt,
            automaticWindowKey = "2026-07-15@Asia-Seoul",
        )
        assertThrows(IllegalArgumentException::class.java) {
            IndexingTask(
                id = "task-calendar-2",
                connectorId = "calendar",
                trigger = IndexingTrigger.AUTOMATIC,
                requestedAt = requestedAt,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            IndexingTask(
                id = "task-calendar-3",
                connectorId = "calendar",
                trigger = IndexingTrigger.MANUAL,
                requestedAt = requestedAt,
                automaticWindowKey = "unexpected",
            )
        }
    }

    @Test
    fun `queue item exposes only stable failure metadata`() {
        val fieldNames = IndexingQueueItem::class.java.declaredFields.map { it.name }

        assertTrue("failureCode" in fieldNames)
        assertFalse("failureExplanation" in fieldNames)
        assertEquals(
            IndexingFailureCode::class.java,
            IndexingQueueItem::class.java.getDeclaredField("failureCode").type,
        )
    }
}
