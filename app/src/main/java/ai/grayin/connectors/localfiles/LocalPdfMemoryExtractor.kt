package ai.grayin.connectors.localfiles

import ai.grayin.connectors.localfiles.document.DocumentProcessingResult
import ai.grayin.connectors.localfiles.document.DocumentProcessingResultValidator
import ai.grayin.connectors.localfiles.document.DocumentRuntimeWire
import ai.grayin.connectors.localfiles.document.PdfResourceLimits
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import ai.grayin.core.security.SourceIdentityHasher
import java.time.Instant

data class LocalDocumentExtractionResult(
    val sourceReferences: List<SourceReference> = emptyList(),
    val derivedEvents: List<DerivedMemoryEvent> = emptyList(),
    val citations: List<MemoryCitation> = emptyList(),
    val issueCodes: Set<ConnectorScanIssueCode> = emptySet(),
) {
    init {
        require(sourceReferences.size == derivedEvents.size && derivedEvents.size == citations.size) {
            "A local document extraction must produce a one-to-one derived graph."
        }
    }
}

class LocalPdfMemoryExtractor(
    private val identityHasher: SourceIdentityHasher,
) {
    fun extract(
        documentIdentityHmac: String,
        result: DocumentProcessingResult,
        observedAt: Instant,
        remainingOutputCapacity: Int,
    ): LocalDocumentExtractionResult {
        require(documentIdentityHmac.matches(SAFE_HMAC)) {
            "Local PDF identity must be an HMAC-SHA256 value."
        }
        require(remainingOutputCapacity in 0..PdfResourceLimits.MAX_DERIVED_PAGE_SIGNALS_PER_SCAN) {
            "Remaining local document capacity is invalid."
        }
        if (!DocumentProcessingResultValidator.isValid(result)) {
            return LocalDocumentExtractionResult(
                issueCodes = setOf(ConnectorScanIssueCode.DOCUMENT_PROCESS_CRASHED),
            )
        }

        val sortedSignals = result.pageSignals.sortedBy { signal -> signal.pageNumber }
        val acceptedSignals = sortedSignals.take(remainingOutputCapacity)
        val issues = result.issueCodes.asSequence()
            .mapNotNull(DocumentRuntimeWire::issueCode)
            .toMutableSet()
        if (acceptedSignals.size < sortedSignals.size) {
            issues += ConnectorScanIssueCode.DOCUMENT_DERIVED_OUTPUT_LIMIT_REACHED
            issues += ConnectorScanIssueCode.PARTIAL_DOCUMENT_INDEX
        }

        val sourceReferences = mutableListOf<SourceReference>()
        val derivedEvents = mutableListOf<DerivedMemoryEvent>()
        val citations = mutableListOf<MemoryCitation>()
        acceptedSignals.forEach { signal ->
            val pageIdentityHmac = identityHasher.hmac(
                namespace = PAGE_IDENTITY_NAMESPACE,
                value = "$documentIdentityHmac:${signal.pageNumber}",
            )
            val sourceId = "source:${LocalFileMemoryExtractor.CONNECTOR_ID}:$pageIdentityHmac"
            val eventId = "event:${LocalFileMemoryExtractor.CONNECTOR_ID}:$pageIdentityHmac"
            val citationId = "citation:${LocalFileMemoryExtractor.CONNECTOR_ID}:$pageIdentityHmac"
            val confidence = DocumentRuntimeWire.confidence(signal.confidenceCode)
            sourceReferences += SourceReference(
                id = sourceId,
                connectorId = LocalFileMemoryExtractor.CONNECTOR_ID,
                sourceKind = SourceKind.PDF_PAGE,
                hmacHash = pageIdentityHmac,
                observedAt = observedAt,
                modifiedAt = observedAt,
                sensitivity = SensitivityLevel.HIGH,
            )
            derivedEvents += DerivedMemoryEvent(
                id = eventId,
                kind = DerivedMemoryEventKind.LOCAL_FILE_INDEX,
                sourceReferenceIds = listOf(sourceId),
                summary = buildSummary(
                    pageNumber = signal.pageNumber,
                    lineCount = signal.nonBlankLineCount,
                    keywords = signal.keywordSignals,
                ),
                startedAt = observedAt,
                keywords = signal.keywordSignals,
                labels = listOf(
                    "local-file",
                    "pdf-page",
                    if (signal.extractionModeCode == DocumentRuntimeWire.EXTRACTION_OCR) "ocr" else "embedded-text",
                ),
                confidence = confidence,
                sensitivity = SensitivityLevel.HIGH,
                citationIds = listOf(citationId),
                createdAt = observedAt,
            )
            citations += MemoryCitation(
                id = citationId,
                sourceReferenceId = sourceId,
                derivedMemoryEventId = eventId,
                label = "PDF page ${signal.pageNumber}",
                observedAt = observedAt,
                confidence = confidence,
            )
        }
        return LocalDocumentExtractionResult(
            sourceReferences = sourceReferences,
            derivedEvents = derivedEvents,
            citations = citations,
            issueCodes = issues,
        )
    }

    private fun buildSummary(
        pageNumber: Int,
        lineCount: Int,
        keywords: List<String>,
    ): String {
        val base = "PDF page $pageNumber indexed with $lineCount non-empty line(s)."
        if (keywords.isEmpty()) return "$base No stable keyword signals found."
        return "$base Signals: ${keywords.take(MAX_SUMMARY_KEYWORDS).joinToString(", ")}."
    }

    private companion object {
        const val PAGE_IDENTITY_NAMESPACE = "local-pdf-page-v1"
        const val MAX_SUMMARY_KEYWORDS = 8
        val SAFE_HMAC = Regex("[a-f0-9]{64}")
    }
}
