package ai.grayin.core.enrichment

import android.content.Context

class AndroidOnlineEnrichmentGateway(
    context: Context,
    private val weatherProvider: WeatherEnrichmentProvider = OpenMeteoWeatherProvider(),
    private val reverseGeocodeProvider: ReverseGeocodeProvider = AndroidReverseGeocodeProvider(
        AndroidPlatformReverseGeocoder(context.applicationContext),
    ),
    private val preferences: OnlineEnrichmentPreferences = OnlineEnrichmentPreferences(context.applicationContext),
) : OnlineEnrichmentGateway {
    override suspend fun getWeather(request: WeatherLookupRequest): EnrichmentResult<WeatherLookupResult> {
        OnlineEnrichmentPolicy.requireAllowed(OnlineEnrichmentFeature.WEATHER_LOOKUP)
        if (!preferences.isEnabled()) {
            return EnrichmentResult.Unavailable(EnrichmentUnavailableReason.CONSENT_REQUIRED)
        }
        return weatherProvider.lookup(request)
    }

    override suspend fun reverseGeocode(request: ReverseGeocodeRequest): EnrichmentResult<PlaceLookupResult> {
        OnlineEnrichmentPolicy.requireAllowed(OnlineEnrichmentFeature.REVERSE_GEOCODE_LOOKUP)
        if (!preferences.isEnabled()) {
            return EnrichmentResult.Unavailable(EnrichmentUnavailableReason.CONSENT_REQUIRED)
        }
        return reverseGeocodeProvider.lookup(request)
    }
}
