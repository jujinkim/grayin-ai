package ai.grayin.connectors.localfiles

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalDocumentTypeResolverTest {
    @Test
    fun resolvesOnlyClosedSupportedMimeTypesAndExtensions() {
        val cases = listOf(
            Triple("application/pdf; charset=binary", "anything.bin", LocalDocumentType.PDF),
            Triple("application/octet-stream", "REPORT.PDF", LocalDocumentType.PDF),
            Triple("text/x-markdown", "note.bin", LocalDocumentType.MARKDOWN),
            Triple("application/octet-stream", "note.MarkDown", LocalDocumentType.MARKDOWN),
            Triple("text/plain; charset=utf-8", "note.bin", LocalDocumentType.TEXT),
            Triple(null, "note.TXT", LocalDocumentType.TEXT),
            Triple("text/html", "page.html", LocalDocumentType.UNSUPPORTED),
            Triple("text/csv", "table.csv", LocalDocumentType.UNSUPPORTED),
            Triple("application/octet-stream", "unknown.bin", LocalDocumentType.UNSUPPORTED),
        )

        cases.forEach { (mimeType, displayName, expected) ->
            assertEquals(expected, LocalDocumentTypeResolver.resolve(mimeType, displayName))
        }
    }

    @Test
    fun pdfTakesPrecedenceOverConflictingTextMetadata() {
        assertEquals(
            LocalDocumentType.PDF,
            LocalDocumentTypeResolver.resolve("text/plain", "document.pdf"),
        )
        assertEquals(
            LocalDocumentType.PDF,
            LocalDocumentTypeResolver.resolve("application/pdf", "document.txt"),
        )
    }
}
