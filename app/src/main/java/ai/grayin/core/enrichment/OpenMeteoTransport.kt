package ai.grayin.core.enrichment

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class OpenMeteoEndpoint {
    FORECAST,
    ARCHIVE,
}

data class OpenMeteoRequest(
    val endpoint: OpenMeteoEndpoint,
    val latitudeE2: Int,
    val longitudeE2: Int,
    val date: LocalDate,
) {
    init {
        require(latitudeE2 in -9_000..9_000)
        require(longitudeE2 in -18_000..18_000)
    }
}

data class OpenMeteoHttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: String,
)

interface OpenMeteoTransport {
    suspend fun fetch(request: OpenMeteoRequest): OpenMeteoHttpResponse
}

class OpenMeteoTransportFailure(
    val reason: EnrichmentUnavailableReason,
) : IOException(reason.name)

class OpenMeteoHttpsTransport : OpenMeteoTransport {
    override suspend fun fetch(request: OpenMeteoRequest): OpenMeteoHttpResponse = withContext(Dispatchers.IO) {
        val connection = buildUrl(request).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", JSON_CONTENT_TYPE)
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connect()

            val statusCode = connection.responseCode
            if (statusCode == HTTP_TOO_MANY_REQUESTS) {
                throw OpenMeteoTransportFailure(EnrichmentUnavailableReason.RATE_LIMITED)
            }
            if (statusCode !in 200..299) {
                throw OpenMeteoTransportFailure(EnrichmentUnavailableReason.PROVIDER_UNAVAILABLE)
            }
            val contentType = connection.contentType
            if (contentType?.substringBefore(';')?.trim() != JSON_CONTENT_TYPE) {
                throw OpenMeteoTransportFailure(EnrichmentUnavailableReason.INVALID_RESPONSE)
            }
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw OpenMeteoTransportFailure(EnrichmentUnavailableReason.INVALID_RESPONSE)
            }
            val bytes = connection.inputStream.use { source ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > MAX_RESPONSE_BYTES) {
                        throw OpenMeteoTransportFailure(EnrichmentUnavailableReason.INVALID_RESPONSE)
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            OpenMeteoHttpResponse(statusCode, contentType, bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildUrl(request: OpenMeteoRequest): URL {
        val baseUrl = when (request.endpoint) {
            OpenMeteoEndpoint.FORECAST -> FORECAST_URL
            OpenMeteoEndpoint.ARCHIVE -> ARCHIVE_URL
        }
        val latitude = String.format(Locale.ROOT, "%.2f", request.latitudeE2 / 100.0)
        val longitude = String.format(Locale.ROOT, "%.2f", request.longitudeE2 / 100.0)
        val query = listOf(
            "latitude=$latitude",
            "longitude=$longitude",
            "start_date=${request.date}",
            "end_date=${request.date}",
            "hourly=$HOURLY_FIELDS",
            "timezone=GMT",
            "timeformat=unixtime",
            "temperature_unit=celsius",
            "precipitation_unit=mm",
        ).joinToString("&")
        return URL("$baseUrl?$query")
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val READ_TIMEOUT_MILLIS = 5_000
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val JSON_CONTENT_TYPE = "application/json"
        private const val USER_AGENT = "GrayinAI/0.1"
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
        private const val HOURLY_FIELDS = "temperature_2m,precipitation,weather_code"
    }
}
