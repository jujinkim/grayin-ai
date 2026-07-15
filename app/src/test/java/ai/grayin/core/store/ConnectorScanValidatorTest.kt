package ai.grayin.core.store

import ai.grayin.connectors.localfiles.LocalFileMemoryExtractor
import ai.grayin.connectors.localfiles.LocalFileMetadata
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.ProcessingState
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceAvailability
import ai.grayin.core.model.SourceReference
import java.time.Instant
import java.time.LocalDate
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
                    explanation = ConnectorScanIssueCode.NO_EXTRACTABLE_TEXT.defaultEnglish,
                    connectorId = "other",
                    issueCode = ConnectorScanIssueCode.NO_EXTRACTABLE_TEXT,
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
                    issueCode = ConnectorScanIssueCode.LOCAL_DOCUMENT_READ_FAILED,
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ConnectorScanValidator.validate(scan)
        }
    }

    @Test
    fun `accepts an exact stable connector scan issue`() {
        val issueCode = ConnectorScanIssueCode.NO_EXTRACTABLE_TEXT
        ConnectorScanValidator.validate(
            validScan().copy(
                missingSources = listOf(
                    MissingSource(
                        capability = MemoryCapability.HAS_TEXT,
                        availability = SourceAvailability.NOT_INDEXED,
                        explanation = issueCode.defaultEnglish,
                        connectorId = "test",
                        issueCode = issueCode,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `rejects code-less or code-mismatched scan explanations`() {
        val codeLess = MissingSource(
            capability = MemoryCapability.HAS_TEXT,
            availability = SourceAvailability.UNSUPPORTED,
            explanation = "RAW_SENTINEL",
            connectorId = "test",
        )
        val mismatched = codeLess.copy(
            issueCode = ConnectorScanIssueCode.LOCAL_DOCUMENT_READ_FAILED,
        )

        listOf(codeLess, mismatched).forEach { missing ->
            assertThrows(IllegalArgumentException::class.java) {
                ConnectorScanValidator.validate(validScan().copy(missingSources = listOf(missing)))
            }
        }
    }

    @Test
    fun `accepts a closed HMAC-only Local Files graph`() {
        ConnectorScanValidator.validate(validLocalFilesScan())
    }

    @Test
    fun `accepts canonical Local Files text and Markdown extractor output`() {
        val at = Instant.parse("2026-07-15T00:00:00Z")
        listOf(SourceKind.LOCAL_FILE, SourceKind.MARKDOWN_NOTE).forEachIndexed { index, sourceKind ->
            val extracted = LocalFileMemoryExtractor().extract(
                metadata = LocalFileMetadata(
                    identityHmac = ("%064x".format(index + 1)),
                    sourceKind = sourceKind,
                    observedAt = at,
                ),
                text = "project project meeting notes",
            )
            ConnectorScanValidator.validate(
                ConnectorScanResult(
                    connectorId = "local_files",
                    processingState = ProcessingState.COMPLETED,
                    sourceReferences = listOf(extracted.sourceReference),
                    derivedEvents = listOf(extracted.derivedEvent),
                    citations = listOf(extracted.citation),
                    replaceExistingConnectorData = true,
                    scannedAt = at,
                ),
            )
        }
    }

    @Test
    fun `rejects Local Files raw pointers hashes and file-name citations`() {
        val valid = validLocalFilesScan()
        val invalidScans = listOf(
            valid.copy(
                sourceReferences = valid.sourceReferences.map { source ->
                    source.copy(localPointer = "content://provider/private.pdf")
                },
            ),
            valid.copy(
                sourceReferences = valid.sourceReferences.map { source ->
                    source.copy(externalIdHash = "unkeyed-hash")
                },
            ),
            valid.copy(
                citations = valid.citations.map { citation -> citation.copy(label = "secret-name.pdf page 1") },
            ),
            valid.copy(replaceExistingConnectorData = false),
        )

        invalidScans.forEach { scan ->
            assertThrows(IllegalArgumentException::class.java) {
                ConnectorScanValidator.validate(scan)
            }
        }
    }

    @Test
    fun `rejects malformed or mismatched Local Files HMAC identities`() {
        val valid = validLocalFilesScan()
        val invalid = valid.copy(
            sourceReferences = valid.sourceReferences.map { source ->
                source.copy(hmacHash = "not-an-hmac")
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            ConnectorScanValidator.validate(invalid)
        }
    }

    @Test
    fun `rejects raw or open-ended Local Files event fields`() {
        val valid = validLocalFilesScan()
        val invalidScans = listOf(
            valid.copy(
                derivedEvents = valid.derivedEvents.map { event ->
                    event.copy(summary = "RAW_SENTINEL from a private PDF")
                },
            ),
            valid.copy(
                derivedEvents = valid.derivedEvents.map { event ->
                    event.copy(entities = listOf("RAW_SENTINEL"))
                },
            ),
            valid.copy(
                derivedEvents = valid.derivedEvents.map { event ->
                    event.copy(labels = listOf("local-file", "pdf-page", "private-name.pdf"))
                },
            ),
            valid.copy(
                derivedEvents = valid.derivedEvents.map { event ->
                    event.copy(keywords = listOf("valid", "raw sentinel"))
                },
            ),
        )

        invalidScans.forEach { scan ->
            assertThrows(IllegalArgumentException::class.java) {
                ConnectorScanValidator.validate(scan)
            }
        }
    }

    @Test
    fun `rejects unrelated Local Files graph sections`() {
        val valid = validLocalFilesScan()
        val sourceId = valid.sourceReferences.single().id
        val invalidScans = listOf(
            valid.copy(
                placeClusters = listOf(
                    PlaceCluster(
                        id = "place-cluster:local_files:1",
                        label = "RAW_SENTINEL",
                        sourceReferenceIds = listOf(sourceId),
                    ),
                ),
            ),
            valid.copy(
                appUsageSummaries = listOf(
                    AppUsageSummary(
                        id = "app-usage:local_files:1",
                        sourceReferenceIds = listOf(sourceId),
                        date = LocalDate.parse("2026-07-15"),
                        packageName = "raw.sentinel",
                        totalDurationMinutes = 1,
                    ),
                ),
            ),
        )

        invalidScans.forEach { scan ->
            assertThrows(IllegalArgumentException::class.java) {
                ConnectorScanValidator.validate(scan)
            }
        }
    }

    @Test
    fun `rejects inconsistent Local Files timestamps and confidence`() {
        val valid = validLocalFilesScan()
        val invalidScans = listOf(
            valid.copy(
                sourceReferences = valid.sourceReferences.map { source -> source.copy(modifiedAt = null) },
            ),
            valid.copy(
                derivedEvents = valid.derivedEvents.map { event -> event.copy(startedAt = null) },
            ),
            valid.copy(
                citations = valid.citations.map { citation -> citation.copy(confidence = ConfidenceLevel.HIGH) },
            ),
            valid.copy(
                derivedEvents = valid.derivedEvents.map { event -> event.copy(confidence = ConfidenceLevel.UNKNOWN) },
                citations = valid.citations.map { citation -> citation.copy(confidence = ConfidenceLevel.UNKNOWN) },
            ),
        )

        invalidScans.forEach { scan ->
            assertThrows(IllegalArgumentException::class.java) {
                ConnectorScanValidator.validate(scan)
            }
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

    private fun validLocalFilesScan(): ConnectorScanResult {
        val at = Instant.parse("2026-07-15T00:00:00Z")
        val hmac = "f".repeat(64)
        val sourceId = "source:local_files:$hmac"
        val eventId = "event:local_files:$hmac"
        val citationId = "citation:local_files:$hmac"
        return ConnectorScanResult(
            connectorId = "local_files",
            processingState = ProcessingState.COMPLETED,
            sourceReferences = listOf(
                SourceReference(
                    id = sourceId,
                    connectorId = "local_files",
                    sourceKind = SourceKind.PDF_PAGE,
                    hmacHash = hmac,
                    observedAt = at,
                    modifiedAt = at,
                    sensitivity = SensitivityLevel.HIGH,
                ),
            ),
            derivedEvents = listOf(
                DerivedMemoryEvent(
                    id = eventId,
                    kind = DerivedMemoryEventKind.LOCAL_FILE_INDEX,
                    sourceReferenceIds = listOf(sourceId),
                    summary = "PDF page 1 indexed with 3 non-empty line(s). No stable keyword signals found.",
                    startedAt = at,
                    labels = listOf("local-file", "pdf-page", "embedded-text"),
                    confidence = ConfidenceLevel.MEDIUM,
                    sensitivity = SensitivityLevel.HIGH,
                    citationIds = listOf(citationId),
                    createdAt = at,
                ),
            ),
            citations = listOf(
                MemoryCitation(
                    id = citationId,
                    sourceReferenceId = sourceId,
                    derivedMemoryEventId = eventId,
                    label = "PDF page 1",
                    observedAt = at,
                    confidence = ConfidenceLevel.MEDIUM,
                ),
            ),
            replaceExistingConnectorData = true,
            scannedAt = at,
        )
    }
}
