package ai.grayin.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ai.grayin.core.security.AppSecurityAuthAttempt
import ai.grayin.core.security.AppSecurityAuthCapability
import ai.grayin.core.security.AppSecurityAuthPurpose
import ai.grayin.core.security.AppSecurityCallbackDisposition
import ai.grayin.core.security.AppSecurityFailure
import ai.grayin.core.security.AppSecurityState
import ai.grayin.core.security.AppSecurityStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object AndroidAppSecurityPolicy {
    fun allowedAuthenticators(apiLevel: Int = Build.VERSION.SDK_INT): Int {
        return if (apiLevel >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
    }

    fun capability(context: Context): AppSecurityAuthCapability {
        return try {
            val keyguardManager = context.getSystemService(KeyguardManager::class.java)
            if (keyguardManager?.isDeviceSecure != true) {
                return AppSecurityAuthCapability.NOT_CONFIGURED
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                AppSecurityAuthCapability.AVAILABLE
            } else {
                val result = BiometricManager.from(context).canAuthenticate(allowedAuthenticators())
                capabilityForResult(result, deviceSecure = true)
            }
        } catch (_: RuntimeException) {
            AppSecurityAuthCapability.TEMPORARILY_UNAVAILABLE
        }
    }

    fun hasPre30Biometric(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return true
        return try {
            BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK,
            ) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: RuntimeException) {
            false
        }
    }

    fun shouldUsePre30CredentialFallback(
        errorCode: Int,
        apiLevel: Int = Build.VERSION.SDK_INT,
    ): Boolean {
        return apiLevel < Build.VERSION_CODES.R && (
            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                errorCode == BiometricPrompt.ERROR_LOCKOUT ||
                errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT ||
                errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT ||
                errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE ||
                errorCode == BiometricPrompt.ERROR_UNABLE_TO_PROCESS ||
                errorCode == BiometricPrompt.ERROR_TIMEOUT ||
                errorCode == BiometricPrompt.ERROR_NO_SPACE ||
                errorCode == BiometricPrompt.ERROR_VENDOR ||
                errorCode == BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED
            )
    }

    fun isExplicitCredentialHandoff(
        activeAttemptId: Long?,
        credentialHandoffAttemptId: Long?,
    ): Boolean {
        return activeAttemptId != null && activeAttemptId == credentialHandoffAttemptId
    }

    fun capabilityForResult(
        result: Int,
        deviceSecure: Boolean,
    ): AppSecurityAuthCapability {
        if (!deviceSecure) return AppSecurityAuthCapability.NOT_CONFIGURED
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> AppSecurityAuthCapability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AppSecurityAuthCapability.NOT_CONFIGURED
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            -> AppSecurityAuthCapability.TEMPORARILY_UNAVAILABLE

            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                AppSecurityAuthCapability.SECURITY_UPDATE_REQUIRED

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            -> AppSecurityAuthCapability.UNSUPPORTED

            else -> AppSecurityAuthCapability.TEMPORARILY_UNAVAILABLE
        }
    }

    fun failureForPromptError(errorCode: Int): AppSecurityFailure {
        return when (errorCode) {
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_USER_CANCELED,
            -> AppSecurityFailure.AUTHENTICATION_CANCELLED

            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            -> AppSecurityFailure.AUTHENTICATION_LOCKED_OUT

            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            -> AppSecurityFailure.AUTHENTICATION_NOT_CONFIGURED

            BiometricPrompt.ERROR_HW_NOT_PRESENT -> AppSecurityFailure.AUTHENTICATION_UNSUPPORTED
            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED ->
                AppSecurityFailure.AUTHENTICATION_SECURITY_UPDATE_REQUIRED

            else -> AppSecurityFailure.AUTHENTICATION_TEMPORARILY_UNAVAILABLE
        }
    }

    fun recoverySettingsIntent(apiLevel: Int = Build.VERSION.SDK_INT): Intent {
        return if (apiLevel >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                allowedAuthenticators(apiLevel),
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }
}

internal class AppSecurityViewModel(
    private val machine: AppSecurityStateMachine,
) : ViewModel() {
    private val mutableState = MutableStateFlow(machine.state)
    val state: StateFlow<AppSecurityState> = mutableState.asStateFlow()

    fun onAppForegrounded(capability: AppSecurityAuthCapability) {
        machine.onAppForegrounded(capability)
        publish()
    }

    fun onAppBackgrounded(isChangingConfigurations: Boolean) {
        machine.onAppBackgrounded(isChangingConfigurations)
        publish()
    }

    fun beginAuthentication(purpose: AppSecurityAuthPurpose): AppSecurityAuthAttempt? {
        return machine.beginAuthentication(purpose).also { publish() }
    }

    fun authenticationSucceeded(attemptId: Long): AppSecurityCallbackDisposition {
        return machine.authenticationSucceeded(attemptId).also { publish() }
    }

    fun credentialAuthenticationSucceeded(attemptId: Long): AppSecurityCallbackDisposition {
        return machine.credentialAuthenticationSucceeded(attemptId).also { publish() }
    }

    fun beginCredentialHandoff(attemptId: Long): AppSecurityCallbackDisposition {
        return machine.beginCredentialHandoff(attemptId).also { publish() }
    }

    fun authenticationFailed(attemptId: Long): AppSecurityCallbackDisposition {
        return machine.authenticationFailed(attemptId).also { publish() }
    }

    fun authenticationError(
        attemptId: Long,
        failure: AppSecurityFailure,
    ): AppSecurityCallbackDisposition {
        return machine.authenticationError(attemptId, failure).also { publish() }
    }

    fun credentialAuthenticationError(
        attemptId: Long,
        failure: AppSecurityFailure,
    ): AppSecurityCallbackDisposition {
        return machine.credentialAuthenticationError(attemptId, failure).also { publish() }
    }

    fun setScreenshotBlockingEnabled(enabled: Boolean): Boolean {
        return machine.setScreenshotBlockingEnabled(enabled).also { publish() }
    }

    fun clearFailure() {
        machine.clearFailure()
        publish()
    }

    private fun publish() {
        mutableState.value = machine.state
    }

    class Factory(
        context: Context,
        initialCapability: AppSecurityAuthCapability,
    ) : ViewModelProvider.Factory {
        private val store = AppSecurityPreferenceStore(context)
        private val machine = AppSecurityStateMachine(
            initialPreferences = store.load(),
            initialAuthCapability = initialCapability,
            preferenceWriter = store,
        )

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppSecurityViewModel::class.java))
            return AppSecurityViewModel(machine) as T
        }
    }
}
