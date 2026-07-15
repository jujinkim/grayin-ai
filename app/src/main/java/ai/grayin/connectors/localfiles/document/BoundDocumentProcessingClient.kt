package ai.grayin.connectors.localfiles.document

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

fun interface DocumentProcessingClient {
    suspend fun process(descriptor: ParcelFileDescriptor): DocumentProcessingResult
}

class BoundDocumentProcessingClient(context: Context) : DocumentProcessingClient {
    private val appContext = context.applicationContext

    override suspend fun process(descriptor: ParcelFileDescriptor): DocumentProcessingResult {
        return try {
            withTimeout(CLIENT_TIMEOUT_MILLIS) {
                processBound(descriptor)
            }
        } catch (_: TimeoutCancellationException) {
            DocumentProcessingResult.failed(DocumentRuntimeWire.ISSUE_DOCUMENT_PROCESS_TIMED_OUT)
        }
    }

    private suspend fun processBound(descriptor: ParcelFileDescriptor): DocumentProcessingResult =
        suspendCancellableCoroutine { continuation ->
            val session = BindingSession(
                appContext = appContext,
                descriptor = descriptor,
                requestId = UUID.randomUUID().toString(),
                continuation = continuation,
            )
            continuation.invokeOnCancellation { session.cancel() }
            session.start()
        }

    private class BindingSession(
        private val appContext: Context,
        private val descriptor: ParcelFileDescriptor,
        private val requestId: String,
        private val continuation: CancellableContinuation<DocumentProcessingResult>,
    ) : ServiceConnection {
        private val lock = Any()
        private val finished = AtomicBoolean(false)
        private val descriptorClosed = AtomicBoolean(false)

        @GuardedBy("lock")
        private var bound = false

        @GuardedBy("lock")
        private var requestDispatched = false

        @GuardedBy("lock")
        private var cancelSent = false

        @GuardedBy("lock")
        private var remote: IDocumentProcessingService? = null

        @GuardedBy("lock")
        private var remoteBinder: IBinder? = null

        private val deathRecipient = IBinder.DeathRecipient { finish(crashResult()) }

        private val callback = object : IDocumentProcessingCallback.Stub() {
            override fun onComplete(callbackRequestId: String?, result: DocumentProcessingResult?) {
                if (callbackRequestId != requestId || result == null) return
                finish(
                    if (DocumentProcessingResultValidator.isValid(result)) result else crashResult(),
                )
            }
        }

        fun start() {
            var shouldUnbind = false
            var bindFailed = false
            synchronized(lock) {
                if (finished.get()) {
                    closeDescriptor()
                    return
                }
                val didBind = runCatching {
                    appContext.bindService(
                        Intent(appContext, DocumentProcessingService::class.java),
                        this,
                        Context.BIND_AUTO_CREATE,
                    )
                }.getOrDefault(false)
                if (didBind) {
                    bound = true
                    if (finished.get()) {
                        sendCancelLocked()
                        unlinkDeathLocked()
                        bound = false
                        shouldUnbind = true
                    }
                } else {
                    bindFailed = true
                }
            }
            if (shouldUnbind) unbind()
            if (bindFailed) finish(crashResult())
        }

        fun cancel() {
            if (!finished.compareAndSet(false, true)) return
            val shouldUnbind = synchronized(lock) {
                sendCancelLocked()
                unlinkDeathLocked()
                closeDescriptor()
                takeBoundLocked()
            }
            if (shouldUnbind) unbind()
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            var shouldUnbind = false
            var failed = false
            synchronized(lock) {
                if (finished.get()) {
                    closeDescriptor()
                    shouldUnbind = takeBoundLocked()
                } else if (service == null) {
                    failed = true
                } else {
                    remoteBinder = service
                    remote = IDocumentProcessingService.Stub.asInterface(service)
                    try {
                        service.linkToDeath(deathRecipient, 0)
                        requestDispatched = true
                        remote?.process(requestId, descriptor, callback)
                    } catch (_: DeadObjectException) {
                        failed = true
                    } catch (_: RemoteException) {
                        failed = true
                    } finally {
                        closeDescriptor()
                    }
                    if (finished.get()) {
                        sendCancelLocked()
                        unlinkDeathLocked()
                        shouldUnbind = takeBoundLocked()
                    }
                }
            }
            if (shouldUnbind) unbind()
            if (failed) finish(crashResult())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            finish(crashResult())
        }

        override fun onBindingDied(name: ComponentName?) {
            finish(crashResult())
        }

        override fun onNullBinding(name: ComponentName?) {
            finish(crashResult())
        }

        private fun finish(result: DocumentProcessingResult) {
            if (!finished.compareAndSet(false, true)) return
            val shouldUnbind = synchronized(lock) {
                sendCancelLocked()
                unlinkDeathLocked()
                closeDescriptor()
                takeBoundLocked()
            }
            if (shouldUnbind) unbind()
            if (continuation.isActive) continuation.resume(result)
        }

        @GuardedBy("lock")
        private fun sendCancelLocked() {
            if (!requestDispatched || cancelSent) return
            cancelSent = true
            runCatching { remote?.cancel(requestId) }
        }

        @GuardedBy("lock")
        private fun unlinkDeathLocked() {
            val binder = remoteBinder ?: return
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            remoteBinder = null
            remote = null
        }

        @GuardedBy("lock")
        private fun takeBoundLocked(): Boolean {
            if (!bound) return false
            bound = false
            return true
        }

        private fun closeDescriptor() {
            if (descriptorClosed.compareAndSet(false, true)) runCatching { descriptor.close() }
        }

        private fun unbind() {
            runCatching { appContext.unbindService(this) }
        }

        private fun crashResult(): DocumentProcessingResult {
            return DocumentProcessingResult.failed(DocumentRuntimeWire.ISSUE_DOCUMENT_PROCESS_CRASHED)
        }
    }

    private annotation class GuardedBy(val value: String)

    private companion object {
        const val CLIENT_TIMEOUT_MILLIS = PdfResourceLimits.DOCUMENT_TIMEOUT_MILLIS +
            PdfResourceLimits.OCR_HARD_TIMEOUT_GRACE_MILLIS +
            5_000L
    }
}
