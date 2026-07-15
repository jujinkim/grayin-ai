package ai.grayin.core.security

data class AppSecurityPreferences(
    val screenshotBlockingEnabled: Boolean = false,
    val appLockEnabled: Boolean = false,
) {
    val effectiveWindowSecure: Boolean
        get() = screenshotBlockingEnabled || appLockEnabled
}

fun interface AppSecurityPreferenceWriter {
    /** Returns only after the preference update has been committed to durable storage. */
    fun write(preferences: AppSecurityPreferences): Boolean
}

enum class AppLockState {
    DISABLED,
    LOCKED,
    AUTHENTICATING,
    UNLOCKED,
}

enum class AppSecurityAuthPurpose {
    UNLOCK,
    ENABLE,
    DISABLE,
}

enum class AppSecurityAuthCapability {
    AVAILABLE,
    NOT_CONFIGURED,
    TEMPORARILY_UNAVAILABLE,
    UNSUPPORTED,
    SECURITY_UPDATE_REQUIRED,
}

enum class AppSecurityFailure {
    AUTHENTICATION_FAILED,
    AUTHENTICATION_CANCELLED,
    AUTHENTICATION_LOCKED_OUT,
    AUTHENTICATION_NOT_CONFIGURED,
    AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
    AUTHENTICATION_UNSUPPORTED,
    AUTHENTICATION_SECURITY_UPDATE_REQUIRED,
    PREFERENCE_WRITE_FAILED,
}

enum class AppSecurityCallbackDisposition {
    APPLIED,
    STALE_IGNORED,
}

data class AppSecurityAuthAttempt(
    val id: Long,
    val purpose: AppSecurityAuthPurpose,
)

data class AppSecurityState(
    val preferences: AppSecurityPreferences,
    val lockState: AppLockState,
    val authCapability: AppSecurityAuthCapability,
    val activeAttempt: AppSecurityAuthAttempt? = null,
    val credentialHandoffAttemptId: Long? = null,
    val failure: AppSecurityFailure? = null,
) {
    val effectiveWindowSecure: Boolean
        get() = preferences.effectiveWindowSecure || credentialHandoffAttemptId != null

    val protectedContentVisible: Boolean
        get() = !preferences.appLockEnabled || lockState == AppLockState.UNLOCKED
}

/**
 * Pure, process-local app-lock state. Constructing a new instance represents a process start:
 * an enabled lock always starts locked, and an unlocked session is never restored.
 */
