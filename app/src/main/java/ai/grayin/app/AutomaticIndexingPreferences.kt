package ai.grayin.app

import android.content.Context
import ai.grayin.core.indexing.AutomaticIndexingPolicy
import ai.grayin.core.indexing.LowUsageWindow
import java.time.LocalTime
import java.util.Locale

data class AutomaticIndexingUiState(
    val enabled: Boolean = false,
    val requireCharging: Boolean = true,
    val startHour: Int = DEFAULT_START_HOUR,
    val endHour: Int = DEFAULT_END_HOUR,
) {
    val hasValidWindow: Boolean
        get() = startHour.coerceIn(HOUR_MIN, HOUR_MAX) != endHour.coerceIn(HOUR_MIN, HOUR_MAX)

    fun toPolicy(): AutomaticIndexingPolicy {
        return AutomaticIndexingPolicy(
            requireCharging = requireCharging,
            lowUsageWindow = LowUsageWindow(
                start = LocalTime.of(startHour.coerceIn(HOUR_MIN, HOUR_MAX), 0),
                end = LocalTime.of(endHour.coerceIn(HOUR_MIN, HOUR_MAX), 0),
            ),
        )
    }

    fun shiftedStartHour(delta: Int): AutomaticIndexingUiState {
        return copy(startHour = wrapHour(startHour + delta))
    }

    fun shiftedEndHour(delta: Int): AutomaticIndexingUiState {
        return copy(endHour = wrapHour(endHour + delta))
    }

    fun windowLabel(): String {
        return "${formatHour(startHour)}-${formatHour(endHour)}"
    }

    fun controlSettingsKey(): String {
        return buildString {
            append(CONTROL_SETTINGS_KEY_VERSION)
            append(":enabled=")
            append(enabled.stableFlag())
            append(":charging=")
            append(requireCharging.stableFlag())
            append(":start=")
            append(startHour.coerceIn(HOUR_MIN, HOUR_MAX))
            append(":end=")
            append(endHour.coerceIn(HOUR_MIN, HOUR_MAX))
        }
    }

    fun repairedAfterLoad(): AutomaticIndexingUiState {
        return if (hasValidWindow) {
            this
        } else {
            copy(
                enabled = false,
                endHour = (startHour.coerceIn(HOUR_MIN, HOUR_MAX) + 1) % 24,
            )
        }
    }

    companion object {
        const val DEFAULT_START_HOUR = 2
        const val DEFAULT_END_HOUR = 5
        const val HOUR_MIN = 0
        const val HOUR_MAX = 23
        private const val CONTROL_SETTINGS_KEY_VERSION = "v1"

        fun formatHour(hour: Int): String {
            return String.format(Locale.US, "%02d:00", hour.coerceIn(HOUR_MIN, HOUR_MAX))
        }

        private fun wrapHour(hour: Int): Int {
            return ((hour % 24) + 24) % 24
        }

        private fun Boolean.stableFlag(): Char = if (this) '1' else '0'
    }
}

class AutomaticIndexingPreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AutomaticIndexingUiState {
        val loaded = AutomaticIndexingUiState(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            requireCharging = prefs.getBoolean(KEY_REQUIRE_CHARGING, true),
            startHour = prefs.getInt(KEY_START_HOUR, AutomaticIndexingUiState.DEFAULT_START_HOUR)
                .coerceIn(AutomaticIndexingUiState.HOUR_MIN, AutomaticIndexingUiState.HOUR_MAX),
            endHour = prefs.getInt(KEY_END_HOUR, AutomaticIndexingUiState.DEFAULT_END_HOUR)
                .coerceIn(AutomaticIndexingUiState.HOUR_MIN, AutomaticIndexingUiState.HOUR_MAX),
        )
        val repaired = loaded.repairedAfterLoad()
        if (repaired != loaded) {
            save(repaired)
        }
        return repaired
    }

    fun save(state: AutomaticIndexingUiState) {
        require(state.hasValidWindow) { "Automatic indexing start and end hours must differ." }
        check(
            prefs.edit()
                .putBoolean(KEY_ENABLED, state.enabled)
                .putBoolean(KEY_REQUIRE_CHARGING, state.requireCharging)
                .putInt(KEY_START_HOUR, state.startHour.coerceIn(0, 23))
                .putInt(KEY_END_HOUR, state.endHour.coerceIn(0, 23))
                .commit(),
        ) { "Could not persist automatic indexing settings." }
    }

    private companion object {
        const val PREFS_NAME = "grayin_auto_indexing"
        const val KEY_ENABLED = "enabled"
        const val KEY_REQUIRE_CHARGING = "require_charging"
        const val KEY_START_HOUR = "start_hour"
        const val KEY_END_HOUR = "end_hour"
    }
}
