package ai.grayin.core.retrieval

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class ApproximateTimeRange(
    val label: String,
    val startInclusive: Instant,
    val endExclusive: Instant,
    val isFuture: Boolean,
)

class ApproximateTimeParser(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun parse(query: String, now: Instant = Instant.now()): ApproximateTimeRange? {
        val normalized = query.lowercase()
        val today = now.atZone(zoneId).toLocalDate()

        return when {
            normalized.contains("around this time last year") ||
                normalized.contains("this time last year") -> {
                val center = today.minusYears(1)
                range("around this time last year", center.minusDays(7), center.plusDays(8), today)
            }

            normalized.contains("yesterday") -> {
                val day = today.minusDays(1)
                range("yesterday", day, day.plusDays(1), today)
            }

            normalized.contains("today") -> {
                range("today", today, today.plusDays(1), today)
            }

            normalized.contains("tomorrow") -> {
                range("tomorrow", today.plusDays(1), today.plusDays(2), today)
            }

            normalized.contains("next week") -> {
                val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val nextWeek = thisWeek.plusWeeks(1)
                range("next week", nextWeek, nextWeek.plusWeeks(1), today)
            }

            normalized.contains("last week") -> {
                val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val lastWeek = thisWeek.minusWeeks(1)
                range("last week", lastWeek, thisWeek, today)
            }

            normalized.contains("this week") -> {
                val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                range("this week", thisWeek, thisWeek.plusWeeks(1), today)
            }

            normalized.contains("next month") -> {
                val nextMonth = today.withDayOfMonth(1).plusMonths(1)
                range("next month", nextMonth, nextMonth.plusMonths(1), today)
            }

            normalized.contains("last month") -> {
                val thisMonth = today.withDayOfMonth(1)
                val lastMonth = thisMonth.minusMonths(1)
                range("last month", lastMonth, thisMonth, today)
            }

            else -> null
        }
    }

    private fun range(
        label: String,
        startDate: LocalDate,
        endDate: LocalDate,
        today: LocalDate,
    ): ApproximateTimeRange {
        return ApproximateTimeRange(
            label = label,
            startInclusive = startDate.atStartOfDay(zoneId).toInstant(),
            endExclusive = endDate.atStartOfDay(zoneId).toInstant(),
            isFuture = !startDate.isBefore(today),
        )
    }
}