class AppSecurityStateMachine(
    initialPreferences: AppSecurityPreferences,
    initialAuthCapability: AppSecurityAuthCapability,
    private val preferenceWriter: AppSecurityPreferenceWriter,
) {
    var state: AppSecurityState = AppSecurityState(
        preferences = initialPreferences,
        lockState = if (initialPreferences.appLockEnabled) AppLockState.LOCKED else AppLockState.DISABLED,
        authCapability = initialAuthCapability,
    )
        private set

    private var lastAttemptId = 0L

    @Synchronized
    fun onAppForegrounded(capability: AppSecurityAuthCapability) {
        val activeAttempt = state.activeAttempt
        if (activeAttempt != null && capability != AppSecurityAuthCapability.AVAILABLE) {
            state = stableStateAfterAttempt(
                attempt = activeAttempt,
                failure = capability.failure,
                capability = capability,
            )
            return
        }
        state = state.copy(
            authCapability = capability,
            failure = state.failure?.takeUnless {
                it.isAvailabilityFailure() && capability == AppSecurityAuthCapability.AVAILABLE
            },
        )
    }

    @Synchronized
    fun onAppBackgrounded(isChangingConfigurations: Boolean) {
        if (isChangingConfigurations) return
        state = state.copy(
            lockState = if (state.preferences.appLockEnabled) AppLockState.LOCKED else AppLockState.DISABLED,
            activeAttempt = null,
            credentialHandoffAttemptId = null,
            failure = null,
        )
    }

    @Synchronized
    fun beginAuthentication(purpose: AppSecurityAuthPurpose): AppSecurityAuthAttempt? {
        if (!canBegin(purpose)) return null
        if (state.authCapability != AppSecurityAuthCapability.AVAILABLE) {
            state = state.copy(failure = state.authCapability.failure)
            return null
        }
        check(lastAttemptId < Long.MAX_VALUE) { "App-security authentication attempt ID exhausted." }
        val attempt = AppSecurityAuthAttempt(
            id = ++lastAttemptId,
            purpose = purpose,
        )
        state = state.copy(
            lockState = AppLockState.AUTHENTICATING,
            activeAttempt = attempt,
            credentialHandoffAttemptId = null,
            failure = null,
        )
        return attempt
    }

    @Synchronized
    fun beginCredentialHandoff(attemptId: Long): AppSecurityCallbackDisposition {
        matchingAttempt(attemptId) ?: return AppSecurityCallbackDisposition.STALE_IGNORED
        if (state.credentialHandoffAttemptId != null) {
            return AppSecurityCallbackDisposition.STALE_IGNORED
        }
        state = state.copy(credentialHandoffAttemptId = attemptId)
        return AppSecurityCallbackDisposition.APPLIED
    }

    @Synchronized
    fun authenticationSucceeded(attemptId: Long): AppSecurityCallbackDisposition {
        val attempt = matchingBiometricAttempt(attemptId)
            ?: return AppSecurityCallbackDisposition.STALE_IGNORED
        return applyAuthenticationSuccess(attempt)
    }

    @Synchronized
    fun credentialAuthenticationSucceeded(attemptId: Long): AppSecurityCallbackDisposition {
        val attempt = matchingCredentialAttempt(attemptId)
            ?: return AppSecurityCallbackDisposition.STALE_IGNORED
        return applyAuthenticationSuccess(attempt)
    }

    private fun applyAuthenticationSuccess(
        attempt: AppSecurityAuthAttempt,
    ): AppSecurityCallbackDisposition {
        state = when (attempt.purpose) {
            AppSecurityAuthPurpose.UNLOCK -> state.copy(
                lockState = AppLockState.UNLOCKED,
                activeAttempt = null,
                credentialHandoffAttemptId = null,
                failure = null,
            )

            AppSecurityAuthPurpose.ENABLE -> persistAuthenticatedChange(
                attempt = attempt,
                updatedPreferences = state.preferences.copy(appLockEnabled = true),
                successState = AppLockState.UNLOCKED,
                failureState = AppLockState.DISABLED,
            )

            AppSecurityAuthPurpose.DISABLE -> persistAuthenticatedChange(
                attempt = attempt,
                updatedPreferences = state.preferences.copy(appLockEnabled = false),
                successState = AppLockState.DISABLED,
                failureState = AppLockState.UNLOCKED,
            )
        }
        return AppSecurityCallbackDisposition.APPLIED
    }

    /** A non-terminal biometric mismatch; the platform prompt remains active for another attempt. */
    @Synchronized
    fun authenticationFailed(attemptId: Long): AppSecurityCallbackDisposition {
        matchingBiometricAttempt(attemptId) ?: return AppSecurityCallbackDisposition.STALE_IGNORED
        state = state.copy(failure = AppSecurityFailure.AUTHENTICATION_FAILED)
        return AppSecurityCallbackDisposition.APPLIED
    }

    @Synchronized
    fun authenticationError(
        attemptId: Long,
        failure: AppSecurityFailure,
    ): AppSecurityCallbackDisposition {
        require(failure.isAuthenticationTerminalFailure()) {
            "A terminal authentication callback requires a terminal authentication failure."
        }
        val attempt = matchingBiometricAttempt(attemptId)
            ?: return AppSecurityCallbackDisposition.STALE_IGNORED
        state = stableStateAfterAttempt(attempt, failure, state.authCapability)
        return AppSecurityCallbackDisposition.APPLIED
    }

    @Synchronized
    fun credentialAuthenticationError(
        attemptId: Long,
        failure: AppSecurityFailure,
    ): AppSecurityCallbackDisposition {
        require(failure.isAuthenticationTerminalFailure()) {
            "A terminal authentication callback requires a terminal authentication failure."
        }
        val attempt = matchingCredentialAttempt(attemptId)
            ?: return AppSecurityCallbackDisposition.STALE_IGNORED
        state = stableStateAfterAttempt(attempt, failure, state.authCapability)
        return AppSecurityCallbackDisposition.APPLIED
    }

    @Synchronized
    fun setScreenshotBlockingEnabled(enabled: Boolean): Boolean {
        if (state.preferences.screenshotBlockingEnabled == enabled) {
            state = state.copy(failure = null)
            return true
        }
        val updatedPreferences = state.preferences.copy(screenshotBlockingEnabled = enabled)
        return if (writePreferences(updatedPreferences)) {
            state = state.copy(preferences = updatedPreferences, failure = null)
            true
        } else {
            state = state.copy(failure = AppSecurityFailure.PREFERENCE_WRITE_FAILED)
            false
        }
    }

    @Synchronized
    fun clearFailure() {
        state = state.copy(failure = null)
    }

    private fun canBegin(purpose: AppSecurityAuthPurpose): Boolean {
        if (state.activeAttempt != null || state.lockState == AppLockState.AUTHENTICATING) return false
        return when (purpose) {
            AppSecurityAuthPurpose.UNLOCK ->
                state.preferences.appLockEnabled && state.lockState == AppLockState.LOCKED

            AppSecurityAuthPurpose.ENABLE ->
                !state.preferences.appLockEnabled && state.lockState == AppLockState.DISABLED

            AppSecurityAuthPurpose.DISABLE ->
                state.preferences.appLockEnabled && state.lockState == AppLockState.UNLOCKED
        }
    }

    private fun matchingAttempt(attemptId: Long): AppSecurityAuthAttempt? {
        return state.activeAttempt?.takeIf { attempt -> attempt.id == attemptId }
    }

    private fun matchingBiometricAttempt(attemptId: Long): AppSecurityAuthAttempt? {
        if (state.credentialHandoffAttemptId != null) return null
        return matchingAttempt(attemptId)
    }

    private fun matchingCredentialAttempt(attemptId: Long): AppSecurityAuthAttempt? {
        if (state.credentialHandoffAttemptId != attemptId) return null
        return matchingAttempt(attemptId)
    }

    private fun persistAuthenticatedChange(
        attempt: AppSecurityAuthAttempt,
        updatedPreferences: AppSecurityPreferences,
        successState: AppLockState,
        failureState: AppLockState,
    ): AppSecurityState {
        check(state.activeAttempt == attempt)
        return if (writePreferences(updatedPreferences)) {
            state.copy(
                preferences = updatedPreferences,
                lockState = successState,
                activeAttempt = null,
                credentialHandoffAttemptId = null,
                failure = null,
            )
        } else {
            state.copy(
                lockState = failureState,
                activeAttempt = null,
                credentialHandoffAttemptId = null,
                failure = AppSecurityFailure.PREFERENCE_WRITE_FAILED,
            )
        }
    }

    private fun writePreferences(preferences: AppSecurityPreferences): Boolean {
        return runCatching { preferenceWriter.write(preferences) }.getOrDefault(false)
    }

    private fun stableStateAfterAttempt(
        attempt: AppSecurityAuthAttempt,
        failure: AppSecurityFailure,
        capability: AppSecurityAuthCapability,
    ): AppSecurityState {
        val fallbackState = when (attempt.purpose) {
            AppSecurityAuthPurpose.UNLOCK -> AppLockState.LOCKED
            AppSecurityAuthPurpose.ENABLE -> AppLockState.DISABLED
            AppSecurityAuthPurpose.DISABLE -> AppLockState.UNLOCKED
        }
        return state.copy(
            lockState = fallbackState,
            authCapability = capability,
            activeAttempt = null,
            credentialHandoffAttemptId = null,
            failure = failure,
        )
    }

    private val AppSecurityAuthCapability.failure: AppSecurityFailure
        get() = when (this) {
            AppSecurityAuthCapability.AVAILABLE -> AppSecurityFailure.AUTHENTICATION_FAILED
            AppSecurityAuthCapability.NOT_CONFIGURED -> AppSecurityFailure.AUTHENTICATION_NOT_CONFIGURED
            AppSecurityAuthCapability.TEMPORARILY_UNAVAILABLE ->
                AppSecurityFailure.AUTHENTICATION_TEMPORARILY_UNAVAILABLE

            AppSecurityAuthCapability.UNSUPPORTED -> AppSecurityFailure.AUTHENTICATION_UNSUPPORTED
            AppSecurityAuthCapability.SECURITY_UPDATE_REQUIRED ->
                AppSecurityFailure.AUTHENTICATION_SECURITY_UPDATE_REQUIRED
        }

    private fun AppSecurityFailure.isAvailabilityFailure(): Boolean {
        return this == AppSecurityFailure.AUTHENTICATION_NOT_CONFIGURED ||
            this == AppSecurityFailure.AUTHENTICATION_TEMPORARILY_UNAVAILABLE ||
            this == AppSecurityFailure.AUTHENTICATION_UNSUPPORTED ||
            this == AppSecurityFailure.AUTHENTICATION_SECURITY_UPDATE_REQUIRED
    }

    private fun AppSecurityFailure.isAuthenticationTerminalFailure(): Boolean {
        return this == AppSecurityFailure.AUTHENTICATION_CANCELLED ||
            this == AppSecurityFailure.AUTHENTICATION_LOCKED_OUT ||
            isAvailabilityFailure()
    }
}
