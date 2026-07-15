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

    @Test
    fun rejectsOversizedAndControlBearingProviderLabels() = runBlocking {
        val provider = AndroidReverseGeocodeProvider(
            FakePlatformGeocoder(
                address = CoarsePlatformAddress(
                    locality = "a".repeat(129),
                    subLocality = "  Jongno   District  ",
                    adminArea = "Seoul\u0000Injected",
                    subAdminArea = null,
                    countryCode = "KOR",
                ),
            ),
        )

        val result = provider.lookup(ReverseGeocodeRequest(GeoCoordinate(37.5, 127.0)))

        val place = (result as EnrichmentResult.Available).value
        assertEquals("Jongno District", place.localityLabel)
        assertEquals(null, place.regionLabel)
        assertEquals(null, place.countryCode)
    }

    @Test
    fun rejectsFormatPrivateUnassignedAndMalformedProviderLabels() = runBlocking {
        val provider = AndroidReverseGeocodeProvider(
            FakePlatformGeocoder(
                address = CoarsePlatformAddress(
                    locality = "Seoul\u202Eredirected",
                    subLocality = "private\uE000value",
                    adminArea = "unassigned\u0378value",
                    subAdminArea = "malformed\uD800value",
                    countryCode = "KOR",
                ),
            ),
        )

        assertEquals(
            EnrichmentResult.Unavailable(EnrichmentUnavailableReason.NOT_FOUND),
            provider.lookup(ReverseGeocodeRequest(GeoCoordinate(37.5, 127.0))),
        )
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
