package ai.grayin.core.store

import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.connector.missingSourceIdentity
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import kotlin.text.Charsets.UTF_8

internal object ConnectorScanValidator {
    fun validate(scanResult: ConnectorScanResult) {
        require(scanResult.connectorId.isNotBlank()) { "A connector scan must have a connector ID." }
        requireDistinctIds("source reference", scanResult.sourceReferences.map { it.id })
        requireDistinctIds("derived event", scanResult.derivedEvents.map { it.id })
        requireDistinctIds("citation", scanResult.citations.map { it.id })
        requireDistinctIds("place cluster", scanResult.placeClusters.map { it.id })
        requireDistinctIds("app usage summary", scanResult.appUsageSummaries.map { it.id })
        require(scanResult.sourceReferences.all { it.connectorId == scanResult.connectorId }) {
            "Every source reference in a connector scan must belong to that connector."
        }
        require(scanResult.sourceReferences.all { it.id.startsWith("source:${scanResult.connectorId}:") }) {
            "Every source reference ID must be connector-scoped."
        }
        require(scanResult.derivedEvents.all { it.id.startsWith("event:${scanResult.connectorId}:") }) {
            "Every derived event ID must be connector-scoped."
        }
        require(scanResult.citations.all { it.id.startsWith("citation:${scanResult.connectorId}:") }) {
            "Every citation ID must be connector-scoped."
        }
        require(scanResult.placeClusters.all { it.id.startsWith("place-cluster:${scanResult.connectorId}:") }) {
            "Every place-cluster ID must be connector-scoped."
        }
        require(scanResult.appUsageSummaries.all { it.id.startsWith("app-usage:${scanResult.connectorId}:") }) {
            "Every app-usage summary ID must be connector-scoped."
        }
        require(
            scanResult.missingSources.all { missing ->
                missing.connectorId == null || missing.connectorId == scanResult.connectorId
            },
        ) {
            "Every connector-scoped missing source must belong to the scan connector."
        }
        require(
            scanResult.missingSources.all { missing ->
                missing.issueCode != null &&
                    missing.explanation == missing.issueCode.defaultEnglish &&
                    missing.explanation.isNotBlank() &&
                    missing.explanation.length <= MAX_MISSING_SOURCE_EXPLANATION_CHARS &&
                    missing.explanation.none(Char::isISOControl)
            },
        ) {
            "A connector scan missing source must use its bounded stable issue code."
        }
        require(scanResult.missingSources.size <= ConnectorScanStatus.MAX_MISSING_SOURCES) {
            "A connector scan contains too many missing-source records."
        }
        require(
            scanResult.missingSources.distinctBy(::missingSourceIdentity).size == scanResult.missingSources.size,
        ) {
            "A connector scan contains duplicate missing-source records."
        }

        val sourceIds = scanResult.sourceReferences.mapTo(mutableSetOf()) { it.id }
        val referencedSourceIds = scanResult.derivedEvents.flatMap { it.sourceReferenceIds } +
            scanResult.citations.map { it.sourceReferenceId } +
            scanResult.placeClusters.flatMap { it.sourceReferenceIds } +
            scanResult.appUsageSummaries.flatMap { it.sourceReferenceIds }
        require(referencedSourceIds.all { it in sourceIds }) {
            "A connector scan cannot persist dangling source references."
        }

        val eventIds = scanResult.derivedEvents.mapTo(mutableSetOf()) { it.id }
        val referencedEventIds = scanResult.citations.mapNotNull { it.derivedMemoryEventId }
        require(referencedEventIds.all { it in eventIds }) {
            "A connector scan cannot persist citations for missing derived events."
        }

        val citationIds = scanResult.citations.mapTo(mutableSetOf()) { it.id }
        val referencedCitationIds = scanResult.derivedEvents.flatMap { it.citationIds }
        require(referencedCitationIds.all { it in citationIds }) {
            "A connector scan cannot persist derived events with missing citations."
        }

        val citationsById = scanResult.citations.associateBy { it.id }
        val eventsById = scanResult.derivedEvents.associateBy { it.id }
        scanResult.derivedEvents.forEach { event ->
            event.citationIds.forEach { citationId ->
                val citation = checkNotNull(citationsById[citationId])
                require(citation.derivedMemoryEventId == event.id) {
                    "A citation must target the derived event that lists it."
                }
                require(citation.sourceReferenceId in event.sourceReferenceIds) {
                    "A citation source must be one of its derived event's sources."
                }
            }
        }
        scanResult.citations.forEach { citation ->
            citation.derivedMemoryEventId?.let { eventId ->
                require(citation.id in checkNotNull(eventsById[eventId]).citationIds) {
                    "A derived-event citation must be listed by its target event."
                }
            }
        }
        if (scanResult.connectorId == LOCAL_FILES_CONNECTOR_ID) {
            validateLocalFilesScan(scanResult)
        }
    }

