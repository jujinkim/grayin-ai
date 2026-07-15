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

    @Test
    fun equalWindowEndpointsAreInvalidWhileCrossMidnightIsValid() {
        assertFalse(AutomaticIndexingUiState(startHour = 2, endHour = 2).hasValidWindow)
        assertTrue(AutomaticIndexingUiState(startHour = 23, endHour = 2).hasValidWindow)
    }

    @Test
    fun hourCandidatesExposeTheInvalidEndpointBeforePersistence() {
        val state = AutomaticIndexingUiState(startHour = 2, endHour = 3)

        assertFalse(state.shiftedStartHour(1).hasValidWindow)
        assertFalse(state.shiftedEndHour(-1).hasValidWindow)
        assertTrue(state.shiftedStartHour(-1).hasValidWindow)
        assertTrue(state.shiftedEndHour(1).hasValidWindow)
    }

    @Test
    fun legacyInvalidWindowRepairsFailClosed() {
        val repaired = AutomaticIndexingUiState(
            enabled = true,
            startHour = 23,
            endHour = 23,
        ).repairedAfterLoad()

        assertFalse(repaired.enabled)
        assertEquals(23, repaired.startHour)
        assertEquals(0, repaired.endHour)
        assertTrue(repaired.hasValidWindow)
    }
}
