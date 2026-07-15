package ai.grayin.connectors.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import ai.grayin.connectors.ConnectorValuePolicy
import ai.grayin.core.connector.ConnectorDeleteRequest
import ai.grayin.core.connector.ConnectorDeleteResult
import ai.grayin.core.connector.ConnectorMetadata
import ai.grayin.core.connector.ConnectorIndexingMode
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
import ai.grayin.core.enrichment.WeatherLookupRequest
import ai.grayin.core.enrichment.WeatherLookupResult
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorCapability
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.ConnectorState
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

class LocationConnector(
    private val context: Context,
    private val enrichmentGateway: OnlineEnrichmentGateway = AndroidOnlineEnrichmentGateway(context.applicationContext),
    private val enrichmentPreferences: OnlineEnrichmentPreferences = OnlineEnrichmentPreferences(context.applicationContext),
) : InvokableMemoryConnector {
    override val metadata: ConnectorMetadata = METADATA

    override suspend fun currentState(): ConnectorState {
        val permissionGranted = hasLocationPermission()
        val consentEnabled = isEnabled()
        val enabled = permissionGranted && consentEnabled
        val lastIndexedAt = prefs().getString(KEY_LAST_INDEXED_AT, null)?.let(Instant::parse)
        return ConnectorState(
            connectorId = CONNECTOR_ID,
            displayName = metadata.displayName,
            enabled = enabled,
            consentEnabled = consentEnabled,
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
            check(prefs().edit().putBoolean(KEY_ENABLED, true).commit()) {
                "Could not persist location connector consent."
            }
        }
        return permissionState
    }

    override suspend fun scan(scope: ConnectorScanScope): ConnectorScanResult {
        val now = Instant.now()
        if (!hasLocationPermission()) {
            return skipped(now, SourceAvailability.MISSING_PERMISSION, ConnectorScanIssueCode.SOURCE_PERMISSION_NOT_GRANTED)
        }
        if (!isEnabled()) {
            return skipped(now, SourceAvailability.DISABLED, ConnectorScanIssueCode.SOURCE_NOT_INVOKED)
        }

        val location = lastKnownLocation()
            ?: return skipped(now, SourceAvailability.NOT_INDEXED, ConnectorScanIssueCode.NO_LAST_KNOWN_LOCATION)
        val sampleAt = location.sampleAt(now)
        val coordinate = location.roundedCoordinate()
        val enrichment = LocationOnlineEnrichmentPolicy.lookup(
            enabled = enrichmentPreferences.isEnabled(),
            gateway = enrichmentGateway,
            coordinate = coordinate,
            observedAt = sampleAt,
        )
        val result = location.toExtractionResult(now, sampleAt, coordinate, enrichment)
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = ProcessingState.COMPLETED,
            sourceReferences = listOf(result.sourceReference),
            derivedEvents = listOf(result.derivedEvent),
            citations = listOf(result.citation),
            placeClusters = listOf(result.placeCluster),
            scannedAt = now,
        )
    }

    override suspend fun onScanStored(scanResult: ConnectorScanResult) {
        if (scanResult.processingState == ProcessingState.COMPLETED) {
            prefs().edit().putString(KEY_LAST_INDEXED_AT, scanResult.scannedAt.toString()).apply()
        }
    }

    override suspend fun revoke(): ConnectorRevokeResult {
        check(prefs().edit().clear().commit()) { "Could not clear location connector consent." }
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

    private fun Location.toExtractionResult(
        observedAt: Instant,
        sampleAt: Instant,
        coordinate: GeoCoordinate,
        enrichment: LocationOnlineEnrichment,
    ): LocationExtractionResult {
        val closedProvider = LocationProviderPolicy.closed(provider)
        val sourceHash = sha256(
            LocationPlaceClusterPolicy.sourceIdentityMaterial(
                provider = closedProvider,
                sampleAt = sampleAt,
                coordinate = coordinate,
            ),
        )
        val sourceId = "source:$CONNECTOR_ID:$sourceHash"
        val eventId = "event:$CONNECTOR_ID:$sourceHash"
        val citationId = "citation:$CONNECTOR_ID:$sourceHash"
        val regionLabel = enrichment.place?.displayLabel()?.let(LocationPlaceClusterPolicy::closedRegionLabel)
        val region = regionLabel ?: "lat ${coordinate.latitude}, lon ${coordinate.longitude}"
        val placeKeywords = enrichment.place?.keywords().orEmpty()
        val weatherSignals = LocationWeatherSignalPolicy.close(enrichment.weather)
        val confidence = if (hasAccuracy() && accuracy.isFinite() && accuracy in 0f..250f) {
            ConfidenceLevel.MEDIUM
        } else {
            ConfidenceLevel.LOW
        }
        return LocationExtractionResult(
            sourceReference = SourceReference(
                id = sourceId,
                connectorId = CONNECTOR_ID,
                sourceKind = SourceKind.LOCATION,
                localPointer = "location-provider:$closedProvider",
                externalIdHash = sourceHash,
                observedAt = observedAt,
                modifiedAt = sampleAt,
                sensitivity = SensitivityLevel.HIGH,
            ),
            derivedEvent = DerivedMemoryEvent(
                id = eventId,
                kind = DerivedMemoryEventKind.PLACE_VISIT,
                sourceReferenceIds = listOf(sourceId),
                summary = "Location sample indexed near $region at $sampleAt.${weatherSignals.summarySuffix}",
                startedAt = sampleAt,
                keywords = listOf("location", "place", closedProvider)
                    .plus(placeKeywords)
                    .plus(weatherSignals.keywords)
                    .filter { it.isNotBlank() },
                labels = listOf("location", "place-visit", closedProvider)
                    .plus(placeKeywords)
                    .plus(weatherSignals.labels)
                    .filter { it.isNotBlank() },
                confidence = confidence,
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
                confidence = confidence,
            ),
            placeCluster = LocationPlaceClusterPolicy.create(
                coordinate = coordinate,
                sourceReferenceId = sourceId,
                sampleAt = sampleAt,
                regionLabel = regionLabel,
                radiusMeters = accuracy
                    .takeIf { value -> hasAccuracy() && value.isFinite() && value >= 0f }
                    ?.toDouble(),
                confidence = confidence,
            ),
        )
    }

    private fun Location.sampleAt(observedAt: Instant): Instant {
        return if (time > 0L) Instant.ofEpochMilli(time) else observedAt
    }

    private fun Location.roundedCoordinate(): GeoCoordinate {
        return LocationPlaceClusterPolicy.roundedCoordinate(latitude, longitude)
    }

    private fun PlaceLookupResult.displayLabel(): String? {
        return listOf(localityLabel, regionLabel, countryCode)
            .mapNotNull { it?.takeIf(String::isNotBlank) }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }

    private fun PlaceLookupResult.keywords(): List<String> {
        return listOf(localityLabel, regionLabel, countryCode).mapNotNull(LocationPlaceClusterPolicy::closedKeyword)
    }

    private fun skipped(
        scannedAt: Instant,
        availability: SourceAvailability,
        issueCode: ConnectorScanIssueCode,
    ): ConnectorScanResult {
        return ConnectorScanResult(
            connectorId = CONNECTOR_ID,
            processingState = ProcessingState.SKIPPED,
            missingSources = missingSources(availability, issueCode),
            scannedAt = scannedAt,
        )
    }

    private fun missingSources(
        availability: SourceAvailability,
        issueCode: ConnectorScanIssueCode,
    ): List<MissingSource> {
        return metadata.memoryCapabilities.map { capability ->
            MissingSource(
                capability = capability,
                availability = availability,
                explanation = issueCode.defaultEnglish,
                connectorId = CONNECTOR_ID,
                issueCode = issueCode,
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

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_ID_CHARS)
    }

    private data class LocationExtractionResult(
        val sourceReference: SourceReference,
        val derivedEvent: DerivedMemoryEvent,
        val citation: MemoryCitation,
        val placeCluster: PlaceCluster,
    )

    companion object {
        const val CONNECTOR_ID = "location"
        val METADATA = ConnectorMetadata(
            connectorId = CONNECTOR_ID,
            displayName = "Location",
            sourceKinds = setOf(SourceKind.LOCATION),
            connectorCapabilities = setOf(ConnectorCapability.LOCATION),
            memoryCapabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_LOCATION),
            indexingMode = ConnectorIndexingMode.FOREGROUND_ONLY,
            defaultEnabled = false,
            sensitivity = SensitivityLevel.HIGH,
        )

        private const val PREFS_NAME = "grayin_location"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_INDEXED_AT = "last_indexed_at"
        private const val HASH_ID_CHARS = 32
    }
}

