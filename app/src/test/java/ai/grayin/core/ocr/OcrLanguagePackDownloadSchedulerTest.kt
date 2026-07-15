package ai.grayin.core.ocr

import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLanguagePackDownloadSchedulerTest {
    @Test
    fun downloadConstraintsRequireUnmeteredNetworkAndAvailableStorage() {
        val constraints = OcrLanguagePackDownloadScheduler.downloadConstraints()

        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresStorageNotLow())
    }

    @Test
    fun uniqueWorkAndTagNamesContainOnlyTheClosedPackId() {
        assertEquals(
            "grayin-ocr-language-pack-kor",
            OcrLanguagePackDownloadScheduler.uniqueNameFor("kor"),
        )
        assertEquals(
            "ocr-language-pack-kor",
            OcrLanguagePackDownloadScheduler.tagFor("kor"),
        )
        assertEquals(30L, OcrLanguagePackDownloadScheduler.MIN_BACKOFF.seconds)
    }

    @Test
    fun workInputContainsOnlyPackIdAndGeneration() {
        val input = OcrLanguagePackDownloadScheduler.inputData("jpn", 7L)

        assertEquals(
            setOf(
                OcrLanguagePackDownloadWorker.KEY_PACK_ID,
                OcrLanguagePackDownloadWorker.KEY_GENERATION,
            ),
            input.keyValueMap.keys,
        )
        assertEquals("jpn", input.getString(OcrLanguagePackDownloadWorker.KEY_PACK_ID))
        assertEquals(7L, input.getLong(OcrLanguagePackDownloadWorker.KEY_GENERATION, -1L))
    }

    @Test
    fun replacementPolicyAndRequestMatchGenerationFencing() {
        val request = OcrLanguagePackDownloadScheduler.requestFor("kor", 9L)

        assertEquals(ExistingWorkPolicy.REPLACE, OcrLanguagePackDownloadScheduler.EXISTING_WORK_POLICY)
        assertTrue(request.tags.contains("ocr-language-pack-kor"))
        assertEquals(
            "kor",
            request.workSpec.input.getString(OcrLanguagePackDownloadWorker.KEY_PACK_ID),
        )
        assertEquals(
            9L,
            request.workSpec.input.getLong(OcrLanguagePackDownloadWorker.KEY_GENERATION, -1L),
        )
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresStorageNotLow())
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
    }

    @Test
    fun reconciliationRecognizesOnlyWorkManagerActiveStates() {
        assertTrue(
            OcrLanguagePackDownloadScheduler.hasActiveWork(
                listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.SUCCEEDED),
            ),
        )
        assertTrue(OcrLanguagePackDownloadScheduler.hasActiveWork(listOf(WorkInfo.State.RUNNING)))
        assertTrue(OcrLanguagePackDownloadScheduler.hasActiveWork(listOf(WorkInfo.State.BLOCKED)))
        assertFalse(
            OcrLanguagePackDownloadScheduler.hasActiveWork(
                listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED),
            ),
        )
    }
}
