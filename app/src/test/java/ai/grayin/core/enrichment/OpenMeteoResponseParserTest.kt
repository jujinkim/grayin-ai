package ai.grayin.core.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenMeteoResponseParserTest {
    @Test
    fun choosesNearestValidatedHour() {
        val parsed = OpenMeteoResponseParser.parse(
            response(
                times = "1000, 4600",
                temperatures = "10.0, 12.0",
                precipitation = "0.0, 1.5",
                weatherCodes = "0, 61",
            ),
            targetEpochSeconds = 4_000,
        )

        assertEquals(4_600L, parsed?.observedAtEpochSeconds)
        assertEquals(61, parsed?.weatherCode)
    }

    @Test
    fun rejectsMismatchedArraysAndInvalidValues() {
        assertNull(
            OpenMeteoResponseParser.parse(
                response("1000, 4600", "10.0", "0.0, 1.0", "0, 61"),
                1_000,
            ),
        )
        assertNull(
            OpenMeteoResponseParser.parse(
                response("1000", "10.0", "-1.0", "0"),
                1_000,
            ),
        )
        assertNull(OpenMeteoResponseParser.parse("not-json", 1_000))
    }

    @Test
    fun rejectsWeatherCodeAndTimestampIntegerOverflow() {
        assertNull(
            OpenMeteoResponseParser.parse(
                response("1000", "10.0", "0.0", "4294967296"),
                1_000,
            ),
        )
        assertNull(
            OpenMeteoResponseParser.parse(
                response(Long.MIN_VALUE.toString(), "10.0", "0.0", "0"),
                0,
            ),
        )
    }

    private fun response(
        times: String,
        temperatures: String,
        precipitation: String,
        weatherCodes: String,
    ): String {
        return """
            {
              "hourly_units": {"temperature_2m":"°C","precipitation":"mm"},
              "hourly": {
                "time": [$times],
                "temperature_2m": [$temperatures],
                "precipitation": [$precipitation],
                "weather_code": [$weatherCodes]
              }
            }
        """.trimIndent()
    }
}
