package ai.grayin.connectors.localfiles

import ai.grayin.connectors.localfiles.document.DocumentPageSignal
import ai.grayin.connectors.localfiles.document.DocumentProcessingResult
import ai.grayin.connectors.localfiles.document.DocumentRuntimeWire
import ai.grayin.connectors.localfiles.document.PdfResourceLimits
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.SourceKind
import ai.grayin.core.security.SourceIdentityHasher
import java.security.MessageDigest
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPdfMemoryExtractorTest {
    private val hasher = SourceIdentityHasher { namespace, value ->
        MessageDigest.getInstance("SHA-256")
            .digest("$namespace\u0000$value".toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }
    private val extractor = LocalPdfMemoryExtractor(hasher)
    private val observedAt = Instant.parse("2026-07-15T02:00:00Z")
    private val documentHmac = "d".repeat(64)

    @Test
    fun mapsSortedPagesToHmacOnlyOneToOneGraph() {
        val result = extractor.extract(
            documentIdentityHmac = documentHmac,
            result = completeResult(
                pageSignal(2, DocumentRuntimeWire.EXTRACTION_OCR, DocumentRuntimeWire.CONFIDENCE_MEDIUM),
                pageSignal(1, DocumentRuntimeWire.EXTRACTION_EMBEDDED, DocumentRuntimeWire.CONFIDENCE_HIGH),
            ),
            observedAt = observedAt,
            remainingOutputCapacity = PdfResourceLimits.MAX_DERIVED_PAGE_SIGNALS_PER_SCAN,
        )

        assertEquals(listOf("PDF page 1", "PDF page 2"), result.citations.map { it.label })
        assertEquals(listOf(ConfidenceLevel.HIGH, ConfidenceLevel.MEDIUM), result.citations.map { it.confidence })
        assertEquals("embedded-text", result.derivedEvents[0].labels.last())
        assertEquals("ocr", result.derivedEvents[1].labels.last())
        assertEquals(2, result.sourceReferences.size)
        result.sourceReferences.forEach { source ->
            assertEquals(SourceKind.PDF_PAGE, source.sourceKind)
            assertTrue(source.hmacHash?.matches(Regex("[a-f0-9]{64}")) == true)
            assertNull(source.localPointer)
            assertNull(source.externalIdHash)
            assertNull(source.sourceAppIdentifier)
            assertFalse(source.id.contains(documentHmac))
        }
        result.derivedEvents.zip(result.citations).zip(result.sourceReferences).forEach { pair ->
            val event = pair.first.first
            val citation = pair.first.second
            val source = pair.second
            assertEquals(listOf(source.id), event.sourceReferenceIds)
            assertEquals(listOf(citation.id), event.citationIds)
            assertEquals(event.id, citation.derivedMemoryEventId)
            assertEquals(source.id, citation.sourceReferenceId)
        }
    }

    @Test
    fun preservesValidatedPartialIssuesAndSuccessfulPages() {
        val runtimeResult = DocumentProcessingResult(
            outcomeCode = DocumentRuntimeWire.OUTCOME_PARTIAL,
            totalPageCount = 3,
            processedPageCount = 2,
            pageSignals = listOf(pageSignal(1), pageSignal(2)),
            issueCodes = intArrayOf(
                DocumentRuntimeWire.ISSUE_PARTIAL_DOCUMENT_INDEX,
                DocumentRuntimeWire.ISSUE_OCR_MODEL_UNAVAILABLE,
            ),
        )

        val result = extractor.extract(documentHmac, runtimeResult, observedAt, 128)

        assertEquals(2, result.derivedEvents.size)
        assertEquals(
            setOf(
                ConnectorScanIssueCode.PARTIAL_DOCUMENT_INDEX,
                ConnectorScanIssueCode.OCR_MODEL_UNAVAILABLE,
            ),
            result.issueCodes,
        )
    }

    @Test
    fun invalidRuntimeResultFailsClosed() {
        val invalid = completeResult(pageSignal(1)).copy(protocolVersion = 999)

        val result = extractor.extract(documentHmac, invalid, observedAt, 128)

        assertTrue(result.sourceReferences.isEmpty())
        assertEquals(setOf(ConnectorScanIssueCode.DOCUMENT_PROCESS_CRASHED), result.issueCodes)
    }

    @Test
    fun aggregateCapacityProducesExplicitPartialResult() {
        val result = extractor.extract(
            documentHmac,
            completeResult(pageSignal(1), pageSignal(2)),
            observedAt,
            remainingOutputCapacity = 1,
        )

        assertEquals(listOf("PDF page 1"), result.citations.map { it.label })
        assertTrue(ConnectorScanIssueCode.DOCUMENT_DERIVED_OUTPUT_LIMIT_REACHED in result.issueCodes)
        assertTrue(ConnectorScanIssueCode.PARTIAL_DOCUMENT_INDEX in result.issueCodes)
    }

    @Test
    fun failedRuntimeResultMapsIssueWithoutGraph() {
        val result = extractor.extract(
            documentHmac,
            DocumentProcessingResult.failed(DocumentRuntimeWire.ISSUE_PDF_MALFORMED),
            observedAt,
            128,
        )

        assertTrue(result.derivedEvents.isEmpty())
        assertEquals(setOf(ConnectorScanIssueCode.PDF_MALFORMED), result.issueCodes)
    }

    private fun completeResult(vararg signals: DocumentPageSignal): DocumentProcessingResult {
        return DocumentProcessingResult(
            outcomeCode = DocumentRuntimeWire.OUTCOME_COMPLETE,
            totalPageCount = signals.size,
            processedPageCount = signals.size,
            pageSignals = signals.toList(),
            issueCodes = intArrayOf(),
        )
    }

    private fun pageSignal(
        pageNumber: Int,
        extractionMode: Int = DocumentRuntimeWire.EXTRACTION_EMBEDDED,
        confidence: Int = DocumentRuntimeWire.CONFIDENCE_MEDIUM,
    ): DocumentPageSignal {
        return DocumentPageSignal(
            pageNumber = pageNumber,
            extractionModeCode = extractionMode,
            nonBlankLineCount = 4,
            keywordSignals = listOf("bounded", "signal"),
            confidenceCode = confidence,
        )
    }
}
