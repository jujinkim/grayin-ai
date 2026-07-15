package ai.grayin.core.indexing

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticIndexingPolicyTest {
    @Test
    fun `equal window endpoints return invalid reason`() {
        val policy = policy(startHour = 2, endHour = 2)

        assertDecision(
            policy = policy,
            conditions = conditions(localTime = LocalTime.of(2, 0)),
            expected = AutomaticIndexingDecisionReason.INVALID_LOW_USAGE_WINDOW,
        )
        assertFalse(policy.lowUsageWindow.includes(LocalTime.of(2, 0)))
    }

    @Test
    fun `normal window includes start and excludes end`() {
        val policy = policy(startHour = 2, endHour = 5)

        assertAllowed(policy, LocalTime.of(2, 0))
        assertAllowed(policy, LocalTime.of(4, 59, 59))
        assertDecision(
            policy = policy,
            conditions = conditions(localTime = LocalTime.of(5, 0)),
            expected = AutomaticIndexingDecisionReason.OUTSIDE_LOW_USAGE_WINDOW,
        )
    }

    @Test
    fun `cross-midnight window includes start and excludes end`() {
        val policy = policy(startHour = 22, endHour = 2)

        assertAllowed(policy, LocalTime.of(22, 0))
        assertAllowed(policy, LocalTime.of(1, 59, 59))
        assertDecision(
            policy = policy,
            conditions = conditions(localTime = LocalTime.of(2, 0)),
            expected = AutomaticIndexingDecisionReason.OUTSIDE_LOW_USAGE_WINDOW,
        )
        assertDecision(
            policy = policy,
            conditions = conditions(localTime = LocalTime.NOON),
            expected = AutomaticIndexingDecisionReason.OUTSIDE_LOW_USAGE_WINDOW,
        )
    }

    @Test
    fun `charging and battery failures return stable reasons`() {
        val policy = policy()

        assertDecision(
            policy = policy,
            conditions = conditions(isCharging = false),
            expected = AutomaticIndexingDecisionReason.REQUIRES_CHARGING,
        )
        assertDecision(
            policy = policy,
            conditions = conditions(batteryPercent = 39),
            expected = AutomaticIndexingDecisionReason.BATTERY_BELOW_MINIMUM,
        )
        assertDecision(
            policy = policy,
            conditions = conditions(batteryPercent = -1),
            expected = AutomaticIndexingDecisionReason.BATTERY_LEVEL_UNKNOWN,
        )
    }

    @Test
    fun `hot and critical thermal states have distinct reasons`() {
        val policy = policy()

        assertDecision(
            policy = policy,
            conditions = conditions(thermalState = ThermalState.HOT),
            expected = AutomaticIndexingDecisionReason.THERMAL_STATE_HOT,
        )
        assertDecision(
            policy = policy,
            conditions = conditions(thermalState = ThermalState.CRITICAL),
            expected = AutomaticIndexingDecisionReason.THERMAL_STATE_CRITICAL,
        )
    }

    private fun assertAllowed(policy: AutomaticIndexingPolicy, localTime: LocalTime) {
        val conditions = conditions(localTime = localTime)
        assertDecision(
            policy = policy,
            conditions = conditions,
            expected = AutomaticIndexingDecisionReason.ALLOWED,
        )
        assertTrue(policy.allowsAutomaticIndexing(conditions))
    }

    private fun assertDecision(
        policy: AutomaticIndexingPolicy,
        conditions: DeviceIndexingConditions,
        expected: AutomaticIndexingDecisionReason,
    ) {
        val decision = policy.evaluate(conditions)
        assertEquals(expected, decision.reason)
        assertEquals(expected == AutomaticIndexingDecisionReason.ALLOWED, decision.isAllowed)
    }

    private fun policy(
        startHour: Int = 2,
        endHour: Int = 5,
    ): AutomaticIndexingPolicy {
        return AutomaticIndexingPolicy(
            requireCharging = true,
            lowUsageWindow = LowUsageWindow(
                start = LocalTime.of(startHour, 0),
                end = LocalTime.of(endHour, 0),
            ),
            minimumBatteryPercent = 40,
            requireAcceptableThermalState = true,
        )
    }

    private fun conditions(
        isCharging: Boolean = true,
        localTime: LocalTime = LocalTime.of(3, 0),
        batteryPercent: Int = 80,
        thermalState: ThermalState = ThermalState.NOMINAL,
    ): DeviceIndexingConditions {
        return DeviceIndexingConditions(
            isCharging = isCharging,
            localTime = localTime,
            batteryPercent = batteryPercent,
            thermalState = thermalState,
        )
    }
}
