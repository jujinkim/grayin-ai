package ai.grayin.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun catalogExposesDedicatedAndGemmaRuntimeDownloadChoices() {
        assertEquals(3, ModelCatalog.entries.size)
        assertNotNull(ModelCatalog.entry("grayin-gemma-4-E2B-it-q4-v1"))
        assertNotNull(ModelCatalog.entry("gemma-4-E2B-it"))
        assertNotNull(ModelCatalog.entry("gemma-4-E4B-it"))
    }

    @Test
    fun grayinDedicatedModelStaysDisabledUntilServerUrlExists() {
        val entry = requireNotNull(ModelCatalog.entry("grayin-gemma-4-E2B-it-q4-v1"))

        assertTrue(entry.recommended)
        assertEquals(ModelProvider.GRAYIN_SERVER, entry.provider)
        assertNull(entry.downloadUrl)
        assertNull(entry.sha256)
    }

    @Test
    fun officialGemmaModelsExposePagesButDisableDownloadsUntilIntegrityMetadataExists() {
        val e2b = requireNotNull(ModelCatalog.entry("gemma-4-E2B-it"))
        val e4b = requireNotNull(ModelCatalog.entry("gemma-4-E4B-it"))

        assertEquals(ModelProvider.HUGGING_FACE, e2b.provider)
        assertEquals(ModelProvider.HUGGING_FACE, e4b.provider)
        assertEquals("Apache-2.0", e2b.licenseLabel)
        assertEquals("Apache-2.0", e4b.licenseLabel)
        assertTrue(requireNotNull(e2b.downloadPageUrl).contains("litert-community/gemma-4-E2B-it-litert-lm"))
        assertTrue(requireNotNull(e4b.downloadPageUrl).contains("litert-community/gemma-4-E4B-it-litert-lm"))
        assertNull(e2b.downloadUrl)
        assertNull(e4b.downloadUrl)
        assertNull(e2b.expectedDownloadSizeBytes)
        assertNull(e4b.expectedDownloadSizeBytes)
        assertNull(e2b.sha256)
        assertNull(e4b.sha256)
        assertFalse(e2b.downloadConfigured)
        assertFalse(e4b.downloadConfigured)
        assertNull(e2b.artifactSpecOrNull())
        assertNull(e4b.artifactSpecOrNull())
        assertTrue(e2b.approxSizeBytes > 2_000_000_000L)
        assertTrue(e4b.approxSizeBytes > e2b.approxSizeBytes)
        assertFalse(e2b.recommended)
        assertFalse(e4b.recommended)
    }

    @Test
    fun everyCurrentCatalogEntryFailsClosedBeforeNetworkDownload() {
        ModelCatalog.entries.forEach { entry ->
            assertFalse(entry.transportMetadataComplete)
            assertFalse(entry.downloadConfigured)
            assertNull(entry.artifactSpecOrNull())
        }
    }

    @Test
    fun completeFixedMetadataCreatesSpecAndEveryIncompleteVariantFailsClosed() {
        val digest = "a".repeat(64)
        val configured = testEntry(
            downloadUrl = "https://artifacts.example.test/models/model-v1.litertlm",
            exactSize = 123L,
            sha256 = digest,
        )

        assertTrue(configured.transportMetadataComplete)
        assertFalse(configured.downloadConfigured)
        assertFalse(ModelDownloadReleaseGate.GENERATION_FENCING_READY)
        val spec = requireNotNull(configured.artifactSpecOrNull())
        assertEquals(123L, spec.expectedSizeBytes)
        assertEquals(digest, spec.sha256)

        val invalidEntries = listOf(
            testEntry(downloadUrl = null, exactSize = 123L, sha256 = digest),
            testEntry(downloadUrl = "http://artifacts.example.test/model", exactSize = 123L, sha256 = digest),
            testEntry(downloadUrl = "https://artifacts.example.test/model?q=1", exactSize = 123L, sha256 = digest),
            testEntry(downloadUrl = "https://artifacts.example.test/model", exactSize = null, sha256 = digest),
            testEntry(downloadUrl = "https://artifacts.example.test/model", exactSize = 0L, sha256 = digest),
            testEntry(downloadUrl = "https://artifacts.example.test/model", exactSize = 123L, sha256 = null),
            testEntry(downloadUrl = "https://artifacts.example.test/model", exactSize = 123L, sha256 = "A".repeat(64)),
        )
        invalidEntries.forEach { entry ->
            assertFalse(entry.transportMetadataComplete)
            assertFalse(entry.downloadConfigured)
            assertNull(entry.artifactSpecOrNull())
        }
    }

    @Test
    fun modelFailureStorageKeysAreClosedAndUnique() {
        assertEquals(
            ModelDownloadFailureCode.entries.size,
            ModelDownloadFailureCode.entries.map { code -> code.storageKey }.toSet().size,
        )
        ModelDownloadFailureCode.entries.forEach { code ->
            assertEquals(code, ModelDownloadFailureCode.fromStorageKey(code.storageKey))
        }
        assertNull(ModelDownloadFailureCode.fromStorageKey("http_500"))
    }

    private fun testEntry(
        downloadUrl: String?,
        exactSize: Long?,
        sha256: String?,
    ): ModelCatalogEntry {
        return ModelCatalogEntry(
            id = "test-model-v1",
            displayName = "Test model",
            provider = ModelProvider.GRAYIN_SERVER,
            providerLabel = "Test provider",
            downloadPageUrl = null,
            downloadUrl = downloadUrl,
            fileName = "model-v1.litertlm",
            approxSizeBytes = 123L,
            expectedDownloadSizeBytes = exactSize,
            sha256 = sha256,
            recommendedRamGb = 4,
            licenseLabel = "Test terms",
            licenseUrl = "https://artifacts.example.test/terms",
            recommended = false,
        )
    }
}
