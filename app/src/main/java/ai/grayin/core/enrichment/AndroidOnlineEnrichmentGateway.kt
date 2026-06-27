package ai.grayin.core.enrichment

import android.content.Context
import android.location.Geocoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidOnlineEnrichmentGateway(
    context: Context,
) : OnlineEnrichmentGateway {
    private val appContext = context.applicationContext

    override suspend fun getWeather(request: WeatherLookupRequest): WeatherLookupResult {
        OnlineEnrichmentPolicy.requireAllowed(OnlineEnrichmentFeature.WEATHER_LOOKUP)
        return WeatherLookupResult(
            coordinate = request.coordinate,
            observedAt = request.observedAt,
            conditionLabel = null,
            temperatureCelsius = null,
            precipitationMillimeters = null,
            providerLabel = "Not configured",
        )
    }

    override suspend fun reverseGeocode(request: ReverseGeocodeRequest): PlaceLookupResult = withContext(Dispatchers.IO) {
        OnlineEnrichmentPolicy.requireAllowed(OnlineEnrichmentFeature.REVERSE_GEOCODE_LOOKUP)
        if (!Geocoder.isPresent()) {
            return@withContext emptyPlaceLookup(request, providerLabel = "Android Geocoder unavailable")
        }

        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(appContext, Locale.getDefault())
                .getFromLocation(request.coordinate.latitude, request.coordinate.longitude, MAX_RESULTS)
                .orEmpty()
                .firstOrNull()
        }.getOrNull()

        PlaceLookupResult(
            coordinate = request.coordinate,
            localityLabel = firstNonBlank(address?.locality, address?.subLocality, address?.featureName),
            regionLabel = firstNonBlank(address?.adminArea, address?.subAdminArea),
            countryCode = address?.countryCode?.takeIf { it.isNotBlank() },
            providerLabel = "Android Geocoder",
        )
    }

    private fun emptyPlaceLookup(request: ReverseGeocodeRequest, providerLabel: String): PlaceLookupResult {
        return PlaceLookupResult(
            coordinate = request.coordinate,
            localityLabel = null,
            regionLabel = null,
            countryCode = null,
            providerLabel = providerLabel,
        )
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { value -> !value.isNullOrBlank() }
    }

    private companion object {
        const val MAX_RESULTS = 1
    }
}
