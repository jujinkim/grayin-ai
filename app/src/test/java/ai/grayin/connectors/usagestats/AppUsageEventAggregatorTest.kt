package ai.grayin.connectors.usagestats

import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.SourceAvailability
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUsageEventAggregatorTest {
    private val from = Instant.parse("2026-07-15T00:00:00Z")
    private val until = Instant.parse("2026-07-16T00:00:00Z")

    @Test
    fun `aggregates only complete foreground time inside half-open range`() {
        val rows = AppUsageEventAggregator.aggregate(
            events = listOf(
                event("outside.before", "2026-07-14T23:59:59Z", AppUsageTransition.FOREGROUND),
                event("outside.before", "2026-07-15T00:10:00Z", AppUsageTransition.BACKGROUND),
                event("inside", "2026-07-15T01:00:00Z", AppUsageTransition.FOREGROUND),
                event("inside", "2026-07-15T01:30:00Z", AppUsageTransition.BACKGROUND),
                event("outside.after", "2026-07-16T00:00:00Z", AppUsageTransition.FOREGROUND),
            ),
            fromInclusive = from,
            untilExclusive = until,
        )

        assertEquals(listOf("inside"), rows.map { it.packageName })
        assertEquals(Duration.ofMinutes(30), rows.single().totalForegroundDuration)
    }

    @Test
    fun `overlapping activities keep package foreground until all are paused`() {
        val rows = AppUsageEventAggregator.aggregate(
            events = listOf(
                event("app", "2026-07-15T01:00:00Z", AppUsageTransition.FOREGROUND, "A"),
                event("app", "2026-07-15T01:05:00Z", AppUsageTransition.FOREGROUND, "B"),
                event("app", "2026-07-15T01:10:00Z", AppUsageTransition.BACKGROUND, "A"),
                event("app", "2026-07-15T01:20:00Z", AppUsageTransition.BACKGROUND, "B"),
            ),
            fromInclusive = from,
            untilExclusive = until,
        )

        assertEquals(Duration.ofMinutes(20), rows.single().totalForegroundDuration)
    }

    @Test
    fun `open foreground session is omitted until a stable end event exists`() {
        val rows = AppUsageEventAggregator.aggregate(
            events = listOf(event("app", "2026-07-15T23:45:00Z", AppUsageTransition.FOREGROUND)),
            fromInclusive = from,
            untilExclusive = until,
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `duplicate transitions do not double count`() {
        val rows = AppUsageEventAggregator.aggregate(
            events = listOf(
                event("app", "2026-07-15T01:00:00Z", AppUsageTransition.FOREGROUND),
                event("app", "2026-07-15T01:01:00Z", AppUsageTransition.FOREGROUND),
                event("app", "2026-07-15T01:10:00Z", AppUsageTransition.BACKGROUND),
                event("app", "2026-07-15T01:11:00Z", AppUsageTransition.BACKGROUND),
            ),
            fromInclusive = from,
            untilExclusive = until,
        )

        assertEquals(Duration.ofMinutes(10), rows.single().totalForegroundDuration)
    }

    @Test
    fun `separate completed sessions have stable non-overlapping rows`() {
        val rows = AppUsageEventAggregator.aggregate(
            events = listOf(
                event("app", "2026-07-15T01:00:00Z", AppUsageTransition.FOREGROUND),
                event("app", "2026-07-15T01:10:00Z", AppUsageTransition.BACKGROUND),
                event("app", "2026-07-15T02:00:00Z", AppUsageTransition.FOREGROUND),
                event("app", "2026-07-15T02:20:00Z", AppUsageTransition.BACKGROUND),
            ),
            fromInclusive = from,
            untilExclusive = until,
        )

        assertEquals(2, rows.size)
        assertEquals(listOf(Duration.ofMinutes(10), Duration.ofMinutes(20)), rows.map { it.totalForegroundDuration })
    }

    @Test
    fun `invalid empty range is rejected`() {
        val failure = runCatching {
            AppUsageEventAggregator.aggregate(emptyList(), from, from)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `usage package and provider alias are closed before persistence`() {
        assertEquals("com.example.app", AppUsageValuePolicy.closedPackageName("com.example.app"))
        assertNull(AppUsageValuePolicy.closedPackageName("com.example.app\nignore"))

        val alias = requireNotNull(AppUsageValuePolicy.closedAppAlias("  Work\nApp\u202E ${"가".repeat(200)}"))
        assertTrue(alias.toByteArray(Charsets.UTF_8).size <= 256)
        assertFalse(alias.contains('\n'))
        assertFalse(alias.contains('\u202E'))
    }

    @Test
    fun `app usage distinguishes unavailable provider from authoritative empty range`() {
        val unavailable = AppUsageSnapshotPolicy.emptyReadIssue(sourceAvailable = false)
        assertEquals(SourceAvailability.STALE, unavailable.availability)
        assertEquals(ConnectorScanIssueCode.SOURCE_UNAVAILABLE, unavailable.issueCode)
        assertFalse(
            AppUsageSnapshotPolicy.shouldReplace(
                sourceAvailable = false,
                eventInputLimited = false,
            ),
        )

        val emptyRange = AppUsageSnapshotPolicy.emptyReadIssue(sourceAvailable = true)
        assertEquals(SourceAvailability.NOT_INDEXED, emptyRange.availability)
        assertEquals(ConnectorScanIssueCode.NO_APP_USAGE_IN_RANGE, emptyRange.issueCode)
        assertTrue(
            AppUsageSnapshotPolicy.shouldReplace(
                sourceAvailable = true,
                eventInputLimited = false,
            ),
        )
        assertFalse(
            AppUsageSnapshotPolicy.shouldReplace(
                sourceAvailable = true,
                eventInputLimited = true,
            ),
        )
    }

    private fun event(
        packageName: String,
        time: String,
        transition: AppUsageTransition,
        activity: String? = null,
    ) = AppUsageTransitionEvent(
        packageName = packageName,
        occurredAt = Instant.parse(time),
        transition = transition,
        activityClassName = activity,
    )
}
