package ai.grayin.connectors.localfiles.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSignalDeriverTest {
    private val deriver = DocumentSignalDeriver()

    @Test
    fun embeddedTextRequiresStableCharactersAndMultipleWords() {
        assertFalse(deriver.hasSufficientEmbeddedText("tiny"))
        assertFalse(deriver.hasSufficientEmbeddedText("1234567890123456"))
        assertTrue(deriver.hasSufficientEmbeddedText("quarterly planning meeting notes"))
        assertTrue(deriver.hasSufficientEmbeddedText("분기별 제품 계획 회의에서 작성한 상세 메모입니다"))
    }

    @Test
    fun derivedSignalContainsOnlyBoundedStructuralDataAndKeywords() {
        val result = deriver.derive(
            pageNumber = 7,
            extractionModeCode = DocumentRuntimeWire.EXTRACTION_EMBEDDED,
            text = "planning planning roadmap\nmeeting roadmap\n",
            confidenceCode = DocumentRuntimeWire.CONFIDENCE_MEDIUM,
        )!!

        assertEquals(7, result.pageNumber)
        assertEquals(2, result.nonBlankLineCount)
        assertEquals(listOf("planning", "roadmap", "meeting"), result.keywordSignals)
        assertTrue(result.keywordSignals.size <= DocumentSignalDeriver.MAX_KEYWORDS)
    }

    @Test
    fun rawSentenceIsNotPresentAsAResultField() {
        val raw = "RAW_TEXT_SENTINEL must never cross the Binder boundary as a sentence."
        val result = deriver.derive(
            pageNumber = 1,
            extractionModeCode = DocumentRuntimeWire.EXTRACTION_OCR,
            text = raw,
            confidenceCode = DocumentRuntimeWire.CONFIDENCE_LOW,
        )!!

        val allStrings = result.keywordSignals.joinToString("|")
        assertFalse(allStrings.contains(raw))
        assertFalse(allStrings.contains(" "))
    }

    @Test
    fun unstablePageProducesNoSignal() {
        assertNull(
            deriver.derive(
                pageNumber = 1,
                extractionModeCode = DocumentRuntimeWire.EXTRACTION_EMBEDDED,
                text = "..",
                confidenceCode = DocumentRuntimeWire.CONFIDENCE_LOW,
            ),
        )
    }
}
