package ai.grayin.core.indexing

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

interface DeviceIndexingConditionProvider {
    fun currentConditions(): DeviceIndexingConditions
}

class AndroidDeviceIndexingConditionProvider(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : DeviceIndexingConditionProvider {
    private val batteryManager = context.applicationContext.getSystemService(BatteryManager::class.java)
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)

    override fun currentConditions(): DeviceIndexingConditions {
        return DeviceIndexingConditions(
            isCharging = batteryManager?.isCharging == true,
            localTime = LocalDateTime.ofInstant(clock.instant(), zoneId).toLocalTime(),
            batteryPercent = normalizeBatteryPercent(
                batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            ),
            thermalState = currentThermalState(),
        )
    }

    private fun currentThermalState(): ThermalState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalState.UNKNOWN
        return powerManager?.currentThermalStatus
            ?.let(::thermalStateFromAndroidStatus)
            ?: ThermalState.UNKNOWN
    }
}

internal fun normalizeBatteryPercent(value: Int?): Int {
    return value?.takeIf { it in 0..100 } ?: UNKNOWN_BATTERY_PERCENT
}

internal fun thermalStateFromAndroidStatus(status: Int): ThermalState {
    return when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
        PowerManager.THERMAL_STATUS_LIGHT,
        PowerManager.THERMAL_STATUS_MODERATE,
        -> ThermalState.WARM

        PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.HOT
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN,
        -> ThermalState.CRITICAL

        else -> ThermalState.UNKNOWN
    }
}

private const val UNKNOWN_BATTERY_PERCENT = -1
