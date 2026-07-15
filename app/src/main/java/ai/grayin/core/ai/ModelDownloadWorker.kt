package ai.grayin.core.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.grayin.core.artifact.ArtifactDownloadFailureCode
import ai.grayin.core.artifact.ArtifactDownloadResult
import ai.grayin.core.artifact.FixedCatalogArtifactDownloader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    private val catalogRepository = ModelCatalogRepository(appContext)
    private val installStore = ModelInstallStore(appContext, catalogRepository)
    private val downloader = FixedCatalogArtifactDownloader()
    private val manifestClient = RemoteModelManifestClient.production(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val generation = inputData.getLong(KEY_GENERATION, -1L)
        val entry = when (
            val preflight = ModelDownloadPreflight.run(
                modelId = modelId,
                generation = generation,
                isCurrentGeneration = installStore::isCurrentGeneration,
                refreshManifest = { manifestClient.refresh() },
                resolveEntry = catalogRepository::entry,
                synchronizeConfiguration = installStore::synchronizeConfiguration,
            )
        ) {
            ModelDownloadPreflightResult.STALE_GENERATION -> return@withContext Result.success()
            ModelDownloadPreflightResult.UNKNOWN_MODEL -> return@withContext Result.failure()
            is ModelDownloadPreflightResult.Ready -> preflight.entry
        }
        if (!entry.downloadConfigured) {
            return@withContext fail(entry, generation, ModelDownloadFailureCode.DOWNLOAD_NOT_CONFIGURED)
        }
        val artifact = entry.artifactSpecOrNull()
            ?: return@withContext fail(entry, generation, ModelDownloadFailureCode.DOWNLOAD_NOT_CONFIGURED)
        val partFile = runCatching { installStore.partFile(entry, generation, id.toString()) }
            .getOrElse {
                return@withContext fail(entry, generation, ModelDownloadFailureCode.ATOMIC_INSTALL_FAILED)
            }

        try {
            setForeground(createForegroundInfo(entry, 0))
            var reportedProgressPercent = 0
            if (
                !installStore.recordDownloading(
                    entry = entry,
                    generation = generation,
                    downloadedBytes = 0L,
                    totalBytes = artifact.expectedSizeBytes,
                    progressPercent = 0,
                )
            ) {
                return@withContext Result.success()
            }
            when (
                val download = downloader.downloadToPart(
                    artifact = artifact,
                    partFile = partFile,
                    onProgress = { downloadedBytes, totalBytes ->
                        val progressPercent = progressPercent(downloadedBytes, totalBytes)
                        if (
                            !installStore.recordDownloading(
                                entry = entry,
                                generation = generation,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                progressPercent = progressPercent,
                            )
                        ) {
                            throw StaleModelGenerationException()
                        }
                        if (progressPercent != reportedProgressPercent) {
                            reportedProgressPercent = progressPercent
                            setProgress(
                                workDataOf(
                                    KEY_MODEL_ID to entry.id,
                                    KEY_GENERATION to generation,
                                    KEY_PROGRESS_PERCENT to progressPercent,
                                    KEY_DOWNLOADED_BYTES to downloadedBytes,
                                    KEY_TOTAL_BYTES to totalBytes,
                                ),
                            )
                            setForeground(createForegroundInfo(entry, progressPercent))
                        }
                    },
                )
            ) {
                is ArtifactDownloadResult.Verified -> {
                    ModelDownloadWorkPolicy.afterPublish(
                        installStore.publishVerified(entry, generation, partFile),
                    ).toWorkerResult()
                }

                is ArtifactDownloadResult.Failed -> {
                    if (!installStore.isCurrentGeneration(entry.id, generation)) {
                        return@withContext Result.success()
                    }
                    val failureCode = download.code.toModelFailureCode()
                    val decision = ModelDownloadWorkPolicy.afterDownloadFailure(
                        retryable = download.retryable,
                        runAttemptCount = runAttemptCount,
                    )
                    val stateRecorded = when (decision) {
                        ModelDownloadWorkDecision.RETRY -> installStore.recordQueuedForRetry(
                            entry = entry,
                            generation = generation,
                            failureCode = failureCode,
                        )

                        ModelDownloadWorkDecision.FAILURE -> installStore.recordFailed(
                            entry = entry,
                            generation = generation,
                            failureCode = failureCode,
                        )

                        ModelDownloadWorkDecision.SUCCESS -> true
                    }
                    if (stateRecorded) decision.toWorkerResult() else Result.success()
                }
            }
        } catch (_: StaleModelGenerationException) {
            installStore.discardPartFile(entry, generation, partFile)
            Result.success()
        } catch (error: CancellationException) {
            installStore.discardPartFile(entry, generation, partFile)
            throw error
        } catch (_: Throwable) {
            installStore.discardPartFile(entry, generation, partFile)
            if (
                installStore.recordFailed(
                    entry = entry,
                    generation = generation,
                    failureCode = ModelDownloadFailureCode.DOWNLOAD_FAILED,
                )
            ) {
                Result.failure()
            } else {
                Result.success()
            }
        }
    }

    private fun fail(
        entry: ModelCatalogEntry,
        generation: Long,
        failureCode: ModelDownloadFailureCode,
    ): Result {
        return if (installStore.recordFailed(entry, generation, failureCode)) {
            Result.failure()
        } else {
            Result.success()
        }
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

    private fun ModelDownloadWorkDecision.toWorkerResult(): Result {
        return when (this) {
            ModelDownloadWorkDecision.SUCCESS -> Result.success()
            ModelDownloadWorkDecision.RETRY -> Result.retry()
            ModelDownloadWorkDecision.FAILURE -> Result.failure()
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
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ModelDownloadForegroundPolicy.serviceType(Build.VERSION.SDK_INT),
        )
    }

    private fun progressPercent(downloadedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        return ((downloadedBytes * 100L) / totalBytes).coerceIn(0L, 99L).toInt()
    }

    private class StaleModelGenerationException : CancellationException("Model generation was invalidated.")

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_GENERATION = "model_generation"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"

        private const val NOTIFICATION_CHANNEL_ID = "grayin_model_downloads"
        private const val NOTIFICATION_ID = 2101
    }
}

internal object ModelDownloadForegroundPolicy {
    fun serviceType(sdkInt: Int): Int {
        return if (sdkInt >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
    }
}

internal sealed interface ModelDownloadPreflightResult {
    data object STALE_GENERATION : ModelDownloadPreflightResult

    data object UNKNOWN_MODEL : ModelDownloadPreflightResult

    data class Ready(val entry: ModelCatalogEntry) : ModelDownloadPreflightResult
}

internal object ModelDownloadPreflight {
    suspend fun run(
        modelId: String,
        generation: Long,
        isCurrentGeneration: (modelId: String, generation: Long) -> Boolean,
        refreshManifest: suspend () -> Unit,
        resolveEntry: (modelId: String) -> ModelCatalogEntry?,
        synchronizeConfiguration: (entry: ModelCatalogEntry) -> Long,
    ): ModelDownloadPreflightResult {
        if (!isCurrentGeneration(modelId, generation)) {
            return ModelDownloadPreflightResult.STALE_GENERATION
        }
        refreshManifest()
        val entry = resolveEntry(modelId) ?: return ModelDownloadPreflightResult.UNKNOWN_MODEL
        synchronizeConfiguration(entry)
        if (!isCurrentGeneration(modelId, generation)) {
            return ModelDownloadPreflightResult.STALE_GENERATION
        }
        return ModelDownloadPreflightResult.Ready(entry)
    }
}