internal object LocationProviderPolicy {
    fun closed(value: String?): String {
        val normalized = value?.trim()?.lowercase(Locale.ROOT)
        return normalized?.takeIf(ALLOWED_PROVIDERS::contains) ?: OTHER_PROVIDER
    }

    private const val OTHER_PROVIDER = "other"
    private val ALLOWED_PROVIDERS = setOf("fused", "gps", "network", "passive")
}

internal data class LocationOnlineEnrichment(
    val place: PlaceLookupResult? = null,
    val weather: WeatherLookupResult? = null,
)

internal object LocationOnlineEnrichmentPolicy {
    suspend fun lookup(
        enabled: Boolean,
        gateway: OnlineEnrichmentGateway,
        coordinate: GeoCoordinate,
        observedAt: Instant,
    ): LocationOnlineEnrichment {
        if (!enabled) return LocationOnlineEnrichment()
        val place = availableOrNull(OnlineEnrichmentFeature.REVERSE_GEOCODE_LOOKUP) {
            gateway.reverseGeocode(ReverseGeocodeRequest(coordinate))
        }
        val weather = availableOrNull(OnlineEnrichmentFeature.WEATHER_LOOKUP) {
            gateway.getWeather(WeatherLookupRequest(coordinate, observedAt))
        }
        return LocationOnlineEnrichment(place = place, weather = weather)
    }

