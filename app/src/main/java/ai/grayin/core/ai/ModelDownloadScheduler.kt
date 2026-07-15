package ai.grayin.core.ai

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

class ModelDownloadScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val installStore = ModelInstallStore(appContext)
    private val workManager = WorkManager.getInstance(appContext)

    fun enqueue(modelId: String): Boolean = synchronized(WORK_LOCK) {
        val entry = ModelCatalog.entry(modelId)?.takeIf { it.downloadConfigured } ?: return false
        val generation = runCatching { installStore.beginInstall(entry) }.getOrElse { return false }
        val request = requestFor(modelId, generation)
        runCatching {
            workManager.enqueueUniqueWork(uniqueNameFor(modelId), EXISTING_WORK_POLICY, request)
                .result
                .get(ENQUEUE_WAIT_SECONDS, TimeUnit.SECONDS)
            true
        }.getOrElse {
            val invalidatedGeneration = installStore.invalidateForCancel(entry)
            runCatching {
                workManager.cancelUniqueWork(uniqueNameFor(modelId))
                    .result
                    .get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS)
            }
            installStore.cleanupStaging(entry)
            installStore.recordFailed(
                entry = entry,
                generation = invalidatedGeneration,
                failureCode = ModelDownloadFailureCode.NETWORK_OR_IO_FAILURE,
            )
            false
        }
    }

    suspend fun cancel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(WORK_LOCK) {
            val entry = ModelCatalog.entry(modelId) ?: return@synchronized false
            installStore.invalidateForCancel(entry)
            val canceled = runCatching {
                workManager.cancelUniqueWork(uniqueNameFor(modelId))
                    .result
                    .get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS)
                true
            }.getOrDefault(false)
            installStore.cleanupStaging(entry)
            canceled
        }
    }

    suspend fun delete(modelId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(WORK_LOCK) {
            val entry = ModelCatalog.entry(modelId) ?: return@synchronized false
            val generation = installStore.invalidateForCancel(entry)
            val canceled = runCatching {
                workManager.cancelUniqueWork(uniqueNameFor(modelId))
                    .result
                    .get(CANCEL_WAIT_SECONDS, TimeUnit.SECONDS)
                true
            }.getOrDefault(false)
            if (!canceled) return@synchronized false
            installStore.deleteInstalled(entry, generation)
        }
    }

    suspend fun reconcile(modelId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(WORK_LOCK) {
            val entry = ModelCatalog.entry(modelId) ?: return@synchronized false
            installStore.synchronizeConfiguration(entry)
            val record = installStore.recordFor(entry)
            if (
                record.status != ModelDownloadStatus.QUEUED &&
                record.status != ModelDownloadStatus.DOWNLOADING
            ) {
                return@synchronized true
            }
            val workInfos = runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueNameFor(modelId))
                    .get(RECONCILE_WAIT_SECONDS, TimeUnit.SECONDS)
            }.getOrElse {
                return@synchronized false
            }
            if (!hasActiveWork(workInfos.map { workInfo -> workInfo.state })) {
                installStore.cleanupStaging(entry)
                installStore.recordFailed(
                    entry = entry,
                    generation = record.generation,
                    failureCode = ModelDownloadFailureCode.NETWORK_OR_IO_FAILURE,
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

        fun uniqueNameFor(modelId: String): String = "grayin-model-download-$modelId"

        fun tagFor(modelId: String): String = "model-download-$modelId"

        fun requestFor(modelId: String, generation: Long) =
            OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(downloadConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    MIN_BACKOFF.toMillis(),
                    TimeUnit.MILLISECONDS,
                )
                .setInputData(inputData(modelId, generation))
                .addTag(tagFor(modelId))
                .build()

        fun inputData(modelId: String, generation: Long) = workDataOf(
            ModelDownloadWorker.KEY_MODEL_ID to modelId,
            ModelDownloadWorker.KEY_GENERATION to generation,
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
