package ai.grayin.core.transfer

import ai.grayin.core.connector.ConnectorScanStatus
import ai.grayin.core.model.AppUsageSummary
import ai.grayin.core.model.ConfidenceLevel
import ai.grayin.core.model.DailyMemorySummary
import ai.grayin.core.model.DerivedMemoryEvent
import ai.grayin.core.model.DerivedMemoryEventKind
import ai.grayin.core.model.MemoryCitation
import ai.grayin.core.model.MissingSource
import ai.grayin.core.model.PlaceCluster
import ai.grayin.core.model.SensitivityLevel
import ai.grayin.core.model.SourceKind
import ai.grayin.core.model.SourceReference
import ai.grayin.core.store.LocalMemorySnapshot
import java.time.Instant
import java.time.LocalDate
import kotlin.text.Charsets.UTF_8

object TransferBounds {
    const val MAX_PLAINTEXT_BYTES = 32 * 1024 * 1024
    const val MAX_SOURCE_REFERENCES = 50_000
    const val MAX_DERIVED_EVENTS = 50_000
    const val MAX_CITATIONS = 50_000
    const val MAX_DAILY_SUMMARIES = 10_000
    const val MAX_PLACE_CLUSTERS = 10_000
    const val MAX_APP_USAGE_SUMMARIES = 50_000
    const val MAX_CONNECTOR_SCAN_STATUSES = 64
    const val MAX_TOTAL_RECORDS = 200_000
    const val MAX_ID_BYTES = 256
    const val MAX_CONNECTOR_ID_BYTES = 64
    const val MAX_SUMMARY_BYTES = 16 * 1024
    const val MAX_LABEL_BYTES = 1_024
    const val MAX_VALUE_BYTES = 512
    const val MAX_LIST_ITEMS = 64
    const val MAX_AGGREGATE_REFERENCES = 4_096
}

internal object TransferSnapshotValidator {
    val trustedConnectorIds: Set<String> = setOf(
        "location",
        "photos",
        "calendar",
        "notification",
        "app_usage",
        "local_files",
    )

    fun detached(snapshot: LocalMemorySnapshot): LocalMemorySnapshot {
        return snapshot.copy(
            sourceReferences = snapshot.sourceReferences.map { source ->
                source.copy(localPointer = null)
            }.sortedBy(SourceReference::id),
            derivedMemoryEvents = snapshot.derivedMemoryEvents.sortedBy(DerivedMemoryEvent::id),
            citations = snapshot.citations.sortedBy(MemoryCitation::id),
            dailySummaries = snapshot.dailySummaries.sortedBy(DailyMemorySummary::id),
            placeClusters = snapshot.placeClusters.sortedBy(PlaceCluster::id),
            appUsageSummaries = snapshot.appUsageSummaries.sortedBy(AppUsageSummary::id),
            connectorScanStatuses = snapshot.connectorScanStatuses.sortedBy(ConnectorScanStatus::connectorId),
        )
    }

    fun validate(payload: TransferPayload) {
        require(payload.createdAt in MIN_INSTANT..MAX_INSTANT) {
            "Transfer creation time is outside the supported range."
        }
        require(payload.producer.applicationId == APPLICATION_ID) {
            "Transfer producer is not supported."
        }
        require(payload.producer.versionCode in 1..Int.MAX_VALUE.toLong()) {
            "Transfer producer version is invalid."
        }
        require(payload.producer.storeSchemaVersion in 1..MAX_STORE_SCHEMA_VERSION) {
            "Transfer store schema version is invalid."
        }
        validate(payload.snapshot)
    }

