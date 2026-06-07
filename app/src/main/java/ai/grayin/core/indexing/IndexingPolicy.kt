package ai.grayin.core.indexing

import java.time.LocalTime

data class LowUsageWindow(
    val start: LocalTime,
    val end: LocalTime,
) {
    fun includes(time: LocalTime): Boolean {
        return if (start <= end) {
            !time.isBefore(start) && time.isBefore(end)
        } else {
            !time.isBefore(start) || time.isBefore(end)
        }
    }
}

enum class ThermalState {
    UNKNOWN,
    NOMINAL,
    WARM,
    HOT,
    CRITICAL,
}

data class DeviceIndexingConditions(
    val isCharging: Boolean,
    val localTime: LocalTime,
    val batteryPercent: Int,
    val thermalState: ThermalState,
)

data class AutomaticIndexingPolicy(
    val requireCharging: Boolean = true,
    val lowUsageWindow: LowUsageWindow,
    val minimumBatteryPercent: Int = 40,
    val requireAcceptableThermalState: Boolean = true,
) {
    fun allowsAutomaticIndexing(conditions: DeviceIndexingConditions): Boolean {
        val chargingAllowed = !requireCharging || conditions.isCharging
        val timeAllowed = lowUsageWindow.includes(conditions.localTime)
        val batteryAllowed = conditions.batteryPercent >= minimumBatteryPercent
        val thermalAllowed = !requireAcceptableThermalState ||
            conditions.thermalState == ThermalState.UNKNOWN ||
            conditions.thermalState == ThermalState.NOMINAL ||
            conditions.thermalState == ThermalState.WARM

        return chargingAllowed && timeAllowed && batteryAllowed && thermalAllowed
    }
}

