package ai.grayin.core.ai

internal enum class ModelDownloadWorkDecision {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal object ModelDownloadWorkPolicy {
    const val MAX_RETRY_ATTEMPT_INDEX = 2

    fun afterDownloadFailure(
        retryable: Boolean,
        runAttemptCount: Int,
    ): ModelDownloadWorkDecision {
        return if (retryable && runAttemptCount < MAX_RETRY_ATTEMPT_INDEX) {
            ModelDownloadWorkDecision.RETRY
        } else {
            ModelDownloadWorkDecision.FAILURE
        }
    }

    fun afterPublish(result: ModelPublishResult): ModelDownloadWorkDecision {
        return when (result) {
            ModelPublishResult.PUBLISHED,
            ModelPublishResult.STALE_GENERATION -> ModelDownloadWorkDecision.SUCCESS

            ModelPublishResult.FAILED -> ModelDownloadWorkDecision.FAILURE
        }
    }
}
