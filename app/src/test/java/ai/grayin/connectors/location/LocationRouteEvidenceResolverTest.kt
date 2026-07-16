package ai.grayin.connectors.location

import ai.grayin.core.enrichment.GeoCoordinate
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import ai.grayin.core.retrieval.ApproximateTimeRange
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRouteEvidenceResolverTest {
    @Test
    fun `builds cited route evidence from rounded stored observations`() {
        val first = source(1, "2026-07-16T00:00:00Z")
        val second = source(2, "2026-07-16T00:30:00Z")
        val result = LocationRouteEvidenceResolver.resolve(
            sourceReferences = listOf(first, second),
            placeClusters = listOf(cluster(SEOUL, first.id, "Seoul"), cluster(BUSAN, second.id, "Busan")),
            citations = listOf(citation(first), citation(second)),
            timeRange = range,
        )

        assertEquals(1, result.size)
        assertTrue(result.single().summary.contains("Seoul"))
        assertTrue(result.single().summary.contains("Busan"))
        assertEquals(listOf("citation:location:1", "citation:location:2"), result.single().citationIds)
    }

    @Test
    fun `does not infer a route through a long observation gap`() {
        val first = source(1, "2026-07-16T00:00:00Z")
        val second = source(2, "2026-07-16T12:00:00Z")
        val result = LocationRouteEvidenceResolver.resolve(
            sourceReferences = listOf(first, second),
            placeClusters = listOf(cluster(SEOUL, first.id, "Seoul"), cluster(BUSAN, second.id, "Busan")),
            citations = listOf(citation(first), citation(second)),
            timeRange = range,
        )

        assertTrue(result.isEmpty())
    }

    private fun source(id: Int, observedAt: String): SourceReference {
        val hash = id.toString(16).padStart(32, '0')
        return SourceReference(
            id = "source:location:$hash",
            connectorId = LocationConnector.CONNECTOR_ID,
            sourceKind = SourceKind.LOCATION,
            externalIdHash = hash,
            observedAt = Instant.parse(observedAt),
            modifiedAt = Instant.parse(observedAt),
            sensitivity = SensitivityLevel.HIGH,
        )
    }

    private fun cluster(coordinate: GeoCoordinate, sourceId: String, regionLabel: String): PlaceCluster {
        return PlaceCluster(
            id = LocationPlaceClusterPolicy.clusterId(coordinate),
            regionLabel = regionLabel,
            centroidLatitude = coordinate.latitude,
            centroidLongitude = coordinate.longitude,
            radiusMeters = 80.0,
            sourceReferenceIds = listOf(sourceId),
            confidence = ConfidenceLevel.HIGH,
        )
    }

    private fun citation(source: SourceReference): MemoryCitation {
        val suffix = source.id.substringAfterLast(':').trimStart('0').ifEmpty { "0" }
        return MemoryCitation(
            id = "citation:location:$suffix",
            sourceReferenceId = source.id,
            observedAt = source.observedAt,
            confidence = ConfidenceLevel.HIGH,
            label = "Location sample",
        )
    }

    private companion object {
        val SEOUL = GeoCoordinate(37.566, 126.978)
        val BUSAN = GeoCoordinate(35.180, 129.075)
        val range = ApproximateTimeRange(
            label = "test",
            startInclusive = Instant.parse("2026-07-16T00:00:00Z"),
            endExclusive = Instant.parse("2026-07-17T00:00:00Z"),
            isFuture = false,
        )
    }
}
