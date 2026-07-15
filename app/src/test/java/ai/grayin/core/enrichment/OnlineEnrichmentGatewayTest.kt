package ai.grayin.core.enrichment

import java.net.URI
import java.net.URL
import java.lang.reflect.Modifier
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineEnrichmentGatewayTest {
    @Test
    fun gatewayOnlyExposesTypedAllowedFeatures() {
        val methods = OnlineEnrichmentGateway::class.java.declaredMethods
            .map { method -> method.name }
            .filterNot { it == "\$annotations" }
            .toSet()

        assertEquals(setOf("getWeather", "reverseGeocode"), methods)
    }

    @Test
    fun gatewayDoesNotAcceptArbitraryUrlOrEndpointParameters() {
        val forbiddenTypes = setOf(String::class.java, URI::class.java, URL::class.java)
        val forbiddenFound = OnlineEnrichmentGateway::class.java.declaredMethods.any { method ->
            method.parameterTypes.any { parameterType -> parameterType in forbiddenTypes }
        }

        assertFalse(forbiddenFound)
    }

    @Test
    fun coordinateValidatesLatitudeAndLongitude() {
        GeoCoordinate(latitude = 37.5665, longitude = 126.9780)

        assertThrowsIllegalArgument { GeoCoordinate(latitude = 91.0, longitude = 126.9780) }
        assertThrowsIllegalArgument { GeoCoordinate(latitude = 37.5665, longitude = 181.0) }
    }

    @Test
    fun policyAllowsOnlyWeatherAndReverseGeocode() {
        assertTrue(OnlineEnrichmentFeature.WEATHER_LOOKUP in OnlineEnrichmentPolicy.allowedFeatures)
        assertTrue(OnlineEnrichmentFeature.REVERSE_GEOCODE_LOOKUP in OnlineEnrichmentPolicy.allowedFeatures)
        assertEquals(2, OnlineEnrichmentPolicy.allowedFeatures.size)
    }

    @Test
    fun requestProjectionTypesContainNoEndpointOrStoredMemoryFields() {
        assertEquals(
            setOf(GeoCoordinate::class.java, Instant::class.java),
            WeatherLookupRequest::class.java.declaredFields
                .filterNot { field -> Modifier.isStatic(field.modifiers) }
                .map { field -> field.type }
                .toSet(),
        )
        assertEquals(
            setOf(GeoCoordinate::class.java),
            ReverseGeocodeRequest::class.java.declaredFields
                .filterNot { field -> Modifier.isStatic(field.modifiers) }
                .map { field -> field.type }
                .toSet(),
        )
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
