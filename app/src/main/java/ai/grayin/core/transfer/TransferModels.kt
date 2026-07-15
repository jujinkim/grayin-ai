package ai.grayin.core.transfer

import ai.grayin.core.store.LocalMemorySnapshot
import java.time.Instant

data class TransferProducerMetadata(
    val applicationId: String,
    val versionCode: Long,
    val storeSchemaVersion: Int,
)

data class TransferPayload(
    val createdAt: Instant,
    val producer: TransferProducerMetadata,
    val snapshot: LocalMemorySnapshot,
)

enum class TransferFailureCode {
    CANCELLED,
    SOURCE_IO_FAILED,
    DESTINATION_IO_FAILED,
    PASSWORD_POLICY_FAILED,
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    TOO_LARGE,
    AUTHENTICATION_FAILED,
    INVALID_PAYLOAD,
    CONSENT_RESET_FAILED,
    STORE_TRANSACTION_FAILED,
    CRYPTO_UNAVAILABLE,
}

data class TransferFailure(
    val code: TransferFailureCode,
)

sealed interface TransferResult<out T> {
    data class Success<T>(
        val value: T,
    ) : TransferResult<T>

    data class Failure(
        val failure: TransferFailure,
    ) : TransferResult<Nothing>
}

fun <T> TransferResult<T>.getOrNull(): T? {
    return (this as? TransferResult.Success)?.value
}

internal fun transferFailure(code: TransferFailureCode): TransferResult.Failure {
    return TransferResult.Failure(TransferFailure(code))
}
