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
import ai.grayin.core.artifact.ArtifactDownloadFailureCode
import ai.grayin.core.artifact.ArtifactDownloadResult
import ai.grayin.core.artifact.FixedCatalogArtifactDownloader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ModelDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    private val installStore = ModelInstallStore(appContext)
    private val downloader = FixedCatalogArtifactDownloader()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val entry = ModelCatalog.entry(modelId) ?: return@withContext Result.failure()
        if (!entry.downloadConfigured) {
            return@withContext fail(modelId, ModelDownloadFailureCode.DOWNLOAD_NOT_CONFIGURED)
        }
        val artifact = entry.artifactSpecOrNull()
            ?: return@withContext fail(modelId, ModelDownloadFailureCode.DOWNLOAD_NOT_CONFIGURED)
        val tempFile = installStore.tempFile(entry)

        try {
            setForeground(createForegroundInfo(entry, 0))
            installStore.recordDownloading(
                modelId = entry.id,
                downloadedBytes = 0L,
                totalBytes = artifact.expectedSizeBytes,
                progressPercent = 0,
            )
            when (
                val download = downloader.downloadToPart(
                    artifact = artifact,
                    partFile = tempFile,
                    onProgress = { downloadedBytes, totalBytes ->
                        val progressPercent = progressPercent(downloadedBytes, totalBytes)
                        installStore.recordDownloading(
                            modelId = entry.id,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            progressPercent = progressPercent,
                        )
                        setProgress(
                            workDataOf(
                                KEY_MODEL_ID to entry.id,
                                KEY_PROGRESS_PERCENT to progressPercent,
                                KEY_DOWNLOADED_BYTES to downloadedBytes,
                                KEY_TOTAL_BYTES to totalBytes,
                            ),
                        )
                        setForeground(createForegroundInfo(entry, progressPercent))
                    },
                )
            ) {
                is ArtifactDownloadResult.Verified -> publish(entry, tempFile, download)
                is ArtifactDownloadResult.Failed -> {
                    if (download.retryable && runAttemptCount < MAX_RETRY_ATTEMPT_INDEX) {
                        installStore.recordQueued(modelId)
                        Result.retry()
                    } else {
                        installStore.recordFailed(
                            modelId,
                            download.code.toModelFailureCode(),
                        )
                        Result.failure()
                    }
                }
            }
        } catch (error: CancellationException) {
            tempFile.delete()
            installStore.recordNotDownloaded(modelId)
            throw error
        } catch (_: Throwable) {
            tempFile.delete()
            installStore.recordFailed(modelId, ModelDownloadFailureCode.DOWNLOAD_FAILED)
            Result.failure()
        }
    }

    private suspend fun publish(
        entry: ModelCatalogEntry,
        tempFile: java.io.File,
        verified: ArtifactDownloadResult.Verified,
    ): Result {
        currentCoroutineContext().ensureActive()
        val destination = installStore.modelFile(entry)
        try {
            Files.move(
                tempFile.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            tempFile.delete()
            installStore.recordFailed(entry.id, ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED)
            return Result.failure()
        }
        installStore.recordReady(entry, destination, verified.bytes, verified.sha256)
        return Result.success()
    }

    private fun fail(modelId: String, failureCode: ModelDownloadFailureCode): Result {
        installStore.recordFailed(modelId, failureCode)
        return Result.failure()
    }

    private fun ArtifactDownloadFailureCode.toModelFailureCode(): ModelDownloadFailureCode {
        return when (this) {
            ArtifactDownloadFailureCode.REDIRECT_REJECTED -> ModelDownloadFailureCode.REDIRECT_REJECTED
            ArtifactDownloadFailureCode.HTTP_REJECTED -> ModelDownloadFailureCode.HTTP_REJECTED
            ArtifactDownloadFailureCode.SERVER_ERROR -> ModelDownloadFailureCode.SERVER_ERROR
            ArtifactDownloadFailureCode.CONTENT_TYPE_INVALID -> ModelDownloadFailureCode.CONTENT_TYPE_INVALID
            ArtifactDownloadFailureCode.CONTENT_ENCODING_INVALID -> ModelDownloadFailureCode.CONTENT_ENCODING_INVALID
            ArtifactDownloadFailureCode.SIZE_MISMATCH -> ModelDownloadFailureCode.SIZE_MISMATCH
            ArtifactDownloadFailureCode.CHECKSUM_MISMATCH -> ModelDownloadFailureCode.CHECKSUM_MISMATCH
            ArtifactDownloadFailureCode.IO_FAILURE -> ModelDownloadFailureCode.NETWORK_OR_IO_FAILURE
        }
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

    private fun progressPercent(downloadedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        return ((downloadedBytes * 100L) / totalBytes).coerceIn(0L, 100L).toInt()
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"

        private const val NOTIFICATION_CHANNEL_ID = "grayin_model_downloads"
        private const val NOTIFICATION_ID = 2101
        private const val MAX_RETRY_ATTEMPT_INDEX = 2
    }
}
