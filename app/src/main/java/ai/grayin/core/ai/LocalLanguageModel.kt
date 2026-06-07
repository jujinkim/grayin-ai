package ai.grayin.core.ai

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MissingSource

enum class LocalModelStatus {
    UNAVAILABLE,
    READY,
    LOADING,
}

data class LocalModelMetadata(
    val modelId: String,
    val displayName: String,
    val localOnly: Boolean,
    val commercialApi: Boolean,
    val networkRequired: Boolean,
)

data class LocalModelAnswerDraft(
    val answer: String,
    val usedEvidenceItemIds: List<String>,
    val inferenceNotes: List<String>,
    val confidence: ConfidenceLevel,
    val missingSources: List<MissingSource>,
)

interface LocalLanguageModel {
    val metadata: LocalModelMetadata

    suspend fun status(): LocalModelStatus

    suspend fun generate(evidencePack: EvidencePack): LocalModelAnswerDraft
}

