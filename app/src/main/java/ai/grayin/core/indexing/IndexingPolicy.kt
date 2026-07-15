package ai.grayin.core.indexing

import java.time.LocalTime

data class LowUsageWindow(
    val start: LocalTime,
    val end: LocalTime,
) {
    val isValid: Boolean
        get() = start != end

    fun includes(time: LocalTime): Boolean {
        if (!isValid) return false
        return if (start < end) {
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

enum class AutomaticIndexingDecisionReason {
    ALLOWED,
    INVALID_LOW_USAGE_WINDOW,
    REQUIRES_CHARGING,
    OUTSIDE_LOW_USAGE_WINDOW,
    BATTERY_LEVEL_UNKNOWN,
    BATTERY_BELOW_MINIMUM,
    THERMAL_STATE_HOT,
    THERMAL_STATE_CRITICAL,
}

data class AutomaticIndexingDecision(
    val isAllowed: Boolean,
    val reason: AutomaticIndexingDecisionReason,
)

data class AutomaticIndexingPolicy(
    val requireCharging: Boolean = true,
    val lowUsageWindow: LowUsageWindow,
    val minimumBatteryPercent: Int = 40,
    val requireAcceptableThermalState: Boolean = true,
) {
    fun evaluate(conditions: DeviceIndexingConditions): AutomaticIndexingDecision {
        val reason = when {
            !lowUsageWindow.isValid -> AutomaticIndexingDecisionReason.INVALID_LOW_USAGE_WINDOW
            requireCharging && !conditions.isCharging -> AutomaticIndexingDecisionReason.REQUIRES_CHARGING
            !lowUsageWindow.includes(conditions.localTime) -> {
                AutomaticIndexingDecisionReason.OUTSIDE_LOW_USAGE_WINDOW
            }
            conditions.batteryPercent !in BATTERY_PERCENT_RANGE -> {
                AutomaticIndexingDecisionReason.BATTERY_LEVEL_UNKNOWN
            }
            conditions.batteryPercent < minimumBatteryPercent -> {
                AutomaticIndexingDecisionReason.BATTERY_BELOW_MINIMUM
            }
            requireAcceptableThermalState && conditions.thermalState == ThermalState.HOT -> {
                AutomaticIndexingDecisionReason.THERMAL_STATE_HOT
            }
            requireAcceptableThermalState && conditions.thermalState == ThermalState.CRITICAL -> {
                AutomaticIndexingDecisionReason.THERMAL_STATE_CRITICAL
            }
            else -> AutomaticIndexingDecisionReason.ALLOWED
        }
        return AutomaticIndexingDecision(
            isAllowed = reason == AutomaticIndexingDecisionReason.ALLOWED,
            reason = reason,
        )
    }

    fun allowsAutomaticIndexing(conditions: DeviceIndexingConditions): Boolean {
        return evaluate(conditions).isAllowed
    }

    private companion object {
        val BATTERY_PERCENT_RANGE = 0..100
    }
}
