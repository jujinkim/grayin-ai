package ai.grayin.core.ai

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MemoryCitation
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalModelGroundingTest {
    private val citation = MemoryCitation(
        id = "citation:1",
        sourceReferenceId = "source:1",
        derivedMemoryEventId = "event:1",
        label = "Calendar: meeting",
    )
    private val citedEvidence = EvidenceItem(
        id = "evidence:event:1",
        derivedMemoryEventId = "event:1",
        summary = "Meeting evidence.",
        eventKind = DerivedMemoryEventKind.CALENDAR_EVENT,
        confidence = ConfidenceLevel.HIGH,
        citationIds = listOf(citation.id),
    )
    private val uncitedEvidence = citedEvidence.copy(
        id = "evidence:event:2",
        derivedMemoryEventId = "event:2",
        citationIds = listOf("citation:missing"),
    )
    private val evidencePack = EvidencePack(
        id = "pack:1",
        query = "What happened?",
        generatedAt = Instant.parse("2026-07-15T00:00:00Z"),
        evidenceItems = listOf(citedEvidence, uncitedEvidence),
        citations = listOf(citation),
    )

    @Test
    fun citedEvidencePackRemovesUncitedEvidence() {
        val filtered = LocalModelGrounding.citedEvidencePack(evidencePack)

        assertEquals(listOf(citedEvidence), filtered.evidenceItems)
        assertEquals(listOf(citation), filtered.citations)
    }

    @Test
    fun parsesOnlyExactEvidenceIdsFromEvidenceLine() {
        val ids = LocalModelGrounding.evidenceIdsFromAnswer(
            answer = "Answer: Meeting.\nEvidence: evidence:event:1\nMissing: none",
            evidencePack = evidencePack,
        )

        assertEquals(listOf("evidence:event:1"), ids)
    }

    @Test
    fun doesNotAcceptEvidenceIdPrefixAsExactClaim() {
        val similarEvidence = citedEvidence.copy(id = "evidence:event:10")
        val ids = LocalModelGrounding.evidenceIdsFromAnswer(
            answer = "Answer: Meeting.\nEvidence: evidence:event:10\nMissing: none",
            evidencePack = evidencePack.copy(evidenceItems = listOf(citedEvidence, similarEvidence)),
        )

        assertEquals(listOf("evidence:event:10"), ids)
    }

    @Test
    fun rejectsDraftThatClaimsUnknownEvidence() {
        val draft = LocalModelAnswerDraft(
            answer = "Answer: unsupported",
            usedEvidenceItemIds = listOf("evidence:event:2"),
            inferenceNotes = emptyList(),
            confidence = ConfidenceLevel.HIGH,
            missingSources = emptyList(),
        )

        assertNull(LocalModelGrounding.validateDraft(evidencePack, draft))
    }
}
