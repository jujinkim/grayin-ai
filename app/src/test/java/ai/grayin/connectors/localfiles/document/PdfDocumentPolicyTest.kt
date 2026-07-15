package ai.grayin.connectors.localfiles.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfDocumentPolicyTest {
    @Test
    fun ocrWordRegionsHaveFixedEdgeAndPixelBounds() {
        assertTrue(OcrWordRegionPolicy.isAllowed(1, 1))
        assertTrue(OcrWordRegionPolicy.isAllowed(512, 512))
        assertFalse(OcrWordRegionPolicy.isAllowed(0, 1))
        assertFalse(OcrWordRegionPolicy.isAllowed(PdfResourceLimits.MAX_OCR_WORD_EDGE + 1, 1))
        assertFalse(OcrWordRegionPolicy.isAllowed(513, 512))
    }

    @Test
    fun canonicalResourceLimitsMatchTheDocumentContract() {
        assertEquals(25L * 1024L * 1024L, PdfResourceLimits.MAX_PDF_BYTES)
        assertEquals(64, PdfResourceLimits.MAX_PDF_PAGES)
        assertEquals(32, PdfResourceLimits.MAX_OCR_PAGES)
        assertEquals(4_000_000L, PdfResourceLimits.MAX_BITMAP_PIXELS)
        assertEquals(2_048, PdfResourceLimits.MAX_BITMAP_LONG_EDGE)
        assertEquals(32 * 1024, PdfResourceLimits.MAX_TRANSIENT_TEXT_BYTES)
        assertEquals(128, PdfResourceLimits.MAX_DERIVED_PAGE_SIGNALS_PER_SCAN)
        assertEquals(10_000L, PdfResourceLimits.OCR_TIMEOUT_MILLIS)
        assertEquals(120_000L, PdfResourceLimits.DOCUMENT_TIMEOUT_MILLIS)
        assertEquals(600_000L, PdfResourceLimits.SCAN_TIMEOUT_MILLIS)
    }

    @Test
    fun bitmapPolicyUsesLongArithmeticAndRejectsEveryExceededBoundary() {
        assertTrue(PdfRenderTarget.isAllowed(2_000, 2_000))
        assertTrue(PdfRenderTarget.isAllowed(2_048, 1_953))
        assertFalse(PdfRenderTarget.isAllowed(2_048, 1_954))
        assertFalse(PdfRenderTarget.isAllowed(2_049, 1))
        assertFalse(PdfRenderTarget.isAllowed(0, 100))
        assertFalse(PdfRenderTarget.isAllowed(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun pagePointConversionAlwaysProducesABoundedTarget() {
        val ordinary = PdfRenderTarget.fromPagePoints(612, 792)
        val extreme = PdfRenderTarget.fromPagePoints(Int.MAX_VALUE, Int.MAX_VALUE)

        assertNotNull(ordinary)
        assertNotNull(extreme)
        assertTrue(PdfRenderTarget.isAllowed(ordinary!!.width, ordinary.height))
        assertTrue(PdfRenderTarget.isAllowed(extreme!!.width, extreme.height))
        assertNull(PdfRenderTarget.fromPagePoints(0, 100))
        assertNull(PdfRenderTarget.fromPagePoints(100, -1))
    }

    @Test
    fun transientTextLimitCountsUtf8BytesInsteadOfUtf16Characters() {
        val exactAscii = BoundedPageTextBuilder(8).apply { append("12345678") }.build()
        val longAsciiBuilder = BoundedPageTextBuilder(8)
        assertFalse(longAsciiBuilder.append("123456789"))
        val longAscii = longAsciiBuilder.build()
        val koreanBuilder = BoundedPageTextBuilder(6)
        assertTrue(koreanBuilder.append("한글"))
        assertFalse(koreanBuilder.append("어"))
        val emojiBuilder = BoundedPageTextBuilder(4)
        assertTrue(emojiBuilder.append("😀"))
        assertFalse(emojiBuilder.append("😀"))

        assertEquals("12345678", exactAscii.value)
        assertFalse(exactAscii.truncated)
        assertEquals("12345678", longAscii.value)
        assertTrue(longAscii.truncated)
        assertEquals("한글", koreanBuilder.build().value)
        assertTrue(koreanBuilder.build().truncated)
        assertEquals("😀", emojiBuilder.build().value)
        assertTrue(emojiBuilder.build().truncated)
    }

    @Test
    fun transientTextReplacesControlCharactersWithoutLeakingThem() {
        val result = BoundedPageTextBuilder().apply {
            append("safe\u0000value\nnext")
        }.build()

        assertEquals("safe value\nnext", result.value)
        assertFalse(result.value.contains('\u0000'))
    }
}
