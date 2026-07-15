package ai.grayin.core.ai

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MemoryCapability
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
    fun citedEvidencePackRejectsCitationLinkedToAnotherEvent() {
        val mismatchedCitation = citation.copy(id = "citation:other", derivedMemoryEventId = "event:other")
        val mismatchedEvidence = citedEvidence.copy(citationIds = listOf(mismatchedCitation.id))

        val filtered = LocalModelGrounding.citedEvidencePack(
            evidencePack.copy(
                evidenceItems = listOf(mismatchedEvidence),
                citations = listOf(mismatchedCitation),
            ),
        )

        assertEquals(emptyList<EvidenceItem>(), filtered.evidenceItems)
        assertEquals(emptyList<MemoryCitation>(), filtered.citations)
    }

    @Test
    fun parsesOnlyExactEvidenceIdsFromEvidenceLine() {
        val ids = LocalModelGrounding.evidenceIdsFromAnswer(
            answer = "Answer: Meeting.\nEvidence: evidence:event:1\nMissing: none\nConfidence: HIGH",
        )

        assertEquals(listOf("evidence:event:1"), ids)
    }

    @Test
    fun preservesTheCompleteClaimedEvidenceId() {
        val ids = LocalModelGrounding.evidenceIdsFromAnswer(
            answer = "Answer: Meeting.\nEvidence: evidence:event:10\nMissing: none\nConfidence: HIGH",
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
            groundingContractValid = true,
        )

        assertNull(LocalModelGrounding.validateDraft(evidencePack, draft))
    }

    @Test
    fun rejectsDraftThatMixesKnownAndUnknownEvidenceClaims() {
        val claimedIds = LocalModelGrounding.evidenceIdsFromAnswer(
            answer = "Answer: Meeting.\nEvidence: evidence:event:1, evidence:event:unknown\nMissing: none\nConfidence: HIGH",
        )
        val draft = LocalModelAnswerDraft(
            answer = "Answer: Meeting.",
            usedEvidenceItemIds = claimedIds,
            inferenceNotes = emptyList(),
            confidence = ConfidenceLevel.HIGH,
            missingSources = emptyList(),
            groundingContractValid = true,
        )

        assertEquals(listOf("evidence:event:1", "evidence:event:unknown"), claimedIds)
        assertNull(LocalModelGrounding.validateDraft(evidencePack, draft))
    }

    @Test
    fun rejectsDraftThatRepeatsAnEvidenceClaim() {
        val draft = LocalModelAnswerDraft(
            answer = "Answer: Meeting.",
            usedEvidenceItemIds = listOf("evidence:event:1", "evidence:event:1"),
            inferenceNotes = emptyList(),
            confidence = ConfidenceLevel.HIGH,
            missingSources = emptyList(),
            groundingContractValid = true,
        )

        assertNull(LocalModelGrounding.validateDraft(evidencePack, draft))
    }

    @Test
    fun rejectsMultipleEvidenceLines() {
        val ids = LocalModelGrounding.evidenceIdsFromAnswer(
            answer = "Answer: Meeting.\nEvidence: evidence:event:1\nEvidence: evidence:event:unknown\nConfidence: HIGH",
        )

        assertEquals(emptyList<String>(), ids)
    }

    @Test
    fun rejectsExtraLinesAndMalformedMissingOrConfidenceFields() {
        val invalidAnswers = listOf(
            "Answer: Meeting.\nEvidence: evidence:event:1\nMissing: none\nConfidence: HIGH\nextra",
            "Answer: Meeting.\nEvidence: evidence:event:1\nMissing: unstructured\nConfidence: HIGH",
            "Answer: Meeting.\nEvidence: evidence:event:1\nMissing: none\nConfidence: CERTAIN",
            "Answer: Meeting.\nEvidence: evidence:event:1, evidence:event:1\nMissing: none\nConfidence: HIGH",
        )

        invalidAnswers.forEach { answer ->
            assertNull(LocalModelGrounding.parseAnswer(answer))
        }
    }

    @Test
    fun parsesStructuredMissingCapabilitiesAndAnswerText() {
        val parsed = LocalModelGrounding.parseAnswer(
            "Answer: Meeting.\n" +
                "Evidence: evidence:event:1\n" +
                "Missing: HAS_LOCATION: Location was not indexed; HAS_MEDIA: Photos were unavailable\n" +
                "Confidence: MEDIUM",
        )

        requireNotNull(parsed)
        assertEquals("Meeting.", parsed.answer)
        assertEquals(listOf("evidence:event:1"), parsed.evidenceIds)
        assertEquals(listOf(MemoryCapability.HAS_LOCATION, MemoryCapability.HAS_MEDIA), parsed.missingCapabilities)
        assertEquals(ConfidenceLevel.MEDIUM, parsed.confidence)
    }

    @Test
    fun rejectsDraftWhenGroundingContractOrMissingSetDoesNotMatch() {
        val missing = ai.grayin.core.model.MissingSource(
            capability = MemoryCapability.HAS_LOCATION,
            availability = ai.grayin.core.model.SourceAvailability.NOT_INDEXED,
            explanation = "Location was not indexed.",
        )
        val pack = evidencePack.copy(missingSources = listOf(missing))
        val baseDraft = LocalModelAnswerDraft(
            answer = "Meeting.",
            usedEvidenceItemIds = listOf(citedEvidence.id),
            inferenceNotes = emptyList(),
            confidence = ConfidenceLevel.MEDIUM,
            missingSources = emptyList(),
            groundingContractValid = true,
        )
        val validDraft = baseDraft.copy(missingSources = listOf(missing))

        assertNull(LocalModelGrounding.validateDraft(pack, baseDraft))
        assertEquals(validDraft, LocalModelGrounding.validateDraft(pack, validDraft))
        assertNull(
            LocalModelGrounding.validateDraft(
                pack,
                baseDraft.copy(missingSources = listOf(missing), groundingContractValid = false),
            ),
        )
    }
}
