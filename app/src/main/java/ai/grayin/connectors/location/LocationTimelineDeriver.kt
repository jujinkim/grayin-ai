package ai.grayin.connectors.location

import ai.grayin.core.enrichment.GeoCoordinate
import ai.grayin.core.model.ConfidenceLevel
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A location observation after the platform fix has been reduced to the approved 0.001-degree
 * projection. Exact platform fixes must never be placed in this type.
 */
internal data class RoundedLocationObservation(
    val sourceReferenceId: String,
    val observedAt: Instant,
    val coordinate: GeoCoordinate,
    val accuracyMeters: Double? = null,
) {
    init {
        require(sourceReferenceId.startsWith("source:${LocationConnector.CONNECTOR_ID}:")) {
            "A rounded location observation must reference a location source."
        }
        require(LocationPlaceClusterPolicy.roundedCoordinate(coordinate.latitude, coordinate.longitude) == coordinate) {
            "A location timeline cannot receive an exact or unrounded coordinate."
        }
        require(accuracyMeters == null || accuracyMeters.isFinite() && accuracyMeters in 0.0..MAX_ACCURACY_METERS) {
            "Location accuracy must be absent or inside the closed derived range."
        }
    }

    companion object {
        private const val MAX_ACCURACY_METERS = 100_000.0
    }
}

internal data class DerivedLocationStay(
    val placeClusterId: String,
    val sourceReferenceIds: List<String>,
    val startedAt: Instant,
    val endedAt: Instant,
    val durationMinutes: Long,
    val observationCount: Int,
    val confidence: ConfidenceLevel,
)

internal data class DerivedMovementLeg(
    val fromPlaceClusterId: String,
    val toPlaceClusterId: String,
    val sourceReferenceIds: List<String>,
    val departedAt: Instant,
    val arrivedAt: Instant,
    val durationMinutes: Long,
    val distanceBucket: MovementDistanceBucket,
    val confidence: ConfidenceLevel,
)

internal enum class MovementDistanceBucket {
    NEARBY,
    LOCAL,
    REGIONAL,
    LONG_DISTANCE,
}

internal data class DerivedLocationTimeline(
    val stays: List<DerivedLocationStay>,
    val movementLegs: List<DerivedMovementLeg>,
    val ignoredDuplicateObservationCount: Int,
    val coverageGapCount: Int,
)

/**
 * Converts already-rounded observations into bounded stay and movement candidates. It retains no
 * exact platform fix and emits movement only when adjacent observations are close enough in time
 * to support the claim.
 */
internal object LocationTimelineDeriver {
    fun derive(observations: List<RoundedLocationObservation>): DerivedLocationTimeline {
        val distinctObservations = observations
            .distinctBy(RoundedLocationObservation::sourceReferenceId)
            .sortedBy(RoundedLocationObservation::observedAt)
        if (distinctObservations.isEmpty()) {
            return DerivedLocationTimeline(
                stays = emptyList(),
                movementLegs = emptyList(),
                ignoredDuplicateObservationCount = 0,
                coverageGapCount = 0,
            )
        }

        val runs = mutableListOf<ObservationRun>()
        distinctObservations.forEach { observation ->
            val clusterId = LocationPlaceClusterPolicy.clusterId(observation.coordinate)
            val current = runs.lastOrNull()
            val startsNewRun = current == null ||
                current.placeClusterId != clusterId ||
                Duration.between(current.observations.last().observedAt, observation.observedAt) > MAX_CONTIGUOUS_GAP
            if (startsNewRun) {
                runs += ObservationRun(clusterId, observation.coordinate, mutableListOf(observation))
            } else {
                current.observations += observation
            }
        }

        val stays = runs.map { run -> run.toStay() }
        val movementLegs = mutableListOf<DerivedMovementLeg>()
        var coverageGapCount = 0
        runs.zipWithNext().forEach { (from, to) ->
            val departedAt = from.observations.last().observedAt
            val arrivedAt = to.observations.first().observedAt
            val gap = Duration.between(departedAt, arrivedAt)
            if (from.placeClusterId == to.placeClusterId || gap > MAX_MOVEMENT_GAP) {
                coverageGapCount += 1
                return@forEach
            }
            movementLegs += DerivedMovementLeg(
                fromPlaceClusterId = from.placeClusterId,
                toPlaceClusterId = to.placeClusterId,
                sourceReferenceIds = listOf(
                    from.observations.last().sourceReferenceId,
                    to.observations.first().sourceReferenceId,
                ),
                departedAt = departedAt,
                arrivedAt = arrivedAt,
                durationMinutes = gap.toMinutes().coerceAtLeast(0),
                distanceBucket = distanceBucket(from.coordinate, to.coordinate),
                confidence = movementConfidence(from.observations.last(), to.observations.first(), gap),
            )
        }

        return DerivedLocationTimeline(
            stays = stays,
            movementLegs = movementLegs,
            ignoredDuplicateObservationCount = observations.size - distinctObservations.size,
            coverageGapCount = coverageGapCount,
        )
    }

