package ai.grayin.connectors.localfiles.document

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

object PdfResourceLimits {
    const val MAX_PDF_BYTES = 25L * 1024L * 1024L
    const val MAX_PDF_PAGES = 64
    const val MAX_OCR_PAGES = 32
    const val MAX_BITMAP_PIXELS = 4_000_000L
    const val MAX_BITMAP_LONG_EDGE = 2_048
    const val MAX_TRANSIENT_TEXT_BYTES = 32 * 1024
    const val MAX_OCR_WORD_REGIONS = 512
    const val MAX_OCR_WORD_EDGE = 1_024
    const val MAX_OCR_WORD_PIXELS = 256L * 1024L
    const val MAX_DERIVED_PAGE_SIGNALS_PER_SCAN = 128
    const val OCR_TIMEOUT_MILLIS = 10_000L
    const val OCR_HARD_TIMEOUT_GRACE_MILLIS = 2_000L
    const val DOCUMENT_TIMEOUT_MILLIS = 120_000L
    const val SCAN_TIMEOUT_MILLIS = 600_000L
}

object OcrWordRegionPolicy {
    fun isAllowed(width: Int, height: Int): Boolean {
        return width in 1..PdfResourceLimits.MAX_OCR_WORD_EDGE &&
            height in 1..PdfResourceLimits.MAX_OCR_WORD_EDGE &&
            width.toLong() * height.toLong() <= PdfResourceLimits.MAX_OCR_WORD_PIXELS
    }
}

data class PdfRenderTarget(
    val width: Int,
    val height: Int,
) {
    init {
        require(isAllowed(width, height)) { "PDF render target exceeds the fixed resource limits." }
    }

    companion object {
        fun fromPagePoints(widthPoints: Int, heightPoints: Int): PdfRenderTarget? {
            if (widthPoints <= 0 || heightPoints <= 0) return null
            val desiredWidth = widthPoints.toDouble() * TARGET_SCALE
            val desiredHeight = heightPoints.toDouble() * TARGET_SCALE
            if (!desiredWidth.isFinite() || !desiredHeight.isFinite()) return null
            val longEdgeScale = PdfResourceLimits.MAX_BITMAP_LONG_EDGE /
                maxOf(desiredWidth, desiredHeight)
            val pixelScale = sqrt(
                PdfResourceLimits.MAX_BITMAP_PIXELS.toDouble() /
                    (desiredWidth * desiredHeight),
            )
            val scale = min(1.0, min(longEdgeScale, pixelScale))
            val width = floor(desiredWidth * scale).toInt().coerceAtLeast(1)
            val height = floor(desiredHeight * scale).toInt().coerceAtLeast(1)
            if (!isAllowed(width, height)) return null
            return PdfRenderTarget(width, height)
        }

        fun isAllowed(width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) return false
            if (maxOf(width, height) > PdfResourceLimits.MAX_BITMAP_LONG_EDGE) return false
            return width.toLong() * height.toLong() <= PdfResourceLimits.MAX_BITMAP_PIXELS
        }

        private const val TARGET_SCALE = 2.0
    }
}

data class BoundedPageText(
    val value: String,
    val truncated: Boolean,
)

class BoundedPageTextBuilder(
    private val maxUtf8Bytes: Int = PdfResourceLimits.MAX_TRANSIENT_TEXT_BYTES,
) {
    private val builder = StringBuilder()
    private var utf8Bytes = 0
    private var wasTruncated = false

    init {
        require(maxUtf8Bytes > 0) { "Text limit must be positive." }
    }

    fun append(value: String): Boolean {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val charCount = Character.charCount(codePoint)
            val safeCodePoint = if (isSafeTextCodePoint(codePoint)) codePoint else ' '.code
            val byteCount = utf8Length(safeCodePoint)
            if (utf8Bytes + byteCount > maxUtf8Bytes) {
                wasTruncated = true
                return false
            }
            builder.appendCodePoint(safeCodePoint)
            utf8Bytes += byteCount
            offset += charCount
        }
        return true
    }

    fun markTruncated() {
        wasTruncated = true
    }

    fun build(): BoundedPageText = BoundedPageText(
        value = builder.toString(),
        truncated = wasTruncated,
    )

    private fun isSafeTextCodePoint(codePoint: Int): Boolean {
        return codePoint == '\n'.code || codePoint == '\t'.code || !Character.isISOControl(codePoint)
    }

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint <= 0x7f -> 1
        codePoint <= 0x7ff -> 2
        codePoint <= 0xffff -> 3
        else -> 4
    }
}
