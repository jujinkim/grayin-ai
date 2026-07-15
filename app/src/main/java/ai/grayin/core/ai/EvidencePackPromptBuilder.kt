package ai.grayin.core.ai

import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource

object EvidencePackPromptBuilder {
    fun build(evidencePack: EvidencePack): String {
        val citationsById = evidencePack.citations.associateBy { it.id }
        val prompt = buildString {
            appendLine("You are Grayin AI, a local-first personal memory recall engine.")
            appendLine("Use only DERIVED_EVIDENCE and MISSING_SOURCES below.")
            appendLine("Never claim access to original files, photos, notifications, calendar records, usage logs, maps, accounts, cloud, or network.")
            appendLine("You may infer cautiously from indexed derived evidence, but every claim must be grounded in evidence ids.")
            appendLine("If required data is missing, say what is missing.")
            appendLine("Answer in the same language as USER_QUERY when practical.")
            appendLine()
            appendLine("USER_QUERY:")
            appendLine(closedPromptLine(evidencePack.query, MAX_QUERY_UTF8_BYTES))
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
            appendLine("Evidence: <exact evidence_id values used, separated by commas; none if no evidence is used>")
            appendLine("Missing: <CAPABILITY: concise explanation entries separated by semicolons; none if no data is missing>")
            appendLine("Confidence: LOW, MEDIUM, HIGH, or UNKNOWN")
        }
        check(prompt.toByteArray(Charsets.UTF_8).size < MAX_PROMPT_UTF8_BYTES) {
            "EvidencePack prompt exceeds its closed size boundary."
        }
        return prompt
    }

    private fun StringBuilder.appendEvidence(
        number: Int,
        item: EvidenceItem,
        citationsById: Map<String, MemoryCitation>,
    ) {
        val citationLabels = item.citationIds
            .mapNotNull { citationsById[it] }
            .take(MAX_CITATIONS_PER_EVIDENCE)
            .joinToString(separator = "; ") { citation ->
                val id = closedPromptLine(citation.id, MAX_ID_UTF8_BYTES)
                val label = closedPromptLine(citation.label, MAX_CITATION_LABEL_UTF8_BYTES)
                "$id:$label"
            }
            .ifBlank { "none" }
        appendLine("- E$number")
        appendLine("  evidence_id: ${closedPromptLine(item.id, MAX_ID_UTF8_BYTES)}")
        appendLine("  event_id: ${closedPromptLine(item.derivedMemoryEventId, MAX_ID_UTF8_BYTES)}")
        appendLine("  kind: ${item.eventKind}")
        appendLine("  occurred_at: ${item.occurredAt ?: "unknown"}")
        appendLine("  confidence: ${item.confidence}")
        appendLine("  capabilities: ${item.capabilities.joinToString()}")
        appendLine("  citations: $citationLabels")
        appendLine("  summary: ${closedPromptLine(item.summary, MAX_SUMMARY_UTF8_BYTES)}")
    }

    private fun StringBuilder.appendMissingSource(missingSource: MissingSource) {
        appendLine(
            "- ${missingSource.capability}: ${missingSource.availability} - " +
                closedPromptLine(missingSource.explanation, MAX_MISSING_UTF8_BYTES),
        )
    }

    private fun closedPromptLine(value: String, maxUtf8Bytes: Int): String {
        val output = StringBuilder(minOf(value.length, maxUtf8Bytes))
        var usedBytes = 0
        var index = 0
        var pendingSpace = false
        while (index < value.length) {
            val first = value[index]
            val codePoint = when {
                first.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                    index += 2
                    Character.toCodePoint(first, value[index - 1])
                }

                first.isSurrogate() -> {
                    index += 1
                    pendingSpace = output.isNotEmpty()
                    continue
                }

                else -> {
                    index += 1
                    first.code
                }
            }
            val type = Character.getType(codePoint)
            if (
                Character.isISOControl(codePoint) ||
                Character.isWhitespace(codePoint) ||
                Character.isSpaceChar(codePoint) ||
                type == Character.FORMAT.toInt() ||
                type == Character.PRIVATE_USE.toInt() ||
                type == Character.SURROGATE.toInt() ||
                type == Character.UNASSIGNED.toInt()
            ) {
                pendingSpace = output.isNotEmpty()
                continue
            }
            val encoded = String(Character.toChars(codePoint))
            val encodedBytes = encoded.toByteArray(Charsets.UTF_8).size
            val separatorBytes = if (pendingSpace && output.isNotEmpty()) 1 else 0
            if (usedBytes + separatorBytes + encodedBytes > maxUtf8Bytes) break
            if (separatorBytes == 1) {
                output.append(' ')
                usedBytes += 1
            }
            output.append(encoded)
            usedBytes += encodedBytes
            pendingSpace = false
        }
        return output.toString().ifBlank { "unknown" }
    }

    private const val MAX_EVIDENCE_ITEMS = 12
    private const val MAX_MISSING_SOURCES = 12
    private const val MAX_CITATIONS_PER_EVIDENCE = 4
    private const val MAX_ID_UTF8_BYTES = 256
    private const val MAX_QUERY_UTF8_BYTES = 1_000
    private const val MAX_CITATION_LABEL_UTF8_BYTES = 200
    private const val MAX_SUMMARY_UTF8_BYTES = 600
    private const val MAX_MISSING_UTF8_BYTES = 300
    private const val MAX_PROMPT_UTF8_BYTES = 64 * 1024
}