    fun validate(snapshot: LocalMemorySnapshot) {
        validateCounts(snapshot)
        requireDistinctIds("source reference", snapshot.sourceReferences.map(SourceReference::id))
        requireDistinctIds("derived event", snapshot.derivedMemoryEvents.map(DerivedMemoryEvent::id))
        requireDistinctIds("citation", snapshot.citations.map(MemoryCitation::id))
        requireDistinctIds("daily summary", snapshot.dailySummaries.map(DailyMemorySummary::id))
        requireDistinctIds("place cluster", snapshot.placeClusters.map(PlaceCluster::id))
        requireDistinctIds("app usage summary", snapshot.appUsageSummaries.map(AppUsageSummary::id))
        requireDistinctIds("connector scan status", snapshot.connectorScanStatuses.map(ConnectorScanStatus::connectorId))

        snapshot.sourceReferences.forEach(::validateSourceReference)
        snapshot.derivedMemoryEvents.forEach(::validateDerivedEvent)
        snapshot.citations.forEach(::validateCitation)
        snapshot.dailySummaries.forEach(::validateDailySummary)
        snapshot.placeClusters.forEach(::validatePlaceCluster)
        snapshot.appUsageSummaries.forEach(::validateAppUsageSummary)
        snapshot.connectorScanStatuses.forEach(::validateConnectorScanStatus)
        validateGraph(snapshot)
        validateLocalFilesGraph(snapshot)
    }

    private fun validateCounts(snapshot: LocalMemorySnapshot) {
        require(snapshot.sourceReferences.size <= TransferBounds.MAX_SOURCE_REFERENCES)
        require(snapshot.derivedMemoryEvents.size <= TransferBounds.MAX_DERIVED_EVENTS)
        require(snapshot.citations.size <= TransferBounds.MAX_CITATIONS)
        require(snapshot.dailySummaries.size <= TransferBounds.MAX_DAILY_SUMMARIES)
        require(snapshot.placeClusters.size <= TransferBounds.MAX_PLACE_CLUSTERS)
        require(snapshot.appUsageSummaries.size <= TransferBounds.MAX_APP_USAGE_SUMMARIES)
        require(snapshot.connectorScanStatuses.size <= TransferBounds.MAX_CONNECTOR_SCAN_STATUSES)
        val total = snapshot.sourceReferences.size.toLong() +
            snapshot.derivedMemoryEvents.size +
            snapshot.citations.size +
            snapshot.dailySummaries.size +
            snapshot.placeClusters.size +
            snapshot.appUsageSummaries.size +
            snapshot.connectorScanStatuses.size
        require(total <= TransferBounds.MAX_TOTAL_RECORDS)
    }

    private fun validateSourceReference(source: SourceReference) {
        requireId(source.id)
        requireConnectorId(source.connectorId)
        require(source.connectorId in trustedConnectorIds)
        require(source.id.startsWith("source:${source.connectorId}:"))
        require(source.sourceKind in SOURCE_KINDS_BY_CONNECTOR.getValue(source.connectorId))
        require(source.localPointer == null) { "Transfer source references must be detached." }
        source.externalIdHash?.let { requireValue(it, TransferBounds.MAX_VALUE_BYTES) }
        source.hmacHash?.let { requireValue(it, TransferBounds.MAX_VALUE_BYTES) }
        source.sourceAppIdentifier?.let { requireText(it, TransferBounds.MAX_VALUE_BYTES) }
        source.observedAt?.let(::requireInstant)
        source.modifiedAt?.let(::requireInstant)
    }

    private fun validateDerivedEvent(event: DerivedMemoryEvent) {
        requireId(event.id)
        val connectorId = connectorFromScopedId("event", event.id)
        require(connectorId != null)
        require(event.kind in EVENT_KINDS_BY_CONNECTOR.getValue(connectorId))
        requireText(event.summary, TransferBounds.MAX_SUMMARY_BYTES)
        requireReferenceList(event.sourceReferenceIds, TransferBounds.MAX_LIST_ITEMS, requireNonEmpty = true)
        requireReferenceList(event.citationIds, TransferBounds.MAX_LIST_ITEMS, requireNonEmpty = false)
        requireTextList(event.keywords, TransferBounds.MAX_LIST_ITEMS, TransferBounds.MAX_VALUE_BYTES)
        requireTextList(event.labels, TransferBounds.MAX_LIST_ITEMS, TransferBounds.MAX_VALUE_BYTES)
        requireTextList(event.entities, TransferBounds.MAX_LIST_ITEMS, TransferBounds.MAX_VALUE_BYTES)
        event.startedAt?.let(::requireInstant)
        event.endedAt?.let(::requireInstant)
        event.startedAt?.let { startedAt ->
            event.endedAt?.let { endedAt -> require(!endedAt.isBefore(startedAt)) }
        }
        requireInstant(event.createdAt)
    }

