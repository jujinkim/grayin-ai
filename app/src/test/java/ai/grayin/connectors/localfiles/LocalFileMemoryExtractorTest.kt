package ai.grayin.connectors.localfiles

import ai.grayin.core.model.SourceKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileMemoryExtractorTest {
    private val extractor = LocalFileMemoryExtractor()

    @Test
    fun extractStoresHmacDerivedSignalsWithoutRawIdentityOrBody() {
        val rawText = """
            Private Seoul dinner note should never persist as a full raw sentence.
            Seoul planning Seoul memory recall local-first evidence.
        """.trimIndent()
        val identityHmac = "a".repeat(64)

        val result = extractor.extract(
            metadata = LocalFileMetadata(
                identityHmac = identityHmac,
                sourceKind = SourceKind.MARKDOWN_NOTE,
                observedAt = Instant.parse("2026-06-24T10:00:00Z"),
            ),
            text = rawText,
        )

        assertEquals(SourceKind.MARKDOWN_NOTE, result.sourceReference.sourceKind)
        assertEquals(identityHmac, result.sourceReference.hmacHash)
        assertNull(result.sourceReference.localPointer)
        assertNull(result.sourceReference.externalIdHash)
        assertNull(result.sourceReference.sourceAppIdentifier)
        assertEquals("Local Markdown document", result.citation.label)
        assertTrue(result.derivedEvent.keywords.contains("seoul"))
        assertFalse(result.derivedEvent.summary.contains("Private Seoul dinner note"))
        assertFalse(result.derivedEvent.summary.contains("full raw sentence"))
        assertFalse(result.toString().contains("content://"))
        assertFalse(result.toString().contains("secret-note.md"))
    }

    @Test
    fun extractUsesClosedTextCitation() {
        val result = extractor.extract(
            metadata = LocalFileMetadata(
                identityHmac = "b".repeat(64),
                sourceKind = SourceKind.LOCAL_FILE,
                observedAt = Instant.parse("2026-06-24T10:00:00Z"),
            ),
            text = "bounded derived signals",
        )

        assertEquals("Local text document", result.citation.label)
        assertEquals(listOf("local-file", "text"), result.derivedEvent.labels)
    }

    @Test
    fun metadataRejectsMalformedHmacAndUnsupportedSourceKind() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalFileMetadata("not-a-hmac", SourceKind.LOCAL_FILE, Instant.EPOCH)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalFileMetadata("c".repeat(64), SourceKind.PDF_PAGE, Instant.EPOCH)
        }
    }

    @Test
    fun extractKeywordsDropsShortAndCommonWords() {
        val keywords = extractor.extractKeywords("the app and AI note note note work")

        assertEquals("note", keywords.first())
        assertFalse(keywords.contains("the"))
        assertFalse(keywords.contains("ai"))
    }
}
