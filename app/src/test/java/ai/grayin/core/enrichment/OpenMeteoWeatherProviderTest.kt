package ai.grayin.core.enrichment

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoWeatherProviderTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun projectsRoundedCoordinatesAndReturnsValidatedWeather() = runBlocking {
        val observedAt = Instant.parse("2026-07-15T12:20:00Z")
        val transport = RecordingTransport(successResponse(observedAt.epochSecond))
        val provider = OpenMeteoWeatherProvider(transport, clock)

        val result = provider.lookup(
            WeatherLookupRequest(
                coordinate = GeoCoordinate(37.56653, 126.97804),
                observedAt = observedAt,
            ),
        )

        assertEquals(
            OpenMeteoRequest(OpenMeteoEndpoint.FORECAST, 3_757, 12_698, java.time.LocalDate.parse("2026-07-15")),
            transport.request,
        )
        val weather = (result as EnrichmentResult.Available).value
        assertEquals(37.57, weather.coordinate.latitude, 0.0)
        assertEquals(21.5, weather.temperatureCelsius, 0.0)
        assertEquals(0.4, weather.precipitationMillimeters, 0.0)
        assertEquals(2, weather.weatherCode)
        assertEquals(OpenMeteoWeatherProvider.PROVIDER_LABEL, weather.providerLabel)
    }

    @Test
    fun usesArchiveForOlderSupportedDate() {
        val provider = OpenMeteoWeatherProvider(RecordingTransport(successResponse(0)), clock)

        val projected = provider.project(
            WeatherLookupRequest(
                coordinate = GeoCoordinate(0.0, 0.0),
                observedAt = Instant.parse("2020-01-02T00:00:00Z"),
            ),
        )

        assertEquals(OpenMeteoEndpoint.ARCHIVE, projected?.endpoint)
    }

    @Test
    fun rejectsUnsupportedTimeWithoutNetworkCall() = runBlocking {
        val transport = RecordingTransport(successResponse(0))
        val provider = OpenMeteoWeatherProvider(transport, clock)

        val result = provider.lookup(
            WeatherLookupRequest(
                coordinate = GeoCoordinate(0.0, 0.0),
                observedAt = Instant.parse("1939-12-31T00:00:00Z"),
            ),
        )

        assertEquals(
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.UNSUPPORTED_TIME),
            result,
        )
        assertEquals(null, transport.request)
    }

    @Test
    fun mapsProviderAndSchemaFailuresToExplicitReasons() = runBlocking {
        val request = WeatherLookupRequest(
            coordinate = GeoCoordinate(37.57, 126.98),
            observedAt = Instant.parse("2026-07-15T12:00:00Z"),
        )
        val rateLimited = OpenMeteoWeatherProvider(
            RecordingTransport(OpenMeteoHttpResponse(429, "application/json", "{}")),
            clock,
        ).lookup(request)
        val malformed = OpenMeteoWeatherProvider(
            RecordingTransport(OpenMeteoHttpResponse(200, "application/json", "{bad")),
            clock,
        ).lookup(request)
        val oversized = OpenMeteoWeatherProvider(
            RecordingTransport(
                OpenMeteoHttpResponse(
                    200,
                    "application/json",
                    "x".repeat(OpenMeteoHttpsTransport.MAX_RESPONSE_BYTES + 1),
                ),
            ),
            clock,
        ).lookup(request)
        val providerUnavailable = OpenMeteoWeatherProvider(
            RecordingTransport(OpenMeteoHttpResponse(503, "application/json", "{}")),
            clock,
        ).lookup(request)
        val wrongContentType = OpenMeteoWeatherProvider(
            RecordingTransport(OpenMeteoHttpResponse(200, "text/plain", "{}")),
            clock,
        ).lookup(request)

        assertEquals(EnrichmentResult.Unavailable(EnrichmentUnavailableReason.RATE_LIMITED), rateLimited)
        assertEquals(EnrichmentResult.Unavailable(EnrichmentUnavailableReason.INVALID_RESPONSE), malformed)
        assertEquals(EnrichmentResult.Unavailable(EnrichmentUnavailableReason.INVALID_RESPONSE), oversized)
        assertEquals(
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.PROVIDER_UNAVAILABLE),
            providerUnavailable,
        )
        assertEquals(
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.INVALID_RESPONSE),
            wrongContentType,
        )
    }

    @Test
    fun mapsTimeoutAndIoFailureWithoutLeakingMessages() = runBlocking {
        val request = WeatherLookupRequest(
            coordinate = GeoCoordinate(37.57, 126.98),
            observedAt = Instant.parse("2026-07-15T12:00:00Z"),
        )
        val timeoutProvider = OpenMeteoWeatherProvider(
            transport = object : OpenMeteoTransport {
                override suspend fun fetch(request: OpenMeteoRequest): OpenMeteoHttpResponse {
                    delay(100)
                    return successResponse(request.date.atStartOfDay().toEpochSecond(ZoneOffset.UTC))
                }
            },
            clock = clock,
            timeoutMillis = 10,
        )
        val ioProvider = OpenMeteoWeatherProvider(
            transport = object : OpenMeteoTransport {
                override suspend fun fetch(request: OpenMeteoRequest): OpenMeteoHttpResponse {
                    throw IOException("sensitive provider response")
                }
            },
            clock = clock,
        )

        assertEquals(
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.TIMEOUT),
            timeoutProvider.lookup(request),
        )
        assertEquals(
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.NETWORK_UNAVAILABLE),
            ioProvider.lookup(request),
        )
    }

    private class RecordingTransport(
        private val response: OpenMeteoHttpResponse,
    ) : OpenMeteoTransport {
        var request: OpenMeteoRequest? = null

        override suspend fun fetch(request: OpenMeteoRequest): OpenMeteoHttpResponse {
            this.request = request
            return response
        }
    }

    private companion object {
        fun successResponse(epochSeconds: Long): OpenMeteoHttpResponse {
            val body = """
                {
                  "hourly_units": {
                    "time": "unixtime",
                    "temperature_2m": "°C",
                    "precipitation": "mm",
                    "weather_code": "wmo code"
                  },
                  "hourly": {
                    "time": [$epochSeconds],
                    "temperature_2m": [21.5],
                    "precipitation": [0.4],
                    "weather_code": [2]
                  }
                }
            """.trimIndent()
            assertTrue(body.length < OpenMeteoHttpsTransport.MAX_RESPONSE_BYTES)
            return OpenMeteoHttpResponse(200, "application/json; charset=utf-8", body)
        }
    }
}
