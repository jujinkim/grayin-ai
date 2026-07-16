package ai.grayin.connectors.location

import ai.grayin.core.enrichment.GeoCoordinate
import ai.grayin.core.model.ConfidenceLevel
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTimelineDeriverTest {
    @Test
    fun `consecutive rounded observations form one high confidence stay`() {
        val result = LocationTimelineDeriver.derive(
            listOf(
                observation(1, "2026-07-15T00:00:00Z", SEOUL, 40.0),
                observation(2, "2026-07-15T00:10:00Z", SEOUL, 50.0),
                observation(3, "2026-07-15T00:20:00Z", SEOUL, 60.0),
            ),
        )

        assertEquals(1, result.stays.size)
        assertEquals(0, result.movementLegs.size)
        assertEquals(20, result.stays.single().durationMinutes)
        assertEquals(3, result.stays.single().observationCount)
        assertEquals(ConfidenceLevel.HIGH, result.stays.single().confidence)
        assertEquals(0, result.coverageGapCount)
    }

    @Test
    fun `cluster changes derive bounded movement legs and distance buckets`() {
        val result = LocationTimelineDeriver.derive(
            listOf(
                observation(1, "2026-07-15T00:00:00Z", SEOUL, 40.0),
                observation(2, "2026-07-15T00:10:00Z", SEOUL, 40.0),
                observation(3, "2026-07-15T00:20:00Z", NEARBY, 70.0),
                observation(4, "2026-07-15T00:30:00Z", NEARBY, 70.0),
                observation(5, "2026-07-15T01:00:00Z", BUSAN, 80.0),
            ),
        )

        assertEquals(3, result.stays.size)
        assertEquals(2, result.movementLegs.size)
        assertEquals(MovementDistanceBucket.NEARBY, result.movementLegs[0].distanceBucket)
        assertEquals(MovementDistanceBucket.LONG_DISTANCE, result.movementLegs[1].distanceBucket)
        assertEquals(ConfidenceLevel.HIGH, result.movementLegs[0].confidence)
        assertEquals(ConfidenceLevel.HIGH, result.movementLegs[1].confidence)
        assertEquals(0, result.coverageGapCount)
    }

    @Test
    fun `long observation gaps never claim a movement leg`() {
        val result = LocationTimelineDeriver.derive(
            listOf(
                observation(1, "2026-07-15T00:00:00Z", SEOUL, 30.0),
                observation(2, "2026-07-15T12:00:00Z", BUSAN, 30.0),
            ),
        )

        assertEquals(2, result.stays.size)
        assertTrue(result.movementLegs.isEmpty())
        assertEquals(1, result.coverageGapCount)
    }

    @Test
    fun `duplicate source observations are ignored deterministically`() {
        val duplicate = observation(1, "2026-07-15T00:00:00Z", SEOUL, 30.0)
        val result = LocationTimelineDeriver.derive(listOf(duplicate, duplicate))

        assertEquals(1, result.stays.size)
        assertEquals(1, result.ignoredDuplicateObservationCount)
        assertEquals(listOf(duplicate.sourceReferenceId), result.stays.single().sourceReferenceIds)
    }

    @Test
    fun `unrounded exact coordinates are rejected at the derivation boundary`() {
        expectIllegalArgument {
            RoundedLocationObservation(
                sourceReferenceId = sourceId(1),
                observedAt = Instant.parse("2026-07-15T00:00:00Z"),
                coordinate = GeoCoordinate(37.5665, 126.9784),
                accuracyMeters = 20.0,
            )
        }
    }

    @Test
    fun `invalid accuracy is rejected before timeline derivation`() {
        expectIllegalArgument {
            observation(1, "2026-07-15T00:00:00Z", SEOUL, Double.NaN)
        }
    }

    private fun observation(
        id: Int,
        observedAt: String,
        coordinate: GeoCoordinate,
        accuracyMeters: Double?,
    ): RoundedLocationObservation {
        return RoundedLocationObservation(
            sourceReferenceId = sourceId(id),
            observedAt = Instant.parse(observedAt),
            coordinate = coordinate,
            accuracyMeters = accuracyMeters,
        )
    }

    private fun sourceId(id: Int): String {
        return "source:location:" + id.toString(16).padStart(32, '0')
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }

    private companion object {
        val SEOUL = GeoCoordinate(37.566, 126.978)
        val NEARBY = GeoCoordinate(37.570, 126.985)
        val BUSAN = GeoCoordinate(35.180, 129.075)
    }
}
