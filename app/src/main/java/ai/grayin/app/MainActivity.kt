package ai.grayin.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.grayin.core.security.AppLockState
import ai.grayin.core.security.AppSecurityAuthAttempt
import ai.grayin.core.security.AppSecurityAuthPurpose
import ai.grayin.core.security.AppSecurityCallbackDisposition
import ai.grayin.core.security.AppSecurityFailure

class MainActivity : FragmentActivity() {
    private lateinit var appSecurityViewModel: AppSecurityViewModel
    private lateinit var deviceCredentialLauncher: ActivityResultLauncher<Intent>
    private var biometricPrompt: BiometricPrompt? = null
    private var biometricPromptAttemptId: Long? = null
    private var automaticUnlockAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capability = AndroidAppSecurityPolicy.capability(this)
        appSecurityViewModel = ViewModelProvider(
            this,
            AppSecurityViewModel.Factory(this, capability),
        )[AppSecurityViewModel::class.java]
        deviceCredentialLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val attemptId = appSecurityViewModel.state.value.credentialHandoffAttemptId
                ?: return@registerForActivityResult
            if (result.resultCode == Activity.RESULT_OK) {
                appSecurityViewModel.credentialAuthenticationSucceeded(attemptId)
            } else {
                appSecurityViewModel.credentialAuthenticationError(
                    attemptId,
                    AppSecurityFailure.AUTHENTICATION_CANCELLED,
                )
            }
            clearPromptIfCurrent(attemptId)
            applyEffectiveWindowSecurity()
        }
        appSecurityViewModel.onAppForegrounded(capability)
        applyEffectiveWindowSecurity()
        appSecurityViewModel.state.value.let { state ->
            automaticUnlockAttempted = state.activeAttempt != null
            state.activeAttempt
                ?.takeIf { state.credentialHandoffAttemptId == null }
                ?.let { activeAttempt ->
                    attachBiometricPrompt(activeAttempt, authenticate = false)
                }
        }
        setContent {
            val appSecurityState by appSecurityViewModel.state.collectAsStateWithLifecycle()
            GrayinApp(
                appSecurityState = appSecurityState,
                onUnlockApp = { startAuthentication(AppSecurityAuthPurpose.UNLOCK) },
                onScreenshotBlockingChanged = ::setScreenshotBlocking,
                onAppLockChanged = { enabled ->
                    startAuthentication(
                        if (enabled) AppSecurityAuthPurpose.ENABLE else AppSecurityAuthPurpose.DISABLE,
                    )
                },
                onOpenDeviceSecuritySettings = ::openDeviceSecuritySettings,
            )
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        appSecurityViewModel.onAppForegrounded(AndroidAppSecurityPolicy.capability(this))
        applyEffectiveWindowSecurity()
        val state = appSecurityViewModel.state.value
        if (
            state.preferences.appLockEnabled &&
            state.lockState == AppLockState.LOCKED &&
            state.activeAttempt == null &&
            !automaticUnlockAttempted
        ) {
            automaticUnlockAttempted = true
            startAuthentication(AppSecurityAuthPurpose.UNLOCK)
        }
    }

    override fun onStop() {
        val state = appSecurityViewModel.state.value
        val explicitCredentialHandoff = AndroidAppSecurityPolicy.isExplicitCredentialHandoff(
            activeAttemptId = state.activeAttempt?.id,
            credentialHandoffAttemptId = state.credentialHandoffAttemptId,
        )
        when {
            isChangingConfigurations -> {
                appSecurityViewModel.onAppBackgrounded(isChangingConfigurations = true)
            }

            explicitCredentialHandoff -> {
                automaticUnlockAttempted = true
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }

            else -> {
                automaticUnlockAttempted = false
                appSecurityViewModel.onAppBackgrounded(isChangingConfigurations = false)
                val prompt = biometricPrompt
                biometricPrompt = null
                biometricPromptAttemptId = null
                prompt?.cancelAuthentication()
                applyEffectiveWindowSecurity()
            }
        }
        super.onStop()
    }

    private fun startAuthentication(purpose: AppSecurityAuthPurpose) {
        appSecurityViewModel.onAppForegrounded(AndroidAppSecurityPolicy.capability(this))
        val attempt = appSecurityViewModel.beginAuthentication(purpose)
        applyEffectiveWindowSecurity()
        if (attempt != null) {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                !AndroidAppSecurityPolicy.hasPre30Biometric(this)
            ) {
                launchDeviceCredentialHandoff(attempt)
            } else {
                attachBiometricPrompt(attempt, authenticate = true)
            }
        }
    }

    private fun attachBiometricPrompt(
        attempt: AppSecurityAuthAttempt,
        authenticate: Boolean,
    ) {
        try {
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        if (
                            appSecurityViewModel.authenticationSucceeded(attempt.id) ==
                            AppSecurityCallbackDisposition.APPLIED
                        ) {
                            clearPromptIfCurrent(attempt.id)
                            applyEffectiveWindowSecurity()
                        }
                    }

                    override fun onAuthenticationFailed() {
                        appSecurityViewModel.authenticationFailed(attempt.id)
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        if (AndroidAppSecurityPolicy.shouldUsePre30CredentialFallback(errorCode)) {
                            launchDeviceCredentialHandoff(attempt)
                            return
                        }
                        if (
                            appSecurityViewModel.authenticationError(
                                attemptId = attempt.id,
                                failure = AndroidAppSecurityPolicy.failureForPromptError(errorCode),
                            ) == AppSecurityCallbackDisposition.APPLIED
                        ) {
                            clearPromptIfCurrent(attempt.id)
                            applyEffectiveWindowSecurity()
                        }
                    }
                },
            )
            biometricPrompt = prompt
            biometricPromptAttemptId = attempt.id
            if (authenticate) {
                prompt.authenticate(buildPromptInfo())
            }
        } catch (_: IllegalArgumentException) {
            terminateAuthenticationAttempt(
                attempt.id,
                AppSecurityFailure.AUTHENTICATION_UNSUPPORTED,
            )
        } catch (_: RuntimeException) {
            terminateAuthenticationAttempt(
                attempt.id,
                AppSecurityFailure.AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
            )
        }
    }

    private fun buildPromptInfo(): BiometricPrompt.PromptInfo {
        val strings = GrayinText.forOption(LanguagePreferenceStore(this).load())
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(strings.appLockScreenTitle())
            .setSubtitle(strings.appLockScreenBody())
            .setAllowedAuthenticators(AndroidAppSecurityPolicy.allowedAuthenticators())
            .setConfirmationRequired(true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            builder.setNegativeButtonText(strings.useDeviceCredential())
        }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun launchDeviceCredentialHandoff(attempt: AppSecurityAuthAttempt) {
        try {
            val strings = GrayinText.forOption(LanguagePreferenceStore(this).load())
            val keyguardManager = getSystemService(KeyguardManager::class.java)
            val intent = keyguardManager?.createConfirmDeviceCredentialIntent(
                strings.appLockScreenTitle(),
                strings.appLockScreenBody(),
            )
            if (intent == null) {
                terminateAuthenticationAttempt(
                    attempt.id,
                    AppSecurityFailure.AUTHENTICATION_NOT_CONFIGURED,
                )
                return
            }
            if (
                appSecurityViewModel.beginCredentialHandoff(attempt.id) !=
                AppSecurityCallbackDisposition.APPLIED
            ) {
                return
            }
            clearPromptIfCurrent(attempt.id)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            deviceCredentialLauncher.launch(intent)
        } catch (_: RuntimeException) {
            terminateAuthenticationAttempt(
                attempt.id,
                AppSecurityFailure.AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
            )
        }
    }

    private fun terminateAuthenticationAttempt(
        attemptId: Long,
        failure: AppSecurityFailure,
    ) {
        if (appSecurityViewModel.state.value.credentialHandoffAttemptId == attemptId) {
            appSecurityViewModel.credentialAuthenticationError(attemptId, failure)
        } else {
            appSecurityViewModel.authenticationError(attemptId, failure)
        }
        clearPromptIfCurrent(attemptId)
        applyEffectiveWindowSecurity()
    }

    private fun clearPromptIfCurrent(attemptId: Long) {
        if (biometricPromptAttemptId == attemptId) {
            biometricPrompt = null
            biometricPromptAttemptId = null
        }
    }

    private fun setScreenshotBlocking(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        appSecurityViewModel.setScreenshotBlockingEnabled(enabled)
        applyEffectiveWindowSecurity()
    }

    private fun applyEffectiveWindowSecurity() {
        if (appSecurityViewModel.state.value.effectiveWindowSecure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun openDeviceSecuritySettings() {
        val recoveryIntent = AndroidAppSecurityPolicy.recoverySettingsIntent()
        if (
            !tryStartSecuritySettings(recoveryIntent) &&
            recoveryIntent.action != Settings.ACTION_SECURITY_SETTINGS
        ) {
            tryStartSecuritySettings(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    private fun tryStartSecuritySettings(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
