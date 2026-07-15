package ai.grayin.app

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import ai.grayin.core.security.AppSecurityAuthCapability
import ai.grayin.core.security.AppSecurityFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppSecurityPolicyTest {
    @Test
    fun onlyTheCurrentExplicitCredentialHandoffIsRecognized() {
        assertTrue(
            AndroidAppSecurityPolicy.isExplicitCredentialHandoff(
                activeAttemptId = 7L,
                credentialHandoffAttemptId = 7L,
            ),
        )
        assertFalse(
            AndroidAppSecurityPolicy.isExplicitCredentialHandoff(
                activeAttemptId = 7L,
                credentialHandoffAttemptId = null,
            ),
        )
        assertFalse(
            AndroidAppSecurityPolicy.isExplicitCredentialHandoff(
                activeAttemptId = 7L,
                credentialHandoffAttemptId = 8L,
            ),
        )
        assertFalse(
            AndroidAppSecurityPolicy.isExplicitCredentialHandoff(
                activeAttemptId = null,
                credentialHandoffAttemptId = null,
            ),
        )
    }

    @Test
    fun authenticatorPolicyUsesCompatiblePre30Fallback() {
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_WEAK,
            AndroidAppSecurityPolicy.allowedAuthenticators(Build.VERSION_CODES.Q),
        )
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            AndroidAppSecurityPolicy.allowedAuthenticators(Build.VERSION_CODES.R),
        )
    }

    @Test
    fun pre30PromptErrorsSelectOnlyTheExplicitCredentialFallbackCases() {
        assertTrue(
            AndroidAppSecurityPolicy.shouldUsePre30CredentialFallback(
                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                Build.VERSION_CODES.Q,
            ),
        )
        assertTrue(
            AndroidAppSecurityPolicy.shouldUsePre30CredentialFallback(
                BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                Build.VERSION_CODES.Q,
            ),
        )
        assertTrue(
            AndroidAppSecurityPolicy.shouldUsePre30CredentialFallback(
                BiometricPrompt.ERROR_NO_BIOMETRICS,
                Build.VERSION_CODES.Q,
            ),
        )
        assertTrue(
            AndroidAppSecurityPolicy.shouldUsePre30CredentialFallback(
                BiometricPrompt.ERROR_TIMEOUT,
                Build.VERSION_CODES.Q,
            ),
        )
        assertTrue(
            AndroidAppSecurityPolicy.shouldUsePre30CredentialFallback(
                BiometricPrompt.ERROR_HW_UNAVAILABLE,
                Build.VERSION_CODES.Q,
            ),
        )
        assertFalse(
            AndroidAppSecurityPolicy.shouldUsePre30CredentialFallback(
                BiometricPrompt.ERROR_USER_CANCELED,
                Build.VERSION_CODES.Q,
            ),
        )
        assertFalse(
            AndroidAppSecurityPolicy.shouldUsePre30CredentialFallback(
                BiometricPrompt.ERROR_CANCELED,
                Build.VERSION_CODES.Q,
            ),
        )
        assertFalse(
            AndroidAppSecurityPolicy.shouldUsePre30CredentialFallback(
                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                Build.VERSION_CODES.R,
            ),
        )
    }

    @Test
    fun capabilityRequiresDeviceCredentialAndMapsOnlyStableCategories() {
        assertEquals(
            AppSecurityAuthCapability.NOT_CONFIGURED,
            AndroidAppSecurityPolicy.capabilityForResult(
                BiometricManager.BIOMETRIC_SUCCESS,
                deviceSecure = false,
            ),
        )
        assertEquals(
            AppSecurityAuthCapability.AVAILABLE,
            AndroidAppSecurityPolicy.capabilityForResult(
                BiometricManager.BIOMETRIC_SUCCESS,
                deviceSecure = true,
            ),
        )
        assertEquals(
            AppSecurityAuthCapability.SECURITY_UPDATE_REQUIRED,
            AndroidAppSecurityPolicy.capabilityForResult(
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
                deviceSecure = true,
            ),
        )
    }

    @Test
    fun promptErrorsMapWithoutUsingPlatformErrorText() {
        assertEquals(
            AppSecurityFailure.AUTHENTICATION_CANCELLED,
            AndroidAppSecurityPolicy.failureForPromptError(BiometricPrompt.ERROR_USER_CANCELED),
        )
        assertEquals(
            AppSecurityFailure.AUTHENTICATION_LOCKED_OUT,
            AndroidAppSecurityPolicy.failureForPromptError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT),
        )
        assertEquals(
            AppSecurityFailure.AUTHENTICATION_NOT_CONFIGURED,
            AndroidAppSecurityPolicy.failureForPromptError(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL),
        )
        assertEquals(
            AppSecurityFailure.AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
            AndroidAppSecurityPolicy.failureForPromptError(BiometricPrompt.ERROR_TIMEOUT),
        )
    }
}
