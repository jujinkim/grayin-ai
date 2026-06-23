package ai.grayin.connectors.localfiles

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import java.security.MessageDigest
import java.time.Instant

data class LocalFileMetadata(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val observedAt: Instant,
)

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
        val sourceHash = sha256(metadata.uri)
        val sourceReferenceId = "source:local_files:$sourceHash"
        val eventId = "event:local_files:$sourceHash"
        val citationId = "citation:local_files:$sourceHash"
        val keywords = extractKeywords(text)
        val lineCount = text.lineSequence().filter { it.isNotBlank() }.count()
        val sourceKind = if (metadata.displayName.endsWith(".md", ignoreCase = true) ||
            metadata.mimeType == "text/markdown"
        ) {
            SourceKind.MARKDOWN_NOTE
        } else {
            SourceKind.LOCAL_FILE
        }

        return LocalFileExtractionResult(
            sourceReference = SourceReference(
                id = sourceReferenceId,
                connectorId = CONNECTOR_ID,
                sourceKind = sourceKind,
                localPointer = metadata.uri,
                externalIdHash = sourceHash,
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
                labels = buildLabels(metadata),
                confidence = if (keywords.isEmpty()) ConfidenceLevel.LOW else ConfidenceLevel.MEDIUM,
                sensitivity = SensitivityLevel.HIGH,
                citationIds = listOf(citationId),
                createdAt = metadata.observedAt,
            ),
            citation = MemoryCitation(
                id = citationId,
                sourceReferenceId = sourceReferenceId,
                derivedMemoryEventId = eventId,
                label = "Local file: ${metadata.displayName}",
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

    private fun buildLabels(metadata: LocalFileMetadata): List<String> {
        return buildList {
            add("local-file")
            metadata.mimeType?.let(::add)
            if (metadata.displayName.endsWith(".md", ignoreCase = true)) add("markdown")
            if (metadata.displayName.endsWith(".txt", ignoreCase = true)) add("text")
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

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_ID_CHARS)
    }

    companion object {
        const val CONNECTOR_ID = "local_files"
        private const val MIN_TOKEN_LENGTH = 3
        private const val MAX_TOKEN_LENGTH = 32
        private const val MAX_KEYWORDS = 16
        private const val MAX_SUMMARY_KEYWORDS = 8
        private const val HASH_ID_CHARS = 32
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