    private fun validateCitation(citation: MemoryCitation) {
        requireId(citation.id)
        require(connectorFromScopedId("citation", citation.id) != null)
        requireId(citation.sourceReferenceId)
        citation.derivedMemoryEventId?.let(::requireId)
        requireText(citation.label, TransferBounds.MAX_LABEL_BYTES)
        citation.observedAt?.let(::requireInstant)
    }

    private fun validateDailySummary(summary: DailyMemorySummary) {
        requireId(summary.id)
        requireDate(summary.date)
        requireText(summary.summary, TransferBounds.MAX_SUMMARY_BYTES)
        requireReferenceList(
            summary.derivedMemoryEventIds,
            TransferBounds.MAX_AGGREGATE_REFERENCES,
            requireNonEmpty = false,
        )
        requireReferenceList(
            summary.placeClusterIds,
            TransferBounds.MAX_AGGREGATE_REFERENCES,
            requireNonEmpty = false,
        )
        requireReferenceList(
            summary.appUsageSummaryIds,
            TransferBounds.MAX_AGGREGATE_REFERENCES,
            requireNonEmpty = false,
        )
        require(summary.missingSources.size <= ConnectorScanStatus.MAX_MISSING_SOURCES)
        summary.missingSources.forEach { missing -> validateMissingSource(missing, requireStableCode = false) }
        require(summary.missingSources.distinctBy(::missingSourceIdentity).size == summary.missingSources.size)
    }

    private fun validatePlaceCluster(cluster: PlaceCluster) {
        requireId(cluster.id)
        require(cluster.id.startsWith("place-cluster:location:"))
        cluster.label?.let { requireText(it, TransferBounds.MAX_LABEL_BYTES) }
        cluster.regionLabel?.let { requireText(it, TransferBounds.MAX_LABEL_BYTES) }
        require((cluster.centroidLatitude == null) == (cluster.centroidLongitude == null))
        cluster.centroidLatitude?.let { latitude ->
            require(latitude.isFinite() && latitude in -90.0..90.0)
        }
        cluster.centroidLongitude?.let { longitude ->
            require(longitude.isFinite() && longitude in -180.0..180.0)
        }
        cluster.radiusMeters?.let { radius ->
            require(radius.isFinite() && radius in 0.0..MAX_PLACE_RADIUS_METERS)
        }
        cluster.firstSeenAt?.let(::requireInstant)
        cluster.lastSeenAt?.let(::requireInstant)
        cluster.firstSeenAt?.let { first ->
            cluster.lastSeenAt?.let { last -> require(!last.isBefore(first)) }
        }
        require(cluster.visitCount in 0..MAX_VISIT_COUNT)
        requireReferenceList(
            cluster.sourceReferenceIds,
            TransferBounds.MAX_AGGREGATE_REFERENCES,
            requireNonEmpty = false,
        )
    }

    private fun validateAppUsageSummary(summary: AppUsageSummary) {
        requireId(summary.id)
        require(summary.id.startsWith("app-usage:app_usage:"))
        requireReferenceList(
            summary.sourceReferenceIds,
            TransferBounds.MAX_AGGREGATE_REFERENCES,
            requireNonEmpty = true,
        )
        requireDate(summary.date)
        requireValue(summary.packageName, MAX_PACKAGE_NAME_BYTES)
        require(PACKAGE_NAME.matches(summary.packageName))
        summary.appAlias?.let { requireText(it, TransferBounds.MAX_LABEL_BYTES) }
        require(summary.totalDurationMinutes in 0..MAX_USAGE_DURATION_MINUTES)
        summary.launchCount?.let { launchCount -> require(launchCount in 0..MAX_LAUNCH_COUNT) }
        requireTextList(
            summary.activeTimeBucketLabels,
            TransferBounds.MAX_LIST_ITEMS,
            TransferBounds.MAX_VALUE_BYTES,
        )
    }

