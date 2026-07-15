package ai.grayin.core.ai

import ai.grayin.core.artifact.FixedCatalogArtifactSpec

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

enum class ModelDownloadFailureCode(val storageKey: String) {
    DOWNLOAD_NOT_CONFIGURED("download_not_configured"),
    DOWNLOAD_FAILED("download_failed"),
    ATOMIC_INSTALL_FAILED("atomic_install_failed"),
    REDIRECT_REJECTED("artifact_redirect_rejected"),
    HTTP_REJECTED("artifact_http_rejected"),
    SERVER_ERROR("artifact_server_error"),
    CONTENT_TYPE_INVALID("artifact_content_type_invalid"),
    CONTENT_ENCODING_INVALID("artifact_content_encoding_invalid"),
    SIZE_MISMATCH("artifact_size_mismatch"),
    CHECKSUM_MISMATCH("artifact_checksum_mismatch"),
    NETWORK_OR_IO_FAILURE("artifact_io_failure"),
    ;

    companion object {
        fun fromStorageKey(storageKey: String): ModelDownloadFailureCode? {
            return entries.firstOrNull { code -> code.storageKey == storageKey }
        }
    }
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
    val expectedDownloadSizeBytes: Long?,
    val sha256: String?,
    val recommendedRamGb: Int,
    val licenseLabel: String,
    val licenseUrl: String?,
    val recommended: Boolean,
) {
    val transportMetadataComplete: Boolean
        get() = artifactSpecOrNull() != null

    val downloadConfigured: Boolean
        get() = ModelDownloadReleaseGate.GENERATION_FENCING_READY && transportMetadataComplete

    internal fun artifactSpecOrNull(): FixedCatalogArtifactSpec? {
        val fixedUrl = downloadUrl ?: return null
        val exactSize = expectedDownloadSizeBytes?.takeIf { it > 0L } ?: return null
        val digest = sha256?.takeIf { it.matches(SHA_256) } ?: return null
        return runCatching {
            FixedCatalogArtifactSpec(
                id = "model-${digest.take(16)}",
                url = fixedUrl,
                fileName = fileName,
                expectedSizeBytes = exactSize,
                sha256 = digest,
            )
        }.getOrNull()
    }

    private companion object {
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}

internal object ModelDownloadReleaseGate {
    const val GENERATION_FENCING_READY = false
}

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
            expectedDownloadSizeBytes = null,
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
            downloadUrl = null,
            fileName = "gemma-4-E2B-it.litertlm",
            approxSizeBytes = 2_580_000_000L,
            expectedDownloadSizeBytes = null,
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
            downloadUrl = null,
            fileName = "gemma-4-E4B-it.litertlm",
            approxSizeBytes = 3_650_000_000L,
            expectedDownloadSizeBytes = null,
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
