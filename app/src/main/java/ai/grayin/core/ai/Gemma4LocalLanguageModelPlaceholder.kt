package ai.grayin.core.ai

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.EvidencePack

class Gemma4LocalLanguageModelPlaceholder : LocalLanguageModel {
    override val metadata: LocalModelMetadata = LocalModelMetadata(
        modelId = "gemma-4-local-placeholder",
        displayName = "Gemma 4 Local Placeholder",
        localOnly = true,
        commercialApi = false,
        networkRequired = false,
    )

    override suspend fun status(): LocalModelStatus = LocalModelStatus.UNAVAILABLE

    override suspend fun generate(evidencePack: EvidencePack): LocalModelAnswerDraft {
        return LocalModelAnswerDraft(
            answer = "Local Gemma 4 model is not available in this MVP placeholder.",
            usedEvidenceItemIds = emptyList(),
            inferenceNotes = listOf("No model inference ran."),
            confidence = ConfidenceLevel.UNKNOWN,
            missingSources = evidencePack.missingSources,
        )
    }
}

