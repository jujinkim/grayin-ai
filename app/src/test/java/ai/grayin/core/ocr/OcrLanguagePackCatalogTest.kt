package ai.grayin.core.ocr

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLanguagePackCatalogTest {
    @Test
    fun catalogContainsExactlyPinnedEnglishKoreanAndJapanesePacks() {
        val expected = mapOf(
            "eng" to (4_113_088L to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
            "kor" to (1_677_415L to "6b85e11d9bbf07863b97b3523b1b112844c43e713df8b66418a081fd1060b3b2"),
            "jpn" to (2_471_260L to "1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d"),
        )

        val entries = OcrLanguagePackCatalog.all()

        assertEquals(expected.keys, entries.mapTo(linkedSetOf()) { it.id })
        entries.forEach { entry ->
            assertEquals("${entry.id}.traineddata", entry.fileName)
            assertEquals(expected.getValue(entry.id).first, entry.expectedSizeBytes)
            assertEquals(expected.getValue(entry.id).second, entry.sha256)
            assertEquals("Apache-2.0", entry.licenseLabel)
            assertEquals(
                "https://github.com/tesseract-ocr/tessdata_fast/blob/" +
                    "${OcrLanguagePackCatalog.TESSDATA_FAST_COMMIT}/LICENSE",
                entry.licenseUrl,
            )
        }
    }

    @Test
    fun everyArtifactUsesTheExactImmutableTessdataFastOriginAndPath() {
        OcrLanguagePackCatalog.all().forEach { entry ->
            val uri = URI(entry.artifact.url)
            assertEquals("https", uri.scheme)
            assertEquals("raw.githubusercontent.com", uri.host)
            assertEquals(-1, uri.port)
            assertNull(uri.userInfo)
            assertNull(uri.query)
            assertNull(uri.fragment)
            assertEquals(
                "/tesseract-ocr/tessdata_fast/${OcrLanguagePackCatalog.TESSDATA_FAST_COMMIT}/${entry.fileName}",
                uri.path,
            )
            assertEquals(entry.expectedSizeBytes, entry.artifact.expectedSizeBytes)
            assertEquals(entry.sha256, entry.artifact.sha256)
        }
    }

    @Test
    fun catalogLookupIsClosedAndReturnedListsCannotMutateTheCatalog() {
        val copy = OcrLanguagePackCatalog.all().toMutableList()
        copy.clear()

        assertEquals(3, OcrLanguagePackCatalog.all().size)
        assertNull(OcrLanguagePackCatalog.entry("unknown"))
        assertEquals(
            OcrLanguagePack.KOREAN,
            OcrLanguagePackCatalog.entry(OcrLanguagePack.KOREAN).language,
        )
    }
}
