package ai.grayin.connectors.localfiles.document

import android.os.Parcelable
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorScanIssueCode
import kotlinx.parcelize.Parcelize

object DocumentRuntimeWire {
    const val PROTOCOL_VERSION = 1

    const val OUTCOME_COMPLETE = 1
    const val OUTCOME_PARTIAL = 2
    const val OUTCOME_EMPTY = 3
    const val OUTCOME_FAILED = 4

    const val EXTRACTION_EMBEDDED = 1
    const val EXTRACTION_OCR = 2

    const val CONFIDENCE_LOW = 1
    const val CONFIDENCE_MEDIUM = 2
    const val CONFIDENCE_HIGH = 3

    const val ISSUE_DOCUMENT_FILE_TOO_LARGE = 1
    const val ISSUE_DOCUMENT_SIZE_UNKNOWN = 2
    const val ISSUE_DOCUMENT_NOT_SEEKABLE = 3
    const val ISSUE_PDF_PAGE_LIMIT_EXCEEDED = 4
    const val ISSUE_PDF_PASSWORD_REQUIRED = 5
    const val ISSUE_PDF_MALFORMED = 6
    const val ISSUE_PDF_PAGE_DIMENSIONS_UNSUPPORTED = 7
    const val ISSUE_OCR_MODEL_UNAVAILABLE = 8
    const val ISSUE_OCR_PAGE_LIMIT_REACHED = 9
    const val ISSUE_OCR_TIMED_OUT = 10
    const val ISSUE_DOCUMENT_PROCESS_CRASHED = 11
    const val ISSUE_NO_EXTRACTABLE_TEXT = 12
    const val ISSUE_PARTIAL_DOCUMENT_INDEX = 13
    const val ISSUE_DOCUMENT_PROCESS_TIMED_OUT = 14

    val validOutcomeCodes = setOf(OUTCOME_COMPLETE, OUTCOME_PARTIAL, OUTCOME_EMPTY, OUTCOME_FAILED)
    val validExtractionCodes = setOf(EXTRACTION_EMBEDDED, EXTRACTION_OCR)
    val validConfidenceCodes = setOf(CONFIDENCE_LOW, CONFIDENCE_MEDIUM, CONFIDENCE_HIGH)
    val validIssueCodes = setOf(
        ISSUE_DOCUMENT_FILE_TOO_LARGE,
        ISSUE_DOCUMENT_SIZE_UNKNOWN,
        ISSUE_DOCUMENT_NOT_SEEKABLE,
        ISSUE_PDF_PAGE_LIMIT_EXCEEDED,
        ISSUE_PDF_PASSWORD_REQUIRED,
        ISSUE_PDF_MALFORMED,
        ISSUE_PDF_PAGE_DIMENSIONS_UNSUPPORTED,
        ISSUE_OCR_MODEL_UNAVAILABLE,
        ISSUE_OCR_PAGE_LIMIT_REACHED,
        ISSUE_OCR_TIMED_OUT,
        ISSUE_DOCUMENT_PROCESS_CRASHED,
        ISSUE_NO_EXTRACTABLE_TEXT,
        ISSUE_PARTIAL_DOCUMENT_INDEX,
        ISSUE_DOCUMENT_PROCESS_TIMED_OUT,
    )

    fun issueCode(wireCode: Int): ConnectorScanIssueCode? = when (wireCode) {
        ISSUE_DOCUMENT_FILE_TOO_LARGE -> ConnectorScanIssueCode.DOCUMENT_FILE_TOO_LARGE
        ISSUE_DOCUMENT_SIZE_UNKNOWN -> ConnectorScanIssueCode.DOCUMENT_SIZE_UNKNOWN
        ISSUE_DOCUMENT_NOT_SEEKABLE -> ConnectorScanIssueCode.DOCUMENT_NOT_SEEKABLE
        ISSUE_PDF_PAGE_LIMIT_EXCEEDED -> ConnectorScanIssueCode.PDF_PAGE_LIMIT_EXCEEDED
        ISSUE_PDF_PASSWORD_REQUIRED -> ConnectorScanIssueCode.PDF_PASSWORD_REQUIRED
        ISSUE_PDF_MALFORMED -> ConnectorScanIssueCode.PDF_MALFORMED
        ISSUE_PDF_PAGE_DIMENSIONS_UNSUPPORTED -> ConnectorScanIssueCode.PDF_PAGE_DIMENSIONS_UNSUPPORTED
        ISSUE_OCR_MODEL_UNAVAILABLE -> ConnectorScanIssueCode.OCR_MODEL_UNAVAILABLE
        ISSUE_OCR_PAGE_LIMIT_REACHED -> ConnectorScanIssueCode.OCR_PAGE_LIMIT_REACHED
        ISSUE_OCR_TIMED_OUT -> ConnectorScanIssueCode.OCR_TIMED_OUT
        ISSUE_DOCUMENT_PROCESS_CRASHED -> ConnectorScanIssueCode.DOCUMENT_PROCESS_CRASHED
        ISSUE_NO_EXTRACTABLE_TEXT -> ConnectorScanIssueCode.NO_EXTRACTABLE_TEXT
        ISSUE_PARTIAL_DOCUMENT_INDEX -> ConnectorScanIssueCode.PARTIAL_DOCUMENT_INDEX
        ISSUE_DOCUMENT_PROCESS_TIMED_OUT -> ConnectorScanIssueCode.DOCUMENT_PROCESS_TIMED_OUT
        else -> null
    }

