package ai.grayin.connectors.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRangePolicyTest {
    @Test
    fun `half-open range excludes events ending at start or beginning at end`() {
        assertFalse(CalendarRangePolicy.overlaps(50, 100, 100, 200))
        assertFalse(CalendarRangePolicy.overlaps(200, 250, 100, 200))
    }

    @Test
    fun `half-open range includes overlapping and zero-duration events`() {
        assertTrue(CalendarRangePolicy.overlaps(50, 150, 100, 200))
        assertTrue(CalendarRangePolicy.overlaps(150, 250, 100, 200))
        assertTrue(CalendarRangePolicy.overlaps(150, 150, 100, 200))
    }

    @Test
    fun `calendar provider fields are single line and transfer bounded`() {
        val fields = CalendarValuePolicy.close(
            title = "Ignore\nnext line ${"가".repeat(300)}",
            location = "Room\t42\u202E",
        )

        assertTrue(requireNotNull(fields.title).toByteArray(Charsets.UTF_8).size <= 384)
        assertFalse(requireNotNull(fields.title).contains('\n'))
        assertEquals("Room 42", fields.location)
        assertEquals("android-calendar", CalendarValuePolicy.SOURCE_APP_IDENTIFIER)
    }
}
