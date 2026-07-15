package ai.grayin.core.indexing

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AutomaticIndexingRunPlanning {
    fun skipReasonFor(decisionReason: AutomaticIndexingDecisionReason): IndexingSkipReason {
        return when (decisionReason) {
            AutomaticIndexingDecisionReason.ALLOWED -> {
                error("An allowed decision does not have a skip reason.")
            }

            AutomaticIndexingDecisionReason.INVALID_LOW_USAGE_WINDOW -> {
                IndexingSkipReason.INVALID_LOW_USAGE_WINDOW
            }

            AutomaticIndexingDecisionReason.REQUIRES_CHARGING -> IndexingSkipReason.NOT_CHARGING
            AutomaticIndexingDecisionReason.OUTSIDE_LOW_USAGE_WINDOW -> {
                IndexingSkipReason.OUTSIDE_LOW_USAGE_WINDOW
            }

            AutomaticIndexingDecisionReason.BATTERY_LEVEL_UNKNOWN -> {
                IndexingSkipReason.BATTERY_LEVEL_UNKNOWN
            }

            AutomaticIndexingDecisionReason.BATTERY_BELOW_MINIMUM -> {
                IndexingSkipReason.BATTERY_BELOW_MINIMUM
            }

            AutomaticIndexingDecisionReason.THERMAL_STATE_HOT -> IndexingSkipReason.THERMAL_STATE_HOT
            AutomaticIndexingDecisionReason.THERMAL_STATE_CRITICAL -> {
                IndexingSkipReason.THERMAL_STATE_CRITICAL
            }
        }
    }

    fun windowKey(
        instant: Instant,
        zoneId: ZoneId,
        window: LowUsageWindow,
    ): String {
        require(window.isValid) { "An automatic indexing window must have different endpoints." }
        val localDateTime = LocalDateTime.ofInstant(instant, zoneId)
        require(window.includes(localDateTime.toLocalTime())) {
            "The automatic indexing instant must be inside the low-usage window."
        }
        val startDate = if (
            window.start > window.end && localDateTime.toLocalTime() < window.end
        ) {
            localDateTime.toLocalDate().minusDays(1)
        } else {
            localDateTime.toLocalDate()
        }
        return buildString {
            append(startDate)
            append(':')
            append(window.start.stableText())
            append('-')
            append(window.end.stableText())
            append(':')
            append(zoneId.id)
        }
    }

    fun summarize(
        checkedAt: Instant,
        startedAt: Instant,
        completedAt: Instant,
        enqueuedItems: List<IndexingQueueItem>,
        processedItems: List<IndexingQueueItem>,
    ): AutomaticIndexingRuntimeStatus {
        require(!startedAt.isBefore(checkedAt)) { "Automatic run start must not precede its check." }
        require(!completedAt.isBefore(startedAt)) { "Automatic run completion must not precede its start." }

        val itemsById = LinkedHashMap<String, IndexingQueueItem>()
        enqueuedItems.forEach { item -> itemsById[item.id] = item }
        processedItems.forEach { item ->
            if (item.id in itemsById) itemsById[item.id] = item
        }
        val items = itemsById.values.toList()
        val live = items.any { item ->
            item.state == IndexingQueueState.PENDING || item.state == IndexingQueueState.RUNNING
        }
        val failed = items.firstOrNull { item -> item.state == IndexingQueueState.FAILED }
        val completed = items.any { item -> item.state == IndexingQueueState.COMPLETED }
        val skipped = items.firstOrNull { item -> item.state == IndexingQueueState.SKIPPED }
        val outcome = when {
            live -> AutomaticIndexingOutcome.RUNNING
            failed != null -> AutomaticIndexingOutcome.FAILED
            completed -> AutomaticIndexingOutcome.COMPLETED
            else -> AutomaticIndexingOutcome.SKIPPED
        }
        return AutomaticIndexingRuntimeStatus(
            lastCheckedAt = checkedAt,
            lastStartedAt = startedAt,
            lastCompletedAt = completedAt.takeUnless { live },
            lastOutcome = outcome,
            lastSkipReason = if (outcome == AutomaticIndexingOutcome.SKIPPED) {
                skipped?.skipReason ?: IndexingSkipReason.NO_INDEXABLE_DATA
            } else {
                null
            },
            lastFailureCode = if (outcome == AutomaticIndexingOutcome.FAILED) {
                failed?.failureCode ?: IndexingFailureCode.INTERNAL_ERROR
            } else {
                null
            },
            lastIndexedEventCount = items.sumOf { item -> item.indexedEventCount },
        )
    }

    private fun LocalTime.stableText(): String = format(WINDOW_TIME_FORMAT)

    private val WINDOW_TIME_FORMAT = DateTimeFormatter.ofPattern("HHmm")
}
