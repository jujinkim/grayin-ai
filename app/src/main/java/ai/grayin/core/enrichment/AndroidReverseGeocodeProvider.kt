package ai.grayin.core.enrichment

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class CoarsePlatformAddress(
    val locality: String?,
    val subLocality: String?,
    val adminArea: String?,
    val subAdminArea: String?,
    val countryCode: String?,
)

interface PlatformReverseGeocoder {
    fun isPresent(): Boolean

    suspend fun lookup(coordinate: GeoCoordinate): CoarsePlatformAddress?
}

interface ReverseGeocodeProvider {
    suspend fun lookup(request: ReverseGeocodeRequest): EnrichmentResult<PlaceLookupResult>
}

class AndroidReverseGeocodeProvider(
    private val platformGeocoder: PlatformReverseGeocoder,
    private val timeoutMillis: Long = REVERSE_GEOCODE_TIMEOUT_MILLIS,
) : ReverseGeocodeProvider {
    override suspend fun lookup(request: ReverseGeocodeRequest): EnrichmentResult<PlaceLookupResult> {
        OnlineEnrichmentPolicy.requireAllowed(OnlineEnrichmentFeature.REVERSE_GEOCODE_LOOKUP)
        if (!platformGeocoder.isPresent()) {
            return EnrichmentResult.Unavailable(EnrichmentUnavailableReason.PLATFORM_UNAVAILABLE)
        }
        return try {
            val lookup = withTimeoutOrNull(timeoutMillis) {
                PlatformLookup(platformGeocoder.lookup(request.coordinate.rounded(places = 3)))
            } ?: return EnrichmentResult.Unavailable(EnrichmentUnavailableReason.TIMEOUT)
            val address = lookup.address
                ?: return EnrichmentResult.Unavailable(EnrichmentUnavailableReason.NOT_FOUND)
            val locality = firstClosedLabel(address.locality, address.subLocality)
            val region = firstClosedLabel(address.adminArea, address.subAdminArea)
            val countryCode = address.countryCode
                ?.takeIf { code -> COUNTRY_CODE.matches(code) }
                ?.uppercase(Locale.ROOT)
            if (locality == null && region == null && countryCode == null) {
                return EnrichmentResult.Unavailable(EnrichmentUnavailableReason.NOT_FOUND)
            }
            EnrichmentResult.Available(
                PlaceLookupResult(
                    coordinate = request.coordinate.rounded(places = 3),
                    localityLabel = locality,
                    regionLabel = region,
                    countryCode = countryCode,
                    providerLabel = "Android Geocoder",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.NETWORK_UNAVAILABLE)
        } catch (_: RuntimeException) {
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.PROVIDER_UNAVAILABLE)
        }
    }

    private fun firstClosedLabel(vararg values: String?): String? {
        return values.firstNotNullOfOrNull(::closedLabel)
    }

    private fun closedLabel(value: String?): String? {
        if (value == null || !hasOnlySafeUnicode(value)) return null
        return value
            .trim()
            .replace(WHITESPACE, " ")
            .takeIf { label ->
                label.isNotBlank() && label.toByteArray(Charsets.UTF_8).size <= MAX_LABEL_UTF8_BYTES
            }
    }

    private fun hasOnlySafeUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            if (Character.charCount(codePoint) == 1 && value[index].isSurrogate()) return false
            if (Character.isISOControl(codePoint)) return false
            if (
                Character.getType(codePoint) in setOf(
                    Character.FORMAT.toInt(),
                    Character.PRIVATE_USE.toInt(),
                    Character.SURROGATE.toInt(),
                    Character.UNASSIGNED.toInt(),
                )
            ) return false
            index += Character.charCount(codePoint)
        }
        return true
    }

    private companion object {
        const val REVERSE_GEOCODE_TIMEOUT_MILLIS = 8_000L
        const val MAX_LABEL_UTF8_BYTES = 128
        val COUNTRY_CODE = Regex("[A-Za-z]{2}")
        val WHITESPACE = Regex("\\s+")
    }

    private data class PlatformLookup(val address: CoarsePlatformAddress?)
}

class AndroidPlatformReverseGeocoder(context: Context) : PlatformReverseGeocoder {
    private val appContext = context.applicationContext

    override fun isPresent(): Boolean = Geocoder.isPresent()

    override suspend fun lookup(coordinate: GeoCoordinate): CoarsePlatformAddress? {
        val geocoder = Geocoder(appContext, Locale.getDefault())
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lookupAsync(geocoder, coordinate)
        } else {
            lookupLegacy(geocoder, coordinate)
        }
        return address?.toCoarseAddress()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun lookupAsync(geocoder: Geocoder, coordinate: GeoCoordinate): Address? {
        return suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocation(
                coordinate.latitude,
                coordinate.longitude,
                MAX_RESULTS,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(IOException("Android Geocoder unavailable")))
                        }
                    }
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun lookupLegacy(geocoder: Geocoder, coordinate: GeoCoordinate): Address? {
        return runInterruptible(Dispatchers.IO) {
            geocoder.getFromLocation(coordinate.latitude, coordinate.longitude, MAX_RESULTS)
                .orEmpty()
                .firstOrNull()
        }
    }

    private fun Address.toCoarseAddress(): CoarsePlatformAddress {
        return CoarsePlatformAddress(
            locality = locality,
            subLocality = subLocality,
            adminArea = adminArea,
            subAdminArea = subAdminArea,
            countryCode = countryCode,
        )
    }

    private companion object {
        const val MAX_RESULTS = 1
    }
}

private fun GeoCoordinate.rounded(places: Int): GeoCoordinate {
    val factor = when (places) {
        3 -> 1_000.0
        else -> error("Unsupported coordinate precision")
    }
    return GeoCoordinate(
        latitude = kotlin.math.round(latitude * factor) / factor,
        longitude = kotlin.math.round(longitude * factor) / factor,
    )
}
