package ai.grayin.core.enrichment

import java.net.URI
import java.net.URL
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenMeteoTransportTest {
    private val transport = OpenMeteoHttpsTransport()

    @Test
    fun buildsOnlyFixedForecastEndpointAndWhitelistedQuery() {
        val url = transport.buildUrl(
            OpenMeteoRequest(OpenMeteoEndpoint.FORECAST, 3_757, 12_698, LocalDate.parse("2026-07-15")),
        )

        assertEquals("https", url.protocol)
        assertEquals("api.open-meteo.com", url.host)
        assertEquals("/v1/forecast", url.path)
        assertEquals(EXPECTED_QUERY_KEYS, url.query.split('&').map { it.substringBefore('=') }.toSet())
    }

    @Test
    fun buildsOnlyFixedArchiveEndpoint() {
        val url = transport.buildUrl(
            OpenMeteoRequest(OpenMeteoEndpoint.ARCHIVE, 0, 0, LocalDate.parse("2020-01-01")),
        )

        assertEquals("archive-api.open-meteo.com", url.host)
        assertEquals("/v1/archive", url.path)
    }

    @Test
    fun typedTransportRequestCannotCarryUrlOrEndpointString() {
        val forbidden = setOf(String::class.java, URI::class.java, URL::class.java)

        assertFalse(OpenMeteoRequest::class.java.declaredFields.any { field -> field.type in forbidden })
    }

    private companion object {
        val EXPECTED_QUERY_KEYS = setOf(
            "latitude",
            "longitude",
            "start_date",
            "end_date",
            "hourly",
            "timezone",
            "timeformat",
            "temperature_unit",
            "precipitation_unit",
        )
    }
}
