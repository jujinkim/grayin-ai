package ai.grayin.core.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
        deleteTestPathNoFollow(context.filesDir.resolve("models/${entry.id}"))
        deleteTestPathNoFollow(context.filesDir.resolve("models/$TEST_MODEL_ID"))
        context.cacheDir.resolve("model-install-symlink-target").deleteRecursively()
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

    @Test
    fun generationIsDurableAndTransportReconfigurationFencesOldWork() {
        val firstEntry = configuredEntry(MODEL_BYTES, "test-v1.litertlm")
        val firstGeneration = store.beginInstall(firstEntry)

        val recreatedStore = ModelInstallStore(context)
        assertEquals(firstGeneration, recreatedStore.recordFor(firstEntry).generation)
        assertEquals(firstGeneration, recreatedStore.synchronizeConfiguration(firstEntry))

        val reconfiguredEntry = configuredEntry(MODEL_BYTES, "test-v2.litertlm")
        val reconfiguredGeneration = recreatedStore.synchronizeConfiguration(reconfiguredEntry)

        assertTrue(reconfiguredGeneration > firstGeneration)
        assertFalse(recreatedStore.isCurrentGeneration(firstEntry.id, firstGeneration))
        assertEquals(reconfiguredGeneration, recreatedStore.synchronizeConfiguration(reconfiguredEntry))
    }

    @Test
    fun replacementGenerationRejectsStaleProgressRetryAndFailure() {
        val configuredEntry = configuredEntry(MODEL_BYTES)
        val staleGeneration = store.beginInstall(configuredEntry)
        val currentGeneration = store.beginInstall(configuredEntry)

        assertTrue(currentGeneration > staleGeneration)
        assertFalse(
            store.recordDownloading(
                entry = configuredEntry,
                generation = staleGeneration,
                downloadedBytes = 2L,
                totalBytes = MODEL_BYTES.size.toLong(),
                progressPercent = 25,
            ),
        )
        assertFalse(
            store.recordQueuedForRetry(
                entry = configuredEntry,
                generation = staleGeneration,
                failureCode = ModelDownloadFailureCode.NETWORK_OR_IO_FAILURE,
            ),
        )
        assertFalse(
            store.recordFailed(
                entry = configuredEntry,
                generation = staleGeneration,
                failureCode = ModelDownloadFailureCode.DOWNLOAD_FAILED,
            ),
        )

        val record = store.recordFor(configuredEntry)
        assertEquals(currentGeneration, record.generation)
        assertEquals(ModelDownloadStatus.QUEUED, record.status)
        assertNull(record.failureCode)
    }

    @Test
    fun staleVerifiedPartCannotReplaceAnInstalledModelOrReadyState() {
        val configuredEntry = configuredEntry(MODEL_BYTES)
        val installedGeneration = store.beginInstall(configuredEntry)
        val installedPart = store.partFile(configuredEntry, installedGeneration, "worker-one")
        installedPart.parentFile?.mkdirs()
        installedPart.writeBytes(MODEL_BYTES)
        assertEquals(
            ModelPublishResult.PUBLISHED,
            store.publishVerified(configuredEntry, installedGeneration, installedPart),
        )
        val destination = store.modelFile(configuredEntry)
        val installedModifiedAt = 1_700_000_000_000L
        assertTrue(destination.setLastModified(installedModifiedAt))
        val preservedModifiedAt = destination.lastModified()

        val staleGeneration = store.beginInstall(configuredEntry)
        val canceledGeneration = store.invalidateForCancel(configuredEntry)
        val stalePart = store.partFile(configuredEntry, staleGeneration, "worker-two")
        stalePart.parentFile?.mkdirs()
        stalePart.writeBytes(MODEL_BYTES)
        stalePart.setLastModified(installedModifiedAt + 10_000L)

        assertEquals(
            ModelPublishResult.STALE_GENERATION,
            store.publishVerified(configuredEntry, staleGeneration, stalePart),
        )
        assertFalse(stalePart.exists())
        assertEquals(MODEL_BYTES.toList(), destination.readBytes().toList())
        assertEquals(preservedModifiedAt, destination.lastModified())
        val record = store.recordFor(configuredEntry)
        assertEquals(canceledGeneration, record.generation)
        assertEquals(ModelDownloadStatus.READY, record.status)
        assertEquals(destination.absolutePath, record.installedPath)
    }

    @Test
    fun failedReplacementKeepsTheLastVerifiedModelReady() {
        val configuredEntry = configuredEntry(MODEL_BYTES)
        val installedGeneration = store.beginInstall(configuredEntry)
        val installedPart = store.partFile(configuredEntry, installedGeneration, "worker-one")
        installedPart.parentFile?.mkdirs()
        installedPart.writeBytes(MODEL_BYTES)
        assertEquals(
            ModelPublishResult.PUBLISHED,
            store.publishVerified(configuredEntry, installedGeneration, installedPart),
        )

        val replacementGeneration = store.beginInstall(configuredEntry)
        assertTrue(
            store.recordFailed(
                entry = configuredEntry,
                generation = replacementGeneration,
                failureCode = ModelDownloadFailureCode.NETWORK_OR_IO_FAILURE,
            ),
        )

        val record = store.recordFor(configuredEntry)
        assertEquals(ModelDownloadStatus.READY, record.status)
        assertEquals(store.modelFile(configuredEntry).absolutePath, record.installedPath)
        assertEquals(ModelDownloadFailureCode.NETWORK_OR_IO_FAILURE, record.failureCode)
    }

    @Test
    fun releaseReconfigurationKeepsOldVerifiedModelUntilNewReleasePublishes() {
        val firstEntry = configuredEntry(MODEL_BYTES, "test-v1.litertlm")
        val firstGeneration = store.beginInstall(firstEntry)
        val firstPart = store.partFile(firstEntry, firstGeneration, "worker-one")
        firstPart.parentFile?.mkdirs()
        firstPart.writeBytes(MODEL_BYTES)
        assertEquals(ModelPublishResult.PUBLISHED, store.publishVerified(firstEntry, firstGeneration, firstPart))
        val firstRecord = store.recordFor(firstEntry)
        val firstFile = java.io.File(requireNotNull(firstRecord.installedPath))
        val firstDigest = requireNotNull(firstRecord.sha256)
        assertTrue(firstFile.isFile)

        val disabledEntry = firstEntry.copy(
            downloadUrl = null,
            expectedDownloadSizeBytes = null,
            sha256 = null,
        )
        val disabledGeneration = store.synchronizeConfiguration(disabledEntry)
        assertTrue(disabledGeneration > firstGeneration)
        val disabledRecord = store.recordFor(disabledEntry)
        assertEquals(ModelDownloadStatus.READY, disabledRecord.status)
        assertEquals(firstFile.absolutePath, disabledRecord.installedPath)
        assertEquals(firstDigest, disabledRecord.sha256)
        assertTrue(firstFile.isFile)

        val replacementEntry = configuredEntry(REPLACEMENT_MODEL_BYTES, "test-v2.litertlm")
        val reconfiguredGeneration = store.synchronizeConfiguration(replacementEntry)
        assertTrue(reconfiguredGeneration > disabledGeneration)
        assertFalse(store.isCurrentGeneration(firstEntry.id, firstGeneration))
        val reconfiguredRecord = store.recordFor(replacementEntry)
        assertEquals(ModelDownloadStatus.READY, reconfiguredRecord.status)
        assertEquals(firstFile.absolutePath, reconfiguredRecord.installedPath)
        assertEquals(firstDigest, reconfiguredRecord.sha256)
        assertTrue(firstFile.isFile)

        val failedGeneration = store.beginInstall(replacementEntry)
        assertTrue(
            store.recordFailed(
                replacementEntry,
                failedGeneration,
                ModelDownloadFailureCode.NETWORK_OR_IO_FAILURE,
            ),
        )
        assertEquals(firstFile.absolutePath, store.recordFor(replacementEntry).installedPath)
        assertTrue(firstFile.isFile)

        val replacementGeneration = store.beginInstall(replacementEntry)
        val replacementPart = store.partFile(replacementEntry, replacementGeneration, "worker-two")
        replacementPart.parentFile?.mkdirs()
        replacementPart.writeBytes(REPLACEMENT_MODEL_BYTES)
        assertEquals(
            ModelPublishResult.PUBLISHED,
            store.publishVerified(replacementEntry, replacementGeneration, replacementPart),
        )

        val replacementRecord = store.recordFor(replacementEntry)
        val replacementFile = java.io.File(requireNotNull(replacementRecord.installedPath))
        assertEquals(ModelDownloadStatus.READY, replacementRecord.status)
        assertTrue(replacementFile.isFile)
        assertTrue(replacementFile.absolutePath != firstFile.absolutePath)
        assertEquals(sha256(REPLACEMENT_MODEL_BYTES), replacementRecord.sha256)
        assertFalse(firstFile.exists())
    }

    @Test
    fun cancelAndDeleteEachAdvanceGenerationBeforeRemovingState() {
        val configuredEntry = configuredEntry(MODEL_BYTES)
        val installGeneration = store.beginInstall(configuredEntry)
        val installedPart = store.partFile(configuredEntry, installGeneration, "worker-one")
        installedPart.parentFile?.mkdirs()
        installedPart.writeBytes(MODEL_BYTES)
        assertEquals(
            ModelPublishResult.PUBLISHED,
            store.publishVerified(configuredEntry, installGeneration, installedPart),
        )

        val cancelGeneration = store.invalidateForCancel(configuredEntry)
        val deleteGeneration = store.invalidateForCancel(configuredEntry)

        assertTrue(cancelGeneration > installGeneration)
        assertTrue(deleteGeneration > cancelGeneration)
        assertTrue(store.deleteInstalled(configuredEntry, deleteGeneration))
        val record = store.recordFor(configuredEntry)
        assertEquals(deleteGeneration, record.generation)
        assertEquals(ModelDownloadStatus.NOT_DOWNLOADED, record.status)
        assertNull(record.installedPath)
    }

    @Test
    fun stagingFilesAreUniquePerGenerationAndWorker() {
        val configuredEntry = configuredEntry(MODEL_BYTES)
        val firstGeneration = store.beginInstall(configuredEntry)
        val secondGeneration = store.beginInstall(configuredEntry)

        val first = store.partFile(configuredEntry, firstGeneration, "worker-one")
        val second = store.partFile(configuredEntry, secondGeneration, "worker-one")
        val third = store.partFile(configuredEntry, secondGeneration, "worker-two")

        assertTrue(first != second)
        assertTrue(second != third)
        assertTrue(first.parentFile == second.parentFile)
    }

    @Test
    fun parentSymlinkIsRejectedAndCleanupDoesNotFollowItsExternalTarget() {
        val configuredEntry = configuredEntry(MODEL_BYTES)
        val digest = requireNotNull(configuredEntry.sha256)
        val installDirectory = context.filesDir.resolve("models/${configuredEntry.id}")
        val externalTarget = context.cacheDir.resolve("model-install-symlink-target").apply {
            deleteRecursively()
            mkdirs()
        }
        val externalModel = externalTarget.resolve("releases/$digest/model.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(MODEL_BYTES)
        }
        installDirectory.parentFile?.mkdirs()
        Files.createSymbolicLink(installDirectory.toPath(), externalTarget.toPath())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("${configuredEntry.id}.status", ModelDownloadStatus.READY.name)
            .putString(
                "${configuredEntry.id}.path",
                installDirectory.resolve("releases/$digest/model.litertlm").absolutePath,
            )
            .putLong("${configuredEntry.id}.installed_bytes", MODEL_BYTES.size.toLong())
            .putString("${configuredEntry.id}.sha256", digest)
            .commit()

        val record = store.recordFor(configuredEntry)

        assertEquals(ModelDownloadStatus.NOT_DOWNLOADED, record.status)
        assertNull(record.installedPath)
        assertFalse(Files.exists(installDirectory.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(externalModel.isFile)
        assertEquals(MODEL_BYTES.toList(), externalModel.readBytes().toList())
        externalTarget.deleteRecursively()
    }

    @Test
    fun verifiedCacheRejectsAtomicReplacementWithSameSizeAndMtime() {
        val configuredEntry = configuredEntry(MODEL_BYTES)
        val generation = store.beginInstall(configuredEntry)
        val part = store.partFile(configuredEntry, generation, "worker-one")
        part.writeBytes(MODEL_BYTES)
        assertEquals(ModelPublishResult.PUBLISHED, store.publishVerified(configuredEntry, generation, part))
        val installed = java.io.File(requireNotNull(store.recordFor(configuredEntry).installedPath))
        val preservedModifiedAt = 1_700_000_000_000L
        assertTrue(installed.setLastModified(preservedModifiedAt))
        assertEquals(ModelDownloadStatus.READY, store.recordFor(configuredEntry).status)

        val replacementBytes = MODEL_BYTES.copyOf().also { bytes -> bytes[0] = (bytes[0].toInt() xor 1).toByte() }
        val replacement = installed.resolveSibling("replacement.litertlm")
        replacement.writeBytes(replacementBytes)
        assertTrue(replacement.setLastModified(preservedModifiedAt))
        Files.move(
            replacement.toPath(),
            installed.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        assertEquals(MODEL_BYTES.size.toLong(), installed.length())
        assertEquals(preservedModifiedAt, installed.lastModified())

        val record = store.recordFor(configuredEntry)

        assertEquals(ModelDownloadStatus.NOT_DOWNLOADED, record.status)
        assertNull(record.installedPath)
        assertFalse(installed.exists())
    }

    private fun configuredEntry(
        bytes: ByteArray,
        fileName: String = "test-v1.litertlm",
    ): ModelCatalogEntry {
        return ModelCatalogEntry(
            id = TEST_MODEL_ID,
            displayName = "Test model",
            provider = ModelProvider.GRAYIN_SERVER,
            providerLabel = "Test provider",
            downloadPageUrl = null,
            downloadUrl = "https://artifacts.example.test/models/$fileName",
            fileName = fileName,
            approxSizeBytes = bytes.size.toLong(),
            expectedDownloadSizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            recommendedRamGb = 1,
            licenseLabel = "Test terms",
            licenseUrl = "https://artifacts.example.test/terms",
            recommended = false,
        )
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun deleteTestPathNoFollow(file: java.io.File) {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.deleteIfExists(file.toPath())
        } else {
            file.deleteRecursively()
        }
    }

    private companion object {
        const val PREFS_NAME = "grayin_model_installs"
        const val TEST_MODEL_ID = "test-model-v1"
        val MODEL_BYTES = "verified-model-v1".toByteArray()
        val REPLACEMENT_MODEL_BYTES = "verified-model-v2".toByteArray()
    }
}
