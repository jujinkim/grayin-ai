package ai.grayin.connectors.localfiles.document

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import ai.grayin.core.ocr.OcrLanguagePackCatalog
import ai.grayin.core.ocr.OcrLanguagePackInstallStore
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel.RIL_WORD
import io.legere.pdfiumandroid.LoggerInterface
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfPasswordException
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.util.Config
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface DocumentCancellationSignal {
    fun isCancelled(): Boolean
}

fun interface DocumentProcessTerminator {
    fun terminate()
}

class PdfiumTesseractDocumentProcessor(
    context: Context,
    private val scheduler: ScheduledExecutorService,
    private val installStore: OcrLanguagePackInstallStore = OcrLanguagePackInstallStore(context.applicationContext),
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    private val processTerminator: DocumentProcessTerminator = DocumentProcessTerminator {
        Process.killProcess(Process.myPid())
    },
    private val signalDeriver: DocumentSignalDeriver = DocumentSignalDeriver(),
) {
    private val appContext = context.applicationContext

    fun process(
        descriptor: ParcelFileDescriptor,
        cancellationSignal: DocumentCancellationSignal,
    ): DocumentProcessingResult {
        val startedAt = monotonicMillis()
        val documentFinished = AtomicBoolean(false)
        val hardDeadline = scheduler.schedule(
            {
                if (!documentFinished.get()) processTerminator.terminate()
            },
            PdfResourceLimits.DOCUMENT_TIMEOUT_MILLIS + PdfResourceLimits.OCR_HARD_TIMEOUT_GRACE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        try {
            checkCancellation(cancellationSignal)
            when (val validation = AndroidPdfDescriptorValidator.validate(descriptor)) {
                is PdfDescriptorValidation.Rejected -> return DocumentProcessingResult.failed(validation.issueCode)
                is PdfDescriptorValidation.Valid -> Unit
            }

            val pdfiumCore = PdfiumCore(appContext, Config(logger = SilentPdfiumLogger))
            val document = try {
                pdfiumCore.newDocument(descriptor)
            } catch (_: PdfPasswordException) {
                return DocumentProcessingResult.failed(DocumentRuntimeWire.ISSUE_PDF_PASSWORD_REQUIRED)
            } catch (_: Exception) {
                return DocumentProcessingResult.failed(DocumentRuntimeWire.ISSUE_PDF_MALFORMED)
            }
            return document.use {
                processDocument(
                    document = document,
                    startedAt = startedAt,
                    cancellationSignal = cancellationSignal,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: OutOfMemoryError) {
            return DocumentProcessingResult.failed(DocumentRuntimeWire.ISSUE_DOCUMENT_PROCESS_CRASHED)
        } catch (_: Exception) {
            return DocumentProcessingResult.failed(DocumentRuntimeWire.ISSUE_PDF_MALFORMED)
        } finally {
            documentFinished.set(true)
            hardDeadline.cancel(false)
            runCatching { descriptor.close() }
        }
    }

    private fun processDocument(
        document: PdfDocument,
        startedAt: Long,
        cancellationSignal: DocumentCancellationSignal,
    ): DocumentProcessingResult {
        val pageCount = document.getPageCount()
        if (pageCount <= 0) {
            return DocumentProcessingResult.failed(DocumentRuntimeWire.ISSUE_PDF_MALFORMED)
        }
        if (pageCount > PdfResourceLimits.MAX_PDF_PAGES) {
            return DocumentProcessingResult.failed(DocumentRuntimeWire.ISSUE_PDF_PAGE_LIMIT_EXCEEDED)
        }

        val installedLanguages = OcrLanguagePackCatalog.all()
            .filter(installStore::isVerifiedInstalled)
            .map { entry -> entry.id }
        val pageSignals = mutableListOf<DocumentPageSignal>()
        val issues = linkedSetOf<Int>()
        var processedPageCount = 0
        var ocrPageCount = 0

        for (pageIndex in 0 until pageCount) {
            checkCancellation(cancellationSignal)
            if (deadlineExceeded(startedAt)) {
                issues += DocumentRuntimeWire.ISSUE_DOCUMENT_PROCESS_TIMED_OUT
                break
            }
            processedPageCount += 1
            val pageResult = try {
                document.openPage(pageIndex).use { page ->
                    processPage(
                        page = page,
                        pageNumber = pageIndex + 1,
                        installedLanguages = installedLanguages,
                        canRunOcr = ocrPageCount < PdfResourceLimits.MAX_OCR_PAGES,
                        cancellationSignal = cancellationSignal,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                PageProcessingResult(issueCode = DocumentRuntimeWire.ISSUE_PDF_MALFORMED)
            }
            if (pageResult.usedOcr) ocrPageCount += 1
            pageResult.signal?.let(pageSignals::add)
            pageResult.issueCode?.let(issues::add)
            if (pageResult.truncated) issues += DocumentRuntimeWire.ISSUE_PARTIAL_DOCUMENT_INDEX
            if (deadlineExceeded(startedAt)) {
                issues += DocumentRuntimeWire.ISSUE_DOCUMENT_PROCESS_TIMED_OUT
                break
            }
        }

        if (pageSignals.isEmpty()) {
            issues += DocumentRuntimeWire.ISSUE_NO_EXTRACTABLE_TEXT
            val outcome = if (
                issues == setOf(DocumentRuntimeWire.ISSUE_NO_EXTRACTABLE_TEXT)
            ) {
                DocumentRuntimeWire.OUTCOME_EMPTY
            } else {
                DocumentRuntimeWire.OUTCOME_EMPTY
            }
            return DocumentProcessingResult(
                outcomeCode = outcome,
                totalPageCount = pageCount,
                processedPageCount = processedPageCount,
                pageSignals = emptyList(),
                issueCodes = issues.toIntArray(),
            )
        }

        if (deadlineExceeded(startedAt)) {
            issues += DocumentRuntimeWire.ISSUE_DOCUMENT_PROCESS_TIMED_OUT
        }
        val complete = issues.isEmpty() &&
            pageSignals.size == pageCount &&
            processedPageCount == pageCount
        if (!complete) issues += DocumentRuntimeWire.ISSUE_PARTIAL_DOCUMENT_INDEX
        return DocumentProcessingResult(
            outcomeCode = if (complete) {
                DocumentRuntimeWire.OUTCOME_COMPLETE
            } else {
                DocumentRuntimeWire.OUTCOME_PARTIAL
            },
            totalPageCount = pageCount,
            processedPageCount = processedPageCount,
            pageSignals = pageSignals,
            issueCodes = issues.toIntArray(),
        )
    }

    private fun processPage(
        page: PdfPage,
        pageNumber: Int,
        installedLanguages: List<String>,
        canRunOcr: Boolean,
        cancellationSignal: DocumentCancellationSignal,
    ): PageProcessingResult {
        val embedded = readEmbeddedText(page)
        if (signalDeriver.hasSufficientEmbeddedText(embedded.value)) {
            return PageProcessingResult(
                signal = signalDeriver.derive(
                    pageNumber = pageNumber,
                    extractionModeCode = DocumentRuntimeWire.EXTRACTION_EMBEDDED,
                    text = embedded.value,
                    confidenceCode = DocumentRuntimeWire.CONFIDENCE_MEDIUM,
                ),
                truncated = embedded.truncated,
            )
        }
        checkCancellation(cancellationSignal)
        if (!canRunOcr) {
            return PageProcessingResult(issueCode = DocumentRuntimeWire.ISSUE_OCR_PAGE_LIMIT_REACHED)
        }
        if (installedLanguages.isEmpty()) {
            return PageProcessingResult(issueCode = DocumentRuntimeWire.ISSUE_OCR_MODEL_UNAVAILABLE)
        }
        val renderTarget = PdfRenderTarget.fromPagePoints(
            widthPoints = page.getPageWidthPoint(),
            heightPoints = page.getPageHeightPoint(),
        ) ?: return PageProcessingResult(issueCode = DocumentRuntimeWire.ISSUE_PDF_PAGE_DIMENSIONS_UNSUPPORTED)

        val bitmap = Bitmap.createBitmap(renderTarget.width, renderTarget.height, Bitmap.Config.ARGB_8888)
        try {
            page.renderPageBitmap(
                bitmap = bitmap,
                startX = 0,
                startY = 0,
                drawSizeX = renderTarget.width,
                drawSizeY = renderTarget.height,
                renderAnnot = false,
            )
            checkCancellation(cancellationSignal)
            return recognizePage(
                bitmap = bitmap,
                pageNumber = pageNumber,
                installedLanguages = installedLanguages,
                cancellationSignal = cancellationSignal,
            )
        } finally {
            bitmap.eraseColor(0)
            bitmap.recycle()
        }
    }

    private fun readEmbeddedText(page: PdfPage): BoundedPageText {
        val builder = BoundedPageTextBuilder()
        page.openTextPage().use { textPage ->
            val count = textPage.textPageCountChars().coerceAtLeast(0)
            var offset = 0
            while (offset < count) {
                val requested = minOf(EMBEDDED_TEXT_CHUNK_CHARS, count - offset)
                val chunk = textPage.textPageGetText(offset, requested).orEmpty()
                if (!builder.append(chunk)) break
                offset += requested
            }
            if (offset < count) builder.markTruncated()
        }
        return builder.build()
    }

    private fun recognizePage(
        bitmap: Bitmap,
        pageNumber: Int,
        installedLanguages: List<String>,
        cancellationSignal: DocumentCancellationSignal,
    ): PageProcessingResult {
        val timedOut = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val softTimeout = scheduler.schedule(
            {
                if (!finished.get()) timedOut.set(true)
            },
            PdfResourceLimits.OCR_TIMEOUT_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        val hardTimeout = scheduler.schedule(
            {
                if (!finished.get()) processTerminator.terminate()
            },
            PdfResourceLimits.OCR_TIMEOUT_MILLIS + PdfResourceLimits.OCR_HARD_TIMEOUT_GRACE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        var tess: TessBaseAPI? = null
        try {
            val api = TessBaseAPI()
            tess = api
            val initialized = runCatching {
                api.init(
                    installStore.tesseractDataPath().absolutePath,
                    installedLanguages.joinToString("+"),
                )
            }.getOrDefault(false)
            if (!initialized) {
                return PageProcessingResult(
                    issueCode = DocumentRuntimeWire.ISSUE_OCR_MODEL_UNAVAILABLE,
                    usedOcr = true,
                )
            }
            if (timedOut.get()) return ocrTimedOutResult()
            api.setImage(bitmap)
            if (timedOut.get()) return ocrTimedOutResult()
            val recognized = recognizedWords(
                tess = api,
                bitmap = bitmap,
                timedOut = timedOut,
                cancellationSignal = cancellationSignal,
            )
            if (timedOut.get()) return ocrTimedOutResult()
            checkCancellation(cancellationSignal)

            val confidenceCode = when {
                recognized.meanConfidence >= HIGH_CONFIDENCE_PERCENT -> DocumentRuntimeWire.CONFIDENCE_HIGH
                recognized.meanConfidence >= MEDIUM_CONFIDENCE_PERCENT -> DocumentRuntimeWire.CONFIDENCE_MEDIUM
                else -> DocumentRuntimeWire.CONFIDENCE_LOW
            }
            val signal = signalDeriver.derive(
                pageNumber = pageNumber,
                extractionModeCode = DocumentRuntimeWire.EXTRACTION_OCR,
                text = recognized.text.value,
                confidenceCode = confidenceCode,
            )
            return PageProcessingResult(
                signal = signal,
                issueCode = if (signal == null) DocumentRuntimeWire.ISSUE_NO_EXTRACTABLE_TEXT else null,
                usedOcr = true,
                truncated = recognized.text.truncated,
            )
        } finally {
            finished.set(true)
            softTimeout.cancel(false)
            hardTimeout.cancel(false)
            runCatching { tess?.recycle() }
        }
    }

    private fun recognizedWords(
        tess: TessBaseAPI,
        bitmap: Bitmap,
        timedOut: AtomicBoolean,
        cancellationSignal: DocumentCancellationSignal,
    ): RecognizedPageText {
        val builder = BoundedPageTextBuilder()
        var confidenceSum = 0f
        var confidenceCount = 0
        val pageMeanConfidence = tess.meanConfidence().coerceIn(0, 100)
        if (timedOut.get()) {
            return RecognizedPageText(builder.build(), pageMeanConfidence.toFloat())
        }
        val iterator = tess.resultIterator
            ?: return RecognizedPageText(builder.build(), pageMeanConfidence.toFloat())
        try {
            iterator.begin()
            var visitedWords = 0
            do {
                checkCancellation(cancellationSignal)
                if (timedOut.get()) break
                if (visitedWords >= PdfResourceLimits.MAX_OCR_WORD_REGIONS) {
                    builder.markTruncated()
                    break
                }
                visitedWords += 1
                val region = iterator.getBoundingRect(RIL_WORD) ?: continue
                val left = region.left.coerceIn(0, bitmap.width)
                val top = region.top.coerceIn(0, bitmap.height)
                val right = region.right.coerceIn(left, bitmap.width)
                val bottom = region.bottom.coerceIn(top, bitmap.height)
                val width = right - left
                val height = bottom - top
                if (!OcrWordRegionPolicy.isAllowed(width, height)) {
                    builder.markTruncated()
                    continue
                }
                val word = iterator.getUTF8Text(RIL_WORD).orEmpty()
                if (timedOut.get()) break
                if (word.isNotBlank()) {
                    if (!builder.append(word)) break
                    if (!builder.append(" ")) break
                    confidenceSum += iterator.confidence(RIL_WORD).coerceIn(0f, 100f)
                    confidenceCount += 1
                }
            } while (iterator.next(RIL_WORD))
        } finally {
            iterator.delete()
        }
        return RecognizedPageText(
            text = builder.build(),
            meanConfidence = if (confidenceCount == 0) 0f else confidenceSum / confidenceCount,
        )
    }

    private fun ocrTimedOutResult(): PageProcessingResult = PageProcessingResult(
        issueCode = DocumentRuntimeWire.ISSUE_OCR_TIMED_OUT,
        usedOcr = true,
    )

    private fun deadlineExceeded(startedAt: Long): Boolean {
        return monotonicMillis() - startedAt > PdfResourceLimits.DOCUMENT_TIMEOUT_MILLIS
    }

    private fun checkCancellation(signal: DocumentCancellationSignal) {
        if (signal.isCancelled()) throw CancellationException("Document processing was cancelled.")
    }

    private data class PageProcessingResult(
        val signal: DocumentPageSignal? = null,
        val issueCode: Int? = null,
        val usedOcr: Boolean = false,
        val truncated: Boolean = false,
    )

    private data class RecognizedPageText(
        val text: BoundedPageText,
        val meanConfidence: Float,
    )

    private object SilentPdfiumLogger : LoggerInterface {
        override fun d(tag: String, message: String?) = Unit

        override fun e(tag: String, t: Throwable?, message: String?) = Unit
    }

    private companion object {
        const val EMBEDDED_TEXT_CHUNK_CHARS = 2_048
        const val HIGH_CONFIDENCE_PERCENT = 80f
        const val MEDIUM_CONFIDENCE_PERCENT = 50f
    }
}
