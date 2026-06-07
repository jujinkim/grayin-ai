package ai.grayin.core.model

import java.time.Instant

data class MemoryCitation(
    val id: String,
    val sourceReferenceId: String,
    val derivedMemoryEventId: String? = null,
    val label: String,
    val observedAt: Instant? = null,
    val confidence: ConfidenceLevel = ConfidenceLevel.UNKNOWN,
)

data class EvidenceItem(
    val id: String,
    val derivedMemoryEventId: String,
    val summary: String,
    val eventKind: DerivedMemoryEventKind,
    val occurredAt: Instant? = null,
    val confidence: ConfidenceLevel,
    val citationIds: List<String> = emptyList(),
    val capabilities: Set<MemoryCapability> = emptySet(),
)

data class EvidencePack(
    val id: String,
    val query: String,
    val generatedAt: Instant,
    val evidenceItems: List<EvidenceItem>,
    val citations: List<MemoryCitation>,
    val missingSources: List<MissingSource> = emptyList(),
)

data class InferenceStep(
    val id: String,
    val claim: String,
    val explanation: String,
    val evidenceItemIds: List<String>,
    val confidence: ConfidenceLevel,
)

data class MissingSource(
    val capability: MemoryCapability,
    val availability: SourceAvailability,
    val explanation: String,
    val connectorId: String? = null,
)

data class GroundedAnswer(
    val id: String,
    val answer: String,
    val evidence: List<EvidenceItem>,
    val inference: List<InferenceStep>,
    val confidence: ConfidenceLevel,
    val missingData: List<MissingSource>,
    val citations: List<MemoryCitation>,
)

