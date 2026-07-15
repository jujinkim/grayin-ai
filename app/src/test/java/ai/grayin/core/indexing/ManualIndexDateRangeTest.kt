package ai.grayin.core.indexing

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManualIndexDateRangeTest {
    @Test
    fun inclusiveLocalDatesBecomeHalfOpenUtcInstants() {
        val command = ManualIndexDateRange(
            startDateInclusive = LocalDate.of(2026, 7, 1),
            endDateInclusive = LocalDate.of(2026, 7, 3),
        ).toCommand(
            connectorId = "calendar",
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), command.from)
        assertEquals(Instant.parse("2026-07-04T00:00:00Z"), command.until)
        assertEquals("calendar", command.connectorId)
    }

    @Test
    fun springDaylightSavingBoundaryUsesLocalMidnights() {
        val command = ManualIndexDateRange(
            startDateInclusive = LocalDate.of(2024, 3, 10),
            endDateInclusive = LocalDate.of(2024, 3, 10),
        ).toCommand(
            connectorId = "photos",
            zoneId = ZoneId.of("America/New_York"),
        )

        assertEquals(Instant.parse("2024-03-10T05:00:00Z"), command.from)
        assertEquals(Instant.parse("2024-03-11T04:00:00Z"), command.until)
        assertEquals(Duration.ofHours(23), Duration.between(command.from, command.until))
    }

    @Test
    fun fallDaylightSavingBoundaryUsesLocalMidnights() {
        val command = ManualIndexDateRange(
            startDateInclusive = LocalDate.of(2024, 11, 3),
            endDateInclusive = LocalDate.of(2024, 11, 3),
        ).toCommand(
            connectorId = "app_usage",
            zoneId = ZoneId.of("America/New_York"),
        )

        assertEquals(Duration.ofHours(25), Duration.between(command.from, command.until))
    }

    @Test
    fun endDateBeforeStartDateIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualIndexDateRange(
                startDateInclusive = LocalDate.of(2026, 7, 3),
                endDateInclusive = LocalDate.of(2026, 7, 2),
            )
        }
    }
}
