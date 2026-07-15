package ai.grayin.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadWorkPolicyTest {
    @Test
    fun retryableFailuresRetryOnlyBeforeTheFinalAttempt() {
        assertEquals(
            ModelDownloadWorkDecision.RETRY,
            ModelDownloadWorkPolicy.afterDownloadFailure(retryable = true, runAttemptCount = 0),
        )
        assertEquals(
            ModelDownloadWorkDecision.RETRY,
            ModelDownloadWorkPolicy.afterDownloadFailure(retryable = true, runAttemptCount = 1),
        )
        assertEquals(
            ModelDownloadWorkDecision.FAILURE,
            ModelDownloadWorkPolicy.afterDownloadFailure(retryable = true, runAttemptCount = 2),
        )
        assertEquals(
            ModelDownloadWorkDecision.FAILURE,
            ModelDownloadWorkPolicy.afterDownloadFailure(retryable = false, runAttemptCount = 0),
        )
    }

    @Test
    fun publishDecisionTreatsPublishedAndStaleAsSuccess() {
        assertEquals(
            ModelDownloadWorkDecision.SUCCESS,
            ModelDownloadWorkPolicy.afterPublish(ModelPublishResult.PUBLISHED),
        )
        assertEquals(
            ModelDownloadWorkDecision.SUCCESS,
            ModelDownloadWorkPolicy.afterPublish(ModelPublishResult.STALE_GENERATION),
        )
        assertEquals(
            ModelDownloadWorkDecision.FAILURE,
            ModelDownloadWorkPolicy.afterPublish(ModelPublishResult.FAILED),
        )
    }
}
