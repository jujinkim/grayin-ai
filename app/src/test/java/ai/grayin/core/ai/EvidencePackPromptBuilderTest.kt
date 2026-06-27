package ai.grayin.core.ai

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.SourceAvailability
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidencePackPromptBuilderTest {
    @Test
    fun promptContainsOnlyDerivedEvidenceAndMissingSources() {
        val citation = MemoryCitation(
            id = "citation:1",
            sourceReferenceId = "source:local_files:1",
            derivedMemoryEventId = "event:1",
            label = "Local file: note.md",
            confidence = ConfidenceLevel.HIGH,
        )
        val evidencePack = EvidencePack(
            id = "pack:1",
            query = "어제 뭐 했지?",
            generatedAt = Instant.parse("2026-06-27T10:00:00Z"),
            evidenceItems = listOf(
                EvidenceItem(
                    id = "evidence:1",
                    derivedMemoryEventId = "event:1",
                    summary = "Visited Seoul at 19:00.",
                    eventKind = DerivedMemoryEventKind.PLACE_VISIT,
                    occurredAt = Instant.parse("2026-06-26T19:00:00Z"),
                    confidence = ConfidenceLevel.HIGH,
                    citationIds = listOf(citation.id),
                    capabilities = setOf(MemoryCapability.HAS_TIME, MemoryCapability.HAS_LOCATION),
                ),
            ),
            citations = listOf(citation),
            missingSources = listOf(
                MissingSource(
                    capability = MemoryCapability.HAS_MEDIA,
                    availability = SourceAvailability.NOT_INDEXED,
                    explanation = "Photos were not indexed.",
                    connectorId = "photos",
                ),
            ),
        )

        val prompt = EvidencePackPromptBuilder.build(evidencePack)

        assertTrue(prompt.contains("DERIVED_EVIDENCE"))
        assertTrue(prompt.contains("Visited Seoul at 19:00."))
        assertTrue(prompt.contains("Photos were not indexed."))
        assertFalse(prompt.contains("raw notification"))
        assertFalse(prompt.contains("source connector"))
    }
}