    private fun ObservationRun.toStay(): DerivedLocationStay {
        val first = observations.first()
        val last = observations.last()
        val duration = Duration.between(first.observedAt, last.observedAt).toMinutes().coerceAtLeast(0)
        return DerivedLocationStay(
            placeClusterId = placeClusterId,
            sourceReferenceIds = observations.map(RoundedLocationObservation::sourceReferenceId),
            startedAt = first.observedAt,
            endedAt = last.observedAt,
            durationMinutes = duration,
            observationCount = observations.size,
            confidence = stayConfidence(observations, duration),
        )
    }

    private fun stayConfidence(observations: List<RoundedLocationObservation>, durationMinutes: Long): ConfidenceLevel {
        val worstAccuracy = observations.mapNotNull(RoundedLocationObservation::accuracyMeters).maxOrNull()
            ?: return ConfidenceLevel.LOW
        return when {
            observations.size >= 3 && durationMinutes >= 10 && worstAccuracy <= 100.0 -> ConfidenceLevel.HIGH
            observations.size >= 2 && worstAccuracy <= 500.0 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }

    private fun movementConfidence(
        from: RoundedLocationObservation,
        to: RoundedLocationObservation,
        gap: Duration,
    ): ConfidenceLevel {
        val worstAccuracy = listOfNotNull(from.accuracyMeters, to.accuracyMeters).maxOrNull()
            ?: return ConfidenceLevel.LOW
        return when {
            gap <= Duration.ofMinutes(30) && worstAccuracy <= 100.0 -> ConfidenceLevel.HIGH
            gap <= Duration.ofHours(2) && worstAccuracy <= 1_000.0 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }

    private fun distanceBucket(from: GeoCoordinate, to: GeoCoordinate): MovementDistanceBucket {
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val fromLatitude = Math.toRadians(from.latitude)
        val toLatitude = Math.toRadians(to.latitude)
        val haversine = (sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(fromLatitude) * cos(toLatitude) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
            ).coerceIn(0.0, 1.0)
        val distanceKilometers = 2 * EARTH_RADIUS_KILOMETERS * atan2(sqrt(haversine), sqrt(1 - haversine))
        return when {
            distanceKilometers < 5.0 -> MovementDistanceBucket.NEARBY
            distanceKilometers < 25.0 -> MovementDistanceBucket.LOCAL
            distanceKilometers < 150.0 -> MovementDistanceBucket.REGIONAL
            else -> MovementDistanceBucket.LONG_DISTANCE
        }
    }

    private data class ObservationRun(
        val placeClusterId: String,
        val coordinate: GeoCoordinate,
        val observations: MutableList<RoundedLocationObservation>,
    )

    private val MAX_CONTIGUOUS_GAP: Duration = Duration.ofMinutes(90)
    private val MAX_MOVEMENT_GAP: Duration = Duration.ofHours(6)
    private const val EARTH_RADIUS_KILOMETERS = 6_371.0
}