    fun confidence(wireCode: Int): ConfidenceLevel = when (wireCode) {
        CONFIDENCE_HIGH -> ConfidenceLevel.HIGH
        CONFIDENCE_MEDIUM -> ConfidenceLevel.MEDIUM
        else -> ConfidenceLevel.LOW
    }
}

@Parcelize
data class DocumentPageSignal(
    val pageNumber: Int,
    val extractionModeCode: Int,
    val nonBlankLineCount: Int,
    val keywordSignals: List<String>,
    val confidenceCode: Int,
) : Parcelable

@Parcelize
data class DocumentProcessingResult(
    val protocolVersion: Int = DocumentRuntimeWire.PROTOCOL_VERSION,
    val outcomeCode: Int,
    val totalPageCount: Int,
    val processedPageCount: Int,
    val pageSignals: List<DocumentPageSignal>,
    val issueCodes: IntArray,
) : Parcelable {
    companion object {
        fun failed(issueCode: Int): DocumentProcessingResult = DocumentProcessingResult(
            outcomeCode = DocumentRuntimeWire.OUTCOME_FAILED,
            totalPageCount = 0,
            processedPageCount = 0,
            pageSignals = emptyList(),
            issueCodes = intArrayOf(issueCode),
        )
    }
}

object DocumentProcessingResultValidator {
    fun isValid(result: DocumentProcessingResult): Boolean {
        if (result.protocolVersion != DocumentRuntimeWire.PROTOCOL_VERSION) return false
        if (result.outcomeCode !in DocumentRuntimeWire.validOutcomeCodes) return false
        if (result.totalPageCount !in 0..PdfResourceLimits.MAX_PDF_PAGES) return false
        if (result.processedPageCount !in 0..result.totalPageCount) return false
        if (result.pageSignals.size > PdfResourceLimits.MAX_PDF_PAGES) return false
        if (result.pageSignals.size > result.processedPageCount) return false
        if (result.pageSignals.any { it.pageNumber > result.processedPageCount }) return false
        if (result.issueCodes.size > MAX_ISSUE_CODES) return false
        if (result.issueCodes.any { it !in DocumentRuntimeWire.validIssueCodes }) return false
        if (result.issueCodes.distinct().size != result.issueCodes.size) return false
        if (result.pageSignals.map { it.pageNumber }.distinct().size != result.pageSignals.size) return false
        if (result.pageSignals.any { !isValid(it, result.totalPageCount) }) return false
        if (estimatedPayloadBytes(result) > MAX_RESULT_PAYLOAD_BYTES) return false

        return when (result.outcomeCode) {
            DocumentRuntimeWire.OUTCOME_COMPLETE -> {
                result.pageSignals.isNotEmpty() &&
                    result.pageSignals.size == result.totalPageCount &&
                    result.processedPageCount == result.totalPageCount &&
                    result.issueCodes.isEmpty()
            }

            DocumentRuntimeWire.OUTCOME_PARTIAL -> {
                result.pageSignals.isNotEmpty() &&
                    result.processedPageCount > 0 &&
                    DocumentRuntimeWire.ISSUE_PARTIAL_DOCUMENT_INDEX in result.issueCodes
            }

            DocumentRuntimeWire.OUTCOME_EMPTY -> {
                result.pageSignals.isEmpty() &&
                    DocumentRuntimeWire.ISSUE_NO_EXTRACTABLE_TEXT in result.issueCodes
            }

            DocumentRuntimeWire.OUTCOME_FAILED -> {
                result.totalPageCount == 0 &&
                    result.processedPageCount == 0 &&
                    result.pageSignals.isEmpty() &&
                    result.issueCodes.isNotEmpty()
            }
            else -> false
        }
    }

    private fun isValid(signal: DocumentPageSignal, totalPageCount: Int): Boolean {
        return signal.pageNumber in 1..totalPageCount &&
            signal.extractionModeCode in DocumentRuntimeWire.validExtractionCodes &&
            signal.nonBlankLineCount in 0..PdfResourceLimits.MAX_TRANSIENT_TEXT_BYTES &&
            signal.keywordSignals.size <= DocumentSignalDeriver.MAX_KEYWORDS &&
            signal.keywordSignals.distinct().size == signal.keywordSignals.size &&
            signal.keywordSignals.all(DocumentSignalDeriver::isSafeKeyword) &&
            signal.confidenceCode in DocumentRuntimeWire.validConfidenceCodes
    }

    private fun estimatedPayloadBytes(result: DocumentProcessingResult): Int {
        val keywordBytes = result.pageSignals.sumOf { signal ->
            signal.keywordSignals.sumOf { keyword -> keyword.toByteArray(Charsets.UTF_8).size }
        }
        return FIXED_RESULT_BYTES +
            result.pageSignals.size * FIXED_PAGE_SIGNAL_BYTES +
            result.issueCodes.size * Int.SIZE_BYTES +
            keywordBytes
    }

    private const val MAX_ISSUE_CODES = 16
    private const val MAX_RESULT_PAYLOAD_BYTES = 64 * 1024
    private const val FIXED_RESULT_BYTES = 64
    private const val FIXED_PAGE_SIGNAL_BYTES = 64
}
