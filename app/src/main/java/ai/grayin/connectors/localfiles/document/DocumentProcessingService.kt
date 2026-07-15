package ai.grayin.connectors.localfiles.document

import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DocumentProcessingService : Service() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "grayin-document-worker").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "grayin-document-watchdog").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val activeRequest = AtomicReference<ActiveRequest?>(null)
    private lateinit var processor: PdfiumTesseractDocumentProcessor

    override fun onCreate() {
        super.onCreate()
        check(currentProcessName() == "$packageName:document") {
            "Document processing must run only in the private document process."
        }
        processor = PdfiumTesseractDocumentProcessor(this, scheduler)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        val unfinishedRequest = activeRequest.getAndSet(null)
        unfinishedRequest?.cancelled?.set(true)
        worker.shutdownNow()
        scheduler.shutdownNow()
        super.onDestroy()
        if (unfinishedRequest != null) Process.killProcess(Process.myPid())
    }

    private val binder = object : IDocumentProcessingService.Stub() {
        override fun process(
            requestId: String?,
            descriptor: ParcelFileDescriptor?,
            callback: IDocumentProcessingCallback?,
        ) {
            if (
                Binder.getCallingUid() != Process.myUid() ||
                requestId == null ||
                !requestId.matches(SAFE_REQUEST_ID) ||
                descriptor == null ||
                callback == null
            ) {
                runCatching { descriptor?.close() }
                return
            }
            val request = ActiveRequest(requestId)
            if (!activeRequest.compareAndSet(null, request)) {
                runCatching { descriptor.close() }
                sendResult(
                    callback = callback,
                    requestId = requestId,
                    result = DocumentProcessingResult.failed(
                        DocumentRuntimeWire.ISSUE_DOCUMENT_PROCESS_CRASHED,
                    ),
                )
                return
            }
            worker.execute {
                val result = try {
                    processor.process(
                        descriptor = descriptor,
                        cancellationSignal = DocumentCancellationSignal(request.cancelled::get),
                    )
                } catch (_: java.util.concurrent.CancellationException) {
                    null
                } catch (_: Exception) {
                    DocumentProcessingResult.failed(DocumentRuntimeWire.ISSUE_DOCUMENT_PROCESS_CRASHED)
                }
                activeRequest.compareAndSet(request, null)
                if (!request.cancelled.get() && result != null) {
                    val safeResult = if (DocumentProcessingResultValidator.isValid(result)) {
                        result
                    } else {
                        DocumentProcessingResult.failed(DocumentRuntimeWire.ISSUE_DOCUMENT_PROCESS_CRASHED)
                    }
                    sendResult(callback, requestId, safeResult)
                }
            }
        }

        override fun cancel(requestId: String?) {
            if (Binder.getCallingUid() != Process.myUid() || requestId == null) return
            val request = activeRequest.get() ?: return
            if (request.requestId != requestId) return
            request.cancelled.set(true)
        }
    }

    private fun sendResult(
        callback: IDocumentProcessingCallback,
        requestId: String,
        result: DocumentProcessingResult,
    ) {
        runCatching { callback.onComplete(requestId, result) }
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val pid = Process.myPid()
        return getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?.firstOrNull { process -> process.pid == pid }
            ?.processName
    }

    private data class ActiveRequest(
        val requestId: String,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
    )

    private companion object {
        val SAFE_REQUEST_ID = Regex("[a-f0-9-]{36}")
    }
}
