package ai.grayin.core.ai

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadSchedulerTest {
    @Test
    fun downloadConstraintsRequireUnmeteredNetworkAndAvailableStorage() {
        val constraints = ModelDownloadScheduler.downloadConstraints()

        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresStorageNotLow())
    }

    @Test
    fun uniqueWorkAndTagNamesContainOnlyTheClosedModelId() {
        assertEquals(
            "grayin-model-download-gemma-4-E2B-it",
            ModelDownloadScheduler.uniqueNameFor("gemma-4-E2B-it"),
        )
        assertEquals(
            "model-download-gemma-4-E2B-it",
            ModelDownloadScheduler.tagFor("gemma-4-E2B-it"),
        )
        assertEquals(30L, ModelDownloadScheduler.MIN_BACKOFF.seconds)
    }

    @Test
    fun workInputContainsOnlyModelIdAndGeneration() {
        val input = ModelDownloadScheduler.inputData("gemma-4-E4B-it", 7L)

        assertEquals(
            setOf(
                ModelDownloadWorker.KEY_MODEL_ID,
                ModelDownloadWorker.KEY_GENERATION,
            ),
            input.keyValueMap.keys,
        )
        assertEquals("gemma-4-E4B-it", input.getString(ModelDownloadWorker.KEY_MODEL_ID))
        assertEquals(7L, input.getLong(ModelDownloadWorker.KEY_GENERATION, -1L))
    }

    @Test
    fun replacementPolicyAndRequestMatchGenerationFencing() {
        val request = ModelDownloadScheduler.requestFor("gemma-4-E2B-it", 9L)

        assertEquals(ExistingWorkPolicy.REPLACE, ModelDownloadScheduler.EXISTING_WORK_POLICY)
        assertTrue(request.tags.contains("model-download-gemma-4-E2B-it"))
        assertEquals(
            "gemma-4-E2B-it",
            request.workSpec.input.getString(ModelDownloadWorker.KEY_MODEL_ID),
        )
        assertEquals(
            9L,
            request.workSpec.input.getLong(ModelDownloadWorker.KEY_GENERATION, -1L),
        )
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresStorageNotLow())
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
    }

    @Test
    fun reconciliationRecognizesOnlyWorkManagerActiveStates() {
        assertTrue(
            ModelDownloadScheduler.hasActiveWork(
                listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.SUCCEEDED),
            ),
        )
        assertTrue(ModelDownloadScheduler.hasActiveWork(listOf(WorkInfo.State.RUNNING)))
        assertTrue(ModelDownloadScheduler.hasActiveWork(listOf(WorkInfo.State.BLOCKED)))
        assertFalse(
            ModelDownloadScheduler.hasActiveWork(
                listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED),
            ),
        )
    }
}
