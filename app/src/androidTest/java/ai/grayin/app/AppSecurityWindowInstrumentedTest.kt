package ai.grayin.app

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.grayin.core.security.AppLockState
import ai.grayin.core.security.AppSecurityAuthCapability
import ai.grayin.core.security.AppSecurityAuthPurpose
import ai.grayin.core.security.AppSecurityCallbackDisposition
import ai.grayin.core.security.AppSecurityPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSecurityWindowInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun persistedScreenshotBlockingIsAppliedWhenActivityStarts() {
        assertPersistedPolicySecuresWindow(
            AppSecurityPreferences(screenshotBlockingEnabled = true),
        )
    }

    @Test
    fun persistedAppLockSecuresWindowEvenWhenScreenshotPreferenceIsOff() {
        assertPersistedPolicySecuresWindow(
            AppSecurityPreferences(
                screenshotBlockingEnabled = false,
                appLockEnabled = true,
            ),
        )
    }

    @Test
    fun recreationKeepsUnlockedSessionButOrdinaryBackgroundRelocksAndHidesContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val securityPreferences = context.getSharedPreferences(
            AppSecurityPreferenceStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val languageStore = LanguagePreferenceStore(context)
        val originalLanguage = languageStore.load()
        check(securityPreferences.edit().clear().commit())
        languageStore.save(GrayinLanguageOption.ENGLISH)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val viewModel = ViewModelProvider(activity)[AppSecurityViewModel::class.java]
                    viewModel.onAppForegrounded(AppSecurityAuthCapability.AVAILABLE)
                    val enableAttempt = viewModel.beginAuthentication(
                        AppSecurityAuthPurpose.ENABLE,
                    )!!
                    assertEquals(
                        AppSecurityCallbackDisposition.APPLIED,
                        viewModel.authenticationSucceeded(enableAttempt.id),
                    )
                    assertEquals(AppLockState.UNLOCKED, viewModel.state.value.lockState)
                    assertTrue(viewModel.state.value.preferences.appLockEnabled)
                }

                scenario.recreate()
                composeRule.waitForIdle()

                scenario.onActivity { activity ->
                    val viewModel = ViewModelProvider(activity)[AppSecurityViewModel::class.java]
                    assertEquals(AppLockState.UNLOCKED, viewModel.state.value.lockState)
                    assertTrue(viewModel.state.value.protectedContentVisible)
                    assertWindowSecure(activity)
                }
                composeRule.onNodeWithText("Ask").assertExists()
                composeRule.onNodeWithText("Grayin is locked").assertDoesNotExist()

                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                composeRule.waitForIdle()

                scenario.onActivity { activity ->
                    val viewModel = ViewModelProvider(activity)[AppSecurityViewModel::class.java]
                    assertFalse(viewModel.state.value.protectedContentVisible)
                    assertWindowSecure(activity)
                }
                composeRule.onNodeWithText("Grayin is locked").assertExists()
                composeRule.onNodeWithText("Ask").assertDoesNotExist()
            }
        } finally {
            check(securityPreferences.edit().clear().commit())
            languageStore.save(originalLanguage)
        }
    }

    private fun assertPersistedPolicySecuresWindow(preferencesToWrite: AppSecurityPreferences) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            AppSecurityPreferenceStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        check(preferences.edit().clear().commit())
        try {
            assertTrue(
                AppSecurityPreferenceStore(context).write(preferencesToWrite),
            )

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity(::assertWindowSecure)
                scenario.recreate()
                scenario.onActivity(::assertWindowSecure)
            }
        } finally {
            check(preferences.edit().clear().commit())
        }
    }

    private fun assertWindowSecure(activity: MainActivity) {
        assertTrue(
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
        )
    }
}
