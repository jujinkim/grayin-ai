package ai.grayin.connectors.localfiles

import java.util.Locale

enum class LocalDocumentType {
    TEXT,
    MARKDOWN,
    PDF,
    UNSUPPORTED,
}

object LocalDocumentTypeResolver {
    fun resolve(
        mimeType: String?,
        displayName: String?,
    ): LocalDocumentType {
        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val normalizedName = displayName?.lowercase(Locale.ROOT).orEmpty()
        return when {
            normalizedMime == PDF_MIME || normalizedName.endsWith(".pdf") -> LocalDocumentType.PDF
            normalizedMime in MARKDOWN_MIMES ||
                normalizedName.endsWith(".md") ||
                normalizedName.endsWith(".markdown") -> LocalDocumentType.MARKDOWN
            normalizedMime == TEXT_MIME || normalizedName.endsWith(".txt") -> LocalDocumentType.TEXT
            else -> LocalDocumentType.UNSUPPORTED
        }
    }

    private const val PDF_MIME = "application/pdf"
    private const val TEXT_MIME = "text/plain"
    private val MARKDOWN_MIMES = setOf("text/markdown", "text/x-markdown")
}
