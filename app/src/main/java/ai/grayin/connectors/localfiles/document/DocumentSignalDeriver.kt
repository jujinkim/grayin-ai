package ai.grayin.connectors.localfiles.document

class DocumentSignalDeriver {
    fun derive(
        pageNumber: Int,
        extractionModeCode: Int,
        text: String,
        confidenceCode: Int,
    ): DocumentPageSignal? {
        val keywords = extractKeywords(text)
        val stableCharacters = text.count { character -> character.isLetterOrDigit() }
        if (stableCharacters < MIN_STABLE_CHARACTERS && keywords.isEmpty()) return null
        return DocumentPageSignal(
            pageNumber = pageNumber,
            extractionModeCode = extractionModeCode,
            nonBlankLineCount = text.lineSequence().count { it.isNotBlank() }
                .coerceAtMost(PdfResourceLimits.MAX_TRANSIENT_TEXT_BYTES),
            keywordSignals = keywords,
            confidenceCode = confidenceCode,
        )
    }

    fun hasSufficientEmbeddedText(text: String): Boolean {
        return text.count { it.isLetterOrDigit() } >= MIN_EMBEDDED_CHARACTERS &&
            WORD_PATTERN.findAll(text).take(MIN_EMBEDDED_WORDS).count() >= MIN_EMBEDDED_WORDS
    }

    fun extractKeywords(text: String): List<String> {
        return WORD_PATTERN.findAll(text.lowercase())
            .map { match -> match.value }
            .filter(::isSafeKeyword)
            .filterNot(STOP_WORDS::contains)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(MAX_KEYWORDS)
    }

    companion object {
        const val MAX_KEYWORDS = 8
        private const val MIN_TOKEN_LENGTH = 3
        private const val MAX_TOKEN_LENGTH = 32
        private const val MIN_STABLE_CHARACTERS = 3
        private const val MIN_EMBEDDED_CHARACTERS = 16
        private const val MIN_EMBEDDED_WORDS = 2
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

        fun isSafeKeyword(keyword: String): Boolean {
            if (keyword.length !in MIN_TOKEN_LENGTH..MAX_TOKEN_LENGTH) return false
            if (keyword.toByteArray(Charsets.UTF_8).size > MAX_KEYWORD_UTF8_BYTES) return false
            return keyword.all { character -> character.isLetterOrDigit() }
        }

        private const val MAX_KEYWORD_UTF8_BYTES = 96
    }
}
