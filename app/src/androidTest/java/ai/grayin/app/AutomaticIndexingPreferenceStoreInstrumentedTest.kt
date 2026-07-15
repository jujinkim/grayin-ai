package ai.grayin.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticIndexingPreferenceStoreInstrumentedTest {
    @Test
    fun loadPersistsFailClosedRepairForLegacyInvalidWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        check(
            preferences.edit()
                .clear()
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_REQUIRE_CHARGING, false)
                .putInt(KEY_START_HOUR, 23)
                .putInt(KEY_END_HOUR, 23)
                .commit(),
        )
        try {
            val loaded = AutomaticIndexingPreferenceStore(context).load()

            assertFalse(loaded.enabled)
            assertEquals(23, loaded.startHour)
            assertEquals(0, loaded.endHour)
            assertTrue(loaded.hasValidWindow)
            assertFalse(preferences.getBoolean(KEY_ENABLED, true))
            assertEquals(0, preferences.getInt(KEY_END_HOUR, -1))
        } finally {
            check(preferences.edit().clear().commit())
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "grayin_auto_indexing"
        const val KEY_ENABLED = "enabled"
        const val KEY_REQUIRE_CHARGING = "require_charging"
        const val KEY_START_HOUR = "start_hour"
        const val KEY_END_HOUR = "end_hour"
    }
}