    private fun validateLocalFilesScan(scanResult: ConnectorScanResult) {
        require(scanResult.replaceExistingConnectorData) {
            "A Local Files scan must atomically replace its connector snapshot."
        }
        require(scanResult.placeClusters.isEmpty() && scanResult.appUsageSummaries.isEmpty()) {
            "A Local Files scan cannot persist place or app-usage sections."
        }
        require(
            scanResult.sourceReferences.size == scanResult.derivedEvents.size &&
                scanResult.derivedEvents.size == scanResult.citations.size &&
                scanResult.sourceReferences.size <= MAX_LOCAL_FILE_GRAPH_ROWS,
        ) {
            "A Local Files scan must contain a bounded one-to-one derived graph."
        }
        val sourcesById = scanResult.sourceReferences.associateBy { source -> source.id }
        val eventsById = scanResult.derivedEvents.associateBy { event -> event.id }
        scanResult.sourceReferences.forEach { source ->
            val hmac = source.hmacHash
            require(
                source.sourceKind in LOCAL_FILE_SOURCE_KINDS &&
                    source.localPointer == null &&
                    source.externalIdHash == null &&
                    source.sourceAppIdentifier == null &&
                    hmac != null &&
                    hmac.matches(SAFE_HMAC) &&
                    source.id == "source:$LOCAL_FILES_CONNECTOR_ID:$hmac" &&
                    source.sensitivity == SensitivityLevel.HIGH,
            ) {
                "A Local Files source reference must contain only its closed HMAC identity."
            }
        }
        scanResult.derivedEvents.forEach { event ->
            require(
                event.kind == DerivedMemoryEventKind.LOCAL_FILE_INDEX &&
                    event.sourceReferenceIds.size == 1 &&
                    event.citationIds.size == 1 &&
                    event.sensitivity == SensitivityLevel.HIGH &&
                    event.entities.isEmpty() &&
                    event.endedAt == null,
            ) {
                "A Local Files event must use the closed one-source derived-event schema."
            }
            val source = checkNotNull(sourcesById[event.sourceReferenceIds.single()])
            val citation = checkNotNull(scanResult.citations.find { it.id == event.citationIds.single() })
            require(event.id == "event:$LOCAL_FILES_CONNECTOR_ID:${source.hmacHash}") {
                "A Local Files event ID must use its source HMAC."
            }
            validateLocalFilesEvent(source, event, citation)
        }
        scanResult.citations.forEach { citation ->
            val source = checkNotNull(sourcesById[citation.sourceReferenceId])
            val event = checkNotNull(citation.derivedMemoryEventId?.let(eventsById::get))
            require(citation.id == "citation:$LOCAL_FILES_CONNECTOR_ID:${source.hmacHash}") {
                "A Local Files citation ID must use its source HMAC."
            }
            require(event.sourceReferenceIds.single() == source.id) {
                "A Local Files citation must belong to its event source."
            }
            require(isAllowedLocalCitation(source.sourceKind, citation.label)) {
                "A Local Files citation must use a closed label without a file identity."
            }
        }
    }

