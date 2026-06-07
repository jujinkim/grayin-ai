package ai.grayin.core.grounding

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.GroundedAnswer
import ai.grayin.core.model.InferenceStep
import ai.grayin.core.model.MissingSource

data class GroundedAnswerRequest(
    val evidencePack: EvidencePack,
)

interface GroundedAnswerGenerator {
    fun generate(request: GroundedAnswerRequest): GroundedAnswer
}

class TemplateGroundedAnswerGenerator : GroundedAnswerGenerator {
    override fun generate(request: GroundedAnswerRequest): GroundedAnswer {
        val evidencePack = request.evidencePack
        val validCitationIds = evidencePack.citations.map { it.id }.toSet()
        val citedEvidence = evidencePack.evidenceItems.filter { evidence ->
            evidence.citationIds.any { it in validCitationIds }
        }
        val usedCitationIds = citedEvidence.flatMap { it.citationIds }.toSet()
        val usedCitations = evidencePack.citations.filter { it.id in usedCitationIds }

        return GroundedAnswer(
            id = "answer:${evidencePack.id}",
            answer = buildAnswer(citedEvidence),
            evidence = citedEvidence,
            inference = buildInference(citedEvidence),
            confidence = combineConfidence(citedEvidence, evidencePack.missingSources),
            missingData = evidencePack.missingSources,
            citations = usedCitations,
        )
    }

    private fun buildAnswer(evidence: List<EvidenceItem>): String {
        if (evidence.isEmpty()) {
            return "I cannot answer from indexed, cited evidence."
        }

        return evidence.joinToString(separator = "\n") { item ->
            "- ${item.summary}"
        }
    }

    private fun buildInference(evidence: List<EvidenceItem>): List<InferenceStep> {
        return evidence.map { item ->
            InferenceStep(
                id = "inference:${item.id}",
                claim = item.summary,
                explanation = "Claim is copied from cited evidence item ${item.id}.",
                evidenceItemIds = listOf(item.id),
                confidence = item.confidence,
            )
        }
    }

    private fun combineConfidence(
        evidence: List<EvidenceItem>,
        missingSources: List<MissingSource>,
    ): ConfidenceLevel {
        if (evidence.isEmpty()) return ConfidenceLevel.UNKNOWN
        if (evidence.any { it.confidence == ConfidenceLevel.LOW }) return ConfidenceLevel.LOW
        if (missingSources.isNotEmpty()) return ConfidenceLevel.MEDIUM
        if (evidence.all { it.confidence == ConfidenceLevel.HIGH }) return ConfidenceLevel.HIGH
        if (evidence.any { it.confidence == ConfidenceLevel.MEDIUM }) return ConfidenceLevel.MEDIUM
        return ConfidenceLevel.UNKNOWN
    }
}
