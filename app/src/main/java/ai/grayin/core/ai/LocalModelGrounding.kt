package ai.grayin.core.ai

import ai.grayin.core.model.EvidencePack

object LocalModelGrounding {
    fun citedEvidencePack(evidencePack: EvidencePack): EvidencePack {
        val citationsById = evidencePack.citations.associateBy { citation -> citation.id }
        val citedEvidence = evidencePack.evidenceItems.mapNotNull { evidence ->
            val citationIds = evidence.citationIds.filter { citationId ->
                citationsById[citationId]?.derivedMemoryEventId == evidence.derivedMemoryEventId
            }
            evidence.copy(citationIds = citationIds).takeIf { citationIds.isNotEmpty() }
        }
        val usedCitationIds = citedEvidence.flatMapTo(hashSetOf()) { evidence -> evidence.citationIds }
        return evidencePack.copy(
            evidenceItems = citedEvidence,
            citations = evidencePack.citations.filter { citation -> citation.id in usedCitationIds },
        )
    }

    fun evidenceIdsFromAnswer(answer: String, evidencePack: EvidencePack): List<String> {
        val evidenceLine = answer.lineSequence()
            .firstOrNull { line -> line.trimStart().startsWith(EVIDENCE_PREFIX, ignoreCase = true) }
            ?: return emptyList()
        val claimedIds = evidenceLine.substringAfter(':')
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        return evidencePack.evidenceItems
            .map { evidence -> evidence.id }
            .filter { evidenceId -> evidenceId in claimedIds }
    }

    fun validateDraft(evidencePack: EvidencePack, draft: LocalModelAnswerDraft): LocalModelAnswerDraft? {
        if (draft.answer.isBlank()) return null
        val citedPack = citedEvidencePack(evidencePack)
        val allowedEvidenceIds = citedPack.evidenceItems.mapTo(hashSetOf()) { evidence -> evidence.id }
        val usedEvidenceIds = draft.usedEvidenceItemIds.distinct()
        if (usedEvidenceIds.isEmpty() || usedEvidenceIds.any { it !in allowedEvidenceIds }) return null
        return draft.copy(usedEvidenceItemIds = usedEvidenceIds)
    }

    private const val EVIDENCE_PREFIX = "Evidence:"
}
