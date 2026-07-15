package ai.grayin.core.enrichment

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

interface WeatherEnrichmentProvider {
    suspend fun lookup(request: WeatherLookupRequest): EnrichmentResult<WeatherLookupResult>
}

class OpenMeteoWeatherProvider(
    private val transport: OpenMeteoTransport = OpenMeteoHttpsTransport(),
    private val clock: Clock = Clock.systemUTC(),
    private val timeoutMillis: Long = PROVIDER_TIMEOUT_MILLIS,
) : WeatherEnrichmentProvider {
    override suspend fun lookup(request: WeatherLookupRequest): EnrichmentResult<WeatherLookupResult> {
        OnlineEnrichmentPolicy.requireAllowed(OnlineEnrichmentFeature.WEATHER_LOOKUP)
        val providerRequest = project(request) ?: return EnrichmentResult.Unavailable(
            EnrichmentUnavailableReason.UNSUPPORTED_TIME,
        )
        return try {
            val response = withTimeout(timeoutMillis) { transport.fetch(providerRequest) }
            if (response.statusCode == HTTP_TOO_MANY_REQUESTS) {
                return EnrichmentResult.Unavailable(EnrichmentUnavailableReason.RATE_LIMITED)
            }
            if (response.statusCode !in 200..299) {
                return EnrichmentResult.Unavailable(EnrichmentUnavailableReason.PROVIDER_UNAVAILABLE)
            }
            if (response.contentType?.substringBefore(';')?.trim() != JSON_CONTENT_TYPE) {
                return EnrichmentResult.Unavailable(EnrichmentUnavailableReason.INVALID_RESPONSE)
            }
            if (response.body.toByteArray(Charsets.UTF_8).size > OpenMeteoHttpsTransport.MAX_RESPONSE_BYTES) {
                return EnrichmentResult.Unavailable(EnrichmentUnavailableReason.INVALID_RESPONSE)
            }
            val parsed = OpenMeteoResponseParser.parse(response.body, request.observedAt.epochSecond)
                ?: return EnrichmentResult.Unavailable(EnrichmentUnavailableReason.INVALID_RESPONSE)
            EnrichmentResult.Available(
                WeatherLookupResult(
                    coordinate = GeoCoordinate(
                        latitude = providerRequest.latitudeE2 / 100.0,
                        longitude = providerRequest.longitudeE2 / 100.0,
                    ),
                    observedAt = Instant.ofEpochSecond(parsed.observedAtEpochSeconds),
                    weatherCode = parsed.weatherCode,
                    temperatureCelsius = parsed.temperatureCelsius,
                    precipitationMillimeters = parsed.precipitationMillimeters,
                    providerLabel = PROVIDER_LABEL,
                    attributionUrl = ATTRIBUTION_URL,
                ),
            )
        } catch (error: TimeoutCancellationException) {
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.TIMEOUT)
        } catch (error: CancellationException) {
            throw error
        } catch (error: OpenMeteoTransportFailure) {
            EnrichmentResult.Unavailable(error.reason)
        } catch (_: SocketTimeoutException) {
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.TIMEOUT)
        } catch (_: IOException) {
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.NETWORK_UNAVAILABLE)
        } catch (_: RuntimeException) {
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.INVALID_RESPONSE)
        }
    }

    internal fun project(request: WeatherLookupRequest): OpenMeteoRequest? {
        val date = request.observedAt.atZone(ZoneOffset.UTC).toLocalDate()
        val today = LocalDate.now(clock)
        if (date < EARLIEST_ARCHIVE_DATE || date > today.plusDays(MAX_FORECAST_DAYS)) return null
        val endpoint = if (date >= today.minusDays(MAX_FORECAST_PAST_DAYS)) {
            OpenMeteoEndpoint.FORECAST
        } else {
            OpenMeteoEndpoint.ARCHIVE
        }
        return OpenMeteoRequest(
            endpoint = endpoint,
            latitudeE2 = (request.coordinate.latitude * 100.0).roundToInt(),
            longitudeE2 = (request.coordinate.longitude * 100.0).roundToInt(),
            date = date,
        )
    }

    companion object {
        private const val PROVIDER_TIMEOUT_MILLIS = 11_000L
        private const val MAX_FORECAST_DAYS = 16L
        private const val MAX_FORECAST_PAST_DAYS = 92L
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val JSON_CONTENT_TYPE = "application/json"
        private val EARLIEST_ARCHIVE_DATE = LocalDate.of(1940, 1, 1)
        const val PROVIDER_LABEL = "Open-Meteo"
        const val ATTRIBUTION_URL = "https://open-meteo.com/"
    }
}
