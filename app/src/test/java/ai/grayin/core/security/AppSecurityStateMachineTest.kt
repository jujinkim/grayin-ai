package ai.grayin.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSecurityStateMachineTest {
    @Test
    fun processStartWithEnabledLockIsFailClosedAndSecuresWindow() {
        val machine = machine(
            preferences = AppSecurityPreferences(
                screenshotBlockingEnabled = false,
                appLockEnabled = true,
            ),
        )

        assertEquals(AppLockState.LOCKED, machine.state.lockState)
        assertNull(machine.state.activeAttempt)
        assertTrue(machine.state.effectiveWindowSecure)
        assertFalse(machine.state.protectedContentVisible)
    }

    @Test
    fun processStartWithDisabledLockStartsDisabled() {
        val machine = machine()

        assertEquals(AppLockState.DISABLED, machine.state.lockState)
        assertFalse(machine.state.effectiveWindowSecure)
        assertTrue(machine.state.protectedContentVisible)
    }

    @Test
    fun enableRequiresAvailableAuthenticationAndCommitsBeforeUnlocking() {
        val writer = RecordingWriter()
        val machine = machine(writer = writer)

        val attempt = machine.beginAuthentication(AppSecurityAuthPurpose.ENABLE)!!

        assertEquals(AppLockState.AUTHENTICATING, machine.state.lockState)
        assertEquals(AppSecurityAuthPurpose.ENABLE, machine.state.activeAttempt?.purpose)
        assertFalse(machine.state.preferences.appLockEnabled)

        assertEquals(
            AppSecurityCallbackDisposition.APPLIED,
            machine.authenticationSucceeded(attempt.id),
        )
        assertEquals(listOf(AppSecurityPreferences(appLockEnabled = true)), writer.writes)
        assertTrue(machine.state.preferences.appLockEnabled)
        assertEquals(AppLockState.UNLOCKED, machine.state.lockState)
        assertTrue(machine.state.effectiveWindowSecure)
        assertTrue(machine.state.protectedContentVisible)
    }

    @Test
    fun unavailableAuthenticationRejectsEnableWithStableFailure() {
        val machine = machine(capability = AppSecurityAuthCapability.NOT_CONFIGURED)

        assertNull(machine.beginAuthentication(AppSecurityAuthPurpose.ENABLE))

        assertEquals(AppLockState.DISABLED, machine.state.lockState)
        assertEquals(AppSecurityFailure.AUTHENTICATION_NOT_CONFIGURED, machine.state.failure)
    }

    @Test
    fun nonterminalFailureKeepsAttemptActiveButCancellationReturnsStableState() {
        val machine = machine()
        val attempt = machine.beginAuthentication(AppSecurityAuthPurpose.ENABLE)!!

        machine.authenticationFailed(attempt.id)

        assertEquals(AppLockState.AUTHENTICATING, machine.state.lockState)
        assertEquals(attempt, machine.state.activeAttempt)
        assertEquals(AppSecurityFailure.AUTHENTICATION_FAILED, machine.state.failure)

        machine.authenticationError(attempt.id, AppSecurityFailure.AUTHENTICATION_CANCELLED)

        assertEquals(AppLockState.DISABLED, machine.state.lockState)
        assertNull(machine.state.activeAttempt)
        assertEquals(AppSecurityFailure.AUTHENTICATION_CANCELLED, machine.state.failure)
    }

    @Test
    fun unlockAndAuthenticatedDisableFollowExpectedTransitions() {
        val writer = RecordingWriter()
        val machine = machine(
            preferences = AppSecurityPreferences(appLockEnabled = true),
            writer = writer,
        )

        val unlock = machine.beginAuthentication(AppSecurityAuthPurpose.UNLOCK)!!
        machine.authenticationSucceeded(unlock.id)
        assertEquals(AppLockState.UNLOCKED, machine.state.lockState)
        assertTrue(writer.writes.isEmpty())

        val disable = machine.beginAuthentication(AppSecurityAuthPurpose.DISABLE)!!
        machine.authenticationSucceeded(disable.id)

        assertEquals(listOf(AppSecurityPreferences(appLockEnabled = false)), writer.writes)
        assertEquals(AppLockState.DISABLED, machine.state.lockState)
        assertFalse(machine.state.preferences.appLockEnabled)
        assertFalse(machine.state.effectiveWindowSecure)
    }

    @Test
    fun disableCannotBeginFromLockedSession() {
        val machine = machine(preferences = AppSecurityPreferences(appLockEnabled = true))

        assertNull(machine.beginAuthentication(AppSecurityAuthPurpose.DISABLE))
        assertEquals(AppLockState.LOCKED, machine.state.lockState)
    }

    @Test
    fun preferenceWriteFailurePreservesOldLockSettingAndSession() {
        val enableMachine = machine(writer = RecordingWriter(succeeds = false))
        val enable = enableMachine.beginAuthentication(AppSecurityAuthPurpose.ENABLE)!!
        enableMachine.authenticationSucceeded(enable.id)

        assertFalse(enableMachine.state.preferences.appLockEnabled)
        assertEquals(AppLockState.DISABLED, enableMachine.state.lockState)
        assertEquals(AppSecurityFailure.PREFERENCE_WRITE_FAILED, enableMachine.state.failure)

        val disableMachine = unlockedMachine(writer = RecordingWriter(succeeds = false))
        val disable = disableMachine.beginAuthentication(AppSecurityAuthPurpose.DISABLE)!!
        disableMachine.authenticationSucceeded(disable.id)

        assertTrue(disableMachine.state.preferences.appLockEnabled)
        assertEquals(AppLockState.UNLOCKED, disableMachine.state.lockState)
        assertTrue(disableMachine.state.effectiveWindowSecure)
        assertEquals(AppSecurityFailure.PREFERENCE_WRITE_FAILED, disableMachine.state.failure)
    }

    @Test
    fun writerExceptionMapsToStableFailure() {
        val machine = machine(writer = AppSecurityPreferenceWriter { error("disk unavailable") })
        val attempt = machine.beginAuthentication(AppSecurityAuthPurpose.ENABLE)!!

        machine.authenticationSucceeded(attempt.id)

        assertEquals(AppLockState.DISABLED, machine.state.lockState)
        assertEquals(AppSecurityFailure.PREFERENCE_WRITE_FAILED, machine.state.failure)
    }

    @Test
    fun realBackgroundRelocksAndRejectsStaleCallbacksWithIncreasingAttemptIds() {
        val machine = machine(preferences = AppSecurityPreferences(appLockEnabled = true))
        val first = machine.beginAuthentication(AppSecurityAuthPurpose.UNLOCK)!!

        machine.onAppBackgrounded(isChangingConfigurations = false)

        assertEquals(AppLockState.LOCKED, machine.state.lockState)
        assertNull(machine.state.activeAttempt)

        val second = machine.beginAuthentication(AppSecurityAuthPurpose.UNLOCK)!!
        assertTrue(second.id > first.id)
        assertEquals(
            AppSecurityCallbackDisposition.STALE_IGNORED,
            machine.authenticationSucceeded(first.id),
        )
        assertEquals(AppLockState.AUTHENTICATING, machine.state.lockState)
        assertEquals(second, machine.state.activeAttempt)
    }

    @Test
    fun configurationChangeDoesNotRelockOrInvalidateSession() {
        val machine = unlockedMachine()

        machine.onAppBackgrounded(isChangingConfigurations = true)

        assertEquals(AppLockState.UNLOCKED, machine.state.lockState)

        val disable = machine.beginAuthentication(AppSecurityAuthPurpose.DISABLE)!!
        machine.onAppBackgrounded(isChangingConfigurations = true)
        assertEquals(disable, machine.state.activeAttempt)
        assertEquals(AppLockState.AUTHENTICATING, machine.state.lockState)
    }

    @Test
    fun explicitCredentialHandoffIsCurrentAttemptFencedAndClearedBySuccess() {
        val machine = machine(preferences = AppSecurityPreferences(appLockEnabled = true))
        val attempt = machine.beginAuthentication(AppSecurityAuthPurpose.UNLOCK)!!

        assertEquals(
            AppSecurityCallbackDisposition.STALE_IGNORED,
            machine.beginCredentialHandoff(attempt.id + 1),
        )
        assertNull(machine.state.credentialHandoffAttemptId)

        assertEquals(
            AppSecurityCallbackDisposition.APPLIED,
            machine.beginCredentialHandoff(attempt.id),
        )
        assertEquals(attempt.id, machine.state.credentialHandoffAttemptId)
        assertEquals(
            AppSecurityCallbackDisposition.STALE_IGNORED,
            machine.beginCredentialHandoff(attempt.id),
        )
        assertEquals(
            AppSecurityCallbackDisposition.STALE_IGNORED,
            machine.authenticationSucceeded(attempt.id),
        )
        assertEquals(
            AppSecurityCallbackDisposition.STALE_IGNORED,
            machine.authenticationFailed(attempt.id),
        )
        assertEquals(
            AppSecurityCallbackDisposition.STALE_IGNORED,
            machine.authenticationError(
                attempt.id,
                AppSecurityFailure.AUTHENTICATION_CANCELLED,
            ),
        )
        assertEquals(AppLockState.AUTHENTICATING, machine.state.lockState)

        machine.credentialAuthenticationSucceeded(attempt.id)

        assertEquals(AppLockState.UNLOCKED, machine.state.lockState)
        assertNull(machine.state.activeAttempt)
        assertNull(machine.state.credentialHandoffAttemptId)
    }

    @Test
    fun ordinaryBackgroundInvalidatesExplicitCredentialHandoffAndLateResult() {
        val machine = machine(preferences = AppSecurityPreferences(appLockEnabled = true))
        val attempt = machine.beginAuthentication(AppSecurityAuthPurpose.UNLOCK)!!
        machine.beginCredentialHandoff(attempt.id)

        machine.onAppBackgrounded(isChangingConfigurations = false)

        assertEquals(AppLockState.LOCKED, machine.state.lockState)
        assertNull(machine.state.activeAttempt)
        assertNull(machine.state.credentialHandoffAttemptId)
        assertEquals(
            AppSecurityCallbackDisposition.STALE_IGNORED,
            machine.credentialAuthenticationSucceeded(attempt.id),
        )
    }

    @Test
    fun terminalCredentialHandoffErrorClearsItsMarker() {
        val machine = machine()
        val attempt = machine.beginAuthentication(AppSecurityAuthPurpose.ENABLE)!!
        assertFalse(machine.state.effectiveWindowSecure)
        machine.beginCredentialHandoff(attempt.id)

        assertTrue(machine.state.effectiveWindowSecure)
        machine.onAppBackgrounded(isChangingConfigurations = true)
        assertEquals(attempt.id, machine.state.credentialHandoffAttemptId)
        assertTrue(machine.state.effectiveWindowSecure)

        machine.credentialAuthenticationError(
            attempt.id,
            AppSecurityFailure.AUTHENTICATION_CANCELLED,
        )

        assertEquals(AppLockState.DISABLED, machine.state.lockState)
        assertNull(machine.state.activeAttempt)
        assertNull(machine.state.credentialHandoffAttemptId)
        assertFalse(machine.state.effectiveWindowSecure)
    }

    @Test
    fun terminalErrorsRestorePurposeSpecificStableStateWithoutWriting() {
        val enableWriter = RecordingWriter()
        val enableMachine = machine(writer = enableWriter)
        val enable = enableMachine.beginAuthentication(AppSecurityAuthPurpose.ENABLE)!!
        enableMachine.authenticationError(enable.id, AppSecurityFailure.AUTHENTICATION_LOCKED_OUT)
        assertEquals(AppLockState.DISABLED, enableMachine.state.lockState)
        assertTrue(enableWriter.writes.isEmpty())

        val disableWriter = RecordingWriter()
        val disableMachine = unlockedMachine(writer = disableWriter)
        val disable = disableMachine.beginAuthentication(AppSecurityAuthPurpose.DISABLE)!!
        disableMachine.authenticationError(disable.id, AppSecurityFailure.AUTHENTICATION_CANCELLED)
        assertEquals(AppLockState.UNLOCKED, disableMachine.state.lockState)
        assertTrue(disableMachine.state.preferences.appLockEnabled)
        assertTrue(disableWriter.writes.isEmpty())
    }

    @Test
    fun foregroundCapabilityLossTerminatesAuthenticationFailClosed() {
        val machine = machine(preferences = AppSecurityPreferences(appLockEnabled = true))
        val attempt = machine.beginAuthentication(AppSecurityAuthPurpose.UNLOCK)!!

        machine.onAppForegrounded(AppSecurityAuthCapability.SECURITY_UPDATE_REQUIRED)

        assertEquals(AppLockState.LOCKED, machine.state.lockState)
        assertNull(machine.state.activeAttempt)
        assertEquals(
            AppSecurityFailure.AUTHENTICATION_SECURITY_UPDATE_REQUIRED,
            machine.state.failure,
        )
        assertEquals(
            AppSecurityCallbackDisposition.STALE_IGNORED,
            machine.authenticationSucceeded(attempt.id),
        )
    }

    @Test
    fun screenshotPolicyIsPersistedAndOrsWithAppLock() {
        val writer = RecordingWriter()
        val machine = machine(writer = writer)

        assertTrue(machine.setScreenshotBlockingEnabled(true))
        assertTrue(machine.state.preferences.screenshotBlockingEnabled)
        assertTrue(machine.state.effectiveWindowSecure)

        assertTrue(machine.setScreenshotBlockingEnabled(false))
        assertFalse(machine.state.effectiveWindowSecure)

        val locked = machine(
            preferences = AppSecurityPreferences(
                screenshotBlockingEnabled = false,
                appLockEnabled = true,
            ),
        )
        assertTrue(locked.state.effectiveWindowSecure)
    }

    @Test
    fun screenshotWriteFailureLeavesEffectivePolicyUnchanged() {
        val enableMachine = machine(writer = RecordingWriter(succeeds = false))
        assertFalse(enableMachine.setScreenshotBlockingEnabled(true))
        assertFalse(enableMachine.state.effectiveWindowSecure)
        assertEquals(AppSecurityFailure.PREFERENCE_WRITE_FAILED, enableMachine.state.failure)

        val disableMachine = machine(
            preferences = AppSecurityPreferences(screenshotBlockingEnabled = true),
            writer = RecordingWriter(succeeds = false),
        )
        assertFalse(disableMachine.setScreenshotBlockingEnabled(false))
        assertTrue(disableMachine.state.effectiveWindowSecure)
    }

    private fun unlockedMachine(
        writer: AppSecurityPreferenceWriter = RecordingWriter(),
    ): AppSecurityStateMachine {
        val machine = machine(
            preferences = AppSecurityPreferences(appLockEnabled = true),
            writer = writer,
        )
        val unlock = machine.beginAuthentication(AppSecurityAuthPurpose.UNLOCK)!!
        machine.authenticationSucceeded(unlock.id)
        return machine
    }

    private fun machine(
        preferences: AppSecurityPreferences = AppSecurityPreferences(),
        capability: AppSecurityAuthCapability = AppSecurityAuthCapability.AVAILABLE,
        writer: AppSecurityPreferenceWriter = RecordingWriter(),
    ): AppSecurityStateMachine {
        return AppSecurityStateMachine(
            initialPreferences = preferences,
            initialAuthCapability = capability,
            preferenceWriter = writer,
        )
    }

    private class RecordingWriter(
        private val succeeds: Boolean = true,
    ) : AppSecurityPreferenceWriter {
        val writes = mutableListOf<AppSecurityPreferences>()

        override fun write(preferences: AppSecurityPreferences): Boolean {
            writes += preferences
            return succeeds
        }
    }
}
