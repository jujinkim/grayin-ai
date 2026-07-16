package ai.grayin.connectors.location

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationObservationAcceptancePolicyTest {
    @Test
    fun `fresh bounded candidate is accepted`() {
        assertTrue(
            LocationObservationAcceptancePolicy.shouldAccept(
                candidate = candidate("2026-07-16T00:00:00Z", 80.0),
                receivedAt = NOW,
                lastAcceptedAt = null,
            ),
        )
    }

    @Test
    fun `stale and future candidates are rejected`() {
        assertFalse(accept(candidate("2026-07-15T23:49:59Z", 80.0)))
        assertFalse(accept(candidate("2026-07-16T00:02:01Z", 80.0)))
    }

    @Test
    fun `invalid or excessively coarse accuracy is rejected`() {
        assertFalse(accept(candidate("2026-07-16T00:00:00Z", Double.NaN)))
        assertFalse(accept(candidate("2026-07-16T00:00:00Z", -1.0)))
        assertFalse(accept(candidate("2026-07-16T00:00:00Z", 5_000.1)))
    }

    @Test
    fun `missing accuracy remains usable at low downstream confidence`() {
        assertTrue(accept(candidate("2026-07-16T00:00:00Z", null)))
    }

    @Test
    fun `accepted samples are rate limited in memory`() {
        assertFalse(
            LocationObservationAcceptancePolicy.shouldAccept(
                candidate = candidate("2026-07-16T00:04:59Z", 100.0),
                receivedAt = Instant.parse("2026-07-16T00:04:59Z"),
                lastAcceptedAt = NOW,
            ),
        )
        assertTrue(
            LocationObservationAcceptancePolicy.shouldAccept(
                candidate = candidate("2026-07-16T00:05:00Z", 100.0),
                receivedAt = Instant.parse("2026-07-16T00:05:00Z"),
                lastAcceptedAt = NOW,
            ),
        )
    }

    private fun accept(candidate: LocationObservationCandidate): Boolean {
        return LocationObservationAcceptancePolicy.shouldAccept(candidate, NOW, null)
    }

    private fun candidate(sampleAt: String, accuracyMeters: Double?): LocationObservationCandidate {
        return LocationObservationCandidate(Instant.parse(sampleAt), accuracyMeters)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z")
    }
}
