package ai.grayin.core.ocr

internal enum class OcrLanguagePackWorkDecision {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal object OcrLanguagePackWorkPolicy {
    const val MAX_RETRY_ATTEMPT_INDEX = 2

    fun afterDownloadFailure(
        retryable: Boolean,
        runAttemptCount: Int,
    ): OcrLanguagePackWorkDecision {
        return if (retryable && runAttemptCount < MAX_RETRY_ATTEMPT_INDEX) {
            OcrLanguagePackWorkDecision.RETRY
        } else {
            OcrLanguagePackWorkDecision.FAILURE
        }
    }

    fun afterPublish(result: OcrLanguagePackPublishResult): OcrLanguagePackWorkDecision {
        return when (result) {
            OcrLanguagePackPublishResult.PUBLISHED,
            OcrLanguagePackPublishResult.STALE_GENERATION -> OcrLanguagePackWorkDecision.SUCCESS

            OcrLanguagePackPublishResult.FAILED -> OcrLanguagePackWorkDecision.FAILURE
        }
    }
}
