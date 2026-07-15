package ai.grayin.core.ai

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.EvidencePack

class FakeLocalLanguageModel : LocalLanguageModel {
    override val metadata: LocalModelMetadata = LocalModelMetadata(
        modelId = "fake-local-model",
        displayName = "Fake Local Model",
        localOnly = true,
        commercialApi = false,
        networkRequired = false,
    )

    override suspend fun status(): LocalModelStatus = LocalModelStatus.READY

    override suspend fun generate(evidencePack: EvidencePack): LocalModelAnswerDraft {
        val evidence = evidencePack.evidenceItems
        if (evidence.isEmpty()) {
            return LocalModelAnswerDraft(
                answer = "No indexed evidence is available for this query.",
                usedEvidenceItemIds = emptyList(),
                inferenceNotes = listOf("No evidence items were supplied."),
                confidence = ConfidenceLevel.UNKNOWN,
                missingSources = evidencePack.missingSources,
                groundingContractValid = true,
            )
        }

        return LocalModelAnswerDraft(
            answer = evidence.joinToString(separator = "\n") { "- ${it.summary}" },
            usedEvidenceItemIds = evidence.map { it.id },
            inferenceNotes = evidence.map { "Used evidence item ${it.id}." },
            confidence = combineConfidence(evidence.map { it.confidence }),
            missingSources = evidencePack.missingSources,
            groundingContractValid = true,
        )
    }

    private fun combineConfidence(confidences: List<ConfidenceLevel>): ConfidenceLevel {
        if (confidences.isEmpty()) return ConfidenceLevel.UNKNOWN
        if (confidences.any { it == ConfidenceLevel.LOW }) return ConfidenceLevel.LOW
        if (confidences.all { it == ConfidenceLevel.HIGH }) return ConfidenceLevel.HIGH
        if (confidences.any { it == ConfidenceLevel.MEDIUM }) return ConfidenceLevel.MEDIUM
        return ConfidenceLevel.UNKNOWN
    }
}
