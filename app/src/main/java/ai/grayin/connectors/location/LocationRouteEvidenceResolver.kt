package ai.grayin.connectors.location

import ai.grayin.core.enrichment.GeoCoordinate
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.SourceReference
import ai.grayin.core.retrieval.ApproximateTimeRange

/** Builds ephemeral, cited route evidence from stored rounded location observations. */
internal object LocationRouteEvidenceResolver {
    fun resolve(
        sourceReferences: List<SourceReference>,
        placeClusters: List<PlaceCluster>,
        citations: List<MemoryCitation>,
        timeRange: ApproximateTimeRange?,
    ): List<RouteEvidence> {
        val clustersBySourceId = placeClusters
            .asSequence()
            .filter { cluster -> cluster.id.startsWith("place-cluster:${LocationConnector.CONNECTOR_ID}:") }
            .mapNotNull { cluster ->
                val latitude = cluster.centroidLatitude ?: return@mapNotNull null
                val longitude = cluster.centroidLongitude ?: return@mapNotNull null
                cluster to GeoCoordinate(latitude, longitude)
            }
            .flatMap { (cluster, coordinate) ->
                cluster.sourceReferenceIds.asSequence().map { sourceId -> sourceId to ClusterObservation(cluster, coordinate) }
            }
            .toMap()
        val observations = sourceReferences
            .asSequence()
            .filter { source -> source.connectorId == LocationConnector.CONNECTOR_ID }
            .mapNotNull { source ->
                val observedAt = source.observedAt ?: return@mapNotNull null
                if (timeRange != null && (observedAt < timeRange.startInclusive || observedAt >= timeRange.endExclusive)) {
                    return@mapNotNull null
                }
                val cluster = clustersBySourceId[source.id] ?: return@mapNotNull null
                RoundedLocationObservation(
                    sourceReferenceId = source.id,
                    observedAt = observedAt,
                    coordinate = cluster.coordinate,
                    accuracyMeters = cluster.cluster.radiusMeters,
                ) to cluster.cluster
            }
            .toList()
        val timeline = LocationTimelineDeriver.derive(observations.map { (observation) -> observation })
        val citationsBySourceId = citations.groupBy(MemoryCitation::sourceReferenceId)
        val labelsByClusterId = observations.associate { (observation, cluster) ->
            LocationPlaceClusterPolicy.clusterId(observation.coordinate) to
                (cluster.regionLabel ?: "observed place")
        }
        return timeline.movementLegs.mapNotNull { leg ->
            val citationIds = leg.sourceReferenceIds
                .flatMap { sourceId -> citationsBySourceId[sourceId].orEmpty().map(MemoryCitation::id) }
                .distinct()
            if (citationIds.size < 2) return@mapNotNull null
            RouteEvidence(
                id = "route:${leg.sourceReferenceIds.joinToString(":")}",
                summary = "Observed movement from ${labelsByClusterId[leg.fromPlaceClusterId] ?: "observed place"} " +
                    "to ${labelsByClusterId[leg.toPlaceClusterId] ?: "observed place"} " +
                    "(${leg.distanceBucket.name.lowercase().replace('_', ' ')}, ${leg.durationMinutes} minutes).",
                occurredAt = leg.departedAt,
                confidence = leg.confidence,
                citationIds = citationIds,
            )
        }
    }

    internal data class RouteEvidence(
        val id: String,
        val summary: String,
        val occurredAt: java.time.Instant,
        val confidence: ConfidenceLevel,
        val citationIds: List<String>,
    )

    private data class ClusterObservation(
        val cluster: PlaceCluster,
        val coordinate: GeoCoordinate,
    )
}
