package ai.grayin.core.ocr

import ai.grayin.core.artifact.FixedCatalogArtifactSpec

enum class OcrLanguagePack(val id: String) {
    ENGLISH("eng"),
    KOREAN("kor"),
    JAPANESE("jpn"),
}

class OcrLanguagePackEntry internal constructor(
    val language: OcrLanguagePack,
    val displayName: String,
    val fileName: String,
    val expectedSizeBytes: Long,
    val sha256: String,
    val licenseLabel: String,
    val licenseUrl: String,
    internal val artifact: FixedCatalogArtifactSpec,
) {
    val id: String
        get() = language.id
}

object OcrLanguagePackCatalog {
    const val TESSDATA_FAST_COMMIT = "87416418657359cb625c412a48b6e1d6d41c29bd"

    private const val BASE_URL =
        "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$TESSDATA_FAST_COMMIT"
    private const val LICENSE_URL =
        "https://github.com/tesseract-ocr/tessdata_fast/blob/$TESSDATA_FAST_COMMIT/LICENSE"

    private val entries = listOf(
        entry(
            language = OcrLanguagePack.ENGLISH,
            displayName = "English",
            expectedSizeBytes = 4_113_088L,
            sha256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
        ),
        entry(
            language = OcrLanguagePack.KOREAN,
            displayName = "Korean",
            expectedSizeBytes = 1_677_415L,
            sha256 = "6b85e11d9bbf07863b97b3523b1b112844c43e713df8b66418a081fd1060b3b2",
        ),
        entry(
            language = OcrLanguagePack.JAPANESE,
            displayName = "Japanese",
            expectedSizeBytes = 2_471_260L,
            sha256 = "1f5de9236d2e85f5fdf4b3c500f2d4926f8d9449f28f5394472d9e8d83b91b4d",
        ),
    )

    fun all(): List<OcrLanguagePackEntry> = entries.toList()

    fun entry(language: OcrLanguagePack): OcrLanguagePackEntry = entries.single { it.language == language }

    fun entry(id: String): OcrLanguagePackEntry? = entries.firstOrNull { it.id == id }

    private fun entry(
        language: OcrLanguagePack,
        displayName: String,
        expectedSizeBytes: Long,
        sha256: String,
    ): OcrLanguagePackEntry {
        val fileName = "${language.id}.traineddata"
        val artifact = FixedCatalogArtifactSpec(
            id = "tessdata-fast-${language.id}",
            url = "$BASE_URL/$fileName",
            fileName = fileName,
            expectedSizeBytes = expectedSizeBytes,
            sha256 = sha256,
        )
        return OcrLanguagePackEntry(
            language = language,
            displayName = displayName,
            fileName = fileName,
            expectedSizeBytes = expectedSizeBytes,
            sha256 = sha256,
            licenseLabel = "Apache-2.0",
            licenseUrl = LICENSE_URL,
            artifact = artifact,
        )
    }
}
