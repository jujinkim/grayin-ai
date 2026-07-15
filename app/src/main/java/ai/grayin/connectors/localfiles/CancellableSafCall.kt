package ai.grayin.connectors.localfiles

import android.os.CancellationSignal
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

internal suspend fun <T> runCancellableSafCall(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    discardResult: (T) -> Unit = {},
    block: (CancellationSignal) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val cancellationSignal = CancellationSignal()
    continuation.invokeOnCancellation { cancellationSignal.cancel() }
    dispatcher.dispatch(
        EmptyCoroutineContext,
        Runnable {
            if (!continuation.isActive) return@Runnable
            try {
                val result = block(cancellationSignal)
                if (!continuation.isActive) {
                    discardResult(result)
                    return@Runnable
                }
                continuation.resume(
                    result,
                    onCancellation = { _, canceledResult, _ -> discardResult(canceledResult) },
                )
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        },
    )
}
