package ai.grayin.core.ai

enum class ModelProvider {
    GRAYIN_SERVER,
    HUGGING_FACE,
}

enum class ModelDownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    READY,
    FAILED,
}

data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val provider: ModelProvider,
    val providerLabel: String,
    val downloadPageUrl: String?,
    val downloadUrl: String?,
    val fileName: String,
    val approxSizeBytes: Long,
    val sha256: String?,
    val recommendedRamGb: Int,
    val licenseLabel: String,
    val licenseUrl: String?,
    val recommended: Boolean,
)

object ModelCatalog {
    const val DEFAULT_MODEL_ID = "gemma-4-E2B-it"

    val entries: List<ModelCatalogEntry> = listOf(
        ModelCatalogEntry(
            id = "grayin-gemma-4-E2B-it-q4-v1",
            displayName = "Grayin Gemma 4 E2B Q4 v1",
            provider = ModelProvider.GRAYIN_SERVER,
            providerLabel = "Grayin file server",
            downloadPageUrl = null,
            downloadUrl = null,
            fileName = "grayin-gemma-4-E2B-it-q4-v1.litertlm",
            approxSizeBytes = 2_300_000_000L,
            sha256 = null,
            recommendedRamGb = 6,
            licenseLabel = "Grayin model terms",
            licenseUrl = null,
            recommended = true,
        ),
        ModelCatalogEntry(
            id = "gemma-4-E2B-it",
            displayName = "Gemma 4 E2B",
            provider = ModelProvider.HUGGING_FACE,
            providerLabel = "Google AI Edge LiteRT Community (Hugging Face)",
            downloadPageUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
            fileName = "gemma-4-E2B-it.litertlm",
            approxSizeBytes = 2_580_000_000L,
            sha256 = null,
            recommendedRamGb = 8,
            licenseLabel = "Apache-2.0",
            licenseUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            recommended = false,
        ),
        ModelCatalogEntry(
            id = "gemma-4-E4B-it",
            displayName = "Gemma 4 E4B",
            provider = ModelProvider.HUGGING_FACE,
            providerLabel = "Google AI Edge LiteRT Community (Hugging Face)",
            downloadPageUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
            fileName = "gemma-4-E4B-it.litertlm",
            approxSizeBytes = 3_650_000_000L,
            sha256 = null,
            recommendedRamGb = 12,
            licenseLabel = "Apache-2.0",
            licenseUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
            recommended = false,
        ),
    )

    fun entry(modelId: String): ModelCatalogEntry? {
        return entries.firstOrNull { it.id == modelId }
    }
}
