package ai.grayin.core.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class ModelDownloadScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    fun enqueue(modelId: String) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build(),
            )
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to modelId))
            .addTag(tagFor(modelId))
            .build()

        workManager.enqueueUniqueWork(uniqueNameFor(modelId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(modelId: String) {
        workManager.cancelUniqueWork(uniqueNameFor(modelId))
    }

    private fun uniqueNameFor(modelId: String): String {
        return "grayin-model-download-$modelId"
    }

    private fun tagFor(modelId: String): String {
        return "model-download-$modelId"
    }
}
