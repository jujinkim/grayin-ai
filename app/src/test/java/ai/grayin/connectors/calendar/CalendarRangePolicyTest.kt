package ai.grayin.connectors.calendar

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
}
