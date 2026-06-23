package ai.grayin.connectors.localfiles

import ai.grayin.core.model.SourceKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileMemoryExtractorTest {
    private val extractor = LocalFileMemoryExtractor()

    @Test
    fun extractStoresDerivedSignalsNotRawBody() {
        val rawText = """
            Private Seoul dinner note should never persist as a full raw sentence.
            Seoul planning Seoul memory recall local-first evidence.
        """.trimIndent()

        val result = extractor.extract(
            metadata = LocalFileMetadata(
                uri = "content://local/test-note.md",
                displayName = "test-note.md",
                mimeType = "text/markdown",
                sizeBytes = rawText.length.toLong(),
                observedAt = Instant.parse("2026-06-24T10:00:00Z"),
            ),
            text = rawText,
        )

        assertEquals(SourceKind.MARKDOWN_NOTE, result.sourceReference.sourceKind)
        assertTrue(result.derivedEvent.keywords.contains("seoul"))
        assertFalse(result.derivedEvent.summary.contains("Private Seoul dinner note"))
        assertFalse(result.derivedEvent.summary.contains("full raw sentence"))
        assertFalse(result.citation.label.contains("Private Seoul"))
    }

    @Test
    fun extractKeywordsDropsShortAndCommonWords() {
        val keywords = extractor.extractKeywords("the app and AI note note note work")

        assertEquals("note", keywords.first())
        assertFalse(keywords.contains("the"))
        assertFalse(keywords.contains("ai"))
    }
}
