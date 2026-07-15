package ai.grayin.core.store

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.PlaceCluster
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPlaceClusterMergePolicyTest {
    @Test
    fun `new source merges atomically into the stable cluster`() {
        val existing = cluster(
            sourceId = SOURCE_ONE,
            observedAt = FIRST,
            regionLabel = "Old region",
            radiusMeters = 80.0,
            confidence = ConfidenceLevel.LOW,
        ).copy(label = "Home")
        val incoming = cluster(
            sourceId = SOURCE_TWO,
            observedAt = LAST,
            regionLabel = "New region",
            radiusMeters = 120.0,
            confidence = ConfidenceLevel.MEDIUM,
        )

        val merged = mergeLocationPlaceCluster(existing, incoming, setOf(SOURCE_TWO))

        assertEquals("Home", merged.label)
        assertEquals("New region", merged.regionLabel)
        assertEquals(120.0, merged.radiusMeters ?: Double.NaN, 0.0)
        assertEquals(FIRST, merged.firstSeenAt)
        assertEquals(LAST, merged.lastSeenAt)
        assertEquals(2, merged.visitCount)
        assertEquals(listOf(SOURCE_ONE, SOURCE_TWO), merged.sourceReferenceIds)
        assertEquals(ConfidenceLevel.MEDIUM, merged.confidence)
    }

    @Test
    fun `recommitting an existing source is idempotent`() {
        val incoming = cluster(SOURCE_TWO, LAST, "Region")
        val existing = cluster(SOURCE_ONE, FIRST, "Region").copy(
            lastSeenAt = LAST,
            visitCount = 2,
            sourceReferenceIds = listOf(SOURCE_ONE, SOURCE_TWO),
        )

        val merged = mergeLocationPlaceCluster(existing, incoming, emptySet())

        assertEquals(existing, merged)
    }

    @Test
    fun `merge rejects a centroid change behind the same stable identity`() {
        val existing = cluster(SOURCE_ONE, FIRST, "Region")
        val incoming = cluster(SOURCE_TWO, LAST, "Region").copy(centroidLatitude = 35.0)

        expectIllegalArgument {
            mergeLocationPlaceCluster(existing, incoming, setOf(SOURCE_TWO))
        }
    }

    private fun cluster(
        sourceId: String,
        observedAt: Instant,
        regionLabel: String?,
        radiusMeters: Double? = null,
        confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    ): PlaceCluster {
        return PlaceCluster(
            id = CLUSTER_ID,
            regionLabel = regionLabel,
            centroidLatitude = 37.566,
            centroidLongitude = 126.978,
            radiusMeters = radiusMeters,
            firstSeenAt = observedAt,
            lastSeenAt = observedAt,
            visitCount = 1,
            sourceReferenceIds = listOf(sourceId),
            confidence = confidence,
        )
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
        const val CLUSTER_ID = "place-cluster:location:0123456789abcdef0123456789abcdef"
        const val SOURCE_ONE = "source:location:one"
        const val SOURCE_TWO = "source:location:two"
        val FIRST: Instant = Instant.parse("2026-07-14T12:00:00Z")
        val LAST: Instant = Instant.parse("2026-07-15T12:00:00Z")
    }
}