    private fun validateConnectorScanStatus(status: ConnectorScanStatus) {
        requireConnectorId(status.connectorId)
        require(status.connectorId in trustedConnectorIds)
        status.scopeFrom?.let(::requireInstant)
        status.scopeUntil?.let(::requireInstant)
        status.scopeFrom?.let { from -> status.scopeUntil?.let { until -> require(from.isBefore(until)) } }
        requireInstant(status.scannedAt)
        require(status.missingSources.size <= ConnectorScanStatus.MAX_MISSING_SOURCES)
        status.missingSources.forEach { missing ->
            validateMissingSource(missing, requireStableCode = true)
            require(missing.connectorId == null || missing.connectorId == status.connectorId)
        }
        require(status.missingSources.distinctBy(::missingSourceIdentity).size == status.missingSources.size)
    }

    private fun validateMissingSource(missing: MissingSource, requireStableCode: Boolean) {
        requireText(missing.explanation, TransferBounds.MAX_LABEL_BYTES)
        missing.connectorId?.let { connectorId ->
            requireConnectorId(connectorId)
            require(connectorId in trustedConnectorIds)
        }
        if (requireStableCode) require(missing.issueCode != null)
        missing.issueCode?.let { issueCode -> require(missing.explanation == issueCode.defaultEnglish) }
    }

    private fun validateGraph(snapshot: LocalMemorySnapshot) {
        val sources = snapshot.sourceReferences.associateBy(SourceReference::id)
        val events = snapshot.derivedMemoryEvents.associateBy(DerivedMemoryEvent::id)
        val citations = snapshot.citations.associateBy(MemoryCitation::id)
        val clusters = snapshot.placeClusters.associateBy(PlaceCluster::id)
        val usageSummaries = snapshot.appUsageSummaries.associateBy(AppUsageSummary::id)

        snapshot.derivedMemoryEvents.forEach { event ->
            val connectorId = checkNotNull(connectorFromScopedId("event", event.id))
            event.sourceReferenceIds.forEach { sourceId ->
                require(sources[sourceId]?.connectorId == connectorId)
            }
            event.citationIds.forEach { citationId ->
                val citation = requireNotNull(citations[citationId])
                require(citation.derivedMemoryEventId == event.id)
                require(citation.sourceReferenceId in event.sourceReferenceIds)
            }
        }
        snapshot.citations.forEach { citation ->
            val source = requireNotNull(sources[citation.sourceReferenceId])
            val connectorId = checkNotNull(connectorFromScopedId("citation", citation.id))
            require(source.connectorId == connectorId)
            citation.derivedMemoryEventId?.let { eventId ->
                val event = requireNotNull(events[eventId])
                require(citation.id in event.citationIds)
                require(citation.sourceReferenceId in event.sourceReferenceIds)
            }
        }
        snapshot.placeClusters.forEach { cluster ->
            val connectorId = checkNotNull(connectorFromScopedId("place-cluster", cluster.id))
            cluster.sourceReferenceIds.forEach { sourceId ->
                require(sources[sourceId]?.connectorId == connectorId)
            }
        }
        snapshot.appUsageSummaries.forEach { summary ->
            summary.sourceReferenceIds.forEach { sourceId ->
                require(sources[sourceId]?.connectorId == APP_USAGE_CONNECTOR_ID)
            }
        }
        snapshot.dailySummaries.forEach { summary ->
            require(summary.derivedMemoryEventIds.all(events::containsKey))
            require(summary.placeClusterIds.all(clusters::containsKey))
            require(summary.appUsageSummaryIds.all(usageSummaries::containsKey))
        }
    }

