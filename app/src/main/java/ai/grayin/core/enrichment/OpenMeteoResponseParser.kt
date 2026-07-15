package ai.grayin.core.enrichment

import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ParsedOpenMeteoWeather(
    val observedAtEpochSeconds: Long,
    val weatherCode: Int,
    val temperatureCelsius: Double,
    val precipitationMillimeters: Double,
)

object OpenMeteoResponseParser {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = true
    }

    fun parse(body: String, targetEpochSeconds: Long): ParsedOpenMeteoWeather? {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            if (root["error"]?.jsonPrimitive?.booleanOrNull == true) return null
            val units = root.getValue("hourly_units").jsonObject
            if (units["temperature_2m"]?.jsonPrimitive?.contentOrNull != "°C") return null
            if (units["precipitation"]?.jsonPrimitive?.contentOrNull != "mm") return null

            val hourly = root.getValue("hourly").jsonObject
            val times = hourly.getValue("time").jsonArray
            val temperatures = hourly.getValue("temperature_2m").jsonArray
            val precipitation = hourly.getValue("precipitation").jsonArray
            val weatherCodes = hourly.getValue("weather_code").jsonArray
            if (!validArraySizes(times, temperatures, precipitation, weatherCodes)) return null

            val parsedTimes = times.map { element -> element.jsonPrimitive.longOrNull ?: return null }
            if (parsedTimes.zipWithNext().any { (first, second) -> second <= first }) return null
            val index = parsedTimes.indices.minByOrNull { candidate ->
                abs(parsedTimes[candidate] - targetEpochSeconds)
            } ?: return null
            if (abs(parsedTimes[index] - targetEpochSeconds) > MAX_TARGET_DISTANCE_SECONDS) return null

            val temperature = temperatures[index].jsonPrimitive.doubleOrNull ?: return null
            val precipitationValue = precipitation[index].jsonPrimitive.doubleOrNull ?: return null
            val weatherCode = weatherCodes[index].jsonPrimitive.longOrNull?.toInt() ?: return null
            if (!temperature.isFinite() || temperature !in MIN_TEMPERATURE_CELSIUS..MAX_TEMPERATURE_CELSIUS) return null
            if (!precipitationValue.isFinite() || precipitationValue !in 0.0..MAX_PRECIPITATION_MILLIMETERS) return null
            if (weatherCode !in WMO_WEATHER_CODES) return null

            ParsedOpenMeteoWeather(
                observedAtEpochSeconds = parsedTimes[index],
                weatherCode = weatherCode,
                temperatureCelsius = temperature,
                precipitationMillimeters = precipitationValue,
            )
        }.getOrNull()
    }

    private fun validArraySizes(vararg arrays: JsonArray): Boolean {
        val size = arrays.firstOrNull()?.size ?: return false
        return size in 1..MAX_HOURLY_VALUES && arrays.all { array -> array.size == size }
    }

    private const val MAX_HOURLY_VALUES = 25
    private const val MAX_TARGET_DISTANCE_SECONDS = 60L * 60L
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
