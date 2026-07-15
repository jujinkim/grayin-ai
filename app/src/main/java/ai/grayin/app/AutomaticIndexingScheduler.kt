package ai.grayin.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ai.grayin.core.indexing.AutomaticIndexingWorker
import ai.grayin.core.store.SqlCipherLocalMemoryStore
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AutomaticIndexingScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val store = SqlCipherLocalMemoryStore(appContext)
    private val preferenceStore = AutomaticIndexingPreferenceStore(appContext)

    fun requestSync(): Deferred<Unit> = APPLICATION_SCOPE.async {
        sync()
    }

    suspend fun disableForImport() {
        val current = preferenceStore.load()
        preferenceStore.save(current.copy(enabled = false))
        requestSync().await()
    }

    private suspend fun sync(): Unit = SYNC_MUTEX.withLock {
        val state = preferenceStore.load()
        withContext(Dispatchers.IO) {
            store.synchronizeAutomaticIndexingControl(
                enabled = state.enabled,
                settingsKey = state.controlSettingsKey(),
                changedAt = Instant.now(),
            )
        }
        if (!state.enabled) {
            withContext(Dispatchers.IO) {
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME).result.get()
            }
            return@withLock
        }

        val schedule = automaticIndexingScheduleConfiguration(state)
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(schedule.requiresBatteryNotLow)
            .setRequiresStorageNotLow(schedule.requiresStorageNotLow)
            .setRequiresCharging(schedule.requiresCharging)
            .setRequiredNetworkType(schedule.requiredNetworkType)
            .build()
        val request = PeriodicWorkRequestBuilder<AutomaticIndexingWorker>(
            repeatInterval = schedule.repeatIntervalHours,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = schedule.flexIntervalMinutes,
            flexTimeIntervalUnit = TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()

        withContext(Dispatchers.IO) {
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            ).result.get()
        }
        Unit
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "grayin-automatic-indexing"
        const val WORK_TAG = "automatic-indexing"
        val APPLICATION_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val SYNC_MUTEX = Mutex()
    }
}

internal data class AutomaticIndexingScheduleConfiguration(
    val requiresCharging: Boolean,
    val requiresBatteryNotLow: Boolean = true,
    val requiresStorageNotLow: Boolean = true,
    val requiredNetworkType: NetworkType = NetworkType.NOT_REQUIRED,
    val repeatIntervalHours: Long = 1L,
    val flexIntervalMinutes: Long = 15L,
)

internal fun automaticIndexingScheduleConfiguration(
    state: AutomaticIndexingUiState,
): AutomaticIndexingScheduleConfiguration {
    return AutomaticIndexingScheduleConfiguration(requiresCharging = state.requireCharging)
}
