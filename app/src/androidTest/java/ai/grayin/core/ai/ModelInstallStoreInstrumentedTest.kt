package ai.grayin.core.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelInstallStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var store: ModelInstallStore
    private lateinit var entry: ModelCatalogEntry

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        entry = requireNotNull(ModelCatalog.entry("gemma-4-E2B-it"))
        context.filesDir.resolve("models/${entry.id}").deleteRecursively()
        store = ModelInstallStore(context)
    }

    @Test
    fun unverifiedLegacyCatalogFileIsRemovedAndCannotBecomeReady() {
        val legacyFile = store.modelFile(entry)
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeText("legacy unverified model")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("${entry.id}.status", ModelDownloadStatus.READY.name)
            .putString("${entry.id}.path", legacyFile.absolutePath)
            .putString("${entry.id}.sha256", "legacy-unverified")
            .commit()

        val record = store.recordFor(entry)

        assertEquals(ModelDownloadStatus.NOT_DOWNLOADED, record.status)
        assertNull(record.installedPath)
        assertFalse(legacyFile.exists())
        assertNull(store.selectedReadyModelFile())
    }

    @Test
    fun legacyExceptionTextIsCollapsedToAStableFailureCode() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("${entry.id}.status", ModelDownloadStatus.FAILED.name)
            .putString("${entry.id}.failure", "http_500")
            .commit()

        val record = store.recordFor(entry)

        assertEquals(ModelDownloadStatus.FAILED, record.status)
        assertEquals(ModelDownloadFailureCode.DOWNLOAD_FAILED, record.failureCode)
    }

    private companion object {
        const val PREFS_NAME = "grayin_model_installs"
    }
}