    private fun validateLocalFilesGraph(snapshot: LocalMemorySnapshot) {
        val sources = snapshot.sourceReferences.filter { it.connectorId == LOCAL_FILES_CONNECTOR_ID }
        val events = snapshot.derivedMemoryEvents.filter { it.id.startsWith("event:$LOCAL_FILES_CONNECTOR_ID:") }
        val citations = snapshot.citations.filter { it.id.startsWith("citation:$LOCAL_FILES_CONNECTOR_ID:") }
        require(sources.size == events.size && events.size == citations.size && sources.size <= MAX_LOCAL_FILE_ROWS)
        val eventsById = events.associateBy(DerivedMemoryEvent::id)
        val citationsById = citations.associateBy(MemoryCitation::id)
        sources.forEach { source ->
            val hmac = source.hmacHash
            require(
                source.sourceKind in LOCAL_FILE_SOURCE_KINDS &&
                    source.externalIdHash == null &&
                    source.sourceAppIdentifier == null &&
                    hmac != null &&
                    SAFE_HMAC.matches(hmac) &&
                    source.id == "source:$LOCAL_FILES_CONNECTOR_ID:$hmac" &&
                    source.sensitivity == SensitivityLevel.HIGH &&
                    source.observedAt != null &&
                    source.modifiedAt == source.observedAt,
            )
            val event = requireNotNull(eventsById["event:$LOCAL_FILES_CONNECTOR_ID:$hmac"])
            val citation = requireNotNull(citationsById["citation:$LOCAL_FILES_CONNECTOR_ID:$hmac"])
            validateLocalFileRow(source, event, citation)
        }
    }

    private fun validateLocalFileRow(
        source: SourceReference,
        event: DerivedMemoryEvent,
        citation: MemoryCitation,
    ) {
        val observedAt = requireNotNull(source.observedAt)
        require(event.kind == DerivedMemoryEventKind.LOCAL_FILE_INDEX)
        require(event.sourceReferenceIds == listOf(source.id))
        require(event.citationIds == listOf(citation.id))
        require(event.entities.isEmpty() && event.endedAt == null)
        require(event.sensitivity == SensitivityLevel.HIGH)
        require(event.startedAt == observedAt && event.createdAt == observedAt)
        require(citation.sourceReferenceId == source.id && citation.derivedMemoryEventId == event.id)
        require(citation.observedAt == observedAt && citation.confidence == event.confidence)
        require(event.confidence in LOCAL_FILE_CONFIDENCE_LEVELS)

        val maxKeywords = if (source.sourceKind == SourceKind.PDF_PAGE) MAX_PDF_KEYWORDS else MAX_TEXT_KEYWORDS
        require(event.keywords.size <= maxKeywords)
        require(event.keywords.distinct().size == event.keywords.size)
        require(event.keywords.all(::isAllowedLocalKeyword))

        when (source.sourceKind) {
            SourceKind.LOCAL_FILE -> {
                require(event.labels == listOf("local-file", "text"))
                require(citation.label == "Local text document")
                requireCanonicalLocalSummary(event, "Local text file indexed with ")
            }

            SourceKind.MARKDOWN_NOTE -> {
                require(event.labels == listOf("local-file", "markdown"))
                require(citation.label == "Local Markdown document")
                requireCanonicalLocalSummary(event, "Local text file indexed with ")
            }

            SourceKind.PDF_PAGE -> {
                require(
                    event.labels == listOf("local-file", "pdf-page", "embedded-text") ||
                        event.labels == listOf("local-file", "pdf-page", "ocr"),
                )
                val page = PDF_PAGE_CITATION.matchEntire(citation.label)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                require(page != null && page in 1..MAX_PDF_PAGES_PER_DOCUMENT)
                requireCanonicalLocalSummary(event, "PDF page $page indexed with ")
            }

            else -> error("Unsupported Local Files source kind.")
        }
    }