    private suspend fun <T> availableOrNull(
        feature: OnlineEnrichmentFeature,
        request: suspend () -> EnrichmentResult<T>,
    ): T? {
        return try {
            OnlineEnrichmentPolicy.requireAllowed(feature)
            when (val result = request()) {
                is EnrichmentResult.Available -> result.value
                is EnrichmentResult.Unavailable -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}

internal data class ClosedLocationWeatherSignals(
    val summarySuffix: String = "",
    val keywords: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
)

internal object LocationWeatherSignalPolicy {
    fun close(weather: WeatherLookupResult?): ClosedLocationWeatherSignals {
        if (
            weather == null ||
            weather.weatherCode !in WMO_WEATHER_CODES ||
            !weather.temperatureCelsius.isFinite() ||
            weather.temperatureCelsius !in MIN_TEMPERATURE_CELSIUS..MAX_TEMPERATURE_CELSIUS ||
            !weather.precipitationMillimeters.isFinite() ||
            weather.precipitationMillimeters !in 0.0..MAX_PRECIPITATION_MILLIMETERS
        ) {
            return ClosedLocationWeatherSignals()
        }
        val codeLabel = "weather-code-${weather.weatherCode}"
        val temperature = String.format(Locale.ROOT, "%.1f", weather.temperatureCelsius)
        val precipitation = String.format(Locale.ROOT, "%.1f", weather.precipitationMillimeters)
        return ClosedLocationWeatherSignals(
            summarySuffix = " Weather signal: WMO ${weather.weatherCode}, " +
                "temperature $temperature C, precipitation $precipitation mm.",
            keywords = buildList {
                add("weather")
                add(codeLabel)
                if (weather.precipitationMillimeters > 0.0) add("precipitation")
            },
            labels = listOf("weather", codeLabel),
        )
    }

    private const val MIN_TEMPERATURE_CELSIUS = -100.0
    private const val MAX_TEMPERATURE_CELSIUS = 70.0
    private const val MAX_PRECIPITATION_MILLIMETERS = 1_000.0
    private val WMO_WEATHER_CODES = setOf(
        0, 1, 2, 3,
        45, 48,
        51, 53, 55, 56, 57,
        61, 63, 65, 66, 67,
        71, 73, 75, 77,
        80, 81, 82,
        85, 86,
        95, 96, 99,
    )
}

internal object LocationPlaceClusterPolicy {
    fun sourceIdentityMaterial(
        provider: String?,
        sampleAt: Instant,
        coordinate: GeoCoordinate,
    ): String {
        val rounded = roundedCoordinate(coordinate.latitude, coordinate.longitude)
        val closedProvider = LocationProviderPolicy.closed(provider)
        return "location-source-v1:$closedProvider:$sampleAt:${rounded.latitude}:${rounded.longitude}"
    }

    fun roundedCoordinate(latitude: Double, longitude: Double): GeoCoordinate {
        return GeoCoordinate(
            latitude = roundAndNormalize(latitude),
            longitude = roundAndNormalize(longitude),
        )
    }

    fun create(
        coordinate: GeoCoordinate,
        sourceReferenceId: String,
        sampleAt: Instant,
        regionLabel: String?,
        radiusMeters: Double? = null,
        confidence: ConfidenceLevel,
    ): PlaceCluster {
        require(sourceReferenceId.startsWith("source:${LocationConnector.CONNECTOR_ID}:")) {
            "A location place cluster must reference a location source."
        }
        val rounded = roundedCoordinate(coordinate.latitude, coordinate.longitude)
        val identity = "location-place-cluster-v1:${rounded.latitude}:${rounded.longitude}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(HASH_ID_CHARS)
        return PlaceCluster(
            id = "place-cluster:${LocationConnector.CONNECTOR_ID}:$digest",
            regionLabel = closedRegionLabel(regionLabel),
            centroidLatitude = rounded.latitude,
            centroidLongitude = rounded.longitude,
            radiusMeters = radiusMeters?.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(MAX_RADIUS_METERS),
            firstSeenAt = sampleAt,
            lastSeenAt = sampleAt,
            visitCount = 1,
            sourceReferenceIds = listOf(sourceReferenceId),
            confidence = confidence,
        )
    }

    fun closedRegionLabel(value: String?): String? {
        return closedText(value, MAX_REGION_LABEL_BYTES)
    }

    fun closedKeyword(value: String?): String? {
        return closedText(value, MAX_KEYWORD_BYTES)
    }

    private fun closedText(value: String?, maxUtf8Bytes: Int): String? {
        if (value == null || containsUnsafeUnicode(value)) return null
        return ConnectorValuePolicy.closedText(value, maxUtf8Bytes)
    }

    private fun containsUnsafeUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            if (Character.charCount(codePoint) == 1 && value[index].isSurrogate()) return true
            if (
                Character.getType(codePoint) in setOf(
                    Character.FORMAT.toInt(),
                    Character.PRIVATE_USE.toInt(),
                    Character.SURROGATE.toInt(),
                    Character.UNASSIGNED.toInt(),
                )
            ) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun roundAndNormalize(value: Double): Double {
        val rounded = (value * COORDINATE_ROUNDING_FACTOR).roundToInt() / COORDINATE_ROUNDING_FACTOR
        return if (rounded == 0.0) 0.0 else rounded
    }

    private const val COORDINATE_ROUNDING_FACTOR = 1000.0
    private const val HASH_ID_CHARS = 32
    private const val MAX_REGION_LABEL_BYTES = 128
    private const val MAX_KEYWORD_BYTES = 64
    private const val MAX_RADIUS_METERS = 100_000.0
}
