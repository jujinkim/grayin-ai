package ai.grayin.core.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ModelDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    private val installStore = ModelInstallStore(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val entry = ModelCatalog.entry(modelId) ?: return@withContext Result.failure()
        val downloadUrl = entry.downloadUrl
            ?: return@withContext fail(modelId, "Model download is not configured.")
        val tempFile = installStore.tempFile(entry)

        try {
            setForeground(createForegroundInfo(entry, 0))
            download(entry, downloadUrl, tempFile)
            Result.success()
        } catch (error: CancellationException) {
            tempFile.delete()
            installStore.recordNotDownloaded(modelId)
            throw error
        } catch (error: Throwable) {
            tempFile.delete()
            installStore.recordFailed(modelId, error.message)
            Result.failure()
        }
    }

    private suspend fun download(entry: ModelCatalogEntry, downloadUrl: String, tempFile: File) {
        val modelDir = requireNotNull(tempFile.parentFile) { "Model directory is unavailable." }
        modelDir.mkdirs()
        tempFile.delete()

        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "GrayinAI/0.1")
        }

        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode")
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: entry.approxSizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var copiedBytes = 0L
            var lastProgressPercent = -1

            installStore.recordDownloading(entry.id, downloadedBytes = 0L, totalBytes = totalBytes, progressPercent = 0)
            connection.inputStream.use { source ->
                tempFile.outputStream().use { target ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        target.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copiedBytes += read

                        val progressPercent = progressPercent(copiedBytes, totalBytes)
                        if (progressPercent != lastProgressPercent) {
                            lastProgressPercent = progressPercent
                            installStore.recordDownloading(entry.id, copiedBytes, totalBytes, progressPercent)
                            setProgress(
                                workDataOf(
                                    KEY_MODEL_ID to entry.id,
                                    KEY_PROGRESS_PERCENT to progressPercent,
                                    KEY_DOWNLOADED_BYTES to copiedBytes,
                                    KEY_TOTAL_BYTES to totalBytes,
                                ),
                            )
                            setForeground(createForegroundInfo(entry, progressPercent))
                        }
                    }
                }
            }

            if (copiedBytes < MIN_MODEL_BYTES) {
                throw IOException("Downloaded model is too small.")
            }
            val sha256 = digest.digest().joinToString(separator = "") { "%02x".format(it) }
            val expectedSha256 = entry.sha256
            if (expectedSha256 != null && !sha256.equals(expectedSha256, ignoreCase = true)) {
                throw IOException("Downloaded model checksum mismatch.")
            }

            val destination = installStore.modelFile(entry)
            if (destination.exists() && !destination.delete()) {
                throw IOException("Existing model file cannot be replaced.")
            }
            if (!tempFile.renameTo(destination)) {
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
            installStore.recordReady(entry, destination, copiedBytes, sha256)
        } finally {
            connection.disconnect()
        }
    }

    private fun fail(modelId: String, message: String): Result {
        installStore.recordFailed(modelId, message)
        return Result.failure()
    }

    private fun createForegroundInfo(entry: ModelCatalogEntry, progressPercent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Grayin model downloads",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        val notification = Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(entry.displayName)
            .setContentText("Downloading local model $progressPercent%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent.coerceIn(0, 100), false)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun progressPercent(downloadedBytes: Long, totalBytes: Long?): Int {
        if (totalBytes == null || totalBytes <= 0L) return 0
        return ((downloadedBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"

        private const val NOTIFICATION_CHANNEL_ID = "grayin_model_downloads"
        private const val NOTIFICATION_ID = 2101
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val MIN_MODEL_BYTES = 100L * 1024L * 1024L
    }
}
