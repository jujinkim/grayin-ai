package ai.grayin.core.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class SecurityBoundaryTest {
    @Test
    fun manifestDoesNotRequestInternetPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun buildDoesNotAddForbiddenNetworkOrTelemetrySdks() {
        val buildFile = File("build.gradle.kts").readText()
        val forbidden = listOf("Firebase", "Crashlytics", "analytics", "OkHttp", "Retrofit")

        forbidden.forEach { token ->
            assertFalse("Forbidden dependency token found: $token", buildFile.contains(token, ignoreCase = true))
        }
    }
}
