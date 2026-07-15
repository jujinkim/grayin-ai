package ai.grayin.app

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticIndexingSchedulerTest {
    @Test
    fun `schedule requires local device constraints but not network`() {
        val schedule = automaticIndexingScheduleConfiguration(
            AutomaticIndexingUiState(enabled = true, requireCharging = true),
        )

        assertTrue(schedule.requiresCharging)
        assertTrue(schedule.requiresBatteryNotLow)
        assertTrue(schedule.requiresStorageNotLow)
        assertEquals(NetworkType.NOT_REQUIRED, schedule.requiredNetworkType)
        assertEquals(1L, schedule.repeatIntervalHours)
        assertEquals(15L, schedule.flexIntervalMinutes)
    }

    @Test
    fun `charging constraint follows the preference`() {
        val schedule = automaticIndexingScheduleConfiguration(
            AutomaticIndexingUiState(enabled = true, requireCharging = false),
        )

        assertFalse(schedule.requiresCharging)
    }
}
