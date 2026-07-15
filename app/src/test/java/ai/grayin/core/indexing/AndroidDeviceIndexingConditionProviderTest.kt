package ai.grayin.core.indexing

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDeviceIndexingConditionProviderTest {
    @Test
    fun mapsBatteryAndThermalReadingsToIndexingConditions() {
        assertEquals(0, normalizeBatteryPercent(0))
        assertEquals(100, normalizeBatteryPercent(100))
        assertEquals(-1, normalizeBatteryPercent(-1))
        assertEquals(-1, normalizeBatteryPercent(101))
        assertEquals(-1, normalizeBatteryPercent(null))

        assertEquals(
            ThermalState.NOMINAL,
            thermalStateFromAndroidStatus(PowerManager.THERMAL_STATUS_NONE),
        )
        assertEquals(
            ThermalState.WARM,
            thermalStateFromAndroidStatus(PowerManager.THERMAL_STATUS_LIGHT),
        )
        assertEquals(
            ThermalState.WARM,
            thermalStateFromAndroidStatus(PowerManager.THERMAL_STATUS_MODERATE),
        )
        assertEquals(
            ThermalState.HOT,
            thermalStateFromAndroidStatus(PowerManager.THERMAL_STATUS_SEVERE),
        )
        assertEquals(
            ThermalState.CRITICAL,
            thermalStateFromAndroidStatus(PowerManager.THERMAL_STATUS_CRITICAL),
        )
        assertEquals(
            ThermalState.CRITICAL,
            thermalStateFromAndroidStatus(PowerManager.THERMAL_STATUS_EMERGENCY),
        )
        assertEquals(
            ThermalState.CRITICAL,
            thermalStateFromAndroidStatus(PowerManager.THERMAL_STATUS_SHUTDOWN),
        )
        assertEquals(ThermalState.UNKNOWN, thermalStateFromAndroidStatus(Int.MAX_VALUE))
    }
}
