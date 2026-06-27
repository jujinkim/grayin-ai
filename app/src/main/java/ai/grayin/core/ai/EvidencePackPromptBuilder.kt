package ai.grayin.core.ai

import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource

object EvidencePackPromptBuilder {
    fun build(evidencePack: EvidencePack): String {
        val citationsById = evidencePack.citations.associateBy { it.id }
        return buildString {
            appendLine("You are Grayin AI, a local-first personal memory recall engine.")
            appendLine("Use only DERIVED_EVIDENCE and MISSING_SOURCES below.")
            appendLine("Never claim access to original files, photos, notifications, calendar records, usage logs, maps, accounts, cloud, or network.")
            appendLine("You may infer cautiously from indexed derived evidence, but every claim must be grounded in evidence ids.")
            appendLine("If required data is missing, say what is missing.")
            appendLine("Answer in the same language as USER_QUERY when practical.")
            appendLine()
            appendLine("USER_QUERY:")
            appendLine(evidencePack.query)
            appendLine()
            appendLine("DERIVED_EVIDENCE:")
            if (evidencePack.evidenceItems.isEmpty()) {
                appendLine("- none")
            } else {
                evidencePack.evidenceItems.take(MAX_EVIDENCE_ITEMS).forEachIndexed { index, item ->
                    appendEvidence(index + 1, item, citationsById)
                }
            }
            appendLine()
            appendLine("MISSING_SOURCES:")
            if (evidencePack.missingSources.isEmpty()) {
                appendLine("- none")
            } else {
                evidencePack.missingSources.take(MAX_MISSING_SOURCES).forEach { missingSource ->
                    appendMissingSource(missingSource)
                }
            }
            appendLine()
            appendLine("Return format:")
            appendLine("Answer: <concise answer>")
            appendLine("Evidence: <evidence ids used>")
            appendLine("Missing: <missing data, if any>")
            appendLine("Confidence: LOW, MEDIUM, HIGH, or UNKNOWN")
        }
    }

    private fun StringBuilder.appendEvidence(
        number: Int,
        item: EvidenceItem,
        citationsById: Map<String, MemoryCitation>,
    ) {
        val citationLabels = item.citationIds
            .mapNotNull { citationsById[it] }
            .joinToString(separator = "; ") { citation -> "${citation.id}:${citation.label}" }
            .ifBlank { "none" }
        appendLine("- E$number")
        appendLine("  evidence_id: ${item.id}")
        appendLine("  event_id: ${item.derivedMemoryEventId}")
        appendLine("  kind: ${item.eventKind}")
        appendLine("  occurred_at: ${item.occurredAt ?: "unknown"}")
        appendLine("  confidence: ${item.confidence}")
        appendLine("  capabilities: ${item.capabilities.joinToString()}")
        appendLine("  citations: $citationLabels")
        appendLine("  summary: ${item.summary.take(MAX_SUMMARY_CHARS)}")
    }

    private fun StringBuilder.appendMissingSource(missingSource: MissingSource) {
        appendLine(
            "- ${missingSource.capability}: ${missingSource.availability} - " +
                missingSource.explanation.take(MAX_MISSING_CHARS),
        )
    }

    private const val MAX_EVIDENCE_ITEMS = 12
    private const val MAX_MISSING_SOURCES = 12
    private const val MAX_SUMMARY_CHARS = 600
    private const val MAX_MISSING_CHARS = 300
}
