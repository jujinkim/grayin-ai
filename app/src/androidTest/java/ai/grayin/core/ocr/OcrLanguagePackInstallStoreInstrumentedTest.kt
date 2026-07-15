package ai.grayin.core.ocr

import ai.grayin.core.artifact.FixedCatalogArtifactSpec
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrLanguagePackInstallStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var store: OcrLanguagePackInstallStore
    private lateinit var entry: OcrLanguagePackEntry
    private val verifiedBytes = "small verified OCR data".toByteArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("grayin_ocr_language_packs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.noBackupFilesDir.resolve("ocr").deleteRecursively()
        entry = testEntry(verifiedBytes)
        store = OcrLanguagePackInstallStore(context)
    }

    @Test
    fun installIsFencedPreservesVerifiedDataAndDeletesExplicitly() {
        val destination = store.installedFile(entry)
        assertTrue(destination.path.startsWith(context.noBackupFilesDir.path))
        assertTrue(destination.invariantSeparatorsPath.endsWith("ocr/tesseract/tessdata/eng.traineddata"))

        val firstGeneration = store.beginInstall(entry)
        val firstPart = store.partFile(entry, firstGeneration, "first")
        firstPart.parentFile?.mkdirs()
        firstPart.writeBytes(verifiedBytes)

        assertEquals(
            OcrLanguagePackPublishResult.PUBLISHED,
            store.publishVerified(entry, firstGeneration, firstPart),
        )
        assertEquals(OcrLanguagePackStatus.READY, store.recordFor(entry).status)
        assertTrue(verifiedBytes.contentEquals(destination.readBytes()))

        val secondGeneration = store.beginInstall(entry)
        val stalePart = store.partFile(entry, firstGeneration, "stale")
        stalePart.parentFile?.mkdirs()
        stalePart.writeBytes(verifiedBytes)
        assertEquals(
            OcrLanguagePackPublishResult.STALE_GENERATION,
            store.publishVerified(entry, firstGeneration, stalePart),
        )
        assertFalse(stalePart.exists())
        assertEquals(secondGeneration, store.recordFor(entry).generation)
        assertTrue(verifiedBytes.contentEquals(destination.readBytes()))

        store.invalidateForCancel(entry)
        assertEquals(OcrLanguagePackStatus.READY, store.recordFor(entry).status)
        assertTrue(verifiedBytes.contentEquals(destination.readBytes()))

        val replacementGeneration = store.beginInstall(entry)
        val invalidPart = store.partFile(entry, replacementGeneration, "invalid")
        invalidPart.parentFile?.mkdirs()
        invalidPart.writeText("invalid")
        assertEquals(
            OcrLanguagePackPublishResult.FAILED,
            store.publishVerified(entry, replacementGeneration, invalidPart),
        )
        assertEquals(OcrLanguagePackStatus.READY, store.recordFor(entry).status)
        assertTrue(verifiedBytes.contentEquals(destination.readBytes()))

        store.invalidateForCancel(entry)
        assertTrue(store.deleteInstalled(entry))
        assertFalse(destination.exists())
        assertEquals(OcrLanguagePackStatus.NOT_INSTALLED, store.recordFor(entry).status)
    }

    private fun testEntry(bytes: ByteArray): OcrLanguagePackEntry {
        val digest = sha256(bytes)
        return OcrLanguagePackEntry(
            language = OcrLanguagePack.ENGLISH,
            displayName = "English test",
            fileName = "eng.traineddata",
            expectedSizeBytes = bytes.size.toLong(),
            sha256 = digest,
            licenseLabel = "Apache-2.0",
            licenseUrl = "https://example.test/license",
            artifact = FixedCatalogArtifactSpec(
                id = "test-eng",
                url = "https://example.test/eng.traineddata",
                fileName = "eng.traineddata",
                expectedSizeBytes = bytes.size.toLong(),
                sha256 = digest,
            ),
        )
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
