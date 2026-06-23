package ai.grayin.core.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityBoundaryTest {
    @Test
    fun manifestAllowsInternetPermissionForOnlineEnrichment() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun buildDoesNotAddForbiddenTelemetryOrCloudSdks() {
        val buildFile = File("build.gradle.kts").readText()
        val forbidden = listOf("Firebase", "Crashlytics", "analytics")

        forbidden.forEach { token ->
            assertFalse("Forbidden dependency token found: $token", buildFile.contains(token, ignoreCase = true))
        }
    }
}
