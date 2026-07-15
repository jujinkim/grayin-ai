package ai.grayin.connectors.location

import ai.grayin.core.enrichment.GeoCoordinate
import ai.grayin.core.model.ConfidenceLevel
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPlaceClusterPolicyTest {
    @Test
    fun `cluster identity is stable for the same rounded coordinate`() {
        val first = cluster(GeoCoordinate(37.56649, 126.97804))
        val sameRoundedCoordinate = cluster(GeoCoordinate(37.5664, 126.9784))
        val anotherCoordinate = cluster(GeoCoordinate(37.568, 126.978))

        assertEquals(first.id, sameRoundedCoordinate.id)
        assertNotEquals(first.id, anotherCoordinate.id)
        assertTrue(first.id.matches(Regex("place-cluster:location:[a-f0-9]{32}")))
        assertEquals(37.566, first.centroidLatitude ?: Double.NaN, 0.0)
        assertEquals(126.978, first.centroidLongitude ?: Double.NaN, 0.0)
    }

    @Test
    fun `cluster contains only the closed derived observation fields`() {
        val sampleAt = Instant.parse("2026-07-15T12:00:00Z")

        val cluster = LocationPlaceClusterPolicy.create(
            coordinate = GeoCoordinate(37.5665, 126.9780),
            sourceReferenceId = SOURCE_ID,
            sampleAt = sampleAt,
            regionLabel = "  Seoul\nJongno  ",
            radiusMeters = 42.5,
            confidence = ConfidenceLevel.MEDIUM,
        )

        assertNull(cluster.label)
        assertEquals("Seoul Jongno", cluster.regionLabel)
        assertEquals(sampleAt, cluster.firstSeenAt)
        assertEquals(sampleAt, cluster.lastSeenAt)
        assertEquals(42.5, cluster.radiusMeters ?: Double.NaN, 0.0)
        assertEquals(1, cluster.visitCount)
        assertEquals(listOf(SOURCE_ID), cluster.sourceReferenceIds)
        assertEquals(ConfidenceLevel.MEDIUM, cluster.confidence)
    }

    @Test
    fun `closed provider values are normalized and bounded`() {
        assertEquals("Seoul Jongno", LocationPlaceClusterPolicy.closedRegionLabel(" Seoul\t Jongno "))
        assertEquals(128, LocationPlaceClusterPolicy.closedRegionLabel("가".repeat(200))?.length)
        assertEquals(64, LocationPlaceClusterPolicy.closedKeyword("a".repeat(100))?.length)
        assertNull(LocationPlaceClusterPolicy.closedRegionLabel("\u0000"))
        assertNull(LocationPlaceClusterPolicy.closedKeyword("   "))
    }

    @Test
    fun `effective fallback sample time participates in source identity`() {
        val coordinate = GeoCoordinate(37.5665, 126.9780)
        val first = LocationPlaceClusterPolicy.sourceIdentityMaterial(
            provider = "gps",
            sampleAt = Instant.parse("2026-07-15T12:00:00Z"),
            coordinate = coordinate,
        )
        val replay = LocationPlaceClusterPolicy.sourceIdentityMaterial(
            provider = "gps",
            sampleAt = Instant.parse("2026-07-15T12:00:00Z"),
            coordinate = coordinate,
        )
        val laterFallbackObservation = LocationPlaceClusterPolicy.sourceIdentityMaterial(
            provider = "gps",
            sampleAt = Instant.parse("2026-07-15T12:01:00Z"),
            coordinate = coordinate,
        )

        assertEquals(first, replay)
        assertNotEquals(first, laterFallbackObservation)
    }

    private fun cluster(coordinate: GeoCoordinate) = LocationPlaceClusterPolicy.create(
        coordinate = coordinate,
        sourceReferenceId = SOURCE_ID,
        sampleAt = Instant.parse("2026-07-15T12:00:00Z"),
        regionLabel = null,
        confidence = ConfidenceLevel.MEDIUM,
    )

    private companion object {
        const val SOURCE_ID = "source:location:sample"
    }
}