    private fun validateLocalFilesEvent(
        source: SourceReference,
        event: DerivedMemoryEvent,
        citation: MemoryCitation,
    ) {
        val observedAt = source.observedAt
        require(
            observedAt != null &&
                source.modifiedAt == observedAt &&
                event.startedAt == observedAt &&
                event.createdAt == observedAt &&
                citation.observedAt == observedAt,
        ) {
            "A Local Files derived graph must use one non-null observation timestamp."
        }
        require(
            event.confidence in LOCAL_FILE_CONFIDENCE_LEVELS &&
                citation.confidence == event.confidence,
        ) {
            "A Local Files citation must preserve its event confidence."
        }

        val maxKeywords = if (source.sourceKind == SourceKind.PDF_PAGE) {
            MAX_PDF_KEYWORDS
        } else {
            MAX_LOCAL_TEXT_KEYWORDS
        }
        require(
            event.keywords.size <= maxKeywords &&
                event.keywords.distinct().size == event.keywords.size &&
                event.keywords.all(::isAllowedLocalKeyword),
        ) {
            "A Local Files event may contain only bounded, distinct keyword signals."
        }

        val expectedLabels = when (source.sourceKind) {
            SourceKind.LOCAL_FILE -> listOf("local-file", "text")
            SourceKind.MARKDOWN_NOTE -> listOf("local-file", "markdown")
            SourceKind.PDF_PAGE -> {
                require(
                    event.labels == listOf("local-file", "pdf-page", "embedded-text") ||
                        event.labels == listOf("local-file", "pdf-page", "ocr"),
                ) {
                    "A Local PDF event must use a closed extraction-mode label."
                }
                event.labels
            }

            else -> error("Unsupported Local Files source kind.")
        }
        require(event.labels == expectedLabels) {
            "A Local Files event must use its exact closed label set."
        }

        val summaryPrefix = when (source.sourceKind) {
            SourceKind.LOCAL_FILE,
            SourceKind.MARKDOWN_NOTE,
            -> "Local text file indexed with "

            SourceKind.PDF_PAGE -> {
                val pageNumber = pdfPageNumber(citation.label)
                require(pageNumber != null) { "A Local PDF citation must contain its bounded page number." }
                "PDF page $pageNumber indexed with "
            }
        }
        val lineCount = Regex(
            "${Regex.escape(summaryPrefix)}([0-9]{1,5}) non-empty line\\(s\\)\\..*",
        ).matchEntire(event.summary)?.groupValues?.get(1)?.toIntOrNull()
        require(lineCount != null && lineCount in 0..MAX_LOCAL_NON_EMPTY_LINES) {
            "A Local Files event must contain a bounded structural line count."
        }
        val summaryBase = "$summaryPrefix$lineCount non-empty line(s)."
        val expectedSummary = if (event.keywords.isEmpty()) {
            "$summaryBase No stable keyword signals found."
        } else {
            "$summaryBase Signals: ${event.keywords.take(MAX_SUMMARY_KEYWORDS).joinToString(", ")}."
        }
        require(event.summary == expectedSummary) {
            "A Local Files event must use the canonical structural summary."
        }
    }

    private fun isAllowedLocalKeyword(keyword: String): Boolean {
        return keyword.length in MIN_LOCAL_KEYWORD_CHARS..MAX_LOCAL_KEYWORD_CHARS &&
            keyword.toByteArray(UTF_8).size <= MAX_LOCAL_KEYWORD_UTF8_BYTES &&
            keyword == keyword.lowercase() &&
            SAFE_LOCAL_KEYWORD.matches(keyword)
    }

    private fun isAllowedLocalCitation(sourceKind: SourceKind, label: String): Boolean {
        return when (sourceKind) {
            SourceKind.LOCAL_FILE -> label == "Local text document"
            SourceKind.MARKDOWN_NOTE -> label == "Local Markdown document"
            SourceKind.PDF_PAGE -> pdfPageNumber(label) != null
            else -> false
        }
    }

    private fun pdfPageNumber(label: String): Int? {
        return PDF_PAGE_CITATION.matchEntire(label)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.takeIf { pageNumber -> pageNumber in 1..MAX_PDF_PAGES_PER_DOCUMENT }
    }

    private fun requireDistinctIds(label: String, ids: List<String>) {
        require(ids.all { it.isNotBlank() }) { "Every $label ID must be non-blank." }
        require(ids.distinct().size == ids.size) { "A connector scan contains duplicate $label IDs." }
    }

    private const val MAX_MISSING_SOURCE_EXPLANATION_CHARS = 240
    private const val LOCAL_FILES_CONNECTOR_ID = "local_files"
    private const val MAX_LOCAL_FILE_GRAPH_ROWS = 256
    private const val MAX_PDF_PAGES_PER_DOCUMENT = 64
    private const val MAX_LOCAL_TEXT_KEYWORDS = 16
    private const val MAX_PDF_KEYWORDS = 8
    private const val MAX_SUMMARY_KEYWORDS = 8
    private const val MIN_LOCAL_KEYWORD_CHARS = 3
    private const val MAX_LOCAL_KEYWORD_CHARS = 32
    private const val MAX_LOCAL_KEYWORD_UTF8_BYTES = 96
    private const val MAX_LOCAL_NON_EMPTY_LINES = 65_536
    private val SAFE_HMAC = Regex("[a-f0-9]{64}")
    private val SAFE_LOCAL_KEYWORD = Regex("[\\p{L}\\p{Nd}]+")
    private val PDF_PAGE_CITATION = Regex("PDF page ([1-9][0-9]*)")
    private val LOCAL_FILE_CONFIDENCE_LEVELS = setOf(
        ConfidenceLevel.LOW,
        ConfidenceLevel.MEDIUM,
        ConfidenceLevel.HIGH,
    )
    private val LOCAL_FILE_SOURCE_KINDS = setOf(
        SourceKind.LOCAL_FILE,
        SourceKind.MARKDOWN_NOTE,
        SourceKind.PDF_PAGE,
    )
}
