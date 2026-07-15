package ai.grayin.core.ai

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class ModelInstallRecord(
    val modelId: String,
    val status: ModelDownloadStatus,
    val progressPercent: Int?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val installedBytes: Long,
    val installedPath: String?,
    val sha256: String?,
    val failureCode: ModelDownloadFailureCode?,
)

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

    fun recordFor(entry: ModelCatalogEntry): ModelInstallRecord {
        var status = statusFor(entry.id)
        val installedFile = modelFile(entry)
        val fileReady = verifyInstalledCatalogModel(entry, installedFile)
        if (!fileReady && hasUntrustedInstallState(entry, installedFile, status)) {
            installedFile.delete()
            VERIFIED_FILE_CACHE.remove(installedFile.absolutePath)
            tempFile(entry).delete()
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
        return ModelInstallRecord(
            modelId = entry.id,
            status = effectiveStatus,
            progressPercent = prefs.getInt(progressKey(entry.id), -1).takeIf { it >= 0 },
            downloadedBytes = prefs.getLong(downloadedBytesKey(entry.id), 0L),
            totalBytes = prefs.getLong(totalBytesKey(entry.id), -1L).takeIf { it > 0L },
            installedBytes = if (installedPath == null) 0L else File(installedPath).length(),
            installedPath = installedPath,
            sha256 = prefs.getString(shaKey(entry.id), null),
            failureCode = storedFailureCode(entry.id),
        )
    }

    fun selectedReadyModelFile(): File? {
        val entry = selectedEntry()
        val record = recordFor(entry)
        val path = record.installedPath ?: return null
        val file = File(path)
        return file.takeIf { record.status == ModelDownloadStatus.READY && it.isFile && it.canRead() }
    }

    fun recordQueued(modelId: String) {
        prefs.edit()
            .putString(statusKey(modelId), ModelDownloadStatus.QUEUED.name)
            .putInt(progressKey(modelId), 0)
            .remove(failureKey(modelId))
            .apply()
    }

    fun recordDownloading(modelId: String, downloadedBytes: Long, totalBytes: Long?, progressPercent: Int?) {
        prefs.edit()
            .putString(statusKey(modelId), ModelDownloadStatus.DOWNLOADING.name)
            .putLong(downloadedBytesKey(modelId), downloadedBytes)
            .putLong(totalBytesKey(modelId), totalBytes ?: -1L)
            .putInt(progressKey(modelId), progressPercent ?: -1)
            .remove(failureKey(modelId))
            .apply()
    }

    fun recordReady(entry: ModelCatalogEntry, file: File, installedBytes: Long, sha256: String?) {
        val expectedSha256 = requireNotNull(entry.sha256) { "Model digest is not configured." }
        val expectedBytes = requireNotNull(entry.expectedDownloadSizeBytes) { "Model size is not configured." }
        require(entry.downloadConfigured) { "Model download is not enabled." }
        require(file.canonicalFile == modelFile(entry).canonicalFile) { "Model path is not canonical." }
        require(installedBytes == expectedBytes && file.length() == expectedBytes) { "Model size is invalid." }
        VERIFIED_FILE_CACHE.remove(file.absolutePath)
        require(sha256 == expectedSha256 && verifyInstalledCatalogModel(entry, file)) {
            "Model digest is invalid."
        }
        prefs.edit()
            .putString(statusKey(entry.id), ModelDownloadStatus.READY.name)
            .putInt(progressKey(entry.id), 100)
            .putLong(downloadedBytesKey(entry.id), installedBytes)
            .putLong(totalBytesKey(entry.id), installedBytes)
            .putString(pathKey(entry.id), file.absolutePath)
            .putLong(installedBytesKey(entry.id), installedBytes)
            .putString(shaKey(entry.id), sha256)
            .remove(failureKey(entry.id))
            .apply()
    }

    fun recordFailed(modelId: String, failureCode: ModelDownloadFailureCode) {
        prefs.edit()
            .putString(statusKey(modelId), ModelDownloadStatus.FAILED.name)
            .putString(failureKey(modelId), failureCode.storageKey)
            .apply()
    }

    fun recordNotDownloaded(modelId: String) {
        prefs.edit()
            .putString(statusKey(modelId), ModelDownloadStatus.NOT_DOWNLOADED.name)
            .remove(progressKey(modelId))
            .remove(downloadedBytesKey(modelId))
            .remove(totalBytesKey(modelId))
            .remove(pathKey(modelId))
            .remove(installedBytesKey(modelId))
            .remove(shaKey(modelId))
            .remove(failureKey(modelId))
            .apply()
    }

    fun deleteInstalledModel(modelId: String): Boolean {
        val entry = ModelCatalog.entry(modelId) ?: return false
        val deleted = installDir(entry).deleteRecursively()
        VERIFIED_FILE_CACHE.remove(modelFile(entry).absolutePath)
        recordNotDownloaded(modelId)
        return deleted
    }

    fun modelFile(entry: ModelCatalogEntry): File {
        return File(installDir(entry), INSTALLED_MODEL_FILE_NAME)
    }

    fun tempFile(entry: ModelCatalogEntry): File {
        return File(installDir(entry), TEMP_MODEL_FILE_NAME)
    }

    private fun installDir(entry: ModelCatalogEntry): File {
        return File(appContext.filesDir, "models/${entry.id}")
    }

    private fun statusFor(modelId: String): ModelDownloadStatus {
        val stored = prefs.getString(statusKey(modelId), null) ?: return ModelDownloadStatus.NOT_DOWNLOADED
        return runCatching { ModelDownloadStatus.valueOf(stored) }.getOrDefault(ModelDownloadStatus.NOT_DOWNLOADED)
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
            tempFile(entry).exists() ||
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { source ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun clearInstallMetadata(modelId: String) {
        prefs.edit()
            .putString(statusKey(modelId), ModelDownloadStatus.NOT_DOWNLOADED.name)
            .remove(progressKey(modelId))
            .remove(downloadedBytesKey(modelId))
            .remove(totalBytesKey(modelId))
            .remove(pathKey(modelId))
            .remove(installedBytesKey(modelId))
            .remove(shaKey(modelId))
            .remove(failureKey(modelId))
            .commit()
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
        const val TEMP_MODEL_FILE_NAME = "model.litertlm.tmp"
        val VERIFIED_FILE_CACHE = ConcurrentHashMap<String, VerifiedFileCacheEntry>()
    }

    private data class VerifiedFileCacheEntry(
        val expectedSha256: String,
        val sizeBytes: Long,
        val lastModifiedMillis: Long,
    )
}
