package ai.grayin.core.ai

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class ModelInstallRecord(
    val modelId: String,
    val generation: Long,
    val status: ModelDownloadStatus,
    val progressPercent: Int?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val installedBytes: Long,
    val installedPath: String?,
    val sha256: String?,
    val failureCode: ModelDownloadFailureCode?,
)

enum class ModelPublishResult {
    PUBLISHED,
    STALE_GENERATION,
    FAILED,
}

class ModelInstallStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun selectedModelId(): String {
        return prefs.getString(KEY_SELECTED_MODEL_ID, ModelCatalog.DEFAULT_MODEL_ID)
            ?.takeIf { ModelCatalog.entry(it) != null }
            ?: ModelCatalog.DEFAULT_MODEL_ID
    }

    fun selectedEntry(): ModelCatalogEntry {
        return ModelCatalog.entry(selectedModelId()) ?: requireNotNull(ModelCatalog.entry(ModelCatalog.DEFAULT_MODEL_ID))
    }

    fun selectModel(modelId: String) {
        require(ModelCatalog.entry(modelId) != null) { "Unknown model id." }
        prefs.edit().putString(KEY_SELECTED_MODEL_ID, modelId).apply()
    }

    fun recordFor(entry: ModelCatalogEntry): ModelInstallRecord = synchronized(INSTALL_LOCK) {
        synchronizeConfigurationLocked(entry)
        var status = statusFor(entry.id)
        val installedFile = modelFile(entry)
        val fileReady = verifyInstalledCatalogModel(entry, installedFile)
        if (!fileReady && hasUntrustedInstallState(entry, installedFile, status)) {
            installedFile.delete()
            VERIFIED_FILE_CACHE.remove(installedFile.absolutePath)
            cleanupStagingLocked(entry)
            clearInstallMetadata(entry.id)
            status = ModelDownloadStatus.NOT_DOWNLOADED
        }
        val installedPath = installedFile.absolutePath.takeIf { fileReady }
        val effectiveStatus = when {
            installedPath != null && status != ModelDownloadStatus.DOWNLOADING && status != ModelDownloadStatus.QUEUED -> {
                ModelDownloadStatus.READY
            }

            status == ModelDownloadStatus.READY && installedPath == null -> ModelDownloadStatus.NOT_DOWNLOADED
            else -> status
        }
        if (effectiveStatus != status) {
            check(
                prefs.edit()
                    .putString(statusKey(entry.id), effectiveStatus.name)
                    .putInt(progressKey(entry.id), if (effectiveStatus == ModelDownloadStatus.READY) 100 else -1)
                    .commit(),
            ) { "Could not reconcile model install state." }
        }
        ModelInstallRecord(
            modelId = entry.id,
            generation = generation(entry.id),
            status = effectiveStatus,
            progressPercent = prefs.getInt(progressKey(entry.id), -1).takeIf { it in 0..100 },
            downloadedBytes = prefs.getLong(downloadedBytesKey(entry.id), 0L),
            totalBytes = prefs.getLong(totalBytesKey(entry.id), -1L).takeIf { it > 0L },
            installedBytes = if (installedPath == null) 0L else installedFile.length(),
            installedPath = installedPath,
            sha256 = prefs.getString(shaKey(entry.id), null).takeIf { installedPath != null },
            failureCode = storedFailureCode(entry.id),
        )
    }

    fun selectedReadyModelFile(): File? {
        val entry = selectedEntry()
        val record = recordFor(entry)
        val path = record.installedPath ?: return null
        return File(path).takeIf { it.isFile && it.canRead() }
    }

    fun synchronizeConfiguration(entry: ModelCatalogEntry): Long = synchronized(INSTALL_LOCK) {
        synchronizeConfigurationLocked(entry)
        generation(entry.id)
    }

    fun beginInstall(entry: ModelCatalogEntry): Long = synchronized(INSTALL_LOCK) {
        require(entry.downloadConfigured) { "Model download is not enabled." }
        synchronizeConfigurationLocked(entry)
        val nextGeneration = nextGeneration(entry.id)
        check(
            prefs.edit()
                .putLong(generationKey(entry.id), nextGeneration)
                .putString(configurationKey(entry.id), transportFingerprint(entry))
                .putString(statusKey(entry.id), ModelDownloadStatus.QUEUED.name)
                .putInt(progressKey(entry.id), 0)
                .putLong(downloadedBytesKey(entry.id), 0L)
                .putLong(totalBytesKey(entry.id), requireNotNull(entry.expectedDownloadSizeBytes))
                .remove(failureKey(entry.id))
                .commit(),
        ) { "Could not persist model download generation." }
        cleanupStagingLocked(entry)
        nextGeneration
    }

    fun isCurrentGeneration(modelId: String, generation: Long): Boolean = synchronized(INSTALL_LOCK) {
        isCurrentGenerationLocked(modelId, generation)
    }

    fun recordDownloading(
        entry: ModelCatalogEntry,
        generation: Long,
        downloadedBytes: Long,
        totalBytes: Long,
        progressPercent: Int,
    ): Boolean = synchronized(INSTALL_LOCK) {
        if (!isCurrentGenerationLocked(entry.id, generation)) return@synchronized false
        if (downloadedBytes < 0L || totalBytes <= 0L || downloadedBytes > totalBytes) return@synchronized false
        val boundedProgress = progressPercent.coerceIn(0, 99)
        if (
            statusFor(entry.id) == ModelDownloadStatus.DOWNLOADING &&
            prefs.getInt(progressKey(entry.id), -1) == boundedProgress
        ) {
            return@synchronized true
        }
        prefs.edit()
            .putString(statusKey(entry.id), ModelDownloadStatus.DOWNLOADING.name)
            .putLong(downloadedBytesKey(entry.id), downloadedBytes)
            .putLong(totalBytesKey(entry.id), totalBytes)
            .putInt(progressKey(entry.id), boundedProgress)
            .remove(failureKey(entry.id))
            .commit()
    }

    fun recordQueuedForRetry(
        entry: ModelCatalogEntry,
        generation: Long,
        failureCode: ModelDownloadFailureCode,
    ): Boolean = synchronized(INSTALL_LOCK) {
        if (!isCurrentGenerationLocked(entry.id, generation)) return@synchronized false
        prefs.edit()
            .putString(statusKey(entry.id), ModelDownloadStatus.QUEUED.name)
            .putInt(progressKey(entry.id), 0)
            .putLong(downloadedBytesKey(entry.id), 0L)
            .putLong(totalBytesKey(entry.id), entry.expectedDownloadSizeBytes ?: -1L)
            .putString(failureKey(entry.id), failureCode.storageKey)
            .commit()
    }

    fun recordFailed(
        entry: ModelCatalogEntry,
        generation: Long,
        failureCode: ModelDownloadFailureCode,
    ): Boolean = synchronized(INSTALL_LOCK) {
        if (!isCurrentGenerationLocked(entry.id, generation)) return@synchronized false
        recordFailedLocked(entry, failureCode)
    }

    fun publishVerified(
        entry: ModelCatalogEntry,
        generation: Long,
        partFile: File,
    ): ModelPublishResult = synchronized(INSTALL_LOCK) {
        if (!isExpectedPartFile(entry, generation, partFile)) {
            return@synchronized ModelPublishResult.FAILED
        }
        if (!isCurrentGenerationLocked(entry.id, generation)) {
            partFile.delete()
            return@synchronized ModelPublishResult.STALE_GENERATION
        }
        val destination = modelFile(entry)
        val destinationDir = checkNotNull(destination.parentFile)
        if (
            (!destinationDir.isDirectory && !destinationDir.mkdirs()) ||
            !verifyArtifactFile(entry, partFile)
        ) {
            partFile.delete()
            recordFailedLocked(entry, ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED)
            return@synchronized ModelPublishResult.FAILED
        }
        try {
            VERIFIED_FILE_CACHE.remove(destination.absolutePath)
            Files.move(
                partFile.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            partFile.delete()
            recordFailedLocked(entry, ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED)
            return@synchronized ModelPublishResult.FAILED
        } catch (_: IOException) {
            partFile.delete()
            recordFailedLocked(entry, ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED)
            return@synchronized ModelPublishResult.FAILED
        }
        if (!verifyInstalledCatalogModel(entry, destination)) {
            destination.delete()
            recordFailedLocked(entry, ModelDownloadFailureCode.CHECKSUM_MISMATCH)
            return@synchronized ModelPublishResult.FAILED
        }
        val installedBytes = destination.length()
        val persisted = prefs.edit()
            .putString(statusKey(entry.id), ModelDownloadStatus.READY.name)
            .putInt(progressKey(entry.id), 100)
            .putLong(downloadedBytesKey(entry.id), installedBytes)
            .putLong(totalBytesKey(entry.id), installedBytes)
            .putString(pathKey(entry.id), destination.absolutePath)
            .putLong(installedBytesKey(entry.id), installedBytes)
            .putString(shaKey(entry.id), requireNotNull(entry.sha256))
            .remove(failureKey(entry.id))
            .commit()
        if (persisted) ModelPublishResult.PUBLISHED else ModelPublishResult.FAILED
    }

    fun invalidateForCancel(entry: ModelCatalogEntry): Long = synchronized(INSTALL_LOCK) {
        synchronizeConfigurationLocked(entry)
        val nextGeneration = nextGeneration(entry.id)
        val installedReady = verifyInstalledCatalogModel(entry, modelFile(entry))
        val status = if (installedReady) ModelDownloadStatus.READY else ModelDownloadStatus.NOT_DOWNLOADED
        check(
            prefs.edit()
                .putLong(generationKey(entry.id), nextGeneration)
                .putString(statusKey(entry.id), status.name)
                .putInt(progressKey(entry.id), if (installedReady) 100 else -1)
                .putLong(downloadedBytesKey(entry.id), if (installedReady) modelFile(entry).length() else 0L)
                .putLong(totalBytesKey(entry.id), if (installedReady) modelFile(entry).length() else -1L)
                .remove(failureKey(entry.id))
                .commit(),
        ) { "Could not invalidate model download work." }
        cleanupStagingLocked(entry)
        nextGeneration
    }

    fun deleteInstalled(entry: ModelCatalogEntry, generation: Long): Boolean = synchronized(INSTALL_LOCK) {
        if (!isCurrentGenerationLocked(entry.id, generation)) return@synchronized false
        cleanupStagingLocked(entry)
        val destination = modelFile(entry)
        if (destination.exists() && !destination.delete()) return@synchronized false
        VERIFIED_FILE_CACHE.remove(destination.absolutePath)
        prefs.edit()
            .putString(statusKey(entry.id), ModelDownloadStatus.NOT_DOWNLOADED.name)
            .putInt(progressKey(entry.id), -1)
            .putLong(downloadedBytesKey(entry.id), 0L)
            .putLong(totalBytesKey(entry.id), -1L)
            .remove(pathKey(entry.id))
            .remove(installedBytesKey(entry.id))
            .remove(shaKey(entry.id))
            .remove(failureKey(entry.id))
            .commit()
    }

    fun cleanupStaging(entry: ModelCatalogEntry) = synchronized(INSTALL_LOCK) {
        cleanupStagingLocked(entry)
    }

    fun modelFile(entry: ModelCatalogEntry): File {
        return File(installDir(entry), INSTALLED_MODEL_FILE_NAME)
    }

    fun partFile(entry: ModelCatalogEntry, generation: Long, workerId: String): File {
        require(generation > 0L) { "Model download generation must be positive." }
        require(workerId.matches(SAFE_WORKER_ID)) { "Model download worker ID is invalid." }
        return File(stagingDir(entry), "$generation.$workerId$PART_SUFFIX")
    }

    private fun synchronizeConfigurationLocked(entry: ModelCatalogEntry) {
        val configuredFingerprint = transportFingerprint(entry)
        val storedFingerprint = prefs.getString(configurationKey(entry.id), null)
        val storedStatus = statusFor(entry.id)
        val legacyActiveWork = storedFingerprint == null &&
            (storedStatus == ModelDownloadStatus.QUEUED || storedStatus == ModelDownloadStatus.DOWNLOADING)
        if (storedFingerprint == null && !legacyActiveWork) {
            check(prefs.edit().putString(configurationKey(entry.id), configuredFingerprint).commit()) {
                "Could not persist model transport identity."
            }
            return
        }
        if (storedFingerprint == configuredFingerprint && !legacyActiveWork) return

        val nextGeneration = nextGeneration(entry.id)
        val destination = modelFile(entry)
        val installedReady = verifyInstalledCatalogModel(entry, destination)
        if (!installedReady) {
            destination.delete()
            VERIFIED_FILE_CACHE.remove(destination.absolutePath)
        }
        val editor = prefs.edit()
            .putLong(generationKey(entry.id), nextGeneration)
            .putString(configurationKey(entry.id), configuredFingerprint)
            .putString(
                statusKey(entry.id),
                if (installedReady) ModelDownloadStatus.READY.name else ModelDownloadStatus.NOT_DOWNLOADED.name,
            )
            .putInt(progressKey(entry.id), if (installedReady) 100 else -1)
            .putLong(downloadedBytesKey(entry.id), if (installedReady) destination.length() else 0L)
            .putLong(totalBytesKey(entry.id), if (installedReady) destination.length() else -1L)
            .remove(failureKey(entry.id))
        if (installedReady) {
            editor
                .putString(pathKey(entry.id), destination.absolutePath)
                .putLong(installedBytesKey(entry.id), destination.length())
                .putString(shaKey(entry.id), requireNotNull(entry.sha256))
        } else {
            editor
                .remove(pathKey(entry.id))
                .remove(installedBytesKey(entry.id))
                .remove(shaKey(entry.id))
        }
        check(editor.commit()) { "Could not persist model transport reconfiguration." }
        cleanupStagingLocked(entry)
    }

    private fun recordFailedLocked(
        entry: ModelCatalogEntry,
        failureCode: ModelDownloadFailureCode,
    ): Boolean {
        val destination = modelFile(entry)
        val installedReady = verifyInstalledCatalogModel(entry, destination)
        val status = if (installedReady) ModelDownloadStatus.READY else ModelDownloadStatus.FAILED
        val editor = prefs.edit()
            .putString(statusKey(entry.id), status.name)
            .putInt(progressKey(entry.id), if (installedReady) 100 else -1)
            .putLong(downloadedBytesKey(entry.id), if (installedReady) destination.length() else 0L)
            .putLong(
                totalBytesKey(entry.id),
                if (installedReady) destination.length() else entry.expectedDownloadSizeBytes ?: -1L,
            )
            .putString(failureKey(entry.id), failureCode.storageKey)
        if (installedReady) {
            editor
                .putString(pathKey(entry.id), destination.absolutePath)
                .putLong(installedBytesKey(entry.id), destination.length())
                .putString(shaKey(entry.id), requireNotNull(entry.sha256))
        } else {
            editor
                .remove(pathKey(entry.id))
                .remove(installedBytesKey(entry.id))
                .remove(shaKey(entry.id))
        }
        return editor.commit()
    }

    private fun installDir(entry: ModelCatalogEntry): File {
        return File(appContext.filesDir, "models/${entry.id}")
    }

    private fun stagingDir(entry: ModelCatalogEntry): File = File(installDir(entry), STAGING_DIR_NAME)

    private fun cleanupStagingLocked(entry: ModelCatalogEntry) {
        stagingDir(entry).deleteRecursively()
    }

    private fun isExpectedPartFile(entry: ModelCatalogEntry, generation: Long, partFile: File): Boolean {
        if (!partFile.name.matches(Regex("${generation}\\.[A-Za-z0-9_-]{1,80}\\.part"))) return false
        return runCatching { partFile.parentFile?.canonicalFile == stagingDir(entry).canonicalFile }.getOrDefault(false)
    }

    private fun statusFor(modelId: String): ModelDownloadStatus {
        val stored = prefs.getString(statusKey(modelId), null) ?: return ModelDownloadStatus.NOT_DOWNLOADED
        return runCatching { ModelDownloadStatus.valueOf(stored) }.getOrDefault(ModelDownloadStatus.NOT_DOWNLOADED)
    }

    private fun generation(modelId: String): Long = prefs.getLong(generationKey(modelId), 0L).coerceAtLeast(0L)

    private fun nextGeneration(modelId: String): Long {
        val current = generation(modelId)
        check(current < Long.MAX_VALUE) { "Model download generation is exhausted." }
        return current + 1L
    }

    private fun isCurrentGenerationLocked(modelId: String, expectedGeneration: Long): Boolean {
        return expectedGeneration > 0L && generation(modelId) == expectedGeneration
    }

    private fun hasUntrustedInstallState(
        entry: ModelCatalogEntry,
        installedFile: File,
        status: ModelDownloadStatus,
    ): Boolean {
        if (
            entry.downloadConfigured &&
            (status == ModelDownloadStatus.QUEUED || status == ModelDownloadStatus.DOWNLOADING)
        ) {
            return false
        }
        if (
            !entry.downloadConfigured &&
            (status == ModelDownloadStatus.QUEUED || status == ModelDownloadStatus.DOWNLOADING)
        ) {
            return true
        }
        return installedFile.exists() ||
            stagingDir(entry).listFiles().orEmpty().isNotEmpty() ||
            prefs.contains(pathKey(entry.id)) ||
            status == ModelDownloadStatus.READY
    }

    private fun verifyInstalledCatalogModel(entry: ModelCatalogEntry, file: File): Boolean {
        if (!entry.downloadConfigured) {
            VERIFIED_FILE_CACHE.remove(file.absolutePath)
            return false
        }
        val expectedBytes = entry.expectedDownloadSizeBytes ?: return false
        val expectedSha256 = entry.sha256 ?: return false
        if (
            !file.isFile ||
            !file.canRead() ||
            file.length() != expectedBytes ||
            Files.isSymbolicLink(file.toPath())
        ) {
            VERIFIED_FILE_CACHE.remove(file.absolutePath)
            return false
        }
        val cached = VERIFIED_FILE_CACHE[file.absolutePath]
        if (
            cached != null &&
            cached.expectedSha256 == expectedSha256 &&
            cached.sizeBytes == file.length() &&
            cached.lastModifiedMillis == file.lastModified()
        ) {
            return true
        }
        val verified = runCatching { sha256(file) == expectedSha256 }.getOrDefault(false)
        if (verified) {
            VERIFIED_FILE_CACHE[file.absolutePath] = VerifiedFileCacheEntry(
                expectedSha256 = expectedSha256,
                sizeBytes = file.length(),
                lastModifiedMillis = file.lastModified(),
            )
        } else {
            VERIFIED_FILE_CACHE.remove(file.absolutePath)
        }
        return verified
    }

    private fun verifyArtifactFile(entry: ModelCatalogEntry, file: File): Boolean {
        val expectedBytes = entry.expectedDownloadSizeBytes ?: return false
        val expectedSha256 = entry.sha256 ?: return false
        if (!entry.downloadConfigured || !file.isFile || !file.canRead()) return false
        if (file.length() != expectedBytes || Files.isSymbolicLink(file.toPath())) return false
        return runCatching { sha256(file) == expectedSha256 }.getOrDefault(false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun transportFingerprint(entry: ModelCatalogEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            entry.id,
            entry.downloadUrl,
            entry.fileName,
            entry.expectedDownloadSizeBytes?.toString(),
            entry.sha256,
        ).forEach { value ->
            if (value == null) {
                digest.update(0)
            } else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(1)
                digest.update((bytes.size ushr 24).toByte())
                digest.update((bytes.size ushr 16).toByte())
                digest.update((bytes.size ushr 8).toByte())
                digest.update(bytes.size.toByte())
                digest.update(bytes)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun clearInstallMetadata(modelId: String) {
        check(
            prefs.edit()
                .putString(statusKey(modelId), ModelDownloadStatus.NOT_DOWNLOADED.name)
                .remove(progressKey(modelId))
                .remove(downloadedBytesKey(modelId))
                .remove(totalBytesKey(modelId))
                .remove(pathKey(modelId))
                .remove(installedBytesKey(modelId))
                .remove(shaKey(modelId))
                .remove(failureKey(modelId))
                .commit(),
        ) { "Could not clear model install metadata." }
    }

    private fun storedFailureCode(modelId: String): ModelDownloadFailureCode? {
        val stored = prefs.getString(failureKey(modelId), null) ?: return null
        val failureCode = ModelDownloadFailureCode.fromStorageKey(stored)
            ?: ModelDownloadFailureCode.DOWNLOAD_FAILED
        if (failureCode.storageKey != stored) {
            prefs.edit().putString(failureKey(modelId), failureCode.storageKey).commit()
        }
        return failureCode
    }

    private fun generationKey(modelId: String) = "$modelId.generation"
    private fun configurationKey(modelId: String) = "$modelId.transport_fingerprint"
    private fun statusKey(modelId: String) = "$modelId.status"
    private fun progressKey(modelId: String) = "$modelId.progress"
    private fun downloadedBytesKey(modelId: String) = "$modelId.downloaded_bytes"
    private fun totalBytesKey(modelId: String) = "$modelId.total_bytes"
    private fun installedBytesKey(modelId: String) = "$modelId.installed_bytes"
    private fun pathKey(modelId: String) = "$modelId.path"
    private fun shaKey(modelId: String) = "$modelId.sha256"
    private fun failureKey(modelId: String) = "$modelId.failure"

    private companion object {
        const val PREFS_NAME = "grayin_model_installs"
        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        const val INSTALLED_MODEL_FILE_NAME = "model.litertlm"
        const val STAGING_DIR_NAME = "staging"
        const val PART_SUFFIX = ".part"
        val SAFE_WORKER_ID = Regex("[A-Za-z0-9_-]{1,80}")
        val INSTALL_LOCK = Any()
        val VERIFIED_FILE_CACHE = ConcurrentHashMap<String, VerifiedFileCacheEntry>()
    }

    private data class VerifiedFileCacheEntry(
        val expectedSha256: String,
        val sizeBytes: Long,
        val lastModifiedMillis: Long,
    )
}
