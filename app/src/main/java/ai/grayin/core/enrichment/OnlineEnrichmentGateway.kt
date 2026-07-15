package ai.grayin.core.enrichment

import java.time.Instant

interface OnlineEnrichmentGateway {
    suspend fun getWeather(request: WeatherLookupRequest): EnrichmentResult<WeatherLookupResult>

    suspend fun reverseGeocode(request: ReverseGeocodeRequest): EnrichmentResult<PlaceLookupResult>
}

sealed interface EnrichmentResult<out T> {
    data class Available<T>(val value: T) : EnrichmentResult<T>

    data class Unavailable(val reason: EnrichmentUnavailableReason) : EnrichmentResult<Nothing>
}

enum class EnrichmentUnavailableReason {
    CONSENT_REQUIRED,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    RATE_LIMITED,
    PROVIDER_UNAVAILABLE,
    INVALID_RESPONSE,
    NOT_FOUND,
    UNSUPPORTED_TIME,
    PLATFORM_UNAVAILABLE,
}

enum class OnlineEnrichmentFeature {
    WEATHER_LOOKUP,
    REVERSE_GEOCODE_LOOKUP,
}

data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90." }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180." }
    }
}

data class WeatherLookupRequest(
    val coordinate: GeoCoordinate,
    val observedAt: Instant,
)

data class WeatherLookupResult(
    val coordinate: GeoCoordinate,
    val observedAt: Instant,
    val weatherCode: Int,
    val temperatureCelsius: Double,
    val precipitationMillimeters: Double,
    val providerLabel: String,
    val attributionUrl: String,
)

data class ReverseGeocodeRequest(
    val coordinate: GeoCoordinate,
)

data class PlaceLookupResult(
    val coordinate: GeoCoordinate,
    val localityLabel: String?,
    val regionLabel: String?,
    val countryCode: String?,
    val providerLabel: String,
)

object OnlineEnrichmentPolicy {
    val allowedFeatures: Set<OnlineEnrichmentFeature> = setOf(
        OnlineEnrichmentFeature.WEATHER_LOOKUP,
        OnlineEnrichmentFeature.REVERSE_GEOCODE_LOOKUP,
    )

    fun requireAllowed(feature: OnlineEnrichmentFeature) {
        require(feature in allowedFeatures) { "Online enrichment feature is not allowed: $feature" }
    }
}
