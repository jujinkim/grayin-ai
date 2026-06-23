package ai.grayin.core.grounding

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateGroundedAnswerGeneratorTest {
    private val generator = TemplateGroundedAnswerGenerator()

    @Test
    fun generateUsesOnlyEvidenceWithValidCitation() {
        val citation = MemoryCitation(
            id = "citation:1",
            sourceReferenceId = "source:1",
            derivedMemoryEventId = "event:1",
            label = "Local file: note.md",
            confidence = ConfidenceLevel.HIGH,
        )
        val cited = EvidenceItem(
            id = "evidence:1",
            derivedMemoryEventId = "event:1",
            summary = "Cited derived signal.",
            eventKind = DerivedMemoryEventKind.LOCAL_FILE_INDEX,
            confidence = ConfidenceLevel.HIGH,
            citationIds = listOf(citation.id),
            capabilities = setOf(MemoryCapability.HAS_TEXT),
        )
        val uncited = cited.copy(
            id = "evidence:2",
            derivedMemoryEventId = "event:2",
            summary = "Uncited derived signal.",
            citationIds = listOf("missing-citation"),
        )

        val answer = generator.generate(
            GroundedAnswerRequest(
                EvidencePack(
                    id = "pack:1",
                    query = "What is indexed?",
                    generatedAt = Instant.parse("2026-06-24T10:00:00Z"),
                    evidenceItems = listOf(cited, uncited),
                    citations = listOf(citation),
                ),
            ),
        )

        assertEquals(1, answer.evidence.size)
        assertTrue(answer.answer.contains("Cited derived signal."))
        assertFalse(answer.answer.contains("Uncited derived signal."))
        assertEquals(ConfidenceLevel.HIGH, answer.confidence)
    }
}
