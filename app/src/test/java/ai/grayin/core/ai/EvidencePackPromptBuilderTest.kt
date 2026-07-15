package ai.grayin.core.ai

import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.EvidenceItem
import ai.grayin.core.model.EvidencePack
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.SourceAvailability
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidencePackPromptBuilderTest {
    @Test
    fun promptMatchesModelTrainingGoldenContract() {
        val fixture = Json.parseToJsonElement(
            repositoryFile("model-training/contracts/evidence_pack_prompt_v1_fixture.json").readText(),
        ).jsonObject
        val citations = fixture.getValue("citations").jsonArray.map { element ->
            val citation = element.jsonObject
            MemoryCitation(
                id = citation.getValue("id").jsonPrimitive.content,
                sourceReferenceId = citation.getValue("source_reference_id").jsonPrimitive.content,
                derivedMemoryEventId = citation.getValue("derived_memory_event_id").jsonPrimitive.content,
                label = citation.getValue("label").jsonPrimitive.content,
                confidence = ConfidenceLevel.valueOf(citation.getValue("confidence").jsonPrimitive.content),
            )
        }
        val evidenceItems = fixture.getValue("evidence_items").jsonArray.map { element ->
            val evidence = element.jsonObject
            EvidenceItem(
                id = evidence.getValue("id").jsonPrimitive.content,
                derivedMemoryEventId = evidence.getValue("derived_memory_event_id").jsonPrimitive.content,
                summary = evidence.getValue("summary").jsonPrimitive.content,
                eventKind = DerivedMemoryEventKind.valueOf(evidence.getValue("event_kind").jsonPrimitive.content),
                occurredAt = Instant.parse(evidence.getValue("occurred_at").jsonPrimitive.content),
                confidence = ConfidenceLevel.valueOf(evidence.getValue("confidence").jsonPrimitive.content),
                citationIds = evidence.getValue("citation_ids").jsonArray.map { it.jsonPrimitive.content },
                capabilities = evidence.getValue("capabilities").jsonArray
                    .map { MemoryCapability.valueOf(it.jsonPrimitive.content) }
                    .toCollection(linkedSetOf()),
            )
        }
        val missingSources = fixture.getValue("missing_sources").jsonArray.map { element ->
            val missing = element.jsonObject
            MissingSource(
                capability = MemoryCapability.valueOf(missing.getValue("capability").jsonPrimitive.content),
                availability = SourceAvailability.valueOf(missing.getValue("availability").jsonPrimitive.content),
                explanation = missing.getValue("explanation").jsonPrimitive.content,
                connectorId = missing.getValue("connector_id").jsonPrimitive.content,
            )
        }
        val pack = EvidencePack(
            id = fixture.getValue("id").jsonPrimitive.content,
            query = fixture.getValue("query").jsonPrimitive.content,
            generatedAt = Instant.parse(fixture.getValue("generated_at").jsonPrimitive.content),
            evidenceItems = evidenceItems,
            citations = citations,
            missingSources = missingSources,
        )

        val prompt = EvidencePackPromptBuilder.build(pack)

        assertEquals(
            repositoryFile("model-training/contracts/evidence_pack_prompt_v1_golden.txt").readText(),
            prompt,
        )
    }

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

    private fun repositoryFile(relativePath: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is unavailable" }
        var current: File? = File(userDir).absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile
        }
        error("Repository file not found: $relativePath")
    }
}