    private fun requireCanonicalLocalSummary(event: DerivedMemoryEvent, prefix: String) {
        val match = Regex("${Regex.escape(prefix)}([0-9]{1,5}) non-empty line\\(s\\)\\..*")
            .matchEntire(event.summary)
        val lineCount = match?.groupValues?.get(1)?.toIntOrNull()
        require(lineCount != null && lineCount in 0..MAX_LOCAL_NON_EMPTY_LINES)
        val base = "$prefix$lineCount non-empty line(s)."
        val expected = if (event.keywords.isEmpty()) {
            "$base No stable keyword signals found."
        } else {
            "$base Signals: ${event.keywords.take(MAX_SUMMARY_KEYWORDS).joinToString(", ")}."
        }
        require(event.summary == expected)
    }

    private fun isAllowedLocalKeyword(keyword: String): Boolean {
        return keyword.length in MIN_LOCAL_KEYWORD_CHARS..MAX_LOCAL_KEYWORD_CHARS &&
            keyword.toByteArray(UTF_8).size <= MAX_LOCAL_KEYWORD_UTF8_BYTES &&
            keyword == keyword.lowercase() &&
            SAFE_LOCAL_KEYWORD.matches(keyword)
    }

    private fun requireDistinctIds(label: String, ids: List<String>) {
        ids.forEach(::requireId)
        require(ids.distinct().size == ids.size) { "Transfer contains duplicate $label IDs." }
    }

    private fun requireReferenceList(values: List<String>, maxItems: Int, requireNonEmpty: Boolean) {
        require(values.size <= maxItems)
        if (requireNonEmpty) require(values.isNotEmpty())
        values.forEach(::requireId)
        require(values.distinct().size == values.size)
    }

    private fun requireTextList(values: List<String>, maxItems: Int, maxValueBytes: Int) {
        require(values.size <= maxItems)
        values.forEach { value -> requireText(value, maxValueBytes) }
        require(values.distinct().size == values.size)
    }

    private fun requireId(value: String) = requireValue(value, TransferBounds.MAX_ID_BYTES)

    private fun requireConnectorId(value: String) = requireValue(value, TransferBounds.MAX_CONNECTOR_ID_BYTES)

    private fun requireValue(value: String, maxBytes: Int) {
        require(value.isNotBlank())
        require(isWellFormedUnicode(value))
        require(value.toByteArray(UTF_8).size <= maxBytes)
        require(value.none(Char::isISOControl))
    }

    private fun requireText(value: String, maxBytes: Int) {
        require(value.isNotBlank())
        require(isWellFormedUnicode(value))
        require(value.toByteArray(UTF_8).size <= maxBytes)
        require(value.none { character -> character.isISOControl() && character !in ALLOWED_TEXT_CONTROLS })
    }

