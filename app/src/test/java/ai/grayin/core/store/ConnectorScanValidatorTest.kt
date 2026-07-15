package ai.grayin.core.store

import ai.grayin.connectors.localfiles.LocalFileMemoryExtractor
import ai.grayin.connectors.localfiles.LocalFileMetadata
import ai.grayin.core.connector.ConnectorScanResult
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.ConnectorScanIssueCode
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MemoryCapability
import ai.grayin.core.model.MissingSource
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
    fun `accepts all five current live connector record shapes`() {
        validCurrentConnectorScans().forEach(ConnectorScanValidator::validate)
    }

    @Test
    fun `rejects app usage aggregate output from a schema v8 scan`() {
        val valid = validCurrentConnectorScans().single { scan -> scan.connectorId == "app_usage" }
        val sourceId = valid.sourceReferences.single().id
        val invalid = valid.copy(
            appUsageSummaries = listOf(
                AppUsageSummary(
                    id = "app-usage:app_usage:legacy",
                    sourceReferenceIds = listOf(sourceId),
                    date = LocalDate.parse("2026-07-15"),
                    packageName = "com.example.app",
                    totalDurationMinutes = 30,
                    confidence = ConfidenceLevel.MEDIUM,
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ConnectorScanValidator.validate(invalid)
        }
    }

    @Test
    fun `accepts a literal untitled calendar title emitted with high confidence`() {
        val scan = validScan().copy(
            derivedEvents = validScan().derivedEvents.map { event ->
                event.copy(
                    summary = "Calendar event indexed: Untitled calendar event, from ${event.startedAt}.",
                )
            },
            citations = validScan().citations.map { citation ->
                citation.copy(label = "Calendar: Untitled calendar event")
            },
        )

        ConnectorScanValidator.validate(scan)
    }

    @Test
    fun `stored snapshot accepts an accumulated location cluster while a live scan does not`() {
        val first = validCurrentConnectorScans().single { scan -> scan.connectorId == "location" }
        val second = currentScan(
            connectorId = "location",
            hash = "6".repeat(32),
            sourceKind = SourceKind.LOCATION,
            sourcePointer = null,
            eventKind = DerivedMemoryEventKind.PLACE_VISIT,
            summary = "Location sample indexed near Seoul at ${first.scannedAt}.",
            keywords = listOf("location", "place", "gps", "Seoul"),
            labels = listOf("location", "place-visit", "gps", "Seoul"),
            citationLabel = "Location sample: Seoul",
        )
        val accumulated = first.copy(
            sourceReferences = (first.sourceReferences + second.sourceReferences).map { source ->
                source.copy(localPointer = null)
            },
            derivedEvents = first.derivedEvents + second.derivedEvents,
            citations = first.citations + second.citations,
            placeClusters = listOf(
                first.placeClusters.single().copy(
                    visitCount = 2,
                    sourceReferenceIds = listOf(
                        first.sourceReferences.single().id,
                        second.sourceReferences.single().id,
                    ),
                ),
            ),
        )

        ConnectorScanValidator.validateStoredSnapshot(accumulated)
        assertThrows(IllegalArgumentException::class.java) {
            ConnectorScanValidator.validate(accumulated)
        }
    }

    @Test
    fun `rejects legacy open fields at the live connector boundary`() {
        val scans = validCurrentConnectorScans().associateBy(ConnectorScanResult::connectorId)
        val invalid = listOf(
            scans.getValue("location").let { scan ->
                scan.copy(
                    derivedEvents = scan.derivedEvents.map { event ->
                        event.copy(keywords = event.keywords.take(2) + "vendor-provider")
                    },
                )
            },
            scans.getValue("photos").let { scan ->
                scan.copy(
                    derivedEvents = scan.derivedEvents.map { event ->
                        event.copy(summary = "Photo filename indexed: secret.jpg")
                    },
                )
            },
            scans.getValue("calendar").let { scan ->
                scan.copy(
                    sourceReferences = scan.sourceReferences.map { source ->
                        source.copy(sourceAppIdentifier = "Private calendar name")
                    },
                )
            },
            scans.getValue("notification").let { scan ->
                scan.copy(
                    derivedEvents = scan.derivedEvents.map { event ->
                        event.copy(labels = event.labels + "provider-private-category")
                    },
                )
            },
            scans.getValue("app_usage").let { scan ->
                scan.copy(
                    citations = scan.citations.map { citation ->
                        citation.copy(label = "App usage: ${"x".repeat(257)}")
                    },
                )
            },
        )

        invalid.forEach { scan ->
            assertThrows(IllegalArgumentException::class.java) {
                ConnectorScanValidator.validate(scan)
            }
        }
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
                    event.copy(citationIds = listOf("citation:calendar:missing"))
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
    fun `rejects records that cannot pass the encrypted transfer boundary`() {
        val valid = validScan()
        val invalidScans = listOf(
            valid.copy(
                derivedEvents = valid.derivedEvents.map { event ->
                    event.copy(summary = "x".repeat(16 * 1024 + 1))
                },
            ),
            valid.copy(
                citations = valid.citations.map { citation ->
                    citation.copy(label = "invalid\u0000label")
                },
            ),
            valid.copy(
                sourceReferences = valid.sourceReferences.map { source ->
                    source.copy(sourceKind = SourceKind.PHOTO)
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
    fun `rejects oversized or malformed live source pointers`() {
        val valid = validScan()
        listOf(
            "x".repeat(4 * 1024 + 1),
            "content://calendar/event\nraw",
            "content://calendar/\uD800",
        ).forEach { pointer ->
            assertThrows(IllegalArgumentException::class.java) {
                ConnectorScanValidator.validate(
                    valid.copy(
                        sourceReferences = valid.sourceReferences.map { source ->
                            source.copy(localPointer = pointer)
                        },
                    ),
                )
            }
        }
    }

    @Test
    fun `rejects scan-only connector IDs that cannot cross the transfer boundary`() {
        val at = Instant.parse("2026-07-15T00:00:00Z")
        listOf("x".repeat(65), "calendar\nprovider").forEach { connectorId ->
            assertThrows(IllegalArgumentException::class.java) {
                ConnectorScanValidator.validate(
                    ConnectorScanResult(
                        connectorId = connectorId,
                        processingState = ProcessingState.COMPLETED,
                        scannedAt = at,
                    ),
                )
            }
        }
    }

    @Test
    fun `rejects empty or reversed scan scopes`() {
        val at = Instant.parse("2026-07-15T00:00:00Z")
        listOf(at, at.minusSeconds(1)).forEach { until ->
            assertThrows(IllegalArgumentException::class.java) {
                ConnectorScanValidator.validate(
                    validScan().copy(
                        scopeFrom = at,
                        scopeUntil = until,
                    ),
                )
            }
        }
    }

    @Test
    fun `rejects a citation cross-linked to another event`() {
        val valid = validScan()
        val secondEventId = "event:calendar:2"
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
                    connectorId = "calendar",
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
                        connectorId = "calendar",
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
            connectorId = "calendar",
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
        val hash = "1".repeat(32)
        val sourceId = "source:calendar:$hash"
        val eventId = "event:calendar:$hash"
        val citationId = "citation:calendar:$hash"
        return ConnectorScanResult(
            connectorId = "calendar",
            processingState = ProcessingState.COMPLETED,
            sourceReferences = listOf(
                SourceReference(
                    id = sourceId,
                    connectorId = "calendar",
                    sourceKind = SourceKind.CALENDAR,
                    localPointer = "content://com.android.calendar/events/1",
                    externalIdHash = hash,
                    sourceAppIdentifier = "android-calendar",
                    observedAt = at,
                    modifiedAt = at,
                    sensitivity = SensitivityLevel.HIGH,
                ),
            ),
            derivedEvents = listOf(
                DerivedMemoryEvent(
                    id = eventId,
                    kind = DerivedMemoryEventKind.CALENDAR_EVENT,
                    sourceReferenceIds = listOf(sourceId),
                    summary = "Calendar event indexed: Team meeting, from $at.",
                    startedAt = at,
                    keywords = listOf("team", "meeting"),
                    labels = listOf("calendar"),
                    confidence = ConfidenceLevel.HIGH,
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
                    label = "Calendar: Team meeting",
                    observedAt = at,
                    confidence = ConfidenceLevel.HIGH,
                ),
            ),
            scannedAt = at,
        )
    }

    private fun validCurrentConnectorScans(): List<ConnectorScanResult> {
        val at = Instant.parse("2026-07-15T00:00:00Z")
        val usageEndedAt = at.plusSeconds(30 * 60)
        return listOf(
            currentScan(
                connectorId = "location",
                hash = "2".repeat(32),
                sourceKind = SourceKind.LOCATION,
                sourcePointer = "location-provider:gps",
                eventKind = DerivedMemoryEventKind.PLACE_VISIT,
                summary = "Location sample indexed near Seoul at $at.",
                keywords = listOf("location", "place", "gps", "Seoul"),
                labels = listOf("location", "place-visit", "gps", "Seoul"),
                citationLabel = "Location sample: Seoul",
            ).let { scan ->
                val sourceId = scan.sourceReferences.single().id
                scan.copy(
                    placeClusters = listOf(
                        PlaceCluster(
                            id = "place-cluster:location:${"a".repeat(32)}",
                            regionLabel = "Seoul",
                            centroidLatitude = 37.5,
                            centroidLongitude = 127.0,
                            radiusMeters = 50.0,
                            firstSeenAt = at,
                            lastSeenAt = at,
                            visitCount = 1,
                            sourceReferenceIds = listOf(sourceId),
                            confidence = ConfidenceLevel.MEDIUM,
                        ),
                    ),
                )
            },
            currentScan(
                connectorId = "photos",
                hash = "3".repeat(32),
                sourceKind = SourceKind.PHOTO,
                sourcePointer = "content://media/external/images/media/3",
                eventKind = DerivedMemoryEventKind.PHOTO_INDEX,
                summary = "Photo metadata indexed at $at. Type: image/jpeg. Dimensions: 1920x1080.",
                keywords = listOf("photo", "jpeg", "landscape"),
                labels = listOf("photo", "jpeg", "landscape"),
                citationLabel = "Photo metadata",
            ),
            validScan(),
            currentScan(
                connectorId = "notification",
                hash = "4".repeat(32),
                sourceKind = SourceKind.NOTIFICATION,
                sourceAppIdentifier = "com.example.pay",
                eventKind = DerivedMemoryEventKind.PAYMENT,
                summary = "Notification-derived payment signal from com.example.pay at $at.",
                keywords = listOf("example", "pay", "payment", "notification"),
                labels = listOf("notification", "payment", "status"),
                entities = listOf("com.example.pay"),
                citationLabel = "Notification signal: com.example.pay",
                sensitivity = SensitivityLevel.VERY_HIGH,
            ),
            currentScan(
                connectorId = "app_usage",
                hash = "5".repeat(32),
                sourceKind = SourceKind.APP_USAGE,
                sourceAppIdentifier = "com.example.app",
                eventKind = DerivedMemoryEventKind.APP_USAGE,
                summary = "App usage indexed: Example used for about 30 minute(s) between $at and $usageEndedAt.",
                keywords = listOf("example"),
                labels = listOf("app-usage", "medium-session"),
                entities = listOf("com.example.app"),
                citationLabel = "App usage: Example",
                sensitivity = SensitivityLevel.VERY_HIGH,
                endedAt = usageEndedAt,
            ),
        )
    }

    private fun currentScan(
        connectorId: String,
        hash: String,
        sourceKind: SourceKind,
        eventKind: DerivedMemoryEventKind,
        summary: String,
        keywords: List<String>,
        labels: List<String>,
        citationLabel: String,
        sourcePointer: String? = null,
        sourceAppIdentifier: String? = null,
        entities: List<String> = emptyList(),
        sensitivity: SensitivityLevel = SensitivityLevel.HIGH,
        endedAt: Instant? = null,
    ): ConnectorScanResult {
        val at = Instant.parse("2026-07-15T00:00:00Z")
        val sourceId = "source:$connectorId:$hash"
        val eventId = "event:$connectorId:$hash"
        val citationId = "citation:$connectorId:$hash"
        return ConnectorScanResult(
            connectorId = connectorId,
            processingState = ProcessingState.COMPLETED,
            sourceReferences = listOf(
                SourceReference(
                    id = sourceId,
                    connectorId = connectorId,
                    sourceKind = sourceKind,
                    localPointer = sourcePointer,
                    externalIdHash = hash,
                    sourceAppIdentifier = sourceAppIdentifier,
                    observedAt = at,
                    modifiedAt = endedAt ?: at,
                    sensitivity = sensitivity,
                ),
            ),
            derivedEvents = listOf(
                DerivedMemoryEvent(
                    id = eventId,
                    kind = eventKind,
                    sourceReferenceIds = listOf(sourceId),
                    summary = summary,
                    startedAt = at,
                    endedAt = endedAt,
                    keywords = keywords,
                    labels = labels,
                    entities = entities,
                    confidence = ConfidenceLevel.MEDIUM,
                    sensitivity = sensitivity,
                    citationIds = listOf(citationId),
                    createdAt = at,
                ),
            ),
            citations = listOf(
                MemoryCitation(
                    id = citationId,
                    sourceReferenceId = sourceId,
                    derivedMemoryEventId = eventId,
                    label = citationLabel,
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
