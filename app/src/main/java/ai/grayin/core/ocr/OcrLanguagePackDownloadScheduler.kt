package ai.grayin.core.ocr

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OcrLanguagePackDownloadScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val installStore = OcrLanguagePackInstallStore(appContext)
    private val workManager = WorkManager.getInstance(appContext)

    fun enqueue(packId: String): Boolean = synchronized(WORK_LOCK) {
        val entry = OcrLanguagePackCatalog.entry(packId) ?: return false
        val generation = installStore.beginInstall(entry)
        val request = requestFor(packId, generation)
        runCatching {
            workManager.enqueueUniqueWork(uniqueNameFor(packId), EXISTING_WORK_POLICY, request)
                .result
                .get(ENQUEUE_WAIT_SECONDS, TimeUnit.SECONDS)
            true
        }.getOrElse {
            val invalidatedGeneration = installStore.invalidateForCancel(entry)
            runCatching {
                workManager.cancelUniqueWork(uniqueNameFor(packId))
                    .result
                    .get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS)
            }
            installStore.cleanupStaging(packId)
            installStore.recordFailed(
                entry,
                invalidatedGeneration,
                OcrLanguagePackFailureCode.NETWORK_OR_IO_FAILURE,
            )
            false
        }
    }

    suspend fun cancel(packId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(WORK_LOCK) {
            val entry = OcrLanguagePackCatalog.entry(packId) ?: return@synchronized false
            installStore.invalidateForCancel(entry)
            val canceled = runCatching {
                workManager.cancelUniqueWork(uniqueNameFor(packId)).result.get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS)
                true
            }.getOrDefault(false)
            installStore.cleanupStaging(packId)
            canceled
        }
    }

    suspend fun delete(packId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(WORK_LOCK) {
            val entry = OcrLanguagePackCatalog.entry(packId) ?: return@synchronized false
            installStore.invalidateForCancel(entry)
            val canceled = runCatching {
                workManager.cancelUniqueWork(uniqueNameFor(packId)).result.get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS)
                true
            }.getOrDefault(false)
            if (!canceled) return@synchronized false
            installStore.deleteInstalled(entry)
        }
    }

    suspend fun reconcile(packId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(WORK_LOCK) {
            val entry = OcrLanguagePackCatalog.entry(packId) ?: return@synchronized false
            val record = installStore.recordFor(entry)
            if (
                record.status != OcrLanguagePackStatus.QUEUED &&
                record.status != OcrLanguagePackStatus.DOWNLOADING
            ) {
                return@synchronized true
            }
            val workInfos = runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueNameFor(packId))
                    .get(RECONCILE_WAIT_SECONDS, TimeUnit.SECONDS)
            }.getOrElse {
                return@synchronized false
            }
            val hasActiveWork = hasActiveWork(workInfos.map { workInfo -> workInfo.state })
            if (!hasActiveWork) {
                installStore.recordFailed(
                    entry,
                    record.generation,
                    OcrLanguagePackFailureCode.NETWORK_OR_IO_FAILURE,
                )
            }
            true
        }
    }

    internal companion object {
        val MIN_BACKOFF: Duration = Duration.ofSeconds(30)
        val EXISTING_WORK_POLICY = ExistingWorkPolicy.REPLACE
        const val ENQUEUE_WAIT_SECONDS = 30L
        const val CANCEL_WAIT_SECONDS = 30L
        const val RECONCILE_WAIT_SECONDS = 30L
        val WORK_LOCK = Any()

        fun downloadConstraints(): Constraints {
            return Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresStorageNotLow(true)
                .build()
        }

        fun uniqueNameFor(packId: String): String = "grayin-ocr-language-pack-$packId"

        fun tagFor(packId: String): String = "ocr-language-pack-$packId"

        fun requestFor(packId: String, generation: Long) =
            OneTimeWorkRequestBuilder<OcrLanguagePackDownloadWorker>()
                .setConstraints(downloadConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    MIN_BACKOFF.toMillis(),
                    TimeUnit.MILLISECONDS,
                )
                .setInputData(inputData(packId, generation))
                .addTag(tagFor(packId))
                .build()

        fun inputData(packId: String, generation: Long) = workDataOf(
            OcrLanguagePackDownloadWorker.KEY_PACK_ID to packId,
            OcrLanguagePackDownloadWorker.KEY_GENERATION to generation,
        )

        fun hasActiveWork(states: Iterable<WorkInfo.State>): Boolean {
            return states.any { state ->
                state == WorkInfo.State.ENQUEUED ||
                    state == WorkInfo.State.RUNNING ||
                    state == WorkInfo.State.BLOCKED
            }
        }
    }
}
