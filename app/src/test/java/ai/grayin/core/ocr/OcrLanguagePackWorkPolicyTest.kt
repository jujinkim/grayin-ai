package ai.grayin.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrLanguagePackWorkPolicyTest {
    @Test
    fun retryableFailuresRetryOnlyBeforeTheFinalAttempt() {
        assertEquals(
            OcrLanguagePackWorkDecision.RETRY,
            OcrLanguagePackWorkPolicy.afterDownloadFailure(retryable = true, runAttemptCount = 0),
        )
        assertEquals(
            OcrLanguagePackWorkDecision.RETRY,
            OcrLanguagePackWorkPolicy.afterDownloadFailure(retryable = true, runAttemptCount = 1),
        )
        assertEquals(
            OcrLanguagePackWorkDecision.FAILURE,
            OcrLanguagePackWorkPolicy.afterDownloadFailure(retryable = true, runAttemptCount = 2),
        )
        assertEquals(
            OcrLanguagePackWorkDecision.FAILURE,
            OcrLanguagePackWorkPolicy.afterDownloadFailure(retryable = false, runAttemptCount = 0),
        )
    }

    @Test
    fun publishDecisionTreatsOnlyPublishedAndStaleAsSuccess() {
        assertEquals(
            OcrLanguagePackWorkDecision.SUCCESS,
            OcrLanguagePackWorkPolicy.afterPublish(OcrLanguagePackPublishResult.PUBLISHED),
        )
        assertEquals(
            OcrLanguagePackWorkDecision.SUCCESS,
            OcrLanguagePackWorkPolicy.afterPublish(OcrLanguagePackPublishResult.STALE_GENERATION),
        )
        assertEquals(
            OcrLanguagePackWorkDecision.FAILURE,
            OcrLanguagePackWorkPolicy.afterPublish(OcrLanguagePackPublishResult.FAILED),
        )
    }
}
