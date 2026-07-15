package ai.grayin.app

import android.content.Context
import ai.grayin.core.security.AppSecurityPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSecurityPreferenceStoreInstrumentedTest {
    @Test
    fun writesBothSecurityPreferencesSynchronously() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sharedPreferences = context.getSharedPreferences(
            AppSecurityPreferenceStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        check(sharedPreferences.edit().clear().commit())
        try {
            val store = AppSecurityPreferenceStore(context)
            val expected = AppSecurityPreferences(
                screenshotBlockingEnabled = true,
                appLockEnabled = true,
            )

            assertTrue(store.write(expected))

            assertEquals(expected, AppSecurityPreferenceStore(context).load())
            assertTrue(
                sharedPreferences.getBoolean(
                    AppSecurityPreferenceStore.KEY_SCREENSHOT_BLOCKING_ENABLED,
                    false,
                ),
            )
            assertTrue(
                sharedPreferences.getBoolean(
                    AppSecurityPreferenceStore.KEY_APP_LOCK_ENABLED,
                    false,
                ),
            )
        } finally {
            check(sharedPreferences.edit().clear().commit())
        }
    }
}
