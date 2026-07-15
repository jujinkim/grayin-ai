package ai.grayin.connectors.localfiles

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import java.time.Instant

private val SAFE_LOCAL_IDENTITY_HMAC = Regex("[a-f0-9]{64}")

data class LocalFileMetadata(
    val identityHmac: String,
    val sourceKind: SourceKind,
    val observedAt: Instant,
) {
    init {
        require(identityHmac.matches(SAFE_LOCAL_IDENTITY_HMAC)) {
            "Local document identity must be an HMAC-SHA256 value."
        }
        require(sourceKind == SourceKind.LOCAL_FILE || sourceKind == SourceKind.MARKDOWN_NOTE) {
            "Local text extraction supports only text and Markdown source kinds."
        }
    }
}

data class LocalFileExtractionResult(
    val sourceReference: SourceReference,
    val derivedEvent: DerivedMemoryEvent,
    val citation: MemoryCitation,
)

class LocalFileMemoryExtractor {
    fun extract(
        metadata: LocalFileMetadata,
        text: String,
    ): LocalFileExtractionResult {
        val sourceReferenceId = "source:local_files:${metadata.identityHmac}"
        val eventId = "event:local_files:${metadata.identityHmac}"
        val citationId = "citation:local_files:${metadata.identityHmac}"
        val keywords = extractKeywords(text)
        val lineCount = text.lineSequence().filter { it.isNotBlank() }.count()

        return LocalFileExtractionResult(
            sourceReference = SourceReference(
                id = sourceReferenceId,
                connectorId = CONNECTOR_ID,
                sourceKind = metadata.sourceKind,
                hmacHash = metadata.identityHmac,
                observedAt = metadata.observedAt,
                modifiedAt = metadata.observedAt,
                sensitivity = SensitivityLevel.HIGH,
            ),
            derivedEvent = DerivedMemoryEvent(
                id = eventId,
                kind = DerivedMemoryEventKind.LOCAL_FILE_INDEX,
                sourceReferenceIds = listOf(sourceReferenceId),
                summary = buildSummary(lineCount, keywords),
                startedAt = metadata.observedAt,
                keywords = keywords,
                labels = buildLabels(metadata.sourceKind),
                confidence = if (keywords.isEmpty()) ConfidenceLevel.LOW else ConfidenceLevel.MEDIUM,
                sensitivity = SensitivityLevel.HIGH,
                citationIds = listOf(citationId),
                createdAt = metadata.observedAt,
            ),
            citation = MemoryCitation(
                id = citationId,
                sourceReferenceId = sourceReferenceId,
                derivedMemoryEventId = eventId,
                label = if (metadata.sourceKind == SourceKind.MARKDOWN_NOTE) {
                    "Local Markdown document"
                } else {
                    "Local text document"
                },
                observedAt = metadata.observedAt,
                confidence = if (keywords.isEmpty()) ConfidenceLevel.LOW else ConfidenceLevel.MEDIUM,
            ),
        )
    }

    private fun buildSummary(lineCount: Int, keywords: List<String>): String {
        val base = "Local text file indexed with $lineCount non-empty line(s)."
        if (keywords.isEmpty()) return "$base No stable keyword signals found."
        return "$base Signals: ${keywords.take(MAX_SUMMARY_KEYWORDS).joinToString(", ")}."
    }

    private fun buildLabels(sourceKind: SourceKind): List<String> {
        return if (sourceKind == SourceKind.MARKDOWN_NOTE) {
            listOf("local-file", "markdown")
        } else {
            listOf("local-file", "text")
        }
    }

    fun extractKeywords(text: String): List<String> {
        return WORD_PATTERN.findAll(text.lowercase())
            .map { it.value }
            .filter { token -> token.length in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH && token !in STOP_WORDS }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(MAX_KEYWORDS)
    }

    companion object {
        const val CONNECTOR_ID = "local_files"
        private const val MIN_TOKEN_LENGTH = 3
        private const val MAX_TOKEN_LENGTH = 32
        private const val MAX_KEYWORDS = 16
        private const val MAX_SUMMARY_KEYWORDS = 8
        private val WORD_PATTERN = Regex("[\\p{L}\\p{Nd}]+")
        private val STOP_WORDS = setOf(
            "the",
            "and",
            "for",
            "with",
            "from",
            "this",
            "that",
            "you",
            "your",
            "are",
            "was",
            "were",
            "have",
            "has",
            "had",
            "not",
            "but",
            "all",
            "can",
            "will",
            "into",
            "about",
            "what",
            "when",
            "where",
            "there",
            "their",
            "then",
        )
    }
}
