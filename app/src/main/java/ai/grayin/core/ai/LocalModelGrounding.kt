package ai.grayin.core.ai

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MemoryCapability

data class ParsedLocalModelAnswer(
    val answer: String,
    val evidenceIds: List<String>,
    val missingCapabilities: List<MemoryCapability>,
    val confidence: ConfidenceLevel,
)

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

    fun parseAnswer(answer: String): ParsedLocalModelAnswer? {
        val lines = answer.trim().lines()
        if (lines.size != RESPONSE_PREFIXES.size) return null
        val values = lines.zip(RESPONSE_PREFIXES).map { (line, prefix) ->
            if (!line.startsWith(prefix)) return null
            line.removePrefix(prefix).trim().takeIf(String::isNotEmpty) ?: return null
        }
        val evidenceIds = when {
            values[1].equals(NONE_VALUE, ignoreCase = true) -> emptyList()
            else -> values[1].split(',').map(String::trim).also { ids ->
                if (
                    ids.any { id -> !id.matches(EVIDENCE_ID) } ||
                    ids.distinct().size != ids.size
                ) {
                    return null
                }
            }
        }
        val missingCapabilities = when {
            values[2].equals(NONE_VALUE, ignoreCase = true) -> emptyList()
            else -> values[2].split(';').map { entry ->
                val capabilityKey = entry.substringBefore(':', missingDelimiterValue = "").trim()
                val explanation = entry.substringAfter(':', missingDelimiterValue = "").trim()
                val capability = MemoryCapability.entries.firstOrNull { it.name == capabilityKey }
                    ?: return null
                if (explanation.isEmpty()) return null
                capability
            }.also { capabilities ->
                if (capabilities.distinct().size != capabilities.size) return null
            }
        }
        val confidence = ConfidenceLevel.entries.firstOrNull { it.name == values[3] } ?: return null
        return ParsedLocalModelAnswer(
            answer = values[0],
            evidenceIds = evidenceIds,
            missingCapabilities = missingCapabilities,
            confidence = confidence,
        )
    }

    fun evidenceIdsFromAnswer(answer: String): List<String> {
        return parseAnswer(answer)?.evidenceIds.orEmpty()
    }

    fun validateDraft(evidencePack: EvidencePack, draft: LocalModelAnswerDraft): LocalModelAnswerDraft? {
        if (draft.answer.isBlank() || !draft.groundingContractValid) return null
        val citedPack = citedEvidencePack(evidencePack)
        val allowedEvidenceIds = citedPack.evidenceItems.mapTo(hashSetOf()) { evidence -> evidence.id }
        val usedEvidenceIds = draft.usedEvidenceItemIds
        if (usedEvidenceIds.isEmpty() || usedEvidenceIds.any { it !in allowedEvidenceIds }) return null
        if (usedEvidenceIds.distinct().size != usedEvidenceIds.size) return null
        val expectedMissing = evidencePack.missingSources.map { it.capability }.toSet()
        val claimedMissing = draft.missingSources.map { it.capability }
        if (claimedMissing.distinct().size != claimedMissing.size || claimedMissing.toSet() != expectedMissing) return null
        return draft.copy(usedEvidenceItemIds = usedEvidenceIds)
    }

    private const val NONE_VALUE = "none"
    private val RESPONSE_PREFIXES = listOf("Answer:", "Evidence:", "Missing:", "Confidence:")
    private val EVIDENCE_ID = Regex("[A-Za-z0-9._:-]{1,200}")
}
