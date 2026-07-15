package ai.grayin.connectors.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import ai.grayin.core.connector.ConnectorDeleteRequest
import ai.grayin.core.connector.ConnectorDeleteResult
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.connector.ConnectorPermissionState
import ai.grayin.core.connector.ConnectorRevokeResult
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanScope
import ai.grayin.core.connector.InvokableMemoryConnector
import ai.grayin.core.enrichment.AndroidOnlineEnrichmentGateway
import ai.grayin.core.enrichment.EnrichmentResult
import ai.grayin.core.enrichment.GeoCoordinate
import ai.grayin.core.enrichment.OnlineEnrichmentPreferences
import ai.grayin.core.enrichment.OnlineEnrichmentFeature
import ai.grayin.core.enrichment.OnlineEnrichmentGateway
import ai.grayin.core.enrichment.OnlineEnrichmentPolicy
import ai.grayin.core.enrichment.PlaceLookupResult
import ai.grayin.core.enrichment.ReverseGeocodeRequest
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.ConnectorState
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.roundToInt

class LocationConnector(
    private val context: Context,
    private val enrichmentGateway: OnlineEnrichmentGateway = AndroidOnlineEnrichmentGateway(context.applicationContext),
    private val enrichmentPreferences: OnlineEnrichmentPreferences = OnlineEnrichmentPreferences(context.applicationContext),
) : InvokableMemoryConnector {
    override val metadata: ConnectorMetadata = METADATA

    override suspend fun currentState(): ConnectorState {
        val permissionGranted = hasLocationPermission()
        val enabled = permissionGranted && isEnabled()
        val lastIndexedAt = prefs().getString(KEY_LAST_INDEXED_AT, null)?.let(Instant::parse)
        return ConnectorState(
            connectorId = CONNECTOR_ID,
            displayName = metadata.displayName,
            enabled = enabled,
            availability = when {
                enabled -> SourceAvailability.AVAILABLE
                permissionGranted -> SourceAvailability.DISABLED
                else -> SourceAvailability.MISSING_PERMISSION
            },
            permissionGranted = permissionGranted,
            capabilities = metadata.connectorCapabilities,
            sensitivity = metadata.sensitivity,
            processingState = when {
                !enabled -> ProcessingState.SKIPPED
                lastIndexedAt != null -> ProcessingState.COMPLETED
                else -> ProcessingState.STALE
            },
            lastIndexedAt = lastIndexedAt,
        )
    }

    override suspend fun permissionState(): ConnectorPermissionState {
        val permissionGranted = hasLocationPermission()
        return ConnectorPermissionState(
            connectorId = CONNECTOR_ID,
            availability = if (permissionGranted) SourceAvailability.AVAILABLE else SourceAvailability.MISSING_PERMISSION,
            permissionGranted = permissionGranted,
            canRequestPermission = true,
            requiredPlatformPermissions = listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            explanation = if (permissionGranted) {
                "Location permission is available. Invoke this source before indexing."
            } else {
                "Grant location permission to invoke location samples for indexing."
            },
        )
    }

    override suspend fun invoke(): ConnectorPermissionState {
        val permissionState = permissionState()
        if (permissionState.permissionGranted) {
            prefs().edit().putBoolean(KEY_ENABLED, true).apply()
        }
        return permissionState
    }

    override suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult {
        val now = Instant.now()
        if (!hasLocationPermission()) {
            return skipped(now, SourceAvailability.MISSING_PERMISSION, "Location permission was not granted.")
        }
        if (!isEnabled()) {
            return skipped(now, SourceAvailability.DISABLED, "Location source has not been invoked.")
        }

        val location = lastKnownLocation()
            ?: return skipped(now, SourceAvailability.NOT_INDEXED, "No last known location sample is available.")
        val sampleAt = location.sampleAt(now)
        val coordinate = location.roundedCoordinate()
        val placeLookup = placeLookup(coordinate)
        val result = location.toExtractionResult(now, sampleAt, coordinate, placeLookup)
        prefs().edit().putString(KEY_LAST_INDEXED_AT, now.toString()).apply()
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = ProcessingState.COMPLETED,
            sourceReferences = listOf(result.sourceReference),
            derivedEvents = listOf(result.derivedEvent),
            citations = listOf(result.citation),
            scannedAt = now,
        )
    }

    override suspend fun revoke(): ConnectorRevokeResult {
        prefs().edit().clear().apply()
        enrichmentPreferences.setEnabled(false)
        return ConnectorRevokeResult(
            connectorId = CONNECTOR_ID,
            revokedAt = Instant.now(),
            permissionState = permissionState(),
            deleteRequest = ConnectorDeleteRequest(connectorId = CONNECTOR_ID),
        )
    }

    override suspend fun deleteDerivedData(request: ConnectorDeleteRequest): ConnectorDeleteResult {
        return ConnectorDeleteResult(
            connectorId = request.connectorId,
            completedAt = Instant.now(),
        )
    }

    private fun lastKnownLocation(): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return manager.getProviders(true)
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }

    private suspend fun placeLookup(coordinate: GeoCoordinate): PlaceLookupResult? {
        if (!enrichmentPreferences.isEnabled()) return null
        return runCatching {
            OnlineEnrichmentPolicy.requireAllowed(OnlineEnrichmentFeature.REVERSE_GEOCODE_LOOKUP)
            when (val result = enrichmentGateway.reverseGeocode(ReverseGeocodeRequest(coordinate))) {
                is EnrichmentResult.Available -> result.value
                is EnrichmentResult.Unavailable -> null
            }
        }.getOrNull()
    }

    private fun Location.toExtractionResult(
        observedAt: Instant,
        sampleAt: Instant,
        coordinate: GeoCoordinate,
        placeLookup: PlaceLookupResult?,
    ): LocationExtractionResult {
        val sourceHash = sha256("${provider.orEmpty()}:$time:${coordinate.latitude}:${coordinate.longitude}")
        val sourceId = "source:$CONNECTOR_ID:$sourceHash"
        val eventId = "event:$CONNECTOR_ID:$sourceHash"
        val citationId = "citation:$CONNECTOR_ID:$sourceHash"
        val region = placeLookup?.displayLabel() ?: "lat ${coordinate.latitude}, lon ${coordinate.longitude}"
        val placeKeywords = placeLookup?.keywords().orEmpty()
        return LocationExtractionResult(
            sourceReference = SourceReference(
                id = sourceId,
                connectorId = CONNECTOR_ID,
                sourceKind = SourceKind.LOCATION,
                localPointer = provider?.let { "location-provider:$it" },
                externalIdHash = sourceHash,
                observedAt = observedAt,
                modifiedAt = sampleAt,
                sensitivity = SensitivityLevel.HIGH,
            ),
            derivedEvent = DerivedMemoryEvent(
                id = eventId,
                kind = DerivedMemoryEventKind.PLACE_VISIT,
                sourceReferenceIds = listOf(sourceId),
                summary = "Location sample indexed near $region at $sampleAt.",
                startedAt = sampleAt,
                keywords = listOf("location", "place", provider.orEmpty())
                    .plus(placeKeywords)
                    .filter { it.isNotBlank() },
                labels = listOf("location", "place-visit", provider.orEmpty())
                    .plus(placeKeywords)
                    .filter { it.isNotBlank() },
                confidence = if (accuracy <= 250f) ConfidenceLevel.MEDIUM else ConfidenceLevel.LOW,
                sensitivity = SensitivityLevel.HIGH,
                citationIds = listOf(citationId),
                createdAt = observedAt,
            ),
            citation = MemoryCitation(
                id = citationId,
                sourceReferenceId = sourceId,
                derivedMemoryEventId = eventId,
                label = "Location sample: $region",
                observedAt = observedAt,
                confidence = if (accuracy <= 250f) ConfidenceLevel.MEDIUM else ConfidenceLevel.LOW,
            ),
        )
    }

    private fun Location.sampleAt(observedAt: Instant): Instant {
        return if (time > 0L) Instant.ofEpochMilli(time) else observedAt
    }

    private fun Location.roundedCoordinate(): GeoCoordinate {
        return GeoCoordinate(
            latitude = round(latitude),
            longitude = round(longitude),
        )
    }

    private fun PlaceLookupResult.displayLabel(): String? {
        return listOf(localityLabel, regionLabel, countryCode)
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }

    private fun PlaceLookupResult.keywords(): List<String> {
        return listOf(localityLabel, regionLabel, countryCode).mapNotNull { it?.takeIf(String::isNotBlank) }
    }

    private fun skipped(
        scannedAt: Instant,
        availability: SourceAvailability,
        explanation: String,
    ): ConnectorScanResult {
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = ProcessingState.SKIPPED,
            missingSources = missingSources(availability, explanation),
            scannedAt = scannedAt,
        )
    }

    private fun missingSources(availability: SourceAvailability, explanation: String): List<MissingSource> {
        return metadata.memoryCapabilities.map { capability ->
            MissingSource(
                capability = capability,
                availability = availability,
                explanation = explanation,
                connectorId = CONNECTOR_ID,
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isEnabled(): Boolean {
        return prefs().getBoolean(KEY_ENABLED, false)
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun round(value: Double): Double {
        return (value * COORDINATE_ROUNDING_FACTOR).roundToInt() / COORDINATE_ROUNDING_FACTOR
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_ID_CHARS)
    }

    private data class LocationExtractionResult(
        val sourceReference: SourceReference,
        val derivedEvent: DerivedMemoryEvent,
        val citation: MemoryCitation,
    )

    companion object {
        const val CONNECTOR_ID = "location"
        val METADATA = ConnectorMetadata(
            connectorId = CONNECTOR_ID,
            displayName = "Location",
            sourceKinds = setOf(SourceKind.LOCATION),
            connectorCapabilities = setOf(ConnectorCapability.LOCATION),
            memoryCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_LOCATION),
            defaultEnabled = false,
            sensitivity = SensitivityLevel.HIGH,
        )

        private const val PREFS_NAME = "grayin_location"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_INDEXED_AT = "last_indexed_at"
        private const val HASH_ID_CHARS = 32
        private const val COORDINATE_ROUNDING_FACTOR = 1000.0
    }
}
