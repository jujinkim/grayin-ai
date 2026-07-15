package ai.grayin.core.ocr

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.grayin.core.artifact.ArtifactDownloadFailureCode
import ai.grayin.core.artifact.ArtifactDownloadResult
import ai.grayin.core.artifact.FixedCatalogArtifactDownloader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OcrLanguagePackDownloadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    private val installStore = OcrLanguagePackInstallStore(appContext)
    private val downloader = FixedCatalogArtifactDownloader()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val packId = inputData.getString(KEY_PACK_ID) ?: return@withContext Result.failure()
        val generation = inputData.getLong(KEY_GENERATION, -1L)
        val entry = OcrLanguagePackCatalog.entry(packId) ?: return@withContext Result.failure()
        if (!installStore.isCurrentGeneration(packId, generation)) return@withContext Result.success()
        val partFile = installStore.partFile(entry, generation, id.toString())
        try {
            if (!installStore.recordDownloading(packId, generation, 0)) {
                return@withContext Result.success()
            }
            when (
                val download = downloader.downloadToPart(
                    artifact = entry.artifact,
                    partFile = partFile,
                    onProgress = { downloadedBytes, totalBytes ->
                        val progress = ((downloadedBytes * 100L) / totalBytes)
                            .coerceIn(0L, 99L)
                            .toInt()
                        if (!installStore.recordDownloading(packId, generation, progress)) {
                            throw CancellationException("OCR language-pack generation was invalidated.")
                        }
                        setProgress(
                            workDataOf(
                                KEY_PACK_ID to packId,
                                KEY_GENERATION to generation,
                                KEY_PROGRESS_PERCENT to progress,
                            ),
                        )
                    },
                )
            ) {
                is ArtifactDownloadResult.Verified -> {
                    OcrLanguagePackWorkPolicy.afterPublish(
                        installStore.publishVerified(entry, generation, partFile),
                    ).toWorkerResult()
                }

                is ArtifactDownloadResult.Failed -> {
                    val failureCode = download.code.toInstallFailure()
                    val decision = OcrLanguagePackWorkPolicy.afterDownloadFailure(
                        retryable = download.retryable,
                        runAttemptCount = runAttemptCount,
                    )
                    when (decision) {
                        OcrLanguagePackWorkDecision.RETRY -> installStore.recordQueuedForRetry(
                            entry = entry,
                            generation = generation,
                            failureCode = failureCode,
                        )

                        OcrLanguagePackWorkDecision.FAILURE -> installStore.recordFailed(
                            entry,
                            generation,
                            failureCode,
                        )

                        OcrLanguagePackWorkDecision.SUCCESS -> Unit
                    }
                    decision.toWorkerResult()
                }
            }
        } catch (error: CancellationException) {
            partFile.delete()
            throw error
        } catch (_: Throwable) {
            partFile.delete()
            installStore.recordFailed(
                entry,
                generation,
                OcrLanguagePackFailureCode.NETWORK_OR_IO_FAILURE,
            )
            Result.failure()
        }
    }

    private fun ArtifactDownloadFailureCode.toInstallFailure(): OcrLanguagePackFailureCode {
        return when (this) {
            ArtifactDownloadFailureCode.REDIRECT_REJECTED -> OcrLanguagePackFailureCode.REDIRECT_REJECTED
            ArtifactDownloadFailureCode.HTTP_REJECTED -> OcrLanguagePackFailureCode.HTTP_REJECTED
            ArtifactDownloadFailureCode.SERVER_ERROR -> OcrLanguagePackFailureCode.SERVER_ERROR
            ArtifactDownloadFailureCode.CONTENT_TYPE_INVALID -> OcrLanguagePackFailureCode.CONTENT_TYPE_INVALID
            ArtifactDownloadFailureCode.CONTENT_ENCODING_INVALID -> OcrLanguagePackFailureCode.CONTENT_ENCODING_INVALID
            ArtifactDownloadFailureCode.SIZE_MISMATCH -> OcrLanguagePackFailureCode.SIZE_MISMATCH
            ArtifactDownloadFailureCode.CHECKSUM_MISMATCH -> OcrLanguagePackFailureCode.CHECKSUM_MISMATCH
            ArtifactDownloadFailureCode.IO_FAILURE -> OcrLanguagePackFailureCode.NETWORK_OR_IO_FAILURE
        }
    }

    private fun OcrLanguagePackWorkDecision.toWorkerResult(): Result {
        return when (this) {
            OcrLanguagePackWorkDecision.SUCCESS -> Result.success()
            OcrLanguagePackWorkDecision.RETRY -> Result.retry()
            OcrLanguagePackWorkDecision.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val KEY_PACK_ID = "ocr_pack_id"
        const val KEY_GENERATION = "ocr_pack_generation"
        const val KEY_PROGRESS_PERCENT = "ocr_pack_progress_percent"
    }
}
