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
            normalized.containsAny(
                "around this time last year",
                "this time last year",
                "작년 이맘때",
                "지난해 이맘때",
                "去年の今頃",
                "昨年の今頃",
            ) -> {
                val center = today.minusYears(1)
                range("around this time last year", center.minusDays(7), center.plusDays(8), today)
            }

            normalized.containsAny("yesterday", "어제", "昨日") -> {
                val day = today.minusDays(1)
                range(normalized.matchedLabel("yesterday", "어제", "昨日"), day, day.plusDays(1), today)
            }

            normalized.containsAny("today", "오늘", "今日") -> {
                range(normalized.matchedLabel("today", "오늘", "今日"), today, today.plusDays(1), today)
            }

            normalized.containsAny("tomorrow", "내일", "明日") -> {
                range(normalized.matchedLabel("tomorrow", "내일", "明日"), today.plusDays(1), today.plusDays(2), today)
            }

            normalized.containsAny("next week", "다음 주", "다음주", "来週") -> {
                val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val nextWeek = thisWeek.plusWeeks(1)
                range(
                    normalized.matchedLabel("next week", "다음 주", "다음주", "来週"),
                    nextWeek,
                    nextWeek.plusWeeks(1),
                    today,
                )
            }

            normalized.containsAny("last week", "지난 주", "지난주", "先週") -> {
                val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val lastWeek = thisWeek.minusWeeks(1)
                range(normalized.matchedLabel("last week", "지난 주", "지난주", "先週"), lastWeek, thisWeek, today)
            }

            normalized.containsAny("this week", "이번 주", "이번주", "今週") -> {
                val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                range(
                    normalized.matchedLabel("this week", "이번 주", "이번주", "今週"),
                    thisWeek,
                    thisWeek.plusWeeks(1),
                    today,
                )
            }

            normalized.containsAny("next month", "다음 달", "다음달", "来月") -> {
                val nextMonth = today.withDayOfMonth(1).plusMonths(1)
                range(
                    normalized.matchedLabel("next month", "다음 달", "다음달", "来月"),
                    nextMonth,
                    nextMonth.plusMonths(1),
                    today,
                )
            }

            normalized.containsAny("last month", "지난 달", "지난달", "先月") -> {
                val thisMonth = today.withDayOfMonth(1)
                val lastMonth = thisMonth.minusMonths(1)
                range(normalized.matchedLabel("last month", "지난 달", "지난달", "先月"), lastMonth, thisMonth, today)
            }

            else -> null
        }
    }

    private fun String.containsAny(vararg candidates: String): Boolean {
        return candidates.any(::contains)
    }

    private fun String.matchedLabel(vararg candidates: String): String {
        return candidates.firstOrNull(::contains) ?: candidates.first()
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
