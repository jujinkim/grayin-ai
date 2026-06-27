package ai.grayin.app

import ai.grayin.core.indexing.DeviceIndexingConditions
import ai.grayin.core.indexing.ThermalState
import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticIndexingUiStateTest {
    @Test
    fun shiftedHoursWrapAcrossDayBoundary() {
        val state = AutomaticIndexingUiState(startHour = 0, endHour = 23)

        assertEquals(23, state.shiftedStartHour(-1).startHour)
        assertEquals(0, state.shiftedEndHour(1).endHour)
    }

    @Test
    fun stateMapsToAutomaticIndexingPolicy() {
        val state = AutomaticIndexingUiState(
            enabled = true,
            requireCharging = true,
            startHour = 2,
            endHour = 5,
        )
        val policy = state.toPolicy()

        assertTrue(
            policy.allowsAutomaticIndexing(
                DeviceIndexingConditions(
                    isCharging = true,
                    localTime = LocalTime.of(3, 0),
                    batteryPercent = 80,
                    thermalState = ThermalState.NOMINAL,
                ),
            ),
        )
        assertFalse(
            policy.allowsAutomaticIndexing(
                DeviceIndexingConditions(
                    isCharging = false,
                    localTime = LocalTime.of(3, 0),
                    batteryPercent = 80,
                    thermalState = ThermalState.NOMINAL,
                ),
            ),
        )
    }
}