    private fun isWellFormedUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            when {
                character.isLowSurrogate() -> return false
                character.isHighSurrogate() -> {
                    if (index >= value.length || !value[index].isLowSurrogate()) return false
                    index++
                }
            }
        }
        return true
    }

    private fun requireInstant(value: Instant) {
        require(value in MIN_INSTANT..MAX_INSTANT)
    }

    private fun requireDate(value: LocalDate) {
        require(value in MIN_DATE..MAX_DATE)
    }

    private fun connectorFromScopedId(prefix: String, id: String): String? {
        return trustedConnectorIds.firstOrNull { connectorId -> id.startsWith("$prefix:$connectorId:") }
    }

    private fun missingSourceIdentity(missing: MissingSource): List<String> {
        return listOf(
            missing.capability.name,
            missing.availability.name,
            missing.issueCode?.storageKey.orEmpty(),
            missing.connectorId.orEmpty(),
        )
    }

    private const val APPLICATION_ID = "ai.grayin"
    private const val MAX_STORE_SCHEMA_VERSION = 65_535
    private const val APP_USAGE_CONNECTOR_ID = "app_usage"
    private const val LOCAL_FILES_CONNECTOR_ID = "local_files"
    private const val MAX_PACKAGE_NAME_BYTES = 255
    private const val MAX_VISIT_COUNT = 1_000_000_000
    private const val MAX_LAUNCH_COUNT = 10_000_000
    private const val MAX_USAGE_DURATION_MINUTES = 5_256_000L
    private const val MAX_PLACE_RADIUS_METERS = 40_075_000.0
    private const val MAX_LOCAL_FILE_ROWS = 256
    private const val MAX_PDF_PAGES_PER_DOCUMENT = 64
    private const val MAX_TEXT_KEYWORDS = 16
    private const val MAX_PDF_KEYWORDS = 8
    private const val MAX_SUMMARY_KEYWORDS = 8
    private const val MIN_LOCAL_KEYWORD_CHARS = 3
    private const val MAX_LOCAL_KEYWORD_CHARS = 32
    private const val MAX_LOCAL_KEYWORD_UTF8_BYTES = 96
    private const val MAX_LOCAL_NON_EMPTY_LINES = 65_536
    private val MIN_INSTANT = Instant.parse("1900-01-01T00:00:00Z")
    private val MAX_INSTANT = Instant.parse("3000-01-01T00:00:00Z")
    private val MIN_DATE = LocalDate.of(1900, 1, 1)
    private val MAX_DATE = LocalDate.of(3000, 1, 1)
    private val ALLOWED_TEXT_CONTROLS = setOf('\n', '\r', '\t')
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    private val SAFE_HMAC = Regex("[a-f0-9]{64}")
    private val SAFE_LOCAL_KEYWORD = Regex("[\\p{L}\\p{Nd}]+")
    private val PDF_PAGE_CITATION = Regex("PDF page ([1-9][0-9]*)")
    private val LOCAL_FILE_CONFIDENCE_LEVELS = setOf(
        ConfidenceLevel.LOW,
        ConfidenceLevel.MEDIUM,
        ConfidenceLevel.HIGH,
    )
    private val LOCAL_FILE_SOURCE_KINDS = setOf(
        SourceKind.LOCAL_FILE,
        SourceKind.MARKDOWN_NOTE,
        SourceKind.PDF_PAGE,
    )
    private val SOURCE_KINDS_BY_CONNECTOR = mapOf(
        "location" to setOf(SourceKind.LOCATION),
        "photos" to setOf(SourceKind.PHOTO),
        "calendar" to setOf(SourceKind.CALENDAR),
        "notification" to setOf(SourceKind.NOTIFICATION),
        APP_USAGE_CONNECTOR_ID to setOf(SourceKind.APP_USAGE),
        LOCAL_FILES_CONNECTOR_ID to LOCAL_FILE_SOURCE_KINDS,
    )
    private val EVENT_KINDS_BY_CONNECTOR = mapOf(
        "location" to setOf(DerivedMemoryEventKind.PLACE_VISIT, DerivedMemoryEventKind.PLACE_CLUSTER),
        "photos" to setOf(DerivedMemoryEventKind.PHOTO_INDEX, DerivedMemoryEventKind.PHOTO_CLUSTER),
        "calendar" to setOf(DerivedMemoryEventKind.CALENDAR_EVENT),
        "notification" to setOf(
            DerivedMemoryEventKind.PAYMENT,
            DerivedMemoryEventKind.DELIVERY,
            DerivedMemoryEventKind.RESERVATION,
            DerivedMemoryEventKind.TRANSPORT,
            DerivedMemoryEventKind.INFERRED_CONTEXT,
        ),
        APP_USAGE_CONNECTOR_ID to setOf(DerivedMemoryEventKind.APP_USAGE),
        LOCAL_FILES_CONNECTOR_ID to setOf(DerivedMemoryEventKind.LOCAL_FILE_INDEX),
    )
}
