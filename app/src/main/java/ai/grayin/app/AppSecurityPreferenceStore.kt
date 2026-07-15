package ai.grayin.app

import android.content.Context
import ai.grayin.core.security.AppSecurityPreferenceWriter
import ai.grayin.core.security.AppSecurityPreferences

class AppSecurityPreferenceStore(context: Context) : AppSecurityPreferenceWriter {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): AppSecurityPreferences {
        return AppSecurityPreferences(
            screenshotBlockingEnabled = preferences.getBoolean(KEY_SCREENSHOT_BLOCKING_ENABLED, false),
            appLockEnabled = preferences.getBoolean(KEY_APP_LOCK_ENABLED, false),
        )
    }

    override fun write(preferences: AppSecurityPreferences): Boolean {
        return this.preferences.edit()
            .putBoolean(KEY_SCREENSHOT_BLOCKING_ENABLED, preferences.screenshotBlockingEnabled)
            .putBoolean(KEY_APP_LOCK_ENABLED, preferences.appLockEnabled)
            .commit()
    }

    internal companion object {
        const val PREFERENCES_NAME = "grayin_app_security"
        const val KEY_SCREENSHOT_BLOCKING_ENABLED = "screenshot_blocking_enabled"
        const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
    }
}
