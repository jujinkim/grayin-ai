package ai.grayin.core.indexing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ai.grayin.app.AutomaticIndexingPreferenceStore
import ai.grayin.connectors.AndroidConnectorRegistry
import ai.grayin.core.store.SqlCipherLocalMemoryStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AutomaticIndexingWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val clock = Clock.systemUTC()
        val checkedAt = clock.instant()
        val store = SqlCipherLocalMemoryStore(applicationContext)
        var automaticGeneration: Long? = null
        var startedAt: Instant? = null
        var terminalStatusSaved = false
        return try {
            withContext(Dispatchers.IO) {
                val settings = AutomaticIndexingPreferenceStore(applicationContext).load()
                val control = store.loadAutomaticIndexingControl()
                if (
                    !settings.enabled ||
                    !control.enabled ||
                    settings.controlSettingsKey() != control.settingsKey
                ) {
                    return@withContext Result.success()
                }
                automaticGeneration = control.generation
                currentCoroutineContext().ensureActive()

                val zoneId = ZoneId.systemDefault()
                val conditions = AndroidDeviceIndexingConditionProvider(
                    context = applicationContext,
                    clock = Clock.fixed(checkedAt, ZoneOffset.UTC),
                    zoneId = zoneId,
                ).currentConditions()
                currentCoroutineContext().ensureActive()
                val policy = settings.toPolicy()
                val decision = policy.evaluate(conditions)
                if (!decision.isAllowed) {
                    terminalStatusSaved = store.saveAutomaticIndexingRuntime(
                        status = AutomaticIndexingRuntimeStatus(
                            lastCheckedAt = checkedAt,
                            lastCompletedAt = checkedAt,
                            lastOutcome = AutomaticIndexingOutcome.SKIPPED,
                            lastSkipReason = AutomaticIndexingRunPlanning.skipReasonFor(decision.reason),
                        ),
                        expectedGeneration = control.generation,
                    )
                    return@withContext Result.success()
                }

                val runStartedAt = clock.instant().coerceAtLeast(checkedAt)
                startedAt = runStartedAt
                val markedRunning = store.saveAutomaticIndexingRuntime(
                    status = AutomaticIndexingRuntimeStatus(
                        lastCheckedAt = checkedAt,
                        lastStartedAt = runStartedAt,
                        lastOutcome = AutomaticIndexingOutcome.RUNNING,
                    ),
                    expectedGeneration = control.generation,
                )
                if (!markedRunning) return@withContext Result.success()
                currentCoroutineContext().ensureActive()

                val connectors = AndroidConnectorRegistry(applicationContext)
                val executor = IndexingCommandExecutor(
                    connectorRegistry = connectors.registry,
                    queue = store,
                    scanWriter = store,
                    clock = clock,
                )
                val windowKey = AutomaticIndexingRunPlanning.windowKey(
                    instant = checkedAt,
                    zoneId = zoneId,
                    window = policy.lowUsageWindow,
                )
                currentCoroutineContext().ensureActive()
                val enqueued = executor.enqueue(
                    command = IndexNow,
                    trigger = IndexingTrigger.AUTOMATIC,
                    automaticWindowKey = windowKey,
                    automaticGeneration = control.generation,
                )
                currentCoroutineContext().ensureActive()
                val processed = executor.drain(
                    trigger = IndexingTrigger.AUTOMATIC,
                    leaseOwner = "automatic:${id}:${UUID.randomUUID()}",
                    maxTasks = MAX_TASKS_PER_RUN,
                    automaticGeneration = control.generation,
                )
                currentCoroutineContext().ensureActive()
                val completedAt = clock.instant().coerceAtLeast(runStartedAt)
                val status = AutomaticIndexingRunPlanning.summarize(
                    checkedAt = checkedAt,
                    startedAt = runStartedAt,
                    completedAt = completedAt,
                    enqueuedItems = enqueued,
                    processedItems = processed,
                )
                val saved = store.saveAutomaticIndexingRuntime(
                    status = status,
                    expectedGeneration = control.generation,
                )
                if (!saved) return@withContext Result.success()
                terminalStatusSaved = true
                currentCoroutineContext().ensureActive()
                try {
                    store.pruneTerminalItems(
                        completedBefore = completedAt.minus(TERMINAL_RETENTION),
                        keepLatest = MAX_RETAINED_TERMINAL_TASKS,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Retention is housekeeping; the generation-fenced terminal result is authoritative.
                }
                Result.success()
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                val stoppedAt = clock.instant().coerceAtLeast(startedAt ?: checkedAt)
                runCatching {
                    val control = store.loadAutomaticIndexingControl()
                    val generation = automaticGeneration
                    if (
                        !terminalStatusSaved &&
                        control.enabled &&
                        generation != null &&
                        control.generation == generation
                    ) {
                        store.saveAutomaticIndexingRuntime(
                            status = AutomaticIndexingRuntimeStatus(
                                lastCheckedAt = checkedAt,
                                lastStartedAt = startedAt,
                                lastCompletedAt = stoppedAt,
                                lastOutcome = AutomaticIndexingOutcome.SKIPPED,
                                lastSkipReason = IndexingSkipReason.WORK_MANAGER_STOPPED,
                            ),
                            expectedGeneration = generation,
                        )
                    }
                }
            }
            throw error
        } catch (_: Exception) {
            val failedAt = clock.instant().coerceAtLeast(checkedAt)
            val generationIsCurrent = withContext(Dispatchers.IO) {
                runCatching {
                    val control = store.loadAutomaticIndexingControl()
                    val generation = automaticGeneration
                    val current = control.enabled && (generation == null || control.generation == generation)
                    if (current && generation != null && !terminalStatusSaved) {
                        store.saveAutomaticIndexingRuntime(
                            status = AutomaticIndexingRuntimeStatus(
                                lastCheckedAt = checkedAt,
                                lastStartedAt = startedAt,
                                lastCompletedAt = failedAt,
                                lastOutcome = AutomaticIndexingOutcome.FAILED,
                                lastFailureCode = IndexingFailureCode.INTERNAL_ERROR,
                            ),
                            expectedGeneration = generation,
                        )
                    }
                    current
                }.getOrDefault(true)
            }
            when {
                !generationIsCurrent -> Result.success()
                runAttemptCount + 1 < MAX_WORK_ATTEMPTS -> Result.retry()
                else -> Result.failure()
            }
        }
    }

    private fun Instant.coerceAtLeast(minimum: Instant): Instant {
        return if (isBefore(minimum)) minimum else this
    }

    private companion object {
        const val MAX_TASKS_PER_RUN = 32
        const val MAX_RETAINED_TERMINAL_TASKS = 100
        const val MAX_WORK_ATTEMPTS = 3
        val TERMINAL_RETENTION: Duration = Duration.ofDays(30)
    }
}
