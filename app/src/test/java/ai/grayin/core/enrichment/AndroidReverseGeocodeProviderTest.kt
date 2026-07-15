package ai.grayin.core.enrichment

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidReverseGeocodeProviderTest {
    @Test
    fun returnsOnlyCoarseFieldsAndRoundsCoordinate() = runBlocking {
        val platform = FakePlatformGeocoder(
            address = CoarsePlatformAddress(
                locality = " Seoul ",
                subLocality = "Jongno",
                adminArea = "Seoul",
                subAdminArea = null,
                countryCode = "kr",
            ),
        )
        val provider = AndroidReverseGeocodeProvider(platform)

        val result = provider.lookup(ReverseGeocodeRequest(GeoCoordinate(37.56654, 126.97804)))

        val place = (result as EnrichmentResult.Available).value
        assertEquals(GeoCoordinate(37.567, 126.978), platform.coordinate)
        assertEquals("Seoul", place.localityLabel)
        assertEquals("Seoul", place.regionLabel)
        assertEquals("KR", place.countryCode)
    }

    @Test
    fun reportsPlatformMissingNotFoundAndTimeoutSeparately() = runBlocking {
        val missingPlatform = AndroidReverseGeocodeProvider(
            FakePlatformGeocoder(present = false),
        ).lookup(ReverseGeocodeRequest(GeoCoordinate(0.0, 0.0)))
        val notFound = AndroidReverseGeocodeProvider(
            FakePlatformGeocoder(address = null),
        ).lookup(ReverseGeocodeRequest(GeoCoordinate(0.0, 0.0)))
        val timeout = AndroidReverseGeocodeProvider(
            FakePlatformGeocoder(address = null, delayMillis = 100),
            timeoutMillis = 10,
        ).lookup(ReverseGeocodeRequest(GeoCoordinate(0.0, 0.0)))

        assertEquals(
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.PLATFORM_UNAVAILABLE),
            missingPlatform,
        )
        assertEquals(EnrichmentResult.Unavailable(EnrichmentUnavailableReason.NOT_FOUND), notFound)
        assertEquals(EnrichmentResult.Unavailable(EnrichmentUnavailableReason.TIMEOUT), timeout)
    }

    private class FakePlatformGeocoder(
        private val present: Boolean = true,
        private val address: CoarsePlatformAddress? = null,
        private val delayMillis: Long = 0,
    ) : PlatformReverseGeocoder {
        var coordinate: GeoCoordinate? = null

        override fun isPresent(): Boolean = present

        override suspend fun lookup(coordinate: GeoCoordinate): CoarsePlatformAddress? {
            this.coordinate = coordinate
            if (delayMillis > 0) delay(delayMillis)
            return address
        }
    }
}
