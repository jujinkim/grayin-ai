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
    fun officialGemmaModelsUseCatalogControlledHuggingFaceUrls() {
        val e2b = requireNotNull(ModelCatalog.entry("gemma-4-E2B-it"))
        val e4b = requireNotNull(ModelCatalog.entry("gemma-4-E4B-it"))

        assertEquals(ModelProvider.HUGGING_FACE, e2b.provider)
        assertEquals(ModelProvider.HUGGING_FACE, e4b.provider)
        assertEquals("Apache-2.0", e2b.licenseLabel)
        assertEquals("Apache-2.0", e4b.licenseLabel)
        assertTrue(requireNotNull(e2b.downloadUrl).contains("litert-community/gemma-4-E2B-it-litert-lm"))
        assertTrue(requireNotNull(e4b.downloadUrl).contains("litert-community/gemma-4-E4B-it-litert-lm"))
        assertTrue(e2b.approxSizeBytes > 2_000_000_000L)
        assertTrue(e4b.approxSizeBytes > e2b.approxSizeBytes)
        assertFalse(e2b.recommended)
        assertFalse(e4b.recommended)
    }
}
