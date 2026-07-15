package ai.grayin.core.security

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityBoundaryTest {
    @Test
    fun manifestAllowsInternetPermissionForOnlineEnrichment() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
    }

    @Test
    fun manifestAllowsCalendarReadPermissionForInvokedCalendarSource() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.READ_CALENDAR"))
    }

    @Test
    fun manifestAllowsPhotoReadPermissionsForInvokedPhotosSource() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.READ_MEDIA_IMAGES"))
        assertTrue(manifest.contains("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
        assertTrue(manifest.contains("android.permission.READ_EXTERNAL_STORAGE"))
    }

    @Test
    fun manifestAllowsLocationPermissionsForInvokedLocationSource() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertTrue(manifest.contains("android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test
    fun manifestAllowsUsageStatsPermissionForInvokedAppUsageSource() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.PACKAGE_USAGE_STATS"))
        assertTrue(manifest.contains("ProtectedPermissions"))
    }

    @Test
    fun manifestDeclaresNotificationListenerForInvokedNotificationSource() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(manifest.contains("android.service.notification.NotificationListenerService"))
    }

    @Test
    fun buildDoesNotAddForbiddenTelemetryOrCloudSdks() {
        val buildFile = File("build.gradle.kts").readText()
        val forbidden = listOf("Firebase", "Crashlytics", "analytics")

        forbidden.forEach { token ->
            assertFalse("Forbidden dependency token found: $token", buildFile.contains(token, ignoreCase = true))
        }
    }

    @Test
    fun networkConnectionsExistOnlyInsideApprovedBoundaries() {
        val networkFiles = File("src/main/java").walkTopDown()
            .filter(File::isFile)
            .filter { file -> file.extension == "kt" }
            .filter { file ->
                val source = file.readText()
                source.contains("openConnection()") || source.contains("openConnection() as")
            }
            .map { file -> file.relativeTo(File("src/main/java")).invariantSeparatorsPath }
            .toSet()

        assertEquals(
            setOf(
                "ai/grayin/core/artifact/FixedCatalogArtifactDownloader.kt",
                "ai/grayin/core/enrichment/OpenMeteoTransport.kt",
            ),
            networkFiles,
        )
    }

    @Test
    fun fixedArtifactSpecsAreConstructedOnlyByApprovedCatalogs() {
        val constructorOwners = File("src/main/java").walkTopDown()
            .filter(File::isFile)
            .filter { file -> file.extension == "kt" && file.readText().contains("FixedCatalogArtifactSpec(") }
            .map { file -> file.relativeTo(File("src/main/java")).invariantSeparatorsPath }
            .toSet()

        assertEquals(
            setOf(
                "ai/grayin/core/ai/ModelCatalog.kt",
                "ai/grayin/core/ai/SignedModelManifest.kt",
                "ai/grayin/core/artifact/FixedCatalogArtifactDownloader.kt",
                "ai/grayin/core/ocr/OcrLanguagePackCatalog.kt",
            ),
            constructorOwners,
        )
    }

    @Test
    fun indexingCodeCannotScheduleOcrLanguageDownloads() {
        val indexingSources = listOf(
            File("src/main/java/ai/grayin/core/indexing"),
            File("src/main/java/ai/grayin/connectors"),
        ).flatMap { root ->
            root.walkTopDown().filter(File::isFile).filter { file -> file.extension == "kt" }.toList()
        }

        indexingSources.forEach { file ->
            assertFalse(
                "Indexing must not schedule OCR language data: ${file.invariantSeparatorsPath}",
                file.readText().contains("OcrLanguagePackDownloadScheduler"),
            )
        }
    }

    @Test
    fun ocrLanguageDataIsNotBundledInTheApp() {
        val bundled = File("src/main").walkTopDown()
            .filter(File::isFile)
            .filter { file -> file.extension == "traineddata" }
            .toList()

        assertTrue(bundled.isEmpty())
    }
}
