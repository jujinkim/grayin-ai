package ai.grayin.core.ai

import android.content.Context
import java.io.File

data class ModelInstallRecord(
    val modelId: String,
    val status: ModelDownloadStatus,
    val progressPercent: Int?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val installedBytes: Long,
    val installedPath: String?,
    val sha256: String?,
    val failureReason: String?,
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
        val status = statusFor(entry.id)
        val installedFile = modelFile(entry)
        val fileReady = installedFile.isFile && installedFile.canRead()
        val storedPath = prefs.getString(pathKey(entry.id), null)
        val installedPath = when {
            fileReady -> installedFile.absolutePath
            storedPath != null && File(storedPath).isFile -> storedPath
            else -> null
        }
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
            failureReason = prefs.getString(failureKey(entry.id), null),
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

    fun recordFailed(modelId: String, reason: String?) {
        prefs.edit()
            .putString(statusKey(modelId), ModelDownloadStatus.FAILED.name)
            .putString(failureKey(modelId), reason ?: "Download failed.")
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
    }
}
