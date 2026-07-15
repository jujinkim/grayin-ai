package ai.grayin.core.store

import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceReference
import java.time.Instant
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectorScanValidatorTest {
    @Test
    fun `accepts a self-contained connector graph`() {
        ConnectorScanValidator.validate(validScan())
    }

    @Test
    fun `rejects a source owned by another connector`() {
        val scan = validScan().let { valid ->
            valid.copy(
                sourceReferences = valid.sourceReferences.map { source ->
                    source.copy(connectorId = "other")
                },
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            ConnectorScanValidator.validate(scan)
        }
    }

    @Test
    fun `rejects a derived event with a dangling citation`() {
        val scan = validScan().let { valid ->
            valid.copy(
                derivedEvents = valid.derivedEvents.map { event ->
                    event.copy(citationIds = listOf("citation:test:missing"))
                },
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            ConnectorScanValidator.validate(scan)
        }
    }

    @Test
    fun `rejects duplicate IDs before any database write`() {
        val scan = validScan().let { valid ->
            valid.copy(sourceReferences = valid.sourceReferences + valid.sourceReferences.first())
        }

        assertThrows(IllegalArgumentException::class.java) {
            ConnectorScanValidator.validate(scan)
        }
    }

    @Test
    fun `rejects a citation cross-linked to another event`() {
        val valid = validScan()
        val secondEventId = "event:test:2"
        val scan = valid.copy(
            derivedEvents = valid.derivedEvents + valid.derivedEvents.first().copy(
                id = secondEventId,
                citationIds = emptyList(),
            ),
            citations = valid.citations.map { citation ->
                citation.copy(derivedMemoryEventId = secondEventId)
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            ConnectorScanValidator.validate(scan)
        }
    }

    @Test
    fun `rejects a missing-source status owned by another connector`() {
        val scan = validScan().copy(
            missingSources = listOf(
                MissingSource(
                    capability = MemoryCapability.HAS_TEXT,
                    availability = SourceAvailability.UNSUPPORTED,
                    explanation = "stable-code",
                    connectorId = "other",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ConnectorScanValidator.validate(scan)
        }
    }

    @Test
    fun `rejects runtime-like multiline missing-source detail`() {
        val scan = validScan().copy(
            missingSources = listOf(
                MissingSource(
                    capability = MemoryCapability.HAS_TEXT,
                    availability = SourceAvailability.UNSUPPORTED,
                    explanation = "parser failed\nraw detail",
                    connectorId = "test",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ConnectorScanValidator.validate(scan)
        }
    }

    private fun validScan(): ConnectorScanResult {
        val at = Instant.parse("2026-07-15T00:00:00Z")
        val sourceId = "source:test:1"
        val eventId = "event:test:1"
        val citationId = "citation:test:1"
        return ConnectorScanResult(
            connectorId = "test",
            processingState = ProcessingState.COMPLETED,
            sourceReferences = listOf(
                SourceReference(
                    id = sourceId,
                    connectorId = "test",
                    sourceKind = SourceKind.LOCAL_FILE,
                    observedAt = at,
                    sensitivity = SensitivityLevel.HIGH,
                ),
            ),
            derivedEvents = listOf(
                DerivedMemoryEvent(
                    id = eventId,
                    kind = DerivedMemoryEventKind.LOCAL_FILE_INDEX,
                    sourceReferenceIds = listOf(sourceId),
                    summary = "Derived test summary.",
                    confidence = ConfidenceLevel.MEDIUM,
                    citationIds = listOf(citationId),
                    createdAt = at,
                ),
            ),
            citations = listOf(
                MemoryCitation(
                    id = citationId,
                    sourceReferenceId = sourceId,
                    derivedMemoryEventId = eventId,
                    label = "Test citation",
                    observedAt = at,
                    confidence = ConfidenceLevel.MEDIUM,
                ),
            ),
            scannedAt = at,
        )
    }
}
